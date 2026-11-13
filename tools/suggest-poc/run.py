#!/usr/bin/env python3
"""Tour-suggestions POC from the terminal – the mode for judging routes without the page (also for the AI).

  python3 tools/suggest-poc/run.py --start Hilden --hours 2 --pace 3.5 --effort relaxed --forest 0.9 --quiet 0.7

Prints a table of all candidates and a short text per chosen route; writes the run (JSON), every candidate as GeoJSON
and – with --png – a picture of the map with the chosen routes (needs serve.py's page; started here when needed).
Output folder: ~/onpath-suggest-poc/runs/<run>/ (SUGGEST_DATA changes the base).
"""
import argparse
import json
import os
import subprocess
import sys
import time
import urllib.request

import suggest as S

p = argparse.ArgumentParser()
p.add_argument("--start", default="Hilden", help="place name (weights.json 'places', else Nominatim) or 'lat,lon'")
p.add_argument("--hours", type=float, default=2.0)
p.add_argument("--pace", type=float, default=4.0, help="km/h on the flat")
p.add_argument("--t", type=int, default=2, help="SAC level T1..T5")
p.add_argument("--effort", default="relaxed", choices=["relaxed", "medium", "sporty"])
p.add_argument("--forest", type=float, default=0.5)
p.add_argument("--quiet", type=float, default=0.5)
p.add_argument("--water", type=float, default=0.0)
p.add_argument("--signed", type=float, default=0.3)
p.add_argument("--natural", type=float, default=0.5)
p.add_argument("--seed", type=int, default=1)
p.add_argument("--png", action="store_true", help="also write map pictures (chosen routes; one per route by way kind)")
p.add_argument("--port", type=int, default=8790)
a = p.parse_args()

W = S.load_weights()
place = S.find_place(a.start, W)
if not place:
    sys.exit("Ort nicht gefunden: " + a.start)
wish = S.make_wish(place[1], place[0], a.hours, a.pace, a.t, a.effort, a.forest, a.quiet, a.water, a.signed, a.natural,
                   a.seed)
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

if a.png:
    base = "http://127.0.0.1:%d" % a.port
    try:
        urllib.request.urlopen(base + "/ping", timeout=1).read()
    except Exception:  # expected: the page server is not running yet
        subprocess.Popen([sys.executable, os.path.join(S.HERE, "serve.py"), "--port", str(a.port)],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
        time.sleep(1.5)
    r = subprocess.run(["node", os.path.join(S.HERE, "shot.js"), base, res["run"], out], capture_output=True, text=True)
    print((r.stdout + r.stderr).strip())
