#!/usr/bin/env python3
"""Seam check between two side-by-side cells (west, east) – docs/design/map-cells.md §4, §18.

  seam_check.py WEST_DIR EAST_DIR BORDER_LON [--png OUT_PREFIX] [--lat LAT]

WEST_DIR / EAST_DIR hold the cell files (a published <version> folder, or the raw unclipped outputs to compare).
Works for format 1 and 2 (whatever files are there).

Checks:
  format 1  GeoJSON lines crossing the border (should be 0 after clipping), line ends on the border that have / lack
            a partner on the other side, identical features in both cells (drawn twice)
  both      POIs in both cells; pmtiles tiles (z >= 10) held by both cells (must be 0: every tile has one owner)
  format 2  vector tile joins: in the z14 (contours z13) tiles left and right of the border, every line that crosses
            the shared tile edge in the west tile must cross it at the same point in the east tile (same layer,
            same attributes) – overlays.pmtiles (paths, trails, ways, routes), base.pmtiles (transportation,
            waterway), contours.pmtiles; addresses/*.sqlite: house numbers in both cells (must be 0), streets in both
            (expected for streets crossing the border: one row per cell, the app merges them)
--png renders lines around the border (west blue, east red): OUT_PREFIX-lines.png (format 1 GeoJSON),
OUT_PREFIX-base.png (base z14 roads / paths), OUT_PREFIX-overlays.png (format 2 overlay z14 paths / trails / routes).
Needs shapely, pmtiles, mapbox-vector-tile, pillow.
"""
import glob
import gzip
import json
import math
import os
import sqlite3
import sys
from collections import Counter

LINE_FILES = ["paths.geojson", "trails.geojson", "ways.geojson", "lowways.geojson", "routes.geojson",
              "trailnames.geojson"]
TILE_FILES = ["base.pmtiles", "terrain.pmtiles", "contours.pmtiles", "overlays.pmtiles"]
# format 2 joins: file -> (zoom, layers)
JOIN_CHECKS = {"overlays.pmtiles": (14, ["paths", "trails", "ways", "routes"]),
               "base.pmtiles": (14, ["transportation", "waterway"]),
               "contours.pmtiles": (13, ["contour"])}
EPS = 1e-6
TOL = 3  # tile units (extent 4096) for "same crossing point": ~1 m at z14 here


def load(d, name):
    p = os.path.join(d, name)
    if not os.path.exists(p):
        return None
    with open(p) as f:
        return json.load(f)["features"]


def lines_of(feat):
    g = feat["geometry"]
    if g["type"] == "LineString":
        return [g["coordinates"]]
    if g["type"] == "MultiLineString":
        return g["coordinates"]
    return []


def line_stats(feats, border):
    crossing, ends = 0, []
    for f in feats:
        for ln in lines_of(f):
            xs = [c[0] for c in ln]
            if min(xs) < border - EPS and max(xs) > border + EPS:
                crossing += 1
            for c in (ln[0], ln[-1]):
                if abs(c[0] - border) < EPS:
                    ends.append(round(c[1], 6))
    return crossing, Counter(ends)


def key(f):
    return json.dumps(f["geometry"]["coordinates"]) + json.dumps(f.get("properties", {}), sort_keys=True)


def tile_set(path):
    from pmtiles.reader import MmapSource, all_tiles
    with open(path, "rb") as f:
        return {zxy: bytes(d) for zxy, d in all_tiles(MmapSource(f)) if zxy[0] >= 10}


def decode_tile(data):
    import mapbox_vector_tile
    try:
        data = gzip.decompress(data)
    except OSError:  # expected: tile not gzipped
        pass
    return mapbox_vector_tile.decode(data, default_options={"y_coord_down": True})


def tile_lines(feat):
    g = feat["geometry"]
    if g["type"] == "LineString":
        return [g["coordinates"]]
    if g["type"] == "MultiLineString":
        return g["coordinates"]
    return []


def crossings(t, layer, edge):
    """(y, props) where the tile's lines cross the vertical line x = edge (tile units)."""
    out = []
    lay = t.get(layer)
    if not lay:
        return out
    for feat in lay["features"]:
        props = json.dumps(feat.get("properties", {}), sort_keys=True)
        for ln in tile_lines(feat):
            for (x0, y0), (x1, y1) in zip(ln, ln[1:]):
                if x0 < edge <= x1 or x1 < edge <= x0:  # half-open: a vertex on the edge counts once
                    y = y0 + (y1 - y0) * (edge - x0) / (x1 - x0)
                    out.append((y, props))
    return out


