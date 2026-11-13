"""Tour-suggestions POC – "deep nature": how much large, connected nature lies around a point (owner 2026-11-13:
"durch ein großes, zusammenhängendes Naturschutzgebiet zu gehen ist ein ganz anderes Gefühl als ein kleiner
Grünstreifen").

From our own map areas (base.pmtiles of a cell, zoom 12): the `landcover` polygons that are forest / heath / wetland
and the `park` polygons that are protected nature (nature reserve, national park) are drawn into a grid of ~45 m per
map area (cached beside the cell as nature-v<N>.bin + .json). The measure for a point is then simply the share of
grid squares that are nature within `radius_m` around it:

  a path in the middle of a big forest or reserve  -> ~1.0
  a path along its edge                            -> ~0.5
  a green strip, a park between houses             -> 0.1 – 0.3

So size and depth come out of one number, without joining polygons across tile borders. `name_at` says in which
protected area a point lies (for the card: "2,4 km im Naturschutzgebiet Ohligser Heide").
Knobs: weights.json "nature".
"""
import json
import math
import os
from array import array

import cells as CE
import pmt

VERSION = 3
DLAT = 0.0004
DLON = 0.00064
FOREST, PROTECTED = 1, 2
NATURE_CLASSES = ("wood", "wetland")
NATURE_SUB = ("wood", "forest", "heath", "scrub", "wetland", "bog", "marsh", "swamp")
PROTECTED_CLASSES = ("naturschutzgebiet", "nature_reserve", "national_park", "nationalpark", "naturwaldzelle", "vogelschutzgebiet", "biotop")

_mem = {}


def _fill(grid, w, h, west, north, rings, bit, names=None, idx=0):
    """Even-odd scanline fill of one polygon (all its rings) into the grid: sets `bit`; names[i] = idx where given."""
    rows = {}
    for ring in rings:
        for (x1, y1), (x2, y2) in zip(ring, ring[1:]):
            if y1 == y2:
                continue
            ra, rb = (north - y1) / DLAT - 0.5, (north - y2) / DLAT - 0.5
            lo, hi = (ra, rb) if ra < rb else (rb, ra)
            r0, r1 = max(0, int(math.ceil(lo))), min(h - 1, int(math.floor(hi)))
            for r in range(r0, r1 + 1):
                yc = north - (r + 0.5) * DLAT
                rows.setdefault(r, []).append(x1 + (yc - y1) * (x2 - x1) / (y2 - y1))
    for r, xs in rows.items():
        xs.sort()
        for a, b in zip(xs[0::2], xs[1::2]):
            c0 = max(0, int(math.ceil((a - west) / DLON - 0.5)))
            c1 = min(w - 1, int(math.floor((b - west) / DLON - 0.5)))
            base = r * w
            for c in range(c0, c1 + 1):
                grid[base + c] |= bit
                if names is not None:
                    names[base + c] = idx


def build(x, y):
    d = CE.folder(x, y)
    west, south, east, north = CE.cell_bbox(x, y)
    w, h = int(math.ceil((east - west) / DLON)), int(math.ceil((north - south) / DLAT))
    grid, names = bytearray(w * h), bytearray(w * h)
    labels = [""]
    ba = pmt.PMTiles(os.path.join(d, "base.pmtiles"))
    for z, tx, ty in pmt.tiles_of_cell(x, y, 12):
        raw = ba.tile(z, tx, ty)
        if not raw:
            continue
        L = pmt.layers(raw, {"landcover", "park"}, z, tx, ty)
        for f in L.get("landcover", []):
            t = f["tags"]
            if f["type"] == 3 and (t.get("class") in NATURE_CLASSES or t.get("subclass") in NATURE_SUB):
                _fill(grid, w, h, west, north, f["parts"], FOREST)
        for f in L.get("park", []):
            t = f["tags"]
            if f["type"] != 3 or t.get("class") not in PROTECTED_CLASSES:
                continue
            nm = t.get("name") or t.get("name:de") or ""
            if nm and nm not in labels and len(labels) < 255:
                labels.append(nm)
            _fill(grid, w, h, west, north, f["parts"], PROTECTED, names, labels.index(nm) if nm in labels else 0)
    with open(os.path.join(d, "nature-v%d.bin" % VERSION), "wb") as f:
        f.write(bytes(grid) + bytes(names))
    with open(os.path.join(d, "nature-v%d.json" % VERSION), "w", encoding="utf-8") as f:
        json.dump({"w": w, "h": h, "west": west, "north": north, "labels": labels}, f, ensure_ascii=False)


