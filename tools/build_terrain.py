#!/usr/bin/env python3
"""Download open terrain tiles (Terrarium encoding, AWS Open Data / Mapzen, built from SRTM,
Copernicus and other open DEMs) for a bounding box and pack them into one PMTiles file.

Usage: python3 build_terrain.py OUT.pmtiles minlon minlat maxlon maxlat [maxzoom]
"""
import math
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor

from pmtiles.tile import Compression, TileType, zxy_to_tileid
from pmtiles.writer import Writer

URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"


def tile_range(z, minlon, minlat, maxlon, maxlat):
    def xy(lon, lat):
        n = 2 ** z
        x = int((lon + 180) / 360 * n)
        y = int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n)
        return x, y
    x0, y0 = xy(minlon, maxlat)
    x1, y1 = xy(maxlon, minlat)
    return [(z, x, y) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1)]


def fetch(t):
    z, x, y = t
    for _ in range(4):
        try:
            with urllib.request.urlopen(URL.format(z=z, x=x, y=y), timeout=60) as r:
                return t, r.read()
        except Exception:
            continue
    raise RuntimeError(f"failed {t}")


def main():
    out = sys.argv[1]
    minlon, minlat, maxlon, maxlat = map(float, sys.argv[2:6])
    maxzoom = int(sys.argv[6]) if len(sys.argv) > 6 else 13
    tiles = []
    for z in range(5, maxzoom + 1):
        tiles += tile_range(z, minlon, minlat, maxlon, maxlat)
    print("tiles:", len(tiles))
    with ThreadPoolExecutor(16) as ex:
        data = dict(ex.map(fetch, tiles))
    with open(out, "wb") as f:
        w = Writer(f)
        for t in sorted(data, key=lambda t: zxy_to_tileid(*t)):
            w.write_tile(zxy_to_tileid(*t), data[t])
        w.finalize(
            {
                "tile_type": TileType.PNG,
                "tile_compression": Compression.NONE,
                "min_zoom": 5,
                "max_zoom": maxzoom,
                "min_lon_e7": int(minlon * 1e7),
                "min_lat_e7": int(minlat * 1e7),
                "max_lon_e7": int(maxlon * 1e7),
                "max_lat_e7": int(maxlat * 1e7),
                "center_zoom": 11,
                "center_lon_e7": int((minlon + maxlon) / 2 * 1e7),
                "center_lat_e7": int((minlat + maxlat) / 2 * 1e7),
            },
            {"name": "terrain", "encoding": "terrarium",
             "attribution": "Terrain: Mapzen / AWS Open Data (SRTM, Copernicus, others)"},
        )
    print("written", out)


main()
