#!/usr/bin/env python3
"""Tour-suggestions POC – the local page.   python3 tools/suggest-poc/serve.py   → http://localhost:8790

Starts the BRouter route service when it is not running (service/start.sh, port 8791). weights.json is read again on
every request. Runs and the owner's verdicts go to ~/onpath-suggest-poc/ (runs/*.json, feedback.jsonl; SUGGEST_DATA).
"""
import argparse
import json
import os
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import suggest as S


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
        q = urllib.parse.parse_qs(u.query)
        if u.path == "/":
            with open(os.path.join(S.HERE, "page.html"), "rb") as f:
                return self.send(f.read(), "text/html")
        if u.path == "/ping":
            return self.send({"ok": True})
        if u.path == "/places":
            W = S.load_weights()
            return self.send({"places": W["places"], "weights_version": W["version"]})
        if u.path == "/place":
            r = S.find_place(q.get("q", [""])[0], S.load_weights())
            return self.send({"name": r[0], "lonlat": r[1]} if r else {"error": "Ort nicht gefunden"})
        if u.path.startswith("/run/"):
            p = os.path.join(S.DATA, "runs", os.path.basename(u.path) + ".json")
            if os.path.isfile(p):
                with open(p, "rb") as f:
                    return self.send(f.read())
            return self.send({"error": "Lauf nicht gefunden"}, code=404)
        self.send({"error": "not found"}, code=404)

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        try:
            if self.path == "/suggest":
                w = body
                wish = S.make_wish(w["start"], w.get("start_name", ""), w["hours"], w["pace"], w["t_level"], w["effort"],
                                   w["forest"], w["quiet"], w["water"], w["signed"], w["natural"], w["seed"])
                res = S.suggest(wish)
                S.save_run(res)
                return self.send(res)
            if self.path == "/feedback":
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
    ap.add_argument("--port", type=int, default=8790)
    a = ap.parse_args()
    S.ensure_service()
    print("Tourvorschläge (POC): http://localhost:%d   – Daten: %s" % (a.port, S.DATA))
    ThreadingHTTPServer(("127.0.0.1", a.port), H).serve_forever()
