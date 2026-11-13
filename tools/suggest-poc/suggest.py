"""Tour-suggestions POC – wish -> loop candidates -> scores -> selection (docs/design/tour-suggestions.md).

Plain Python, stdlib only. Routing comes from the BRouter route service (service/RouteService.java);
every knob is in weights.json, which is read again on every request.
"""
import json
import math
import os
import random
import subprocess
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
SERVICE = os.environ.get("SUGGEST_SERVICE", "http://127.0.0.1:8791")
DATA = os.environ.get("SUGGEST_DATA", os.path.expanduser("~/onpath-suggest-poc"))

DIR_NAMES = ["N", "NNO", "NO", "ONO", "O", "OSO", "SO", "SSO", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"]


def load_weights():
    with open(os.path.join(HERE, "weights.json"), encoding="utf-8") as f:
        return json.load(f)


# ---------------------------------------------------------------- wish

def make_wish(start, start_name="", hours=2.0, pace=4.0, t_level=2, effort="relaxed", forest=0.5, quiet=0.5,
              water=0.0, signed=0.3, natural=0.5, seed=1):
    """start = [lon, lat]. pace = km/h on the flat. effort = relaxed | medium | sporty. Taste sliders 0..1."""
    return {"start": [float(start[0]), float(start[1])], "start_name": start_name, "hours": float(hours),
            "pace": float(pace), "t_level": int(t_level), "effort": effort, "forest": float(forest),
            "quiet": float(quiet), "water": float(water), "signed": float(signed), "natural": float(natural),
            "seed": int(seed)}


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
    subprocess.Popen([os.path.join(HERE, "service", "start.sh")], stdout=log, stderr=log, env=env,
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
    return json.load(urllib.request.urlopen(req, timeout=60))


def profile_kv(wish, W):
    r = W["routing"]
    t = max(1, min(5, wish["t_level"]))
    kv = {"SAC_scale_limit": t, "SAC_scale_preferred": min(t, 3),
          "consider_forest": int(wish["forest"] >= r["consider_forest_from"]),
          "consider_noise": int(wish["quiet"] >= r["consider_noise_from"]),
          "consider_river": int(wish["water"] >= r["consider_river_from"]),
          "consider_town": int(wish["quiet"] >= r["consider_town_from"]),
          "consider_elevation": int(wish["effort"] == "relaxed" and r["relaxed_considers_elevation"]),
          "hiking_routes_preference": round(r["hiking_routes_preference_base"]
                                            + r["hiking_routes_preference_per_signed"] * wish["signed"], 2)}
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


def key(p):
    return (round(p[0] * 1e6), round(p[1] * 1e6))


def ekey(a, b):
    ka, kb = key(a), key(b)
    return (ka, kb) if ka <= kb else (kb, ka)


def shape_points(start, bearing, shape, air_len, rnd, jitter, clockwise):
    """Via points of a loop through the start: a circle of perimeter air_len whose centre lies in `bearing`."""
    r = air_len / (2 * math.pi)
    centre = offset(start, bearing, r)
    angles = [120, 240] if shape == "dreieck" else [90, 180, 270]
    if shape == "keule":  # a stem out, a smaller loop at its end, the stem back (the way back is pushed off the way out)
        r = 0.5 * air_len / (2 * math.pi)
        centre = offset(start, bearing, 0.25 * air_len + r)
    if not clockwise:
        angles = [360 - a for a in angles]
    pts = []
    for a in angles:
        rr = r * (1 + rnd.uniform(-jitter, jitter))
        # the start sits at bearing+180 seen from the centre; walk round from there
        pts.append(offset(centre, bearing + 180 + a + rnd.uniform(-jitter, jitter) * 60, rr))
    return pts


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
    air = target_h * wish["pace"] * 1000.0 / g["detour_factor"]
    best = None
    ms = 0
    nlegs = 0
    for it in range(g["fit_iterations"] + 1):
        rnd = random.Random(cand["seed"])
        vias = shape_points(wish["start"], cand["bearing"], cand["shape"], air, rnd, g["via_jitter"], cand["cw"])
        if cand.get("good"):  # second pass: pull each via point to the nearest pleasant spot the first pass found
            limit = g["snap_max_factor"] * air / (2 * math.pi)
            snapped = []
            for v in vias:
                d, gp = min((dist(v, x), x) for x in cand["good"])
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
    best.update({k: v for k, v in cand.items() if k != "good"})
    best["route_ms"] = ms
    best["legs_routed"] = nlegs
    return best


# ---------------------------------------------------------------- numbers and score

def edge_class(tags, C):
    """W = way near forest (class >= forest_share_from_class, not a street), G = big street, S = street, P = other path."""
    hw = tags.get("highway", "")
    street = hw in C["street_highways"]
    if not street and C["sidewalk_is_street"] and tags.get("footway") == "sidewalk":
        street = True
    if not street and C["lit_town_path_is_street"] and tags.get("lit") == "yes" and tags.get("estimated_town_class") \
            and not tags.get("estimated_forest_class") and hw in ("footway", "cycleway", "path"):
        street = True
    if hw in C["big_highways"]:
        return "G"
    if street:
        return "S"
    if int(tags.get("estimated_forest_class", "0") or 0) >= C["forest_share_from_class"]:
        return "W"
    return "P"


def numbers(c, wish, W):
    C, S = W["classes"], W["score"]
    edges = c["edges"]
    total = sum(e[2] for e in edges) or 1.0
    uses = {}
    for e in edges:
        k = ekey(e[0], e[1])
        uses[k] = uses.get(k, 0) + 1
    m = dict(forest_v=0.0, forest=0.0, noise_v=0.0, loud=0.0, river_v=0.0, street=0.0, big=0.0, paved=0.0,
             signed=0.0, doubled=0.0, town=0.0)
    cls = []
    crossings = 0
    by_hw = {}
    for e in edges:
        ln, t, nt = e[2], e[3], e[4]
        k = edge_class(t, C)
        cls.append(k)
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
        if any(x.startswith("route_hiking") for x in t) or t.get("route") == "hiking":
            m["signed"] += ln
        if uses[ekey(e[0], e[1])] > 1:
            m["doubled"] += ln
        if nt.get("highway") in ("crossing", "traffic_signals"):
            crossings += 1
        by_hw[hw] = by_hw.get(hw, 0.0) + ln
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

    km = total / 1000.0
    n = {"km": round(km, 2), "hours": round(c["hours"], 2), "ascent": int(c["ascent"]),
         "ascent_per_km": round(c["ascent"] / km, 1),
         "forest_pct": round(100 * m["forest"] / total), "forest_value": round(m["forest_v"] / total, 2),
         "loud_pct": round(100 * m["loud"] / total), "noise_value": round(m["noise_v"] / total, 2),
         "water_value": round(m["river_v"] / total, 2), "town_pct": round(100 * m["town"] / total),
         "street_pct": round(100 * m["street"] / total), "big_street_pct": round(100 * m["big"] / total),
         "paved_pct": round(100 * m["paved"] / total), "signed_pct": round(100 * m["signed"] / total),
         "doubled_pct": round(100 * m["doubled"] / total), "stalk_m": int(stalk), "crossings": crossings,
         "compact": round(compact, 2),
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
    parts = {
        "length": max(0.0, 1 - c["fit"] / S["length_zero_at"]),
        "ascent": ascent_part,
        "forest": m["forest_v"] / total,
        "quiet": 1 - m["noise_v"] / total,
        "water": m["river_v"] / total,
        "signed": min(1.0, m["signed"] / total / S["signed_full_at"]),
        "natural": 1 - m["paved"] / total,
        "street": max(0.0, 1 - street_share / S["street_zero_at"]),
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


CLS_WORD = {"W": "Waldweg", "P": "Weg", "S": "Straße", "G": "große Straße"}


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


# ---------------------------------------------------------------- the whole request

def suggest(wish, W=None):
    W = W or load_weights()
    ensure_service()
    g = W["generator"]
    t0 = time.time()
    rnd = random.Random(wish["seed"])
    kv = profile_kv(wish, W)
    turn = rnd.uniform(0, 360.0 / g["directions"])
    cands = []
    for i in range(g["directions"]):
        bearing = (turn + i * 360.0 / g["directions"]) % 360
        for shape in g["shapes"]:
            cands.append({"bearing": round(bearing), "shape": shape, "cw": rnd.random() < 0.5,
                          "seed": rnd.randrange(1 << 30)})
    with ThreadPoolExecutor(g["threads"]) as ex:
        built = list(ex.map(lambda c: build(c, wish, W, kv), cands))
    built = [c for c in built if c]
    for c in built:
        numbers(c, wish, W)
    if g["taste_pass"] and built:
        good = good_points(built, wish, W)
        if good:
            again = [dict(c, good=good, taste=True, seed=c["seed"] + 1) for c in cands]
            with ThreadPoolExecutor(g["threads"]) as ex:
                more = [c for c in ex.map(lambda c: build(c, wish, W, kv), again) if c]
            for c in more:
                numbers(c, wish, W)
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
    out = []
    for i, c in enumerate(built):
        d = DIR_NAMES[int((c["bearing"] + 11.25) // 22.5) % 16]
        out.append({"id": i + 1, "name": "%s · %s%s" % (d, c["shape"].capitalize(), " · gezielt" if c.get("taste") else ""), "chosen": bool(c.get("chosen")),
                    "score": c["score"], "parts": c["parts"], "numbers": c["numbers"], "story": c["story"],
                    "shared_with_chosen": c["shared_with_chosen"], "bearing": c["bearing"], "shape": c["shape"],
                    "vias": c["vias"], "route_ms": c["route_ms"], "legs_routed": c["legs_routed"],
                    "coords": [e[0] for e in c["edges"]] + [c["edges"][-1][1]], "cls": c["cls"]})
    return {"run": time.strftime("%Y%m%d-%H%M%S") + "-%d" % wish["seed"], "wish": wish, "weights_version": W["version"],
            "profile_kv": kv, "seconds": round(time.time() - t0, 1), "candidates_tried": len(cands),
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


def table(result, only_chosen=False):
    """Compact text for the terminal (and for the AI to judge)."""
    w = result["wish"]
    L = ["Wunsch: %s · %s h · %.1f km/h → ~%.1f km · T%d · %s · Wald %.1f Ruhe %.1f Wasser %.1f markiert %.1f Naturboden %.1f · seed %d"
         % (w.get("start_name") or w["start"], w["hours"], w["pace"], result["target_km"], w["t_level"], w["effort"],
            w["forest"], w["quiet"], w["water"], w["signed"], w["natural"], w["seed"]),
         "%d Kandidaten in %.1f s (%d Teilstrecken, Router %.0f ms je Teilstrecke) · Gewichte %s"
         % (len(result["candidates"]), result["seconds"], result["legs_routed"],
            result["route_ms_sum"] / max(1, result["legs_routed"]), result["weights_version"]),
         "",
         " #  ✓ Punkte  Name            km   Zeit   ↑m  Wald% Str% groß% Asph% mark% laut% dopp% Stiel Quer Form geteilt"]
    for c in result["candidates"]:
        if only_chosen and not c["chosen"]:
            continue
        n = c["numbers"]
        L.append("%2d  %s %6.1f  %-14s %5.1f  %s %4d  %4d %4d %5d %5d %5d %5d %5d %5d %4d %4.2f %5.2f"
                 % (c["id"], "✓" if c["chosen"] else " ", c["score"], c["name"], n["km"], fmt_h(n["hours"]), n["ascent"],
                    n["forest_pct"], n["street_pct"], n["big_street_pct"], n["paved_pct"], n["signed_pct"],
                    n["loud_pct"], n["doubled_pct"], n["stalk_m"], n["crossings"], n["compact"], c["shared_with_chosen"]))
    L.append("")
    for c in result["candidates"]:
        if not c["chosen"]:
            continue
        L.append("#%d %s – %.1f Punkte" % (c["id"], c["name"], c["score"]))
        L.append("   Teile: " + " ".join("%s %.2f" % kv for kv in c["parts"].items()))
        L.append("   Wegarten km: " + ", ".join("%s %.1f" % kv for kv in list(c["numbers"]["by_highway_km"].items())[:7]))
        L.append("   Verlauf: " + c["story"])
    return "\n".join(L)