def vt_joins(west_file, east_file, border, z, layers):
    """Compare line crossings of the shared tile edge between the west cell's tiles (x-1) and the east cell's (x)."""
    from pmtiles.reader import MmapSource, Reader
    n = 2 ** z
    bx = round((border + 180) / 360 * n)
    res = {}
    with open(west_file, "rb") as fw, open(east_file, "rb") as fe:
        rw, re_ = Reader(MmapSource(fw)), Reader(MmapSource(fe))
        hw = rw.header()
        y_top = int((1 - math.asinh(math.tan(math.radians(hw["max_lat_e7"] / 1e7))) / math.pi) / 2 * n)
        y_bot = int((1 - math.asinh(math.tan(math.radians(hw["min_lat_e7"] / 1e7))) / math.pi) / 2 * n)
        for ty in range(y_top, y_bot + 1):
            dw, de = rw.get(z, bx - 1, ty), re_.get(z, bx, ty)
            if not dw or not de:
                continue
            tw, te = decode_tile(dw), decode_tile(de)
            for layer in layers:
                ext_w = (tw.get(layer) or {}).get("extent", 4096)
                cw = crossings(tw, layer, ext_w)
                ce = crossings(te, layer, 0)
                r = res.setdefault(layer, Counter())
                r["west"] += len(cw)
                r["east"] += len(ce)
                used = set()
                for y, p in cw:
                    best = None
                    for i, (y2, p2) in enumerate(ce):
                        if i not in used and abs(y - y2) <= TOL and (best is None or abs(y - y2) < abs(y - ce[best][0])):
                            best = i
                    if best is None:
                        r["unmatched_west"] += 1
                        continue
                    used.add(best)
                    r["joined"] += 1
                    if ce[best][1] != p:
                        r["attr_differs"] += 1
                r["unmatched_east"] += len(ce) - len(used)
    return res


def address_check(west, east):
    def rows(d, sql):
        out = []
        for p in glob.glob(os.path.join(d, "addresses", "*.sqlite")):
            db = sqlite3.connect(f"file:{p}?mode=ro", uri=True)
            out += db.execute(sql).fetchall()
            db.close()
        return out
    q_house = "SELECT h.num, h.lat, h.lon FROM house h"
    q_street = "SELECT name, town FROM street WHERE p = 0"
    hw, he = rows(west, q_house), rows(east, q_house)
    if not hw and not he:
        return
    sw, se = Counter(r[0] for r in rows(west, q_street)), Counter(r[0] for r in rows(east, q_street))
    print(f"\n# addresses: houses west {len(hw)}, east {len(he)}, in both cells {len(set(hw) & set(he))}; "
          f"streets west {sum(sw.values())}, east {sum(se.values())}, same name in both {len(set(sw) & set(se))} "
          f"(streets crossing the border have one row per cell)")


def report(west, east, border):
    print(f"border lon {border}")
    head = False
    for name in LINE_FILES:
        fw, fe = load(west, name), load(east, name)
        if fw is None or fe is None:
            continue
        if not head:
            print("\n# GeoJSON lines")
            print(f"{'file':20} {'west f':>7} {'east f':>7} {'cross W':>7} {'cross E':>7} {'ends W':>6} "
                  f"{'ends E':>6} {'paired':>6} {'twice':>6}")
            head = True
        cw, ew = line_stats(fw, border)
        ce, ee = line_stats(fe, border)
        paired = sum((ew & ee).values())
        twice = len({key(f) for f in fw} & {key(f) for f in fe})
        print(f"{name:20} {len(fw):7} {len(fe):7} {cw:7} {ce:7} {sum(ew.values()):6} {sum(ee.values()):6} "
              f"{paired:6} {twice:6}")
    pw, pe = load(west, "pois.geojson"), load(east, "pois.geojson")
    if pw is not None and pe is not None:
        iw = Counter(f["properties"].get("id") for f in pw)
        ie = Counter(f["properties"].get("id") for f in pe)
        both = set(iw) & set(ie)
        merged_both = sum(1 for f in pw if f["properties"].get("merged") and f["properties"]["id"] in both)
        on = sum(1 for f in pw + pe if abs(f["geometry"]["coordinates"][0] - border) < EPS)
        print(f"\n# POIs: west {len(pw)}, east {len(pe)}, ids in both cells {len(both)} "
              f"(of them merged bus stops {merged_both}), on the border line {on}")
    print("\n# pmtiles (z >= 10)")
    for name in TILE_FILES:
        a, b = os.path.join(west, name), os.path.join(east, name)
        if not (os.path.exists(a) and os.path.exists(b)):
            continue
        tw, te = tile_set(a), tile_set(b)
        both = set(tw) & set(te)
        same = sum(1 for t in both if tw[t] == te[t])
        zs = Counter(t[0] for t in both)
        print(f"{name:17} west {len(tw):6} tiles, east {len(te):6}, in both {len(both):5} "
              f"(identical bytes {same}) by zoom {dict(sorted(zs.items()))}")
    first = True
    for name, (z, layers) in JOIN_CHECKS.items():
        a, b = os.path.join(west, name), os.path.join(east, name)
        if not (os.path.exists(a) and os.path.exists(b)):
            continue
        if first:
            print(f"\n# vector tile joins across the border (crossing points of the shared tile edge, tolerance "
                  f"{TOL}/4096)")
            print(f"{'file / layer':30} {'z':>3} {'west':>6} {'east':>6} {'joined':>6} {'only W':>6} {'only E':>6} "
                  f"{'attr≠':>6}")
            first = False
        for layer, r in vt_joins(a, b, border, z, layers).items():
            print(f"{name + ' / ' + layer:30} {z:3} {r['west']:6} {r['east']:6} {r['joined']:6} "
                  f"{r['unmatched_west']:6} {r['unmatched_east']:6} {r['attr_differs']:6}")
    address_check(west, east)


