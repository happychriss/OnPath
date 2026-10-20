"""Offline tests for the cloud builder helpers: cd tools/cloud && python3 -m unittest -v test_cloud"""
import datetime
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import job_build  # noqa: E402
import r2  # noqa: E402


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
        for bad in ("11-1-1", "10-1024-1", "abc"):
            with self.assertRaises(ValueError):
                job_build.parse_cells(bad)

    def test_pick_source(self):
        with open(os.path.join(os.path.dirname(__file__), "sources.json")) as f:
            src = json.load(f)
        self.assertEqual(job_build.pick_source(src, 10, 543, 361)["id"], "trentino_alto_adige")  # Meran
        self.assertIsNone(job_build.pick_source(src, 10, 536, 360))                            # Swiss side


FAKE_BUILD = r"""#!/usr/bin/env bash
# fake build_cell.sh: Z X Y → $DATA_ROOT/cells/f$FORMAT/Z/X/Y/<date>/ + current.json
set -e
echo "[00:00:00] ==> cut"; echo "[00:00:01] ==> base"
[ "$3" = 999 ] && { echo "ERROR: step base failed (log: x)"; exit 1; }
D="$DATA_ROOT/cells/f$FORMAT/$1/$2/$3"; V="$D/2026-10-19"; mkdir -p "$V"
printf 'tiles' > "$V/base.pmtiles"; printf '{}' > "$V/build.json"
cat > "$V/manifest.json" <<EOF
{"cell": "$1-$2-$3", "osm_date": "2026-10-19", "total_size": 5,
 "files": [{"name": "base.pmtiles", "size": 5, "sha256": "x", "url": "cells/f1/$1/$2/$3/2026-10-19/base.pmtiles"}]}
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
        os.makedirs(os.path.join(t, "src"))
        with open(os.path.join(t, "sources.json"), "w") as f:
            json.dump({"max_age_days": 30, "sources": [
                {"id": "test", "url": "https://example.invalid/test.osm.pbf", "cover": [[-180, -86, 180, 86]]}]}, f)
        self.env = {"PATH": os.environ["PATH"], "SRC_DIR": os.path.join(t, "src"), "WORK": os.path.join(t, "work"),
                    "DATA_ROOT": os.path.join(t, "store"), "R2_LOCAL_DIR": os.path.join(t, "r2"),
                    "SOURCES_JSON": os.path.join(t, "sources.json"), "BUILD_CELL": self.fake,
                    "CLOUD_RUN_EXECUTION": "onpath-cell-test-abc12", "FORMAT": "1"}
        self.downloads = []

    def tearDown(self):
        self.tmp.cleanup()

    def download(self, url, dest):
        self.downloads.append(url)
        with open(dest, "wb") as f:
            f.write(b"\0" * 1_100_000)

    def builder(self):
        return job_build.Builder(self.env, download=self.download)

    def status(self, cid):
        with open(os.path.join(self.tmp.name, "r2", "status", "f1", f"{cid}.json")) as f:
            return json.load(f)

    def test_builds_uploads_and_reports(self):
        b = self.builder()
        self.assertEqual(b.main([(10, 543, 361), (10, 544, 361)]), 0)
        r = os.path.join(self.tmp.name, "r2", "cells", "f1", "10", "543", "361")
        self.assertEqual(json.load(open(os.path.join(r, "current.json"))), {"version": "2026-10-19"})
        self.assertTrue(os.path.exists(os.path.join(r, "2026-10-19", "base.pmtiles")))
        self.assertTrue(os.path.exists(os.path.join(r, "2026-10-19", "manifest.json")))
        s = self.status("10-543-361")
        self.assertEqual((s["state"], s["version"], s["size"], s["execution"]),
                         ("ready", "2026-10-19", 5, "onpath-cell-test-abc12"))
        # one download for both cells, saved to the bucket for the next execution
        self.assertEqual(len(self.downloads), 1)
        self.assertTrue(os.path.exists(os.path.join(self.tmp.name, "src", "osm", "test.osm.pbf")))
        # local output removed after upload (Cloud Run disk = memory)
        self.assertFalse(os.path.exists(os.path.join(self.env["DATA_ROOT"], "cells", "f1", "10", "543", "361",
                                                     "2026-10-19")))

    def test_fresh_source_from_bucket_is_not_downloaded(self):
        os.makedirs(os.path.join(self.env["SRC_DIR"], "osm"))
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
        self.env["SOURCES_JSON"] = os.path.join(os.path.dirname(__file__), "sources.json")
        self.builder().main([(10, 0, 0)])
        s = self.status("10-0-0")
        self.assertEqual((s["state"], s["reason"], s["permanent"]), ("failed", job_build.NO_SOURCE, True))

    def test_retry_skips_cells_already_published(self):
        self.builder().main([(10, 543, 361)])
        calls = []

        def popen(*a, **kw):
            calls.append(a)
            return subprocess.Popen(*a, **kw)
        b = job_build.Builder(self.env, download=self.download, run=popen)
        b.main([(10, 543, 361)])
        self.assertEqual(calls, [])


if __name__ == "__main__":
    unittest.main()
