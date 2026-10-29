"""Offline tests for the pre-rendered hill shade: cd tools && python3 -m unittest -v test_build_hillshade
Needs numpy, pillow and pmtiles (the builder image has them); skipped without them. No network: the Terrarium tiles
come from a small synthetic cache, the worker pool runs in-process."""
import io
import os
import sys
import tempfile
import unittest

try:
    import numpy as np
    from PIL import Image
    from pmtiles.reader import MmapSource, all_tiles
    HAVE_DEPS = True
except ImportError:  # expected on CI without the map tools: the test is skipped
    HAVE_DEPS = False

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))


def terrarium_png(height):
    """256 x 256 Terrarium PNG of a height field (metres)."""
    v = np.clip(height + 32768, 0, 65535.99)
    rgb = np.dstack([np.floor(v / 256), np.floor(v % 256), np.floor((v % 1) * 256)]).astype(np.uint8)
    buf = io.BytesIO()
    Image.fromarray(rgb, "RGB").save(buf, "PNG")
    return buf.getvalue()


def write_cache(root, maxzoom, flat=False):
    """A cone-shaped mountain over every tile of z0..maxzoom (so every tile has shade); flat: 1 m everywhere."""
    yy, xx = np.mgrid[0:256, 0:256]
    cone = np.full((256, 256), 1.0) if flat else np.maximum(0, 2500 - 25 * np.hypot(xx - 128, yy - 128))
    for z in range(maxzoom + 1):
        for x in range(2 ** z):
            for y in range(2 ** z):
                d = os.path.join(root, str(z), str(x))
                os.makedirs(d, exist_ok=True)
                with open(os.path.join(d, f"{y}.png"), "wb") as f:
                    f.write(terrarium_png(cone))


class InProcessPool:
    """multiprocessing.Pool stand-in: the jobs run here (no worker processes, so the test sees module patches)."""

    def __init__(self, n, initializer=None, initargs=()):
        if initializer:
            initializer(*initargs)

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False

    def imap_unordered(self, fn, jobs, chunksize=1):
        return (fn(j) for j in jobs)


@unittest.skipUnless(HAVE_DEPS, "numpy / pillow / pmtiles not installed")
class TileSize(unittest.TestCase):
    def setUp(self):
        import build_hillshade as bh
        self.bh = bh
        self.saved = bh.Pool
        bh.Pool = InProcessPool
        self.tmp = tempfile.TemporaryDirectory()
        self.cache = os.path.join(self.tmp.name, "cache")
        write_cache(self.cache, 2)

    def tearDown(self):
        self.bh.Pool = self.saved
        self.tmp.cleanup()

    def tiles(self, path):
        with open(path, "rb") as f:
            return {t: bytes(d) for t, d in all_tiles(MmapSource(f))}

    def test_tiles_are_256_px_from_their_own_zoom(self):
        # regression 2026-10-26: the fetch / shade loop counters were called k and overwrote the tile size factor
        # k = tile_size // 256 before the jobs were built – every tile was shaded at the wrong size from a DEM zoom
        # that was not there (here: no tile at all)
        out = os.path.join(self.tmp.name, "hs.pmtiles")
        self.bh.main([out, "--maxzoom", "1", "--cache", self.cache, "--workers", "1"])
        tiles = self.tiles(out)
        self.assertEqual(sorted(t for t in tiles), [(0, 0, 0), (1, 0, 0), (1, 0, 1), (1, 1, 0), (1, 1, 1)])
        for t, data in tiles.items():
            self.assertEqual(Image.open(io.BytesIO(data)).size, (256, 256), t)

    def test_keep_flat_writes_every_tile_as_background(self):
        # region packages: the opaque relief must cover the whole package, also flat land and sea
        flat = os.path.join(self.tmp.name, "flat")
        write_cache(flat, 1, flat=True)
        out = os.path.join(self.tmp.name, "flat.pmtiles")
        # without --keep-flat these tiles have no shade and are left out (here: nothing to write at all)
        self.bh.main([out, "--maxzoom", "1", "--cache", flat, "--workers", "1", "--keep-flat"])
        tiles = self.tiles(out)
        self.assertEqual(len(tiles), 5)
        px = Image.open(io.BytesIO(tiles[(1, 0, 0)])).convert("RGB").getpixel((128, 128))
        for got, want in zip(px, (0xf6, 0xf4, 0xec)):  # the style background (WebP lossy: a few levels off)
            self.assertLessEqual(abs(got - want), 4, px)


if __name__ == "__main__":
    unittest.main()
