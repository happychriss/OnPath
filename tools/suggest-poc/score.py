"""Judging loops: five axes, a few hard rules, one selection – and the words that say why.

  Natur       green beside the way and being deep inside nature
  Ruhe        away from traffic and houses
  Weg         the ground fits the character
  Besonderes  water, known routes, a rail trail, a named protected area, a tip – what makes it more than a way
  Form        a clean loop: about the wished length, nothing walked twice, not crossing itself, ascent that fits

The first four are means over the loop beyond the unavoidable way out, read on the place's own yardstick
(place.rel): 1 = as good as it gets from here. Form is absolute. The total is their mean, weighted by the character.
"""
from net import direction, dist

AXES = ("natur", "ruhe", "weg", "besonderes", "form")
WORDS = {"natur": "Natur", "ruhe": "Ruhe", "weg": "Weg", "besonderes": "Besonderes", "form": "Form"}


def score(P, S, c, wish, views):
    """Fills c with axes, points, numbers and the reason a hard rule drops it (c["out"])."""
    F, ch, net, K = P.F, P.ch, P.net, S["form"]
    edges = c["edges"]
    core = [e for e in edges if P.core(e)] or edges
    m = sum(F.ln[e] for e in core)
    ax = {a: P.rel(a, sum(P.v[a][e] * F.ln[e] for e in core) / m) for a in ("natur", "ruhe", "weg", "besonderes")}
    seen_views = sorted({v["name"] for v in views if any(dist(net.nodes[x], v["p"]) < 120.0 for x in c["nodes"][::3])})
    ax["besonderes"] = min(1.0, ax["besonderes"] + K["view"] * min(3, len(seen_views)))
    fam, clamp = ch.family, lambda x: max(0.0, min(1.0, x))
    off_m = max(0.0, abs(c["hours"] - wish["hours"]) * wish["pace"] * 1000.0 - fam["band_km"] * 1000.0)
    up_h = c["up"] / c["hours"]
    parts = {"Länge": 1.0 - clamp(off_m / (K["length_zero"] * P.target)),
             "doppelt": 1.0 - clamp(c["doubled_m"] / c["m"] / K["doubled_zero"]),
             "kreuzt sich": K["cross"] ** c["cross"],
             "Steigung": 1.0 if ch.up_ok is None else 1.0 - clamp((up_h - ch.up_ok) / (0.5 * ch.up_ok))}
    ax["form"] = parts["Länge"] * parts["doppelt"] * parts["kreuzt sich"] * parts["Steigung"]
    c["axes"] = {a: round(ax[a], 3) for a in AXES}
    c["score"] = round(100.0 * sum(ch.axes[a] * ax[a] for a in AXES), 1)
    c["form_parts"] = {k: round(v, 2) for k, v in parts.items() if v < 0.995}
    n = c["numbers"] = numbers(P, c, seen_views)
    c["out"] = ("zu steil für die gewählte Anstrengung" if parts["Steigung"] <= 0 else
                "langes Stück an einer Straße mit Autos" if 0 < fam["road_walk_max_m"] < n["road_longest_m"] else None)
    return c


def numbers(P, c, views):
    """The plain facts of a loop for the card and for judging – absolute, not relative to the place."""
    F, net, edges, total = P.F, P.net, c["edges"], c["m"]
    pct = lambda test: int(round(100.0 * sum(F.ln[e] for e in edges if test(e)) / total))
    km_by, run, longest = {}, 0.0, 0.0
    for e in edges:
        for key in [("reserves", F.areas[F.area[e]])] * (F.area[e] > 0) + [
                ({"route": "known", "lake": "lakes"}.get(F.things[i]["kind"]), F.things[i]["name"]) for i in F.named[e]]:
            if key[0]:
                km_by[key] = km_by.get(key, 0.0) + F.ln[e] / 1000.0
        run = run + F.ln[e] if F.traffic[e] >= 0.45 and not F.built[e] else 0.0
        longest = max(longest, run)
    top = lambda kind, least: [[nm, round(km, 1)] for (k, nm), km in sorted(km_by.items(), key=lambda x: -x[1]) if k == kind and km >= least][:4]
    return {"km": round(total / 1000.0, 2), "hours": round(c["hours"], 2), "ascent": int(c["up"]),
            "forest_pct": pct(lambda e: F.green[e] >= 0.5), "deep_pct": pct(lambda e: F.depth[e] >= 0.5),
            "town_pct": pct(lambda e: F.built[e]), "street_pct": pct(lambda e: F.traffic[e] >= 0.15),
            "road_longest_m": int(longest), "signed_pct": pct(lambda e: F.signed[e] > 0),
            "water_km": round(sum(F.ln[e] for e in edges if F.water[e] > 0) / 1000.0, 1),
            "rail_km": round(sum(F.ln[e] for e in edges if F.rail[e]) / 1000.0, 1),
            "ground_pct": [pct(lambda e, g=g: F.ground[e] == g) for g in range(4)],
            "ferry_m": int(sum(F.ln[e] for e in set(edges) if F.ferry[e])), "self_cross": c["cross"],
            "doubled_pct": int(round(100.0 * c["doubled_m"] / total)), "stem_m": int(c["stem_m"]),
            "reserves": top("reserves", 0.8), "known": top("known", 0.8), "lakes": top("lakes", 0.4), "views": views[:3]}


