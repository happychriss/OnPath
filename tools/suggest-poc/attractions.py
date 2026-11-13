"""Tour-suggestions POC – destinations in reach: named lakes, known signed routes, viewpoints.

Source (weights.json attractions.source):
  "cells" (default) – **the app's own map areas**, the same files the phone has (cells.py downloads them once):
  - known routes with name and rank: overlays.pmtiles, layer `routes` (`kind` hike / bike, `name`, `ref`, `net` =
    4 international … 2 regional). Named pieces exist only for net >= 2 – local routes (net 1, e.g. a town's
    circular walks A1 / A2) are only in the merged band without a name; their rank is still in the routing data
    (way flags route_hiking_lwn …), which the score uses.
  - lakes: base.pmtiles, layer `water` (polygons, z10 tile = the whole cell) + `water_name` (name points, z14).
  - viewpoints: pois.geojson (kind `viewpoint`).
  Read once per cell with the small reader in pmt.py, kept as attr-*.json next to the cell.
  "overpass" – the round-2 stand-in (online, cached), kept only as an explicit fallback.
A map area that is not on this computer: the request runs without its destinations and says so.
"""
import json
import math
import os
import re
import sys
import time
import urllib.parse
import urllib.request

import cells as CE
import pmt

DATA = os.environ.get("SUGGEST_DATA", os.path.expanduser("~/onpath-suggest-poc"))
ATTR_VERSION = 5
NET = {4: "i", 3: "n", 2: "r", 1: "l"}


def _inside(p, box, eps=1e-5):
    return box[0] + eps < p[0] < box[2] - eps and box[1] + eps < p[1] < box[3] - eps


def _in_poly(p, ring):
    x, y = p
    inside = False
    for a, b in zip(ring, ring[1:]):
        if (a[1] > y) != (b[1] > y) and x < (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1]) + a[0]:
            inside = not inside
    return inside


def route_label(name, ref):
    """'X30 Neandertalweg [Hilden]' + 'X30' -> 'X30 Neandertalweg'; one label per route whatever its pieces are called."""
    name = re.sub(r"\s*\[[^\]]*\]\s*$", "", name or "").strip()
    name = re.sub(r"^\[[^\]]*\]\s*", "", name).strip()
    ref = (ref or "").strip()
    if not name:
        return ref
    if ref and len(ref) <= 6 and ref.lower() not in name.lower() and not ref.startswith("<"):
        return "%s · %s" % (ref, name)
    return name


