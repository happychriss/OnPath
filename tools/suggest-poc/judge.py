#!/usr/bin/env python3
"""Tour-suggestions POC – judging by eye: fixed wishes at places with different terrain, one picture per run.

  python3 tools/suggest-poc/judge.py --method auto --tag after            # all cases
  python3 tools/suggest-poc/judge.py --method b --tag before --only nk    # cases whose id contains the word
  python3 tools/suggest-poc/judge.py --method c --tag x --jobs 4 --no-png

Per case: the shown suggestions as lines on the map (one picture, pieces on / beside a busy road drawn dark red, the
start as a dot) and a text block with the numbers one needs to judge a loop like a local would (through houses,
deep nature, longest stretch at a road, walked twice, meets itself, known routes, lakes, protected areas).
Output: ~/onpath-suggest-poc/night/pics/<tag>/<case>-<method>.png and ~/onpath-suggest-poc/night/<tag>-<method>.txt
(owner 2026-11-13: "du solltest dir die Bilder auch selber angucken und deine eigene Bewertung machen").
The bench (bench.py) checks plain expectations; this is the second yardstick: marks given from the pictures.
"""
import argparse
import glob
import json
import os
import shutil
import subprocess
import sys
import time
from concurrent.futures import ProcessPoolExecutor

import suggest as S

# id, place label, wish (the page's simple controls)
CASES = [
    # mixed: a town with green belts around it (the owner's home ground)
    ("hil-hike-2h", "Hilden Mitte", dict(start=[6.9395, 51.1680], activity="hike", hours=2, pace="gemütlich", effort="leicht",
                                         natur="hoch", ruhe="hoch", bekannt="mittel")),
    ("heide-hike-2h", "Ohligser Heide", dict(start=[6.9866, 51.1645], activity="hike", hours=2, pace="gemütlich",
                                             effort="leicht", natur="hoch", ruhe="hoch", bekannt="mittel")),
    ("hil-bike-2h", "Hilden Mitte", dict(start=[6.9395, 51.1680], activity="bike", hours=2, pace="normal", effort="leicht",
                                         natur="hoch", ruhe="mittel", bekannt="mittel")),
    ("lan-bike-2h", "Langenfeld", dict(start=[6.9500, 51.1100], activity="bike", hours=2, pace="normal", effort="leicht",
                                       natur="hoch", ruhe="hoch", bekannt="mittel")),
    # flat farmland with woods and a moated castle – Münsterland (the owner's comments of 2026-11-13 23:30–23:48)
    ("nk-hike-2h", "Nordkirchen", dict(start=[7.5251792, 51.7380176], activity="hike", hours=2, pace="normal", effort="leicht",
                                       natur="hoch", ruhe="hoch", bekannt="mittel")),
    ("nk-bike-2h", "Nordkirchen", dict(start=[7.5251792, 51.7380176], activity="bike", hours=2, pace="normal", effort="leicht",
                                       natur="hoch", ruhe="mittel", bekannt="mittel")),
    ("nk-bike-4h", "Nordkirchen", dict(start=[7.5251792, 51.7380176], activity="bike", hours=4, pace="normal", effort="leicht",
                                       natur="hoch", ruhe="mittel", bekannt="mittel", surface="egal")),
    # big connected nature, known for walking: Eifel (Monschau, Rursee), Sauerland (Winterberg), Siebengebirge, Ahr
    ("mon-hike-3h", "Monschau", dict(start=[6.2416, 50.5545], activity="hike", hours=3, pace="normal", effort="mittel",
                                     natur="hoch", ruhe="mittel", bekannt="hoch")),
    ("ein-hike-3h", "Einruhr (Rursee)", dict(start=[6.3800, 50.5830], activity="hike", hours=3, pace="normal", effort="mittel",
                                             natur="hoch", ruhe="hoch", bekannt="mittel", wasser=True)),
    ("wb-hike-3h", "Winterberg", dict(start=[8.5345, 51.1945], activity="hike", hours=3, pace="normal", effort="mittel",
                                      natur="hoch", ruhe="hoch", bekannt="hoch")),
    ("sg-hike-3h", "Königswinter", dict(start=[7.1940, 50.6745], activity="hike", hours=3, pace="normal", effort="mittel",
                                        natur="hoch", ruhe="mittel", bekannt="hoch")),
    ("ahr-hike-3h", "Altenahr", dict(start=[6.9886, 50.5167], activity="hike", hours=3, pace="normal", effort="mittel",
                                     natur="hoch", ruhe="mittel", bekannt="hoch")),
    # rail trails and river valleys by bike: Wuppertal (Nordbahntrasse, Sambatrasse, Korkenziehertrasse, Niederbergbahn),
    # towards Mettmann, and the owner's regular ride Wuppertal – Schwelm – Hagen – Ruhr – Fröndenberg (start points along it)
    ("wup-bike-2h", "Wuppertal Mirke", dict(start=[7.1445, 51.2665], activity="bike", hours=2, pace="normal", effort="mittel",
                                            natur="mittel", ruhe="hoch", bekannt="mittel")),
    ("wup-bike-3h", "Wuppertal Vohwinkel", dict(start=[7.0730, 51.2330], activity="bike", hours=3, pace="normal", effort="mittel",
                                                natur="hoch", ruhe="hoch", bekannt="mittel")),
    ("met-bike-2h", "Mettmann", dict(start=[6.9750, 51.2500], activity="bike", hours=2, pace="normal", effort="mittel",
                                     natur="hoch", ruhe="hoch", bekannt="mittel")),
    ("swm-bike-2h", "Schwelm", dict(start=[7.2890, 51.2850], activity="bike", hours=2, pace="normal", effort="mittel",
                                    natur="mittel", ruhe="hoch", bekannt="mittel")),
    ("hag-bike-3h", "Hagen", dict(start=[7.4600, 51.3625], activity="bike", hours=3, pace="normal", effort="mittel",
                                  natur="hoch", ruhe="hoch", bekannt="mittel", wasser=True)),
    ("wet-bike-3h", "Wetter (Ruhr)", dict(start=[7.3900, 51.3870], activity="bike", hours=3, pace="normal", effort="mittel",
                                          natur="hoch", ruhe="hoch", bekannt="hoch", wasser=True)),
    ("fro-bike-3h", "Fröndenberg", dict(start=[7.7660, 51.4720], activity="bike", hours=3, pace="normal", effort="mittel",
                                        natur="hoch", ruhe="mittel", bekannt="mittel", wasser=True)),
    # the mountain control
    ("meran-hike-3h", "Meran", dict(start=[11.1597, 46.6713], activity="hike", hours=3, pace="normal", effort="mittel",
                                    t_level=2, natur="hoch", ruhe="mittel", bekannt="hoch")),
]
NIGHT = os.path.join(S.DATA, "night")
COLORS = ["#1746c9", "#c2185b", "#00897b", "#ef6c00", "#6a1b9a"]

