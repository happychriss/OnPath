"""Builds queued regions one at a time (systemd: onpath-worker.service) and refreshes / cleans up (onpath-refresh.timer).

  python3 -m onpath_maps.worker run       # loop forever: take the oldest queued region, build, publish
  python3 -m onpath_maps.worker refresh   # monthly: re-queue regions still in use, free disk if needed
  python3 -m onpath_maps.worker once      # build one queued region and exit (tests)

Per build: Geofabrik leaf extracts that cover the bbox (cached, refreshed after 6 days, md5-checked, all of the same
day) → `osmium extract` to the bbox (+ margin) → `osmium merge` → tools/build_region.sh (OSM_FILE, --no-segments)
→ the zip is published under PUBLIC/dl/<name>-<token>/ (atomic swap, the old zip stays until the new one is there).
"""
import glob
import json
import os
import shutil
import subprocess
import sys
import time

from . import regions, sources

HOME = os.environ.get("ONPATH_HOME", "/srv/onpath")
REPO = os.environ.get("ONPATH_REPO", "/opt/onpath/repo")
STATE = os.path.join(HOME, "state")
WORK = os.path.join(HOME, "work")
BUILD = os.path.join(HOME, "build")
PUBLIC = os.path.join(HOME, "public")
LOGS = os.path.join(HOME, "logs")
GEOFABRIK_INDEX = "https://download.geofabrik.de/index-v1.json"
OSM_MAX_AGE_S = 6 * 86400
INDEX_MAX_AGE_S = 7 * 86400
MARGIN_DEG = 0.05
REFRESH_AFTER_S = 25 * 86400       # re-build regions older than this …
KEEP_IN_USE_S = 180 * 86400        # … that were asked for in the last 6 months
MIN_FREE_BYTES = 15 * 1024 ** 3    # below this, the least recently asked-for regions are deleted

store = regions.Store(STATE)


class BuildError(Exception):
    pass


def log_path(name):
    return os.path.join(LOGS, name + ".log")


def _log(f, msg):
    f.write(time.strftime("[%H:%M:%S] ") + msg + "\n")
    f.flush()


def run(cmd, logf, env=None, on_line=None):
    """Runs cmd, streams output to the log, raises BuildError on failure."""
    _log(logf, "$ " + " ".join(cmd))
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=env, bufsize=1)
    for line in p.stdout:
        logf.write(line)
        if on_line:
            on_line(line.rstrip("\n"))
    logf.flush()
    if p.wait() != 0:
        raise BuildError(f"{os.path.basename(cmd[0])} ist fehlgeschlagen (Exit {p.returncode}).")


def fetch(url, dest, max_age_s, logf, md5=False):
    """Download with resume to dest unless it is younger than max_age_s. Optionally checks <url>.md5."""
    if os.path.exists(dest) and time.time() - os.path.getmtime(dest) < max_age_s:
        _log(logf, f"cached: {os.path.basename(dest)}")
        return dest
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    part = dest + ".part"
    run(["curl", "-fL", "-sS", "--retry", "5", "--retry-delay", "10", "--connect-timeout", "30", "-C", "-",
         "-o", part, url], logf)
    if md5:
        want = subprocess.run(["curl", "-fsSL", "--retry", "3", url + ".md5"], capture_output=True, text=True)
        if want.returncode == 0 and want.stdout.split():
            got = subprocess.run(["md5sum", part], capture_output=True, text=True).stdout.split()[0]
            if got != want.stdout.split()[0]:
                os.remove(part)
                raise BuildError(f"Prüfsumme falsch: {os.path.basename(dest)} (wird beim nächsten Versuch neu geladen).")
    os.replace(part, dest)
    return dest


def osm_timestamp(path):
    r = subprocess.run(["osmium", "fileinfo", "-g", "header.option.osmosis_replication_timestamp", path],
                       capture_output=True, text=True)
    return r.stdout.strip() if r.returncode == 0 else ""


def osm_sources(r, logf):
    """Local .osm.pbf files covering the region (test override: ONPATH_OSM_FILE)."""
    if os.environ.get("ONPATH_OSM_FILE"):
        return [os.environ["ONPATH_OSM_FILE"]]
    index = fetch(GEOFABRIK_INDEX, os.path.join(WORK, "geofabrik", "index-v1.json"), INDEX_MAX_AGE_S, logf)
    b = r["bbox"]
    picks = sources.select(sources.load_index(index),
                           (b[0] - MARGIN_DEG, b[1] - MARGIN_DEG, b[2] + MARGIN_DEG, b[3] + MARGIN_DEG))
    if not picks:
        raise BuildError("Für dieses Gebiet gibt es keinen OSM-Auszug bei Geofabrik.")
    _log(logf, "sources: " + ", ".join(i for i, _ in picks))
    files = [fetch(url, os.path.join(WORK, "geofabrik", gid.replace("/", "_") + ".osm.pbf"), OSM_MAX_AGE_S, logf,
                   md5=True) for gid, url in picks]
    # osmium merge keeps both versions of an object that differs between files: all files must be of the same day
    stamps = {f: osm_timestamp(f) for f in files}
    if len(set(stamps.values())) > 1:
        newest = max(stamps.values())
        for (gid, url), f in zip(picks, files):
            if stamps[f] != newest:
                _log(logf, f"{gid} is older ({stamps[f]} < {newest}): downloading again")
                os.remove(f)
                fetch(url, f, OSM_MAX_AGE_S, logf, md5=True)
    return files


