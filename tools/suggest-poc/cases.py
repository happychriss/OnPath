#!/usr/bin/env python3
"""The owner's comments as plain checks – the acceptance list for every change to the method.

  python3 tools/suggest-poc/cases.py                 all cases, one line each, the failed checks named
  python3 tools/suggest-poc/cases.py heide zons      only cases whose name contains one of the words
  python3 tools/suggest-poc/cases.py --old <dir>     the same wishes through an older version of the POC (its folder)

Each case is one wish and a few statements about the suggestions it must give, in the owner's sense. A check gets
`s` = the shown suggestions (best first), each as a small record (see `view`). Not a test suite: a yardstick to read.
"""
import json
import os
import subprocess
import sys

DIRS = ["N", "NO", "O", "SO", "S", "SW", "W", "NW"]
HOCH = {"natur": "hoch", "ruhe": "hoch"}
RHEIN = ("Rheinradweg", "EV15")


def km(row, *words):
    """Kilometres of a loop in / at / on the named things whose name contains one of the words."""
    return sum(v for k, v in row["named"].items() if any(w.lower() in k.lower() for w in words))


CASES = [
    ("hilden-wandern", {"start": [6.9395, 51.168], "hours": 2},
     "„der Weg ins Grüne ist gut, aber entlang der Hauptstraße nicht“ · aus der Ortsmitte mehrere verschiedene Runden", [
         ("mindestens 3 Vorschläge", lambda s: len(s) >= 3),
         ("der Lauf sagt, dass der Weg ins Grüne nicht mitzählt", lambda s: any("Grüne" in n for n in s[0]["notes"])),
         ("erste Runde ohne langes Straßenstück", lambda s: s[0]["road"] < 200),
         ("erste Runde führt in ein Schutzgebiet", lambda s: s[0]["reserve_km"] >= 1.0)]),
    ("heide-wandern", {"start": [6.9866, 51.1645], "hours": 2},
     "„die Ohligser Heide, Waldrand, das war eine gute Wanderung“", [
         ("erste Runde ≥ 2 km in der Ohligser Heide", lambda s: km(s[0], "Ohligser Heide") >= 2.0),
         ("erste Runde ≥ 60 % am Wald, nichts doppelt", lambda s: s[0]["forest"] >= 60 and s[0]["doubled"] <= 5)]),
    ("elbsee-wasser", {"start": [6.94460, 51.18413], "hours": 2, "natur": "hoch", "wasser": True},
     "„warum nicht am Wasser entlang?“ (südlich des Elbsees, Wasser angekreuzt)", [
         ("erste Runde ≥ 1,5 km an Elbsee / Menzelsee", lambda s: km(s[0], "Elbsee", "Menzelsee") >= 1.5)]),
    ("nordkirchen-wandern", {"start": [7.52518, 51.73802], "hours": 2, **HOCH},
     "„warum taucht der Hirschpark nicht auf, super schön“ · „auf einer Landstraße wandern“ · „geht nur durch den Ort“", [
         ("eine Runde ≥ 2,5 km im Hirschpark", lambda s: any(km(r, "Hirschpark") >= 2.5 for r in s)),
         ("keine Runde mit > 300 m Landstraße", lambda s: all(r["road"] <= 300 for r in s)),
         ("keine Runde > 40 % durch Häuser", lambda s: all(r["town"] <= 40 for r in s))]),
    ("nordkirchen-rad", {"start": [7.52518, 51.73802], "activity": "bike", "hours": 2, "natur": "hoch"},
     "„warum durch Werne, es ist viel Grün drum rum“ · „warum gehen alle Wege nach Süden“", [
         ("erste Runde ≤ 20 % durch Häuser", lambda s: s[0]["town"] <= 20),
         ("Vorschläge in mindestens 3 Richtungen", lambda s: len({r["dir"] for r in s}) >= 3)]),
    ("nordkirchen-rad-4h", {"start": [7.52518, 51.73802], "activity": "bike", "hours": 4, "natur": "hoch"},
     "„einige Strecken doppelt gefahren, war das nötig“", [
         ("erste Runde ≤ 3 % doppelt", lambda s: s[0]["doubled"] <= 3)]),
    ("hilden-rad", {"start": [6.9395, 51.168], "activity": "bike", "hours": 2, **HOCH},
     "„Rückweg nur durch die Stadt, warum nichts über die Urdenbacher Kämpe“ · „zu viel durch Orte“ · „alle Grüngürtel geschickt kombiniert“", [
         ("eine Runde durch die Urdenbacher Kämpe", lambda s: any(km(r, "Urdenbacher") >= 1.0 for r in s)),
         ("erste Runde ≤ 30 % durch Häuser", lambda s: s[0]["town"] <= 30),
         ("erste Runde durch ≥ 2 Schutzgebiete", lambda s: s[0]["reserves"] >= 2)]),
    ("rhein-rad", {"start": [6.81206, 51.14162], "activity": "bike", "hours": 2, **HOCH, "wasser": True},
     "„das ist eigentlich die beste, wunderbar am Rhein lang“ · „Rückweg entlang der Münchner Straße … stärker bestrafen“ · „viel zu viel Wohngebiet“", [
         ("unter den ersten 3 eine Runde ≥ 15 km auf dem Rheinradweg", lambda s: any(km(r, *RHEIN) >= 15 for r in s[:3])),
         ("keine Runde mit > 1 km an einer Straße am Stück", lambda s: all(r["road"] <= 1000 for r in s)),
         ("keine Runde > 30 % durch Häuser", lambda s: all(r["town"] <= 30 for r in s))]),
    ("zons-rad-3h", {"start": [6.8448, 51.1231], "activity": "bike", "hours": 3, **HOCH, "bekannt": "hoch", "wasser": True},
     "„es gibt einen wunderbaren Radweg am Rhein entlang – warum haben wir den nicht genommen?“", [
         ("eine Runde ≥ 25 km auf dem Rheinradweg", lambda s: any(km(r, *RHEIN) >= 25 for r in s))]),
    ("koeln-rad", {"start": [6.97278, 50.94156], "activity": "bike", "hours": 2, **HOCH, "bekannt": "niedrig"},
     "„warum hat diese gegen das 2. Ziel gewonnen“ (eine lange schmale Runde an beiden Ufern ist keine schlechte Form)", [
         ("mindestens 3 Vorschläge", lambda s: len(s) >= 3),
         ("erste Runde ≥ 10 km auf dem Rheinradweg", lambda s: km(s[0], *RHEIN) >= 10)]),
    ("duesseldorf-wandern", {"start": [6.7763, 51.2254], "hours": 2},
     "„mitten aus der Stadt sind alle Routen schwach … vielleicht benutze ich den falschen Maßstab“", [
         ("mindestens 2 Vorschläge", lambda s: len(s) >= 2),
         ("erste Runde ≤ 35 % durch Häuser", lambda s: s[0]["town"] <= 35)]),
    ("meran-wandern", {"start": [11.09986, 46.68463], "hours": 3, **HOCH, "bekannt": "hoch"},
     "„schöner Weg in der Natur“ (Waalwege, Tappeiner)", [
         ("eine Runde ≥ 2 km auf Waalweg / Tappeiner", lambda s: any(km(r, "Waalweg", "Tappeiner") >= 2.0 for r in s))]),
    ("meran-flach", {"start": [11.1597, 46.6713], "hours": [2.5, 3.5], "ascent": [0, 200]},
     "„eine Einschränkung bei den Höhenmetern“: höchstens 200 m", [
         ("keine Runde über 200 m", lambda s: all(r["up"] <= 200 for r in s))]),
    ("meran-steil", {"start": [11.1597, 46.6713], "hours": [4, 5], "ascent": [500, 900], "t_level": 3},
     "500–900 Höhenmeter gewünscht: ein echter Anstieg", [
         ("erste Runde 500–900 m", lambda s: 500 <= s[0]["up"] <= 900)]),
]
EVERY = [("keine Runde kreuzt sich mehr als einmal", lambda s: all(r["cross"] <= 1 for r in s)),
         ("jede Runde hat einen echten Namen", lambda s: all(r["name"] and "Grünstück" not in r["name"] for r in s))]


