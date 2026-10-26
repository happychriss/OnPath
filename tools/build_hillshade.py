#!/usr/bin/env python3
"""Pre-rendered hill shade (raster PMTiles, WebP lossy) from the AWS Terrarium tiles – the world overview's relief
(docs/map-pipeline.md §9b). The app draws it as a `raster` layer, not as a `hillshade` layer.

Why pre-rendered instead of a raster-dem: MapLibre draws an over-zoomed raster-dem with a seam at every tile edge
(the DEM border is only back-filled from loaded neighbours of the same zoom), magnifies the coarse DEM into blurry
"worms", and parent / child tiles shade with different strength (tone blocks while zooming). A shaded image has none
of that: every tile is computed from a block with a border from its 8 neighbours (seamless), and it magnifies
smoothly (linear resampling).

The shading is MapLibre's own hillshade shader (prepare + draw, 'map' anchor) with the app style's two layers
(tools/themes/classic.json hillshade.main / hillshade.fill at z5-9), composited (premultiplied "over"):
  hillshade-nw  315°, exaggeration 0.8, shadow rgba(45,68,45,0.40), highlight rgba(255,254,246,0.55),
                accent rgba(50,58,84,0.18)
  hillshade-w   260°, exaggeration 0.4, shadow rgba(45,68,45,0.22)
with overview tweaks: --strength (slope factor; 1 = what MapLibre draws from a DEM of that zoom – far too dark and
busy for an overview at z7), --blur (Gaussian on the DEM, pixels: no pixel staircase / WebP blocks when magnified
4-8 x, smaller files), one exaggeration factor 0.3 at every zoom (MapLibre: 0.35 below z4.5, 0.4 below z2 – a
brightness jump between z4 and z5) and --level-gain (strength x gain per level below --maxzoom: a coarser DEM has
gentler slopes; 1.3 keeps the mean brightness of a tile and its children within 1 grey level, z1-z7, so no tone
blocks while parent and child tiles mix).

Seamless tile edges: MapLibre clamps every raster tile's texture at its edge (no interpolation across tiles). With
ordinary pixel-centred texels each tile edge then shows as a straight step when magnified. So texel i holds the
shade at tile position i * 256 / 255 (edge_matrix): the first / last texel is the shade exactly ON the edge, the same
value as the neighbour's – continuous at any magnification (the 0.4 % stretch is invisible).

Tiles are opaque: the shade is composited onto --background (the style's background colour). WebP stores alpha
lossless, and a noisy alpha channel made RGBA tiles 6-10 x larger (world z0-7 ≈ 180-280 MB vs ≈ 25 MB, measured
without blur). So the layer must be drawn directly above the background, below every fill (the overview's ice /
urban fills then need some transparency to let the relief through). Sea tiles (no height > 0 m) and tiles without any shade are left
out (the map shows the background there). Heights below 0 m count as 0 (sea floor flat). Above z6 only tiles whose
z6 parent has land are downloaded.

Usage:
  python3 build_hillshade.py OUT.pmtiles --cache DIR [--bbox minlon minlat maxlon maxlat] [--maxzoom 7]
         [--quality 60] [--strength 0.6] [--level-gain 1.3] [--blur 1.5] [--background f6f4ec] [--workers 4]
"""
import argparse
import io
import math
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from multiprocessing import Pool

# one BLAS thread per worker process (the matrix products would otherwise start a thread per core in every worker)
for _v in ("OMP_NUM_THREADS", "OPENBLAS_NUM_THREADS", "MKL_NUM_THREADS"):
    os.environ.setdefault(_v, "1")
import numpy as np  # noqa: E402
from PIL import Image  # noqa: E402

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_terrain import decode, fetch, tile_range  # noqa: E402

N = 256
# (direction °, exaggeration, shadow, highlight, accent) – rgba with alpha 0..1, as in the style
LAYERS = [
    (315, 0.8, (45, 68, 45, 0.40), (255, 254, 246, 0.55), (50, 58, 84, 0.18)),
    (260, 0.4, (45, 68, 45, 0.22), (255, 255, 255, 0.0), (0, 0, 0, 0.0)),
]
ALPHA_MIN = 3  # max alpha (0..255) below which a tile counts as empty

CACHE = None
BACKGROUND = None  # opaque: RGB 0..1 the shade is composited onto; None = RGBA


def set_cache(cache, background=None):
    global CACHE, BACKGROUND
    CACHE, BACKGROUND = cache, background


def premul(c):
    r, g, b, a = c
    return np.array([r / 255 * a, g / 255 * a, b / 255 * a, a])


