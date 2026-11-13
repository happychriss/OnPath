#!/usr/bin/env python3
"""Tour-suggestions POC – the AI's region picks as a bonus for a loop (the interface for "mit / ohne KI").

Reads the files `region_ai.py` wrote per map area (~/onpath-suggest-poc/region-ai/10-x-y.json, version 2).
Never calls the AI; a missing or old file is an empty list, never an error.

    picks = load(start_lonlat, reach_m, activity)      # the picks in reach, for this activity
    hits  = along(route_points, picks)                  # which picks one loop touches, and how much
    value, tips, km = bonus(route_points, picks, weights)   # one number 0–1, the card's lines, km on picks

Wiring (suggest.py / netsearch.py, only when the wish has `ai`): the score part `ai` = value with its weight from
weights.json (`score.weights.ai`, knobs under `ai`), the tips on the card, and the picks as extra destinations for
the generator.

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
    "min_km": 0.25,                                            # less than this on a line / in an area = not touched
    "rang": {"1": 0.4, "2": 0.7, "3": 1.0},                    # value per metre on a pick of that rang
    "route_pick_factor": 0.5,                                  # a pick that is a signed route: `signed` already pays for it
    "full_share": 0.5,                                         # this rang-weighted share of the loop = bonus 1.0
    "point_value": 0.06,                                       # a sight passed adds this x rang value ...
    "point_cap": 0.15,                                         # ... up to this in all
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


_prep = {}


def prepare(picks):
    """The picks' geometry in a 100 m grid, built once per list of picks (a request scores many loops against it)."""
    hit = _prep.get(id(picks))
    if hit and hit[0] is picks:
        return hit[1]
    lat0 = next((q[1] for pk in picks for q in _points(pk["geom"])), 50.0)
    gy = 100.0 / 111320.0
    gx = gy / math.cos(math.radians(lat0))
    lines, points, areas = {}, {}, []
    for pi, pk in enumerate(picks):
        g = pk["geom"]
        t = g["type"]
        if t == "area":
            areas.append((pi, g["rings"], [(min(q[0] for q in r), min(q[1] for q in r), max(q[0] for q in r),
                                            max(q[1] for q in r)) for r in g["rings"]]))
        elif t == "point":
            q = g["p"]
            points.setdefault((int(q[0] / gx), int(q[1] / gy)), []).append((pi, q[0], q[1]))
        else:
            for l in g["lines"]:
                for q in _densify(l, 30.0):
                    lines.setdefault((int(q[0] / gx), int(q[1] / gy)), []).append((pi, q[0], q[1]))
    P = {"gx": gx, "gy": gy, "lines": lines, "points": points, "areas": areas}
    _prep.clear()
    _prep[id(picks)] = (picks, P)
    return P


def cover(route_points, picks, weights=None):
    """The loop cut into ~30 m points and, per pick, which of them lie on / in it.
    -> (route, wt_km per point, [(pick, set of point indexes)])"""
    W = dict(DEFAULTS, **(weights or {}))
    step = 30.0
    route = _densify(route_points, step)
    if not route or not picks:
        return route, [], []
    # the loop's own points can be closer than `step`: a point stands for the way around it, not for 30 m
    seg = [dist_m(route[i], route[i + 1]) for i in range(len(route) - 1)]
    wt = [((seg[i - 1] if i else 0.0) + (seg[i] if i < len(seg) else 0.0)) / 2000.0 for i in range(len(route))]
    P = prepare(picks)
    gx, gy = P["gx"], P["gy"]
    near = [W["near_m"].get(pk["geom"]["type"], 0.0) for pk in picks]
    on = {}
    for i, p in enumerate(route):
        cx, cy = int(p[0] / gx), int(p[1] / gy)
        seen = set()
        for grid, r in ((P["lines"], 1), (P["points"], 2)):
            if not grid:
                continue
            for dx in range(-r, r + 1):
                for dy in range(-r, r + 1):
                    for pi, x, y in grid.get((cx + dx, cy + dy), ()):
                        if pi not in seen and dist_m(p, (x, y)) <= near[pi]:
                            seen.add(pi)
        for pi, rings, boxes in P["areas"]:
            if any(b[0] <= p[0] <= b[2] and b[1] <= p[1] <= b[3] and _in_ring(p, r) for r, b in zip(rings, boxes)):
                seen.add(pi)
        for pi in seen:
            on.setdefault(pi, set()).add(i)
    return route, wt, [(picks[pi], idx) for pi, idx in sorted(on.items())]


