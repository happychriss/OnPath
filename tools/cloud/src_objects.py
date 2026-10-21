"""Objects in the source bucket for the extract locks and freshness checks (docs/design/map-hosting.md §7f).

Parallel Cloud Run executions each mount the bucket with Cloud Storage FUSE. gcsfuse's O_EXCL is not atomic across
machines (a new file stays local until it is closed) and its stat cache may answer "missing" for up to a minute, so
two executions could both "win" a lock file or both miss a just-downloaded extract. On Cloud Run the builder
therefore asks the GCS JSON API directly: a lock is an object created with ifGenerationMatch=0 (exactly one
creator wins, HTTP 412 for the others), and existence/size/meta are read from the API, not the mount.

  FileObjects(root)        plain folder (zo, tests, no SRC_BUCKET): O_EXCL files – atomic on one machine
  GcsObjects(bucket)       GCS JSON API with the job's own service account (metadata server token);
                           cell-builder-<env> has roles/storage.objectUser on the bucket – no new permission
Names are relative to the bucket root / SRC_DIR, e.g. "osm/geofabrik/europe/austria.osm.pbf.lock".
"""
import calendar
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request

METADATA_TOKEN = "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token"
API = "https://storage.googleapis.com"


class FileObjects:
    def __init__(self, root):
        self.root = root

    def _p(self, name):
        return os.path.join(self.root, name)

    def create_if_absent(self, name, data):
        os.makedirs(os.path.dirname(self._p(name)), exist_ok=True)
        try:
            fd = os.open(self._p(name), os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o644)
        except FileExistsError:
            return False
        with os.fdopen(fd, "wb") as f:
            f.write(data)
        return True

    def write(self, name, data):
        with open(self._p(name), "wb") as f:
            f.write(data)

    def read(self, name):
        try:
            with open(self._p(name), "rb") as f:
                return f.read()
        except FileNotFoundError:
            return None

    def stat(self, name):
        try:
            st = os.stat(self._p(name))
        except FileNotFoundError:
            return None
        return {"size": st.st_size, "mtime": st.st_mtime}

    def delete(self, name):
        try:
            os.remove(self._p(name))
        except FileNotFoundError:  # expected: someone else removed it (stale lock taken over)
            pass


class GcsObjects:
    def __init__(self, bucket, opener=urllib.request.urlopen, now=time.time):
        self.bucket = bucket
        self.open = opener
        self.now = now
        self._token = None
        self._exp = 0

    def token(self):
        if self._token and self._exp - 60 > self.now():
            return self._token
        req = urllib.request.Request(METADATA_TOKEN, headers={"Metadata-Flavor": "Google"})
        with self.open(req, timeout=10) as r:
            j = json.load(r)
        self._token, self._exp = j["access_token"], self.now() + int(j.get("expires_in", 300))
        return self._token

    def _call(self, method, url, data=None):
        req = urllib.request.Request(url, data=data, method=method,
                                     headers={"Authorization": f"Bearer {self.token()}"})
        if data is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with self.open(req, timeout=30) as r:
                return r.status, r.read()
        except urllib.error.HTTPError as e:
            if e.code in (404, 412):
                return e.code, b""
            raise

    def _o(self, name):
        return f"{API}/storage/v1/b/{self.bucket}/o/{urllib.parse.quote(name, safe='')}"

    def create_if_absent(self, name, data):
        url = (f"{API}/upload/storage/v1/b/{self.bucket}/o?uploadType=media&ifGenerationMatch=0"
               f"&name={urllib.parse.quote(name, safe='')}")
        code, _ = self._call("POST", url, data)
        if code == 412:
            return False
        if code == 404:
            raise OSError(f"bucket {self.bucket} not found")
        return True

    def write(self, name, data):
        url = f"{API}/upload/storage/v1/b/{self.bucket}/o?uploadType=media&name={urllib.parse.quote(name, safe='')}"
        self._call("POST", url, data)

    def read(self, name):
        code, body = self._call("GET", self._o(name) + "?alt=media")
        return None if code == 404 else body

    def stat(self, name):
        code, body = self._call("GET", self._o(name) + "?fields=size,updated")
        if code == 404:
            return None
        j = json.loads(body)
        upd = j.get("updated", "")
        try:
            mtime = calendar.timegm(time.strptime(upd[:19], "%Y-%m-%dT%H:%M:%S"))
        except ValueError:
            mtime = 0  # expected: an odd timestamp → treated as old
        return {"size": int(j.get("size", 0)), "mtime": mtime}

    def delete(self, name):
        self._call("DELETE", self._o(name))


def from_env(env, src_dir):
    """GCS API on Cloud Run (SRC_BUCKET set and SRC_LOCKS != file), else the folder."""
    bucket = env.get("SRC_BUCKET")
    if bucket and env.get("SRC_LOCKS", "gcs") != "file":
        return GcsObjects(bucket)
    return FileObjects(src_dir)