def load(z, x, y):
    """Heights of tile z/x/y (x wraps around the antimeridian), None outside the world or when not available."""
    n = 2 ** z
    if y < 0 or y >= n:
        return None
    x %= n
    path = os.path.join(CACHE, str(z), str(x), f"{y}.png")
    if not os.path.isfile(path):
        return None
    with open(path, "rb") as f:
        return np.maximum(decode(f.read()), 0.0)


def buffered(z, x0, y0, k, pad):
    """(256 k + 2 pad)² heights: the k x k DEM tiles from z/x0/y0 plus a border of `pad` pixels from the tiles around
    them (edge pixels copied where a neighbour is missing – beyond the poles, or sea that was not downloaded).
    None when none of the k x k tiles has a DEM."""
    inner = [[load(z, x0 + i, y0 + j) for i in range(k)] for j in range(k)]
    if all(t is None for row in inner for t in row):
        return None
    flat = np.zeros((N, N))
    m = np.pad(np.block([[t if t is not None else flat for t in row] for row in inner]), N, mode="edge")
    for i in range(-1, k + 1):
        for j in range(-1, k + 1):
            if 0 <= i < k and 0 <= j < k:
                continue
            nb = load(z, x0 + i, y0 + j)
            if nb is not None:
                m[(j + 1) * N:(j + 2) * N, (i + 1) * N:(i + 2) * N] = nb
    return m[N - pad:(k + 1) * N + pad, N - pad:(k + 1) * N + pad]


def blur(h, sigma, pad):
    """Gaussian blur (sigma in pixels) of a block padded by `pad`, cropped to a 2-pixel border."""
    r = pad - 2
    size = h.shape[0] - 2 * r
    if sigma <= 0 or r <= 0:
        return h[r:r + size, r:r + size]
    w = np.exp(-0.5 * (np.arange(-r, r + 1) / sigma) ** 2)
    w /= w.sum()
    g = np.zeros((size, h.shape[0]))
    for i in range(size):
        g[i, i:i + 2 * r + 1] = w
    return g @ h @ g.T


def shade(h, z, y_px, strength=1.0):
    """MapLibre's hillshade (prepare + draw shaders, 'map' anchor, north up) of a height block of DEM zoom z whose
    first row is centred at y_px (pixel rows of that zoom from the north edge of the world) -> premultiplied RGBA
    float, one pixel smaller on each side, values 0..1."""
    a, b, c = h[:-2, :-2], h[:-2, 1:-1], h[:-2, 2:]
    d, f = h[1:-1, :-2], h[1:-1, 2:]
    g, hh, i = h[2:, :-2], h[2:, 1:-1], h[2:, 2:]
    # MapLibre: factor 0.4 below z2, 0.35 below z4.5, else 0.3 – one factor here, so the brightness does not jump
    # between z4 and z5 (--level-gain evens out the levels)
    ex = (z - 15.0) * 0.3
    div = 2.0 ** (ex + (19.2562 - z)) / strength
    dx = np.clip(((c + f + f + i) - (a + d + d + g)) / div, -1, 1)
    dy = np.clip(((g + hh + hh + i) - (a + b + b + c)) / div, -1, 1)
    # latitude of each pixel row (scaleFactor in the draw shader)
    yy = np.clip((y_px + 1 + np.arange(dx.shape[0])) / (N * 2 ** z), 0, 1)
    lat = np.degrees(np.arctan(np.sinh(np.pi * (1 - 2 * yy))))
    scale = np.cos(np.radians(lat))[:, None]
    slope = np.arctan(1.25 * np.hypot(dx, dy) / scale)
    aspect = np.where(dx != 0, np.arctan2(dy, -dx), np.pi / 2 * np.where(dy > 0, 1.0, -1.0))
    out = np.zeros(dx.shape + (4,))
    for direction, inten, sh, hi, ac in LAYERS:
        az = math.radians(direction) + math.pi
        base = 1.875 - inten * 1.75
        maxv = 0.5 * math.pi
        ss = ((np.power(base, slope) - 1) / (base ** maxv - 1)) * maxv if inten != 0.5 else slope
        k = min(max(inten * 2, 0), 1)
        accent = (1 - np.cos(ss))[..., None] * premul(ac) * k
        s = np.abs(np.mod((aspect + az) / math.pi + 0.5, 2.0) - 1.0)[..., None]
        col = (premul(sh) * (1 - s) + premul(hi) * s) * np.sin(ss)[..., None] * k
        layer = accent * (1 - col[..., 3:4]) + col
        out = layer + out * (1 - layer[..., 3:4])  # this layer over the ones below
    return out


