#!/usr/bin/env python3
"""Seam check between two side-by-side cells (west, east) – docs/design/map-cells.md §4.

  seam_check.py WEST_DIR EAST_DIR BORDER_LON [--png OUT_PREFIX] [--lat LAT]

WEST_DIR / EAST_DIR hold the cell files (a published <version> folder, or the raw unclipped outputs to compare).
Checks: GeoJSON lines crossing the border (should be 0 after clipping), line ends on the border that have / lack a
partner on the other side, identical features in both cells (drawn twice), POIs in both cells, pmtiles tiles held by
both cells. --png renders the GeoJSON lines and the base map's z14 roads/paths around the border (west blue, east
red) into OUT_PREFIX-lines.png / OUT_PREFIX-base.png. Needs shapely, pmtiles, mapbox-vector-tile, pillow.
"""
import gzip
import json
import math
import os
import sys
from collections import Counter

LINE_FILES = ["paths.geojson", "trails.geojson", "ways.geojson", "lowways.geojson", "routes.geojson",
              "trailnames.geojson"]
TILE_FILES = ["base.pmtiles", "terrain.pmtiles", "contours.pmtiles"]
EPS = 1e-6


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


def report(west, east, border):
    print(f"border lon {border}")
    print("\n# GeoJSON lines")
    print(f"{'file':20} {'west f':>7} {'east f':>7} {'cross W':>7} {'cross E':>7} {'ends W':>6} {'ends E':>6} "
          f"{'paired':>6} {'twice':>6}")
    for name in LINE_FILES:
        fw, fe = load(west, name), load(east, name)
        if fw is None or fe is None:
            continue
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


def render_base(west, east, border, lat, out, z=14):
    import mapbox_vector_tile
    from PIL import Image, ImageDraw
    from pmtiles.reader import MmapSource, Reader
    n = 2 ** z
    tx = int((border + 180) / 360 * n)  # first tile east of the border
    ty = int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n)
    S = 512
    img = Image.new("RGB", (2 * S, 2 * S), "white")
    d = ImageDraw.Draw(img)
    for folder, col, wd in ((west, (30, 90, 220), 7), (east, (220, 40, 40), 3)):
        with open(os.path.join(folder, "base.pmtiles"), "rb") as f:
            r = Reader(MmapSource(f))
            for i, x in enumerate((tx - 1, tx)):
                for j, y in enumerate((ty, ty + 1)):
                    data = r.get(z, x, y)
                    if not data:
                        continue
                    try:
                        data = gzip.decompress(data)
                    except OSError:  # expected: tile not gzipped
                        pass
                    t = mapbox_vector_tile.decode(data, default_options={"y_coord_down": True})
                    for layer in ("transportation", "waterway"):
                        for feat in t.get(layer, {}).get("features", []):
                            g = feat["geometry"]
                            parts = [g["coordinates"]] if g["type"] == "LineString" else (
                                g["coordinates"] if g["type"] == "MultiLineString" else [])
                            ext = t[layer].get("extent", 4096)
                            for ln in parts:
                                pts = [(i * S + c[0] / ext * S, j * S + c[1] / ext * S) for c in ln]
                                if len(pts) > 1:
                                    d.line(pts, fill=col, width=wd)
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
        render_lines(west, east, border, lat, prefix + "-lines.png")
        if os.path.exists(os.path.join(west, "base.pmtiles")):
            render_base(west, east, border, lat, prefix + "-base.png")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
