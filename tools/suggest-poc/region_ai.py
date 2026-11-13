#!/usr/bin/env python3
"""Tour-suggestions POC – "region knowledge" from an AI, once per map area (an experiment, owner 2026-11-13).

    python3 tools/suggest-poc/region_ai.py Hilden            # the map area that holds the place
    python3 tools/suggest-poc/region_ai.py 10-543-361        # a map area by id (Meran)

What it does: (1) reads the place names of one map area from our own map files, (2) asks the AI ONE question
about that area – only the area's box and its towns go out, nothing about a user –, (3) checks every name
the AI returns against the names our map really has (known routes, trail labels, lakes, parks, peaks, POIs).
Only checked names could ever be used as a bonus in the score. Result: a table + JSON under
~/onpath-suggest-poc/region-ai/.

The AI call here is `claude -p` (the Claude Code command line on this computer, run in an empty folder so it
knows nothing of this project). The real thing would be one API call on the map server when it builds an area.
"""
import difflib
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import unicodedata

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cells as CE  # noqa: E402
import pmt  # noqa: E402
import suggest as S  # noqa: E402

OUT = os.path.join(CE.DATA, "region-ai")
POI_KINDS = ("viewpoint", "castle", "ruins", "museum", "waterfall", "cave", "spring", "camping", "info", "picnic")


def norm(s):
    s = unicodedata.normalize("NFKD", s.lower().replace("ß", "ss")).encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9]+", " ", s).strip()


def map_names(x, y):
    """Every name our map files of this area carry, by source."""
    d = CE.folder(x, y)
    names = {"route": set(), "trail": set(), "water": set(), "park": set(), "peak": set(), "place": set(), "poi": set()}
    towns = {}
    for fn, want in (("overlays.pmtiles", ("routes", "trailnames")),
                     ("base.pmtiles", ("water_name", "waterway", "park", "mountain_peak", "place"))):
        p = pmt.PMTiles(os.path.join(d, fn))
        for z in (12, 14):
            if not (p.minz <= z <= p.maxz):
                continue
            for zz, tx, ty in pmt.tiles_of_cell(x, y, z):
                raw = p.tile(zz, tx, ty)
                if not raw:
                    continue
                for layer, feats in pmt.layers(raw, want, zz, tx, ty).items():
                    for f in feats:
                        t = f["tags"]
                        n = t.get("name") or t.get("name:de") or ""
                        if layer == "routes":
                            for v in (n, t.get("ref") or ""):
                                if v:
                                    names["route"].add(v)
                        elif not n:
                            continue
                        elif layer == "trailnames":
                            names["trail"].add(n)
                        elif layer in ("water_name", "waterway"):
                            names["water"].add(n)
                        elif layer == "park":
                            names["park"].add(n)
                        elif layer == "mountain_peak":
                            names["peak"].add(n)
                        elif layer == "place":
                            names["place"].add(n)
                            if t.get("class") in ("city", "town", "village"):
                                towns[n] = min(towns.get(n, 99), int(t.get("rank") or 50))
    pj = os.path.join(d, "pois.geojson")
    if os.path.isfile(pj):
        with open(pj, encoding="utf-8") as f:
            for ft in json.load(f)["features"]:
                pr = ft["properties"]
                if pr.get("name") and pr.get("kind") in POI_KINDS:
                    names["poi"].add(pr["name"])
    return names, [n for n, _ in sorted(towns.items(), key=lambda kv: kv[1])][:14]


PROMPT = """Du hilfst einer Wander- und Fahrrad-App. Es geht um ein Kartengebiet (Rechteck):
West {w:.3f}, Süd {s:.3f}, Ost {e:.3f}, Nord {n:.3f} (WGS84). Orte darin: {towns}.

Nenne die lohnendsten Ziele INNERHALB dieses Rechtecks für Wandern und Radfahren, so wie ein
Ortskundiger sie empfehlen würde: Gebiete (Wald, Heide, Tal, Park), bekannte Wander- und Radwege,
Seen und Ufer, Aussichtspunkte, Sehenswertes am Weg, gute Einstiegspunkte (Wanderparkplatz, Bahnhof).
Höchstens 25 Einträge, die besten zuerst. Nenne nur, was du sicher kennst – lieber weniger.
Benutze die Namen so, wie sie vor Ort und in OpenStreetMap heißen.

Antworte NUR mit JSON, ohne Text davor oder danach:
[{{"name": "...", "art": "gebiet|weg|see|aussicht|sehenswert|einstieg", "fuer": "wandern|rad|beides",
  "rang": 1-3, "warum": "ein kurzer Satz"}}]
(rang 3 = überregional bekannt, 2 = in der Region bekannt, 1 = lokaler Tipp)"""


