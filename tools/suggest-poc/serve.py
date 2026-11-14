#!/usr/bin/env python3
"""Tour suggestions – the local page.   python3 tools/suggest-poc/serve.py   → http://localhost:8790

Other ports: --port / SUGGEST_PORT; route service: SUGGEST_SERVICE=http://127.0.0.1:<port> (started when it is not
running). settings.json is read again on every request. Runs and comments go to ~/onpath-suggest-poc/ (SUGGEST_DATA).
"""
import argparse
import json
import os
import re
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import named
import suggest as S
from character import wish as make_wish


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send(self, body, ctype="application/json", code=200):
        if not isinstance(body, bytes):
            body = (body if isinstance(body, str) else json.dumps(body, ensure_ascii=False)).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype + "; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        q = {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}
        # behind a path prefix (a preview pane) the calls arrive as <prefix>/places …: the end of the path decides
        tail = re.search(r"/(places|place|known|run/[^/]+)$", u.path)
        what = tail.group(1) if tail else ""
        st = S.load_settings()
        if what == "places":
            return self.send({"places": st["places"], "version": st["version"],
                              "activities": {k: v["label"] for k, v in st["activities"].items() if k != "_about"}})
        if what == "place":
            r = S.find_place(q.get("q", ""), st)
            return self.send({"name": r[0], "lonlat": r[1]} if r else {"error": "Ort nicht gefunden"})
        if what == "known":  # the known signed routes around a point, from our own map areas (the page's overlay)
            try:
                return self.send({"routes": named.known_lines([float(q["lon"]), float(q["lat"])], float(q.get("reach", 12000)),
                                                              q.get("activity", "hike") != "hike")})
            except Exception as e:  # shown as "no overlay" on the page – a POC has no other place for it
                return self.send({"routes": [], "error": "%s: %s" % (type(e).__name__, e)})
        if what.startswith("run/"):
            p = os.path.join(S.DATA, "runs", os.path.basename(what) + ".json")
            if os.path.isfile(p):
                with open(p, "rb") as f:
                    return self.send(f.read())
            return self.send({"error": "Lauf nicht gefunden"}, code=404)
        if u.path.endswith("/favicon.ico"):
            return self.send({"error": "not found"}, code=404)
        with open(os.path.join(S.HERE, "page.html"), "rb") as f:  # any other address shows the page
            return self.send(f.read(), "text/html")

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        try:
            if self.path.endswith("/suggest"):
                st = S.load_settings()
                res = S.suggest(make_wish(body, st), st)
                S.save_run(res)
                return self.send(res)
            if self.path.endswith("/feedback"):
                os.makedirs(S.DATA, exist_ok=True)
                body["at"] = time.strftime("%Y-%m-%dT%H:%M:%S")
                with open(os.path.join(S.DATA, "feedback.jsonl"), "a", encoding="utf-8") as f:
                    f.write(json.dumps(body, ensure_ascii=False) + "\n")
                return self.send({"ok": True})
        except Exception as e:  # shown on the page – a POC has no other place for it
            return self.send({"error": "%s: %s" % (type(e).__name__, e)})
        self.send({"error": "not found"}, code=404)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=int(os.environ.get("SUGGEST_PORT", 8790)))
    a = ap.parse_args()
    S.ensure_service()
    print("Tourvorschläge: http://localhost:%d   – Daten: %s" % (a.port, S.DATA))
    ThreadingHTTPServer(("127.0.0.1", a.port), H).serve_forever()