def sample(pick, n=40):
    """Up to n points of a pick for the generator: where a loop could be led to."""
    pts = _points(pick["geom"])
    if len(pts) > n:
        k = len(pts) / float(n)
        pts = [pts[int(i * k)] for i in range(n)]
    return pts


def along(route_points, picks, weights=None):
    """Which picks this loop touches: [{id, name, kind, rang, warum, type, km | passes}] – lines / shores: km of the
    loop on them; areas: km inside; points: passes within reach."""
    W = dict(DEFAULTS, **(weights or {}))
    route, wt, cov = cover(route_points, picks, weights)
    hits = []
    for pk, on in cov:
        t = pk["geom"]["type"]
        h = {"id": pk["id"], "name": pk["name"], "kind": pk["kind"], "rang": pk["rang"], "warum": pk.get("warum", ""), "type": t}
        if t == "point":
            hits.append(dict(h, passes=True))
        else:
            km = sum(wt[i] for i in on)
            if km >= W["min_km"]:
                hits.append(dict(h, km=round(km, 1)))
    return hits


def bonus(route_points, picks, weights=None):
    """-> (value 0–1, tips, km on picks). Depth, not breadth: every ~30 m of the loop counts once, with the rang value
    of the best pick it lies on / in (rang 3 > 2 > 1); the sum over the loop's length is the share "on the AI's
    recommendations", full at `full_share`. A pick that is a signed route counts only `route_pick_factor` of that
    (the `signed` score part already pays for signed routes by their rank – the AI adds its own ranking on top, not
    a second full reward). A sight passed (point pick) adds a small fixed amount, capped."""
    W = dict(DEFAULTS, **(weights or {}))
    route, wt, cov = cover(route_points, picks, weights)
    if not route:
        return 0.0, [], 0.0
    total_km = sum(wt) or 1.0
    best = {}
    any_on = set()
    tips, pts = [], 0.0
    for pk, on in cov:
        rw = float(W["rang"].get(str(pk["rang"]), 0.5))
        t = pk["geom"]["type"]
        if t == "point":
            v = rw * W["point_value"]
            pts += v
            tips.append({"id": pk["id"], "name": pk["name"], "rang": pk["rang"], "value": round(v, 3), "km": None,
                         "how": "am Weg", "warum": pk.get("warum", "")})
            continue
        km = sum(wt[i] for i in on)
        if km < W["min_km"]:
            continue
        f = rw * (W["route_pick_factor"] if pk["kind"].startswith("route") else 1.0)
        for i in on:
            if f > best.get(i, 0.0):
                best[i] = f
        any_on |= on
        tips.append({"id": pk["id"], "name": pk["name"], "rang": pk["rang"], "value": round(f * km / total_km, 3),
                     "km": round(km, 1), "how": ("%.1f km" % km).replace(".", ","), "warum": pk.get("warum", "")})
    depth = sum(wt[i] * f for i, f in best.items()) / total_km
    value = min(1.0, depth / W["full_share"] + min(W["point_cap"], pts))
    tips.sort(key=lambda t: -t["value"])
    for t in tips:
        t["text"] = "KI-Tipp: %s (%s)" % (t["name"], t["how"])
    return round(value, 3), tips, round(sum(wt[i] for i in any_on), 1)


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
            v, tips, kmp = bonus(c["coords"], picks)
            print("  %-34s %5.1f km  Punkte %5.1f  KI-Bonus %.2f  (%.1f km auf Tipps)" % (c["name"][:34], c["numbers"]["km"], c["score"], v, kmp))
            for t in tips[:4]:
                print("      " + t["text"][:150])


if __name__ == "__main__":
    _selftest()
