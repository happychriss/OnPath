#!/usr/bin/env python3
"""Tour suggestions – the TEST PLANNER served by this computer (owner 2026-11-14, the plain-VPN way).

A VPN into the home network gives only http://<this computer>:<port>; the live planner (https) may not call such an
address. So this computer serves a copy of the planner itself – the branch build with the tour suggestions – and
everything the page needs on ONE address:

    /                     the built planner (a copy of web/app/dist, see testplanner_install.sh)
    /suggest-remote/…     -> the suggestion service on this computer (remote_server.py, 127.0.0.1:8780); the
                             password still has to be in the request (the page's debug setting)
    /segments/<tile>.rd5  routing data from this computer's own files (HTTP ranges); a tile it does not have is
                             passed on to the live planner's host
    /data/… /maps/… /overview/…   passed on to the live planner's host (map files, map areas on demand) – read only,
                             plus the two map-area calls the page makes (request / status)
    /sync/…               not offered here (404): the test planner is not paired with a phone; saved tours of this
                             address are separate from the live planner's

Listens on 127.0.0.1 unless started with --bind <address>. Limits by nature: on an http page the browser gives no
"Mein Standort", and nothing here touches plan.onpath.earth's own pages.
"""
import argparse
import mimetypes
import os
import re
import sys
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DATA = os.environ.get("SUGGEST_DATA", os.path.expanduser("~/onpath-suggest-poc"))
LIVE = "https://plan.onpath.earth"
PASS_GET = ("/data/", "/maps/", "/overview/")
HOP = {"connection", "keep-alive", "transfer-encoding", "content-encoding", "content-length", "host", "accept-encoding"}
A = None


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "onpath-testplanner"
    sys_version = ""

    def log_message(self, *a):
        pass

    def plain(self, code, text=b""):
        self.send_response(code)
        self.send_header("Content-Length", str(len(text)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(text)

    def file(self, path, cache="no-cache"):
        try:
            size = os.path.getsize(path)
            f = open(path, "rb")
        except OSError:
            return self.plain(404)
        with f:
            ctype = "application/wasm" if path.endswith(".wasm") else mimetypes.guess_type(path)[0] or "application/octet-stream"
            m = re.match(r"bytes=(\d+)-(\d*)$", self.headers.get("Range") or "")
            start, end = 0, size - 1
            if m:
                start = int(m.group(1))
                end = min(int(m.group(2)), size - 1) if m.group(2) else size - 1
                if start > end:
                    self.send_response(416)
                    self.send_header("Content-Range", "bytes */%d" % size)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                self.send_response(206)
                self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
            else:
                self.send_response(200)
            self.send_header("Content-Type", ctype)
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", str(end - start + 1))
            self.send_header("Cache-Control", cache)
            self.end_headers()
            if self.command == "HEAD":
                return
            f.seek(start)
            left = end - start + 1
            while left > 0:
                chunk = f.read(min(1 << 16, left))
                if not chunk:
                    break
                self.wfile.write(chunk)
                left -= len(chunk)

    def forward(self, url, with_body=False, keep=("range", "content-type", "accept", "if-none-match", "x-onpath-token")):
        """Passes the request on and the answer back. Only the listed request headers travel."""
        body = None
        if with_body:
            n = int(self.headers.get("Content-Length") or 0)
            if n > 65536:
                return self.plain(413)
            body = self.rfile.read(n) if n else b""
        req = urllib.request.Request(url, data=body, method=self.command)
        for k in keep:
            v = self.headers.get(k)
            if v:
                req.add_header(k, v)
        req.add_header("User-Agent", "onpath-testplanner")
        try:
            r = urllib.request.urlopen(req, timeout=180)
            code, heads, data = r.status, r.headers, r.read()
        except urllib.error.HTTPError as e:
            code, heads, data = e.code, e.headers, e.read()
        except Exception:  # expected: the other side is away – the page shows its own calm message
            return self.plain(502)
        self.send_response(code)
        for k, v in heads.items():
            if k.lower() not in HOP and not k.lower().startswith(("cf-", "report-", "nel", "alt-svc", "server", "set-cookie", "access-control-")):
                self.send_header(k, v)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)

    def route(self):
        p = self.path.split("?", 1)[0]
        q = self.path[len(p):]
        if p.startswith("/suggest-remote/"):
            return self.forward("http://127.0.0.1:%d/%s%s" % (A.service_port, p[len("/suggest-remote/"):], q), with_body=self.command == "POST")
        if self.command == "POST":
            if p in ("/maps/v1/cells/request",):
                return self.forward(LIVE + p + q, with_body=True)
            return self.plain(404)
        if p.startswith("/segments/"):
            name = os.path.basename(p)
            local = os.path.join(A.segments, name)
            if re.fullmatch(r"[EW]\d+_[NS]\d+\.rd5", name) and os.path.isfile(local):
                return self.file(local, "public, max-age=3600")
            return self.forward(LIVE + p + q)
        if p.startswith("/sync/"):
            return self.plain(404)
        if p.startswith(PASS_GET):
            return self.forward(LIVE + p + q)
        rel = os.path.normpath("/" + (p if p != "/" else "/index.html")).lstrip("/")
        full = os.path.join(A.dist, rel)
        if not os.path.abspath(full).startswith(os.path.abspath(A.dist) + os.sep) or not os.path.isfile(full):
            return self.plain(404)
        return self.file(full, "public, max-age=31536000, immutable" if rel.startswith("assets/") else "no-cache")

    do_GET = do_HEAD = do_POST = route


def main():
    global A
    ap = argparse.ArgumentParser(description="The test planner served by this computer")
    ap.add_argument("--bind", default="127.0.0.1", help="address to listen on (default: this computer only)")
    ap.add_argument("--port", type=int, default=8785)
    ap.add_argument("--dist", default=os.path.join(DATA, "remote", "testplanner", "dist"), help="the built planner (a copy of web/app/dist)")
    ap.add_argument("--segments", default=os.environ.get("ONPATH_SEGMENTS", os.path.expanduser("~/onpath-routing/segments")))
    ap.add_argument("--service-port", type=int, default=8780, help="the suggestion service on this computer")
    A = ap.parse_args()
    if not os.path.isfile(os.path.join(A.dist, "index.html")):
        sys.exit("no built planner in %s – run testplanner_install.sh from the planner branch's checkout" % A.dist)
    srv = ThreadingHTTPServer((A.bind, A.port), H)
    srv.daemon_threads = True
    print("Test-Planer: http://%s:%d/   (Planer-Stand: %s)" % (A.bind, A.port, A.dist), flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
