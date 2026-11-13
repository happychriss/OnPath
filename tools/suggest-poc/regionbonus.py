#!/usr/bin/env python3
"""Tour-suggestions POC – the AI's region picks as a bonus for a loop (the interface for "mit / ohne KI").

Reads the files `region_ai.py` wrote per map area (~/onpath-suggest-poc/region-ai/10-x-y.json, version 2).
Never calls the AI; a missing or old file is an empty list, never an error.

    picks = load(start_lonlat, reach_m, activity)      # the picks in reach, for this activity
    hits  = along(route_points, picks)                  # which picks one loop touches, and how much
    value, tips = bonus(route_points, picks, weights)   # one number 0–1 + the lines for the card

Wiring (not done here – after round 5): a score part `ai` = value with a weight from weights.json, an on / off
switch "mit KI-Regionswissen" on the page; `tips` on the card; `targets(picks)` gives the generator extra
destinations (a point per pick) next to the map's own.

    python3 tools/suggest-poc/regionbonus.py            # self-test on saved runs (Zons bike, Ohligser Heide)
"""
import glob
import json
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cells as CE  # noqa: E402

OUT = os.path.join(CE.DATA, "region-ai")
DEFAULTS = {
    "near_m": {"line": 40.0, "shore": 90.0, "point": 150.0},   # how close counts as "on it"
    "need_frac": {"line": 0.12, "shore": 0.06, "area": 0.08},  # share of the loop that earns a pick's full value …
    "need_km": {"line": [1.0, 8.0], "shore": [0.5, 3.0], "area": [0.5, 4.0]},  # … within these bounds
    "min_km": 0.25,                                            # less than this on a line / in an area = not touched
    "rang": {"1": 0.5, "2": 0.8, "3": 1.0},
    "point_value": 0.5,                                        # a sight passed counts half of a way followed
    "full_at": 3.0,                                            # this sum of values = bonus 1.0
}
_cache = {}


def dist_m(a, b):
    k = math.cos(math.radians((a[1] + b[1]) / 2))
    return math.hypot((a[0] - b[0]) * k, a[1] - b[1]) * 111320.0


def _points(g):
    if g["type"] == "point":
        return [g["p"]]
    return [p for l in g.get("lines", g.get("rings", [])) for p in l]


def _file(x, y):
    p = os.path.join(OUT, "10-%d-%d.json" % (x, y))
    try:
        st = os.path.getmtime(p)
    except OSError:
        return []
    if _cache.get(p, (None,))[0] != st:
        try:
            with open(p, encoding="utf-8") as f:
                d = json.load(f)
            _cache[p] = (st, d.get("picks", []) if d.get("version") == 2 else [])
        except (OSError, ValueError):
            _cache[p] = (st, [])
    return _cache[p][1]


def load(start, reach_m, activity="hike"):
    """The AI's picks within reach_m of the start that fit the activity. One entry per object, even when two
    neighbouring map areas both picked it (the higher rang wins; lines / rings are joined)."""
    want = "wandern" if activity == "hike" else "rad"
    out = {}
    for x, y in CE.cells_for(start[0], start[1], reach_m):
        for p in _file(x, y):
            if p.get("fuer", "beides") not in ("beides", want) or p["geom"].get("big"):
                continue
            if min(dist_m(start, q) for q in _points(p["geom"])) > reach_m:
                continue
            cur = out.get(p["id"])
            if cur is None:
                out[p["id"]] = {k: (json.loads(json.dumps(v)) if k == "geom" else v) for k, v in p.items()}
            else:
                cur["rang"] = max(cur["rang"], p["rang"])
                for k in ("lines", "rings"):
                    if k in p["geom"]:
                        cur["geom"].setdefault(k, []).extend(p["geom"][k])
    return list(out.values())


def _densify(line, step):
    out = []
    for i in range(len(line) - 1):
        a, b = line[i], line[i + 1]
        n = max(1, int(dist_m(a, b) / step))
        out += [[a[0] + (b[0] - a[0]) * j / n, a[1] + (b[1] - a[1]) * j / n] for j in range(n)]
    if line:
        out.append(line[-1])
    return out


def _in_ring(p, ring):
    x, y, inside = p[0], p[1], False
    j = len(ring) - 1
    for i in range(len(ring)):
        xi, yi, xj, yj = ring[i][0], ring[i][1], ring[j][0], ring[j][1]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
            inside = not inside
        j = i
    return inside


