#!/usr/bin/env python3
"""Risk-based unit tests for a release (owner: the full suite takes too long for every release).

Prints the Gradle arguments for `testDebugUnitTest`:
- always the core-feature packages (robust first – never lose a track): recording, navigation, data, gpx, backup, plansync, sync;
- plus every app package whose main or test code changed since the last release tag;
- ui/ or the texts (strings/) changed → the ui tests (with the screenshot tests);
- build files, BRouter jars / profiles, or nothing to compare with → "" = the full suite.
The full suite still runs after every push to main (unit.yml), beside the release.

Usage: risk_tests.py <changed file> …  (one path per argument, relative to the repo root)
"""
import re, sys

CORE = ["recording", "navigation", "data", "gpx", "backup", "plansync", "sync"]
FULL_TRIGGERS = (
    "app/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradle/",
    "app/libs/", "app/src/main/assets/brouter/", "strings/build.gradle.kts", "app/src/test/resources/",
)
PKG = re.compile(r"^app/src/(?:main|test)/java/com/onpath/app/([a-z0-9_]+)/")


def select(changed):
    if not changed:
        return None  # nothing to compare with: everything
    pkgs = set(CORE)
    for f in changed:
        if f.startswith(FULL_TRIGGERS):
            return None
        m = PKG.match(f)
        if m:
            pkgs.add(m.group(1))
        elif f.startswith("app/src/main/java/com/onpath/app/") or f.startswith("app/src/test/java/com/onpath/app/"):
            return None  # a file in the root package (MainActivity, OnPathApp …): everything
        elif f.startswith("strings/") or f.startswith("app/src/test/snapshots/") or f.startswith("app/src/main/res/"):
            pkgs.add("ui")
        elif f.startswith("app/src/main/java/btools/"):
            pkgs.add("routing")
        elif f.startswith("app/src/main/assets/"):
            pkgs.update(["map", "ui"])
    return sorted(pkgs)


def gradle_args(changed):
    pkgs = select(changed)
    return "" if pkgs is None else " ".join(f"--tests 'com.onpath.app.{p}.*'" for p in pkgs)


if __name__ == "__main__":
    print(gradle_args([a for a in sys.argv[1:] if a.strip()]))
