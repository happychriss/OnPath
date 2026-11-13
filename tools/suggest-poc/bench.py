#!/usr/bin/env python3
"""Tour-suggestions POC – the fixed yardstick: the same wishes every time, one sheet, pass / fail on plain expectations.

  python3 tools/suggest-poc/bench.py                      # run all cases with weights.json, print + save the sheet
  python3 tools/suggest-poc/bench.py --weights other.json # the same with another weights file
  python3 tools/suggest-poc/bench.py --only hilden        # cases whose name contains the word
  python3 tools/suggest-poc/bench.py --refs               # second yardstick: known circular routes scored by our scorer
  python3 tools/suggest-poc/bench.py --compare A.json B.json

Sheets: ~/onpath-suggest-poc/bench/<time>-<weights version>.txt / .json; every run is compared with the sheet before.
Tune weights.json only against this sheet (a change must not turn a passing case red), and note it in the README.
"""
import argparse
import glob
import json
import os
import sys
import time

import suggest as S

H = [6.9395, 51.1680]
# name, wish (the page's simple controls), expectations beyond the common ones
CASES = [
    ("hilden-hike-2h", dict(start=H, start_name="Hilden", activity="hike", hours=2, pace="gemütlich", effort="leicht",
                            natur="hoch", ruhe="hoch", bekannt="mittel"), dict(forest_min=50)),
    ("heide-hike-2h", dict(start=[6.9866, 51.1645], start_name="Ohligser Heide", activity="hike", hours=2, pace="gemütlich",
                           effort="leicht", natur="hoch", ruhe="hoch", bekannt="mittel"), dict(forest_min=70, street_max=15)),
    ("duesseldorf-hike-2h", dict(start=[6.7763, 51.2254], start_name="Düsseldorf", activity="hike", hours=2, pace="normal",
                                 effort="leicht", natur="hoch", ruhe="mittel", bekannt="mittel"), dict()),
    ("hilden-wasser-3h", dict(start=H, start_name="Hilden", activity="hike", hours=3, pace="gemütlich", effort="leicht",
                              natur="hoch", ruhe="mittel", bekannt="mittel", wasser=True), dict(lake_in_top3="Elbsee", lake_round_shown=True)),
    ("hilden-bekannt-3h", dict(start=H, start_name="Hilden", activity="hike", hours=3, pace="normal", effort="leicht",
                               natur="mittel", ruhe="mittel", bekannt="hoch"), dict(signed_min=50, known_named=True)),
    ("hilden-bike-2h", dict(start=H, start_name="Hilden", activity="bike", hours=2, pace="normal", effort="leicht",
                            natur="mittel", ruhe="hoch", bekannt="mittel"), dict()),
    ("hilden-bike-4h", dict(start=H, start_name="Hilden", activity="bike", hours=4, pace="zügig", effort="mittel",
                            natur="mittel", ruhe="hoch", bekannt="hoch"), dict()),
    ("hilden-gravel-3h", dict(start=H, start_name="Hilden", activity="gravel", hours=3, pace="normal", effort="mittel",
                              natur="hoch", ruhe="hoch", bekannt="niedrig"), dict()),
    ("zons-bike-3h", dict(start=[6.8448, 51.1231], start_name="Zons", activity="bike", hours=3, pace="normal",
                          effort="mittel", natur="hoch", ruhe="hoch", bekannt="hoch", wasser=True),
     dict(known_km=("Rheinradweg", 25))),
    # control for the Rhine case: without the water theme and with "Bekannte Wege mittel" it must stay a sound loop
    ("zons-bike-3h-mittel", dict(start=[6.8448, 51.1231], start_name="Zons", activity="bike", hours=3, pace="normal",
                                 effort="mittel", natur="mittel", ruhe="mittel", bekannt="mittel"),
     dict(known_km=("Rheinradweg", 14))),
    # the owner's two bike trials of 2026-11-13 evening (feedback.jsonl): "wunderbar am Rhein lang" must be on top,
    # no long return beside a road ("Münchner Straße – zwar offizieller Radweg")
    ("owner-rhein-bike-2h", dict(start=[6.81206, 51.14162], start_name="Stürzelberg", activity="bike", hours=2,
                                 pace="normal", effort="leicht", natur="hoch", ruhe="hoch", bekannt="mittel", wasser=True),
     dict(known_km=("Rheinradweg", 18))),
    ("owner-koeln-bike-2h", dict(start=[6.97278, 50.94156], start_name="Köln", activity="bike", hours=2, pace="normal",
                                 effort="leicht", natur="hoch", ruhe="hoch", bekannt="niedrig"), dict()),
    # the owner's comments of 2026-11-13 22:40–22:51 (feedback.jsonl): "warum nicht am Wasser entlang?" (a hike from
    # just south of the Elbsee) and "der Rückweg geht nur durch die Stadt, warum nichts über die Urdenbacher Kämpe" /
    # "zu viel durch Orte" (bike from Hilden) – through houses by the map's built-up land (nature.built)
    ("elbsee-hike-2h-wasser", dict(start=[6.94460, 51.18413], start_name="Hilden Nord", activity="hike", hours=2,
                                   pace="normal", effort="leicht", natur="hoch", ruhe="mittel", bekannt="mittel", wasser=True),
     dict(lake_km=(("Elbsee", "Menzelsee"), 1.5))),
    ("hilden-bike-2h-natur", dict(start=H, start_name="Hilden", activity="bike", hours=2, pace="normal", effort="leicht",
                                  natur="hoch", ruhe="hoch", bekannt="mittel"), dict(built_max=30)),
    # the night of 2026-11-13/14 (judge.py's places; the owner's comments on Nordkirchen and his test regions):
    # no road walking and not through the village; by bike the suggestions go several ways (his runs had no map
    # area and all went south); a rail trail in reach is used; big nature in the Eifel; the Ruhr valley by bike
    ("nordkirchen-hike-2h", dict(start=[7.5251792, 51.7380176], start_name="Nordkirchen", activity="hike", hours=2,
                                 pace="normal", effort="leicht", natur="hoch", ruhe="hoch", bekannt="mittel"),
     dict(built_max=20)),
    ("nordkirchen-bike-2h", dict(start=[7.5251792, 51.7380176], start_name="Nordkirchen", activity="bike", hours=2,
                                 pace="normal", effort="leicht", natur="hoch", ruhe="mittel", bekannt="mittel"),
     dict(built_max=15, spread_deg=90)),
    ("wuppertal-bike-2h", dict(start=[7.1445, 51.2665], start_name="Wuppertal Mirke", activity="bike", hours=2,
                               pace="normal", effort="mittel", natur="mittel", ruhe="hoch", bekannt="mittel"),
     dict(rail_km_min=6)),
    ("monschau-hike-3h", dict(start=[6.2416, 50.5545], start_name="Monschau", activity="hike", hours=3, pace="normal",
                              effort="mittel", natur="hoch", ruhe="mittel", bekannt="hoch"), dict(deep_min=50, built_max=15)),
    ("wetter-bike-3h", dict(start=[7.3900, 51.3870], start_name="Wetter (Ruhr)", activity="bike", hours=3, pace="normal",
                            effort="mittel", natur="hoch", ruhe="hoch", bekannt="hoch", wasser=True), dict(bank_km_min=8)),
    ("meran-hike-3h", dict(start=[11.1597, 46.6713], start_name="Meran", activity="hike", hours=3, pace="normal",
                           effort="mittel", t_level=2, natur="hoch", ruhe="mittel", bekannt="hoch"),
     dict(signed_min=40, known_like=("Tappeiner", "Waalweg"))),
]
METHOD = "auto"
AI = False


