#!/usr/bin/env python3
"""Markdown summary of an Android lint XML report for the CI job summary (unit.yml, job "lint").

Lint leaves out what app/lint-baseline.xml lists, so this shows only NEW findings: a count per check and the first
places. Never fails (exit 0) – lint only reports for now (owner).

Usage: lint_summary.py app/build/reports/lint-results-debug.xml >> "$GITHUB_STEP_SUMMARY"
"""
import collections
import os
import sys
import xml.etree.ElementTree as ET

MAX_PLACES = 30


def summary(path):
    if not os.path.isfile(path):
        return "## Lint\n\nNo lint report – lint did not finish (see the step log).\n"
    try:
        # LintBaseline = lint's own note "N findings filtered out by the baseline" – not a finding
        issues = [i for i in ET.parse(path).getroot().findall("issue") if i.get("id") != "LintBaseline"]
    except ET.ParseError as e:
        return f"## Lint\n\nLint report unreadable: {e}\n"
    if not issues:
        return "## Lint\n\nNo new findings (known ones are in app/lint-baseline.xml).\n"
    by_id = collections.Counter((i.get("id"), i.get("severity")) for i in issues)
    out = [f"## Lint: {len(issues)} new finding(s)", "", "Not in app/lint-baseline.xml. Full report: artifact lint-report.", "",
           "| Check | Severity | Count |", "| --- | --- | --- |"]
    out += [f"| {i} | {s} | {n} |" for (i, s), n in by_id.most_common()]
    out += ["", "| Where | Check | Message |", "| --- | --- | --- |"]
    for i in issues[:MAX_PLACES]:
        loc = i.find("location")
        where = ""
        if loc is not None:
            f = loc.get("file", "")
            f = f.split("/app/", 1)[-1] if "/app/" in f else f
            where = f"{f}:{loc.get('line', '')}"
        msg = (i.get("message") or "").replace("|", "\\|").replace("\n", " ")
        out.append(f"| {where} | {i.get('id')} | {msg} |")
    if len(issues) > MAX_PLACES:
        out.append(f"\n… and {len(issues) - MAX_PLACES} more in the report.")
    return "\n".join(out) + "\n"


if __name__ == "__main__":
    print(summary(sys.argv[1] if len(sys.argv) > 1 else "app/build/reports/lint-results-debug.xml"))
