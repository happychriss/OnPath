#!/usr/bin/env python3
"""Tour suggestions – the service on the computer at home, for testing on the road (owner 2026-11-14).

A thin, locked front for the POC in the folder above: the planner (debug setting "Tourvorschläge (Test)") or the test
planner served by this computer asks it for loops. It is NOT the POC's page server (serve.py): no page, no feedback,
no place search – only

    GET  /v1/ping        {"ok": true, "busy": false}
    POST /v1/suggest     the wish (the POC's few controls, as the planner's poc.ts sends them) -> the chosen loops

Rules it keeps:
- A **password** (token) on every request: header `X-OnPath-Token`. Created once into ~/onpath-suggest-poc/remote/token
  (mode 600); never in git, never in a log, never in an answer. No / a wrong token: 401, nothing is computed.
- Listens on 127.0.0.1 unless started with --bind <address> (the owner's VPN step). It opens nothing to the outside.
- Browser pages may call it only from https://plan.onpath.earth and from localhost (CORS); other origins get no CORS
  headers. The test planner (test_planner.py) reaches it on its own address (same origin), which needs none.
- One computation at a time, a short queue, a hard time limit (the worker process is replaced when it overruns), a
  request size limit, a simple rate limit.
- The log has one line per request with the start rounded to ~1 km. The run itself (exact start, all candidates) is
  kept on this computer in ~/onpath-suggest-poc/remote/runs/ for looking at the routes later – `--no-save` turns
  that off.
- Map areas the POC does not have yet are fetched (or their build is asked for, as the app would) in the background;
  until they are there the answer is a calm {"preparing": true, …} instead of an error or a long wait.

The wish may carry the old single values (hours) or the new ranges (hours / duration = [from, to], ascent =
[from, to] metres up); an old "method" field is ignored – there is one method. settings.json is read at every request.
"""
import argparse
import hmac
import json
import multiprocessing as mp
import os
import secrets
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
POC = os.path.dirname(HERE)
DATA = os.environ.get("SUGGEST_DATA", os.path.expanduser("~/onpath-suggest-poc"))
RDIR = os.path.join(DATA, "remote")
TOKEN_FILE = os.path.join(RDIR, "token")
ALLOWED_ORIGINS = ("https://plan.onpath.earth",)
LOCAL_ORIGIN = ("http://localhost", "http://127.0.0.1", "http://[::1]", "https://localhost", "https://127.0.0.1")
ACTIVITIES = ("hike", "bike", "gravel", "mtb")
LEVELS = ("niedrig", "mittel", "hoch")


