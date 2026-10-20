#!/usr/bin/env python3
"""Build contour-line vector tiles (PMTiles, MVT layer "contour") from Terrarium terrain tiles.

Zoom 12 tiles hold 50 m lines (from the z12 DEM), zoom 13 tiles hold 20 m lines (from the z13 DEM);
MapLibre overzooms z13 for higher zooms. Feature properties: ele (int metres), idx (1 = multiple of 100 m).

Usage: python3 build_contours.py SOURCE contours.pmtiles minlon minlat maxlon maxlat [--cache DIR]
  SOURCE = terrain.pmtiles (format 1: needs z12 + z13 tiles), or
  SOURCE = aws: the original AWS Terrarium PNGs z12 + z13 (downloaded, kept in --cache DIR – the same folder as
           build_terrain.py --cache). Format 2 ships terrain only up to z12, so its contours come from here and
           keep exactly today's quality (docs/design/map-data-size.md §1).
Tile ranges: bbox max edges exclusive (build_terrain.tile_bounds); one extra tile ring is read so lines run
through to the neighbour.
Needs: numpy, pillow, contourpy, shapely, mapbox-vector-tile, pmtiles.
"""
import contextlib
import gzip
import io
import math
import os
import sys

import contourpy
import mapbox_vector_tile
import numpy as np
from PIL import Image
from pmtiles.reader import MmapSource, Reader
from pmtiles.tile import Compression, TileType, zxy_to_tileid
from pmtiles.writer import Writer
from shapely.geometry import LineString

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_terrain import fetch_all, tile_bounds  # noqa: E402

LEVELS = {12: 50, 13: 20}
EXTENT = 4096
BUF = 8  # DEM pixels of context around each tile


def tile_range(z, minlon, minlat, maxlon, maxlat):
    return tile_bounds(z, minlon, minlat, maxlon, maxlat)


def gauss(a, sigma):
    r = int(3 * sigma)
    k = np.exp(-0.5 * (np.arange(-r, r + 1) / sigma) ** 2)
    k /= k.sum()
    p = np.pad(a, r, mode="edge")
    p = np.apply_along_axis(lambda m: np.convolve(m, k, mode="valid"), 0, p)
    p = np.apply_along_axis(lambda m: np.convolve(m, k, mode="valid"), 1, p)
    return p


class AwsTiles:
    """Original AWS Terrarium PNGs, downloaded per zoom level (cached in --cache DIR when given)."""

    def __init__(self, cache):
        self.cache, self.tiles = cache, {}

    def prefetch(self, tiles):
        self.tiles = fetch_all(tiles, self.cache)

    def get(self, z, x, y):
        return self.tiles.get((z, x, y))


def main():
    args = sys.argv[1:]
    cache = None
    if "--cache" in args:
        i = args.index("--cache")
        cache = args[i + 1]
        del args[i:i + 2]
    src, dst = args[0], args[1]
    bbox = list(map(float, args[2:6]))
    with contextlib.ExitStack() as stack:
        reader = AwsTiles(cache) if src == "aws" else Reader(MmapSource(stack.enter_context(open(src, "rb"))))
        out_tiles = []
        for z, step in LEVELS.items():
            x0, y0, x1, y1 = tile_range(z, *bbox)
            n = 2 ** z
            ring = [(z, x, y) for x in range(x0 - 1, x1 + 2) for y in range(y0 - 1, y1 + 2) if 0 <= x < n and 0 <= y < n]
            if src == "aws":
                reader.prefetch(ring)
            # mosaic with one extra tile ring
            W, H = (x1 - x0 + 3) * 256, (y1 - y0 + 3) * 256
            dem = np.full((H, W), np.nan, dtype=np.float32)
            for _, x, y in ring:
                data = reader.get(z, x, y)
                if not data:
                    continue
                rgb = np.asarray(Image.open(io.BytesIO(data)).convert("RGB")).astype(np.float32)
                e = rgb[..., 0] * 256 + rgb[..., 1] + rgb[..., 2] / 256 - 32768
                dem[(y - y0 + 1) * 256:(y - y0 + 2) * 256, (x - x0 + 1) * 256:(x - x0 + 2) * 256] = e
            fill = np.nanmin(dem) if np.isfinite(dem).any() else 0
            dem = np.where(np.isfinite(dem), dem, fill)
            dem = gauss(dem, 1.2 if z == 12 else 1.6)
            for x in range(x0, x1 + 1):
                for y in range(y0, y1 + 1):
                    px, py = (x - x0 + 1) * 256 - BUF, (y - y0 + 1) * 256 - BUF
                    sub = dem[py:py + 256 + 2 * BUF + 1, px:px + 256 + 2 * BUF + 1]
                    lo, hi = math.ceil(sub.min() / step) * step, math.floor(sub.max() / step) * step
                    gen = contourpy.contour_generator(z=sub, line_type="Separate")
                    feats = []
                    scale = EXTENT / 256
                    for lev in range(int(lo), int(hi) + 1, step):
                        for ln in gen.lines(lev):
                            if len(ln) < 2:
                                continue
                            pts = [((c - BUF) * scale, (r - BUF) * scale) for c, r in ln]
                            g = LineString(pts).simplify(6)
                            if g.length < 40:
                                continue
                            feats.append({"geometry": g.wkt,
                                          "properties": {"ele": lev, "idx": int(lev % 100 == 0)}})
                    if not feats:
                        continue
                    mvt = mapbox_vector_tile.encode([{"name": "contour", "features": feats}],
                                                    default_options={"y_coord_down": True, "extents": EXTENT})
                    out_tiles.append((zxy_to_tileid(z, x, y), gzip.compress(mvt)))
            print("z", z, "tiles so far", len(out_tiles), flush=True)
    out_tiles.sort()
    with open(dst, "wb") as f:
        w = Writer(f)
        for tid, data in out_tiles:
            w.write_tile(tid, data)
        w.finalize(
            {"tile_type": TileType.MVT, "tile_compression": Compression.GZIP,
             "min_zoom": min(LEVELS), "max_zoom": max(LEVELS),
             "min_lon_e7": int(bbox[0] * 1e7), "min_lat_e7": int(bbox[1] * 1e7),
             "max_lon_e7": int(bbox[2] * 1e7), "max_lat_e7": int(bbox[3] * 1e7),
             "center_zoom": 12, "center_lon_e7": int((bbox[0] + bbox[2]) / 2 * 1e7),
             "center_lat_e7": int((bbox[1] + bbox[3]) / 2 * 1e7)},
            {"vector_layers": [{"id": "contour", "fields": {"ele": "Number", "idx": "Number"}}],
             "attribution": "Terrain: Mapzen/AWS Terrarium (SRTM, Copernicus et al.)"},
        )
    print("wrote", len(out_tiles), "tiles")


main()
