#!/usr/bin/env python3
"""Tour-suggestions POC from the terminal – the mode for judging routes without the page (also for the AI).

  python3 tools/suggest-poc/run.py --start Hilden --hours 2 --pace gemütlich --effort leicht --natur hoch --ruhe hoch
  python3 tools/suggest-poc/run.py --start Hilden --activity bike --hours 2 --wasser --png

Prints a table of all candidates and a short text per chosen route; writes the run (JSON), every candidate as GeoJSON
and – with --png – a picture of the map with the chosen routes (headless Chrome on serve.py's page; started here when needed).
Output folder: ~/onpath-suggest-poc/runs/<run>/ (SUGGEST_DATA changes the base).
"""
import argparse
import glob
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.request

import suggest as S

p = argparse.ArgumentParser()
p.add_argument("--start", default="Hilden", help="place name (weights.json 'places', else Nominatim) or 'lat,lon'")
p.add_argument("--activity", default="hike", choices=S.ACTIVITIES)
p.add_argument("--hours", type=float, default=2.0)
p.add_argument("--pace", default="normal", help="gemütlich | normal | zügig, or km/h on the flat")
p.add_argument("--t", type=int, default=2, help="SAC level T1..T5 (hike)")
p.add_argument("--effort", default="leicht", help="leicht | mittel | sportlich (or relaxed | medium | sporty)")
p.add_argument("--natur", default="mittel", choices=["niedrig", "mittel", "hoch"])
p.add_argument("--ruhe", default="mittel", choices=["niedrig", "mittel", "hoch"])
p.add_argument("--bekannt", default="mittel", choices=["niedrig", "mittel", "hoch"])
p.add_argument("--wasser", action="store_true")
p.add_argument("--surface", default=None, help="Wegbeschaffenheit: an option of weights.json surface.<activity> (hike: natur / gemischt / befestigt; bike: asphalt / fest / egal; gravel: schotter / gemischt; mtb: trails / forst)")
for k in ("forest", "quiet", "water", "signed", "natural"):
    p.add_argument("--" + k, type=float, default=None, help="exact slider 0..1 (wins over the level)")
p.add_argument("--seed", type=int, default=1)
p.add_argument("--method", default="a", choices=["a", "b", "c"], help="a = fan of routed loops, b = search in the way network, c = green-patch chain")
p.add_argument("--ai", action="store_true", help="mit KI-Regionswissen (the AI's picks per map area: score part + destinations)")
p.add_argument("--compare-ai", action="store_true", help="the same wish without and with the AI's region knowledge, side by side")
p.add_argument("--png", action="store_true", help="also write map pictures (chosen routes; one per route by way kind)")
p.add_argument("--port", type=int, default=int(os.environ.get("SUGGEST_PORT", 8790)))
a = p.parse_args()

W = S.load_weights()
place = S.find_place(a.start, W)
if not place:
    sys.exit("Ort nicht gefunden: " + a.start)
try:
    pace = float(a.pace)
except ValueError:
    pace = a.pace
body = {"start": place[1], "start_name": place[0], "activity": a.activity, "hours": a.hours, "pace": pace,
        "t_level": a.t, "effort": a.effort, "natur": a.natur, "ruhe": a.ruhe, "bekannt": a.bekannt,
        "wasser": a.wasser, "surface": a.surface, "seed": a.seed, "method": a.method,
        "fein": {k: getattr(a, k) for k in ("forest", "quiet", "water", "signed", "natural")}}
off = None
if a.compare_ai:  # first without, then with – same wish, same seed
    off = S.suggest(S.simple_wish(dict(body, ai=False), W), W)
    S.save_run(off)
wish = S.simple_wish(dict(body, ai=a.ai or a.compare_ai), W)
res = S.suggest(wish, W)
path = S.save_run(res)
out = path[:-5]
os.makedirs(out, exist_ok=True)
for c in res["candidates"]:
    gj = {"type": "Feature", "properties": {k: c[k] for k in ("id", "name", "chosen", "score", "parts", "numbers", "story")},
          "geometry": {"type": "LineString", "coordinates": c["coords"]}}
    with open(os.path.join(out, "%02d.geojson" % c["id"]), "w", encoding="utf-8") as f:
        json.dump(gj, f, ensure_ascii=False)
text = S.table(res)
with open(os.path.join(out, "table.txt"), "w", encoding="utf-8") as f:
    f.write(text + "\n")
print(text)
print("\nLauf: %s\nOrdner: %s" % (res["run"], out))
if off:
    cmp_text = S.compare_ai(off, res)
    with open(os.path.join(out, "vergleich-ki.txt"), "w", encoding="utf-8") as f:
        f.write(cmp_text + "\n")
    print("\n" + cmp_text + "\nLauf ohne KI: " + off["run"])

if a.png:
    base = "http://127.0.0.1:%d" % a.port
    try:
        urllib.request.urlopen(base + "/ping", timeout=1).read()
    except Exception:  # expected: the page server is not running yet
        subprocess.Popen([sys.executable, os.path.join(S.HERE, "serve.py"), "--port", str(a.port)],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
        time.sleep(1.5)
    chrome = os.environ.get("CHROME") or next((c for c in [shutil.which("google-chrome"), shutil.which("chromium"),
                                                        "/opt/pw-browsers/chromium"]
                                               + glob.glob(os.path.expanduser("~/.cache/ms-playwright/chromium-*/chrome-linux*/chrome"))
                                               if c and os.path.exists(c)), None)
    if not chrome:
        sys.exit("kein Chrome / Chromium gefunden (CHROME=<Pfad> setzen)")
    shots = [(res["run"], "", "overview.png")] + [(res["run"], "&sel=%d&only=%d" % (c["id"], c["id"]), "route-%02d.png" % c["id"])
                                                  for c in res["candidates"] if c["chosen"]]
    if off:
        shots.insert(1, (off["run"], "", "overview-ohne-ki.png"))
    for run, q, name in shots:
        png = os.path.join(out, name)
        subprocess.run([chrome, "--headless=new", "--no-sandbox", "--hide-scrollbars", "--window-size=1500,1000",
                        "--virtual-time-budget=9000", "--screenshot=" + png, "%s/?run=%s%s" % (base, run, q)],
                       capture_output=True, timeout=90)
        print(png if os.path.isfile(png) else "Bild fehlt: " + png)