def token():
    """The password; made on first use. Mode 600, outside git."""
    os.makedirs(RDIR, mode=0o700, exist_ok=True)
    if not os.path.isfile(TOKEN_FILE):
        fd = os.open(TOKEN_FILE, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as f:
            f.write(secrets.token_urlsafe(24) + "\n")
    with open(TOKEN_FILE) as f:
        return f.read().strip()


def log(line):
    os.makedirs(RDIR, exist_ok=True)
    with open(os.path.join(RDIR, "remote.log"), "a", encoding="utf-8") as f:
        f.write(time.strftime("%Y-%m-%d %H:%M:%S ") + line + "\n")


# ---------------------------------------------------------------- the wish, checked

def clean_wish(b):
    """Only known fields with sane values reach the POC. Raises ValueError with a plain reason."""
    if not isinstance(b, dict):
        raise ValueError("wish must be an object")
    s = b.get("start")
    if not (isinstance(s, list) and len(s) == 2 and all(isinstance(v, (int, float)) for v in s)):
        raise ValueError("start must be [lon, lat]")
    lon, lat = float(s[0]), float(s[1])
    if not (-180 <= lon <= 180 and -85 <= lat <= 85):
        raise ValueError("start out of range")
    act = b.get("activity", "hike")
    if act not in ACTIVITIES:
        raise ValueError("unknown activity")
    # time and ascent: the old single value or the new range [from, to] (also as "duration")
    def span(v, lo, hi, what):
        vals = [float(x) for x in v] if isinstance(v, list) and len(v) == 2 else [float(v)]
        if not all(lo <= x <= hi for x in vals):
            raise ValueError("%s must be %g … %g" % (what, lo, hi))
        return sorted(vals) if len(vals) == 2 else vals[0]
    hours = span(b.get("duration") or b.get("hours", 2), 0.25, 10, "hours")
    w = {"start": [lon, lat], "start_name": str(b.get("start_name") or "")[:80], "activity": act, "hours": hours,
         "t_level": max(1, min(5, int(b.get("t_level", 2)))), "pace": "normal", "seed": int(b.get("seed", 1)) % 100000,
         "wasser": bool(b.get("wasser")), "ai": bool(b.get("ai") or b.get("tips"))}
    if isinstance(b.get("ascent"), list):
        w["ascent"] = span(b["ascent"], 0, 5000, "ascent")
    if b.get("pace") in ("gemütlich", "normal", "zügig"):
        w["pace"] = b["pace"]
    if b.get("effort") in ("leicht", "mittel", "sportlich"):
        w["effort"] = b["effort"]
    for k in ("natur", "ruhe", "bekannt"):
        if b.get(k) in LEVELS:
            w[k] = b[k]
    if isinstance(b.get("surface"), str):
        w["surface"] = b["surface"][:20]
    return w  # "method" in an old wish is ignored: there is one method


def rd5_name(lon, lat):
    """BRouter's 5°×5° tile of a point: E5_N50 = lon 5…10, lat 50…55."""
    x, y = int(lon // 5) * 5, int(lat // 5) * 5
    return "%s%d_%s%d" % ("E" if x >= 0 else "W", abs(x), "N" if y >= 0 else "S", abs(y))


# ---------------------------------------------------------------- the worker: the POC itself, in its own process

def worker_main(conn, service):
    """Runs in a child process: imports the POC once (its caches live here) and answers jobs until it is replaced."""
    os.environ["SUGGEST_SERVICE"] = service
    sys.path.insert(0, POC)
    try:
        os.nice(19)
    except OSError:  # expected: already at the lowest priority
        pass
    import suggest as S
    import cells as CE
    from character import wish as make_wish

    while True:
        try:
            job = conn.recv()
        except EOFError:
            return
        try:
            st = S.load_settings()
            wish = make_wish(job["wish"], st)
            if job["op"] == "need":
                target_m = wish["hours"][1] * wish["pace"] * 1000.0
                # a loop of length L stays within L/2 of its start: the map areas in that circle (at least 3 km, at most 30)
                reach = min(max(0.5 * target_m, 3000.0), 30000.0)
                need = CE.cells_for(wish["start"][0], wish["start"][1], reach)
                conn.send({"need": need, "missing": [c for c in need if not CE.have(*c)],
                           "segments": os.environ.get("ONPATH_SEGMENTS", os.path.expanduser("~/onpath-routing/segments"))})
                continue
            res = S.suggest(wish, st)
            if job.get("save"):
                d = os.path.join(RDIR, "runs")
                os.makedirs(d, exist_ok=True)
                with open(os.path.join(d, res["run"] + ".json"), "w", encoding="utf-8") as f:
                    json.dump(res, f, ensure_ascii=False)
            keep = ("km", "hours", "ascent", "ferry_m", "known", "reserves", "lakes", "forest_pct", "self_cross",
                    "street_pct", "town_pct", "deep_pct", "signed_pct", "water_km", "rail_km", "road_longest_m")
            out = []
            for c in res.get("candidates", []):
                if not c.get("chosen"):
                    continue
                n = c.get("numbers", {})
                out.append({"id": c["id"], "name": c["name"], "chosen": True, "score": c.get("score"),
                            "coords": [[round(p[0], 6), round(p[1], 6)] for p in c.get("coords", [])],
                            "numbers": {k: n[k] for k in keep if k in n}, "tips": [],
                            "reasons": c.get("reasons", []), "hints": c.get("hints", []), "axes": c.get("axes"), "why": c.get("why")})
            conn.send({"candidates": out, "method": res.get("method"), "seconds": res.get("seconds"),
                       "notes": res.get("notes", []), "weights_version": res.get("weights_version"), "run": res.get("run")})
        except Exception as e:  # reported to the caller as a calm error; the worker lives on
            conn.send({"error": "%s: %s" % (type(e).__name__, str(e)[:300])})


class Worker:
    """One worker process at a time; replaced when a job overruns its time."""

    def __init__(self, service):
        self.service = service
        self.ctx = mp.get_context("spawn")
        self.proc = None
        self.conn = None

    def start(self):
        self.stop()
        parent, child = self.ctx.Pipe()
        self.proc = self.ctx.Process(target=worker_main, args=(child, self.service), daemon=True)
        self.proc.start()
        child.close()
        self.conn = parent

    def stop(self):
        if self.proc is not None and self.proc.is_alive():
            self.proc.kill()
            self.proc.join(5)
        self.proc = None

    def call(self, job, limit_s):
        if self.proc is None or not self.proc.is_alive():
            self.start()
        try:
            self.conn.send(job)
            if self.conn.poll(limit_s):
                return self.conn.recv()
        except (EOFError, OSError):  # the worker died (out of memory, killed): a fresh one next time
            self.start()
            return {"error": "worker", "code": 500}
        self.start()  # overrun: the computation is cut off, a fresh worker waits for the next request
        return {"error": "timeout", "code": 504}


# ---------------------------------------------------------------- map areas in the background

class Areas:
    """Fetches missing map areas (the app's cells) in a background thread; `state()` says how far it is."""

    def __init__(self):
        self.lock = threading.Lock()
        self.busy = set()
        self.failed = {}

    def want(self, cells):
        with self.lock:
            new = [c for c in cells if c not in self.busy]
            self.busy.update(new)
        if new:
            threading.Thread(target=self._fetch, args=(new,), daemon=True).start()

    def _fetch(self, cells):
        sys.path.insert(0, POC)
        import cells as CE
        left = list(cells)
        asked = False
        t_end = time.time() + 30 * 60
        try:
            while left and time.time() < t_end:
                try:
                    st = {c.get("id", "").replace("-", "/"): c for c in CE.status(left).get("cells", [])}
                except Exception as e:  # expected: no network for a moment – try again
                    log("areas status failed: %s" % type(e).__name__)
                    time.sleep(10)
                    continue
                for c in list(left):
                    s = st.get("10/%d/%d" % c, {})
                    if s.get("state") == "ready":
                        try:
                            CE.get(c[0], c[1], log=lambda *_: None)
                            left.remove(c)
                            log("area 10-%d-%d fetched" % c)
                        except BaseException as e:  # SystemExit from cells.py (checksum) included: keep serving
                            log("area 10-%d-%d fetch failed: %s" % (c[0], c[1], type(e).__name__))
                            self.failed[c] = time.time()
                            left.remove(c)
                if left and not asked:
                    try:
                        CE.request(left)
                        log("areas build asked: %d" % len(left))
                    except Exception as e:  # expected: the server refuses / is away – reported as still preparing
                        log("areas request failed: %s" % type(e).__name__)
                    asked = True
                if left:
                    time.sleep(8)
        finally:
            with self.lock:
                self.busy.difference_update(cells)


# ---------------------------------------------------------------- the server

class State:
    def __init__(self, a):
        self.args = a
        self.token = token()
        self.worker = Worker("http://127.0.0.1:%d" % a.service_port)
        self.areas = Areas()
        self.compute = threading.Lock()
        self.waiting = 0
        self.wlock = threading.Lock()
        self.hits = {}

    def rate_ok(self, who):
        now = time.time()
        with self.wlock:
            h = [t for t in self.hits.get(who, []) if now - t < self.args.rate_window]
            if len(h) >= self.args.rate_max:
                self.hits[who] = h
                return False
            h.append(now)
            self.hits[who] = h
            return True


ST = None


class H(BaseHTTPRequestHandler):
    server_version = "onpath-suggest"
    sys_version = ""

    def log_message(self, *a):
        pass

    def cors(self):
        o = self.headers.get("Origin")
        if o and (o in ALLOWED_ORIGINS or any(o == p or o.startswith(p + ":") for p in LOCAL_ORIGIN)):
            self.send_header("Access-Control-Allow-Origin", o)
            self.send_header("Vary", "Origin")
            self.send_header("Access-Control-Allow-Headers", "X-OnPath-Token, Content-Type")
            self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            self.send_header("Access-Control-Max-Age", "600")

    def send(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.cors()
        self.end_headers()
        self.wfile.write(body)

    def path_tail(self):
        p = self.path.split("?", 1)[0]
        for t in ("/v1/ping", "/v1/suggest"):
            if p == t or p.endswith(t):  # behind the test planner's same-origin path the call arrives with a prefix
                return t
        return None

    def authed(self):
        got = self.headers.get("X-OnPath-Token") or ""
        return bool(got) and hmac.compare_digest(got.encode(), ST.token.encode())

    def do_OPTIONS(self):
        self.send_response(204)
        self.cors()
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        if self.path_tail() != "/v1/ping":
            return self.send({"error": "not found"}, 404)
        if not self.authed():
            log("ping 401")
            return self.send({"error": "token"}, 401)
        self.send({"ok": True, "busy": ST.compute.locked()})  # answered at once, also while a computation runs

    def do_POST(self):
        if self.path_tail() != "/v1/suggest":
            return self.send({"error": "not found"}, 404)
        if not self.authed():
            log("suggest 401")
            return self.send({"error": "token"}, 401)
        try:
            n = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            n = -1
        if n < 0 or n > ST.args.max_body:
            log("suggest 413")
            return self.send({"error": "too large"}, 413)
        if not ST.rate_ok(self.client_address[0]):
            log("suggest 429 rate")
            return self.send({"error": "rate", "message": "Zu viele Anfragen – kurz warten."}, 429)
        try:
            w = clean_wish(json.loads(self.rfile.read(n) or b"{}"))
        except (ValueError, TypeError) as e:
            return self.send({"error": "wish", "message": str(e)[:120]}, 400)
        where = "%.2f,%.2f" % (w["start"][1], w["start"][0])  # ~1 km: enough to see the region, not the doorstep
        with ST.wlock:
            if ST.waiting >= ST.args.queue:
                log("suggest 429 busy %s" % where)
                return self.send({"error": "busy", "message": "Der Rechner rechnet gerade – gleich noch einmal."}, 429)
            ST.waiting += 1
        t0 = time.time()
        try:
            with ST.compute:
                waited = time.time() - t0
                need = ST.worker.call({"op": "need", "wish": w}, 60)
                if "error" in need:
                    log("suggest %s %s need error %s" % (w["activity"], where, need["error"]))
                    return self.send({"error": need["error"]}, need.get("code", 500))
                tile = rd5_name(w["start"][0], w["start"][1])
                if not os.path.isfile(os.path.join(need["segments"], tile + ".rd5")):
                    log("suggest %s %s no routing data %s" % (w["activity"], where, tile))
                    return self.send({"error": "region", "message": "Für diese Gegend liegen auf dem Rechner zu Hause keine "
                                      "Routingdaten (%s)." % tile, "message_en": "The computer at home has no routing "
                                      "data for this region (%s)." % tile}, 200)
                missing = [tuple(c) for c in need["missing"]]
                if missing:
                    ST.areas.want(missing)
                    t_end = time.time() + ST.args.area_wait
                    sys.path.insert(0, POC)
                    import cells as CE
                    while time.time() < t_end and any(not CE.have(*c) and c not in ST.areas.failed for c in missing):
                        time.sleep(1)
                    still = [c for c in missing if not CE.have(*c) and c not in ST.areas.failed]
                    if still:
                        log("suggest %s %s preparing %d of %d areas" % (w["activity"], where, len(still), len(need["need"])))
                        return self.send({"preparing": True, "areas_needed": len(need["need"]), "areas_missing": len(still),
                                          "message": "Die Karte für diese Gegend wird vorbereitet – gleich "
                                                     "noch einmal versuchen (bei einer ganz neuen Gegend kann es ein paar Minuten dauern).",
                                          "message_en": "The map for this region is being prepared – try again in a "
                                                        "moment (a region never used before can take a few minutes)."}, 200)
                r = ST.worker.call({"op": "suggest", "wish": w, "save": not ST.args.no_save}, ST.args.time_limit)
        finally:
            with ST.wlock:
                ST.waiting -= 1
        if "error" in r:
            log("suggest %s %sh %s error %s after %.0fs" % (w["activity"], w["hours"], where, r["error"].split(":")[0], time.time() - t0))
            code = r.pop("code", 500)
            if r["error"] == "timeout":
                r["message"] = "Das hat zu lange gedauert – eine kürzere Tour versuchen."
            return self.send(r, code)
        log("suggest %s %sh %s method=%s loops=%d %.1fs (waited %.1fs)" % (
            w["activity"], w["hours"], where, r.get("method"), len(r["candidates"]), time.time() - t0, waited))
        self.send(r)


def main():
    global ST
    ap = argparse.ArgumentParser(description="Tour suggestions – the service on the computer at home")
    ap.add_argument("--bind", default="127.0.0.1", help="address to listen on (default: this computer only)")
    ap.add_argument("--port", type=int, default=int(os.environ.get("SUGGEST_REMOTE_PORT", 8780)))
    ap.add_argument("--service-port", type=int, default=int(os.environ.get("SUGGEST_REMOTE_SERVICE_PORT", 8781)),
                    help="this service's own BRouter route service (started when not running)")
    ap.add_argument("--time-limit", type=float, default=90.0, help="seconds one computation may take")
    ap.add_argument("--area-wait", type=float, default=25.0, help="seconds a request waits for missing map areas")
    ap.add_argument("--queue", type=int, default=3, help="requests that may wait behind the running one")
    ap.add_argument("--max-body", type=int, default=16384)
    ap.add_argument("--rate-max", type=int, default=30, help="requests per address in --rate-window seconds")
    ap.add_argument("--rate-window", type=float, default=300.0)
    ap.add_argument("--no-save", action="store_true", help="do not keep the runs in ~/onpath-suggest-poc/remote/runs/")
    ap.add_argument("--print-token-path", action="store_true")
    a = ap.parse_args()
    if a.print_token_path:
        token()
        print(TOKEN_FILE)
        return
    ST = State(a)
    ST.worker.start()
    srv = ThreadingHTTPServer((a.bind, a.port), H)
    srv.daemon_threads = True
    log("start bind=%s port=%d service=%d" % (a.bind, a.port, a.service_port))
    print("Tourvorschläge – Dienst: http://%s:%d/v1/   Passwort in %s" % (a.bind, a.port, TOKEN_FILE), flush=True)
    try:
        srv.serve_forever()
    finally:
        ST.worker.stop()


if __name__ == "__main__":
    main()
