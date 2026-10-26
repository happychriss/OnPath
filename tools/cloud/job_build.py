#!/usr/bin/env python3
"""Entry point of the Cloud Run Job "onpath-cell-<env>" (docs/design/map-hosting.md §Builder).

One execution builds one or more cells, one after the other, with the unchanged tools/cells/build_cell.sh, then
uploads each finished version folder to R2 and reports per cell through a small status file in the same bucket:

    status/f<format>/<cell>.json   {"state": "building"|"ready"|"failed", "step", "progress", "execution", ...}

The doorman (Cloudflare Worker) polls the Cloud Run execution and reads these files – no callback URL, no shared
secret, nothing to reach inside the job.

Source data (map-hosting.md §Source data): the OSM extracts a cell is cut from are chosen automatically from the
Geofabrik index (osm_sources.py; pinned extracts in sources.json first – none today) and kept in the source bucket:
    osm/<pinned id>.osm.pbf                        pinned (sources.json, if any)
    osm/geofabrik/<path>.osm.pbf + .meta.json      e.g. osm/geofabrik/europe/austria.osm.pbf (download date, size)
    osm/geofabrik/index-v1.json                    the index, refreshed weekly
An extract older than max_age_days is downloaded again – straight into the bucket mount (no copy in memory, a
<path>.lock file keeps two jobs from fetching the same one) – and build_cell.sh reads it from there.

Env (set by the job definition tools/cloud/job.yaml; CELLS/FORMAT come per execution from the doorman's overrides):
  CELLS="10-543-361 10-544-361"   cell ids (also 10/543/361), space or comma separated
  FORMAT=2                        cell format (build_cell.sh writes cells/f<FORMAT>/…)
  SRC_DIR=/src                    source bucket (Cloud Storage volume mount): osm/…, planetiler-sources/
  SOURCES_JSON=<this dir>/sources.json   pinned extracts + limits (max_age_days, max_extract_mb, …)
  WORK=/tmp/onpath/work  DATA_ROOT=/tmp/onpath/store   local (in-memory on Cloud Run) work + output folders
  R2_ACCOUNT_ID (+ R2_JURISDICTION, default eu) or R2_ENDPOINT, R2_BUCKET, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY   upload target
  R2_LOCAL_DIR=/data/store        instead of R2: write into this folder (zo, tests)
  BUILD_CELL=<repo>/tools/cells/build_cell.sh   BUFFER_KM=5
  CLOUD_RUN_EXECUTION / CLOUD_RUN_TASK_ATTEMPT   set by Cloud Run

Exit code 0 when every cell got a final status (ready or failed) – a failed cell is the doorman's business (it
retries up to 3 times). Non-zero only when status files could not be written (Cloud Run then retries the task once).
"""
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "cells"))
import osm_sources  # noqa: E402
import r2  # noqa: E402
import src_objects  # noqa: E402
from cells import buffered_bbox, cell_bbox  # noqa: E402

NO_SOURCE = "No map data exists for this area (open sea)"
TOO_LARGE = "The source data for this area is too large for the map server yet"
UNAVAILABLE = "Source data could not be downloaded"
INDEX_MAX_AGE_S = 7 * 86400
LOCK_STALE_S = 45 * 60
MIN_SOURCE_BYTES = 100_000
# step names of build_cell.sh (format 2) -> rough progress for the app's card: share of the time before each step in
# the first cloud build with raw cuts (Innsbruck 10/544/359, two extracts, 366 s; map-hosting.md §7e); unknown steps
# keep the last value. "download" = a new extract is being fetched (its time is reported separately as
# source_eta_s, the doorman adds it to the ETA).
STEP_PROGRESS = {"source": 0.0, "download": 0.0, "cut": 0.01, "merge": 0.23, "renumber": 0.24, "base": 0.245,
                 "base-own": 0.47, "base-trim": 0.47, "overlays": 0.48, "overlays-tiles": 0.58, "overlays-own": 0.63,
                 "pois": 0.63, "pois-ids": 0.70, "clip": 0.70, "addresses": 0.70, "terrain": 0.76, "contours": 0.94,
                 "terrain-own": 0.94, "contours-own": 0.97, "manifest": 0.97, "upload": 0.98}
