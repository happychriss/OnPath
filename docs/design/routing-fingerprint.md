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

## 2. Sync fields (built, owner-approved 2026-10-31)

So app and browser say quietly when a synced plan was routed with other routing – without anyone opening Diagnostics.
The code is in `sync/SyncItem.kt` (pure, shared with the web through TeaVM; no new file, so the web's `appFiles` stay
as they are).

### 2.1 Wire format

The item format's rule holds: optional fields until the end – tag (varint), length (varint), bytes; unknown tags are
skipped; one tag never means two things. Used before: 1 title, 2 plan code, 3 device name, 4 track, 5 pairing code,
6 role. New, **kind 1 (PLAN) only** – the encoder writes them for plans only:

```
7 = routing      ASCII, the RoutingFingerprint text: "r1-" + 12 lowercase hex, e.g. "r1-3fa9c2e81b04"
                 ≤ 32 bytes (SyncFormat.MAX_ROUTING), Base64url code characters only ([A-Za-z0-9_-]);
                 anything else is dropped on reading. A later format ("r2-…") passes through; only
                 RoutingFingerprint.isComparable values are compared – others give no hint.
8 = routingData  ASCII, the rd5 dates of the tiles under the plan's points (SyncRoutingData):
                 "<tile>:<day>" joined by ",", sorted by tile
                 tile = brouter.de's file name without ".rd5": [EW]<0–180>_[NS]<0–90>   e.g. E10_N45, W5_S10
                 day  = whole UTC days since 1970-01-01 of the rd5's creation time (its tail, Rd5Parts.creationTime)
                        = floorDiv(ms, 86 400 000), 1–7 digits
                 e.g. "E10_N45:20752,E5_N45:20750"  (20752 = 2026-10-26)
                 ≤ 200 bytes (SyncFormat.MAX_ROUTING_DATA): whole entries only, the rest left out (≈ 15 tiles);
                 entries that do not read are dropped; a tile without a known date on the saving side is left out.
```

Order in the bytes: after every older field (7 then 8), so an item without them has exactly today's bytes
(`PlanSyncTest.planItemWithRoutingFieldsIsFrozen` pins an item with both, `blobIsFrozenForVersion1` stays as it was).

**Why days, not ms or "2026-10-26":** brouter.de builds the rd5 files once a week, so a day tells two versions apart;
a day number compares as a plain integer (no date parsing in TeaVM); 5 digits instead of 13 for ms or 10 for an ISO
date – a plan's 1–2 tiles are 13–27 bytes. The tile name stays readable and is the downloader's own name. A finer
"other file, same day" difference is still caught on the phone: `Rd5PartStore.refreshIfNewer` compares the whole tail
(creation time + part checksums).

**Not content:** `SyncItem.sameContent` ignores 7 and 8 – the user's plan is the same plan, a re-save on another device
with newer data never makes a conflict copy. `SyncMerge.newer` (same time and device) compares the content bytes first
and the routing fields only when the content is the same, so the order stays total. The winner's fields are kept.

**Old readers** (app ≤ 0.12.x, web before the merge) skip tags 7 / 8 (`else -> Unit` in `SyncFormat.decode`; tested by
renumbering them to unknown tags – `anOldReaderSkipsTheRoutingFields`). An old app re-saving the plan drops them:
missing = unknown = no hint, never a wrong one. The plan link (`PlanCode` v1) stays frozen – the fingerprint is not in
links.

### 2.2 Who writes them

Whoever saves the plan (the device whose route the user looked at when saving):
- the app (`PlanSyncPass.save` with `RoutingStamp` = `FileRoutingStamp`: the profiles in `assets/brouter` and the rd5
  tails in `segments/`): field 7 = its fingerprint, field 8 = `SyncRouting.dataFor(plan points)` – the phone's tiles
  under the points with their dates (`SyncLedger.save(…, routing, routingData)`);
- the web: the same call with its own engine's fingerprint and the dates of the rd5 it routed with
  (`docs/handoff-web-sync-fingerprint.md`).

A rename keeps the fields (the plan was not routed again). The phone does **not** re-save a plan from the browser only
because its own routing differs (`RouteItems.samePlan`) – the item keeps the browser's fields.

## 3. What the app does with them (built)

**Grey line when a synced plan is opened** (My routes → a planned route; `AppViewModel.syncRoutingCheck` →
`PlanSync.opened` → `SyncRouting.opened`): when the linked plan item was saved by another device and its field 7
differs from the phone's fingerprint (both `isComparable`), the plan sheet shows one grey INFO line, no box, no tap:
EN "Planned in the browser with other routing – the route may differ slightly", DE „Im Browser mit anderer
Routenberechnung geplant – Route kann leicht abweichen“ (`sync_routing_other`). Nothing when either side is unknown,
for a plan this phone saved itself (with an older app version), or for an unsynced route. It goes with the next edit of
the plan (`planChanged`) or another plan.

**Newer routing data at once** (instead of the 35-day rule of `checkRoutingData`):
- A pass brings a new or changed plan (`PlanSyncPass.import`) whose field 8 names a tile with a **newer** day than the
  phone's file → `Result.newerData` → `AppViewModel.refreshRoutingTiles` → `Rd5PartStore.refreshIfNewer(tile)` right
  away: background, the existing downloader (only the 1° parts on the phone), not during a tour, "Maps only on Wi-Fi"
  respected, at most once per tile per 12 h (`RefreshThrottle`), quiet – INFO lines in Problems ("checking for newer
  data now", failures INFO as before). Tiles not on the phone are no refresh (they load in the newest version when a
  route needs them); a phone file without a readable date counts as older.
- Opening such a plan checks the same again (a refresh that failed offline is retried, throttled).
- **Without field 8** (older browser / app): opening a synced plan checks its tiles on the phone whose date is more
  than 7 days old (`SyncRouting.FALLBACK_AGE_DAYS`, by the rd5's creation day) or unknown – one HEAD request per tile.
- After a refresh the open plan is routed again (the existing "routing data freshness" re-route, which tells the user
  only when the way changed).

Tests: `PlanSyncTest` (round trip with / without, frozen bytes, old reader, not content, `SyncRoutingData`),
`plansync/SyncRoutingTest` (line only when the fingerprints differ, refresh only for a newer date, 7-day fallback,
throttle, the pass writes and reads the fields, `FileRoutingStamp` reads rd5 dates), `SyncMailboxE2ETest` (the fields
through the real mailbox, `tools/sync/e2e.sh`).