def view(result):
    """The shown suggestions as small records, the same for this version and for older ones."""
    out = []
    for c in result["candidates"]:
        if not c.get("chosen"):
            continue
        n = c["numbers"]
        named = {}
        for key in ("reserves", "lakes", "known"):
            for row in n.get(key) or []:
                named[row[0]] = named.get(row[0], 0.0) + row[1]
        b = c.get("bearing")
        out.append({"name": c.get("name", ""), "km": n["km"], "up": n["ascent"], "forest": n.get("forest_pct", 0),
                    "town": n.get("town_pct", n.get("built_pct", 0)), "road": n.get("road_longest_m", n.get("main_longest_m", 0)),
                    "cross": n.get("self_cross", 0), "doubled": n.get("doubled_pct", 0), "named": named,
                    "reserves": len(n.get("reserves") or []), "reserve_km": sum(r[1] for r in n.get("reserves") or []),
                    "dir": DIRS[int((b + 22.5) // 45) % 8] if isinstance(b, (int, float)) else str(b), "notes": result.get("notes", [])})
    return out


OLD = """import json, sys, suggest as S
b = json.loads(sys.argv[1]); b["hours"] = b["hours"] if not isinstance(b["hours"], list) else sum(b["hours"]) / 2
print(json.dumps(S.suggest(S.simple_wish(b, S.load_weights()))))"""


def run(body, old=None):
    if old:
        if "ascent" in body:
            return None  # ranges did not exist
        r = subprocess.run([sys.executable, "-c", OLD, json.dumps(body)], cwd=old, capture_output=True, text=True, timeout=300)
        return json.loads(r.stdout) if r.returncode == 0 else {"candidates": [], "notes": [r.stderr[-200:]]}
    import suggest as S
    from character import wish as make_wish
    st = S.load_settings()
    return S.suggest(make_wish(body, st), st)


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    old = os.path.expanduser(args.pop(0)) if "--old" in sys.argv else None
    good = total = 0
    for name, body, words, checks in CASES:
        if args and not any(a in name for a in args):
            continue
        result = run(body, old)
        if result is None:
            print("–  %-20s gab es in dieser Version nicht" % name)
            continue
        s = view(result)
        failed = [text for text, test in checks + EVERY if not (s and test(s))]
        good, total = good + len(checks) + len(EVERY) - len(failed), total + len(checks) + len(EVERY)
        print("%s  %-20s %d Vorschläge, %.1f s · %s" % ("ok" if not failed else "✗ ", name, len(s), result.get("seconds", 0),
                                                        s[0]["name"] if s else "–"))
        for text in failed:
            print("      nicht erfüllt: %s      (%s)" % (text, words))
    print("%d von %d Aussagen erfüllt" % (good, total))
