"""Tour-suggestions POC – destinations in reach: named lakes, known signed routes, viewpoints.

POC stand-in: one Overpass query per area (online, cached on disk under ~/onpath-suggest-poc/cache/). On the phone
the same facts come from the map area's own files, no network:
  - known routes with name and rank: overlays.pmtiles, layer `routes` (`kind` hike / bike, `name`, `ref`, `net` =
    network rank; docs/map-pipeline.md "Route bands") – and the rank alone also from the routing data
    (way flags route_hiking_iwn / nwn / rwn / lwn, route_bicycle_icn / ncn / rcn / lcn), which the score uses.
  - lakes: base.pmtiles, layers `water` (polygons) + `water_name` (name points).
  - viewpoints: the POI file (kind `viewpoint`).
Without network and without a cache entry the request simply runs without destinations (note in the result).
"""
import json
import math
import os
import sys
import time
import urllib.parse
import urllib.request

DATA = os.environ.get("SUGGEST_DATA", os.path.expanduser("~/onpath-suggest-poc"))
MIRRORS = ["https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter"]
_mem = {}
_failed = {}


def _dist(a, b):
    kx = 111320.0 * math.cos(math.radians((a[1] + b[1]) / 2))
    return math.hypot((a[0] - b[0]) * kx, (a[1] - b[1]) * 111320.0)


def _densify(line, step):
    out = []
    for a, b in zip(line, line[1:]):
        n = max(1, int(_dist(a, b) / step))
        for i in range(n):
            out.append([a[0] + (b[0] - a[0]) * i / n, a[1] + (b[1] - a[1]) * i / n])
    if line:
        out.append(list(line[-1]))
    return out


def _thin(points, step):
    out = []
    for p in points:
        if not out or _dist(out[-1], p) >= step:
            out.append(p)
    return out


def _fetch(bbox, bike, A):
    s, w, n, e = bbox
    key = "attr-%s-%.2f-%.2f-%.2f-%.2f" % ("bike" if bike else "hike", s, w, n, e)
    if key in _mem:
        return _mem[key], None
    path = os.path.join(DATA, "cache", key + ".json")
    if os.path.isfile(path):
        with open(path, encoding="utf-8") as f:
            _mem[key] = json.load(f)
        return _mem[key], None
    routes = ('relation["route"="bicycle"]["network"~"^(icn|ncn|rcn)$"];' if bike
              else 'relation["route"~"^(hiking|foot)$"];')
    q = ('[out:json][timeout:50][bbox:%f,%f,%f,%f];(way["natural"="water"]["name"];relation["natural"="water"]["name"];'
         '%snode["tourism"="viewpoint"];);out geom(%f,%f,%f,%f);' % (s, w, n, e, routes, s, w, n, e))
    err = None
    if _failed.get(key, 0) > time.time():
        return None, "vor kurzem fehlgeschlagen, neuer Versuch später"
    for m in MIRRORS:
        for _ in range(A.get("tries", 2)):
            try:
                req = urllib.request.Request(m, data=urllib.parse.urlencode({"data": q}).encode(),
                                             headers={"User-Agent": "onpath-suggest-poc"})
                d = json.load(urllib.request.urlopen(req, timeout=A.get("timeout_s", 40)))
                os.makedirs(os.path.dirname(path), exist_ok=True)
                with open(path, "w", encoding="utf-8") as f:
                    json.dump(d, f)
                _mem[key] = d
                return d, None
            except Exception as ex:  # expected: no network / server busy – the request runs without destinations
                err = "%s: %s" % (type(ex).__name__, ex)
    _failed[key] = time.time() + A.get("retry_after_s", 300)
    return None, err