def cell(x, y):
    """The grid of one map area, with a summed-area table for fast "share within a radius"; None when the area's
    files are not there (the measure is then simply 0 – never an error)."""
    if (x, y) in _mem:
        return _mem[(x, y)]
    d = CE.folder(x, y)
    meta = os.path.join(d, "nature-v%d.json" % VERSION)
    try:
        if not os.path.isfile(meta):
            if not os.path.isfile(os.path.join(d, "base.pmtiles")):
                _mem[(x, y)] = None
                return None
            build(x, y)
        with open(meta, encoding="utf-8") as f:
            m = json.load(f)
        with open(os.path.join(d, "nature-v%d.bin" % VERSION), "rb") as f:
            raw = f.read()
    except Exception:  # expected: a broken / half-written cache – the measure is 0 for this area
        _mem[(x, y)] = None
        return None
    w, h = m["w"], m["h"]
    grid, names = raw[:w * h], raw[w * h:]
    sat = array("I", bytes(4 * (w + 1) * (h + 1)))
    W1 = w + 1
    for r in range(h):
        run = 0
        base, up = (r + 1) * W1, r * W1
        row = grid[r * w:(r + 1) * w]
        for c in range(w):
            if row[c]:
                run += 1
            sat[base + c + 1] = sat[up + c + 1] + run
    m.update({"grid": grid, "names": names, "sat": sat})
    _mem[(x, y)] = m
    return m


def depth(p, radius_m=300.0):
    """Share of nature (forest / heath / wetland / protected area) within radius_m around p = [lon, lat], 0..1."""
    m = cell(*CE.cell_of(p[0], p[1]))
    if not m:
        return 0.0
    w, h = m["w"], m["h"]
    c, r = int((p[0] - m["west"]) / DLON), int((m["north"] - p[1]) / DLAT)
    if not (0 <= c < w and 0 <= r < h):
        return 0.0
    k = max(1, int(radius_m / 44.5))
    c0, c1, r0, r1 = max(0, c - k), min(w, c + k + 1), max(0, r - k), min(h, r + k + 1)
    W1 = w + 1
    s = m["sat"]
    n = s[r1 * W1 + c1] - s[r0 * W1 + c1] - s[r1 * W1 + c0] + s[r0 * W1 + c0]
    return n / float((c1 - c0) * (r1 - r0))


def name_at(p):
    """Name of the protected area p lies in ('' = none / unnamed)."""
    m = cell(*CE.cell_of(p[0], p[1]))
    if not m:
        return ""
    c, r = int((p[0] - m["west"]) / DLON), int((m["north"] - p[1]) / DLAT)
    if not (0 <= c < m["w"] and 0 <= r < m["h"]):
        return ""
    i = r * m["w"] + c
    return m["labels"][m["names"][i]] if m["grid"][i] & PROTECTED else ""


def value(d, N):
    """Depth share -> pleasure 0..1: nothing below `from`, full at `full`."""
    return max(0.0, min(1.0, (d - N["from"]) / (N["full"] - N["from"])))


if __name__ == "__main__":
    import sys
    import time
    for a in sys.argv[1:]:
        lon, lat = [float(v) for v in a.split(",")]
        t = time.time()
        print("%s  Tiefe 300 m: %.2f  1000 m: %.2f  Schutzgebiet: %s  (%.1f s)" % (
            a, depth([lon, lat], 300), depth([lon, lat], 1000), name_at([lon, lat]) or "–", time.time() - t))
