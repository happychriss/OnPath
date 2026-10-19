"""Offline tests for the Hetzner map server: region grid / store, Geofabrik source choice, setup-script safety.

  cd tools/hetzner && python3 -m unittest -v test_hetzner
"""
import json
import os
import sys
import tempfile
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "server"))
sys.path.insert(0, HERE)

from onpath_maps import regions, sources  # noqa: E402
import onpath_hetzner as hz  # noqa: E402


class RegionTest(unittest.TestCase):
    def test_bbox_is_square_in_km(self):
        b = regions.bbox_for(46.54, 10.99, 100)
        self.assertAlmostEqual((b[3] - b[1]) * 110.574, 100, delta=0.5)
        self.assertAlmostEqual((b[2] - b[0]) * 111.320 * 0.688, 100, delta=1.5)

    def test_names_are_valid_region_names(self):
        n = regions.region_name(46.5, 11.0, 200)
        self.assertEqual(n, "n46p50-e011p00-200km")
        self.assertRegex(n, r"^[a-z0-9][a-z0-9_-]*$")
        self.assertEqual(regions.region_name(-33.25, -70.5, 100), "s33p25-w070p50-100km")

    def test_request_reuses_and_queues(self):
        with tempfile.TemporaryDirectory() as d:
            s = regions.Store(d)
            r, reused = s.request(46.54, 10.99, 100, now=1)
            self.assertFalse(reused)
            self.assertEqual(r["status"], "queued")
            self.assertTrue(regions.contains(r["bbox"], regions.bbox_for(46.54, 10.99, 100)))
            r2, reused2 = s.request(46.56, 11.02, 100, now=2)  # same grid cell, still queued
            self.assertTrue(reused2)
            self.assertEqual(r2["name"], r["name"])
            s.update(r["name"], status="ready")
            r3, reused3 = s.request(46.60, 11.05, 60, now=3)  # smaller box inside the ready one
            self.assertTrue(reused3)
            self.assertEqual(r3["name"], r["name"])
            r4, reused4 = s.request(47.30, 11.40, 100, now=4)  # elsewhere: new
            self.assertFalse(reused4)
            self.assertEqual(s.next_queued()["name"], r4["name"])

    def test_limits(self):
        with tempfile.TemporaryDirectory() as d:
            s = regions.Store(d)
            for bad in ((95, 10, 100), (46, 10, 5), (46, 10, 900)):
                with self.assertRaises(ValueError):
                    s.request(*bad)

    def test_broken_state_file_is_skipped(self):
        with tempfile.TemporaryDirectory() as d:
            open(os.path.join(d, "x.json"), "w").write("{broken")
            self.assertEqual(regions.Store(d).all(), [])


def feat(fid, parent, poly, iso=None):
    p = {"id": fid, "parent": parent, "urls": {"pbf": f"https://example/{fid}.osm.pbf"}}
    if iso:
        p["iso3166-1:alpha2"] = [iso]
    (x0, y0, x1, y1) = poly
    return {"type": "Feature", "properties": p,
            "geometry": {"type": "Polygon", "coordinates": [[[x0, y0], [x1, y0], [x1, y1], [x0, y1], [x0, y0]]]}}


INDEX = [
    feat("europe", None, (-30, 30, 50, 75)),
    feat("italy", "europe", (6, 36, 19, 47.1), "IT"),
    feat("italy/nord-est", "italy", (10, 44, 14, 47.1)),
    feat("italy/nord-ovest", "italy", (6, 43, 10.5, 47)),
    feat("austria", "europe", (9.5, 46.3, 17.2, 49.1), "AT"),
    feat("switzerland", "europe", (5.9, 45.8, 10.5, 47.9), "CH"),
    feat("europe/alps", "europe", (5, 43, 17, 49)),  # special overlapping extract: never used
]


class SourceTest(unittest.TestCase):
    def test_leaves_with_country_cover_the_box(self):
        got = [i for i, _ in sources.select(INDEX, regions.bbox_for(46.54, 10.99, 100))]
        self.assertEqual(got, ["austria", "italy/nord-est", "italy/nord-ovest", "switzerland"])

    def test_inside_one_leaf(self):
        got = [i for i, _ in sources.select(INDEX, (12.0, 45.5, 12.5, 45.9))]
        self.assertEqual(got, ["italy/nord-est"])

    def test_sea_falls_back_to_smallest_container(self):
        got = [i for i, _ in sources.select(INDEX, (-20, 40, -19, 41))]
        self.assertEqual(got, ["europe"])


class SetupSafetyTest(unittest.TestCase):
    def test_no_write_without_permission(self):
        os.environ.pop("ONPATH_HETZNER_ALLOW_WRITE", None)
        with self.assertRaises(SystemExit):
            hz.Api("t").call("POST", "/servers", {})

    def test_foreign_resource_with_our_name_stops(self):
        inv = {"servers": [{"name": "onpath-maps", "labels": {}}], "firewalls": [], "ssh_keys": []}
        self.assertTrue(hz.conflicts(inv))
        inv["servers"][0]["labels"] = {"app": "onpath-maps"}
        self.assertFalse(hz.conflicts(inv))

    def test_type_choice_uses_location_availability(self):
        def st(name, mem, disk, eur, avail):
            return {"id": hash(name), "name": name, "memory": mem, "disk": disk, "cores": 4, "deprecation": None,
                    "prices": [{"location": "nbg1", "price_monthly": {"gross": str(eur)}}],
                    "locations": [{"name": "nbg1", "available": avail, "deprecation": None}]}
        api = mock.Mock()
        api.list.return_value = [st("small", 4, 80, 5, True), st("cheap8", 8, 80, 9, False),
                                 st("ok8", 8, 160, 12, True), st("big", 16, 240, 25, True)]
        t, loc, eur = hz.choose_type(api, None, ["nbg1"])
        self.assertEqual((t["name"], loc, eur), ("ok8", "nbg1", 12.0))

    def test_user_data_clones_and_installs(self):
        ud = hz.user_data("")
        self.assertTrue(ud.startswith("#cloud-config"))
        self.assertIn("tools/hetzner/server/install.sh", ud)
        self.assertIn("--domain maps.x", hz.user_data("maps.x"))

    def test_fingerprint(self):
        pub = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl test"
        self.assertRegex(hz.fingerprint(pub), r"^([0-9a-f]{2}:){15}[0-9a-f]{2}$")


if __name__ == "__main__":
    unittest.main()
