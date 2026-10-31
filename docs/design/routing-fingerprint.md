# Routing fingerprint: same routing in app and browser, visibly

Owner rule (2026-10-31): routes and plans are always identical in the browser planner and the app. Both run the same
Kotlin code (the web engine compiles the app's files with TeaVM), but three things can still differ between two
devices: the routing code's version, the BRouter profiles, and the routing data (the rd5 files – brouter.de rebuilds
them weekly). This note says how they are made visible.

## 1. What is built (main)

`routing/RoutingFingerprint.kt` – pure Kotlin, no Android, no `java.security` (TeaVM-safe, uses the sync's own
`Sha256`; kept so by `SharedCoreRuleTest.webShared`):

- `RoutingFingerprint.of(read)` = `"r1-"` + the first 12 hex digits of SHA-256 over
  `"onpath-routing\nbrouter=<BROUTER_VERSION>\nplanner=<PLANNER_VERSION>\n"` and, per file of `FILES` in that order
  (`lookups.dat`, `hiking-mountain.brf`, `mtb.brf`, `touring.brf`, `trekking.brf`), `"<name>\n<length>\n"` + its bytes
  without `\r` (a CRLF checkout is the same profile). Comments count (a profile with other comments is another version
  – simpler and safer than `RouterClassHash`'s normalising, which follows WayClass.java for another purpose).
- `BROUTER_VERSION = "1.7.11-beta"` – `RoutingFingerprintTest` fails when the jars in `app/libs` are another version.
- `PLANNER_VERSION = 1` – raised by hand with every change that can give another route for the same points, activity
  and data: Planner passes / splits, `WaypointSnap` rules, the profile parameters and via handling in `BRouterEngine`.
  Not raised for refactors that keep every route (e.g. `PlanResult`).
- `FILES` – the test fails when `assets/brouter` gets a profile the fingerprint does not hash.
- `RoutingFingerprint.describe(fp, rd5)` – the Diagnostics line, e.g.
  `Routing: r1-3fa9c2e81b04 · BRouter 1.7.11-beta · planner 1 · data E10_N45 2026-10-26, E5_N45 2026-10-26`.
  The date is the rd5's own creation time (BRouter writes it into the file's tail, `Rd5Parts.creationTime`; ms, UTC
  day), "–" for a file without a tail.

Settings → Diagnostics shows the line (English, read on a background thread when the page opens). The web planner can
show the same line from the same code (`docs/handoff-web-parity-b.md`). Same fingerprint + same data dates for the
plan's tiles = the same route.

## 2. Proposal (not built): carry it in the sync item

So app and browser can say quietly when a synced plan was routed with other routing – without anyone opening
Diagnostics.

**Fields** (`sync/SyncItem.kt`, `SyncFormat`): the item format already has the rule "optional fields until the end:
tag, length, bytes; unknown tags are skipped; one tag never means two things". Used today: 1 title, 2 plan code,
3 device name, 4 track, 5 pairing code, 6 role. Proposed:

| Tag | Name | Content | Kind |
| --- | --- | --- | --- |
| **7** | routing | ASCII, `RoutingFingerprint` text (`r1-` + 12 hex), ≤ 32 bytes; anything not `isComparable` is ignored | 1 PLAN only |
| **8** | routingData | ASCII, the rd5 stamps of the plan's tiles: `E10_N45:1792982642161,E5_N45:…` (tile without `.rd5`, `:`, creation time ms), ≤ 200 bytes | 1 PLAN only |

- Written by whoever saves the plan (the device whose route the user looked at when saving). `SyncItem` gets
  `routing: String? = null` and `routingData: String? = null`; `encode` / `decode` as the other optional fields.
- **Not** part of `sameContent`: the user's plan is the same plan; a re-save on another device with newer data must
  not make a conflict copy. `SyncMerge` keeps the fields of the winning version, as it does now.
- Old readers skip tags 7 / 8 (forward-compatible as tags 5 / 6 were). An old app re-saving the plan drops them –
  missing = unknown = no hint, never a wrong one. `PlanSyncTest` gets a frozen-bytes test for an item with both fields
  (like `joinItemIsFrozen`), and an old item without them must still read.
- The plan link (`PlanCode` v1) stays frozen – the fingerprint is not in links.

**The hint** (owner to decide the words; INFO = grey, no box, no dialog):
- When a synced plan is opened and its `routing` differs from the own fingerprint, or a tile in `routingData` has
  another creation time than the own file: one grey line in the plan sheet, e.g. "Routed in the browser with other
  routing data – the route may differ a little". Nothing when either side is unknown.
- When only the data differs and the own data is the older: the phone first tries to get the newer data (§3), then
  routes; the line goes away when the stamps match.

## 3. Proposal (not built): refresh the phone's routing data when a synced plan arrives

Today (`AppViewModel.checkRoutingData`): at most every 12 h (`ROUTING_CHECK_MS`) and only for the start's and the
goal's tiles, a tile whose file is older than 35 days (`ROUTING_MAX_AGE_DAYS`, by the file's modification time = its
fetch time) is refreshed with `Rd5PartStore.refreshIfNewer` (only the 1° parts on the phone, Wi-Fi rule respected,
never during a tour). So for up to 35 days the phone can route with older data than the browser, which reads the
current files from brouter.de through its proxy (`web/host`, cached up to a day).

Proposal:
- When a pass brings a new or changed plan (`PlanSyncPass`, kind PLAN) whose `routingData` (tag 8) names a tile with a
  **newer** creation time than the own file, call `refreshIfNewer(tile)` for those tiles right away – the same rules as
  today: not during a tour, "Maps only on Wi-Fi" respected, at most once per tile per 12 h, failures INFO (offline).
- Without tag 8 (older browser / app): when the plan is opened, check its tiles once with `refreshIfNewer` if the own
  file is older than 7 days (instead of 35) – a HEAD request per tile (`Rd5Remote.head`), cheap.
- After a refresh the plan is routed again (as now: "routing data freshness" re-route, tells the user only when the way
  changed).
- `refreshIfNewer` already compares versions by the tail (creation time + part checksums), so a needless download
  does not happen.
