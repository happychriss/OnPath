# Flaky tests 2026-11-03 … 11-24 – GitHub evidence (aufräumen, 2026-11-24)

Owner 2026-11-24 („ok, so machen“): flaky tests first. Read-only hunt over unit.yml, e2e.yml, web.yml (failing step per
job, e2e per test from the artifacts, same-SHA red/green pairs). About three quarters of the web.yml reds are the parity
cascade (web-build „Web ⇄ app parity“ red → every other job „Wait for the build job“) – real breaks, not flaky.

## Still open (red without a bug, not fixed yet)
| Test | Suite | Evidence | Likely cause | Test-side fix |
|---|---|---|---|---|
| areas-smoke „polling: the doorman's 40 % moves the bar“ (web/app/test/areas-smoke.mjs ~176) | web, areas-parity | 44924598176 (main, from 94 %), 45062448790 (from 84 %) – both after 5f4ba88c | the time-based bar is already above 40 % and never goes back; `pct > p0` in 15 s depends on runner speed | assert „stays up, monotone“, or let the doorman answer above the shown bar |
| import-smoke „Reverse: the new route draws itself“ (import-smoke.mjs ~255) | web, lang-gpx | 43477413309 red / 43477411808 green on the same SHA; ≥2 more earlier | 0.5 s RouteReveal sampled by frames; a loaded runner loses / adds frames | sample in the page with rAF; assert „some partial length, then whole“ |
| ReleaseRundgang flow10 step c) name chip „Restaurant Sissi ›“ (ReleaseRundgang.kt ~440) | e2e (dispatch) | 44497175210 red / 44496395033 green, same SHA 6b71c967 | chip looked up once with 10 s right after `closeMapOverlays` | `waitUntil` the chip in the semantic tree, 30 s like steps a) b) |
| nowebgl-smoke „the wait box: spinner, 'Preparing map …32%…'“ | web, short | 43965797223 red / 43965795072 green, same SHA | text compared while the percent ticks | regexp, ignore the number |
| Playwright Chromium install „exited with code 100“ | web, sheet-sync | 43764893116 red / 43764893332 green, same SHA | apt infra | retry the step once in web.yml – needs a pull request (workflows) |

## Fixed already (no red on main since)
OverviewSwipeE2ETest pill (4f4ec3795) · areas-smoke „own area: the wait window“ (5f4ba88c) · areas-smoke beside-sign
race (f931aa5d6, a planner race) · robust-smoke 10(a) failed plan learned shares (8a04b05cf, 9c2b9931d, 083dcc17b,
e47cc7e58) · NoStaleDownloadsTest multi-day (a real lost wake-up, 2342a23e9).

## Real, not flaky
CorePoiTapE2ETest hold while following (field bug 22.11, fixed) · CoreFieldE2ETest initializationError (build break) ·
TourDbMigrationTest schema 9 (announced) · parity cascades.

## Watch
- Only local, no CI red: routes-smoke step 4b (1 in 4 under load), poipin (d) „POI name beside its icon at z17+“ (2/3 local),
  ReleaseRundgang flow 17 (once on the 0.18.2 candidate, 3 green alone).
- The backlog's „robust-smoke 'without downloads: the percent moves' red in the 0.18.8 run“: no CI red found in the window.
- No retries / @FlakyTest hide anything. Fixed waits are the likeliest future flakes: web smokes' `waitForTimeout`
  (flows-shots 35, search-smoke 20, parity-smoke 17, zuletzt-smoke 14, robust-smoke 15, areas-smoke 8).

## Playwright install retry – the pull request could not be opened (2026-11-24 night)
The push of `pr/web-playwright-install-retry` was refused: donald's `gh` token has no `workflow` scope (repo, gist, read:org only). Needs the owner or a session whose token may change `.github/workflows/`. The change (local branch on donald, 53182aab5):

```diff
diff --git a/.github/workflows/web.yml b/.github/workflows/web.yml
index b533a21a1..de5d5f9b3 100644
--- a/.github/workflows/web.yml
+++ b/.github/workflows/web.yml
@@ -220,7 +220,8 @@ jobs:
         working-directory: web/app
         run: |
           npm ci
-          npx playwright install --with-deps chromium
+          # a second try after a short wait: the apt mirror failed once (exit 100, run 43764893116; green on the same commit)
+          npx playwright install --with-deps chromium || { sleep 20; npx playwright install --with-deps chromium; }
 
       # test = the app's own tests of the shared files (appTests) + WebImportTest (writes build/jvm-gpx.json)
       - name: The app's tests of the shared files + routes on the JVM (same engine code)
@@ -315,7 +316,8 @@ jobs:
         working-directory: web/app
         run: |
           npm ci
-          npx playwright install --with-deps chromium
+          # a second try after a short wait: the apt mirror failed once (exit 100, run 43764893116; green on the same commit)
+          npx playwright install --with-deps chromium || { sleep 20; npx playwright install --with-deps chromium; }
 
       - name: Wait for the build job (engine + page)
         env:
```

## 2026-11-25 night: MapAreaNetworkLostFlowTest (new) red on GitHub
unit.yml on a08683fd2: the cut stream ended as a clean short end (`HttpDownload.Incomplete` → „Map download stopped at 50 % …“) instead of an IOException („Connection to the map server broke …“) – timing, both are the product's plain UNREACHABLE texts and keep the part. The test now accepts both (test only).

## 2026-11-25 morning (seen on PR 24, unrelated to it)
- `smokes (sheet-sync)` and `smokes (lang-gpx)`: `actions/download-artifact` failed before any test (GitHub infra), green on rerun.
- `smokes (lang-gpx)` „an edit during a long detour check: the new route in 5483 ms (not after the check)“ – a timing
  bound in the detour-line smoke (donald's fb38e0ec4 area); green on rerun, same commit. → [donald] / [laptop] watch it.

## Seen once since (laptop 2026-11-25)
- robust-smoke (c) „Cancel: nothing learned from the stopped run“ (`onpath.progress.mapSteps` written), run 45570599195 on ab07f1cd6, green on rerun, 3/3 green locally. Guess: a job of the cancelled run still open after Cancel starts a new (dismissed) wait whose finished end writes the shares – `loadoverlay.ts` `cancel()` → `end(false)` → `update()` → `begin()`. Next time red: log `__wait.overlay()` jobs at the check.