def read_cell(x, y, A):
    """Everything this feature needs from one map area -> {routes: [{kind, label, net, lines}], lakes: [{name,
    lines}], views: [{name, p}]}; cached as JSON in the cell's folder."""
    d = CE.folder(x, y)
    cache = os.path.join(d, "attr-v%d.json" % ATTR_VERSION)
    if os.path.isfile(cache):
        with open(cache, encoding="utf-8") as f:
            return json.load(f)
    box = CE.cell_bbox(x, y)
    out = {"routes": [], "lakes": [], "views": []}
    # known routes: named pieces, grouped by (kind, label)
    ov = pmt.PMTiles(os.path.join(d, "overlays.pmtiles"))
    groups = {}
    for z, tx, ty in pmt.tiles_of_cell(x, y, 12):
        raw = ov.tile(z, tx, ty)
        if not raw:
            continue
        n = float(1 << z)
        tb = (tx / n * 360 - 180, None, (tx + 1) / n * 360 - 180, None)
        for f in pmt.layers(raw, {"routes"}, z, tx, ty).get("routes", []):
            t = f["tags"]
            name, ref = t.get("name"), t.get("ref")
            if not (name or ref):
                continue
            if re.search(r"\(\d+\)", name or "") or re.match(r"^\d+\s*-\s*\d+$", ref or ""):
                continue  # links of the cycle node network: good ways, but no destination with a name
            g = groups.setdefault((t.get("kind"), route_label(name, ref)), {"net": 0, "lines": []})
            g["net"] = max(g["net"], int(t.get("net") or 0))
            for part in f["parts"]:
                # keep only what lies inside the tile (pieces are cut at tile borders with a buffer)
                part = [p for p in part if tb[0] <= p[0] <= tb[2]]
                if len(part) > 1:
                    g["lines"].append([[round(p[0], 6), round(p[1], 6)] for p in part])
    # named trails (layer trailnames, the map's trail labels): local circular walks ("Rund um Hilden H", "Rundweg A1"),
    # promenades ("Tappeinerweg", "Algunder Waalweg") – tier 1 / 2 = a trail's own name, 3 = street-like names
    for z, tx, ty in pmt.tiles_of_cell(x, y, 13):
        raw = ov.tile(z, tx, ty)
        if not raw:
            continue
        for f in pmt.layers(raw, {"trailnames"}, z, tx, ty).get("trailnames", []):
            t = f["tags"]
            if t.get("t") != "n" or not t.get("name") or (t.get("tier") or 9) > A.get("trailname_max_tier", 2):
                continue
            label = route_label(t["name"], None)
            if ("bike", label) in groups:
                continue
            g = groups.setdefault(("hike", label), {"net": 1, "lines": [], "trail": True})
            if g.get("trail"):
                g["lines"] += [[[round(p[0], 6), round(p[1], 6)] for p in part] for part in f["parts"] if len(part) > 1]
    for (kind, label), g in groups.items():
        out["routes"].append({"kind": kind, "label": label, "net": g["net"], "lines": g["lines"],
                              "trail": bool(g.get("trail"))})
    # lakes: polygons of the whole cell (z10) + names (z14 points)
    ba = pmt.PMTiles(os.path.join(d, "base.pmtiles"))
    names = {}
    for z, tx, ty in pmt.tiles_of_cell(x, y, 14):
        raw = ba.tile(z, tx, ty)
        if not raw:
            continue
        for f in pmt.layers(raw, {"water_name"}, z, tx, ty).get("water_name", []):
            nm = f["tags"].get("name")
            if nm and f["parts"] and f["tags"].get("class") in ("lake", "pond", None):
                names.setdefault(nm, f["parts"][0][0])
    raw = ba.tile(10, x, y)
    polys = []
    if raw:
        for f in pmt.layers(raw, {"water"}, 10, x, y).get("water", []):
            if f["tags"].get("class") not in ("lake", "pond"):
                continue
            polys += [r for r in f["parts"] if len(r) > 3]
    for nm, p in names.items():
        hit = [r for r in polys if _in_poly(p, r)]
        if not hit:
            continue
        ring = max(hit, key=len)  # the outer ring has the most points
        # the shore inside the cell only (a lake across a cell border is cut there; the neighbour has the rest)
        lines, cur = [], []
        for q in ring:
            if _inside(q, box):
                cur.append([round(q[0], 6), round(q[1], 6)])
            else:
                if len(cur) > 1:
                    lines.append(cur)
                cur = []
        if len(cur) > 1:
            lines.append(cur)
        if lines:
            out["lakes"].append({"name": nm, "lines": lines, "whole": len(lines) == 1 and len(lines[0]) >= len(ring) - 1})
    pois = os.path.join(d, "pois.geojson")
    if os.path.isfile(pois):
        with open(pois, encoding="utf-8") as f:
            for ft in json.load(f).get("features", []):
                if ft["properties"].get("kind") == "viewpoint":
                    out["views"].append({"name": ft["properties"].get("name") or "Aussichtspunkt",
                                         "p": ft["geometry"]["coordinates"][:2]})
    with open(cache, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False)
    return out


RIVER_VERSION = 1


