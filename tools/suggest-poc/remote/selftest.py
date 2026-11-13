#!/usr/bin/env python3
"""Tour suggestions, remote service – self-test against a running service on this computer.

    python3 tools/suggest-poc/remote/selftest.py [http://127.0.0.1:8780]

Checks: password needed (none / wrong -> 401, nothing computed); with it suggestions for Hilden, hike 2 h; a foreign
Origin gets no CORS headers, plan.onpath.earth and localhost do; an oversize request -> 413; a bad wish -> 400; two
requests at once -> the second waits (queued), both answer. Exit code 0 = all green. The password is read from
~/onpath-suggest-poc/remote/token and never printed.
"""
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8780").rstrip("/")
TOKEN = open(os.path.join(os.environ.get("SUGGEST_DATA", os.path.expanduser("~/onpath-suggest-poc")), "remote", "token")).read().strip()
LOGF = os.path.join(os.environ.get("SUGGEST_DATA", os.path.expanduser("~/onpath-suggest-poc")), "remote", "remote.log")
WISH = {"start": [6.9866, 51.1645], "activity": "hike", "hours": 2, "natur": "hoch", "ruhe": "hoch", "bekannt": "mittel"}
ok_all = True


def call(path, body=None, token=TOKEN, origin=None, method=None, raw=None, timeout=150):
    h = {}
    if token:
        h["X-OnPath-Token"] = token
    if origin:
        h["Origin"] = origin
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(BASE + path, data=data, headers=h, method=method)
    try:
        r = urllib.request.urlopen(req, timeout=timeout)
        return r.status, dict(r.headers), r.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()


def check(name, cond, detail=""):
    global ok_all
    ok_all = ok_all and bool(cond)
    print(("ok   " if cond else "FAIL ") + name + (("  – " + detail) if detail else ""))


def log_lines():
    return sum(1 for _ in open(LOGF)) if os.path.isfile(LOGF) else 0


# password
n0 = log_lines()
c, _, _ = call("/v1/ping", token=None)
check("ping without password -> 401", c == 401, str(c))
c, _, _ = call("/v1/ping", token="wrong-" + "x" * 20)
check("ping with a wrong password -> 401", c == 401, str(c))
c, _, b = call("/v1/ping")
check("ping with the password -> ok", c == 200 and json.loads(b).get("ok") is True, str(c))
t0 = time.time()
c, _, b = call("/v1/suggest", WISH, token=None)
check("suggest without password -> 401 at once, nothing computed", c == 401 and time.time() - t0 < 1.0, "%d in %.2fs" % (c, time.time() - t0))
c, _, _ = call("/v1/suggest", WISH, token="wrong-" + "x" * 20)
check("suggest with a wrong password -> 401", c == 401, str(c))
log_new = open(LOGF).read().splitlines()[n0:] if os.path.isfile(LOGF) else []
check("the log never shows the password", all(TOKEN not in l for l in open(LOGF).read().splitlines()) if os.path.isfile(LOGF) else True)

# a real answer
t0 = time.time()
c, h, b = call("/v1/suggest", WISH)
d = json.loads(b)
cands = d.get("candidates", [])
check("suggest with the password -> loops for the Ohligser Heide, hike 2 h", c == 200 and len(cands) >= 1,
      "%d loops in %.1fs, method %s%s" % (len(cands), time.time() - t0, d.get("method"), (" – " + str(d.get("message") or d.get("error"))) if not cands else ""))
if cands:
    c0 = cands[0]
    check("a loop has a name, a line and numbers", bool(c0.get("name")) and len(c0.get("coords", [])) > 20 and "km" in c0.get("numbers", {}),
          "%s · %.1f km · %d points" % (c0.get("name"), c0["numbers"].get("km", 0), len(c0.get("coords", []))))
    p0, p1 = c0["coords"][0], c0["coords"][-1]
    check("the line starts and ends near the start", abs(p0[0] - WISH["start"][0]) < 0.01 and abs(p1[1] - WISH["start"][1]) < 0.01)
last = open(LOGF).read().splitlines()[-1] if os.path.isfile(LOGF) else ""
check("the log line has the start rounded (~1 km), not exact", "51.16,6.99" in last and "6.9866" not in last and "51.1645" not in last, last[20:])

# CORS
c, h, _ = call("/v1/ping", origin="https://evil.example")
check("a foreign Origin gets no CORS headers", "Access-Control-Allow-Origin" not in h)
c, h, _ = call("/v1/ping", origin="https://plan.onpath.earth")
check("plan.onpath.earth gets its CORS header", h.get("Access-Control-Allow-Origin") == "https://plan.onpath.earth")
c, h, _ = call("/v1/ping", origin="http://localhost:5199")
check("localhost gets its CORS header", h.get("Access-Control-Allow-Origin") == "http://localhost:5199")
c, h, _ = call("/v1/suggest", token=None, origin="https://plan.onpath.earth", method="OPTIONS")
check("the browser's preflight is answered without password", c == 204 and "X-OnPath-Token" in h.get("Access-Control-Allow-Headers", ""), str(c))
c, h, _ = call("/v1/suggest", token=None, origin="https://plan.onpath.earth.evil.example", method="OPTIONS")
check("a look-alike Origin gets nothing", "Access-Control-Allow-Origin" not in h)

# limits
c, _, _ = call("/v1/suggest", raw=b"{" + b" " * 20000 + b"}")
check("an oversize request -> 413", c == 413, str(c))
c, _, b = call("/v1/suggest", {"start": [6.98, 51.16], "activity": "plane"})
check("a bad wish -> 400", c == 400, str(c))
c, _, _ = call("/v1/other")
check("any other path -> 404", c == 404, str(c))
c, _, _ = call("/")
check("no page at / -> 404", c == 404, str(c))

# two at once: one computes, one waits
res = {}


def one(k, seed):
    t = time.time()
    c, _, b = call("/v1/suggest", dict(WISH, seed=seed))
    res[k] = (c, time.time() - t, len(json.loads(b).get("candidates", [])))


ths = [threading.Thread(target=one, args=("a", 2)), threading.Thread(target=one, args=("b", 3))]
for t in ths:
    t.start()
    time.sleep(0.2)
for t in ths:
    t.join()
tail = open(LOGF).read().splitlines()[-2:]
waited = [float(l.split("(waited ")[1].split("s")[0]) for l in tail if "(waited " in l]
check("two at once: both answer", res["a"][0] == 200 and res["b"][0] == 200 and res["a"][2] and res["b"][2],
      "a %.1fs, b %.1fs" % (res["a"][1], res["b"][1]))
check("two at once: the second waited for the first (queued)", len(waited) == 2 and max(waited) > 0.5, "waited %s s" % waited)

print("\n" + ("ALLES GRÜN" if ok_all else "NICHT ALLES GRÜN"))
sys.exit(0 if ok_all else 1)
