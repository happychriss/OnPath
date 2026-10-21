#!/usr/bin/env python3
"""Download open terrain tiles (Terrarium encoding, AWS Open Data / Mapzen, built from SRTM,
Copernicus and other open DEMs) for a bounding box and pack them into one PMTiles file.

Usage:
  python3 build_terrain.py OUT.pmtiles minlon minlat maxlon maxlat [maxzoom]      format 1: AWS PNG as is, z5-13
  python3 build_terrain.py OUT.pmtiles minlon minlat maxlon maxlat --format 2     WebP lossless, 0.25 m, z5-12
  python3 build_terrain.py OUT.pmtiles --cell 10/543/361 --format 2               only the cell's own z10-12 tiles

Options:
  --format 1|2     1 (default) = today's PNG z5-13; 2 = --webp --round 0.25 --maxzoom 12 (docs/design/map-data-size.md)
  --minzoom N      lowest zoom (default 5; with --cell at least 10, the cell's own tiles only)
  --maxzoom N      highest zoom (default 13, format 2: 12); the positional maxzoom does the same
  --webp           store tiles as WebP lossless (MapLibre Native Android decodes them with BitmapFactory)
  --round M        round heights to M metres (e.g. 0.25) – the Terrarium blue channel then compresses well
  --cell Z/X/Y     tiles of one map cell (docs/design/map-cells.md §2): every tile z >= max(10, minzoom) inside it
  --floor M        heights below M metres become M (e.g. 0: flat sea floor – the water fill covers it anyway, and
                   flat tiles compress far better; the world overview z0-4 uses it)
  --cache DIR      keep the downloaded AWS PNGs in DIR/z/x/y.png (build_contours.py --cache reads the same folder)

Tile ranges treat the bbox edges as exclusive: a bbox whose east/south edge lies exactly on a tile edge does not
take the next tile column/row (that bug made a z10 cell carry 9 x 9 instead of 8 x 8 z13 tiles).
"""
import argparse
import io
import math
import os
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"
EPS = 1e-9  # in tile units: a bbox edge closer than this to a tile edge counts as on it


def tile_xy(z, lon, lat):
    """Fractional tile coordinates of a point."""
    n = 2 ** z
    lat = max(-85.0511287798, min(85.0511287798, lat))
    return ((lon + 180) / 360 * n,
            (1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n)


def tile_bounds(z, minlon, minlat, maxlon, maxlat):
    """(x0, y0, x1, y1) inclusive tile range covering the bbox; max edges exclusive (a bbox ending exactly on a
    tile edge does not take the next tile)."""
    n = 2 ** z
    fx0, fy0 = tile_xy(z, minlon, maxlat)
    fx1, fy1 = tile_xy(z, maxlon, minlat)
    x0, y0 = math.floor(fx0 + EPS), math.floor(fy0 + EPS)
    x1, y1 = math.ceil(fx1 - EPS) - 1, math.ceil(fy1 - EPS) - 1
    x0, y0 = min(max(x0, 0), n - 1), min(max(y0, 0), n - 1)
    x1, y1 = min(max(x1, x0), n - 1), min(max(y1, y0), n - 1)
    return x0, y0, x1, y1


def tile_range(z, minlon, minlat, maxlon, maxlat):
    x0, y0, x1, y1 = tile_bounds(z, minlon, minlat, maxlon, maxlat)
    return [(z, x, y) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1)]


def cell_tiles(cz, cx, cy, minzoom, maxzoom):
    """Every tile z >= max(cz, minzoom) inside cell cz/cx/cy (the tiles the cell owns)."""
    out = []
    for z in range(max(cz, minzoom), maxzoom + 1):
        d = z - cz
        out += [(z, x, y) for x in range(cx << d, (cx + 1) << d) for y in range(cy << d, (cy + 1) << d)]
    return out


def cell_bbox(cz, cx, cy):
    n = 2 ** cz
    lat = lambda y: math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * y / n))))
    return cx / n * 360 - 180, lat(cy + 1), (cx + 1) / n * 360 - 180, lat(cy)


def fetch(t, cache=None):
    """AWS Terrarium PNG of tile t=(z, x, y), from DIR/z/x/y.png when cached."""
    z, x, y = t
    path = os.path.join(cache, str(z), str(x), f"{y}.png") if cache else None
    if path and os.path.isfile(path) and os.path.getsize(path) > 0:
        with open(path, "rb") as f:
            return t, f.read()
    err = None
    for attempt in range(6):
        if attempt:
            time.sleep(2 ** attempt)  # 2, 4, 8, 16, 32 s: rides out short network / DNS hiccups
        try:
            with urllib.request.urlopen(URL.format(z=z, x=x, y=y), timeout=60) as r:
                data = r.read()
            break
        except Exception as e:  # retried; the last error is raised below
            err = e
    else:
        raise RuntimeError(f"failed {t}: {err}")
    if path:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        tmp = f"{path}.{os.getpid()}.tmp"
        with open(tmp, "wb") as f:
            f.write(data)
        os.replace(tmp, path)
    return t, data


def fetch_all(tiles, cache=None):
    with ThreadPoolExecutor(16) as ex:
        return dict(ex.map(lambda t: fetch(t, cache), tiles))


def decode(data):
    """Terrarium tile bytes (PNG or WebP) -> float64 heights in metres."""
    import numpy as np
    from PIL import Image
    a = np.asarray(Image.open(io.BytesIO(data)).convert("RGB")).astype(np.float64)
    return a[..., 0] * 256 + a[..., 1] + a[..., 2] / 256 - 32768


