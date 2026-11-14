#!/usr/bin/env python3
"""Tour suggestions in the terminal – one wish, the suggestions as text, on request as pictures.

  python3 tools/suggest-poc/run.py Hilden --hours 1.5-2.5 --ascent 0-150
  python3 tools/suggest-poc/run.py "51.18413, 6.94460" --wasser --png        (map picture of the suggestions + the first one)
  python3 tools/suggest-poc/run.py Zons --activity bike --hours 3 --natur hoch --all

The run is saved like a run from the page (~/onpath-suggest-poc/runs/<run>.json; pictures in runs/<run>/).
"""
import argparse
import os
import shutil
import subprocess
import threading
from http.server import ThreadingHTTPServer

import suggest as S
from character import wish as make_wish


def pictures(run, count=1, size="1500,1000"):
    """Screenshots of the local page showing a saved run: overview.png and route-<i>.png for the first `count`."""
    import serve
    chrome = os.environ.get("CHROME") or shutil.which("google-chrome") or shutil.which("chromium")
    if not chrome:
        return []
    srv = ThreadingHTTPServer(("127.0.0.1", 0), serve.H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    out_dir = os.path.join(S.DATA, "runs", run)
    os.makedirs(out_dir, exist_ok=True)
    made = []
    for name, extra in [("overview", "")] + [("route-%d" % i, "&only=%d" % i) for i in range(1, count + 1)]:
        png = os.path.join(out_dir, name + ".png")
        subprocess.run([chrome, "--headless=new", "--no-sandbox", "--disable-gpu", "--hide-scrollbars", "--window-size=" + size,
                        "--virtual-time-budget=9000", "--screenshot=" + png,
                        "http://127.0.0.1:%d/?run=%s%s" % (srv.server_address[1], run, extra)],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=120)
        made += [png] if os.path.isfile(png) else []
    srv.shutdown()
    return made


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("start")
    ap.add_argument("--activity", default="hike")
    ap.add_argument("--t", dest="t_level", type=int, default=2)
    span = lambda s: [float(x) for x in s.split("-")] if "-" in s else float(s)
    ap.add_argument("--hours", type=span, default=2.0, help="2 or a range 1.5-2.5")
    ap.add_argument("--ascent", type=span, default=None, help="metres up as a range, e.g. 0-200 (default: from the effort)")
    for k, default in (("pace", "normal"), ("effort", "mittel"), ("surface", "gemischt"), ("natur", "mittel"), ("ruhe", "mittel"), ("bekannt", "mittel")):
        ap.add_argument("--" + k, default=default)
    ap.add_argument("--wasser", action="store_true")
    ap.add_argument("--tips", action="store_true", help="mit KI-Regionswissen (region_ai.py)")
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--all", action="store_true", help="every loop of the pool, not only the suggestions")
    ap.add_argument("--png", action="store_true")
    a = ap.parse_args()
    st = S.load_settings()
    place = S.find_place(a.start, st)
    if not place:
        raise SystemExit("Ort nicht gefunden: " + a.start)
    r = S.suggest(make_wish(dict(vars(a), start=place[1], start_name=place[0]), st), st)
    S.save_run(r)
    print(S.table(r, a.all))
    print("Lauf %s" % r["run"])
    if a.png:
        print("\n".join(pictures(r["run"])))
