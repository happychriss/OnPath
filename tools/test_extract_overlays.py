"""Format-2 overlay data of extract_overlays.py: towns and path links (TownIndex / link_pieces, "quiet towns")
and the bike map attributes of schema 7 (cycle networks, route labels, bicycle streets, footbridges):
cd tools && python3 -m unittest -v test_extract_overlays

Runs extract_overlays.py on a tiny made-up OSM file. Needs pyosmium + shapely (the pipeline's packages); skipped
without them."""
import json
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
# build_overlays.sh / cells.py tiles: tippecanoe + the pmtiles package (the cell builder image has both)
HAVE_TILES = (all(subprocess.run(["sh", "-c", f"command -v {t}"], capture_output=True).returncode == 0
                  for t in ("tippecanoe", "tile-join"))
              and subprocess.run([sys.executable, "-c", "import pmtiles"], capture_output=True).returncode == 0)

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
        self.nodes, self.ways, self.rels, self.ids = [], [], [], {}

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

    def route(self, way_ids, **tags):
        """A route relation over these ways (type=route added)."""
        mem = "".join(f'<member type="way" ref="{w}" role=""/>' for w in way_ids)
        tg = "".join(f'<tag k="{k}" v="{v}"/>' for k, v in {"type": "route", **tags}.items())
        self.rels.append(f'<relation id="{len(self.rels) + 1}" version="1">{mem}{tg}</relation>')

    def xml(self):
        return ('<?xml version="1.0" encoding="UTF-8"?>\n<osm version="0.6" generator="test">\n'
                + "\n".join(self.nodes + self.ways + self.rels) + "\n</osm>\n")


def run_extract(osm, tmp, x0=-50, y0=-50, x1=550, y1=350):
    """extract_overlays.py --tiles on the made-up file -> {layer: [features]} of the tile inputs."""
    src = os.path.join(tmp, "t.osm")
    with open(src, "w") as f:
        f.write(osm.xml())
    out = os.path.join(tmp, "tiles")
    lo, la = at(x0, y0)
    hi, ha = at(x1, y1)
    script = os.path.join(os.path.dirname(os.path.abspath(__file__)), "extract_overlays.py")
    res = subprocess.run([sys.executable, script, src, tmp, str(lo), str(la), str(hi), str(ha),
                          "--tiles", out, "--no-geojson", "--smooth"], capture_output=True, text=True)
    if res.returncode:
        raise AssertionError(res.stdout + res.stderr)
    layers = {}
    for name in ("paths", "streets", "routes", "areas"):
        with open(os.path.join(out, name + ".ndjson")) as f:
            layers[name] = [json.loads(line) for line in f]
    return layers


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
        layers = run_extract(o, cls.tmp.name)
        cls.paths, cls.streets = layers["paths"], layers["streets"]

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


