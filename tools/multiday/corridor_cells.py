"""Map cells (z10, format 2) along a GPX track for several corridor widths and their size from the published manifests.

Multi-day tours stage 0 (docs/design/multi-day-tours.md §6). The cell rule is the app's CellId.alongLine (samples the
line every ~1 km, every cell whose box is within the corridor of a sample; start and goal included); region packages
(z7) as CellId.packagesFor (own packages + those the 8 neighbours reach into). Sizes: the published cell's
current.json + manifest.json (sum of its files) read over HTTPS from the planner host's /data/ (public, read-only, the
same files the planner page reads); a cell not built yet → "missing", estimated with the median of the built ones.

  python3 -I tools/multiday/corridor_cells.py <track.gpx> [corridor km …]   (default 1 5 10 20)
Env: CELLS_BASE (default https://plan.onpath.earth/data), CELLS_CACHE=<dir> (manifests kept between runs).
"""
import json, math, os, re, statistics, sys, time, urllib.request

BASE = os.environ.get("CELLS_BASE", "https://plan.onpath.earth/data")
CACHE = os.environ.get("CELLS_CACHE")
R = 6371000.0


def dist(la1, lo1, la2, lo2):
    p1, p2 = math.radians(la1), math.radians(la2)
    a = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(lo2 - lo1) / 2) ** 2
    return 2 * R * math.asin(math.sqrt(a))


def tile(lat, lon, z):
    n = 1 << z
    x = int((lon + 180) / 360 * n)
    y = int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n)
    return x, y


def bbox(x, y, z):
    n = 1 << z
    lon = lambda xx: xx / n * 360 - 180
    lat = lambda yy: math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * yy / n))))
    return lon(x), lat(y + 1), lon(x + 1), lat(y)  # w s e n


def dist_to_cell(lat, lon, c):
    w, s, e, n = bbox(*c, 10)
    return dist(lat, lon, min(max(lat, s), n), min(max(lon, w), e))


def along(line, corridor_m):
    out = {}
    def add(lat, lon):
        out.setdefault(tile(lat, lon, 10), None)
        dlat = corridor_m / 111320.0; dlon = corridor_m / (111320.0 * math.cos(math.radians(lat)))
        x0, y0 = tile(lat + dlat, lon - dlon, 10); x1, y1 = tile(lat - dlat, lon + dlon, 10)
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                if (x, y) not in out and dist_to_cell(lat, lon, (x, y)) <= corridor_m: out[(x, y)] = None
    add(*line[0])
    for (la, lo), (lb, lob) in zip(line, line[1:]):
        steps = int(dist(la, lo, lb, lob) / 1000)
        for s in range(1, steps + 1):
            t = s / (steps + 1); add(la + (lb - la) * t, lo + (lob - lo) * t)
        add(lb, lob)
    return list(out)


def packages(cells):
    out = {}
    for x, y in cells:
        out.setdefault((x >> 3, y >> 3), None)
    for x, y in cells:
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                out.setdefault(((x + dx) >> 3, (y + dy) >> 3), None)
    return list(out)


def get(path):
    if CACHE:
        f = os.path.join(CACHE, path.replace("/", "_"))
        if os.path.exists(f):
            t = open(f).read()
            return None if t == "404" else json.loads(t)
    try:
        with urllib.request.urlopen(urllib.request.Request(BASE + "/" + path, headers={"User-Agent": "onpath-measure"}), timeout=30) as r:
            d = json.loads(r.read())
    except urllib.error.HTTPError as e:
        if e.code != 404: raise
        d = None
    time.sleep(0.05)
    if CACHE:
        os.makedirs(CACHE, exist_ok=True)
        open(os.path.join(CACHE, path.replace("/", "_")), "w").write("404" if d is None else json.dumps(d))
    return d


def size(z, x, y):
    """(bytes, version, {file: bytes}) of the published cell, None when not built."""
    cur = get(f"cells/f2/{z}/{x}/{y}/current.json")
    if not cur: return None
    m = get(f"cells/f2/{z}/{x}/{y}/{cur['version']}/manifest.json")
    if not m: return None
    files = {f["name"]: f["size"] for f in m["files"]}
    return sum(files.values()), cur["version"], files


def main():
    xml = open(sys.argv[1]).read()
    line = [(float(a), float(b)) for a, b in re.findall(r'<trkpt lat="([-\d.]+)" lon="([-\d.]+)"', xml)]
    widths = [float(k) for k in sys.argv[2:]] or [1, 5, 10, 20]
    known = {}
    for km in widths:
        cells = along(line, km * 1000)
        for c in cells:
            if c not in known: known[c] = size(10, *c)
        built = [known[c][0] for c in cells if known[c]]
        med = statistics.median(built) if built else 0
        missing = len(cells) - len(built)
        total = sum(built) + missing * med
        pk = packages(cells)
        pks = [size(7, *p) for p in pk]
        pbytes = sum(p[0] for p in pks if p)
        print(f"corridor {km:g} km: {len(cells)} cells ({len(built)} published, {missing} not built → median {med/1e6:.0f} MB each), "
              f"cells {total/1e9:.2f} GB (published only {sum(built)/1e9:.2f} GB; min {min(built)/1e6:.0f} / median {med/1e6:.0f} / "
              f"max {max(built)/1e6:.0f} MB), + {len(pk)} region packages {pbytes/1e6:.0f} MB ({sum(1 for p in pks if p)} published)")
    allb = [v for v in known.values() if v]
    per = {}
    for _, _, f in allb:
        for k, v in f.items(): k = k.split("/")[0]; per[k] = per.get(k, 0) + v
    print("by file (all cells seen):", ", ".join(f"{k} {v/1e6:.0f} MB" for k, v in sorted(per.items(), key=lambda kv: -kv[1])))
    print("versions:", sorted({v[1] for v in allb}))


main()