def reencode(item, webp, step, floor=None):
    """Round heights to `step` m (0 = keep), raise heights below `floor` m to it (None = keep) and store as WebP
    lossless or PNG."""
    import numpy as np
    from PIL import Image
    t, data = item
    if not webp and not step and floor is None:
        return t, data
    if step or floor is not None:
        h = decode(data)
        if floor is not None:
            h = np.maximum(h, floor)
        e = (np.round(h / step) * step if step else h) + 32768
        e = np.clip(e, 0, 65536 - 1 / 256)
        r = np.floor(e / 256)
        g = np.floor(e - r * 256)
        b = np.round((e - r * 256 - g) * 256)
        rgb = np.stack([r, g, np.minimum(b, 255)], axis=-1).astype(np.uint8)
        img = Image.fromarray(rgb, "RGB")
    else:
        img = Image.open(io.BytesIO(data)).convert("RGB")
    buf = io.BytesIO()
    if webp:
        # lossless: Terrarium packs the height into R/G/B bytes, any lossy change would be metres of error
        img.save(buf, "WEBP", lossless=True, quality=100, method=6, exact=True)
    else:
        img.save(buf, "PNG", optimize=True)
    return t, buf.getvalue()


def parse_args(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("out")
    ap.add_argument("bbox", nargs="*", type=float, help="minlon minlat maxlon maxlat [maxzoom]")
    ap.add_argument("--format", type=int, choices=(1, 2), default=1)
    ap.add_argument("--minzoom", type=int, default=5)
    ap.add_argument("--maxzoom", type=int)
    ap.add_argument("--webp", action="store_true")
    ap.add_argument("--round", type=float, default=0.0)
    ap.add_argument("--floor", type=float)
    ap.add_argument("--cell")
    ap.add_argument("--cache")
    a = ap.parse_args(argv)
    if a.format == 2:
        a.webp = True
        a.round = a.round or 0.25
        a.maxzoom = a.maxzoom or 12
    if len(a.bbox) == 5:
        a.maxzoom = int(a.bbox.pop())
    a.maxzoom = a.maxzoom or 13
    if a.cell:
        try:
            a.cell = tuple(int(v) for v in a.cell.split("/"))
            assert len(a.cell) == 3
        except (ValueError, AssertionError):
            ap.error("--cell wants Z/X/Y, e.g. 10/543/361")
        if a.bbox:
            ap.error("give either a bbox or --cell")
        a.bbox = list(cell_bbox(*a.cell))
    elif len(a.bbox) != 4:
        ap.error("bbox (minlon minlat maxlon maxlat) or --cell needed")
    return a


def main(argv=None):
    from multiprocessing import Pool

    from pmtiles.tile import Compression, TileType, zxy_to_tileid
    from pmtiles.writer import Writer

    a = parse_args(argv)
    minlon, minlat, maxlon, maxlat = a.bbox
    if a.cell:
        tiles = cell_tiles(*a.cell, a.minzoom, a.maxzoom)
        minzoom = max(a.cell[0], a.minzoom)
    else:
        tiles = []
        for z in range(a.minzoom, a.maxzoom + 1):
            tiles += tile_range(z, minlon, minlat, maxlon, maxlat)
        minzoom = a.minzoom
    print("tiles:", len(tiles), f"z{minzoom}-{a.maxzoom}", "webp" if a.webp else "png",
          f"round {a.round} m" if a.round else "exact", flush=True)
    data = fetch_all(tiles, a.cache)
    src_bytes = sum(len(v) for v in data.values())
    if a.webp or a.round or a.floor is not None:
        from functools import partial
        with Pool() as pool:
            data = dict(pool.map(partial(reencode, webp=a.webp, step=a.round, floor=a.floor), list(data.items()), chunksize=4))
    tmp = a.out + ".tmp"
    with open(tmp, "wb") as f:
        w = Writer(f)
        for t in sorted(data, key=lambda t: zxy_to_tileid(*t)):
            w.write_tile(zxy_to_tileid(*t), data[t])
        w.finalize(
            {
                "tile_type": TileType.WEBP if a.webp else TileType.PNG,
                "tile_compression": Compression.NONE,
                "min_zoom": minzoom,
                "max_zoom": a.maxzoom,
                "min_lon_e7": int(minlon * 1e7),
                "min_lat_e7": int(minlat * 1e7),
                "max_lon_e7": int(maxlon * 1e7),
                "max_lat_e7": int(maxlat * 1e7),
                "center_zoom": min(11, a.maxzoom),
                "center_lon_e7": int((minlon + maxlon) / 2 * 1e7),
                "center_lat_e7": int((minlat + maxlat) / 2 * 1e7),
            },
            {"name": "terrain", "encoding": "terrarium", "format": "webp" if a.webp else "png",
             **({"height_step_m": a.round} if a.round else {}),
             "attribution": "Terrain: Mapzen / AWS Open Data (SRTM, Copernicus, others)"},
        )
    os.replace(tmp, a.out)
    out_bytes = sum(len(v) for v in data.values())
    print(f"written {a.out}: {len(data)} tiles, {out_bytes / 1e6:.2f} MB tile data (AWS PNG {src_bytes / 1e6:.2f} MB)")


if __name__ == "__main__":
    main()