def run_case(name, wish_in, exp, W):
    wish = S.simple_wish(dict(wish_in, seed=1, method=METHOD, ai=AI), W)
    E = S.effective(W, wish["activity"])
    try:
        r = S.suggest(wish, W)
    except Exception as ex:  # a case that cannot run is a failed case, the sheet goes on
        return {"name": name, "error": "%s: %s" % (type(ex).__name__, ex), "checks": {"läuft": False}}
    S.save_run(r)
    ch = [c for c in r["candidates"] if c["chosen"]]
    out = {"name": name, "run": r["run"], "seconds": r["seconds"], "legs": r["legs_routed"], "chosen": len(ch),
           "notes": r.get("notes", [])}
    if not ch:
        out["checks"] = {"Vorschlag da": False}
        return out
    top = ch[0]
    n = top["numbers"]
    # the owner's rules (2026-11-13): the length is a rough guide (±1 km on foot, ±5 km by bike, plus 10 %); the
    # loop meets its own way at most once – every shown loop; no long stretch on or beside a busy road (the free
    # stretch shrinks with the Natur level)
    flat = E["score"]["length_flat_km"]
    road_max = n.get("main_road_max_m", E["score"]["main_road_max_m"])
    checks = {"Länge ±(%g km + 10 %%)" % flat: n["length_off_km"] <= flat + 0.1 * wish["hours"] * wish["pace"],
              "an / neben Straße ≤ %d m" % road_max: n["main_longest_m"] <= road_max,
              "kreuzt sich ≤ 1× (alle Vorschläge)": all(c["numbers"]["self_cross"] <= 1 for c in ch),
              "doppelt < 10 %": n["doubled_pct"] < 10,
              "≥ 3 Vorschläge": len(ch) >= 3,
              "≤ 15 s": r["seconds"] <= 15}
    if "forest_min" in exp:
        checks["am Wald ≥ %d %%" % exp["forest_min"]] = n["forest_pct"] >= exp["forest_min"]
    if "street_max" in exp:
        checks["Straße ≤ %d %%" % exp["street_max"]] = n["street_pct"] <= exp["street_max"]
    if "signed_min" in exp:
        checks["markiert ≥ %d %%" % exp["signed_min"]] = n["signed_pct"] >= exp["signed_min"]
    if "lake_in_top3" in exp:
        checks["%s unter den ersten 3" % exp["lake_in_top3"]] = any(
            exp["lake_in_top3"] in l[0] for c in ch[:3] for l in c["numbers"].get("lakes", []))
    if exp.get("lake_round_shown"):
        checks["eine Runde am See unter den Vorschlägen (≥ 1,5 km am Ufer)"] = any(
            sum(l[1] for l in c["numbers"].get("lakes", [])) >= 1.5 for c in ch)
    if exp.get("known_like"):
        checks["%s unter den ersten 3" % " / ".join(exp["known_like"])] = any(
            w in k[0] for c in ch[:3] for k in c["numbers"].get("known", []) for w in exp["known_like"])
    if exp.get("known_km"):
        word, km = exp["known_km"]
        checks["≥ %d km %s im ersten Vorschlag" % (km, word)] = sum(
            k[1] for k in n.get("known", []) if word in k[0]) >= km
    if exp.get("lake_km"):
        names, km = exp["lake_km"]
        checks["≥ %g km am %s im ersten Vorschlag" % (km, " / ".join(names))] = sum(
            l[1] for l in n.get("lakes", []) if any(w in l[0] for w in names)) >= km
    if "built_max" in exp:
        checks["durch Häuser ≤ %d %%" % exp["built_max"]] = n["built_pct"] <= exp["built_max"]
    if "rail_km_min" in exp:
        checks["≥ %g km Bahntrasse im ersten Vorschlag" % exp["rail_km_min"]] = n.get("railtrail_km", 0) >= exp["rail_km_min"]
    if "bank_km_min" in exp:
        checks["≥ %g km am Wasser im ersten Vorschlag" % exp["bank_km_min"]] = n.get("big_water_km", 0) >= exp["bank_km_min"]
    if "deep_min" in exp:
        checks["tief in der Natur ≥ %d %%" % exp["deep_min"]] = n.get("deep_pct", 0) >= exp["deep_min"]
    if "spread_deg" in exp:
        bs = [c.get("bearing", 0) for c in ch]
        checks["Vorschläge in verschiedene Richtungen (≥ %d°)" % exp["spread_deg"]] = any(
            abs((a_ - b_ + 180) % 360 - 180) >= exp["spread_deg"] for a_ in bs for b_ in bs)
    if exp.get("known_named"):
        checks["bekannter Weg benannt"] = bool(n.get("known"))
    out.update({"top": {"name": top["name"], "score": top["score"], "km": n["km"], "hours": n["hours"], "ascent": n["ascent"],
                        "forest_pct": n["forest_pct"], "street_pct": n["street_pct"], "main_longest_m": n["main_longest_m"],
                        "signed_pct": n["signed_pct"], "doubled_pct": n["doubled_pct"], "goals": S.goals_text(n),
                        "self_cross": n["self_cross"], "roadside_pct": n["roadside_pct"], "built_pct": n["built_pct"],
                        "deep_pct": n["deep_pct"], "stalk_m": n["stalk_m"],
                        "ai_km": n.get("ai_km"), "ai": S.ai_text(n), "ai_part": top["parts"].get("ai")},
                "checks": checks})
    return out


