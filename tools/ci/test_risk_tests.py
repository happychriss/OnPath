import unittest, risk_tests as r

class RiskTests(unittest.TestCase):
    def test_core_always(self):
        self.assertEqual(r.select(["docs/backlog.md"]), sorted(r.CORE))
    def test_changed_package_added(self):
        self.assertIn("search", r.select(["app/src/main/java/com/onpath/app/search/OnlineSearch.kt"]))
    def test_texts_run_the_ui_tests(self):
        self.assertIn("ui", r.select(["strings/src/commonMain/composeResources/values/strings_plan.xml"]))
    def test_build_files_run_everything(self):
        self.assertIsNone(r.select(["app/build.gradle.kts"]))
        self.assertEqual(r.gradle_args(["app/libs/x.jar"]), "")
    def test_root_package_runs_everything(self):
        self.assertIsNone(r.select(["app/src/main/java/com/onpath/app/MainActivity.kt"]))
    def test_nothing_to_compare_runs_everything(self):
        self.assertIsNone(r.select([]))
    def test_args(self):
        self.assertEqual(r.gradle_args(["app/src/main/java/com/onpath/app/map/X.kt"]).count("--tests"), len(r.CORE) + 1)

    def test_btools_tests_are_selected(self):
        self.assertIn("btools", r.select(["app/src/test/java/btools/router/OnPathHintsTest.java"]))
        self.assertIn("--tests 'btools.*'", r.gradle_args(["app/src/main/java/btools/router/X.java"]))

    # gate.sh (local agent gate)
    SHOTS = {"ui", "search"}
    def test_gate_docs_only_skips_gradle(self):
        g = r.gate(["docs/backlog.md", "CLAUDE.md", "web/x.ts"], self.SHOTS)
        self.assertEqual((g["mode"], g["paparazzi"], g["release"]), ("skip", 0, 0))
    def test_gate_no_changes_skips(self):
        self.assertEqual(r.gate([], self.SHOTS)["mode"], "skip")
    def test_gate_app_change_outside_ui_no_screenshots(self):
        g = r.gate(["app/src/main/java/com/onpath/app/routing/Planner.kt"], self.SHOTS)
        self.assertEqual((g["mode"], g["paparazzi"]), ("select", 0))
        self.assertIn("com.onpath.app.routing.*", g["patterns"].split())
        self.assertIn("*RuleTest", g["patterns"].split())
        self.assertNotIn("com.onpath.app.ui.*", g["patterns"].split())
    def test_gate_ui_texts_snapshots_run_screenshots(self):
        for f in ["app/src/main/java/com/onpath/app/ui/Theme.kt", "strings/src/commonMain/composeResources/values/strings_plan.xml",
                  "app/src/test/snapshots/images/x.png", "app/src/main/java/com/onpath/app/search/SearchSheet.kt"]:
            self.assertEqual(r.gate([f], self.SHOTS)["paparazzi"], 1, f)
    def test_gate_build_files_run_everything(self):
        g = r.gate(["gradle.properties"], self.SHOTS)
        self.assertEqual((g["mode"], g["paparazzi"]), ("full", 1))
    def test_gate_version_line_only_is_a_release(self):
        g = r.gate([r.VERSION_ONLY, "docs/release-notes/v1.2.3.md", "docs/project-status.md"], self.SHOTS)
        self.assertEqual((g["mode"], g["release"], g["paparazzi"]), ("select", 1, 0))
        self.assertIn("com.onpath.app.update.*", g["patterns"].split())
    def test_gate_release_with_code_is_not_release_only(self):
        self.assertEqual(r.gate([r.VERSION_ONLY, "app/src/main/java/com/onpath/app/map/X.kt"], self.SHOTS)["release"], 0)
    def test_gate_tools_read_by_tests(self):
        self.assertIn("plansync", r.gate(["tools/sync/mailbox-server.mjs"], self.SHOTS)["why"])
        self.assertEqual(r.gate(["tools/ci/gate.sh"], self.SHOTS)["python"], 1)
    def test_screenshot_packages_found_in_repo(self):
        self.assertIn("ui", r.screenshot_packages())

if __name__ == "__main__":
    unittest.main()