def explain(P, c, wish):
    """Title, the reason line and the five-word verdict of a loop."""
    n, net = c["numbers"], P.net
    far = max(c["nodes"], key=lambda x: dist(net.nodes[net.start], net.nodes[x]))
    where = "Richtung %s" % direction(net.nodes[net.start], net.nodes[far])
    main = [x[0] for x in n["reserves"][:2]] + [x[0] for x in n["lakes"][:1]] \
        + [x[0] for x in n["known"][:1] if x[1] >= 0.25 * n["km"]] + (["Bahntrasse"] if n["rail_km"] >= 0.25 * n["km"] else [])
    c["name"] = " – ".join(dict.fromkeys(main[:3])) or next((a for a in c["aim"] if " bei " in a or " im " in a), where)
    c["bearing"] = where
    km = lambda rows, pre: ["%s km %s%s" % (str(k).replace(".", ","), pre, nm) for nm, k in rows]
    c["reasons"] = (km(n["reserves"], "im Schutzgebiet ") + km(n["lakes"], "am ") + km(n["known"], "auf ")
                    + (["%s km am Wasser" % str(n["water_km"]).replace(".", ",")] if n["water_km"] >= 1 and not n["lakes"] else [])
                    + (["%s km Bahntrasse" % str(n["rail_km"]).replace(".", ",")] if n["rail_km"] >= 1 else [])
                    + ["Aussicht: " + v for v in n["views"][:1]] + (["mit Fähre"] if n["ferry_m"] else []))[:5]
    off = (c["hours"] - wish["hours"]) * wish["pace"]
    band = P.ch.family["band_km"]
    c["hints"] = (["etwas %s als gewünscht" % ("kürzer" if off < 0 else "länger")] if abs(off) > band else []) \
        + (["%d m an einer Straße am Stück" % n["road_longest_m"]] if n["road_longest_m"] >= 200 else []) \
        + (["%d %% doppelt" % n["doubled_pct"]] if n["doubled_pct"] >= 8 else []) \
        + (["kreuzt sich einmal"] if n["self_cross"] else []) + (["%d %% durch Häuser" % n["town_pct"]] if n["town_pct"] >= 30 else [])
    ranked = sorted(AXES, key=lambda a: -c["axes"][a])
    weak = [a for a in ranked[::-1] if c["axes"][a] < 0.5][:2]
    c["why"] = "stark bei %s" % " und ".join(WORDS[a] for a in ranked[:2]) + (
        " · schwächer bei %s" % " und ".join(WORDS[a] + (" (%s)" % ", ".join(c["form_parts"]) if a == "form" and c["form_parts"] else "")
                                               for a in weak) if weak else "")
    return c


def choose(P, S, cands):
    """The best first; further ones must be different and not far behind; every stand-out area in reach gets the
    loop that uses it best, if that loop is good enough. Fewer than three is fine when the rest is poor."""
    F, K = P.F, S["select"]
    ranked = sorted((c for c in cands if not c["out"]), key=lambda c: -c["score"])
    if not ranked:
        return []
    core = {id(c): {e for e in c["edges"] if P.core(e)} or set(c["edges"]) for c in ranked}
    length = lambda es: sum(F.ln[e] for e in es) or 1.0

    def fresh(c, shown):
        return all(length(core[id(c)] & core[id(o)]) <= K["overlap"] * length(core[id(c)]) for o in shown)
    shown = [ranked[0]]
    for area in P.areas:
        best = next((c for c in ranked if length(core[id(c)] & area["edges"]) >= min(2500.0, 0.3 * area["m"])), None)
        if best and best not in shown and best["score"] >= ranked[0]["score"] - K["cover_behind"] and fresh(best, shown):
            shown.append(best)
    for c in ranked:
        if len(shown) >= K["max"] or c["score"] < ranked[0]["score"] - K["behind"]:
            break
        if c not in shown and fresh(c, shown):
            shown.append(c)
    return sorted(shown[:K["max"]], key=lambda c: -c["score"])