# ------------------------------------------------------------------------------------------- render
def render_lines(west, east, border, lat, out, half_deg=0.012):
    from PIL import Image, ImageDraw
    W = H = 1400
    k = math.cos(math.radians(lat))
    x0, x1 = border - half_deg / k, border + half_deg / k
    y0, y1 = lat - half_deg, lat + half_deg

    def px(c):
        return ((c[0] - x0) / (x1 - x0) * W, (y1 - c[1]) / (y1 - y0) * H)

    img = Image.new("RGB", (W, H), "white")
    d = ImageDraw.Draw(img)
    d.line([px((border, y0)), px((border, y1))], fill=(170, 170, 170), width=1)
    for folder, col, wd in ((west, (30, 90, 220), 7), (east, (220, 40, 40), 3)):
        for name in LINE_FILES:
            feats = load(folder, name) or []
            for f in feats:
                for ln in lines_of(f):
                    pts = [px(c) for c in ln]
                    if len(pts) > 1 and any(0 <= p[0] <= W and 0 <= p[1] <= H for p in pts):
                        d.line(pts, fill=col, width=wd if name != "routes.geojson" else wd + 4)
    img.save(out)
    print("wrote", out)


def render_tiles(west, east, border, lat, out, file="base.pmtiles", layers=("transportation", "waterway"), z=14):
    """2 x 2 z14 tiles around the border (left column from the west cell, right from the east cell); lines are
    drawn with their tile buffer, so a gap or a kink at the border shows."""
    from PIL import Image, ImageDraw
    from pmtiles.reader import MmapSource, Reader
    n = 2 ** z
    tx = round((border + 180) / 360 * n)  # first tile east of the border
    ty = int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n)
    S = 512
    img = Image.new("RGB", (2 * S, 2 * S), "white")
    d = ImageDraw.Draw(img)
    for folder, col, wd in ((west, (30, 90, 220), 7), (east, (220, 40, 40), 3)):
        with open(os.path.join(folder, file), "rb") as f:
            r = Reader(MmapSource(f))
            for xx in (tx - 1, tx):  # both columns from both cells: a cell has no tiles outside it (empty there)
                i = xx - (tx - 1)
                for j, y in enumerate((ty, ty + 1)):
                    data = r.get(z, xx, y)
                    if not data:
                        continue
                    t = decode_tile(data)
                    for layer in layers:
                        lay = t.get(layer, {})
                        ext = lay.get("extent", 4096)
                        for feat in lay.get("features", []):
                            for ln in tile_lines(feat):
                                pts = [(i * S + c[0] / ext * S, j * S + c[1] / ext * S) for c in ln]
                                if len(pts) > 1:
                                    d.line(pts, fill=col, width=wd + (4 if layer == "routes" else 0))
    d.line([(S, 0), (S, 2 * S)], fill=(170, 170, 170), width=1)
    img.save(out)
    print("wrote", out)


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    west, east, border = argv[0], argv[1], float(argv[2])
    report(west, east, border)
    if "--png" in argv:
        prefix = argv[argv.index("--png") + 1]
        lat = float(argv[argv.index("--lat") + 1]) if "--lat" in argv else None
        if lat is None:  # the latitude with the most line ends on the border
            _, ends = line_stats(load(west, "paths.geojson") or [], border)
            lat = Counter(round(v, 2) for v in ends.elements()).most_common(1)[0][0] if ends else 46.7
        if load(west, "paths.geojson") is not None:
            render_lines(west, east, border, lat, prefix + "-lines.png")
        if os.path.exists(os.path.join(west, "base.pmtiles")):
            render_tiles(west, east, border, lat, prefix + "-base.png")
        if os.path.exists(os.path.join(west, "overlays.pmtiles")):
            render_tiles(west, east, border, lat, prefix + "-overlays.png", "overlays.pmtiles",
                         ("paths", "trails", "routes"))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