def along(route_points, picks, weights=None):
    """Which picks this loop touches: [{id, name, kind, rang, warum, km | passes}] – lines / shores: km of the loop
    on them; areas: km inside; points: passes within reach."""
    W = dict(DEFAULTS, **(weights or {}))
    step = 30.0
    route = _densify(route_points, step)
    if not route:
        return []
    # the loop's own points can be closer than `step`: a point stands for the way around it, not for 30 m
    seg = [dist_m(route[i], route[i + 1]) for i in range(len(route) - 1)]
    wt = [((seg[i - 1] if i else 0.0) + (seg[i] if i < len(seg) else 0.0)) / 2000.0 for i in range(len(route))]
    lat0 = route[0][1]
    gy = 100.0 / 111320.0
    gx = gy / math.cos(math.radians(lat0))
    grid = {}
    for i, p in enumerate(route):
        grid.setdefault((int(p[0] / gx), int(p[1] / gy)), []).append(i)
    rb = (min(p[0] for p in route), min(p[1] for p in route), max(p[0] for p in route), max(p[1] for p in route))
    hits = []
    for pk in picks:
        g = pk["geom"]
        t = g["type"]
        if t == "area":
            km = 0.0
            rings = [r for r in g["rings"] if not (max(q[0] for q in r) < rb[0] or min(q[0] for q in r) > rb[2]
                                                   or max(q[1] for q in r) < rb[1] or min(q[1] for q in r) > rb[3])]
            if rings:
                boxes = [(min(q[0] for q in r), min(q[1] for q in r), max(q[0] for q in r), max(q[1] for q in r)) for r in rings]
                for i, p in enumerate(route):
                    if any(b[0] <= p[0] <= b[2] and b[1] <= p[1] <= b[3] and _in_ring(p, r) for r, b in zip(rings, boxes)):
                        km += wt[i]
            if km >= W["min_km"]:
                hits.append({"id": pk["id"], "name": pk["name"], "kind": pk["kind"], "rang": pk["rang"], "warum": pk.get("warum", ""), "type": t, "km": round(km, 1)})
            continue
        near = W["near_m"]["point" if t == "point" else t]
        reach = int(near / 100.0) + 1
        on = set()
        pts = [g["p"]] if t == "point" else [q for l in g["lines"] for q in _densify(l, 30.0)]
        for q in pts:
            cx, cy = int(q[0] / gx), int(q[1] / gy)
            for dx in range(-reach, reach + 1):
                for dy in range(-reach, reach + 1):
                    for i in grid.get((cx + dx, cy + dy), ()):
                        if i not in on and dist_m(route[i], q) <= near:
                            on.add(i)
        if t == "point":
            if on:
                hits.append({"id": pk["id"], "name": pk["name"], "kind": pk["kind"], "rang": pk["rang"], "warum": pk.get("warum", ""), "type": t, "passes": True})
        else:
            km = sum(wt[i] for i in on)
            if km >= W["min_km"]:
                hits.append({"id": pk["id"], "name": pk["name"], "kind": pk["kind"], "rang": pk["rang"], "warum": pk.get("warum", ""), "type": t, "km": round(km, 1)})
    return hits


def bonus(route_points, picks, weights=None):
    """-> (value 0–1, tips): how much of what the AI recommends this loop really takes, and the card's lines."""
    W = dict(DEFAULTS, **(weights or {}))
    route_km = sum(dist_m(route_points[i], route_points[i + 1]) for i in range(len(route_points) - 1)) / 1000.0
    total, tips = 0.0, []
    for h in along(route_points, picks, weights):
        rw = float(W["rang"].get(str(h["rang"]), 0.5))
        if h["type"] == "point":
            v = rw * W["point_value"]
            how = "am Weg"
        else:
            lo, hi = W["need_km"][h["type"]]
            need = min(hi, max(lo, W["need_frac"][h["type"]] * route_km))
            v = rw * min(1.0, h["km"] / need)
            how = "%.1f km" % h["km"]
        total += v
        tips.append({"id": h["id"], "name": h["name"], "rang": h["rang"], "value": round(v, 2), "how": how,
                     "text": "KI-Tipp: %s (%s) – %s" % (h["name"], how, h["warum"])})
    tips.sort(key=lambda t: -t["value"])
    return round(min(1.0, total / W["full_at"]), 3), tips


def targets(picks):
    """A point per pick for the generator (extra destinations): the middle point of a line / ring, the point itself."""
    out = []
    for p in picks:
        pts = _points(p["geom"])
        out.append({"id": p["id"], "name": p["name"], "rang": p["rang"], "p": pts[len(pts) // 2]})
    return out


def _selftest():
    runs = sorted(glob.glob(os.path.join(CE.DATA, "runs", "*.json")), key=os.path.getmtime, reverse=True)
    want = {"Zons Rad 3 h": lambda w: w.get("activity") == "bike" and abs(w["start"][0] - 6.8448) < 0.01 and abs(w["start"][1] - 51.1231) < 0.01,
            "Ohligser Heide Wandern 2 h": lambda w: w.get("activity", "hike") == "hike" and abs(w["start"][0] - 6.9866) < 0.01 and abs(w["start"][1] - 51.1645) < 0.01,
            "Meran Wandern 3 h": lambda w: w.get("activity", "hike") == "hike" and abs(w["start"][0] - 11.16) < 0.03 and abs(w["start"][1] - 46.67) < 0.02}
    for title, ok in want.items():
        for f in runs:
            try:
                with open(f, encoding="utf-8") as fh:
                    d = json.load(fh)
            except (OSError, ValueError):
                continue
            if d.get("method") == "b" and ok(d["wish"]) and any(c.get("chosen") and c.get("coords") for c in d.get("candidates", [])):
                break
        else:
            print("\n== %s: kein gespeicherter Lauf gefunden" % title)
            continue
        w = d["wish"]
        reach = max(3000.0, d.get("target_km", 8) * 1000 * 0.5)
        picks = load(w["start"], reach, w.get("activity", "hike"))
        print("\n== %s · Lauf %s · %d KI-Tipps in Reichweite (%.0f km): %s" % (
            title, d["run"], len(picks), reach / 1000, ", ".join("%s(r%d)" % (p["name"], p["rang"]) for p in picks[:12])))
        for c in [c for c in d["candidates"] if c.get("chosen")]:
            v, tips = bonus(c["coords"], picks)
            print("  %-34s %5.1f km  Punkte %5.1f  KI-Bonus %.2f" % (c["name"][:34], c["numbers"]["km"], c["score"], v))
            for t in tips[:4]:
                print("      " + t["text"][:150])


if __name__ == "__main__":
    _selftest()
