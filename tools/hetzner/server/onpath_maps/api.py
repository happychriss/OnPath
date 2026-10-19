"""Small JSON API for the web page (systemd: onpath-api.service). Listens on 127.0.0.1 only; Caddy in front does
HTTPS and the password. Stdlib only.

  GET  /api/regions            all regions (newest request first)
  POST /api/build              {"lat":46.54,"lon":10.99,"km":100} → region (reused when one already covers it)
  POST /api/rebuild/<name>     build a region again now (fresh OSM data)
  GET  /api/log/<name>         last lines of the build log (plain text)
  GET  /api/health             "ok"
"""
import json
import os
import re
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import regions

HOME = os.environ.get("ONPATH_HOME", "/srv/onpath")
store = regions.Store(os.path.join(HOME, "state"))
MAX_QUEUED = 5
NAME_RE = re.compile(r"^[a-z0-9-]{3,64}$")


def public(r):
    keys = ("name", "lat", "lon", "km", "bbox", "status", "step", "error", "url", "zip_bytes", "osm_date",
            "built_at", "queued_at", "started", "last_requested")
    return {k: r.get(k) for k in keys}


class Handler(BaseHTTPRequestHandler):
    server_version = "onpath-maps"

    def log_message(self, fmt, *args):  # journal gets one short line per request
        print("%s %s" % (self.command, self.path), flush=True)

    def send(self, code, body, ctype="application/json"):
        data = body if isinstance(body, bytes) else (json.dumps(body) if ctype == "application/json" else body).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype + "; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n > 10_000:
            raise ValueError("Anfrage zu groß.")
        return json.loads(self.rfile.read(n) or b"{}")

    def do_GET(self):
        if self.path == "/api/health":
            return self.send(200, "ok", "text/plain")
        if self.path == "/api/regions":
            rs = sorted(store.all(), key=lambda r: r.get("last_requested", 0), reverse=True)
            return self.send(200, {"regions": [public(r) for r in rs], "now": time.time()})
        m = re.match(r"^/api/log/([a-z0-9-]+)$", self.path)
        if m and NAME_RE.match(m.group(1)):
            try:
                with open(os.path.join(HOME, "logs", m.group(1) + ".log"), errors="replace") as f:
                    return self.send(200, "".join(f.readlines()[-80:]), "text/plain")
            except OSError:
                return self.send(404, "Kein Log.", "text/plain")
        self.send(404, {"error": "Nicht gefunden."})

    def do_POST(self):
        try:
            if self.path == "/api/build":
                b = self.body()
                queued = sum(1 for r in store.all() if r.get("status") in ("queued", "building"))
                r, reused = store.request(b.get("lat"), b.get("lon"), b.get("km", 100))
                if not reused and queued >= MAX_QUEUED:
                    store.delete(r["name"])
                    return self.send(429, {"error": f"Schon {queued} Karten in Arbeit – bitte später noch einmal."})
                return self.send(200, {"region": public(r), "reused": reused})
            m = re.match(r"^/api/rebuild/([a-z0-9-]+)$", self.path)
            if m and NAME_RE.match(m.group(1)):
                r = store.get(m.group(1))
                if not r:
                    return self.send(404, {"error": "Unbekannte Karte."})
                if r.get("status") not in ("queued", "building"):
                    r = store.update(r["name"], status="queued", step="wartet", queued_at=time.time(),
                                     last_requested=time.time())
                return self.send(200, {"region": public(r)})
            self.send(404, {"error": "Nicht gefunden."})
        except (ValueError, TypeError) as e:
            self.send(400, {"error": str(e) or "Ungültige Anfrage."})


def main():
    port = int(os.environ.get("ONPATH_API_PORT", "8090"))
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()


if __name__ == "__main__":
    main()
