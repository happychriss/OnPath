"""Tiny S3 client (AWS Signature V4, Python stdlib only) for Cloudflare R2 – keeps the builder image small
(no aws-cli / boto3). Only what the builder needs: PUT an object, GET an object (status files).

R2: endpoint https://<account_id>[.<jurisdiction>].r2.cloudflarestorage.com (our buckets: jurisdiction "eu"), region "auto", path-style /<bucket>/<key>.
`LocalStore` writes the same keys into a folder instead (zo / tests: R2_LOCAL_DIR).
"""
import datetime
import hashlib
import hmac
import os
import shutil
import urllib.error
import urllib.parse
import urllib.request

EMPTY_SHA256 = hashlib.sha256(b"").hexdigest()


def _hmac(key, msg):
    return hmac.new(key, msg.encode(), hashlib.sha256).digest()


def signing_key(secret, date, region, service):
    k = _hmac(("AWS4" + secret).encode(), date)
    k = _hmac(k, region)
    k = _hmac(k, service)
    return _hmac(k, "aws4_request")


def sign(method, url, headers, payload_sha256, access_key, secret_key, region="auto", service="s3", now=None):
    """Return headers incl. Authorization for a SigV4 request. `headers` must not contain host/x-amz-*."""
    now = now or datetime.datetime.now(datetime.timezone.utc)
    amz_date = now.strftime("%Y%m%dT%H%M%SZ")
    date = amz_date[:8]
    u = urllib.parse.urlsplit(url)
    h = {k.lower(): str(v).strip() for k, v in headers.items()}
    h["host"] = u.netloc
    h["x-amz-date"] = amz_date
    h["x-amz-content-sha256"] = payload_sha256
    names = sorted(h)
    canonical_headers = "".join(f"{n}:{h[n]}\n" for n in names)
    signed_headers = ";".join(names)
    # path is already URI-encoded by the caller (quote(key, safe="/~")) – S3 does not double-encode
    query = "&".join(f"{urllib.parse.quote(k, safe='~')}={urllib.parse.quote(v, safe='~')}"
                     for k, v in sorted(urllib.parse.parse_qsl(u.query, keep_blank_values=True)))
    creq = "\n".join([method, u.path or "/", query, canonical_headers, signed_headers, payload_sha256])
    scope = f"{date}/{region}/{service}/aws4_request"
    sts = "\n".join(["AWS4-HMAC-SHA256", amz_date, scope, hashlib.sha256(creq.encode()).hexdigest()])
    sig = hmac.new(signing_key(secret_key, date, region, service), sts.encode(), hashlib.sha256).hexdigest()
    h["authorization"] = (f"AWS4-HMAC-SHA256 Credential={access_key}/{scope}, "
                          f"SignedHeaders={signed_headers}, Signature={sig}")
    del h["host"]  # urllib sets it
    return h


def file_sha256(path):
    d = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            d.update(b)
    return d.hexdigest()


class R2Store:
    def __init__(self, endpoint, bucket, access_key, secret_key, timeout=300):
        self.endpoint = endpoint.rstrip("/")
        self.bucket = bucket
        self.ak, self.sk = access_key, secret_key
        self.timeout = timeout

    def _url(self, key):
        return f"{self.endpoint}/{self.bucket}/{urllib.parse.quote(key, safe='/~')}"

    def _send(self, method, key, body=None, sha=EMPTY_SHA256, headers=None):
        h = sign(method, self._url(key), headers or {}, sha, self.ak, self.sk)
        req = urllib.request.Request(self._url(key), data=body, method=method, headers=h)
        return urllib.request.urlopen(req, timeout=self.timeout)

    def put_bytes(self, key, data, content_type="application/json", cache_control=None):
        h = {"content-type": content_type, "content-length": str(len(data))}
        if cache_control:
            h["cache-control"] = cache_control
        for attempt in range(3):
            try:
                with self._send("PUT", key, data, hashlib.sha256(data).hexdigest(), h):
                    return
            except (urllib.error.URLError, TimeoutError):
                if attempt == 2:
                    raise

    def put_file(self, key, path, content_type="application/octet-stream", cache_control=None):
        size = os.path.getsize(path)
        sha = file_sha256(path)
        h = {"content-type": content_type, "content-length": str(size)}
        if cache_control:
            h["cache-control"] = cache_control
        for attempt in range(3):
            try:
                with open(path, "rb") as f, self._send("PUT", key, f, sha, h):
                    return
            except (urllib.error.URLError, TimeoutError):
                if attempt == 2:
                    raise

    def get_bytes(self, key):
        """Object bytes, or None when it does not exist."""
        try:
            with self._send("GET", key) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            raise


class LocalStore:
    """Same interface, keys below a folder (zo with BUILDER_MODE=local, tests)."""

    def __init__(self, root):
        self.root = root

    def _p(self, key):
        p = os.path.normpath(os.path.join(self.root, key))
        if not p.startswith(os.path.normpath(self.root) + os.sep):
            raise ValueError(f"bad key {key}")
        os.makedirs(os.path.dirname(p), exist_ok=True)
        return p

    def put_bytes(self, key, data, content_type=None, cache_control=None):
        p = self._p(key)
        with open(p + ".tmp", "wb") as f:
            f.write(data)
        os.replace(p + ".tmp", p)

    def put_file(self, key, path, content_type=None, cache_control=None):
        p = self._p(key)
        shutil.copyfile(path, p + ".tmp")
        os.replace(p + ".tmp", p)

    def get_bytes(self, key):
        p = self._p(key)
        if not os.path.exists(p):
            return None
        with open(p, "rb") as f:
            return f.read()


def from_env(env=os.environ):
    """R2_LOCAL_DIR → LocalStore; else R2Store from R2_ENDPOINT (or R2_ACCOUNT_ID + R2_JURISDICTION), R2_BUCKET and
    the keys."""
    if env.get("R2_LOCAL_DIR"):
        return LocalStore(env["R2_LOCAL_DIR"])
    return R2Store(endpoint_from_env(env), env["R2_BUCKET"], env["R2_ACCESS_KEY_ID"].strip(),
                   env["R2_SECRET_ACCESS_KEY"].strip())


def endpoint_from_env(env=os.environ):
    """R2_ENDPOINT if set; else https://<R2_ACCOUNT_ID>.<R2_JURISDICTION>.r2.cloudflarestorage.com.
    R2_JURISDICTION defaults to "eu" (our buckets were created with EU jurisdiction – they are only reachable on the
    .eu. endpoint); "default" or "" (set explicitly) gives the plain endpoint for buckets without a jurisdiction."""
    if env.get("R2_ENDPOINT"):
        return env["R2_ENDPOINT"].rstrip("/")
    j = env.get("R2_JURISDICTION", "eu").strip().lower()
    host = env["R2_ACCOUNT_ID"].strip() + (f".{j}" if j and j != "default" else "")
    return f"https://{host}.r2.cloudflarestorage.com"