# region packages (z7 ids, tools/cells/build_package.sh, map-cells.md §20): cut + Planetiler are most of the time
PACKAGE_STEP_PROGRESS = {"source": 0.0, "download": 0.0, "cut": 0.01, "merge": 0.35, "renumber": 0.4, "base": 0.45,
                         "base-own": 0.75, "base-trim": 0.76, "routes-filter": 0.78, "routes": 0.8, "routes-tiles": 0.85,
                         "routes-own": 0.88, "shade": 0.9, "manifest": 0.97, "upload": 0.98}
PACKAGE_Z = 7
PACKAGE_BUFFER_KM = 10  # build_package.sh PKG_BUFFER_KM: the sources must cover the package + this
# a package (~200 km) touches many Geofabrik leaves – Düsseldorf's 7/66/42: 15 (NL provinces, NRW districts, Belgium,
# Lower Saxony …); each is cut on its own (memory of one cut), so more extracts only cost time
PACKAGE_MAX_EXTRACTS = 20
IMMUTABLE = "public, max-age=31536000, immutable"


class SourceUnavailable(Exception):
    pass


class SourceTooLarge(Exception):
    pass


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def parse_cells(text):
    out = []
    for tok in re.split(r"[\s,;]+", (text or "").strip()):
        if not tok:
            continue
        m = re.fullmatch(r"(\d+)[-/](\d+)[-/](\d+)", tok)
        if not m:
            raise ValueError(f"bad cell id {tok!r}")
        z, x, y = map(int, m.groups())
        if z not in (10, PACKAGE_Z) or not (0 <= x < 2 ** z and 0 <= y < 2 ** z):
            raise ValueError(f"bad cell id {tok!r} (only z10 cells and z7 region packages)")
        if (z, x, y) not in out:
            out.append((z, x, y))
    return out


def content_type(name):
    if name.endswith(".json"):
        return "application/json"
    if name.endswith(".geojson"):
        return "application/geo+json"
    return "application/octet-stream"