def sheet(res, version):
    L = ["Bench · Gewichte %s · %s" % (version, time.strftime("%Y-%m-%d %H:%M")), "",
         "Fall                  ok   Punkte    km  Zeit  Wald% Str% Haupt-m mark% dopp% kreuzt neben% Häuser% tief%    s  Erster Vorschlag"]
    ok_all = n_all = 0
    for c in res:
        ok = sum(1 for v in c["checks"].values() if v)
        ok_all += ok
        n_all += len(c["checks"])
        t = c.get("top")
        if not t:
            L.append("%-20s %d/%d  – %s" % (c["name"], ok, len(c["checks"]), c.get("error", "kein Vorschlag")))
            continue
        L.append("%-20s %d/%d  %6.1f %5.1f  %s  %4d %4d %6d %5d %5d %5s %6s %6s %5s %5.1f  %s%s"
                 % (c["name"], ok, len(c["checks"]), t["score"], t["km"], S.fmt_h(t["hours"]), t["forest_pct"],
                    t["street_pct"], t["main_longest_m"], t["signed_pct"], t["doubled_pct"], t.get("self_cross", "–"),
                    t.get("roadside_pct", "–"), t.get("built_pct", "–"), t.get("deep_pct", "–"), c["seconds"], t["name"],
                    " – " + t["goals"] if t["goals"] else ""))
        if t.get("ai"):
            L.append("%-20s      %s · KI-Teil %.2f · %.1f km auf Tipps" % ("", t["ai"], t.get("ai_part") or 0, t.get("ai_km") or 0))
        bad = [k for k, v in c["checks"].items() if not v]
        if bad:
            L.append("%-20s      ✗ %s" % ("", " · ✗ ".join(bad)))
        for nt in c.get("notes", []):
            L.append("%-20s      Hinweis: %s" % ("", nt))
    L += ["", "Gesamt: %d von %d Erwartungen erfüllt · Punktesumme der ersten Vorschläge %.1f"
          % (ok_all, n_all, sum(c["top"]["score"] for c in res if c.get("top")))]
    return "\n".join(L)


