"""Named things from our own map areas: known signed routes, trail names, lakes, viewpoints – and which way lies on them.

The same files the phone has (cells.py fetches them): overlays.pmtiles layer `routes` (kind hike / bike, name, ref,
net 4 international … 2 regional) and `trailnames` (local circular walks, promenades, Waalwege), base.pmtiles `water`
+ `water_name`, pois.geojson (viewpoints). Read once per map area, kept as attr-v5.json beside it.
"""
import json
import os
import re

import cells as CE
import pmt
from net import dist

NET = {4: "i", 3: "n", 2: "r", 1: "l"}
NOT_A_LAKE = re.compile(r"becken|filter|klär|fang$|hafen", re.I)
_mem = {}


def _in_poly(p, ring):
    x, y, inside = p[0], p[1], False
    for a, b in zip(ring, ring[1:]):
        if (a[1] > y) != (b[1] > y) and x < (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1]) + a[0]:
            inside = not inside
    return inside


def label(name, ref):
    """'X30 Neandertalweg [Hilden]' + 'X30' -> 'X30 Neandertalweg': one label per route whatever its pieces are called."""
    name = re.sub(r"^\[[^\]]*\]\s*|\s*\[[^\]]*\]\s*$", "", name or "").strip()
    name = re.sub(r"\s+-\s+(Teilstrecke|Verbindung|Abschnitt|Etappe|Teil|Variante)\b.*$|\s*\((West|Ost|Nord|Süd|Mitte)\)$", "", name)
    ref = (ref or "").strip()
    if name and ref and len(ref) <= 6 and ref.lower() not in name.lower() and not ref.startswith("<"):
        return "%s · %s" % (ref, name)
    return name or ref


def read_cell(x, y):
    """One map area -> {routes: [{kind, label, net, lines, trail}], lakes: [{name, lines}], views: [{name, p}]}."""
    d = CE.folder(x, y)
    cache = os.path.join(d, "attr-v5.json")
    if os.path.isfile(cache):
        with open(cache, encoding="utf-8") as f:
            return json.load(f)
    box = CE.cell_bbox(x, y)
    rnd = lambda part: [[round(p[0], 6), round(p[1], 6)] for p in part]
    ov, groups = pmt.PMTiles(os.path.join(d, "overlays.pmtiles")), {}
    for z, tx, ty in pmt.tiles_of_cell(x, y, 12):
        raw = ov.tile(z, tx, ty)
        west, east = tx / float(1 << z) * 360 - 180, (tx + 1) / float(1 << z) * 360 - 180
        for f in pmt.layers(raw, {"routes"}, z, tx, ty).get("routes", []) if raw else []:
            t = f["tags"]
            name, ref = t.get("name"), t.get("ref")
            # links of the cycle node network ("Dormagen (83) - Neuss (84)") are good ways but no destination
            if not (name or ref) or re.search(r"\(\d+\)", name or "") or re.match(r"^\d+\s*-\s*\d+$", ref or ""):
                continue
            g = groups.setdefault((t.get("kind"), label(name, ref)), {"net": 0, "lines": []})
            g["net"] = max(g["net"], int(t.get("net") or 0))
            g["lines"] += [rnd(q) for q in ([p for p in part if west <= p[0] <= east] for part in f["parts"]) if len(q) > 1]
    for z, tx, ty in pmt.tiles_of_cell(x, y, 13):  # the map's trail labels: a trail's own name (tier 1 / 2)
        raw = ov.tile(z, tx, ty)
        for f in pmt.layers(raw, {"trailnames"}, z, tx, ty).get("trailnames", []) if raw else []:
            t = f["tags"]
            if t.get("t") != "n" or not t.get("name") or (t.get("tier") or 9) > 2 or ("bike", label(t["name"], None)) in groups:
                continue
            g = groups.setdefault(("hike", label(t["name"], None)), {"net": 1, "lines": [], "trail": True})
            if g.get("trail"):
                g["lines"] += [rnd(part) for part in f["parts"] if len(part) > 1]
    out = {"routes": [{"kind": k, "label": lb, "net": g["net"], "lines": g["lines"], "trail": bool(g.get("trail"))}
                      for (k, lb), g in groups.items()], "lakes": [], "views": []}
    ba, names = pmt.PMTiles(os.path.join(d, "base.pmtiles")), {}
    for z, tx, ty in pmt.tiles_of_cell(x, y, 14):
        raw = ba.tile(z, tx, ty)
        for f in pmt.layers(raw, {"water_name"}, z, tx, ty).get("water_name", []) if raw else []:
            if f["tags"].get("name") and f["parts"] and f["tags"].get("class") in ("lake", "pond", None):
                names.setdefault(f["tags"]["name"], f["parts"][0][0])
    raw = ba.tile(10, x, y)
    polys = [r for f in (pmt.layers(raw, {"water"}, 10, x, y).get("water", []) if raw else [])
             if f["tags"].get("class") in ("lake", "pond") for r in f["parts"] if len(r) > 3]
    for nm, p in names.items():
        hit = [r for r in polys if _in_poly(p, r)]
        if not hit:
            continue
        lines, cur = [], []  # the shore inside this area only (the neighbour has the rest)
        for q in max(hit, key=len) + [None]:
            if q and box[0] < q[0] < box[2] and box[1] < q[1] < box[3]:
                cur.append([round(q[0], 6), round(q[1], 6)])
            else:
                lines += [cur] if len(cur) > 1 else []
                cur = []
        if lines:
            out["lakes"].append({"name": nm, "lines": lines})
    pois = os.path.join(d, "pois.geojson")
    if os.path.isfile(pois):
        with open(pois, encoding="utf-8") as f:
            out["views"] = [{"name": ft["properties"].get("name") or "Aussichtspunkt", "p": ft["geometry"]["coordinates"][:2]}
                            for ft in json.load(f).get("features", []) if ft["properties"].get("kind") == "viewpoint"]
    with open(cache, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False)
    return out


