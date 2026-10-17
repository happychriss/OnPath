#!/usr/bin/env python3
"""Build contour-line vector tiles (PMTiles, MVT layer "contour") from the Terrarium terrain PMTiles.

Zoom 12 tiles hold 50 m lines (from the z12 DEM), zoom 13 tiles hold 20 m lines (from the z13 DEM);
MapLibre overzooms z13 for higher zooms. Feature properties: ele (int metres), idx (1 = multiple of 100 m).

Usage: python3 build_contours.py terrain.pmtiles contours.pmtiles minlon minlat maxlon maxlat
Needs: numpy, pillow, contourpy, shapely, mapbox-vector-tile, pmtiles.
"""
import gzip
import io
import math
import sys

import contourpy
import mapbox_vector_tile
import numpy as np
from PIL import Image
from pmtiles.reader import MmapSource, Reader
from pmtiles.tile import Compression, TileType, zxy_to_tileid
from pmtiles.writer import Writer
from shapely.geometry import LineString

LEVELS = {12: 50, 13: 20}
EXTENT = 4096
BUF = 8  # DEM pixels of context around each tile


def tile_range(z, minlon, minlat, maxlon, maxlat):
    def xy(lon, lat):
        n = 2 ** z
        return (int((lon + 180) / 360 * n),
                int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n))
    x0, y0 = xy(minlon, maxlat)
    x1, y1 = xy(maxlon, minlat)
    return x0, y0, x1, y1


def gauss(a, sigma):
    r = int(3 * sigma)
    k = np.exp(-0.5 * (np.arange(-r, r + 1) / sigma) ** 2)
    k /= k.sum()
    p = np.pad(a, r, mode="edge")
    p = np.apply_along_axis(lambda m: np.convolve(m, k, mode="valid"), 0, p)
    p = np.apply_along_axis(lambda m: np.convolve(m, k, mode="valid"), 1, p)
    return p


def main():
    src, dst = sys.argv[1], sys.argv[2]
    bbox = list(map(float, sys.argv[3:7]))
    with open(src, "rb") as f:
        reader = Reader(MmapSource(f))
        out_tiles = []
        for z, step in LEVELS.items():
            x0, y0, x1, y1 = tile_range(z, *bbox)
            # mosaic with one extra tile ring
            W, H = (x1 - x0 + 3) * 256, (y1 - y0 + 3) * 256
            dem = np.full((H, W), np.nan, dtype=np.float32)
            for x in range(x0 - 1, x1 + 2):
                for y in range(y0 - 1, y1 + 2):
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
