# AppViewModel split (review Arch 3) – plan, Fable's review, state

Owner 2026-11-01: split all five areas now, with a safety net, so the owner tests ONE build outside. Fable (claude-fable-5-1)
reviewed the plan read-only before the start; cloud-app does the work. Captain: do not change / move / rename any file in
`app/web-shared-files.txt` during the split; when done write "split done – <commit>, shared files touched: …" under
"For the captain".

## Done (each: full gate, androidTest compile; no shared file touched) – split complete
1. App updates → `ui/AppUpdates.kt` (f92268ac) – lazy, `UpdateState` stays in the ViewModel, members forwarded.
2. Phone setup → `ui/PhoneSetupState.kt` (d5396fd6) – created where the members were declared (same start-up order),
   settings via a lambda; backup / restore (4749–4839) stays.
3. Pairing half of the sync → `ui/PairingFlow.kt` (6674dc82) – screens through `PairingFlow.Screens` (getters /
   setters on the ViewModel's own properties); `PairEntryTest` reads the new file. Sync passes, `routeSyncedPlans`,
   `followSyncedSavedPlan` (saved-route link), `syncRoutingCheck`, the sync `init` stay.

4. Map cells + routing data → `ui/MapDataFlow.kt` (a8aade87) – triggers (point, position, route, tap, search, tour
   start), card, loading modal, background strip, Settings → Offline maps, routing data loader + manual download;
   `MapDataFlow.Host` (getters / setters on the ViewModel), `start()` from the last init, `stop()` from `onCleared`.
   Stay in the ViewModel: `routingDataArrived` + `gated()` / `QuietGate` (one owner, also style reload), the routing
   data freshness (`checkRoutingData`, `refreshRd5` → engine, recalc), the very-long-plan deferral in
   `cellsForNewPoints`, the demo. `OfflineResultTest` reads the new file.
5. Search + shared places → `ui/SearchFlow.kt` (2e50efbc) – search view, pick + flight, candidate marker
   (`searchMark`), place names (`stopName`), shared places (lookup, "Place not clear", menu on arrival, held start
   camera); `SearchFlow.Host`. Stay: `addPlace`, place card, `fittingPlaceAdd` (plan edits). The lazy sits above the
   init that routes the saved plan (`stopName` from recalc). `SharedPlaceMenuTest`, `WholeRouteTest` read the new file.

AppViewModel 5083 → 4881 (steps 1–3) → 4091 lines. Every public name forwarded (UI / E2E unchanged). E2E map tests
(`NoStaleDownloads`, `RoutingDataFreshInstall` with `E2E_ARGS="-e routingDownload true"`, `PhoneSetup`, `CorePlan`)
green before and after step 4; after step 5 + the Release-Rundgang: see `docs/project-status.md` "Last session".

## Notes from before steps 4 + 5 (kept for the record)
Fable: both are woven into `change()`, `recalc()`, `closeMapOverlays()`, undo / redo and the last `init {}` – NOT
self-contained. Couplings that would break silently if missed (keep them, test them):
- routing data arrives → `recheckData()`, `engine = null`, `recalc()`, `routeSyncedPlans(syncRoutingLater)`
  (`routingDataArrived`); `refreshRd5` → `engine = null`, `recalc()`, `routingDataReroute`; `removeRoutingParts` →
  `engine = null`; `routingDataChanged` → `routeError`, `dataBusy`.
- core → cells: `change()` → `cellsForNewPoints`; undo / redo / long route; `recalc()` → `corridorFollowsPlan`,
  `postRoute`, `routingForLine`, `showCellJob(cellTriggers.routeComputed)`, `freshenAfterRoute`; `enter()` / tour start →
  `routingForLine`; track → plan → `routingData.ensure`; `flyToPlace` → `showCellJob` / `routingFor`;
  `onReturnToApp` → `t3Pending`, `cells.setForeground`; `settingsPage` setter → `offlineResult`.
- search: `searchMark` / `searchPlace` read / written by `cancelLoadModal`, `candidateJoin`, `closeMapOverlays`
  ("menu closes ⇒ marker goes"), the POI menu (`clearSearchMarkAt`), `Relevance`; `addPlace` / `fittingPlaceAdd` use
  `change{}`, `pinned`, `history`, `fitNextRoute`; `stopName` / `rememberPlaceName` used at 12 sites.
- init order: `cells`, `cellTriggers`, `routingData` (lazy, registers a listener; `onCleared` unregisters), `search` /
  `searchStore` are reached from the `init` at ~1486 and the last `init` – a controller must be declared above that
  `init`, must not touch ViewModel state in its constructor, and should get an explicit `start()` from the last `init`.
- `gated()` + the `QuietGate` fields are shared by style reload and routing-data arrival – one owner.
- Unit tests that read AppViewModel.kt as text and would need to read the new file: OfflineResultTest, NoticesTest:83,
  PlanStatusTest:69, SyncedRoutingTest, SharedPlaceMenuTest, WholeRouteTest, CandidateMarkerTest, PlanEditsTest,
  CorePlanLinkTest, BackgroundRoutingRuleTest (exactly 3 `withContext(routingDispatcher)` in AppViewModel.kt).
- E2E reads: `vm.cells.waiting()/installed`, `vm.mapLoadUi(…)`, `vm.openSearch()`, `vm.sharedLookup`, `vm.sharedUnsure`,
  `vm.searchMark` – every public name must stay on AppViewModel (forwarders).

Recommended technique: a controller per area that holds a narrow interface of the ViewModel (Fable's `PlanView`:
`mode`, `waypoints`, `route`, `recState`, `tracker`, `searchMark`, `recalcForNewData()`, `recheckData()`,
`invalidateEngine()`, `say`), state forwarded by getters / setters (never value copies), public names forwarded.
Safety net before moving: emulator runs of `NoStaleDownloadsE2ETest`, `RoutingDataFreshInstallE2ETest`,
`PhoneSetupE2ETest`, the Release-Rundgang; after each step the same + full gate + androidTest compile.

## How the next session works (owner 2026-11-01)
- **Split the work across agents where it causes no problems** – never two agents editing `AppViewModel.kt` (or any
  file) at the same time. Safe in parallel: one agent writes / runs the safety-net tests (characterisation unit tests,
  the E2E map tests on the emulator) while another prepares step 4 in a worktree; or step 4 and step 5 in two
  worktrees only if their hunks in AppViewModel.kt do not overlap, merged one after the other with a full gate each.
- **Every emulator run on donald** (`connectedDebugAndroidTest -Ponpath.e2e=true`, `tools/ci/release_rundgang.sh`),
  one at a time under the Gradle lock (`~/onpath-cells-work/gradle.lock`) – never in a plain cloud container.
