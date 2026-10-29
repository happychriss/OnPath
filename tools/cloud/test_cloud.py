"""Offline tests for the cloud builder helpers: cd tools/cloud && python3 -m unittest -v test_cloud"""
import datetime
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import job_build  # noqa: E402
import osm_sources  # noqa: E402
import r2  # noqa: E402
import src_objects  # noqa: E402
from cells import buffered_bbox, cell_bbox  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
INDEX = os.path.join(HERE, "testdata", "geofabrik-index-subset.json")


class SigV4(unittest.TestCase):
    def test_signing_key_aws_example(self):
        # AWS docs "Examples of how to derive a signing key"
        k = r2.signing_key("wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", "20120215", "us-east-1", "iam")
        self.assertEqual(k.hex(), "f4780e2d9f65fa895f9c67b32ce1baf0b0d8a43505a000a1a9e090d414db404d")

    def test_s3_get_object_aws_example(self):
        # AWS S3 docs "Signature Calculations for the Authorization Header" – GET Object example
        h = r2.sign("GET", "https://examplebucket.s3.amazonaws.com/test.txt", {"Range": "bytes=0-9"},
                    r2.EMPTY_SHA256, "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                    region="us-east-1", now=datetime.datetime(2013, 5, 24, tzinfo=datetime.timezone.utc))
        self.assertIn("SignedHeaders=host;range;x-amz-content-sha256;x-amz-date", h["authorization"])
        self.assertTrue(h["authorization"].endswith(
            "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"), h["authorization"])

    def test_endpoint_eu_jurisdiction_by_default(self):
        # our buckets were created with EU jurisdiction: only the .eu. endpoint reaches them
        self.assertEqual(r2.endpoint_from_env({"R2_ACCOUNT_ID": "abc123"}),
                         "https://abc123.eu.r2.cloudflarestorage.com")
        self.assertEqual(r2.endpoint_from_env({"R2_ACCOUNT_ID": "abc123", "R2_JURISDICTION": "default"}),
                         "https://abc123.r2.cloudflarestorage.com")
        self.assertEqual(r2.endpoint_from_env({"R2_ACCOUNT_ID": "x", "R2_ENDPOINT": "http://localhost:9000/"}),
                         "http://localhost:9000")
        s = r2.from_env({"R2_ACCOUNT_ID": "abc123", "R2_BUCKET": "onpath-maps-test",
                         "R2_ACCESS_KEY_ID": "k", "R2_SECRET_ACCESS_KEY": "s"})
        self.assertEqual(s._url("a/b.json"), "https://abc123.eu.r2.cloudflarestorage.com/onpath-maps-test/a/b.json")

    def test_wrangler_toml_buckets_are_eu(self):
        with open(os.path.join(HERE, "worker", "wrangler.toml")) as f:
            toml = f.read()
        for b in ("onpath-maps-test", "onpath-maps-production"):
            self.assertIn(f'bucket_name = "{b}"\njurisdiction = "eu"', toml)

    def test_local_store_refuses_escape(self):
        with tempfile.TemporaryDirectory() as d:
            s = r2.LocalStore(d)
            s.put_bytes("status/f1/a.json", b"{}")
            self.assertEqual(s.get_bytes("status/f1/a.json"), b"{}")
            self.assertIsNone(s.get_bytes("status/f1/missing.json"))
            with self.assertRaises(ValueError):
                s.put_bytes("../x", b"")


class Cells(unittest.TestCase):
    def test_parse(self):
        self.assertEqual(job_build.parse_cells("10-543-361, 10/544/361 10-543-361"), [(10, 543, 361), (10, 544, 361)])
        for bad in ("11-1-1", "10-1024-1", "abc", "7-128-1", "9-1-1"):
            with self.assertRaises(ValueError):
                job_build.parse_cells(bad)

    def test_parse_region_package(self):
        # region packages (z7, map-cells.md §20) go through the same job
        self.assertEqual(job_build.parse_cells("7-66-42 10-531-342 7/66/43"), [(7, 66, 42), (10, 531, 342), (7, 66, 43)])

    def test_package_routes_only_long_distance(self):
        import package_routes as pr
        self.assertEqual(pr.route_of({"type": "route", "route": "bicycle", "network": "icn", "ref": "EV15",
                                      "name": "Rheinradweg", "name:de": "Rheinradweg (DE)"}),
                         ("bike", 4, "EV15", "Rheinradweg (DE)"))
        self.assertEqual(pr.route_of({"type": "route", "route": "foot", "network": "nwn", "name": "Rheinsteig"})[:2],
                         ("hike", 3))
        for tags in ({"type": "route", "route": "bicycle", "network": "rcn"},      # regional: z11+ from the cells
                     {"type": "route", "route": "hiking", "network": "lwn"},
                     {"type": "route", "route": "bicycle", "network": "ncn", "network:type": "node_network"},
                     {"type": "route", "route": "mtb", "network": "ncn"},          # no MTB band in the style
                     {"type": "route", "route": "road", "network": "e-road"},
                     {"type": "multipolygon", "route": "bicycle", "network": "icn"}):
            self.assertIsNone(pr.route_of(tags), tags)

    def test_pinned_source(self):
        # the mechanism stays; sources.json pins nothing (owner: South Tyrol uses the automatic choice)
        with open(os.path.join(HERE, "sources.json")) as f:
            real = json.load(f)
        self.assertEqual(real["sources"], [])
        self.assertNotIn("max_downloads_per_day", real)
        self.assertIsNone(osm_sources.pick_pinned(real, cell_bbox(10, 543, 361)))
        src = {"sources": [{"id": "pin", "url": "u", "cover": [[10.55, 46.20, 11.95, 46.85]]}]}
        self.assertEqual(osm_sources.pick_pinned(src, cell_bbox(10, 543, 361))["id"], "pin")  # Meran
        self.assertIsNone(osm_sources.pick_pinned(src, cell_bbox(10, 536, 360)))             # Swiss side


class SourceSelection(unittest.TestCase):
    """Extract choice for cell + 5 km on a subset of the real Geofabrik index (testdata/, 2026-10-21)."""
    regions = osm_sources.load_index(INDEX)

    def ids(self, x, y):
        return [r.id for r in osm_sources.select(self.regions, buffered_bbox(cell_bbox(10, x, y), 5))]

    def test_index_flags(self):
        by = {r.id: r for r in self.regions}
        self.assertTrue(by["austria"].leaf and by["austria"].country)
        self.assertTrue(by["nord-est"].leaf and by["nord-est"].country)      # italy has the ISO code
        self.assertFalse(by["italy"].leaf)
        self.assertFalse(by["alps"].country)                                  # special extract, never a leaf choice
        self.assertTrue(by["us"].leaf)                                        # states' parent is north-america ...

    def test_inside_one_country_leaf(self):
        self.assertEqual(self.ids(544, 361), ["nord-est"])      # Bozen
        self.assertEqual(self.ids(545, 362), ["nord-est"])      # Dolomites
        self.assertEqual(self.ids(547, 358), ["austria"])       # Kitzbühel
        self.assertEqual(self.ids(558, 355), ["austria"])       # Vienna
        self.assertEqual(self.ids(536, 358), ["switzerland"])   # Zürich
        self.assertEqual(self.ids(534, 361), ["switzerland"])   # Grindelwald
        self.assertEqual(self.ids(539, 361), ["switzerland"])   # Davos
        self.assertEqual(self.ids(302, 377), ["us/new-york"])   # Albany: the state, not the whole "us"

    def test_border_cells_take_the_leaves_smallest_first(self):
        # documented choice: the country leaves the buffered cell touches, cut one by one and merged (no alps/dach)
        self.assertEqual(self.ids(543, 361), ["nord-est", "austria"])     # Meran: 5 km reach the Ötztal ridge
        self.assertEqual(self.ids(544, 359), ["nord-est", "austria"])     # Innsbruck: buffer reaches the Brenner
        self.assertEqual(self.ids(543, 360), ["nord-est", "austria"])     # Ötztal (Obergurgl)
        self.assertEqual(self.ids(539, 362), ["switzerland", "nord-ovest"])  # Engadin (St. Moritz)
        self.assertEqual(self.ids(544, 358), ["oberbayern", "austria"])   # north of Innsbruck
        self.assertEqual(self.ids(539, 359), ["liechtenstein", "switzerland", "austria"])
        self.assertEqual(self.ids(541, 361), ["switzerland", "nord-ovest", "nord-est", "austria"])  # Scuol: 4
        self.assertEqual(self.ids(301, 385), ["us/new-jersey", "us/new-york"])  # NYC: "us" is a container

    def test_sea(self):
        self.assertEqual(self.ids(398, 405), [])   # mid Atlantic
        self.assertEqual(self.ids(529, 393), [])   # Mediterranean between Corsica and the Balearics
        self.assertEqual(self.ids(0, 0), [])

    def test_too_many_leaves_falls_back_to_a_containing_leaf(self):
        box = buffered_bbox(cell_bbox(10, 541, 361), 5)
        self.assertEqual([r.id for r in osm_sources.select(self.regions, box, max_extracts=2)], ["alps"])

    def test_geometry(self):
        sq = {"properties": {"id": "sq", "urls": {"pbf": "u"}}, "geometry": {"type": "Polygon", "coordinates": [
            [[0, 0], [10, 0], [10, 10], [0, 10], [0, 0]], [[4, 4], [6, 4], [6, 6], [4, 6], [4, 4]]]}}
        r = osm_sources.Region(sq)
        self.assertTrue(r.contains_box((1, 1, 3, 3)))
        self.assertFalse(r.contains_box((3, 3, 5, 5)))       # overlaps the hole
        self.assertFalse(r.contains_box((5.5, 5.5, 5.8, 5.8)))  # inside the hole
        self.assertFalse(r.contains_box((9, 9, 11, 11)))
        self.assertTrue(r.intersects_box((9, 9, 11, 11)))
        self.assertTrue(r.intersects_box((-5, -5, 15, 15)))  # region inside the box
        self.assertFalse(r.intersects_box((11, 11, 12, 12)))


FAKE_BUILD = r"""#!/usr/bin/env bash
# fake build_cell.sh: Z X Y → $DATA_ROOT/cells/f$FORMAT/Z/X/Y/<date>/ + current.json
set -e
echo "[00:00:00] ==> cut"; echo "[00:00:01] ==> base"
[ "$3" = 999 ] && { echo "ERROR: step base failed (log: x)"; exit 1; }
D="$DATA_ROOT/cells/f$FORMAT/$1/$2/$3"; V="$D/2026-10-19"; mkdir -p "$V"
printf 'tiles' > "$V/base.pmtiles"; printf '{}' > "$V/build.json"
mkdir -p "$V/addresses"; printf "db" > "$V/addresses/$1-$2-$3.sqlite"
cat > "$V/manifest.json" <<EOF
{"cell": "$1-$2-$3", "osm_date": "2026-10-19", "total_size": 5,
 "files": [{"name": "base.pmtiles", "size": 5, "sha256": "x", "url": "cells/f2/$1/$2/$3/2026-10-19/base.pmtiles"}]}
EOF
echo '{"version": "2026-10-19"}' > "$D/current.json"
echo "[00:00:02] ==> manifest"
"""


class BuilderFlow(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        t = self.tmp.name
        self.fake = os.path.join(t, "build_cell.sh")
        with open(self.fake, "w") as f:
            f.write(FAKE_BUILD)
        os.makedirs(os.path.join(t, "src", "osm", "geofabrik"))
        shutil.copyfile(INDEX, os.path.join(t, "src", "osm", "geofabrik", "index-v1.json"))
        with open(os.path.join(t, "sources.json"), "w") as f:
            json.dump({"max_age_days": 30, "sources": [
                {"id": "test", "url": "https://example.invalid/test.osm.pbf", "cover": [[-180, -86, 180, 86]]}]}, f)
        self.env = {"PATH": os.environ["PATH"], "SRC_DIR": os.path.join(t, "src"), "WORK": os.path.join(t, "work"),
                    "DATA_ROOT": os.path.join(t, "store"), "R2_LOCAL_DIR": os.path.join(t, "r2"),
                    "SOURCES_JSON": os.path.join(t, "sources.json"), "BUILD_CELL": self.fake,
                    "CLOUD_RUN_EXECUTION": "onpath-cell-test-abc12", "FORMAT": "2"}
        self.downloads = []
        self.slept = []

    def tearDown(self):
        self.tmp.cleanup()

    def download(self, url, dest):
        self.downloads.append(url)
        with open(dest, "wb") as f:
            f.write(b"\0" * 1_100_000)

    def builder(self, **kw):
        return job_build.Builder(self.env, download=self.download, sleep=self.slept.append, **kw)

    def real_sources(self):
        self.env["SOURCES_JSON"] = os.path.join(HERE, "sources.json")

    def src(self, *p):
        return os.path.join(self.env["SRC_DIR"], "osm", *p)

    def status(self, cid):
        with open(os.path.join(self.tmp.name, "r2", "status", "f2", f"{cid}.json")) as f:
            return json.load(f)

    def test_builds_uploads_and_reports(self):
        b = self.builder()
        self.assertEqual(b.main([(10, 543, 361), (10, 544, 361)]), 0)
        r = os.path.join(self.tmp.name, "r2", "cells", "f2", "10", "543", "361")
        self.assertEqual(json.load(open(os.path.join(r, "current.json"))), {"version": "2026-10-19"})
        self.assertTrue(os.path.exists(os.path.join(r, "2026-10-19", "base.pmtiles")))
        self.assertTrue(os.path.exists(os.path.join(r, "2026-10-19", "manifest.json")))
        self.assertTrue(os.path.exists(os.path.join(r, "2026-10-19", "addresses", "10-543-361.sqlite")))  # sub folder
        s = self.status("10-543-361")
        self.assertEqual((s["state"], s["version"], s["size"], s["execution"]),
                         ("ready", "2026-10-19", 5, "onpath-cell-test-abc12"))
        # one download for both cells, saved to the bucket for the next execution
        self.assertEqual(len(self.downloads), 1)
        self.assertTrue(os.path.exists(os.path.join(self.tmp.name, "src", "osm", "test.osm.pbf")))
        # local output removed after upload (Cloud Run disk = memory)
        self.assertFalse(os.path.exists(os.path.join(self.env["DATA_ROOT"], "cells", "f2", "10", "543", "361",
                                                     "2026-10-19")))

    def test_fresh_source_from_bucket_is_not_downloaded(self):
        os.makedirs(os.path.join(self.env["SRC_DIR"], "osm"), exist_ok=True)
        with open(os.path.join(self.env["SRC_DIR"], "osm", "test.osm.pbf"), "wb") as f:
            f.write(b"\0" * 1_100_000)
        self.builder().main([(10, 543, 361)])
        self.assertEqual(self.downloads, [])
        self.assertEqual(self.status("10-543-361")["state"], "ready")

    def test_failed_step_is_reported_plainly(self):
        self.builder().main([(10, 543, 999)])
        s = self.status("10-543-999")
        self.assertEqual((s["state"], s["reason"]), ("failed", "Build step 'base' failed"))

    def test_no_source(self):
        self.real_sources()
        self.builder().main([(10, 0, 0)])
        s = self.status("10-0-0")
        self.assertEqual((s["state"], s["reason"], s["permanent"]), ("failed", job_build.NO_SOURCE, True))
        self.assertEqual(self.downloads, [])

    def test_choose_sources(self):
        self.real_sources()
        b = self.builder()
        # Meran: no pin – nord-est, plus austria where the 5 km buffer crosses the main ridge
        self.assertEqual([s["id"] for s in b.choose_sources(10, 543, 361)], ["nord-est", "austria"])
        self.assertEqual([s["id"] for s in b.choose_sources(10, 545, 362)], ["nord-est"])  # Bozen south
        got = b.choose_sources(10, 544, 359)  # Innsbruck
        self.assertEqual([s["url"] for s in got], ["https://download.geofabrik.de/europe/italy/nord-est-latest.osm.pbf",
                                                   "https://download.geofabrik.de/europe/austria-latest.osm.pbf"])
        self.assertEqual([s["path"] for s in got], [self.src("geofabrik", "europe", "italy", "nord-est.osm.pbf"),
                                                    self.src("geofabrik", "europe", "austria.osm.pbf")])

    def test_region_package_builds_and_reports_like_a_cell(self):
        self.assertEqual(self.builder().main([(7, 68, 44)]), 0)
        r = os.path.join(self.tmp.name, "r2", "cells", "f2", "7", "68", "44")
        self.assertEqual(json.load(open(os.path.join(r, "current.json"))), {"version": "2026-10-19"})
        self.assertEqual(self.status("7-68-44")["state"], "ready")

    def test_region_package_sources_cover_the_package_plus_10_km(self):
        self.real_sources()
        boxes = []
        real = osm_sources.select

        def spy(regions, box, *a, **kw):
            boxes.append(box)
            return real(regions, box, *a, **kw)
        osm_sources.select = spy
        try:
            got = self.builder().choose_sources(7, 68, 44)  # Innsbruck's package: Tyrol, Bavaria, South Tyrol
        finally:
            osm_sources.select = real
        self.assertEqual(boxes, [buffered_bbox(cell_bbox(7, 68, 44), job_build.PACKAGE_BUFFER_KM)])
        self.assertIn("austria", [s["id"] for s in got])

    def test_border_cell_downloads_both_and_passes_both(self):
        self.real_sources()
        envs = []

        def popen(*a, **kw):
            envs.append(kw["env"])
            return subprocess.Popen(*a, **kw)
        self.builder(run=popen).main([(10, 544, 359), (10, 547, 358)])  # Innsbruck (AT + IT), Kitzbühel (AT)
        self.assertEqual(self.status("10-544-359")["state"], "ready")
        self.assertEqual(self.status("10-544-359")["sources"], ["nord-est", "austria"])
        self.assertEqual(len(self.downloads), 2)  # austria once for both cells
        self.assertEqual(envs[0]["SRC_PBF"], self.src("geofabrik", "europe", "italy", "nord-est.osm.pbf") + " "
                         + self.src("geofabrik", "europe", "austria.osm.pbf"))
        self.assertEqual(envs[1]["SRC_PBF"], self.src("geofabrik", "europe", "austria.osm.pbf"))
        meta = json.load(open(self.src("geofabrik", "europe", "austria.meta.json")))
        self.assertEqual((meta["id"], meta["size"]), ("austria", 1_100_000))
        self.assertFalse(os.path.exists(self.src("geofabrik", "europe", "austria.osm.pbf.lock")))
        self.assertFalse(os.path.exists(self.src("downloads.json")))  # no daily download limit

    def test_old_extract_is_downloaded_again(self):
        self.real_sources()
        p = self.src("geofabrik", "europe", "austria.osm.pbf")
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "wb") as f:
            f.write(b"\0" * 1_100_000)
        with open(self.src("geofabrik", "europe", "austria.meta.json"), "w") as f:
            json.dump({"downloaded": 1}, f)
        self.builder().main([(10, 547, 358)])
        self.assertEqual(self.downloads, ["https://download.geofabrik.de/europe/austria-latest.osm.pbf"])
        with open(self.src("geofabrik", "europe", "austria.meta.json"), "w") as f:
            json.dump({"downloaded": int(time.time())}, f)
        self.downloads.clear()
        self.builder().main([(10, 547, 358)])
        self.assertEqual(self.downloads, [])

    def test_no_daily_download_limit(self):
        # owner: many downloads in the last day do not hold back a new area
        self.real_sources()
        with open(self.src("downloads.json"), "w") as f:
            json.dump({"downloads": [{"t": int(time.time()), "url": "x"}] * 50}, f)
        self.builder().main([(10, 547, 358)])
        s = self.status("10-547-358")
        self.assertEqual(s["state"], "ready")
        self.assertNotIn("defer", s)
        self.assertEqual(self.downloads, ["https://download.geofabrik.de/europe/austria-latest.osm.pbf"])

    def test_waits_for_another_jobs_download(self):
        self.real_sources()
        p = self.src("geofabrik", "europe", "austria.osm.pbf")
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p + ".lock", "w") as f:
            json.dump({"execution": "other", "t": int(time.time())}, f)

        def sleep(s):  # the other job finishes while we wait
            self.slept.append(s)
            with open(p, "wb") as f:
                f.write(b"\0" * 1_100_000)
            os.remove(p + ".lock")
        job_build.Builder(self.env, download=self.download, sleep=sleep).main([(10, 547, 358)])
        self.assertEqual(self.slept, [20])
        self.assertEqual(self.downloads, [])
        self.assertEqual(self.status("10-547-358")["state"], "ready")

    def test_stale_lock_is_taken_over(self):
        self.real_sources()
        p = self.src("geofabrik", "europe", "austria.osm.pbf")
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p + ".lock", "w") as f:
            json.dump({"execution": "crashed", "t": 1}, f)
        self.builder().main([(10, 547, 358)])
        self.assertEqual(len(self.downloads), 1)
        self.assertFalse(os.path.exists(p + ".lock"))

    def test_too_large_extract_is_refused_for_good(self):
        self.real_sources()

        def download(url, dest):
            raise job_build.SourceTooLarge("9000 MB")
        job_build.Builder(self.env, download=download, sleep=self.slept.append).main([(10, 547, 358)])
        s = self.status("10-547-358")
        self.assertEqual((s["state"], s["reason"], s["permanent"]), ("failed", job_build.TOO_LARGE, True))

    def test_download_error_is_retryable(self):
        self.real_sources()

        def download(url, dest):
            raise OSError("connection reset")
        job_build.Builder(self.env, download=download, sleep=self.slept.append).main([(10, 547, 358)])
        s = self.status("10-547-358")
        self.assertEqual((s["state"], s["reason"]), ("failed", job_build.UNAVAILABLE))
        self.assertNotIn("permanent", s)
        self.assertFalse(os.path.exists(self.src("geofabrik", "europe", "austria.osm.pbf.lock")))

    def test_retry_skips_cells_already_published(self):
        self.builder().main([(10, 543, 361)])
        calls = []

        def popen(*a, **kw):
            calls.append(a)
            return subprocess.Popen(*a, **kw)
        b = job_build.Builder(self.env, download=self.download, run=popen)
        b.main([(10, 543, 361)])
        self.assertEqual(calls, [])

    def test_ready_status_has_measured_times(self):
        self.real_sources()
        self.builder().main([(10, 547, 358)])  # austria downloaded for it
        s = self.status("10-547-358")
        self.assertTrue(s["new_source"])
        self.assertEqual(s["download_mb"], 1.1)
        self.assertGreaterEqual(s["build_s"], 0)
        self.assertIn("download_s", s)
        self.env["CLOUD_RUN_EXECUTION"] = "onpath-cell-test-next"
        self.builder().main([(10, 547, 358)])  # next execution: cached now
        self.assertFalse(self.status("10-547-358")["new_source"])

    def test_lock_held_by_a_parallel_execution_on_another_machine(self):
        # the lock is only in the bucket (the GCS API sees it), not in this machine's mount: still waits, no download
        self.real_sources()
        p = self.src("geofabrik", "europe", "austria.osm.pbf")
        os.makedirs(os.path.dirname(p), exist_ok=True)
        local = src_objects.FileObjects(self.env["SRC_DIR"])
        remote = {"osm/geofabrik/europe/austria.osm.pbf.lock":
                  json.dumps({"execution": "other", "t": int(time.time()), "eta_at": int(time.time()) + 50}).encode()}

        class Remote(src_objects.FileObjects):
            def create_if_absent(self, name, data):
                return False if name in remote else local.create_if_absent(name, data)

            def read(self, name):
                return remote.get(name) or local.read(name)

            def stat(self, name):
                return {"size": len(remote[name]), "mtime": time.time()} if name in remote else local.stat(name)

        statuses = []

        def sleep(s):  # the other execution finishes while we wait
            statuses.append(self.status("10-547-358"))
            with open(p, "wb") as f:
                f.write(b"\0" * 1_100_000)
            del remote["osm/geofabrik/europe/austria.osm.pbf.lock"]
        job_build.Builder(self.env, download=self.download, sleep=sleep, objects=Remote(self.env["SRC_DIR"])).main(
            [(10, 547, 358)])
        self.assertEqual(self.downloads, [])
        self.assertEqual(statuses[0]["step"], "download")
        self.assertTrue(45 <= statuses[0]["source_eta_s"] <= 50)  # the downloader's ETA from the lock
        s = self.status("10-547-358")
        self.assertEqual(s["state"], "ready")
        self.assertTrue(s["new_source"])  # waited for a fresh extract