PAGE = """<!doctype html><meta charset="utf-8">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>html,body,#m{margin:0;height:100%}#l{position:absolute;z-index:999;left:8px;top:8px;background:rgba(255,255,255,.93);
font:12px/1.35 sans-serif;padding:6px 9px;border-radius:6px;max-width:640px;box-shadow:0 1px 4px rgba(0,0,0,.3)}
#l b{font-size:13px}.sw{display:inline-block;width:18px;height:5px;margin-right:5px;vertical-align:middle}</style>
<div id="m"></div><div id="l"></div><script>
const D = __DATA__;
const map = L.map('m', {zoomControl:false, attributionControl:false});
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {maxZoom:19}).addTo(map);
let b = [];
D.routes.slice().reverse().forEach((r) => {
  const ll = r.coords.map(c => [c[1], c[0]]);
  b = b.concat(ll);
  L.polyline(ll, {color:'#fff', weight:r.i == 0 ? 9 : 7, opacity:.85}).addTo(map);
  L.polyline(ll, {color:r.color, weight:r.i == 0 ? 5.5 : 3.5, opacity:.95}).addTo(map);
  // pieces on / beside a busy road: dark red on top
  let run = null;
  for (let k = 0; k < r.cls.length; k++) {
    if (r.cls[k] == 'G') { if (!run) run = [ll[k]]; run.push(ll[k + 1]); }
    else if (run) { L.polyline(run, {color:'#7a0000', weight:r.i == 0 ? 6.5 : 4.5}).addTo(map); run = null; }
  }
  if (run) L.polyline(run, {color:'#7a0000', weight:5}).addTo(map);
});
L.circleMarker([D.start[1], D.start[0]], {radius:7, color:'#000', fillColor:'#fff', fillOpacity:1, weight:3}).addTo(map);
map.fitBounds(L.latLngBounds(b).pad(0.06));
document.getElementById('l').innerHTML = '<b>' + D.title + '</b><br>' + D.routes.map(r =>
  '<span class="sw" style="background:' + r.color + '"></span>' + r.text).join('<br>') + (D.note ? '<br><i>' + D.note + '</i>' : '');
</script>"""


def line(c):
    n = c["numbers"]
    fl = " ".join("%s -%.0f" % kv for kv in c.get("flaws", {}).items())
    return ("%-40s %5.1f km %s ↑%4d %5.1f P [%s]| Häuser %2d%% tief %2d%% grün %s km Str %2d%% | Straße max %4d m, "
            "Anteil %2d%% | dopp %2d%% kreuzt %d | mark %2d%%%s%s%s"
            % (c["name"][:40], n["km"], S.fmt_h(n["hours"]), n["ascent"], c["score"], c.get("origin", "?"), n.get("built_pct", 0),
               n.get("deep_pct", 0), n.get("green_km", "?"), n["street_pct"], n["main_longest_m"], n.get("roadside_pct", 0),
               n["doubled_pct"], n.get("self_cross", 0), n["signed_pct"],
               " | Bahntrasse %.1f km" % n["railtrail_km"] if n.get("railtrail_km") else "",
               " | Wasser %.1f km" % n["big_water_km"] if n.get("big_water_km") else "", " | Abzug: " + fl if fl else ""))


