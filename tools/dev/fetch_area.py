#!/usr/bin/env python3
"""Fetch already-built map cells (and their region packages) of an area into a local store, for the planner's local
tests, measurements and screenshots (laptop: /home/development/Projects/work/onpath-data, never in git).

    python3 -I tools/dev/fetch_area.py --store DIR --bbox 5.85,50.32,9.47,52.53          # NRW
    python3 -I tools/dev/fetch_area.py --store DIR --centre 46.67,11.16 --radius-km 40     # Meran
        [--packages] [--segments DIR] [--base https://plan.onpath.earth/data/] [--jobs 2]

The store gets the R2 layout the web reads (web/app/src/cells.ts): cells/f2/<z>/<x>/<y>/current.json and
<version>/manifest.json + every file the manifest lists. Only published cells are fetched (nothing is built): a cell
without current.json is listed as missing. Resumable: a file whose size and sha256 match its manifest is kept;
current.json is written last, so a half-fetched cell is never seen as complete. --packages adds the z7 region packages
over the area; --segments DIR also fetches the BRouter rd5 tiles (5° squares, brouter.de/brouter/segments4/).
Standard library only; polite: a few requests at a time, backoff on 429 / 5xx.
"""
import argparse
import concurrent.futures as cf
import hashlib
import json
import math
import os
import sys
import time
import urllib.error
import urllib.request

UA = "OnPath-dev-fetch/1 (laptop test store)"
SEGMENTS = "https://brouter.de/brouter/segments4/"


def tile_of(z, lon, lat):
    n = 2 ** z
    x = int((lon + 180) / 360 * n)
    lr = math.radians(lat)
    y = int((1 - math.log(math.tan(lr) + 1 / math.cos(lr)) / math.pi) / 2 * n)
    return min(max(x, 0), n - 1), min(max(y, 0), n - 1)


def tiles(z, w, s, e, n):
    x0, y0 = tile_of(z, w, n)
    x1, y1 = tile_of(z, e, s)
    return [(x, y) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1)]


def get(url, tries=6):
    """Bytes of [url], None on 404; backoff on 429 / 5xx / network errors."""
    for i in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=120) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            if e.code not in (408, 425, 429) and e.code < 500:
                raise
            wait = max(60 if e.code == 429 else 0, 5 * 2 ** i)
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            wait = 5 * 2 ** i
        print(f"  busy, again in {wait} s: {url}", file=sys.stderr)
        time.sleep(wait)
    raise RuntimeError(f"gave up: {url}")


def sha_ok(path, size, sha):
    if not os.path.exists(path) or os.path.getsize(path) != size:
        return False
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest() == sha


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".part"
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, path)


def fetch_cell(base, store, z, x, y):
    """'got' / 'kept' / 'missing' for the cell z/x/y."""
    rel = f"cells/f2/{z}/{x}/{y}/"
    cur = get(base + rel + "current.json")
    if cur is None:
        return "missing"
    version = json.loads(cur)["version"]
    man_b = get(base + rel + version + "/manifest.json")
    if man_b is None:
        return "missing"
    man = json.loads(man_b)
    fresh = False
    for f in man["files"]:
        path = os.path.join(store, f["url"])
        if sha_ok(path, f["size"], f["sha256"]):
            continue
        data = get(base + f["url"])
        if data is None or len(data) != f["size"] or hashlib.sha256(data).hexdigest() != f["sha256"]:
            raise RuntimeError(f"bad file {f['url']}")
        write(path, data)
        fresh = True
    write(os.path.join(store, rel, version, "manifest.json"), man_b)
    old = os.path.join(store, rel, "current.json")
    same = os.path.exists(old) and open(old, "rb").read() == cur
    write(old, cur)  # last: the cell is complete
    return "got" if fresh or not same else "kept"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--store", required=True)
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--bbox", help="w,s,e,n in degrees")
    g.add_argument("--centre", help="lat,lon")
    ap.add_argument("--radius-km", type=float, default=30)
    ap.add_argument("--packages", action="store_true", help="also the z7 region packages over the area")
    ap.add_argument("--segments", help="also the BRouter rd5 tiles into this folder")
    ap.add_argument("--base", default="https://plan.onpath.earth/data/")
    ap.add_argument("--jobs", type=int, default=2)
    a = ap.parse_args()
    if a.bbox:
        w, s, e, n = map(float, a.bbox.split(","))
    else:
        lat, lon = map(float, a.centre.split(","))
        dlat = a.radius_km / 110.57
        dlon = a.radius_km / (111.32 * math.cos(math.radians(lat)))
        w, s, e, n = lon - dlon, lat - dlat, lon + dlon, lat + dlat
    base = a.base if a.base.endswith("/") else a.base + "/"
    work = [(10, x, y) for x, y in tiles(10, w, s, e, n)]
    if a.packages:
        work += [(7, x, y) for x, y in tiles(7, w, s, e, n)]
    print(f"area {w:.3f},{s:.3f},{e:.3f},{n:.3f}: {len(work)} cells / packages", file=sys.stderr)
    result = {"got": [], "kept": [], "missing": []}
    with cf.ThreadPoolExecutor(a.jobs) as ex:
        futs = {ex.submit(fetch_cell, base, a.store, *c): c for c in work}
        for i, f in enumerate(cf.as_completed(futs), 1):
            z, x, y = futs[f]
            r = f.result()
            result[r].append(f"{z}-{x}-{y}")
            print(f"[{i}/{len(work)}] {z}-{x}-{y} {r}", file=sys.stderr)
    if a.segments:
        os.makedirs(a.segments, exist_ok=True)
        for lo in range(int(math.floor(w / 5) * 5), int(math.floor(e / 5) * 5) + 1, 5):
            for la in range(int(math.floor(s / 5) * 5), int(math.floor(n / 5) * 5) + 1, 5):
                name = f"{'E' if lo >= 0 else 'W'}{abs(lo)}_{'N' if la >= 0 else 'S'}{abs(la)}.rd5"
                path = os.path.join(a.segments, name)
                if os.path.exists(path) and os.path.getsize(path) > 0:
                    print(f"segments {name} kept", file=sys.stderr)
                    continue
                data = get(SEGMENTS + name)
                if data is None:
                    print(f"segments {name} missing", file=sys.stderr)
                    continue
                write(path, data)
                print(f"segments {name} got ({len(data) >> 20} MB)", file=sys.stderr)
    print(json.dumps({k: len(v) for k, v in result.items()}))
    if result["missing"]:
        print("missing: " + " ".join(sorted(result["missing"])))


if __name__ == "__main__":
    main()