class GcsLocks(unittest.TestCase):
    def test_create_if_absent_uses_generation_precondition(self):
        import urllib.error
        calls = []

        class Resp(io.BytesIO):
            status = 200

            def __enter__(self):
                return self

            def __exit__(self, *a):
                return False

        def opener(req, timeout=None):
            url = req.full_url if hasattr(req, "full_url") else req
            calls.append((req.get_method(), url))
            if "metadata.google.internal" in url:
                return Resp(json.dumps({"access_token": "t", "expires_in": 3600}).encode())
            if "ifGenerationMatch=0" in url and len(calls) > 2:
                raise urllib.error.HTTPError(url, 412, "precondition", {}, None)
            if url.endswith("?alt=media"):
                raise urllib.error.HTTPError(url, 404, "nf", {}, None)
            if "fields=size,updated" in url:
                return Resp(json.dumps({"size": "123", "updated": "2026-10-21T10:00:00.000Z"}).encode())
            return Resp(b"{}")
        g = src_objects.GcsObjects("bkt", opener=opener)
        self.assertTrue(g.create_if_absent("osm/a b.osm.pbf.lock", b"{}"))
        self.assertFalse(g.create_if_absent("osm/a b.osm.pbf.lock", b"{}"))  # someone else holds it
        self.assertIn("ifGenerationMatch=0", calls[1][1])
        self.assertIn("name=osm%2Fa%20b.osm.pbf.lock", calls[1][1])
        self.assertIsNone(g.read("osm/x"))
        st = g.stat("osm/x")
        self.assertEqual(st["size"], 123)
        self.assertEqual(datetime.datetime.fromtimestamp(st["mtime"], datetime.timezone.utc).hour, 10)
        self.assertEqual(sum(1 for c in calls if "metadata" in c[1]), 1)  # token cached

    def test_from_env(self):
        self.assertIsInstance(src_objects.from_env({"SRC_BUCKET": "b"}, "/src"), src_objects.GcsObjects)
        self.assertIsInstance(src_objects.from_env({}, "/src"), src_objects.FileObjects)
        self.assertIsInstance(src_objects.from_env({"SRC_BUCKET": "b", "SRC_LOCKS": "file"}, "/src"),
                              src_objects.FileObjects)


if __name__ == "__main__":
    unittest.main()