def one(args):
    cid, place, wish_in, method, tag, png, seed = args
    W = S.load_weights()
    wish = S.simple_wish(dict(wish_in, start_name=place, seed=seed, method=method), W)
    t0 = time.time()
    try:
        r = S.suggest(wish, W)
    except Exception as ex:
        return cid, "%s  FEHLER %s: %s" % (cid, type(ex).__name__, ex), None
    S.save_run(r)
    ch = [c for c in r["candidates"] if c["chosen"]]
    L = ["== %s · %s · %s %gh · %s · %d Vorschläge · %.1f s · Lauf %s" % (
        cid, place, wish["activity"], wish["hours"], r["method"], len(ch), time.time() - t0, r["run"])]
    for nt in r.get("notes", []):
        L.append("   Hinweis: " + nt)
    for i, c in enumerate(ch):
        L.append(" %d %s" % (i + 1, line(c)))
        g = S.goals_text(c["numbers"])
        if g:
            L.append("     " + g)
        L.append("     schwächste Teile: %s%s" % (" ".join(
            "%s %.2f" % (k, v) for k, v in sorted(c["parts"].items(), key=lambda x: x[1])[:6]),
            " · Arbeitsname: " + c["work_name"] if c.get("work_name") else ""))
    pic = None
    if png and ch:
        d = os.path.join(NIGHT, "pics", tag)
        os.makedirs(d, exist_ok=True)
        data = {"title": "%s · %s · %s %g h · Verfahren %s · %s" % (cid, place, wish["activity"], wish["hours"], r["method"], tag),
                "start": wish["start"], "note": "; ".join(r.get("notes", []))[:160],
                "routes": [{"i": i, "color": COLORS[i % len(COLORS)], "coords": c["coords"], "cls": c["cls"],
                            "text": "%d · %s · %.1f km · %s · %.0f P · Häuser %d%% · tief %d%% · Straße max %d m%s" % (
                                i + 1, c["name"][:46], c["numbers"]["km"], S.fmt_h(c["numbers"]["hours"]), c["score"],
                                c["numbers"].get("built_pct", 0), c["numbers"].get("deep_pct", 0), c["numbers"]["main_longest_m"],
                                " · kreuzt %d×" % c["numbers"]["self_cross"] if c["numbers"].get("self_cross") else "")}
                           for i, c in enumerate(ch[:5])]}
        html = os.path.join(d, "%s-%s.html" % (cid, method))
        with open(html, "w", encoding="utf-8") as f:
            f.write(PAGE.replace("__DATA__", json.dumps(data, ensure_ascii=False)))
        pic = os.path.join(d, "%s-%s.png" % (cid, method))
        chrome = os.environ.get("CHROME") or next((c for c in [shutil.which("google-chrome"), shutil.which("chromium")]
                                                   + glob.glob(os.path.expanduser("~/.cache/ms-playwright/chromium-*/chrome-linux*/chrome"))
                                                   if c and os.path.exists(c)), None)
        if chrome:
            subprocess.run([chrome, "--headless=new", "--no-sandbox", "--hide-scrollbars", "--window-size=1300,900",
                            "--user-data-dir=/tmp/judge-chrome-%s-%d" % (cid, os.getpid()),
                            "--virtual-time-budget=9000", "--screenshot=" + pic, "file://" + html],
                           capture_output=True, timeout=120)
            shutil.rmtree("/tmp/judge-chrome-%s-%d" % (cid, os.getpid()), ignore_errors=True)
        os.remove(html)
        if not os.path.isfile(pic):
            pic = None
    return cid, "\n".join(L), pic


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--method", default="auto")
    ap.add_argument("--tag", default="x")
    ap.add_argument("--only", default="", help="comma-separated words; a case runs when its id contains one")
    ap.add_argument("--jobs", type=int, default=4)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--no-png", action="store_true")
    a = ap.parse_args()
    S.ensure_service()
    words = [w for w in a.only.split(",") if w]
    todo = [(cid, place, w, a.method, a.tag, not a.no_png, a.seed) for cid, place, w in CASES
            if not words or any(x in cid for x in words)]
    with ProcessPoolExecutor(a.jobs) as ex:
        res = list(ex.map(one, todo))
    text = "\n\n".join(t for _, t, _ in res)
    os.makedirs(NIGHT, exist_ok=True)
    out = os.path.join(NIGHT, "%s-%s%s.txt" % (a.tag, a.method, "-" + a.only.replace(",", "+") if a.only else ""))
    with open(out, "w", encoding="utf-8") as f:
        f.write(text + "\n")
    print(text)
    print("\nText: %s\nBilder: %s" % (out, ", ".join(p for _, _, p in res if p)))
