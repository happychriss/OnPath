#!/usr/bin/env python3
"""Entry point of the Cloud Run Job "onpath-cell-<env>" (docs/design/map-hosting.md §Builder).

One execution builds one or more cells, one after the other, with the unchanged tools/cells/build_cell.sh, then
uploads each finished version folder to R2 and reports per cell through a small status file in the same bucket:

    status/f<format>/<cell>.json   {"state": "building"|"ready"|"failed", "step", "progress", "execution", ...}

The doorman (Cloudflare Worker) polls the Cloud Run execution and reads these files – no callback URL, no shared
secret, nothing to reach inside the job.

Env (set by the job definition tools/cloud/job.yaml; CELLS/FORMAT come per execution from the doorman's overrides):
  CELLS="10-543-361 10-544-361"   cell ids (also 10/543/361), space or comma separated
  FORMAT=2                        cell format (build_cell.sh writes cells/f<FORMAT>/…)
  SRC_DIR=/src                    source bucket (Cloud Storage volume mount): osm/<id>.osm.pbf, planetiler-sources/
  SOURCES_JSON=<this dir>/sources.json   which extract covers which area
  WORK=/tmp/onpath/work  DATA_ROOT=/tmp/onpath/store   local (in-memory on Cloud Run) work + output folders
  R2_ACCOUNT_ID (+ R2_JURISDICTION, default eu) or R2_ENDPOINT, R2_BUCKET, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY   upload target
  R2_LOCAL_DIR=/data/store        instead of R2: write into this folder (zo, tests)
  BUILD_CELL=<repo>/tools/cells/build_cell.sh
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
import r2  # noqa: E402
from cells import cell_bbox  # noqa: E402

NO_SOURCE = "Source data not available for this area yet"
# progress at the start of each build_cell.sh step (from the phase-1 timings, map-cells.md §17); unknown steps
# (format 2) keep the last value
# step names of build_cell.sh (format 1 and 2) -> rough progress for the app's card
STEP_PROGRESS = {"source": 0.0, "renumber": 0.02, "cut": 0.06, "base": 0.10, "base-own": 0.45, "base-trim": 0.47,
                 "overlays": 0.50, "overlays-tiles": 0.58, "overlays-own": 0.61,
                 "pois": 0.62, "pois-ids": 0.70, "clip": 0.71, "addresses": 0.72, "terrain": 0.76, "contours": 0.80,
                 "terrain-own": 0.93, "contours-own": 0.95, "manifest": 0.97, "upload": 0.98}
IMMUTABLE = "public, max-age=31536000, immutable"


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
        if z != 10 or not (0 <= x < 1024 and 0 <= y < 1024):
            raise ValueError(f"bad cell id {tok!r} (only z10 cells)")
        if (z, x, y) not in out:
            out.append((z, x, y))
    return out


def inside(b, r):
    return r[0] <= b[0] and r[1] <= b[1] and b[2] <= r[2] and b[3] <= r[3]


def pick_source(sources, z, x, y):
    """First source whose cover rectangles contain the whole cell (the list is ordered small → large)."""
    b = cell_bbox(z, x, y)
    for s in sources.get("sources", []):
        if any(inside(b, r) for r in s.get("cover", [])):
            return s
    return None


def content_type(name):
    if name.endswith(".json"):
        return "application/json"
    if name.endswith(".geojson"):
        return "application/geo+json"
    return "application/octet-stream"


class Builder:
    def __init__(self, env=os.environ, store=None, run=subprocess.Popen, download=None):
        self.env = env
        self.fmt = int(env.get("FORMAT", "2"))
        self.src_dir = env.get("SRC_DIR", "/src")
        self.work = env.get("WORK", "/tmp/onpath/work")
        self.data_root = env.get("DATA_ROOT", "/tmp/onpath/store")
        self.build_cell = env.get("BUILD_CELL", os.path.join(HERE, "..", "cells", "build_cell.sh"))
        with open(env.get("SOURCES_JSON", os.path.join(HERE, "sources.json"))) as f:
            self.sources = json.load(f)
        self.max_age_s = float(self.sources.get("max_age_days", 30)) * 86400
        self.execution = env.get("CLOUD_RUN_EXECUTION") or f"local-{int(time.time())}"
        self.store = store or r2.from_env(env)
        self.popen = run
        self.download = download or self._download
        self.local_sources = {}

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
    def _download(self, url, dest):
        tmp = dest + ".part"
        subprocess.run(["curl", "-fsSL", "--retry", "3", "--retry-delay", "10", "-o", tmp, url], check=True)
        os.replace(tmp, dest)

    def ensure_source(self, src):
        """Local copy of the source extract: from the bucket when fresh, else downloaded (and saved back)."""
        sid = src["id"]
        if sid in self.local_sources:
            return self.local_sources[sid]
        local = os.path.join(self.work, "osm", f"{sid}.osm.pbf")
        os.makedirs(os.path.dirname(local), exist_ok=True)
        remote = os.path.join(self.src_dir, "osm", f"{sid}.osm.pbf")
        fresh = os.path.exists(remote) and time.time() - os.path.getmtime(remote) < self.max_age_s
        if fresh:
            log(f"source {sid}: from bucket")
            shutil.copyfile(remote, local)
        else:
            log(f"source {sid}: downloading {src['url']}")
            self.download(src["url"], local)
            try:  # best effort: the next execution can use it (two environments may race – same content)
                os.makedirs(os.path.dirname(remote), exist_ok=True)
                shutil.copyfile(local, remote + ".tmp")
                os.replace(remote + ".tmp", remote)
            except OSError as e:
                log(f"WARN: could not save source to the bucket: {e}")
        if os.path.getsize(local) < 1_000_000:
            raise RuntimeError(f"source {sid} looks broken ({os.path.getsize(local)} bytes)")
        self.local_sources[sid] = local
        return local

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
    def run_build(self, z, x, y, src_pbf, cid):
        env = dict(self.env, SRC_PBF=src_pbf, DATA_ROOT=self.data_root, WORK=self.work, FORMAT=str(self.fmt),
                   PLANETILER_SOURCES=os.path.join(self.work, "planetiler-sources"), KEEP_WORK="0")
        p = self.popen(["bash", self.build_cell, str(z), str(x), str(y)], env=env, stdout=subprocess.PIPE,
                       stderr=subprocess.STDOUT, text=True, bufsize=1)
        tail, progress = [], 0.0
        for line in p.stdout:
            sys.stdout.write(line)
            tail = (tail + [line.rstrip()])[-30:]
            m = re.search(r"==> (\S+)", line)
            if m:
                step = m.group(1)
                progress = STEP_PROGRESS.get(step, progress)
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
        src = pick_source(self.sources, z, x, y)
        if src is None:
            self.status(cid, "failed", reason=NO_SOURCE, permanent=True)
            log(f"cell {cid}: {NO_SOURCE}")
            return False
        self.status(cid, "building", step="source", progress=0.0)
        try:
            pbf = self.ensure_source(src)
        except Exception as e:  # noqa: BLE001 – reported as a plain status, details in the log
            log(f"cell {cid}: source failed: {e}")
            self.status(cid, "failed", reason="Source data could not be downloaded", detail=str(e)[:500])
            return False
        rc, tail = self.run_build(z, x, y, pbf, cid)
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