def _dense(lines, step=25.0):
    out = []
    for line in lines:
        for a, b in zip(line, line[1:]):
            n = max(1, int(dist(a, b) / step))
            out += [[a[0] + (b[0] - a[0]) * i / n, a[1] + (b[1] - a[1]) * i / n] for i in range(n)]
        out.append(line[-1])
    return out


def _merged(cells, bike):
    key = (tuple(cells), bike)
    if key not in _mem:
        routes, lakes, views = {}, {}, []
        for c in cells:
            if not CE.have(*c):
                continue
            d = read_cell(*c)
            for r in d["routes"]:
                if r["kind"] == ("bike" if bike else "hike"):
                    g = routes.setdefault(label(r["label"], None), {"net": 0, "lines": [], "trail": True})
                    g["net"], g["trail"] = max(g["net"], r["net"]), g["trail"] and r.get("trail", False)
                    g["lines"] += r["lines"]
            for lk in d["lakes"]:
                lakes.setdefault(lk["name"], []).extend(lk["lines"])
            views += d["views"]
        real = [k.lower() for k, g in routes.items() if not g["trail"]]
        for k in [k for k, g in routes.items() if g["trail"] and any(k.lower() in r for r in real)]:
            del routes[k]  # a trail label that only repeats a real route
        _mem[key] = (routes, lakes, views)
    return _mem[key]


def load(start, reach_m, bike, ranks):
    """The named things within reach_m of the start: [{kind: route | lake | view, name, rank 0..1, dense: points}]."""
    routes, lakes, views = _merged(CE.cells_for(start[0], start[1], reach_m), bike)
    out = []
    for name, g in routes.items():
        near = [p for p in _dense(g["lines"]) if dist(start, p) <= reach_m]
        if len(near) * 25.0 >= (1000.0 if g["trail"] else 25.0):
            out.append({"kind": "route", "name": name, "rank": ranks[NET.get(g["net"], "l")], "dense": near})
    for name, lines in lakes.items():
        near = [p for p in _dense(lines) if dist(start, p) <= reach_m]
        if len(near) * 25.0 >= 600.0 and not NOT_A_LAKE.search(name):
            out.append({"kind": "lake", "name": name, "rank": 1.0, "dense": near})
    out += [{"kind": "view", "name": v["name"], "rank": 0.5, "dense": [v["p"]]} for v in views if dist(start, v["p"]) <= reach_m]
    return out


def known_lines(start, reach_m, bike):
    """The known routes around the start as lines for the page's overlay: [{name, net, trail, lines}]."""
    out = []
    for name, g in _merged(CE.cells_for(start[0], start[1], reach_m), bike)[0].items():
        lines = [[[round(q[0], 5), round(q[1], 5)] for q in ln[::2] + ln[-1:]] for ln in g["lines"]
                 if dist(start, ln[0]) <= reach_m or dist(start, ln[-1]) <= reach_m]
        if lines:
            out.append({"name": name, "net": NET.get(g["net"], "l") + ("cn" if bike else "wn"), "trail": g["trail"], "lines": lines})
    return out


class Index:
    """Which named thing is a point on? A grid of the things' dense points (cells of ~30 m); lakes reach two cells
    further (a shore path often runs 50–100 m from the water)."""
    CX, CY = 0.00045, 0.00028

    def __init__(self, things):
        self.things, self.cells = things, {}
        for i, a in enumerate(things):
            pad = 2 if a["kind"] == "lake" else 0
            for p in a["dense"]:
                cx, cy = int(p[0] / self.CX), int(p[1] / self.CY)
                for dx in range(-pad, pad + 1):
                    for dy in range(-pad, pad + 1):
                        self.cells.setdefault((cx + dx, cy + dy), set()).add(i)

    def at(self, p, reach=1):
        cx, cy, hit = int(p[0] / self.CX), int(p[1] / self.CY), set()
        for dx in range(-reach, reach + 1):
            for dy in range(-reach, reach + 1):
                hit |= self.cells.get((cx + dx, cy + dy), set())
        return hit