def compare(a, b):
    L = ["Vergleich  %s  →  %s" % (a["version"], b["version"]), "",
         "Fall                   ok vorher → nachher   Punkte        Wald%      Haupt-m    dopp%"]
    A = {c["name"]: c for c in a["cases"]}
    for c in b["cases"]:
        o = A.get(c["name"])
        if not o or not o.get("top") or not c.get("top"):
            continue
        ok = lambda x: "%d/%d" % (sum(1 for v in x["checks"].values() if v), len(x["checks"]))
        t, u = o["top"], c["top"]
        L.append("%-22s %5s → %-5s      %5.1f → %-5.1f %3d → %-3d   %4d → %-4d  %2d → %-2d"
                 % (c["name"], ok(o), ok(c), t["score"], u["score"], t["forest_pct"], u["forest_pct"],
                    t["main_longest_m"], u["main_longest_m"], t["doubled_pct"], u["doubled_pct"]))
    return "\n".join(L)


def refs(W):
    """Known circular routes near Hilden (signed, roundtrip) walked through our router and scored by our scorer, next to
    our own best suggestion for the same start and duration. A sound scorer rates the known route about as high."""
    import attractions as AT
    E = S.effective(W, "hike")
    attr, note = AT.load(H, E["attractions"]["max_reach_m"], "hike", E)
    if note:
        return note
    idx = AT.Index(attr)
    L = ["Bekannte Rundwege, von unserer Bewertung benotet (Wunsch: Wandern, Natur hoch, Ruhe hoch, Bekannte Wege mittel)", "",
         "Rundweg                               km  Zeit  Punkte  unser bester  Wald% Str% Haupt-m mark%  schwächste Teile"]
    for a in [x for x in attr if x["kind"] == "route" and x["ring"] and 3000 <= x["length_m"] <= 14000][:8]:
        pts = a["points"]
        cx, cy = sum(p[0] for p in pts) / len(pts), sum(p[1] for p in pts) / len(pts)
        order = sorted(pts, key=lambda p: S.bearing([cx, cy], p))
        via = [order[int(i * len(order) / 8)] for i in range(8)]
        wish = S.simple_wish(dict(start=via[0], activity="hike", hours=2, pace="normal", effort="leicht", natur="hoch",
                                  ruhe="hoch", bekannt="mittel"), W)
        kv = S.profile_kv(wish, E)
        kv["hiking_routes_preference"] = 3  # hold the router on the signed way between the sample points
        r = S.route(via + [via[0]], kv, [], E)
        if "error" in r:
            L.append("%-36s – nicht routbar: %s" % (a["name"][:36], r["error"][:50]))
            continue
        edges = S.leg_edges(r)
        km = sum(e[2] for e in edges) / 1000.0
        hours = km / wish["pace"] + r["ascend"] / E["time"]["ascent_m_per_h"]
        wish["hours"] = round(hours * 4) / 4 or 0.25
        c = {"edges": edges, "km": km, "hours": hours, "ascent": r["ascend"], "fit": abs(hours - wish["hours"]) / wish["hours"]}
        S.numbers(c, wish, E, idx)
        ours = S.suggest(wish, W)
        best = max((x["score"] for x in ours["candidates"] if x["chosen"]), default=0)
        weak = sorted(c["parts"].items(), key=lambda x: x[1])[:3]
        n = c["numbers"]
        L.append("%-36s %5.1f  %s  %6.1f  %6.1f %s  %4d %4d %6d %5d  %s"
                 % (a["name"][:36], km, S.fmt_h(hours), c["score"], best, "✓" if c["score"] >= best - 3 else "✗",
                    n["forest_pct"], n["street_pct"], n["main_longest_m"], n["signed_pct"],
                    " ".join("%s %.2f" % w for w in weak)))
    return "\n".join(L)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--weights")
    ap.add_argument("--only", default="")
    ap.add_argument("--refs", action="store_true")
    ap.add_argument("--compare", nargs=2)
    ap.add_argument("--method", default="auto", choices=["auto", "a", "b", "c"], help="a = fan of routed loops, b = search in the way network, c = green-patch chain")
    ap.add_argument("--ai", action="store_true", help="mit KI-Regionswissen (score part 'ai' + the picks as destinations)")
    a = ap.parse_args()
    METHOD = a.method
    AI = a.ai
    if a.compare:
        print(compare(json.load(open(a.compare[0])), json.load(open(a.compare[1]))))
        sys.exit()
    W = S.load_weights(a.weights)
    if a.refs:
        print(refs(W))
        sys.exit()
    res = [run_case(n, w, e, W) for n, w, e in CASES if a.only in n]
    W["version"] += "-" + a.method.upper() + ("-KI" if a.ai else "")
    text = sheet(res, W["version"])
    print(text)
    d = os.path.join(S.DATA, "bench")
    os.makedirs(d, exist_ok=True)
    before = sorted(glob.glob(os.path.join(d, "*.json")))
    base = os.path.join(d, time.strftime("%Y%m%d-%H%M%S") + "-" + W["version"] + ("-" + a.only if a.only else ""))
    doc = {"version": W["version"], "cases": res}
    with open(base + ".json", "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False)
    with open(base + ".txt", "w", encoding="utf-8") as f:
        f.write(text + "\n")
    print("\nBlatt: " + base + ".txt")
    if before and not a.only:
        print("\n" + compare(json.load(open(before[-1])), doc))