class Builder:
    def __init__(self, env=os.environ, store=None, run=subprocess.Popen, download=None, sleep=time.sleep,
                 objects=None):
        self.env = env
        self.fmt = int(env.get("FORMAT", "2"))
        self.src_dir = env.get("SRC_DIR", "/src")
        self.work = env.get("WORK", "/tmp/onpath/work")
        self.data_root = env.get("DATA_ROOT", "/tmp/onpath/store")
        self.build_cell = env.get("BUILD_CELL", os.path.join(HERE, "..", "cells", "build_cell.sh"))
        self.buffer_km = float(env.get("BUFFER_KM", "5"))
        with open(env.get("SOURCES_JSON", os.path.join(HERE, "sources.json"))) as f:
            self.sources = json.load(f)
        self.max_age_s = float(self.sources.get("max_age_days", 30)) * 86400
        self.max_extract_mb = int(self.sources.get("max_extract_mb", 2500))
        self.index_url = self.sources.get("index_url", osm_sources.INDEX_URL)
        self.execution = env.get("CLOUD_RUN_EXECUTION") or f"local-{int(time.time())}"
        self.store = store or r2.from_env(env)
        self.popen = run
        self.download = download or self._download
        self.sleep = sleep
        self._regions = None
        self.checked = set()
        # locks + freshness: GCS API on Cloud Run (atomic across parallel executions), else the folder
        self.objects = objects or src_objects.from_env(env, self.src_dir)
        self.dl = {"s": 0.0, "mb": 0.0}
        self.waited_for_source = False

    # ------------------------------------------------------------------ status
    def status_key(self, cid):
        return f"status/f{self.fmt}/{cid}.json"

    def status(self, cid, state, **kw):
        body = {"cell": cid, "format": self.fmt, "state": state, "execution": self.execution,
                "updated": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), **kw}
        self.store.put_bytes(self.status_key(cid), json.dumps(body).encode(), "application/json", "no-store")

    def already_ready(self, cid):
        """Task retry: a cell this execution already published is not built again."""
        raw = self.store.get_bytes(self.status_key(cid))
        if not raw:
            return False
        s = json.loads(raw)
        return s.get("state") == "ready" and s.get("execution") == self.execution

    # ------------------------------------------------------------------ sources
    def _download(self, url, dest, progress=None):
        """Stream url into dest (on Cloud Run straight into the bucket mount – no copy in memory).
        progress(done_bytes, total_bytes, bytes_per_s) every ~10 s. Refuses files above max_extract_mb."""
        last_err = None
        for attempt in range(3):
            try:
                req = urllib.request.Request(url, headers={"User-Agent": "OnPath-cell-builder (github.com/happychriss/OnPath)"})
                with urllib.request.urlopen(req, timeout=60) as r:
                    total = int(r.headers.get("Content-Length") or 0)
                    if total > self.max_extract_mb * 1_000_000:
                        raise SourceTooLarge(f"{url}: {total // 1_000_000} MB > {self.max_extract_mb} MB")
                    t0 = last = time.time()
                    done = 0
                    with open(dest, "wb") as f:
                        while True:
                            b = r.read(1 << 20)
                            if not b:
                                break
                            f.write(b)
                            done += len(b)
                            now = time.time()
                            if progress and now - last >= 10:
                                last = now
                                progress(done, total, done / max(now - t0, 0.001))
                    if total and done != total:
                        raise OSError(f"{url}: got {done} of {total} bytes")
                    return
            except SourceTooLarge:
                raise
            except Exception as e:  # noqa: BLE001 – retried, then raised
                last_err = e
                log(f"download {url} failed (attempt {attempt + 1}): {e}")
                self.sleep(10 * (attempt + 1))
        raise last_err

    def regions(self):
        """Geofabrik index (osm_sources), cached in the bucket, refreshed weekly; a stale copy beats none."""
        if self._regions is not None:
            return self._regions
        path = os.path.join(self.src_dir, "osm", "geofabrik", "index-v1.json")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        if not os.path.exists(path) or time.time() - os.path.getmtime(path) > INDEX_MAX_AGE_S:
            try:
                log(f"geofabrik index: downloading {self.index_url}")
                self.download(self.index_url, path + ".part")
                osm_sources.load_index(path + ".part")  # parses → not an error page
                os.replace(path + ".part", path)
            except Exception as e:  # noqa: BLE001
                if not os.path.exists(path):
                    raise SourceUnavailable(f"Geofabrik index could not be loaded: {e}") from e
                log(f"WARN: geofabrik index not refreshed ({e}), using the old one")
        self._regions = osm_sources.load_index(path)
        return self._regions

    def choose_sources(self, z, x, y):
        """[{id, url, path}] the cell is cut from ([] = open sea). Pinned sources (sources.json) first."""
        pinned = osm_sources.pick_pinned(self.sources, cell_bbox(z, x, y))
        if pinned:
            return [dict(pinned, path=os.path.join(self.src_dir, "osm", f"{pinned['id']}.osm.pbf"))]
        box = buffered_bbox(cell_bbox(z, x, y), PACKAGE_BUFFER_KM if z == PACKAGE_Z else self.buffer_km)
        out = []
        for r in osm_sources.select(self.regions(), box,
                                    PACKAGE_MAX_EXTRACTS if z == PACKAGE_Z else osm_sources.MAX_EXTRACTS):
            rel = re.sub(r"^https?://[^/]+/", "", r.url)
            rel = re.sub(r"(-latest)?\.osm\.pbf$", "", rel)
            out.append({"id": r.id, "url": r.url,
                        "path": os.path.join(self.src_dir, "osm", "geofabrik", f"{rel}.osm.pbf")})
        return out

    @staticmethod
    def _meta_path(path):
        return path[:-len(".osm.pbf")] + ".meta.json"

    def _rel(self, path):
        return os.path.relpath(path, self.src_dir)

    def _fresh(self, path):
        """Extract in the bucket, big enough and younger than max_age_days – asked through self.objects (the GCS API
        on Cloud Run: the mount's stat cache may not see another execution's download yet)."""
        st = self.objects.stat(self._rel(path))
        if not st or st["size"] < MIN_SOURCE_BYTES:
            return False
        try:
            t = json.loads(self.objects.read(self._rel(self._meta_path(path))) or b"null")
            t = t.get("downloaded") if isinstance(t, dict) else None
        except ValueError:
            t = None  # expected: pinned extracts saved before meta files existed → object time
        return time.time() - (t or st["mtime"]) < self.max_age_s

    def _lock_info(self, lock):
        try:
            info = json.loads(self.objects.read(lock) or b"{}")
            return info if isinstance(info, dict) else {}
        except ValueError:
            return {}  # expected: half-written lock – judged by its age below

    def _lock(self, path, cid):
        """<path>.lock, created atomically (self.objects: GCS ifGenerationMatch=0 on Cloud Run, O_EXCL locally): one
        downloader per extract, also across parallel executions. Waits while another job holds it (its download ETA
        is in the lock); takes over a lock older than LOCK_STALE_S. True = we hold it; False = it went away (the
        other job is done – check again)."""
        lock = self._rel(path) + ".lock"
        waited = 0
        while True:
            body = json.dumps({"execution": self.execution, "t": int(time.time())}).encode()
            if self.objects.create_if_absent(lock, body):
                return True
            info = self._lock_info(lock)
            st = self.objects.stat(lock)
            if st is None:
                continue  # expected: released between our create and read – try again
            age = time.time() - info.get("t", st["mtime"])
            if age > LOCK_STALE_S or info.get("execution") == self.execution:
                log(f"source lock {lock}: stale ({int(age)} s) – taking over")
                self.objects.delete(lock)
                continue
            eta = info.get("eta_at")
            eta_s = max(15, int(eta - time.time())) if isinstance(eta, (int, float)) else 120
            if waited == 0:
                log(f"source {os.path.basename(path)}: another build ({info.get('execution')}) is downloading it, waiting")
            self.waited_for_source = True
            self.status(cid, "building", step="download", progress=0.0, source_eta_s=eta_s)
            self.sleep(20)
            waited += 20
            if self.objects.stat(lock) is None:
                return False

    def ensure_source(self, src, cid):
        """Path of a fresh copy of the extract in the source bucket (downloaded into it when missing or older than
        max_age_days). Raises SourceUnavailable / SourceTooLarge. Adds the download time and size to self.dl."""
        path = src["path"]
        if path in self.checked:
            return path
        os.makedirs(os.path.dirname(path), exist_ok=True)
        lock = self._rel(path) + ".lock"
        while not self._fresh(path):
            if not self._lock(path, cid):
                continue
            try:
                if self._fresh(path):  # another job finished it while we waited for the lock
                    break
                log(f"source {src['id']}: downloading {src['url']}")
                t0 = time.time()

                def progress(done, total, rate):
                    eta = int((total - done) / rate) + 30 if total and rate > 0 else 300
                    self.status(cid, "building", step="download", progress=0.0, source_eta_s=eta,
                                source={"id": src["id"], "mb": round(done / 1e6), "total_mb": round(total / 1e6)})
                    try:  # waiting executions read our ETA from the lock
                        self.objects.write(lock, json.dumps({"execution": self.execution, "t": int(t0),
                                                             "eta_at": int(time.time() + eta)}).encode())
                    except OSError as e:
                        log(f"WARN: lock progress not written: {e}")
                self.status(cid, "building", step="download", progress=0.0, source_eta_s=300, source={"id": src["id"]})
                if self.download == self._download:
                    self.download(src["url"], path + ".part", progress=progress)
                else:
                    self.download(src["url"], path + ".part")
                size = os.path.getsize(path + ".part")
                if size < MIN_SOURCE_BYTES:
                    raise SourceUnavailable(f"source {src['id']} looks broken ({size} bytes)")
                os.replace(path + ".part", path)
                meta = {"id": src["id"], "url": src["url"], "downloaded": int(time.time()), "size": size,
                        "seconds": round(time.time() - t0), "execution": self.execution}
                with open(self._meta_path(path), "w") as f:
                    json.dump(meta, f)
                self.dl["s"] += time.time() - t0
                self.dl["mb"] += size / 1e6
                log(f"source {src['id']}: {size // 1_000_000} MB in {meta['seconds']} s")
            finally:
                self.objects.delete(lock)
            break
        self.checked.add(path)
        return path

    def planetiler_sources_in(self):
        src = os.path.join(self.src_dir, "planetiler-sources")
        dst = os.path.join(self.work, "planetiler-sources")
        os.makedirs(dst, exist_ok=True)
        if os.path.isdir(src):
            for n in os.listdir(src):
                if os.path.isfile(os.path.join(src, n)) and not n.endswith(".tmp"):
                    shutil.copyfile(os.path.join(src, n), os.path.join(dst, n))
        return dst

    def planetiler_sources_out(self):
        """Files Planetiler downloaded (first run, or newer upstream) go back to the bucket."""
        src = os.path.join(self.work, "planetiler-sources")
        dst = os.path.join(self.src_dir, "planetiler-sources")
        if not os.path.isdir(src):
            return
        try:
            os.makedirs(dst, exist_ok=True)
            for n in os.listdir(src):
                a, b = os.path.join(src, n), os.path.join(dst, n)
                if os.path.isfile(a) and (not os.path.exists(b) or os.path.getsize(a) != os.path.getsize(b)):
                    log(f"planetiler source {n}: saving to the bucket")
                    shutil.copyfile(a, b + ".tmp")
                    os.replace(b + ".tmp", b)
        except OSError as e:
            log(f"WARN: could not save planetiler sources: {e}")

    # ------------------------------------------------------------------ one cell
    def run_build(self, z, x, y, src_pbfs, cid, src_meta=None):
        env = dict(self.env, SRC_PBF=" ".join(src_pbfs), DATA_ROOT=self.data_root, WORK=self.work,
                   SRC_META=json.dumps([{"id": m.get("id"), "url": m.get("url")} for m in (src_meta or [])]),
                   FORMAT=str(self.fmt), PLANETILER_SOURCES=os.path.join(self.work, "planetiler-sources"),
                   KEEP_WORK="0", BUFFER_KM=str(self.buffer_km).rstrip("0").rstrip("."))
        p = self.popen(["bash", self.build_cell, str(z), str(x), str(y)], env=env, stdout=subprocess.PIPE,
                       stderr=subprocess.STDOUT, text=True, bufsize=1)
        tail, progress = [], 0.0
        table = PACKAGE_STEP_PROGRESS if z == PACKAGE_Z else STEP_PROGRESS
        for line in p.stdout:
            sys.stdout.write(line)
            tail = (tail + [line.rstrip()])[-30:]
            m = re.search(r"==> (\S+)", line)
            if m:
                step = m.group(1)
                progress = table.get(step, progress)
                self.status(cid, "building", step=step, progress=progress)
        rc = p.wait()
        return rc, tail

    def publish(self, z, x, y, cid):
        dest = os.path.join(self.data_root, "cells", f"f{self.fmt}", str(z), str(x), str(y))
        with open(os.path.join(dest, "current.json")) as f:
            version = json.load(f)["version"]
        vdir = os.path.join(dest, version)
        with open(os.path.join(vdir, "manifest.json")) as f:
            manifest = json.load(f)
        for e in manifest["files"]:
            p = os.path.join(vdir, e["name"])
            if os.path.getsize(p) != e["size"]:
                raise RuntimeError(f"{e['name']}: size differs from the manifest")
        prefix = f"cells/f{self.fmt}/{z}/{x}/{y}"
        # every file below the version folder (format 2 has addresses/<cell>.sqlite in a sub folder)
        names = sorted(os.path.relpath(os.path.join(r, n), vdir) for r, _, ns in os.walk(vdir) for n in ns)
        names = [n for n in names if n != "manifest.json" and not os.path.basename(n).startswith(".")]
        for n in names:  # files first, manifest after them, current.json last (readers never see half a cell)
            self.store.put_file(f"{prefix}/{version}/{n}", os.path.join(vdir, n), content_type(n), IMMUTABLE)
        self.store.put_file(f"{prefix}/{version}/manifest.json", os.path.join(vdir, "manifest.json"),
                            "application/json", IMMUTABLE)
        self.store.put_bytes(f"{prefix}/current.json", json.dumps({"version": version}).encode(),
                             "application/json", "no-cache")
        shutil.rmtree(vdir, ignore_errors=True)  # Cloud Run's disk is memory
        return {"version": version, "osm_date": manifest.get("osm_date"), "size": manifest.get("total_size")}

    def build_one(self, z, x, y):
        cid = f"{z}-{x}-{y}"
        if self.already_ready(cid):
            log(f"cell {cid}: already published by this execution")
            return True
        self.status(cid, "building", step="source", progress=0.0)
        self.dl = {"s": 0.0, "mb": 0.0}
        self.waited_for_source = False
        t_src = time.time()
        try:
            sources = self.choose_sources(z, x, y)
            if not sources:
                self.status(cid, "failed", reason=NO_SOURCE, permanent=True)
                log(f"cell {cid}: {NO_SOURCE}")
                return False
            log(f"cell {cid}: sources {', '.join(s['id'] for s in sources)}")
            pbfs = [self.ensure_source(s, cid) for s in sources]
        except SourceTooLarge as e:
            log(f"cell {cid}: {e}")
            self.status(cid, "failed", reason=TOO_LARGE, permanent=True, detail=str(e)[:500])
            return False
        except Exception as e:  # noqa: BLE001 – reported as a plain status, details in the log
            log(f"cell {cid}: source failed: {e}")
            self.status(cid, "failed", reason=UNAVAILABLE, detail=str(e)[:500])
            return False
        t_build = time.time()
        rc, tail = self.run_build(z, x, y, pbfs, cid, sources)
        if rc != 0:
            step = next((m.group(1) for line in reversed(tail) for m in [re.search(r"step (\S+) failed", line)] if m),
                        None)
            reason = f"Build step '{step}' failed" if step else "Build failed"
            self.status(cid, "failed", reason=reason, detail="\n".join(tail[-12:])[-1500:])
            return False
        self.status(cid, "building", step="upload", progress=STEP_PROGRESS["upload"])
        try:
            info = self.publish(z, x, y, cid)
        except Exception as e:  # noqa: BLE001
            log(f"cell {cid}: upload failed: {e}")
            self.status(cid, "failed", reason="Upload failed", detail=str(e)[:500])
            return False
        info["sources"] = [s["id"] for s in sources]
        # measured times for the doorman's ETA (rolling averages, map-hosting.md §7f): build + upload without the
        # extract download / lock wait; the download itself (rate = MB / s)
        info.update(build_s=round(time.time() - t_build, 1), source_s=round(t_build - t_src, 1),
                    new_source=bool(self.dl["mb"] or self.waited_for_source),
                    download_s=round(self.dl["s"], 1), download_mb=round(self.dl["mb"], 1))
        self.status(cid, "ready", progress=1.0, **info)
        log(f"cell {cid}: ready ({info['version']}, {info['size']} bytes)")
        return True

    def main(self, cells):
        self.planetiler_sources_in()
        ok = 0
        for z, x, y in cells:
            ok += self.build_one(z, x, y)
        self.planetiler_sources_out()
        log(f"done: {ok}/{len(cells)} cells ready (execution {self.execution})")
        return 0


def main():
    env = os.environ
    try:
        cells = parse_cells(" ".join(sys.argv[1:]) or env.get("CELLS", ""))
    except ValueError as e:
        log(f"ERROR: {e}")
        return 2
    if not cells:
        log("ERROR: no cells given (CELLS env or arguments)")
        return 2
    os.makedirs(env.get("WORK", "/tmp/onpath/work"), exist_ok=True)
    try:
        return Builder(env).main(cells)
    except (OSError, urllib.request.URLError) as e:  # status files not writable etc. → let Cloud Run retry once
        log(f"ERROR: {e}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
