---
paths:
  - "app/build.gradle.kts"
  - "docs/release-notes/**"
  - ".github/**"
  - "tools/ci/**"
  - "app/src/main/java/com/onpath/app/update/**"
---
# Releases, CI, the gate (loads when you touch the version, release notes, workflows or tools/ci)

## Releases (updates on the phone)
- Version: default `appVersion` in `app/build.gradle.kts` (versionCode = major*10000+minor*100+patch).
- To release: bump that version, add `docs/release-notes/vX.Y.Z.md`, push to `main`.
  `.github/workflows/release.yml` then tests, builds, signs (test key), creates tag `vX.Y.Z` and the
  GitHub Release with the APK – once per version (re-runs skip existing releases). The release runs
  **risk-based unit tests** (`tools/ci/risk_tests.py`: core-feature packages always + packages changed since the last
  release; build files → all) beside the emulator start test; the **full** unit suite runs after every push in
  `unit.yml` with the screenshot check (`verifyPaparazziDebug` against the committed images) and a lint job that only
  reports new findings (known ones in `app/lint-baseline.xml`) – not a gate; a red run is fixed at once, like E2E.
  Actions in the workflows are pinned to a commit (`# vX.Y.Z` beside it). Cloud sessions may not
  push tags or create releases themselves; this push-to-main flow avoids both. Manual: Actions → Release → Run.
- **Release-Rundgang is OPTIONAL** (owner 2026-10-31, "it takes too long"): a normal release = the unit + screenshot gate
  (`tools/ci/gate.sh`: nothing to run on a CI-green base) + GitHub's `StartSmokeE2ETest`. Run `tools/ci/release_rundgang.sh` (~6 min, own
  emulator; red = no release) only when the owner asks or when a release changes navigation / recording core logic. Nobody
  waits for the push E2E run. Flows: `docs/release-rundgang.md`; every field bug adds one.
- The app (Settings → Updates, plus a quiet daily check) installs new releases via `update/AppUpdater.kt`.

## The gate in detail
- **Agents gate a push with `tools/ci/gate.sh`** (not the full suite): tests picked from the files changed vs origin/main
  (`risk_tests.py --gate`: core packages + changed ones + rule tests; build files → all), screenshots only when ui /
  texts / snapshots changed, docs-only → no Gradle; a release (version line + notes) skips it when `unit.yml` is green.
  It takes the Gradle lock and `--max-workers=2` itself; `--full` runs everything. No `./gradlew --stop` afterwards –
  the daemon ends itself after 10 idle minutes. The full suite + screenshots still run in CI (`unit.yml`) on every push.
- **Cloud session (no computer):** `tools/cloud/session_setup.sh` once (Android SDK, local.properties, Maven Central mirror –
  the environment's setup script runs it at start), then `tools/ci/gate.sh` as usual; E2E on GitHub's emulator:
  `tools/ci/e2e_github.sh --tests <Class> --wait` (dispatch, wait, report + failed tests; no gh → MCP `actions_run_trigger`).
