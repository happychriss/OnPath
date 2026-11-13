"""Tour-suggestions POC – wish -> loop candidates -> scores -> selection (docs/design/tour-suggestions.md).

Plain Python, stdlib only. Routing comes from the BRouter route service (service/RouteService.java);
every knob is in weights.json, which is read again on every request.
"""
import copy
import json
import math
import os
import random
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

import attractions as AT

HERE = os.path.dirname(os.path.abspath(__file__))
SERVICE = os.environ.get("SUGGEST_SERVICE", "http://127.0.0.1:8791")
DATA = os.environ.get("SUGGEST_DATA", os.path.expanduser("~/onpath-suggest-poc"))

DIR_NAMES = ["N", "NNO", "NO", "ONO", "O", "OSO", "SO", "SSO", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"]
ACTIVITIES = ["hike", "bike", "gravel", "mtb"]


def load_weights(path=None):
    with open(path or os.environ.get("SUGGEST_WEIGHTS") or os.path.join(HERE, "weights.json"), encoding="utf-8") as f:
        return json.load(f)


def _merge(base, over):
    for k, v in over.items():
        if isinstance(v, dict) and isinstance(base.get(k), dict):
            _merge(base[k], v)
        else:
            base[k] = v
    return base


def effective(W, activity):
    """weights.json with the activity's overrides laid over it."""
    E = copy.deepcopy(W)
    act = W["activities"].get(activity, {})
    _merge(E, {k: v for k, v in act.items() if k not in ("label", "pace", "hours")})
    return E


# ---------------------------------------------------------------- wish

def make_wish(start, start_name="", hours=2.0, pace=4.0, t_level=2, effort="relaxed", forest=0.5, quiet=0.5,
              water=0.0, signed=0.3, natural=0.5, seed=1, activity="hike", surface=None):
    """start = [lon, lat]. pace = km/h on the flat. effort = relaxed | medium | sporty. Taste sliders 0..1."""
    return {"start": [float(start[0]), float(start[1])], "start_name": start_name, "hours": float(hours),
            "pace": float(pace), "t_level": int(t_level), "effort": effort, "forest": float(forest),
            "quiet": float(quiet), "water": float(water), "signed": float(signed), "natural": float(natural),
            "seed": int(seed), "activity": activity if activity in ACTIVITIES else "hike", "surface": surface}


def simple_wish(b, W):
    """The few controls (activity, duration, pace word, effort word, Natur / Ruhe / Bekannte Wege as niedrig | mittel |
    hoch, Wasser yes/no) -> the engine's wish. b["fein"] = optional exact sliders that win over the levels."""
    s = W["simple"]
    act = b.get("activity", "hike")
    lv = lambda k: s["levels"].get(b.get(k, "mittel"), 0.5)
    paces = W["activities"][act]["pace"]
    pace = b.get("pace", "normal")
    pace = paces.get(pace, paces["normal"]) if isinstance(pace, str) else float(pace)
    nat = lv("natur")
    w = make_wish(b["start"], b.get("start_name", ""), b.get("hours", 2), pace, b.get("t_level", 2),
                  s["effort"].get(b.get("effort", "leicht"), b.get("effort", "relaxed")), nat, lv("ruhe"),
                  s["wasser_on"] if b.get("wasser") else s["wasser_off"], lv("bekannt"),
                  nat * s["natur_natural_factor"], b.get("seed", 1), act, b.get("surface"))
    so = W["surface"].get(w["activity"], W["surface"]["hike"])
    if w["surface"] not in so["options"]:
        w["surface"] = so["default"]
    for k, v in (b.get("fein") or {}).items():
        if k in ("forest", "quiet", "water", "signed", "natural") and v is not None:
            w[k] = float(v)
    w["simple"] = {k: b.get(k) for k in ("natur", "ruhe", "bekannt", "wasser", "pace", "effort")}
    w["method"] = "b" if str(b.get("method", "a")).lower() == "b" else "a"
    return w


def find_place(name, W):
    """Built-in list first (weights.json 'places'), then Nominatim (online). Returns (label, [lon, lat]) or None."""
    n = name.strip().lower()
    places = {k: v for k, v in W["places"].items() if not k.startswith("_")}
    for k, v in places.items():
        if k.lower() == n:
            return k, v
    for k, v in places.items():
        if k.lower().startswith(n):
            return k, v
    try:
        lon, lat = [float(x) for x in name.replace(";", ",").split(",")]
        if lon > lat:  # in Europe lat > lon: accept either order
            lon, lat = lat, lon
        return "%.5f, %.5f" % (lat, lon), [lon, lat]
    except ValueError:
        pass
    try:
        url = "https://nominatim.openstreetmap.org/search?format=json&limit=1&q=" + urllib.parse.quote(name)
        req = urllib.request.Request(url, headers={"User-Agent": "onpath-suggest-poc"})
        r = json.load(urllib.request.urlopen(req, timeout=8))
        if r:
            return r[0]["display_name"].split(",")[0], [float(r[0]["lon"]), float(r[0]["lat"])]
    except Exception:  # expected: no network / no hit – the caller says "Ort nicht gefunden"
        pass
    return None


# ---------------------------------------------------------------- route service

def service_up():
    try:
        urllib.request.urlopen(SERVICE + "/ping", timeout=1).read()
        return True
    except Exception:  # expected: not started yet
        return False


def ensure_service():
    if service_up():
        return
    os.makedirs(DATA, exist_ok=True)
    log = open(os.path.join(DATA, "service.log"), "a")
    env = dict(os.environ, THREADS=str(load_weights()["generator"]["threads"]))
    port = SERVICE.rsplit(":", 1)[1].strip("/")
    seg = os.environ.get("ONPATH_SEGMENTS", os.path.expanduser("~/onpath-routing/segments"))
    subprocess.Popen([os.path.join(HERE, "service", "start.sh"), seg, port], stdout=log, stderr=log, env=env,
                     start_new_session=True)
    for _ in range(60):
        time.sleep(0.5)
        if service_up():
            return
    raise RuntimeError("route service did not start – see " + os.path.join(DATA, "service.log"))


def route(points, kv, nogos, W):
    lines = ["profile=" + W["routing"]["profile"], "timeout=%d" % W["generator"]["leg_timeout_ms"]]
    lines += ["kv=%s=%s" % (k, v) for k, v in kv.items()]
    lines.append("pts=" + ";".join("%.6f,%.6f" % (p[0], p[1]) for p in points))
    for weight, line in nogos:
        lines.append("nogo=%s;%s" % (weight, ";".join("%.6f,%.6f" % (p[0], p[1]) for p in line)))
    req = urllib.request.Request(SERVICE + "/route", data="\n".join(lines).encode())
    return json.load(urllib.request.urlopen(req, timeout=90))


def profile_kv(wish, W):
    r = W["routing"]
    on = lambda k, name: int(wish[k] >= r[name])
    kv = {"consider_forest": on("forest", "consider_forest_from"), "consider_noise": on("quiet", "consider_noise_from"),
          "consider_river": on("water", "consider_river_from"), "consider_town": on("quiet", "consider_town_from")}
    if wish["activity"] == "hike":
        t = max(1, min(5, wish["t_level"]))
        kv.update({"SAC_scale_limit": t, "SAC_scale_preferred": min(t, 3),
                   "consider_elevation": int(wish["effort"] == "relaxed" and r["relaxed_considers_elevation"]),
                   "hiking_routes_preference": round(r["hiking_routes_preference_base"]
                                                     + r["hiking_routes_preference_per_signed"] * wish["signed"], 2),
                   "mainroad_penalty": round(r["mainroad_penalty_base"] + r["mainroad_penalty_per_quiet"] * wish["quiet"], 2)})
    else:
        kv.update({"consider_traffic": 1, "consider_elevation": int(wish["effort"] != "sporty"),
                   "mainroad_penalty": round(r["mainroad_penalty_base"] + r["mainroad_penalty_per_quiet"] * wish["quiet"], 2)})
        if wish["quiet"] >= r["avoid_unsafe_from"]:
            kv["avoid_unsafe"] = 1
        if wish["signed"] >= r["stick_to_cycleroutes_from"]:
            kv["stick_to_cycleroutes"] = 1
    kv.update(r.get("extra_kv", {}))
    return kv


# ---------------------------------------------------------------- geometry

def offset(p, bearing_deg, dist_m):
    b = math.radians(bearing_deg)
    dlat = dist_m * math.cos(b) / 111320.0
    dlon = dist_m * math.sin(b) / (111320.0 * math.cos(math.radians(p[1])))
    return [p[0] + dlon, p[1] + dlat]


def dist(a, b):
    kx = 111320.0 * math.cos(math.radians((a[1] + b[1]) / 2))
    return math.hypot((a[0] - b[0]) * kx, (a[1] - b[1]) * 111320.0)


def bearing(a, b):
    kx = math.cos(math.radians((a[1] + b[1]) / 2))
    return math.degrees(math.atan2((b[0] - a[0]) * kx, b[1] - a[1])) % 360


def key(p):
    return (round(p[0] * 1e6), round(p[1] * 1e6))


def ekey(a, b):
    ka, kb = key(a), key(b)
    return (ka, kb) if ka <= kb else (kb, ka)


def shape_points(start, bearing_deg, shape, air_len, rnd, jitter, clockwise, stem=None):
    """Via points of a loop through the start: a circle of perimeter air_len whose centre lies in `bearing`."""
    r = air_len / (2 * math.pi)
    centre = offset(start, bearing_deg, r)
    angles = [120, 240] if shape == "dreieck" else [60, 120, 180, 240, 300] if shape == "sechseck" else [90, 180, 270]
    if shape == "keule":  # a stem out, a smaller loop at its end, the stem back (the way back is pushed off the way out)
        st = 0.25 * air_len if stem is None else stem
        r = max(0.25 * air_len, air_len - 2 * st) / (2 * math.pi)
        centre = offset(start, bearing_deg, st + r)
    if not clockwise:
        angles = [360 - a for a in angles]
    pts = []
    for a in angles:
        rr = r * (1 + rnd.uniform(-jitter, jitter))
        # the start sits at bearing+180 seen from the centre; walk round from there
        pts.append(offset(centre, bearing_deg + 180 + a + rnd.uniform(-jitter, jitter) * 60, rr))
    return pts


def ring_vias(start, a, budget_m, clockwise):
    """A ring destination (lake shore, circular signed route): enter at the nearest point, go round (as far as the
    budget allows), leave. None when not even a quarter fits."""
    pts = a["points"]
    cx = sum(p[0] for p in pts) / len(pts)
    cy = sum(p[1] for p in pts) / len(pts)
    order = sorted(pts, key=lambda p: bearing([cx, cy], p))
    if not clockwise:
        order.reverse()
    i0 = min(range(len(order)), key=lambda i: dist(start, order[i]))
    f = min(1.0, (budget_m - 2.6 * a["d"]) / max(1.0, a["length_m"] * 1.1))
    if f < 0.25:
        return None
    n = len(order)
    fr = [0.0, 0.25, 0.5, 0.75] if f >= 0.95 else [0.0, f / 3, 2 * f / 3, f]
    vias = []
    for x in fr:
        p = order[(i0 + int(x * n)) % n]
        if p not in vias:
            vias.append(p)
    return vias


# ---------------------------------------------------------------- one candidate

def parse_tags(s):
    d = {}
    for x in s.split(" "):
        if "=" in x:
            k, v = x.split("=", 1)
            d[k] = v
    return d


def leg_edges(r):
    """BRouter's aggregated messages -> one edge per coordinate pair: [a, b, metres, way tags, node tags]."""
    coords = r["coords"]
    edges = []
    i = 0
    for m in r["msgs"][1:]:
        f = m.split("\t")
        end = (int(f[0]), int(f[1]))
        tags = parse_tags(f[9])
        ntags = parse_tags(f[10]) if len(f) > 10 else {}
        j = i + 1
        while j < len(coords) and key(coords[j]) != end:
            j += 1
        if j >= len(coords):  # no exact hit (should not happen): give the rest to this row
            j = len(coords) - 1
        for k in range(i, j):
            edges.append([coords[k], coords[k + 1], dist(coords[k], coords[k + 1]), tags, ntags if k == j - 1 else {}])
        i = j
        if i >= len(coords) - 1:
            break
    return edges


def trim_spikes(legs):
    """Joins the legs; an out-and-back at a via point (… A X A …) is cut out."""
    out = []
    for edges in legs:
        edges = list(edges)
        while out and edges and key(out[-1][0]) == key(edges[0][1]):
            out.pop()
            edges.pop(0)
        out += edges
    return out


def build(cand, wish, W, kv):
    g = W["generator"]
    target_h = wish["hours"]
    target_m = target_h * wish["pace"] * 1000.0
    air = target_m / g["detour_factor"]
    best = None
    ms = 0
    nlegs = 0
    tgt = cand.get("target")
    ring = tgt is not None and cand.get("mode") == "ring"
    for it in range(1 if ring else g["fit_iterations"] + 1):
        rnd = random.Random(cand["seed"])
        if ring:
            vias = ring_vias(wish["start"], tgt, target_m, cand["cw"])
            if not vias:
                return None
        else:
            vias = shape_points(wish["start"], cand["bearing"], cand["shape"], air, rnd, g["via_jitter"], cand["cw"],
                                stem=min(tgt["d"], 0.4 * air) if tgt is not None and cand["shape"] == "keule" else None)
            good = tgt["points"] if tgt is not None else cand.get("good")
            if good:  # pull each via point to the nearest pleasant spot (second pass) / onto the destination
                limit = g["snap_max_factor"] * air / (2 * math.pi) * (g["target_snap_factor"] if tgt is not None else 1)
                snapped = []
                for v in vias:
                    d, gp = min((dist(v, x), x) for x in good)
                    snapped.append(gp if d <= limit and gp not in snapped else v)
                vias = snapped
        pts = [wish["start"]] + vias + [wish["start"]]
        legs, nogos, ascend, err = [], [], 0, None
        for a, b in zip(pts, pts[1:]):
            r = route([a, b], kv, nogos if g["back_penalty"] > 0 else [], W)
            nlegs += 1
            ms += r.get("ms", 0)
            if "error" in r:
                err = r["error"]
                break
            legs.append(leg_edges(r))
            ascend += r["ascend"]
            nogos.append((g["back_penalty"], [c[:2] for c in r["coords"]]))
        if err:
            air *= 0.85  # a via point without a way nearby: try a smaller loop
            cand["error"] = err
            continue
        edges = trim_spikes(legs)
        if not edges:
            continue
        km = sum(e[2] for e in edges) / 1000.0
        hours = km / wish["pace"] + ascend / W["time"]["ascent_m_per_h"]
        fit = abs(hours - target_h) / target_h
        if best is None or fit < best["fit"]:
            best = {"edges": edges, "km": km, "hours": hours, "ascent": ascend, "fit": fit, "vias": vias}
        if fit <= g["fit_tolerance"]:
            break
        air *= max(0.6, min(1.6, target_h / hours))
    if best is None:
        return None
    best.update({k: v for k, v in cand.items() if k not in ("good", "target")})
    if tgt is not None:
        best["target_name"] = tgt["name"]
    best["route_ms"] = ms
    best["legs_routed"] = nlegs
    return best


# ---------------------------------------------------------------- numbers and score

def main_weight(tags, C):
    """How much a piece of way is "along a main road", 0..1: the busy road itself, or a pavement / cycle path beside
    it. Rules in weights.json classes.main (per activity): a calm road (30 km/h) is no main road, a cycle track or
    lane on the road counts milder, a bridge counts little (often the only crossing), a separate cycle path beside
    a loud road counts `beside` – milder than the carriageway, worse than a quiet street."""
    M = C["main"]
    hw = tags.get("highway", "")
    noise = int(tags.get("estimated_noise_class", "0") or 0)
    forest = int(tags.get("estimated_forest_class", "0") or 0)
    w = 0.0
    if hw in C["big_highways"]:
        w = M["base"].get(hw.replace("_link", ""), 1.0)
        if tags.get("maxspeed", "") in M["calm_maxspeed"]:
            w *= M["calm_factor"]
        cw = [tags.get(k, "") for k in ("cycleway", "cycleway:both", "cycleway:right", "cycleway:left")]
        if "track" in cw or "separate" in cw or tags.get("bicycle") == "use_sidepath":
            w *= M["track_factor"]
        elif "lane" in cw:
            w *= M["lane_factor"]
    elif tags.get("footway") == "sidewalk" and noise >= C["sidewalk_main_from_noise"]:
        w = M["sidewalk"]
    elif hw == "cycleway" and noise >= C["cycleway_main_from_noise"] and forest < C["forest_share_from_class"] \
            and (tags.get("estimated_town_class") or not M["beside_needs_town"]):
        w = M["beside"]
    if w and tags.get("bridge") == "yes":
        w *= M["bridge_factor"]
    return w


def edge_class(tags, C):
    """G = along a main road (main_weight >= classes.main.class_from), S = quiet street,
    W = way near forest (class >= forest_share_from_class), P = other way."""
    hw = tags.get("highway", "")
    if main_weight(tags, C) >= C["main"]["class_from"]:
        return "G"
    forest = int(tags.get("estimated_forest_class", "0") or 0)
    sidewalk = tags.get("footway") == "sidewalk"
    street = hw in C["street_highways"] or (sidewalk and C["sidewalk_is_street"])
    if not street and C["lit_town_path_is_street"] and tags.get("lit") == "yes" and tags.get("estimated_town_class") \
            and not forest and hw in ("footway", "cycleway", "path"):
        street = True
    if street:
        return "S"
    return "W" if forest >= C["forest_share_from_class"] else "P"


def surface_class(tags, C):
    """A = asphalt / paved, F = firm (compacted, gravel, a good track), N = natural ground. From surface, else
    tracktype, else the kind of way."""
    surf = tags.get("surface", "")
    if surf:
        return "A" if surf in C["surface_asphalt"] else "F" if surf in C["surface_firm"] else "N"
    tt = tags.get("tracktype", "")
    if tt:
        return "A" if tt == "grade1" else "F" if tt == "grade2" else "N"
    hw = tags.get("highway", "")
    if hw in C["street_highways"] or hw == "cycleway":
        return "A"
    if hw == "track":
        return "F"
    return "A" if tags.get("estimated_town_class") and hw in ("footway", "pedestrian", "steps") else "N"


def surface_values(wish, W):
    """The wish's surface choice -> pleasure value per class {A, F, N} (+ 'avoid': classes to keep off)."""
    so = W["surface"].get(wish.get("activity", "hike"), W["surface"]["hike"])
    return so["options"].get(wish.get("surface") or so["default"], so["options"][so["default"]])


def signed_rank(tags, C, ranks):
    """Rank of the best signed route this way belongs to (routing data flags route_hiking_rwn=yes …), 0 = none."""
    best = 0.0
    for k, v in tags.items():
        if v != "yes":
            continue
        for pre in C["signed_prefixes"]:
            if k.startswith(pre):
                best = max(best, ranks.get(k[len(pre):], ranks[""]))
    return best


def numbers(c, wish, W, index=None):
    C, S = W["classes"], W["score"]
    ranks = W["attractions"]["rank"]
    edges = c["edges"]
    total = sum(e[2] for e in edges) or 1.0
    uses = {}
    for e in edges:
        k = ekey(e[0], e[1])
        uses[k] = uses.get(k, 0) + 1
    m = dict(forest_v=0.0, forest=0.0, noise_v=0.0, loud=0.0, river_v=0.0, street=0.0, big=0.0, paved=0.0,
             signed=0.0, signed_v=0.0, doubled=0.0, town=0.0)
    cls = []
    sv = surface_values(wish, W)
    surf_m = {"A": 0.0, "F": 0.0, "N": 0.0}
    crossings = 0
    by_hw = {}
    named = {}
    main_run, main_gap, main_longest = 0.0, 1e9, 0.0
    ferry_m = 0.0
    for e in edges:
        ln, t, nt = e[2], e[3], e[4]
        k = edge_class(t, C)
        cls.append(k)
        mw = main_weight(t, C)
        if mw >= C["main"]["run_from"]:  # the longest stretch counts weighted metres (a bridge, a cycle track: less)
            main_run = main_run + ln * mw if main_gap <= C["main_gap_m"] else ln * mw
            main_gap = 0.0
            main_longest = max(main_longest, main_run)
        else:
            main_gap += ln
        if t.get("route") == "ferry":
            ferry_m += ln
        fc = t.get("estimated_forest_class", "")
        fv = C["forest_value"].get(fc, 0.0)
        if k in "SG":
            fv *= C["forest_on_street_factor"]
            m["street"] += ln
            if k == "G":
                m["big"] += ln
        elif int(fc or 0) >= C["forest_share_from_class"]:
            m["forest"] += ln
        m["forest_v"] += fv * ln
        nc = t.get("estimated_noise_class", "")
        m["noise_v"] += C["noise_value"].get(nc, 0.0) * ln
        if int(nc or 0) >= C["loud_from_class"]:
            m["loud"] += ln
        m["river_v"] += C["river_value"].get(t.get("estimated_river_class", ""), 0.0) * ln
        if t.get("estimated_town_class"):
            m["town"] += ln
        hw = t.get("highway", "?")
        surf = t.get("surface", "")
        if surf in C["paved_surfaces"] or (not surf and hw in C["street_highways"]):
            m["paved"] += ln
        surf_m[surface_class(t, C)] += ln
        sr = signed_rank(t, C, ranks)
        if sr > 0:
            m["signed"] += ln
            m["signed_v"] += ln * sr
        if uses[ekey(e[0], e[1])] > 1:
            m["doubled"] += ln
        if nt.get("highway") in ("crossing", "traffic_signals"):
            crossings += 1
        by_hw[hw] = by_hw.get(hw, 0.0) + ln
        if index is not None:
            mid = [(e[0][0] + e[1][0]) / 2, (e[0][1] + e[1][1]) / 2]
            near = index.at(mid, 1)
            best_route = None
            for i in index.at(mid, 2):
                a = index.attr[i]
                if a["kind"] == "route":
                    # one name per piece of way: the highest-ranking route on it (then the longer one) – and only
                    # where the way really lies on it (30 m), so a parallel way does not borrow the name
                    # … and on a main road only when the road itself carries a signed route (see netsearch)
                    if i in near and (mw <= 0 or sr > 0) and (
                            best_route is None or (a["rank"], a["length_m"]) >
                            (index.attr[best_route]["rank"], index.attr[best_route]["length_m"])):
                        best_route = i
                    continue
                named[i] = named.get(i, 0.0) + ln
            if best_route is not None:
                named[best_route] = named.get(best_route, 0.0) + ln
    stalk = 0.0
    for e in edges:
        if uses[ekey(e[0], e[1])] < 2:
            break
        stalk += e[2]
    # enclosed area vs a circle of the same length (1 = circle, 0 = out-and-back)
    kx = 111320.0 * math.cos(math.radians(edges[0][0][1]))
    xy = [(e[0][0] * kx, e[0][1] * 111320.0) for e in edges]
    area = abs(sum(xy[i][0] * xy[(i + 1) % len(xy)][1] - xy[(i + 1) % len(xy)][0] * xy[i][1] for i in range(len(xy)))) / 2
    compact = 4 * math.pi * area / (total * total)

    known, lakes, views = [], [], []
    lake_m = 0.0
    if index is not None:
        for i, ln in sorted(named.items(), key=lambda x: -x[1] * index.attr[x[0]]["rank"]):
            a = index.attr[i]
            if a["kind"] == "route" and ln >= 300:
                known.append([a["name"], round(ln / 1000.0, 1), a.get("net", "")])
            elif a["kind"] == "lake" and ln >= 200:
                lakes.append([a["name"], round(ln / 1000.0, 1)])
                lake_m += ln
            elif a["kind"] == "view":
                views.append(a["name"])

    km = total / 1000.0
    n = {"km": round(km, 2), "hours": round(c["hours"], 2), "ascent": int(c["ascent"]),
         "ascent_per_km": round(c["ascent"] / km, 1),
         "forest_pct": round(100 * m["forest"] / total), "forest_value": round(m["forest_v"] / total, 2),
         "loud_pct": round(100 * m["loud"] / total), "noise_value": round(m["noise_v"] / total, 2),
         "water_value": round(m["river_v"] / total, 2), "town_pct": round(100 * m["town"] / total),
         "street_pct": round(100 * m["street"] / total), "big_street_pct": round(100 * m["big"] / total),
         "main_longest_m": int(main_longest), "ferry_m": int(ferry_m),
         "surface_pct": {k: round(100 * v / total) for k, v in surf_m.items()},
         "paved_pct": round(100 * m["paved"] / total), "signed_pct": round(100 * m["signed"] / total),
         "doubled_pct": round(100 * m["doubled"] / total), "stalk_m": int(stalk), "crossings": crossings,
         "compact": round(compact, 2), "known": known[:4], "lakes": lakes[:3], "views": views[:3],
         "by_highway_km": {k: round(v / 1000.0, 2) for k, v in sorted(by_hw.items(), key=lambda x: -x[1])}}

    # parts 0..1
    eff = W["effort"].get(wish["effort"], W["effort"]["medium"])
    apk = c["ascent"] / km
    if "min_m_per_km" in eff:
        ascent_part = min(1.0, apk / eff["min_m_per_km"])
    else:
        mx = eff["max_m_per_km"]
        ascent_part = 1.0 if apk <= mx else max(0.0, 1 - (apk - mx) / (2 * mx))
    street_share = (m["street"] + (S["big_street_counts"] - 1) * m["big"]) / total
    mr = S["main_road_max_m"]
    parts = {
        "length": max(0.0, 1 - c["fit"] / S["length_zero_at"]),
        "ascent": ascent_part,
        "forest": m["forest_v"] / total,
        "quiet": 1 - m["noise_v"] / total,
        # along water: the share near a river / lake (full at water_full_at), or a lake shore walked (full at
        # lake_full_m, on long loops at lake_full_share of the loop) – never full just because one pond was passed
        "water": max(min(1.0, m["river_v"] / total / S["water_full_at"]),
                     min(1.0, lake_m / max(S["lake_full_m"], S["lake_full_share"] * total))),
        "signed": min(1.0, m["signed_v"] / total / S["signed_full_at"]),
        "natural": 1 - m["paved"] / total,
        "surface": sum(surf_m[k] * sv[k] for k in surf_m) / total,
        "street": max(0.0, 1 - street_share / S["street_zero_at"]),
        "mainroad": 1.0 if main_longest <= mr else max(0.0, 1 - (main_longest - mr) / (mr * (S["main_road_zero_factor"] - 1))),
        "doubled": max(0.0, 1 - m["doubled"] / total / S["doubled_zero_at"]),
        "shape": min(1.0, compact / S["shape_full_at"]),
        "crossings": max(0.0, 1 - crossings / km / S["crossings_zero_per_km"]),
    }
    taste = {"forest": wish["forest"], "quiet": wish["quiet"], "water": wish["water"], "signed": wish["signed"],
             "natural": wish["natural"]}
    wsum, ssum = 0.0, 0.0
    for k, p in parts.items():
        w = S["weights"][k] * taste.get(k, 1.0)
        wsum += w
        ssum += w * p
    c["numbers"] = n
    c["parts"] = {k: round(v, 2) for k, v in parts.items()}
    c["score"] = round(100 * ssum / wsum, 1) if wsum else 0.0
    c["cls"] = "".join(cls)
    c["story"] = story(edges, cls)
    c["keys"] = set(uses)


CLS_WORD = {"W": "Waldweg", "P": "Weg", "S": "Straße", "G": "an Hauptstraße"}


def story(edges, cls):
    """The route as a short sequence: 'Straße 0,4 → Waldweg 2,1 → …' (pieces under 150 m join their neighbour)."""
    runs = []
    for e, k in zip(edges, cls):
        if runs and runs[-1][0] == k:
            runs[-1][1] += e[2]
        else:
            runs.append([k, e[2]])
    merged = []
    for k, ln in runs:
        if merged and (ln < 150 or merged[-1][0] == k):
            merged[-1][1] += ln
        else:
            merged.append([k, ln])
    return " → ".join("%s %.1f" % (CLS_WORD[k], ln / 1000.0) for k, ln in merged)


def good_points(built, wish, W):
    """Pleasant spots seen on the first pass' routes (one per ~150 m cell): targets for the second pass' via points.
    Pleasant = not a street and, weighted by the wish, near forest / quiet / near water."""
    g = W["generator"]
    C = W["classes"]
    cells = {}
    for c in built:
        for e, k in zip(c["edges"], c["cls"]):
            if k in "SG":
                continue
            t = e[3]
            v = (wish["forest"] * C["forest_value"].get(t.get("estimated_forest_class", ""), 0.0)
                 + wish["quiet"] * (1 - C["noise_value"].get(t.get("estimated_noise_class", ""), 0.0)) * 0.5
                 + wish["water"] * C["river_value"].get(t.get("estimated_river_class", ""), 0.0))
            norm = wish["forest"] + wish["quiet"] * 0.5 + wish["water"]
            if norm <= 0 or v / norm < g["good_point_from"]:
                continue
            cell = (round(e[0][0] / 0.002), round(e[0][1] / 0.0013))
            if cell not in cells or v > cells[cell][0]:
                cells[cell] = (v, e[0])
    return [p for _, p in cells.values()]


def target_cands(attr, wish, W, rnd):
    """Loops through the destinations in reach: the most relevant first (rank x the wish's taste), at most max_targets."""
    g = W["generator"]
    target_m = wish["hours"] * wish["pace"] * 1000.0
    rel = {"lake": 0.3 + wish["water"], "route": 0.3 + wish["signed"], "view": 0.4}
    ok = [a for a in attr if a["d"] <= g["target_reach_factor"] * target_m
          and not (a["kind"] == "route" and a["length_m"] < 1500)]
    ok.sort(key=lambda a: -(a["rank"] * rel[a["kind"]] * (1.2 if a["ring"] else 1.0)))
    out = []
    for a in ok[:g["max_targets"]]:
        near = min(a["points"], key=lambda p: dist(wish["start"], p))
        b = round(bearing(wish["start"], near)) if a["d"] > 30 else rnd.randrange(360)
        base = {"bearing": b, "target": a}
        if a["ring"]:
            out.append(dict(base, shape="ring", mode="ring", cw=True, seed=rnd.randrange(1 << 30)))
            out.append(dict(base, shape="ring", mode="ring", cw=False, seed=rnd.randrange(1 << 30)))
            out.append(dict(base, shape="keule", mode="pull", cw=rnd.random() < 0.5, seed=rnd.randrange(1 << 30)))
        else:
            out.append(dict(base, shape="keule", mode="pull", cw=True, seed=rnd.randrange(1 << 30)))
            out.append(dict(base, shape="keule", mode="pull", cw=False, seed=rnd.randrange(1 << 30)))
            if a["kind"] == "route":
                out.append(dict(base, shape="dreieck", mode="pull", cw=rnd.random() < 0.5, seed=rnd.randrange(1 << 30)))
    return out


# ---------------------------------------------------------------- the whole request

def suggest(wish, W=None):
    W = effective(W or load_weights(), wish.get("activity", "hike"))
    ensure_service()
    g = W["generator"]
    if wish["hours"] * wish["pace"] >= g.get("long_from_km", 1e9):  # long loops: fewer candidates, more and shorter legs
        g.update(g["long"])
    t0 = time.time()
    rnd = random.Random(wish["seed"])
    kv = profile_kv(wish, W)
    notes = []
    target_m = wish["hours"] * wish["pace"] * 1000.0
    attr, note = AT.load(wish["start"], min(W["attractions"]["max_reach_m"], 0.5 * target_m), wish["activity"], W)
    if note:
        notes.append(note)
    index = AT.Index(attr) if attr else None
    t_attr = time.time() - t0
    method = wish.get("method", "a")
    net_info = None
    cands, tc = [], []
    if method == "b":
        import netsearch as NS
        built, net_info, _ = NS.search(wish, W, kv, attr, index, rnd, sys.modules[__name__], SERVICE)
        for c in built:
            numbers(c, wish, W, index)
    else:
        turn = rnd.uniform(0, 360.0 / g["directions"])
        for i in range(g["directions"]):
            b = (turn + i * 360.0 / g["directions"]) % 360
            for shape in g["shapes"]:
                cands.append({"bearing": round(b), "shape": shape, "cw": rnd.random() < 0.5,
                              "seed": rnd.randrange(1 << 30)})
        tc = target_cands(attr, wish, W, rnd)
        with ThreadPoolExecutor(g["threads"]) as ex:
            built = list(ex.map(lambda c: build(c, wish, W, kv), cands + tc))
        built = [c for c in built if c]
        for c in built:
            numbers(c, wish, W, index)
    if method == "a" and g["taste_pass"] and built:
        good = good_points(built, wish, W)
        if good:
            again = [dict(c, good=good, taste=True, seed=c["seed"] + 1) for c in cands]
            with ThreadPoolExecutor(g["threads"]) as ex:
                more = [c for c in ex.map(lambda c: build(c, wish, W, kv), again) if c]
            for c in more:
                numbers(c, wish, W, index)
            built += more
    built.sort(key=lambda c: -c["score"])
    chosen = []
    for c in built:
        total = sum(e[2] for e in c["edges"])
        shared = 0.0
        for o in chosen:
            s = sum(e[2] for e in c["edges"] if ekey(e[0], e[1]) in o["keys"]) / total
            shared = max(shared, s)
        c["shared_with_chosen"] = round(shared, 2)
        c["length_ok"] = c["fit"] <= W["select"]["max_length_off"]
        if len(chosen) < W["select"]["show"] and shared <= W["select"]["max_shared"] and c["length_ok"]:
            c["chosen"] = True
            chosen.append(c)
    # an explicit theme is always represented: with "am Wasser entlang" the shown loops include one made for a lake
    # (along its shore) and, when one fits the wished time, a full round of a lake – even if loops elsewhere score
    # higher or the round shares much with the shore loop
    if wish["water"] >= W["select"].get("lake_slot_from_water", 2.0):
        for want in ("ring", None):
            if any(c.get("theme") == "lake" and (want is None or c.get("mode") == want) for c in chosen):
                continue
            made = [c for c in built if c.get("theme") == "lake" and c["length_ok"] and not c.get("chosen")
                    and (want is None or c.get("mode") == want)]
            if made:
                if len(chosen) >= W["select"]["show"]:
                    drop = [c for c in chosen if c.get("theme") != "lake"][-1]
                    drop["chosen"] = False
                    chosen.remove(drop)
                made[0]["chosen"] = True
                chosen.append(made[0])
    if method == "b" and W["netsearch"]["router_check"]:
        import netsearch as NS
        for c in chosen:  # each shown loop once through the real router: does BRouter confirm it?
            c["numbers"].update(NS.router_check(c, wish, W, kv, sys.modules[__name__]))
    out = []
    for i, c in enumerate(built):
        d = DIR_NAMES[int((c["bearing"] + 11.25) // 22.5) % 16]
        if c.get("target_name"):
            name = ("Entlang %s" % c["target_name"] if c.get("mode") == "follow" else
                    "Am %s entlang" % c["target_name"] if c.get("mode") == "shore" else
                    "Ziel %s%s" % (c["target_name"], " · Runde" if c.get("mode") == "ring" else ""))
        else:
            name = "%s · %s%s" % (d, c["shape"].capitalize(), " · gezielt" if c.get("taste") else "")
        out.append({"id": i + 1, "name": name, "chosen": bool(c.get("chosen")), "target": c.get("target_name"),
                    "score": c["score"], "parts": c["parts"], "numbers": c["numbers"], "story": c["story"],
                    "shared_with_chosen": c["shared_with_chosen"], "bearing": c["bearing"], "shape": c["shape"],
                    "vias": c["vias"], "route_ms": c["route_ms"], "legs_routed": c["legs_routed"],
                    "coords": [e[0] for e in c["edges"]] + [c["edges"][-1][1]], "cls": c["cls"]})
    return {"run": time.strftime("%Y%m%d-%H%M%S") + "-%d" % wish["seed"], "wish": wish, "weights_version": W["version"],
            "profile": W["routing"]["profile"], "profile_kv": kv,
            "main_road_max_m": W["score"]["main_road_max_m"], "seconds": round(time.time() - t0, 1),
            "seconds_destinations": round(t_attr, 1), "method": method, "net": net_info,
            "candidates_tried": net_info["loops_kept"] if net_info else len(cands) + len(tc), "notes": notes,
            "destinations": [{"kind": a["kind"], "name": a["name"], "km_away": round(a["d"] / 1000.0, 1)}
                             for a in sorted(attr, key=lambda a: a["d"]) if a["kind"] != "view"][:40],
            "legs_routed": sum(c["legs_routed"] for c in built), "route_ms_sum": sum(c["route_ms"] for c in built),
            "target_km": round(wish["hours"] * wish["pace"], 1), "candidates": out}


def save_run(result):
    d = os.path.join(DATA, "runs")
    os.makedirs(d, exist_ok=True)
    p = os.path.join(d, result["run"] + ".json")
    with open(p, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False)
    return p


def fmt_h(h):
    return "%d:%02d" % (int(h), round((h - int(h)) * 60) % 60)


def goals_text(n):
    """'4,2 km X30 Neandertalweg · 0,9 km am Elbsee' – what the card says about known routes and lakes."""
    t = ["%s km %s" % (("%.1f" % km).replace(".", ","), name) for name, km, _ in n.get("known", [])[:3]]
    t += ["%s km am %s" % (("%.1f" % km).replace(".", ","), name) for name, km in n.get("lakes", [])[:2]]
    t += ["Aussicht: " + v for v in n.get("views", [])[:1]]
    if n.get("ferry_m"):
        t.append("mit Fähre")
    return " · ".join(t)


def table(result, only_chosen=False):
    """Compact text for the terminal (and for the AI to judge)."""
    w = result["wish"]
    L = ["Wunsch: %s · %s · %s h · %.1f km/h → ~%.1f km · T%d · %s · Wald %.1f Ruhe %.1f Wasser %.1f markiert %.1f Naturboden %.1f · seed %d"
         % (w.get("start_name") or w["start"], w.get("activity", "hike"), w["hours"], w["pace"], result["target_km"],
            w["t_level"], w["effort"], w["forest"], w["quiet"], w["water"], w["signed"], w["natural"], w["seed"]),
         "%d Kandidaten in %.1f s (%d Teilstrecken, Router %.0f ms je Teilstrecke) · Gewichte %s · Profil %s"
         % (len(result["candidates"]), result["seconds"], result["legs_routed"],
            result["route_ms_sum"] / max(1, result["legs_routed"]), result["weights_version"], result.get("profile", "")),
         "Ziele in Reichweite: " + (", ".join("%s (%.1f km)" % (d["name"], d["km_away"]) for d in result.get("destinations", [])[:14]) or "–")]
    L += result.get("notes", [])
    L += ["",
          " #  ✓ Punkte  Name                          km   Zeit   ↑m  Wald% Str% Haupt% längste Asph% mark% laut% dopp% Stiel Quer Form geteilt"]
    for c in result["candidates"]:
        if only_chosen and not c["chosen"]:
            continue
        n = c["numbers"]
        L.append("%2d  %s %6.1f  %-28s %5.1f  %s %4d  %4d %4d %5d %6dm %5d %5d %5d %5d %5d %4d %4.2f %5.2f"
                 % (c["id"], "✓" if c["chosen"] else " ", c["score"], c["name"][:28], n["km"], fmt_h(n["hours"]), n["ascent"],
                    n["forest_pct"], n["street_pct"], n["big_street_pct"], n["main_longest_m"], n["paved_pct"],
                    n["signed_pct"], n["loud_pct"], n["doubled_pct"], n["stalk_m"], n["crossings"], n["compact"],
                    c["shared_with_chosen"]))
    L.append("")
    for c in result["candidates"]:
        if not c["chosen"]:
            continue
        L.append("#%d %s – %.1f Punkte" % (c["id"], c["name"], c["score"]))
        L.append("   Teile: " + " ".join("%s %.2f" % kv for kv in c["parts"].items()))
        L.append("   Wegarten km: " + ", ".join("%s %.1f" % kv for kv in list(c["numbers"]["by_highway_km"].items())[:7]))
        if goals_text(c["numbers"]):
            L.append("   Bekannt: " + goals_text(c["numbers"]))
        L.append("   Verlauf: " + c["story"])
    return "\n".join(L)
