# Hard cases – what the tests cover (aufräumen, 2026-11-24)

Owner 2026-11-24 („ok, so machen“): flaky tests first, then flows for the hard cases. This is the coverage audit
(read only, a helper's grep + spot reads – "not found" means not found by name, not proven absent).

**Structural point:** nearly every restart / GPS flow runs against `FakeRecording` (state testkit), not the real
`RecordingService`; the service itself is checked by core unit tests (PointFilter, PointQueue, GpsWatchdog, NetworkFix,
TourRun, InterruptedLog) and a few E2E. No fake network, clock or storage exists for the state flows.

| Case | Covered well | Gap |
|---|---|---|
| No fix at start | CoreIndoorStartE2ETest, NetworkFixTest, TourSessionFlowTest | long no-fix start as a flow |
| Fix lost mid-tour | PointFilterTest, GpsWatchdogTest (U), TourFlowPropertyTest `gap()` | whole flow: lost → Navigate says so → back → two pieces, no jump, no off-route alarm |
| Permission revoked mid-tour | – | untested; service checks only at start (RecordingService.kt ~413) – may be a product gap |
| Jumps / inaccurate fixes | PointFilterTest, NetworkFixTest (U) | a jump next to Navigate (alarm / re-route / arrival) |
| Map-area download, network gone | CellDownloadTest, HttpDownloadTest (U), FirstMapDownloadE2ETest | the user flow: paused sign, old map works, resumes |
| Plan sync / links offline | PlanSyncTest, SyncPollTest, SyncRulesRandomTest, PlanLinkFlowTest | pull cut in the middle (check SyncRulesRandomTest first); a plan link with no network |
| Live sharing offline | LiveUploaderTest (300 seeds, kills) | only the status line after long offline |
| Process killed mid-tour | TourSessionFlowTest (~15), TourEndFlowTest, UnfinishedTourSetupE2ETest | point count before/after a real kill; no doubled point at DB level |
| Phone reboot / app update in a tour | – | TourInterruptedReceiver (BOOT_COMPLETED, MY_PACKAGE_REPLACED) untested; schema upgrade with an active tour: SchemaUpgradeFlowTest (2026-11-25) |
| Broken map / routing / app files | BrokenAppFilesTest, BrokenFilesTest, StorageNotReadyTest | – |
| Storage full while recording | fake `saveFailed` only | a real write failure, resume when space returns |
| Clock / time-zone jump | unit only (UTC suite) | clock back / zone change mid-tour |
| Battery saver | GpsWatchdogTest (U) | as a flow |

## New flow tests, ranked
1. **Fix lost, then back – a whole tour** (M; needs a clock-driven GPS silence in FakeRecording running the real core filter/watchdog).
2. **Kill / reboot / update restart, DB level** (M): 100 points, kill, receiver path, continue, 50 more → 150, none doubled or lost; again with a half-written last point. Receiver test alone is S.
3. **GPS permission revoked mid-tour** (M) – may first show a product gap.
4. **Clock jump and time-zone change** (S–M): duration positive, order kept, live timestamps not backwards.
5. **Network lost during a map-area download, as a user flow** (M–L).
6. **Storage full while recording** (M): told once, earlier points intact, resumes.
7. **One jump fix next to Navigate** (S–M): no alarm, no re-route, no arrival, no spike.
8. **Plan sync, pull cut in the middle** (M) – after checking SyncRulesRandomTest.