def read_rivers(x, y):
    """Large rivers of one map area -> [{name, width_m, lines}]: the `water` polygons of class river (zoom 10) are the
    river's surface, their outline inside the area is the bank; the name comes from the named `waterway` line that
    runs through the polygon (Rhein, Wupper, Erft …). A stream without a surface polygon is no destination here.
    Cached beside the cell as rivers-v<N>.json."""
    d = CE.folder(x, y)
    cache = os.path.join(d, "rivers-v%d.json" % RIVER_VERSION)
    if os.path.isfile(cache):
        with open(cache, encoding="utf-8") as f:
            return json.load(f)
    box = CE.cell_bbox(x, y)
    ba = pmt.PMTiles(os.path.join(d, "base.pmtiles"))
    raw = ba.tile(10, x, y)
    polys, named = [], {}
    if raw:
        L = pmt.layers(raw, {"water", "waterway"}, 10, x, y)
        for f in L.get("water", []):
            if f["tags"].get("class") == "river":
                polys += [r for r in f["parts"] if len(r) > 3]
        for f in L.get("waterway", []):
            nm = f["tags"].get("name")
            if nm and f["tags"].get("class") == "river":
                for part in f["parts"]:
                    named.setdefault(nm, []).extend(_densify(part, 100))
    kx = 111320.0 * math.cos(math.radians((box[1] + box[3]) / 2))
    rivers = {}
    for ring in polys:
        votes = {nm: sum(1 for p in pts if _in_poly(p, ring)) for nm, pts in named.items()}
        nm = max(votes, key=votes.get) if votes else None
        if not nm or votes[nm] < 2:
            continue
        area = abs(sum(a[0] * b[1] - b[0] * a[1] for a, b in zip(ring, ring[1:]))) / 2 * kx * 111320.0
        lines, cur = [], []
        for q in ring:  # the bank inside the area only (the polygon is cut at the area's border)
            if _inside(q, box):
                cur.append([round(q[0], 6), round(q[1], 6)])
            else:
                if len(cur) > 1:
                    lines.append(cur)
                cur = []
        if len(cur) > 1:
            lines.append(cur)
        bank = sum(_dist(a, b) for l in lines for a, b in zip(l, l[1:]))
        if bank < 200:
            continue
        g = rivers.setdefault(nm, {"area": 0.0, "bank": 0.0, "lines": []})
        g["area"] += area
        g["bank"] += bank
        g["lines"] += lines
    out = [{"name": nm, "width_m": round(2 * g["area"] / g["bank"]), "lines": g["lines"]} for nm, g in rivers.items()]
    with open(cache, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False)
    return out


def load_cells(start, reach_m, activity, W):
    A = W["attractions"]
    bike = activity != "hike"
    ranks = A["rank"]
    need = CE.cells_for(start[0], start[1], A["max_reach_m"])
    missing = [c for c in need if not CE.have(*c)]
    key = ("cells", tuple(need), bike)
    if key not in _mem:
        routes, lakes, views, rivers = {}, {}, [], {}
        for c in need:
            if c in missing:
                continue
            d = read_cell(c[0], c[1], A)
            for r in read_rivers(c[0], c[1]):
                g = rivers.setdefault(r["name"], {"width": 0, "lines": []})
                g["width"] = max(g["width"], r["width_m"])
                g["lines"] += r["lines"]
            for r in d["routes"]:
                if (r["kind"] == "hike") == bike or r["kind"] not in ("hike", "bike"):
                    continue
                # one route, many pieces ("EuroVelo 15 - Rheinradweg - Teilstrecke Köln - Moers" …): one name
                lab = re.sub(r"\s+-\s+(Teilstrecke|Verbindung|Abschnitt|Etappe|Teil|Variante)\b.*$", "", r["label"])
                lab = re.sub(r"\s*\((West|Ost|Nord|Süd|Mitte)\)$", "", lab)
                g = routes.setdefault(lab, {"net": 0, "lines": [], "trail": True})
                g["net"] = max(g["net"], r["net"])
                g["lines"] += r["lines"]
                g["trail"] = g["trail"] and r.get("trail", False)
            for l in d["lakes"]:
                lakes.setdefault(l["name"], []).extend(l["lines"])
            views += d["views"]
        # a trail label that only repeats a real route ("Neanderland STEIG" next to "NLS · neanderland STEIG") goes
        real = [k.lower() for k, g in routes.items() if not g["trail"]]
        for k in [k for k, g in routes.items() if g["trail"]]:
            n = sum(len(l) for l in routes[k]["lines"])
            if any(k.lower() in r for r in real) or n < 2:
                del routes[k]
        _mem[key] = (routes, lakes, views, rivers)
    routes, lakes, views, rivers = _mem[key]
    skip = re.compile(A.get("lake_skip", "$^"), re.I)
    out = []
    for label, g in routes.items():
        dense = [p for l in g["lines"] for p in _densify(l, 25)]
        near = [p for p in dense if _dist(start, p) <= A["max_reach_m"]]
        if not near or (g["trail"] and len(near) * 25.0 < A.get("trail_min_m", 1000)):
            continue
        net = NET.get(g["net"], "l") + ("cn" if bike else "wn")
        out.append({"kind": "route", "name": label, "rank": ranks.get(net, ranks[""]), "net": net,
                    "ring": "rund" in label.lower(), "points": _thin(near, 150), "dense": near,
                    "length_m": len(near) * 25.0})
    for name, lines in lakes.items():
        if skip.search(name):
            continue
        dense = [p for l in lines for p in _densify(l, 25)]
        length = len(dense) * 25.0
        if length < A["lake_min_shore_m"]:
            continue
        out.append({"kind": "lake", "name": name, "rank": 0.8, "ring": True, "points": _thin(dense, 150), "dense": dense,
                    "length_m": length, "pad": A.get("lake_pad_cells", 0)})
    for v in views:
        out.append({"kind": "view", "name": v["name"], "rank": 0.5, "ring": False, "points": [v["p"]], "dense": [v["p"]],
                    "length_m": 0})
    # large rivers: the bank is a destination of its own (owner 2026-11-13: "wunderbar am Rhein lang") – a way within
    # river_pad_cells of the bank counts as "am Rhein"; the wider the river, the higher its rank
    R = A.get("river", {})
    for name, g in rivers.items() if R.get("enabled", True) else ():
        if g["width"] < R.get("min_width_m", 15):
            continue
        dense = [p for l in g["lines"] for p in _densify(l, 25)]
        near = [p for p in dense if _dist(start, p) <= A["max_reach_m"]]
        if len(near) * 25.0 < R.get("min_bank_m", 1500):
            continue
        out.append({"kind": "river", "name": name, "rank": 1.0 if g["width"] >= R.get("big_width_m", 100) else R.get("small_rank", 0.7),
                    "ring": False, "points": _thin(near, 150), "dense": near, "length_m": len(near) * 25.0 / 2,
                    "pad": R.get("pad_cells", 5), "width_m": g["width"]})
    for a in out:
        a["d"] = min(_dist(start, p) for p in a["points"])
    note = None
    if missing:
        note = "Kartengebiet fehlt auf diesem Rechner (%s) – dort ohne Ziele; holen: cells.py get" % ", ".join(
            "10-%d-%d" % c for c in missing)
    return [a for a in out if a["d"] <= reach_m], note