@unittest.skipUnless(HAVE_LIBS, "pyosmium / shapely not installed")
class BikeMap(unittest.TestCase):
    """Schema 7: only signed cycle networks, labels for named local routes, bicycle
    streets, footbridges. Every line runs west -> east at its own y (metres north), so a feature is found by its y."""

    @classmethod
    def setUpClass(cls):
        o = Osm()

        def street(y, x0=0, x1=200, **tags):
            return o.way([(x0, y), (x1, y)], highway="residential", **tags)
        # unknown networks: no route line (relation 12129864 network=bcn on "An den Gölden", 12129863, 12105768)
        o.route([street(0, name="An den Gölden")], route="bicycle", network="bcn", cycle_network="Kreis Mettmann")
        o.route([street(50)], route="bicycle", network="bicycle")
        o.route([street(100)], route="bicycle")
        # a named local route over two ways: one label line
        o.route([o.way([(0, 150), (100, 150)], highway="cycleway"), o.way([(100, 150), (200, 150)], highway="cycleway")],
                route="bicycle", network="lcn", name="Radweg Rund um Hilden")
        # an unnamed regional route over a named local one: the highlight stays regional, the label is the local name
        under, both = street(200), street(250)
        o.route([under, both], route="bicycle", network="rcn", ref="R19")
        o.route([under], route="bicycle", network="lcn", name="Local Loop")
        # a named regional route over a named local one: its "majoronly" piece has the name, no label copy
        o.route([both], route="bicycle", network="rcn", name="Big Route")
        o.route([both], route="bicycle", network="lcn", name="Other Loop")
        # a network's name is no route name
        o.route([street(300)], route="bicycle", network="lcn", name="Radverkehrsnetz NRW, Stadt Solingen Restnetz")
        # bicycle streets
        street(50, 300, 500, bicycle_road="yes", name="Fahrradstraße")
        street(100, 300, 500, cyclestreet="yes")
        street(150, 300, 500)
        # a footbridge; a hiking route on a path (no label copies for hiking)
        o.way([(300, 200), (400, 200)], highway="footway", bridge="yes")
        # building passages vs underpasses (tn): short footways at x 520-540, one per y
        for y, tags in ((0, {"tunnel": "building_passage"}), (20, {"covered": "yes"}), (40, {"indoor": "yes"}),
                        (60, {"tunnel": "yes"}), (80, {"tunnel": "culvert"}), (100, {"layer": "-1"}),
                        (120, {"layer": "-1", "bridge": "yes"}), (140, {}), (160, {"layer": "1"}),
                        (180, {"tunnel": "yes", "indoor": "yes", "layer": "-1"}),   # Hilden station underpass
                        (200, {"covered": "yes", "layer": "-1"})):
            o.way([(520, y), (540, y)], highway="footway", **tags)
        o.route([o.way([(300, 250), (500, 250)], highway="path")], route="hiking", network="lwn", name="Waalweg")
        cls.tmp = tempfile.TemporaryDirectory()
        cls.layers = run_extract(o, cls.tmp.name)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    @staticmethod
    def y_of(feat):
        """The y (metres) of a west-east line, or None when it is not level."""
        ys = {round((p[1] - LAT0) / M_LAT) for p in feat["geometry"]["coordinates"]}
        return ys.pop() if len(ys) == 1 else None

    @staticmethod
    def x_range(feat):
        xs = [round((p[0] - LON0) / M_LON) for p in feat["geometry"]["coordinates"]]
        return min(xs), max(xs)

    def bike(self):
        return [f for f in self.layers["routes"] if f["properties"]["kind"] == "bike"]

    def test_only_signed_networks_draw_a_route(self):
        band = {self.y_of(f) for f in self.bike() if "majoronly" not in f["properties"]}
        self.assertEqual(band, {150, 200, 250, 300})

    def test_label_copies(self):
        lb = sorted((self.y_of(f), self.x_range(f), f["properties"]) for f in self.bike() if "lb" in f["properties"])
        self.assertEqual(lb, [
            (150, (0, 200), {"kind": "bike", "name": "Radweg Rund um Hilden", "lb": 1, "majoronly": 1}),
            (200, (0, 200), {"kind": "bike", "name": "Local Loop", "lb": 1, "majoronly": 1})])

    def test_highlight_keeps_the_highest_ranked_route(self):
        major = sorted((self.y_of(f), f["properties"]) for f in self.bike()
                       if "majoronly" in f["properties"] and "lb" not in f["properties"])
        self.assertEqual(major, [(200, {"kind": "bike", "ref": "R19", "net": 2, "majoronly": 1}),
                                 (250, {"kind": "bike", "name": "Big Route", "net": 2, "majoronly": 1})])

    def test_no_label_copies_for_hiking(self):
        hike = [f["properties"] for f in self.layers["routes"] if f["properties"]["kind"] == "hike"]
        self.assertEqual(hike, [{"kind": "hike"}])

    def test_bicycle_streets(self):
        bs = {self.y_of(f): f["properties"].get("bs") for f in self.layers["streets"] if self.x_range(f) == (300, 500)}
        self.assertEqual(bs, {50: 1, 100: 1, 150: None})

    def test_footbridge(self):
        br = {self.y_of(f): f["properties"].get("br") for f in self.layers["paths"]
              if f["tippecanoe"]["minzoom"] == 13 and self.x_range(f)[0] == 300}
        self.assertEqual(br, {200: 1, 250: None})

    def test_passage_or_underpass(self):
        tn = {self.y_of(f): f["properties"].get("tn") for f in self.layers["paths"]
              if f["tippecanoe"]["minzoom"] == 13 and self.x_range(f) == (520, 540)}
        self.assertEqual(tn, {0: "p", 20: "p", 40: "p", 60: "t", 80: "t", 100: "t", 120: None, 140: None, 160: None,
                              180: "t", 200: "t"})


