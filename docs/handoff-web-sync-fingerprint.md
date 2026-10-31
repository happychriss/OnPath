# Handoff: routing fingerprint in plan sync (web session)

Owner rule (2026-10-31): the browser and the app route identically – and when they cannot (other routing code,
profiles or routing data), the other side says so quietly. Main now carries the routing fingerprint and the rd5 dates
in plan items (fields 7 and 8). The format code is in the shared `sync/SyncItem.kt` (`SyncItem.routing` /
`routingData`, `SyncFormat`, `SyncRoutingData`, `SyncLedger.save(…, routing, routingData)`, `SyncMerge.newer`) – no new
file, so `appFiles` stay as they are; it comes with the next merge of main. Full description:
`docs/design/routing-fingerprint.md` §2–3 on main. web-spike is not touched by the app session.

## 1. Text for `docs/design/web-sync.md` – add after §5.1

```markdown
### 5.2 Routing fingerprint and routing data dates (2026-10-31) – format fixed, built on main

Owner rule: plans route the same in browser and app; when the routing differs, the other side says so quietly.
Two optional fields for **kind 1 (plan)** items, backward compatible (old readers skip unknown tags):

    kind 1 = PLAN, new optional fields (written by whoever saves the plan – the side whose route the user saw)
      7 = routing      ASCII, RoutingFingerprint text "r1-" + 12 lowercase hex (e.g. "r1-3fa9c2e81b04"), ≤ 32 bytes,
                       code characters only; a value that is not RoutingFingerprint.isComparable is never compared
      8 = routingData  ASCII, rd5 dates of the tiles under the plan's points: "<tile>:<day>" joined by ",", sorted by tile
                       tile = brouter.de's name without ".rd5" ([EW]<lon>_[NS]<lat>), day = UTC days since 1970-01-01 of
                       the rd5's creation time (Rd5Parts.creationTime of its tail) – e.g. "E10_N45:20752,E5_N45:20750";
                       ≤ 200 bytes, whole entries only; tiles without a known date are left out

Days, not ms: brouter.de builds weekly, a day number compares without date parsing and is 5 digits. Neither field is
content: `sameContent` ignores them (no conflict copy for a re-save with other data); `SyncMerge.newer` compares the
content bytes first, the routing fields only for the same content (total order). A rename keeps them. Missing =
unknown = no hint. `SyncItem` has `routing: String?` and `routingData: String?`; the codec is `SyncRoutingData`
(`encode(tile → day)`, `parse`, `newer(text, own)`, `day(ms)`). The plan link (`PlanCode` v1) stays without them.

**Phone:** writes its own fingerprint and the dates of its rd5 files; a plan from the browser whose field 7 differs
gets one grey line when opened ("Im Browser mit anderer Routenberechnung geplant – Route kann leicht abweichen"); a tile
whose field-8 day is newer than the phone's file is fetched again at once (`refreshIfNewer`, not after 35 days);
without field 8 the plan's tiles older than 7 days are checked when it is opened.

**Browser:** writes its engine's fingerprint and the dates of the rd5 it routed with; a plan from the phone whose field
7 differs gets the same kind of grey line ("Auf dem Handy mit anderer Routenberechnung geplant – …"). The browser
always reads current rd5 files (proxy, cached ≤ 1 day), so it has no refresh to do.
```

## 2. What the web fills when it saves a plan

Where the page saves a plan into the ledger today (`ledger.save(id, plan, title, now, device)` in `WebSync.kt`), pass
the two new arguments:

```kotlin
// the engine's fingerprint – the same text the diagnostics line shows (handoff-web-parity-b.md §4)
val fp = RoutingFingerprint.of { n -> File(root, "assets/brouter/$n").readBytes() }   // compute once, keep it
// the rd5 the page routed this plan with: tiles under the plan's points → creation day
val tiles = points.map { Rd5Parts.tileName(it.lat, it.lon) }.toSet()                  // "E10_N45.rd5"
val days = tiles.mapNotNull { t -> creationMs(t)?.let { t to SyncRoutingData.day(it) } }.toMap()
ledger.save(id, plan, title, now, device, routing = fp, routingData = SyncRoutingData.encode(days))
```

- `creationMs(tile)` = `Rd5Parts.creationTime(tail)` of the tile the engine loaded (`PartialRd5` fetches the tail with
  the index); null when not known – then that tile is left out (`encode(emptyMap())` is null = no field 8).
- Same rule as the app: write them only when the page saves (Speichern), with the route the user saw. A rename
  (`ledger.rename`) keeps the fields; never invent a value – unknown stays null.
- Tests: the format is pinned by `sync/PlanSyncTest.kt` (already in `appTests`: `planItemWithRoutingFieldsIsFrozen`,
  `anOldReaderSkipsTheRoutingFields`, `routingFieldsAreNoContent`). Add a web JVM test beside `WebSyncJoinTest`: a page
  save carries the engine's fingerprint and the loaded tiles' days; the grey line only when the fingerprints differ.

## 3. The grey line the web shows

When a plan is opened / shown in the planner and its item came from the phone (`item.device` is not the page's own
device id) and `item.routing` differs from the page's fingerprint, both `RoutingFingerprint.isComparable`:

- one grey INFO line in the plan panel, no box, no button, no tap – like the app's plan sheet line;
- EN "Planned on the phone with other routing – the route may differ slightly", DE „Auf dem Handy mit anderer
  Routenberechnung geplant – Route kann leicht abweichen“ – new strings in `web/strings/values[-de]/strings_web.xml`;
- nothing when either fingerprint is unknown or not comparable, for a plan the page saved itself, or for a plan
  without field 7 (older app);
- it goes when the plan is edited in the page (then the route is the page's own) or another plan is opened.

Field 8 needs no action in the browser (it reads current data). Optional, if cheap: the diagnostics line can show the
item's field 8 beside the page's own dates.

## 4. Backlog

`docs/backlog.md` → "Web planner: mirror app changes" → Must: "Plan sync fields 7 / 8 (routing fingerprint + rd5
dates)".