def known_lines(start, reach_m, activity, W):
    """The known routes around the start as lines for the page's overlay: [{name, net, lines}] (own map areas only)."""
    load_cells(start, reach_m, activity, W)
    A = W["attractions"]
    bike = activity != "hike"
    key = ("cells", tuple(CE.cells_for(start[0], start[1], A["max_reach_m"])), bike)
    out = []
    for label, g in _mem.get(key, ({}, {}, []))[0].items():
        lines = []
        for l in g["lines"]:
            if _dist(start, l[0]) <= reach_m or _dist(start, l[-1]) <= reach_m:
                lines.append([[round(q[0], 5), round(q[1], 5)] for q in l[::2] + ([l[-1]] if len(l) % 2 == 0 else [])])
        if lines:
            out.append({"name": label, "net": NET.get(g["net"], "l") + ("cn" if bike else "wn"), "trail": g["trail"],
                        "lines": lines})
    return out


def load(start, reach_m, activity, W):
    if not W["attractions"].get("enabled", True):
        return [], "Ziele aus"
    if W["attractions"].get("source", "cells") == "overpass":
        return load_overpass(start, reach_m, activity, W)
    return load_cells(start, reach_m, activity, W)
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


def load_overpass(start, reach_m, activity, W):
    """-> (list of attractions, note). Attraction: {kind: lake|route|view, name, rank, ring, points [[lon,lat]..] every
    ~150 m, dense (every ~25 m, for matching), length_m}."""
    A = W["attractions"]
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
            pad = a.get("pad", 0)  # lakes: the shore path often runs 50–120 m from the water – a wider band counts
            for p in a["dense"]:
                cx, cy = int(p[0] / self.CX), int(p[1] / self.CY)
                if not pad:
                    self.cells.setdefault((cx, cy), set()).add(i)
                    continue
                for dx in range(-pad, pad + 1):
                    for dy in range(-pad, pad + 1):
                        self.cells.setdefault((cx + dx, cy + dy), set()).add(i)

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
