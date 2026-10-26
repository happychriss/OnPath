"""Towns and path links in the format-2 overlay (extract_overlays.py TownIndex / link_pieces, "quiet towns" 2026-10-26):
cd tools && python3 -m unittest -v test_extract_overlays

Runs extract_overlays.py on a tiny made-up OSM file. Needs pyosmium + shapely (the pipeline's packages); skipped
without them."""
import json
import os
import subprocess
import sys
import tempfile
import unittest

# Probe in a fresh interpreter, the way the script runs: another test in the same run (test_extract_pois) may put a
# stand-in "osmium" into sys.modules, so an import here could succeed while the real script cannot.
HAVE_LIBS = subprocess.run([sys.executable, "-c", "import osmium, shapely"], capture_output=True).returncode == 0

LON0, LAT0 = 11.0, 46.0
M_LAT = 1 / 110540.0
M_LON = 1 / (111320.0 * 0.6947)  # cos(46°)


def at(x, y):
    """Metres east / north of the origin -> (lon, lat)."""
    return LON0 + x * M_LON, LAT0 + y * M_LAT


class Osm:
    def __init__(self):
        self.nodes, self.ways, self.ids = [], [], {}

    def node(self, x, y):
        key = (x, y)
        if key not in self.ids:
            self.ids[key] = len(self.ids) + 1
            lon, lat = at(x, y)
            self.nodes.append(f'<node id="{self.ids[key]}" version="1" lat="{lat:.7f}" lon="{lon:.7f}"/>')
        return self.ids[key]

    def way(self, pts, **tags):
        refs = "".join(f'<nd ref="{self.node(x, y)}"/>' for x, y in pts)
        tg = "".join(f'<tag k="{k}" v="{v}"/>' for k, v in tags.items())
        wid = len(self.ways) + 1
        self.ways.append(f'<way id="{wid}" version="1">{refs}{tg}</way>')
        return wid

    def area(self, x0, y0, x1, y1, **tags):
        return self.way([(x0, y0), (x1, y0), (x1, y1), (x0, y1), (x0, y0)], **tags)

    def xml(self):
        return ('<?xml version="1.0" encoding="UTF-8"?>\n<osm version="0.6" generator="test">\n'
                + "\n".join(self.nodes + self.ways) + "\n</osm>\n")


@unittest.skipUnless(HAVE_LIBS, "pyosmium / shapely not installed")
class TownsAndLinks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        o = Osm()
        o.area(0, 0, 200, 200, landuse="residential")
        o.area(120, 20, 180, 80, leisure="park")
        o.area(300, 0, 500, 300, landuse="forest")
        # town street with a sidewalk and a crossing to it; a footway ends on the sidewalk (the gap)
        o.way([(0, 108), (60, 108), (200, 108)], highway="residential", name="Hauptstraße")
        o.way([(0, 100), (50, 100), (60, 100), (90, 100), (100, 100)], highway="footway", footway="sidewalk")
        o.way([(60, 100), (60, 108)], highway="footway", footway="crossing")
        o.way([(50, 40), (50, 100)], highway="footway", name="town footway")
        # a 5 m spur on the sidewalk: no link (traffic-island pieces made small triangles)
        o.way([(90, 95), (90, 100)], highway="footway", name="spur")
        o.way([(150, 30), (150, 70)], highway="footway", name="park footway")
        o.way([(60, 20), (100, 20)], highway="path", sac_scale="mountain_hiking", name="graded")
        o.way([(350, 50), (350, 150)], highway="path", name="forest path")
        o.way([(300, 250), (500, 250)], highway="unclassified", name="country road")
        o.area(10, 150, 40, 180, highway="pedestrian", area="yes")
        cls.tmp = tempfile.TemporaryDirectory()
        src = os.path.join(cls.tmp.name, "t.osm")
        with open(src, "w") as f:
            f.write(o.xml())
        out = os.path.join(cls.tmp.name, "tiles")
        lo, la = at(-50, -50)
        hi, ha = at(550, 350)
        script = os.path.join(os.path.dirname(os.path.abspath(__file__)), "extract_overlays.py")
        res = subprocess.run([sys.executable, script, src, cls.tmp.name, str(lo), str(la), str(hi), str(ha),
                              "--tiles", out, "--no-geojson", "--smooth"], capture_output=True, text=True)
        if res.returncode:
            raise AssertionError(res.stdout + res.stderr)

        def read(name):
            with open(os.path.join(out, name + ".ndjson")) as f:
                return [json.loads(line) for line in f]
        cls.paths, cls.streets = read("paths"), read("streets")

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def exact(self, k="p"):
        return [f for f in self.paths if f["properties"].get("k") == k and f["tippecanoe"]["minzoom"] == 13]

    def by_start(self, x, y, k="p"):
        lon, lat = at(x, y)
        for f in self.exact(k):
            c = f["geometry"]["coordinates"]
            if any(abs(p[0] - lon) < 2e-6 and abs(p[1] - lat) < 2e-6 for p in (c[0], c[-1])):
                return f["properties"]
        self.fail(f"no {k} way at {x},{y}")

    def test_town_flag(self):
        self.assertEqual(self.by_start(50, 40).get("u"), 1)       # footway in built-up land
        self.assertEqual(self.by_start(150, 30).get("u"), 2)      # in a town park: dark, but not zoomed out
        self.assertNotIn("u", self.by_start(350, 50))             # forest path
        self.assertNotIn("u", self.by_start(60, 20))              # graded trail: hiking look stays
        low = [f["properties"] for f in self.paths if f["tippecanoe"]["minzoom"] == 11]
        self.assertIn({"k": "p", "u": 1}, low)                    # the zoomed-out copy carries it too

    def test_town_streets(self):
        town = [f for f in self.streets if f["properties"].get("u") == 1]
        self.assertEqual(len(town), 1)
        self.assertEqual(len(self.streets), 2)

    def test_link_from_path_end_through_sidewalk_and_crossing(self):
        links = self.exact("l")
        self.assertEqual(len(links), 1, links)
        c = links[0]["geometry"]["coordinates"]
        self.assertEqual([round(v, 6) for v in c[0]], [round(v, 6) for v in at(50, 100)])
        self.assertEqual([round(v, 6) for v in c[-1]], [round(v, 6) for v in at(60, 108)])
        self.assertEqual(links[0]["properties"].get("u"), 1)

    def test_pedestrian_square_marked(self):
        sq = [f["properties"] for f in self.exact("x") if f["properties"].get("sub") == "pedestrian"]
        self.assertEqual(sq, [{"k": "x", "sub": "pedestrian", "ar": 1, "u": 1}])


if __name__ == "__main__":
    unittest.main()
