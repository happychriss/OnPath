#!/usr/bin/env python3
"""Tests of vm_forwarders.py: python3 tools/dev/test_vm_forwarders.py (stdlib unittest, reads no repo file)."""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import vm_forwarders as v

VM = """package x

class AppViewModel(app: Application) {
    val planEdit = PlanEditSlice()
    fun undoStep() = planEdit.undoStep()
    fun clearPlan() {
        if (guard()) return
        planEdit.clearPlan()
    }
    fun redoStep(extra: Int) = planEdit.redoStep()
}
"""


class PersistedConflicts(unittest.TestCase):
    def table(self):
        t, _, members, _ = v.build_table(VM.split("\n"))
        return t, members

    def test_an_exact_pass_through_is_not_a_conflict(self):
        t, members = self.table()
        self.assertIn("undoStep", t)
        persisted = {"undoStep": ("planEdit", ["undoStep"], "fun", False)}
        self.assertEqual([], v.persisted_conflicts(persisted, t, members))

    def test_a_member_that_got_a_guard_is_a_conflict(self):
        t, members = self.table()
        persisted = {"clearPlan": ("planEdit", ["clearPlan"], "fun", False)}
        self.assertEqual(["clearPlan"], v.persisted_conflicts(persisted, t, members))

    def test_a_member_that_changed_its_arguments_is_a_conflict(self):
        t, members = self.table()
        persisted = {"redoStep": ("planEdit", ["redoStep"], "fun", False)}
        self.assertEqual(["redoStep"], v.persisted_conflicts(persisted, t, members))

    def test_a_name_that_is_gone_or_kept_is_no_conflict(self):
        t, members = self.table()
        persisted = {"longGone": ("planEdit", ["x"], "fun", False), "mode": ("tourSession", ["mode"], "val", False)}
        self.assertEqual([], v.persisted_conflicts(persisted, t, members))


class ReceiverCheck(unittest.TestCase):
    def test_view_model_receivers_pass(self):
        for before in ("    vm.", "x = vm(it).", "this@AppViewModel.", "    ", "(vm."):
            self.assertTrue(v.receiver_ok("", before), before)

    def test_a_read_block_parameter_passes(self):
        self.assertTrue(v.receiver_ok("waitUntil { 0 in read { it.", "waitUntil { 0 in read { it."))
        self.assertTrue(v.receiver_ok("onVm { v -> v.", "onVm { v -> v."))

    def test_another_object_is_refused(self):
        line = "vm(it).routes.tours.filter { t -> t.draft && t."
        self.assertFalse(v.receiver_ok(line, line))
        self.assertFalse(v.receiver_ok("tour.", "tour."))


if __name__ == "__main__":
    unittest.main()