def ask(prompt):
    """One AI call. Returns (text, seconds)."""
    t = time.time()
    with tempfile.TemporaryDirectory() as empty:
        r = subprocess.run(["claude", "-p", prompt, "--output-format", "json", "--max-turns", "1"],
                           cwd=empty, capture_output=True, text=True, timeout=300)
    if r.returncode != 0:
        raise RuntimeError("claude -p failed: " + (r.stderr or r.stdout)[:400])
    o = json.loads(r.stdout)
    return o.get("result", ""), time.time() - t, o


def check(name, names):
    """Is this name in our map? -> (source, our name) or (None, None). Exact words first, then a near match."""
    n = norm(name)
    words = [w for w in n.split() if len(w) >= 4] or n.split()
    best = (0.0, None, None)
    for src, pool in names.items():
        for m in pool:
            nm = norm(m)
            if not nm:
                continue
            if n == nm or (words and all(w in nm for w in words)) or (len(nm) >= 6 and nm in n):
                return src, m
            r = difflib.SequenceMatcher(None, n, nm).ratio()
            if r > best[0]:
                best = (r, src, m)
    return (best[1], best[2]) if best[0] >= 0.86 else (None, None)


def main():
    arg = sys.argv[1] if len(sys.argv) > 1 else "Hilden"
    m = re.fullmatch(r"10-(\d+)-(\d+)", arg)
    if m:
        x, y = int(m.group(1)), int(m.group(2))
    else:
        pl = S.find_place(arg, S.load_weights())
        if not pl:
            sys.exit("Ort nicht gefunden: " + arg)
        x, y = CE.cell_of(*pl[1])
    CE.get(x, y)
    w, s, e, n = CE.cell_bbox(x, y)
    names, towns = map_names(x, y)
    prompt = PROMPT.format(w=w, s=s, e=e, n=n, towns=", ".join(towns) or "(keine Angabe)")
    text, secs, meta = ask(prompt)
    js = re.search(r"\[.*\]", text, re.S)
    items = json.loads(js.group(0)) if js else []
    found = 0
    for it in items:
        src, ours = check(it.get("name", ""), names)
        it["in_karte"], it["karten_name"] = src, ours
        found += bool(src)
    os.makedirs(OUT, exist_ok=True)
    out = os.path.join(OUT, "10-%d-%d.json" % (x, y))
    with open(out, "w", encoding="utf-8") as f:
        json.dump({"cell": [x, y], "bbox": [w, s, e, n], "towns": towns, "prompt": prompt, "seconds": round(secs, 1),
                   "cost_usd": meta.get("total_cost_usd"), "map_name_counts": {k: len(v) for k, v in names.items()},
                   "items": items, "raw": text}, f, ensure_ascii=False, indent=1)
    print("Kartengebiet 10-%d-%d · Orte: %s" % (x, y, ", ".join(towns)))
    print("Namen in unserer Karte:", {k: len(v) for k, v in names.items()})
    print("KI: %d Einträge in %.0f s, Kosten laut Aufruf: %s $ · in unserer Karte gefunden: %d" % (
        len(items), secs, meta.get("total_cost_usd"), found))
    for it in items:
        print(" %s r%s %-10s %-7s %-44s %s" % ("✓" if it["in_karte"] else "–", it.get("rang"), it.get("art"), it.get("fuer"),
                                               it.get("name", "")[:44],
                                               ("= %s: %s" % (it["in_karte"], it["karten_name"])) if it["in_karte"] else ""))
        print("        " + it.get("warum", ""))
    print("gespeichert:", out)


if __name__ == "__main__":
    main()