@unittest.skipUnless(HAVE_LIBS, "pyosmium / shapely not installed")
class JunctionAreas(unittest.TestCase):
    """Schema 7 (Hilden Hagelkreuz): street areas as polygons (layer "areas") and short unnamed town footways
    between sidewalks hidden like a sidewalk (no stray pieces, no links from them)."""

    @classmethod
    def setUpClass(cls):
        o = Osm()
        o.area(0, 0, 400, 300, landuse="residential")
        o.area(10, 10, 30, 20, **{"area:highway": "footway"})
        o.area(40, 10, 50, 16, **{"area:highway": "traffic_island"})
        o.area(60, 10, 61, 11, **{"area:highway": "traffic_island"})       # 1 m²: left out
        o.area(70, 10, 100, 30, highway="pedestrian", area="yes")
        o.area(110, 10, 120, 40, man_made="bridge")
        o.area(130, 10, 135, 60, highway="platform")
        o.area(150, 10, 170, 30, highway="footway")                       # a closed footway line, no area
        # a street with a sidewalk on each side; footways between the sidewalks
        o.way([(0, 150), (350, 150), (400, 150)], highway="residential", name="Hauptstraße")
        o.way([(0, 140), (100, 140), (200, 140), (300, 140), (400, 140)], highway="footway", footway="sidewalk")
        o.way([(0, 160), (100, 160), (400, 160)], highway="footway", footway="sidewalk")
        o.way([(100, 140), (100, 120)], highway="footway", footway="sidewalk")
        o.way([(100, 120), (112, 132)], highway="footway")             # ~17 m, both ends on sidewalks: hidden
        o.way([(200, 140), (200, 125)], highway="footway")             # 15 m stub from the sidewalk: hidden
        o.way([(250, 140), (250, 125)], highway="footway", name="Gang")  # named: stays drawn
        o.way([(300, 140), (300, 100)], highway="footway")             # 40 m: a real path, stays drawn
        o.way([(350, 135), (350, 150)], highway="footway")             # short, but touches the street: stays drawn
        o.way([(112, 132), (100, 140)], highway="footway", footway="crossing")
        o.way([(380, 150), (380, 160)], highway="footway", footway="traffic_island")  # hidden like a sidewalk
        # T junction (Hilden, Hegelstraße): a 20 m stub from the sidewalk, and a 40 m path ends in its middle – the
        # stub stays drawn, else the path hangs loose without a link
        o.way([(150, 140), (150, 130), (150, 120)], highway="footway")
        o.way([(150, 130), (190, 130)], highway="footway")
        cls.tmp = tempfile.TemporaryDirectory()
        cls.layers = run_extract(o, cls.tmp.name)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_street_areas(self):
        got = []
        for f in self.layers["areas"]:
            ring = f["geometry"]["coordinates"][0]
            x0 = min(round((p[0] - LON0) / M_LON) for p in ring)
            got.append((x0, f["properties"]["a"], f["tippecanoe"]))
        z = {"minzoom": 14, "maxzoom": 14}
        self.assertEqual(sorted(got), [(10, "foot", z), (40, "island", z), (70, "square", z), (110, "bridge", z),
                                       (130, "platform", z)])

    def test_sidewalk_fragment_hidden(self):
        def k_at(x, y):
            lon, lat = at(x, y)
            for f in self.layers["paths"]:
                c = f["geometry"]["coordinates"]
                if f["tippecanoe"]["minzoom"] == 13 and any(abs(p[0] - lon) < 2e-6 and abs(p[1] - lat) < 2e-6
                                                           for p in (c[0], c[-1])) and "footway" not in f["properties"]:
                    if f["properties"].get("sub") == "footway" and f["properties"]["k"] != "l":
                        return f["properties"]["k"], f["properties"].get("sac")
            return None
        self.assertEqual(k_at(112, 132), ("x", None))    # sidewalk fragment: like a sidewalk
        self.assertEqual(k_at(200, 125), ("x", None))    # short stub
        self.assertEqual(k_at(250, 125), ("p", 0))       # named
        self.assertEqual(k_at(300, 100), ("p", 0))       # long enough to be a path
        self.assertEqual(k_at(350, 135), ("p", 0))       # touches a drawn way
        self.assertEqual(k_at(380, 160), ("x", None))    # footway=traffic_island
        self.assertEqual(k_at(150, 120), ("p", 0))       # a path ends in its middle (T junction)
        self.assertEqual(k_at(190, 130), ("p", 0))       # that path

    def test_hidden_footways_say_what_they_are(self):
        fw = sorted({f["properties"]["fw"] for f in self.layers["paths"] if "fw" in f["properties"]})
        self.assertEqual(fw, ["c", "s", "t"])
        links = [f for f in self.layers["paths"] if f["properties"].get("k") == "l"]
        lon, lat = at(100, 120)
        self.assertFalse(any(abs(p[0] - lon) < 2e-6 and abs(p[1] - lat) < 2e-6
                             for f in links for p in f["geometry"]["coordinates"]), links)


