"""Tour-suggestions POC – the app's own map areas (z10 cells, docs/design/map-cells.md) on this computer.

Downloads the cells around a point from our map server exactly as the app does (status → manifest → files, sha256
checked) into ~/onpath-suggest-poc/cells/10-x-y/ (outside git). The key is read from ONPATH_MAP_KEY_TEST or
~/.gradle/gradle.properties (`onpath.mapKey.test`) – never printed, never stored anywhere else.

  python3 cells.py status <lon> <lat> [ring]     which cells exist around the point (ring 1 = 3×3)
  python3 cells.py get <lon> <lat> [ring]        download the ready ones (skips files already there)
  python3 cells.py request <lon> <lat> [ring]    ask the server to build missing ones (as the app would)
"""
import hashlib
import json
import math
import os
import re
import sys
import urllib.request

DATA = os.environ.get("SUGGEST_DATA", os.path.expanduser("~/onpath-suggest-poc"))
CELLS = os.path.join(DATA, "cells")
API = os.environ.get("ONPATH_MAP_API", "https://maps-test.free-your-pictures.org/v1")
FORMAT = 2
APP = "0.17.9"
WANT = ("base.pmtiles", "overlays.pmtiles", "pois.geojson")


def _key():
    k = os.environ.get("ONPATH_MAP_KEY_TEST")
    if k:
        return k
    p = os.path.expanduser("~/.gradle/gradle.properties")
    m = re.search(r"^onpath\.mapKey\.test\s*=\s*(\S+)", open(p).read(), re.M) if os.path.isfile(p) else None
    if not m:
        raise SystemExit("no map key (ONPATH_MAP_KEY_TEST or onpath.mapKey.test in ~/.gradle/gradle.properties)")
    return m.group(1)


def _open(url, body=None, extra=None):
    h = {"X-OnPath-Key": _key(), "X-OnPath-App": APP, "User-Agent": "OnPath-Android/" + APP,
         "Accept": "application/json"}
    if body is not None:
        h["Content-Type"] = "application/json"
    h.update(extra or {})
    return urllib.request.urlopen(urllib.request.Request(url, data=body, headers=h), timeout=120)


def cell_of(lon, lat):
    x = int((lon + 180.0) / 360.0 * 1024)
    y = int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * 1024)
    return x, y


def cell_bbox(x, y):
    def lat(yy):
        return math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * yy / 1024.0))))
    return x / 1024.0 * 360 - 180, lat(y + 1), (x + 1) / 1024.0 * 360 - 180, lat(y)


def around(lon, lat, ring=1):
    x, y = cell_of(lon, lat)
    return [(x + dx, y + dy) for dx in range(-ring, ring + 1) for dy in range(-ring, ring + 1)]


def cells_for(lon, lat, reach_m):
    """Cells that a circle of reach_m around the point touches."""
    dlat = reach_m / 111320.0
    dlon = reach_m / (111320.0 * math.cos(math.radians(lat)))
    x0, y0 = cell_of(lon - dlon, lat + dlat)
    x1, y1 = cell_of(lon + dlon, lat - dlat)
    return [(x, y) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1)]


def folder(x, y):
    return os.path.join(CELLS, "10-%d-%d" % (x, y))


def have(x, y):
    return os.path.isfile(os.path.join(folder(x, y), "manifest.json"))


def status(cells):
    ids = ",".join("10/%d/%d" % c for c in cells)
    return json.load(_open("%s/cells/status?ids=%s&format=%d" % (API, ids, FORMAT)))


def request(cells):
    body = json.dumps({"cells": ["10/%d/%d" % c for c in cells], "reason": "point", "formats": [FORMAT]}).encode()
    return json.load(_open(API + "/cells/request", body))


def get(x, y, log=print):
    d = folder(x, y)
    os.makedirs(d, exist_ok=True)
    text = _open("%s/cells/10/%d/%d/manifest?format=%d" % (API, x, y, FORMAT)).read()
    man = json.loads(text)
    for f in man["files"]:
        name = f["name"]
        if name not in WANT:
            continue  # routing data: the POC uses the whole rd5 tile it already has; terrain, addresses: not needed
        path = os.path.join(d, name)
        if os.path.isfile(path) and os.path.getsize(path) == f["size"]:
            continue
        data = _open("%s/files/%s" % (API, f["url"])).read()
        if f.get("sha256") and hashlib.sha256(data).hexdigest() != f["sha256"]:
            raise SystemExit("checksum mismatch " + name)
        with open(path, "wb") as o:
            o.write(data)
        log("  %s %d kB" % (name, len(data) // 1024))
    with open(os.path.join(d, "manifest.json"), "wb") as o:
        o.write(text)
    return d


if __name__ == "__main__":
    cmd, lon, lat = sys.argv[1], float(sys.argv[2]), float(sys.argv[3])
    ring = int(sys.argv[4]) if len(sys.argv) > 4 else 1
    cs = around(lon, lat, ring)
    if cmd == "request":
        st = request(cs)
    else:
        st = status(cs)
    for c in st.get("cells", []):
        print(c.get("id"), c.get("state"), c.get("size"), c.get("version"), c.get("step") or "")
        if cmd == "get" and c.get("state") == "ready":
            z, x, y = c["id"].replace("-", "/").split("/")[-3:]
            get(int(x), int(y))
