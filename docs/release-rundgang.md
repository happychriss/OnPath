# Release-Rundgang (release walkthrough)

Owner 2026-10-31: a fixed list of real user flows played automatically in the emulator with the demo ride – about
6 minutes on donald (14 flows), no human. Pushing a build to the phone needs no extra test (as before).

**Rule: the Rundgang is OPTIONAL** (owner 2026-10-31 evening, "it takes too long"; replaces "mit donald = Rundgang")
- A **normal release** = the unit + screenshot gate (`tools/ci/gate.sh` – skips itself on a CI-green base) + GitHub's smoke test.
- Run the Rundgang (`tools/ci/release_rundgang.sh`, on donald) **only when the owner asks** or when a release **changes
  navigation / recording core logic**. Then: **red = no release.**
- GitHub `release.yml` runs only the short `StartSmokeE2ETest` as its emulator gate (job `smoke`).
- Nobody waits for the push E2E run (`e2e.yml`) before releasing; that run keeps leaving the Rundgang out (`notClass`).

**Rule: every field bug the owner finds becomes one more flow here** (a line below + a test method in
`app/src/androidTest/java/com/onpath/app/e2e/ReleaseRundgang.kt`).

## Run it (donald)

- `tools/ci/release_rundgang.sh` – takes a running emulator (`emulator-*` only, never a phone), else starts its own
  headless 4 GB one and kills it after; builds app + test APK with `-Ponpath.e2e=true` inside the Gradle lock
  (`flock ~/onpath-cells-work/gradle.lock`, `--max-workers=2`); installs both and **runs `pm clear com.onpath.app` on
  the emulator first** (a clean app like CI's fresh emulator); pushes the real routing data; runs only
  `ReleaseRundgang`; screenshots + `instrument.txt` + logcat into `~/onpath-cells-work/rundgang/<date-time>/`; prints
  a pass / fail table, exit 1 when a flow failed or the run did not finish.
- `--no-build` – reuse the APKs already in `app/build/outputs/` (e.g. a second run after a fix in the test only).
- `FLOWS=com.onpath.app.e2e.ReleaseRundgang#flow05_roundTripDeleteStartFromMapMenuStaysClosed` (comma list of class or class#method) – only these flows, for
  debugging one flow.
- Other env: `SERIAL=`, `AVD=` (default `Rundgang`), `RD5=` (path to `E10_N45.rd5`).
- **Never Gradle in parallel to the emulator**: no other build on the machine while the flows run (RAM, see the load
  limits) – the script builds first, then plays the flows.

Why the Rundgang left CI: `release.yml` asked `e2e.yml` for
`-Pandroid.testInstrumentationRunnerArguments.class=com.onpath.app.e2e.StartSmokeE2ETest,com.onpath.app.e2e.ReleaseRundgang`;
the value reached Gradle intact (job log), yet `connectedDebugAndroidTest` (AGP 9.4.1, UTP) ran only the 3
`StartSmokeE2ETest` tests and the job was green (0.13.3 and the run before). The local script's
`am instrument -e class <one class>` runs the Rundgang fine. Where the second class was dropped (AGP / UTP passing the
comma list on) is not proven. So: never trust a comma list through the Gradle property – `e2e.yml` now has the step
"Every requested test class ran", which fails the job when a requested class (or `Class#method`) has no test case in
the JUnit XML (and, without a list, when no test ran at all).

Every flow: the demo location (never real GPS), the real routing data (E10_N45.rd5 – the script pushes it), Bike in
Meran (Theatre → station, ~1.4 km), one assertion per step, a screenshot per key step
(`ReleaseRundgang-<flow>-<step>.png`).

## Flows

| # | The user does | What must happen |
| --- | --- | --- |
| 1 | Holds two points on the map | The route appears with its difficulty badge and the amber hint line ("pushing · steps") |
| 2 | Starts the tour at the start and rides it | A junction cue on the way; at the end "You have arrived – end recording?" → "Save recording" keeps the tour |
| 3a | Leaves the route | The off-route question (FRAGE box) → "New route": the new line from here is followed |
| 3b | Leaves the route | The off-route question → "Keep original": the original line stays, nothing re-routed |
| 4 | Starts far from the route → "Guide me" → in Plan holds a point near him → Undo (Bug 02) | The held point becomes the new start and the lead is redone; Undo brings the lead back exactly; no junction cue while far away (wiring of `FarStartTest.bug02*`, `WaypointInsertionTest.bug02*`, `FollowHistoryTest.bug02*`, `RoutingSpotTest.bug02*`) |
| 5 | Round trip: deletes its start from the map menu | The loop stays closed (start = end) |
| 6 | During a tour taps a saved route in My routes | No question (owner 2026-11-06): the same tour records on, follows the loaded route, back in Navigate with "Neuer Plan – deine Aufnahme läuft weiter", the tour's activity stays |
| 7 | Navigate (locked): hold Pause → hold Beenden → end sheet (a press in its first 0.5 s is ignored) → "Tour speichern" → box "Tour gespeichert" → "In Meine Touren ›" | My routes opens on Done with the tour marked "✓ Gerade gespeichert" (not the tour on the map) |
| 8 | Pairs with the browser; a plan is saved there | It arrives in My routes → Planned with the tag "New" (mailbox in memory, no network) |
| 9 | Types a POI name, then a partial town, word by word in the search | The exact hit stays on top; the list does not jump while offline index and online results come in (`SearchStableListTest`) |
| 10 | Shares a place from Google Maps to OnPath (name, address, `?q=lat,lon` link; then name + address only) | The pin and its menu sit at the shared coordinate; closing the menu keeps the pin, its name chip opens the menu again, "Nadel entfernen" removes it; without one never a namesake in another town (a namesake in Bozen is in the offline data) – found in Meran or "Place not clear" (`SharedPlaceLookupTest`; the `maps.app.goo.gl` short link needs the network and is left out) |
| 11 | During a tour deletes a point ahead (field bug Hilden 2026-10-31) | The tour follows position → the rest at once, no question; the passed start stays in the plan (`TourSplitTest`) |
| 12 | Rides past points 1 and 2, deletes the destination → Back to the start → Undo | "No destination ahead any more." → the tour follows position → start; Undo brings the destination back |
| 13 | Pairs with the browser while a tour records (field bug 2026-10-31: "the phone says paired and the planner says nothing") | The phone's hello reaches the browser at once (a one-off handshake), the phone sees the browser (connected); no route goes up; Browser sync says "Routes sync after the tour" (`CorePlanSyncTest.pairingDuringATourSaysHelloButTheRoutesWaitForTheTourEnd`; mailbox in memory as in 8) |
| 14 | During a tour (Bike) opens the activity check → Hike → "New route" (field bug 2026-11-06: the question came, the navigation stayed Bike) | The tour is navigated as Hike on the new line and stays so; the service never drops the switch as an older line (`ActivityCheckTest.aTakenSwitchKeepsTheRunningSessionsTokenSoTheServiceSavesIt`) |