def edge_matrix(m):
    """(m, m + 2) bilinear weights: texel i of an m-pixel tile takes the shade at tile position i * m / (m - 1)
    (pixels from the tile's left / top edge), from an (m + 2)-pixel shade whose pixel j is centred at j - 0.5.
    So the first and last texels hold the shade exactly ON the tile edge, the same value as the neighbour tile's
    edge texel: MapLibre clamps each raster tile's texture at its edge (no interpolation across tiles), and with
    plain pixel-centred texels every tile edge showed as a straight step when magnified (seams at z8-10)."""
    w = np.zeros((m, m + 2))
    for i in range(m):
        q = i * m / (m - 1) + 0.5
        j = min(int(math.floor(q)), m)
        t = q - j
        w[i, j] += 1 - t
        w[i, j + 1] += t
    return w


def tile_shade(z, x, y, strength, sigma, k=1):
    """Premultiplied RGBA (256 k, 256 k, 4) of tile z/x/y, k = 1 (256 px, from DEM zoom z) or 2 (512 px, from the
    2 x 2 DEM tiles of zoom z + 1), edge-aligned (see edge_matrix); None without DEM."""
    zd = z + (k - 1)
    pad = 2 + (math.ceil(3 * sigma) if sigma > 0 else 0)
    h = buffered(zd, x * k, y * k, k, pad)
    if h is None:
        return None
    p = shade(blur(h, sigma, pad), zd, y * k * N - 1.5, strength)  # (256 k + 2)²: pixel centres -0.5 .. 256 k + 0.5
    e = edge_matrix(N * k)
    rows = np.tensordot(e, p, axes=(1, 0))
    return np.tensordot(rows, e, axes=(1, 1)).transpose(0, 2, 1)


def encode(p, quality):
    """premultiplied RGBA float -> WebP lossy bytes, None when there is (almost) no shade. With BACKGROUND set the
    shade is composited onto it (opaque image: about 1/10 of the size – WebP keeps alpha lossless, and a noisy
    alpha channel costs far more than the lossy colour), else RGBA."""
    a = p[..., 3]
    if a.max() * 255 < ALPHA_MIN:
        return None
    if BACKGROUND is not None:
        im = Image.fromarray(np.round((p[..., :3] + BACKGROUND * (1 - a[..., None])) * 255).astype(np.uint8), "RGB")
    else:
        rgb = np.where(a[..., None] > 1e-4, p[..., :3] / np.maximum(a[..., None], 1e-4), 0.5)
        img = np.dstack([np.clip(rgb, 0, 1), a[..., None]])
        im = Image.fromarray(np.round(img * 255).astype(np.uint8), "RGBA")
    buf = io.BytesIO()
    im.save(buf, "WEBP", quality=quality, alpha_quality=100, method=6)
    return buf.getvalue()


def job_shade(args):
    z, x, y, quality, strength, sigma, k = args
    p = tile_shade(z, x, y, strength, sigma, k)
    return (z, x, y), (encode(p, quality) if p is not None else None)


