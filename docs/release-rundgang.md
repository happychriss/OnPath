# Release-Rundgang (release walkthrough)

Owner 2026-10-31: before every **release** a fixed list of real user flows is played automatically in the emulator with
the demo ride – about 5 minutes on donald (11 flows, 2026-10-31; longer on CI), no human. **If one flow fails, there is no release.** Pushing a build to the phone
needs no extra test (as before).

**Rule: every field bug the owner finds becomes one more flow here** (a line below + a test method in
`app/src/androidTest/java/com/onpath/app/e2e/ReleaseRundgang.kt`).

## Run it

- On donald: `tools/ci/release_rundgang.sh` – one emulator (a running one, else its own headless 4 GB one, killed
  after), build with `-Ponpath.e2e=true` in the Gradle lock (`--max-workers=2`), only the Rundgang, screenshots into
  `~/onpath-cells-work/rundgang/<date-time>/`, a short pass / fail table. Never on a phone.
- CI: `release.yml` → job `smoke` (= `e2e.yml` with `StartSmokeE2ETest` + `ReleaseRundgang`); `publish` waits for it.
  The full E2E run after every push leaves the Rundgang out (it runs at release time). Screenshots: artifact
  `e2e-report` → `e2e-out/`.

Every flow: the demo location (never real GPS), the real routing data (E10_N45.rd5 – the script and CI push it), Bike in
Meran (Theatre → station, ~1.4 km), one assertion per step, a screenshot per key step
(`ReleaseRundgang-<flow>-<step>.png`).

## Flows

| # | The user does | What must happen |
| --- | --- | --- |
| 1 | Holds two points on the map | The route appears with its difficulty badge and the amber hint line ("pushing · steps") |
| 2 | Starts the tour at the start and rides it | A junction cue on the way; at the end "You have arrived – end tour?" → Save keeps the tour |
| 3a | Leaves the route | The off-route question (FRAGE box) → "New route": the new line from here is followed |
| 3b | Leaves the route | The off-route question → "Keep original": the original line stays, nothing re-routed |
| 4 | Starts far from the route → "Guide me" → in Plan holds a point near him → Undo (Bug 02) | The held point becomes the new start and the lead is redone; Undo brings the lead back exactly; no junction cue while far away (wiring of `FarStartTest.bug02*`, `WaypointInsertionTest.bug02*`, `FollowHistoryTest.bug02*`, `RoutingSpotTest.bug02*`) |
| 5 | Round trip: deletes its start from the map menu | The loop stays closed (start = end) |
| 6 | During a tour taps a saved route in My routes | The box "End the running tour first …"; plan, followed line, activity and tour stay |
| 7 | Ends the tour → Save | The tour is listed in My routes → Done |
| 8 | Pairs with the browser; a plan is saved there | It arrives in My routes → Planned with the tag "New" (mailbox in memory, no network) |
| 9 | Types a POI name, then a partial town, word by word in the search | The exact hit stays on top; the list does not jump while offline index and online results come in (`SearchStableListTest`) |
| 10 | Shares a place from Google Maps to OnPath (name, address, `?q=lat,lon` link; then name + address only) | The ring and its menu sit at the shared coordinate; without one never a namesake in another town (a namesake in Bozen is in the offline data) – found in Meran or "Place not clear" (`SharedPlaceLookupTest`; the `maps.app.goo.gl` short link needs the network and is left out) |