@unittest.skipUnless(HAVE_LIBS and HAVE_TILES, "pyosmium / shapely / tippecanoe / pmtiles not installed")
class EmptyOverlays(unittest.TestCase):
    """Germany pre-build 2026-10-30: sea cells (10-532-329 north of the East Frisian islands) and cells cut from the
    wrong extract failed in overlays-tiles ("trails.ndjson missing or empty") or overlays-own (pmtiles IndexError: the
    cell owns none of the tiles, all data lies in the 5 km buffer). Empty overlays must give a valid, empty file."""

    def tiles(self, path):
        from pmtiles.reader import MmapSource, Reader, all_tiles
        with open(path, "rb") as f:
            get = MmapSource(f)
            r = Reader(get)
            return r.header(), r.metadata(), [t for t, _ in all_tiles(get)]

    def build(self, osm, tmp):
        run_extract(osm, tmp)
        out = os.path.join(tmp, "overlays.pmtiles")
        res = subprocess.run([os.path.join(HERE, "build_overlays.sh"), os.path.join(tmp, "tiles"), out],
                             capture_output=True, text=True)
        self.assertEqual(res.returncode, 0, res.stdout + res.stderr)
        return out, res.stdout

    def own(self, src, tmp, z, x, y):
        dst = os.path.join(tmp, "own.pmtiles")
        res = subprocess.run([sys.executable, os.path.join(HERE, "cells", "cells.py"), "tiles", str(z), str(x), str(y),
                              src, dst], capture_output=True, text=True)
        self.assertEqual(res.returncode, 0, res.stdout + res.stderr)
        return dst

    def test_no_feature_at_all(self):
        o = Osm()
        o.area(0, 0, 400, 300, natural="water")          # open sea: nothing for the overlays
        with tempfile.TemporaryDirectory() as tmp:
            out, log = self.build(o, tmp)
            self.assertIn("no overlay features at all", log)
            h, meta, tiles = self.tiles(out)
            self.assertEqual(tiles, [])
            self.assertEqual((h["tile_type"].name, h["tile_compression"].name), ("MVT", "GZIP"))  # as tippecanoe's
            self.assertEqual(meta["name"], "OnPath overlays")
            h, _, tiles = self.tiles(self.own(out, tmp, 10, 543, 364))
            self.assertEqual((tiles, h["addressed_tiles_count"]), ([], 0))

    def test_layers_without_features_left_out(self):
        o = Osm()
        o.way([(0, 0), (300, 0)], highway="path")         # a path, no trail / route / street
        with tempfile.TemporaryDirectory() as tmp:
            out, log = self.build(o, tmp)
            self.assertIn("trails: no features", log)
            _, meta, tiles = self.tiles(out)
            self.assertTrue(tiles)
            self.assertIn("paths", [layer["id"] for layer in meta["vector_layers"]])
            # the east neighbour owns none of these tiles (in a cell build: data only in the buffer): an empty file,
            # not the IndexError of pmtiles
            h, meta2, own = self.tiles(self.own(out, tmp, 10, 544, 364))
            self.assertEqual((own, h["addressed_tiles_count"], h["min_zoom"]), ([], 0, 10))
            self.assertEqual(meta2["name"], "OnPath overlays")
            # and the cell holding the path keeps its tiles as before
            _, _, mine = self.tiles(self.own(out, tmp, 10, 543, 364))
            self.assertTrue(mine and all(z >= 10 for z, _, _ in mine))


if __name__ == "__main__":
    unittest.main()
