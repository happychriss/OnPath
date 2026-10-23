"""Offline tests for the POI placement priority: cd tools && python3 -m unittest -v test_extract_pois
(no pyosmium / shapely needed – stubbed, only the pure helpers are tested)."""
import os
import sys
import types
import unittest

for name in ("osmium", "shapely", "shapely.geometry"):
    if name not in sys.modules:
        try:
            __import__(name)
        except ImportError:
            m = types.ModuleType(name)
            m.SimpleHandler = object
            m.LineString = m.Polygon = None
            sys.modules[name] = m

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import extract_pois as ep  # noqa: E402


def feat(lon, lat, **tags):
    return {"type": "Feature", "geometry": {"type": "Point", "coordinates": [lon, lat]},
            "properties": ep.props("n", 1, "restaurant", tags)}


class Priority(unittest.TestCase):
    def test_range(self):
        bare = ep.priority(ep.importance({}), 11.16, 46.67)
        rich = {f"k{i}": "v" for i in range(80)}
        rich.update(wikipedia="de:X", website="x", opening_hours="Mo", phone="1", cuisine="pizza")
        top = ep.priority(ep.importance(rich), 11.16, 46.67)
        self.assertGreaterEqual(bare, 0)
        self.assertLess(top, ep.PR_LEVELS * ep.PR_TIE)
        self.assertLess(top, 50000)  # make_style.POI_SORT_UNNAMED

    def test_importance_order(self):
        bare = ep.importance({"amenity": "restaurant", "name": "A"})
        hours = ep.importance({"amenity": "restaurant", "name": "A", "opening_hours": "Mo-Su 11:00-22:00"})
        wiki = ep.importance({"amenity": "restaurant", "name": "A", "wikidata": "Q1"})
        self.assertLess(bare, hours)
        self.assertLess(hours, wiki)
        # the importance level always outranks the tie-break
        self.assertLess(ep.priority(bare, 11.0, 46.0) // ep.PR_TIE, ep.priority(hours, 11.0, 46.0) // ep.PR_TIE)
        self.assertLess(max(ep.priority(bare, 11 + i / 1e4, 46.0) for i in range(200)),
                        min(ep.priority(hours, 11 + i / 1e4, 46.0) for i in range(200)))

    def test_deterministic_and_spread(self):
        a = ep.priority(5, 11.16251, 46.67012)
        self.assertEqual(a, ep.priority(5, 11.16251, 46.67012))
        # same position as a float with more digits (rounded in the output) gives the same priority
        self.assertEqual(a, ep.priority(5, 11.162512, 46.670118))
        ties = {ep.priority(5, 11.16 + i * 1e-5, 46.67) for i in range(100)}
        self.assertGreater(len(ties), 95)  # neighbours of equal importance almost never tie

    def test_add_priorities_after_merge(self):
        fs = [feat(11.16251, 46.67012, name="Bus", highway="bus_stop"),
              feat(11.16261, 46.67022, name="Bus", highway="bus_stop", shelter="yes")]
        for f in fs:
            f["properties"]["kind"] = "bus"
        merged, n = ep.merge_duplicates(fs)
        ep.add_priorities(merged)
        self.assertEqual(n, 1)
        for f in merged:
            lon, lat = f["geometry"]["coordinates"]
            pr = f["properties"]["pr"]
            self.assertIsInstance(pr, int)
            self.assertEqual(pr % ep.PR_TIE, ep.priority(0, lon, lat))


if __name__ == "__main__":
    unittest.main()