def load(start, reach_m, activity, W):
    """-> (list of attractions, note). Attraction: {kind: lake|route|view, name, rank, ring, points [[lon,lat]..] every
    ~150 m, dense (every ~25 m, for matching), length_m}."""
    A = W["attractions"]
    if not A.get("enabled", True):
        return [], "Ziele aus"
    bike = activity != "hike"
    g = A["cache_grid_deg"]
    box_m = A["max_reach_m"]  # always the same box per start, so one cache entry serves every duration
    dlat = box_m / 111320.0
    dlon = box_m / (111320.0 * math.cos(math.radians(start[1])))
    bbox = (math.floor((start[1] - dlat) / g) * g, math.floor((start[0] - dlon) / g) * g,
            math.ceil((start[1] + dlat) / g) * g, math.ceil((start[0] + dlon) / g) * g)
    d, err = _fetch(bbox, bike, A)
    if d is None:
        return [], "Ziele (Seen, bekannte Wege) nicht geladen – online nicht erreichbar (%s)" % err
    ranks = A["rank"]
    out = []
    for el in d.get("elements", []):
        t = el.get("tags", {})
        if el["type"] == "node":
            out.append({"kind": "view", "name": t.get("name") or "Aussichtspunkt", "rank": 0.5, "ring": False,
                        "points": [[el["lon"], el["lat"]]], "dense": [[el["lon"], el["lat"]]], "length_m": 0})
            continue
        lines = []
        if el["type"] == "way":
            lines = [[[p["lon"], p["lat"]] for p in el.get("geometry", []) if p]]
        else:
            for mb in el.get("members", []):
                if mb.get("type") != "way" or not mb.get("geometry"):
                    continue
                if t.get("natural") == "water" and mb.get("role") == "inner":
                    continue
                lines.append([[p["lon"], p["lat"]] for p in mb["geometry"] if p])
        lines = [l for l in lines if len(l) > 1]
        if not lines:
            continue
        length = sum(_dist(a, b) for l in lines for a, b in zip(l, l[1:]))
        dense = [p for l in lines for p in _densify(l, 25)]
        pts = _thin(dense, 150)
        if t.get("natural") == "water":
            if length < A["lake_min_shore_m"]:
                continue
            out.append({"kind": "lake", "name": t["name"], "rank": 0.8, "ring": True, "points": pts, "dense": dense,
                        "length_m": length})
        else:
            net = t.get("network", "")
            name = t.get("name") or t.get("ref") or ""
            if not name:
                continue
            out.append({"kind": "route", "name": name, "rank": ranks.get(net, ranks[""]), "net": net,
                        "ring": t.get("roundtrip") == "yes", "points": pts, "dense": dense, "length_m": length})
    for a in out:
        a["d"] = min(_dist(start, p) for p in a["points"])
    return [a for a in out if a["d"] <= reach_m], None


class Index:
    """Which named route / lake is an edge on? A grid of the attractions' dense points (cells of ~30 m)."""
    CX, CY = 0.00045, 0.00028

    def __init__(self, attractions):
        self.cells = {}
        self.attr = attractions
        for i, a in enumerate(attractions):
            for p in a["dense"]:
                self.cells.setdefault((int(p[0] / self.CX), int(p[1] / self.CY)), set()).add(i)

    def at(self, p, reach=1):
        cx, cy = int(p[0] / self.CX), int(p[1] / self.CY)
        hit = set()
        for dx in range(-reach, reach + 1):
            for dy in range(-reach, reach + 1):
                hit |= self.cells.get((cx + dx, cy + dy), set())
        return hit


if __name__ == "__main__":
    # Fill the cache patiently:  python3 attractions.py <lon> <lat> <hike|bike>   (long timeout, several tries)
    import suggest as S
    W = S.effective(S.load_weights(), sys.argv[3])
    W["attractions"].update(timeout_s=170, tries=3)
    a, note = load([float(sys.argv[1]), float(sys.argv[2])], W["attractions"]["max_reach_m"], sys.argv[3], W)
    print(note or "%d Ziele: %s" % (len(a), ", ".join(sorted(set(x["name"] for x in a if x["kind"] != "view")))[:1500]))