def extract(r, files, logf):
    b = r["bbox"]
    bb = ",".join(f"{v:.4f}" for v in (b[0] - MARGIN_DEG, b[1] - MARGIN_DEG, b[2] + MARGIN_DEG, b[3] + MARGIN_DEG))
    tmp = os.path.join(WORK, "extract")
    os.makedirs(tmp, exist_ok=True)
    parts = []
    for i, f in enumerate(files):
        out = os.path.join(tmp, f"{r['name']}-{i}.osm.pbf")
        run(["osmium", "extract", "-b", bb, "--overwrite", "-o", out, f], logf)
        parts.append(out)
    if len(parts) == 1:
        return parts[0]
    merged = os.path.join(tmp, f"{r['name']}.osm.pbf")
    run(["osmium", "merge", "--overwrite", "-o", merged] + parts, logf)
    for p in parts:
        os.remove(p)
    return merged


def java_xmx():
    try:
        with open("/proc/meminfo") as f:
            kb = int(next(l for l in f if l.startswith("MemTotal")).split()[1])
        return f"{max(3, kb // 1048576 - 2)}g"
    except Exception:
        return "6g"


def publish(r, logf):
    out = os.path.join(BUILD, r["name"])
    zip_name = f"onpath-data-{r['name']}.zip"
    zip_path = os.path.join(out, zip_name)
    if not os.path.isfile(zip_path):
        raise BuildError("Das Paket wurde nicht erzeugt.")
    dl = os.path.join(PUBLIC, "dl")
    os.makedirs(dl, exist_ok=True)
    final = os.path.join(dl, f"{r['name']}-{r['token']}")
    new = final + ".new"
    shutil.rmtree(new, ignore_errors=True)
    os.makedirs(new)
    os.replace(zip_path, os.path.join(new, zip_name))
    manifest = {}
    if os.path.isfile(os.path.join(out, "manifest.json")):
        shutil.copy(os.path.join(out, "manifest.json"), os.path.join(new, "manifest.json"))
        with open(os.path.join(new, "manifest.json")) as f:
            manifest = json.load(f)
    old = final + ".old"
    shutil.rmtree(old, ignore_errors=True)
    if os.path.exists(final):
        os.replace(final, old)
    os.replace(new, final)
    shutil.rmtree(old, ignore_errors=True)
    shutil.rmtree(out, ignore_errors=True)  # the unzipped build copy: rebuilt from scratch next time
    _log(logf, f"published {final}")
    return {"url": f"/dl/{r['name']}-{r['token']}/{zip_name}",
            "zip_bytes": os.path.getsize(os.path.join(final, zip_name)),
            "osm_date": (manifest.get("osm") or {}).get("date")}


def build(r):
    name = r["name"]
    os.makedirs(LOGS, exist_ok=True)
    with open(log_path(name), "w") as logf:
        try:
            store.update(name, status="building", step="OSM-Daten laden", started=time.time(), error=None)
            files = osm_sources(r, logf)
            store.update(name, step="Gebiet ausschneiden")
            osm = extract(r, files, logf)
            bbox = ",".join(str(v) for v in r["bbox"])
            env = dict(os.environ, OSM_FILE=osm, OUT=os.path.join(BUILD, name), WORK=WORK,
                       JAVA_XMX=os.environ.get("JAVA_XMX", java_xmx()))

            def on_line(line):
                if "==> " in line:
                    store.update(name, step=line.split("==> ", 1)[1][:80])

            run(["bash", os.path.join(REPO, "tools", "build_region.sh"), "--no-segments", name, bbox], logf, env, on_line)
            info = publish(r, logf)
            if osm.startswith(os.path.join(WORK, "extract")):
                os.remove(osm)
            store.update(name, status="ready", step="fertig", built_at=time.time(), **info)
            _log(logf, "ready")
        except Exception as e:  # never kill the worker loop
            msg = str(e) if isinstance(e, BuildError) else f"Unerwarteter Fehler: {e.__class__.__name__}: {e}"
            _log(logf, "FAILED: " + msg)
            # a region that was ready before keeps its old zip and stays usable
            prev = "ready" if r.get("url") else "failed"
            store.update(name, status=prev, step="fehlgeschlagen", error=msg, failed_at=time.time())


def update_repo():
    subprocess.run(["git", "-C", REPO, "pull", "--ff-only", "-q"], capture_output=True)


def recover():
    """A crash or restart during a build: queue it again."""
    for r in store.all():
        if r.get("status") == "building":
            store.update(r["name"], status="queued", step="wartet (Neustart)")


def once():
    r = store.next_queued()
    if r:
        update_repo()
        build(r)
    return r is not None


def run_forever():
    recover()
    while True:
        if not once():
            time.sleep(5)


def free_bytes():
    return shutil.disk_usage(HOME).free


def refresh(now=None):
    now = now or time.time()
    for r in store.all():
        if (r.get("status") == "ready" and now - r.get("built_at", 0) > REFRESH_AFTER_S
                and now - r.get("last_requested", 0) < KEEP_IN_USE_S):
            store.update(r["name"], status="queued", step="wartet (Auffrischung)", queued_at=now)
    # old Geofabrik downloads are fetched again when needed
    for f in glob.glob(os.path.join(WORK, "geofabrik", "*.osm.pbf")):
        if now - os.path.getmtime(f) > 30 * 86400:
            os.remove(f)
    cleanup()


def cleanup():
    """Deletes the least recently asked-for ready regions while free disk is below MIN_FREE_BYTES."""
    ready = sorted((r for r in store.all() if r.get("status") in ("ready", "failed")),
                   key=lambda r: r.get("last_requested", 0))
    while ready and free_bytes() < MIN_FREE_BYTES:
        r = ready.pop(0)
        shutil.rmtree(os.path.join(PUBLIC, "dl", f"{r['name']}-{r['token']}"), ignore_errors=True)
        try:
            os.remove(log_path(r["name"]))
        except OSError:
            pass
        store.delete(r["name"])


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "run"
    {"run": run_forever, "refresh": refresh, "once": once}[cmd]()
