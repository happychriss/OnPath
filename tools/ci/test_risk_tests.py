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

if __name__ == "__main__":
    unittest.main()