def main(argv=None):
    global CACHE, BACKGROUND
    from pmtiles.tile import Compression, TileType, zxy_to_tileid
    from pmtiles.writer import Writer

    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("out")
    ap.add_argument("--bbox", nargs=4, type=float, default=[-180, -85.0511, 180, 85.0511])
    ap.add_argument("--minzoom", type=int, default=0)
    ap.add_argument("--maxzoom", type=int, default=7)
    ap.add_argument("--quality", type=int, default=60, help="WebP lossy quality")
    ap.add_argument("--strength", type=float, default=0.6,
                    help="slope factor at --maxzoom on top of MapLibre's formula (1 = what the hillshade layer draws "
                         "from a DEM of that zoom: far too dark and busy for an overview)")
    ap.add_argument("--level-gain", type=float, default=1.3,
                    help="strength factor per zoom level below --maxzoom: a coarser DEM has gentler slopes, this "
                         "keeps the brightness of parent and child tiles equal (no tone blocks while zooming)")
    ap.add_argument("--blur", type=float, default=1.5, help="Gaussian blur of the DEM in pixels before shading: "
                    "smoother when magnified, smaller files")
    ap.add_argument("--background", default="f6f4ec",
                    help="hex colour the shade is composited onto (the style's background), 'none' = RGBA tiles")
    ap.add_argument("--tile-size", type=int, choices=(256, 512), default=256,
                    help="512: each tile from the 2 x 2 DEM tiles one zoom deeper (z0-6 at 512 = z7 detail, half the "
                         "tile edges); the style source then needs tileSize 512")
    ap.add_argument("--workers", type=int, default=4, help="worker processes (keep the laptop usable)")
    ap.add_argument("--cache", required=True, help="AWS Terrarium PNGs DIR/z/x/y.png (shared with build_terrain.py)")
    a = ap.parse_args(argv)
    CACHE = a.cache
    BACKGROUND = None if a.background == "none" else np.array([int(a.background[i:i + 2], 16) / 255 for i in (0, 2, 4)])
    minlon, minlat, maxlon, maxlat = a.bbox
    t0 = time.time()
    top = a.maxzoom

    k = a.tile_size // N
    dz = k - 1  # DEM zoom = tile zoom + dz
    need = {z: tile_range(z, minlon, minlat, maxlon, maxlat) for z in range(a.minzoom, top + 1)}
    land = None
    if top + dz > 6:
        # land test on the z6 DEM; tiles from a deeper DEM under a z6 tile without land (sea) are neither
        # downloaded nor drawn
        land = set()
        for (_, x6, y6) in tile_range(6, minlon, minlat, maxlon, maxlat):
            if decode(fetch((6, x6, y6), CACHE)[1]).max() > 0:
                land.add((x6, y6))
        for z in range(max(7 - dz, a.minzoom), top + 1):
            sh = z + dz - 6
            need[z] = [t for t in need[z] if ((t[1] * k) >> sh, (t[2] * k) >> sh) in land]
    fetch_set = set()  # the DEM tiles and a ring around them (border); a missing sea neighbour is copied from the edge
    for z, tiles in need.items():
        zd = z + dz
        n = 2 ** zd
        for (_, x, y) in tiles:
            for i in range(-1, k + 1):
                for j in range(-1, k + 1):
                    t = (zd, (x * k + i) % n, y * k + j)
                    if 0 <= t[2] < n and (zd <= 6 or (t[1] >> (zd - 6), t[2] >> (zd - 6)) in land):
                        fetch_set.add(t)
    print(f"DEM tiles: {len(fetch_set)}, tiles to shade: {sum(map(len, need.values()))} (z{a.minzoom}-{top})",
          flush=True)
    with ThreadPoolExecutor(16) as ex:
        # loop counters must not be called k: k (tile size factor) is used below to build the jobs (bug until
        # 2026-10-26: after the loop k was the last fetch index, so every job shaded the wrong DEM zoom / size)
        for n_done, _ in enumerate(ex.map(lambda t: fetch(t, CACHE), sorted(fetch_set))):
            if n_done % 2000 == 0:
                print(f"  fetched {n_done}", flush=True)
    print(f"downloaded / cached in {time.time() - t0:.0f} s", flush=True)

    out = {}
    jobs = [(z, x, y, a.quality, a.strength * a.level_gain ** (top - z), a.blur, k) for z in need for (_, x, y) in need[z]]
    with Pool(a.workers, initializer=set_cache, initargs=(CACHE, BACKGROUND)) as pool:  # Python 3.14: workers do not fork the globals
        for n_done, (t, data) in enumerate(pool.imap_unordered(job_shade, jobs, chunksize=8)):
            if data is not None:
                out[t] = data
            if n_done % 4000 == 0:
                print(f"  shaded {n_done}", flush=True)
    print(f"shaded {len(jobs)} tiles in {time.time() - t0:.0f} s", flush=True)

    tmp = a.out + ".tmp"
    with open(tmp, "wb") as f:
        w = Writer(f)
        for t in sorted(out, key=lambda t: zxy_to_tileid(*t)):
            w.write_tile(zxy_to_tileid(*t), out[t])
        w.finalize(
            {"tile_type": TileType.WEBP, "tile_compression": Compression.NONE,
             "min_zoom": a.minzoom, "max_zoom": top,
             "min_lon_e7": int(minlon * 1e7), "min_lat_e7": int(minlat * 1e7),
             "max_lon_e7": int(maxlon * 1e7), "max_lat_e7": int(maxlat * 1e7),
             "center_zoom": min(4, top), "center_lon_e7": int((minlon + maxlon) / 2 * 1e7),
             "center_lat_e7": int((minlat + maxlat) / 2 * 1e7)},
            {"name": "hillshade", "format": "webp", "type": "baselayer", "tileSize": a.tile_size,
             "description": f"pre-rendered hill shade on #{a.background}, strength {a.strength}, "
                            f"level gain {a.level_gain}, blur {a.blur} px, WebP q{a.quality}, {a.tile_size} px",
             "attribution": "Terrarium (Mapzen/AWS)"},
        )
    os.replace(tmp, a.out)
    for z in range(a.minzoom, top + 1):
        zs = [len(v) for t, v in out.items() if t[0] == z]
        print(f"  z{z}: {len(zs)} tiles, {sum(zs) / 1e6:.2f} MB")
    print(f"written {a.out}: {len(out)} tiles, {os.path.getsize(a.out) / 1e6:.2f} MB, {time.time() - t0:.0f} s")


if __name__ == "__main__":
    main()
