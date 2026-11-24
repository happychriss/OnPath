# Detour stitch + long-half cuts – design proposal (2026-11-21)

Status: **agreed 2026-11-21 ([umbau] + laptop, owner's three answers at the end); §8 is binding and wins over older text.** Built on `wip/umbau-2026-11-21-detour-stitch` (piece 1, data model + tag 7: done, reviewed, full gate green at abc8ac5b3); nothing of tag 7 or the anchors reaches `main` until `SameRouteTest` passes on JVM and Wasm (captain). Input: `docs/team/items/2026-11-20-add-point-speed.md` (measurements + owner's decisions).
Goal: a point added to a long leg routes in ~0.3 s instead of 4–15 s, and **every device gives the same route for the same
plan** (phone, browser, sync, plan link, GPX export, saved route reload).

## 1. The one idea: anchors are hidden via points stored on the point
A stitched point P keeps the points of the old line where its detour leaves and rejoins it. The Planner routes the plan
**expanded**: `[…, prev, a, P, b, next, …]` – leg by leg, exactly as today (`Planner.plan`, `LegCache`). Nothing else
changes in routing: same expanded list = same legs = same route (the rule `LegCache` already lives by).

```kotlin
/** Hidden via points of a plan point, all ON the line it was put on, in route order (1e-6°, BRouter's own grid). */
data class Anchors(val before: List<GeoPoint> = emptyList(), val after: List<GeoPoint> = emptyList())
// SharedPlan.anchors: Map<Int, Anchors> – the 4th per-point attribute beside pinned / names / nights
```
- **Stitch** (≤ 2 km off the visible leg's line, that leg's line > 60 km): `before = [line point 10 km before P's
  projection]`, `after = [10 km after]`. A side is left empty when its neighbour is closer than 10 km + 2 km along the
  line (that side is short – routed in full, cheap).
- **Speed-up 3** (beyond 2 km, or the far parts of a stitch): the long half gets cut points on its old line every
  50–80 km (line points chosen on the editing device only – it does
  not matter, they are stored), none closer than 50 km to P's projection. They go into the same lists (`before` =
  cuts then the 10 km anchor). Same data, same rules, one concept.

### Options considered
| Option | Same route everywhere? | Verdict |
|---|---|---|
| A. Flag only, anchors recomputed (10 km along the base line) | needs the old line on every device (cold browser: route the full base first) and identical float geometry JVM ⇄ Wasm (`Geo.distance` sin/cos may differ by an ulp → another line point) | no |
| B. Store the stitched geometry on the point | exact, but megabytes in a plan code / sync item; stale after a routing-data update | no |
| C. Anchors cut "the base leg prev→next" | exact, but the base depends on the neighbours: a second point on the same long leg has no routed base → does not compose | no |
| **D. Anchors as hidden via points (coordinates)** | yes up to one invariant (below); composes; tiny (a few bytes per point) | **recommended** |

**Why coordinates, not indices:** an index into a routed line means nothing on a device with other routing data or before
the line exists; a coordinate is plain data, survives every transport, and BRouter line points are 1e-6° integers – so the
anchor is exactly a line point and `LegSplit` cuts there without tolerance games.

**What the anchors buy, and the one invariant left.** Without anchors a cold device routes `prev→P` and `P→next` in full
– at 5–10 km off that is another valley (+9 to +34 km measured). With anchors every device routes `a→P→b` itself, so the
detour is identical everywhere. What is left: the editing device takes `prev→a` and `b→next` as **cuts of the cached old
leg** (no routing), a cold device **routes them** fresh. They are equal when "a route through a point of an optimal route
= that route's piece" (sub-path consistency). This is the same assumption `LegSplit` (0.18.3, nights on the line) already
ships with; the check in §5 measures it. Safety net if §5 finds mismatches: the editing device routes the cut legs once
more when the router is idle (lowest priority, dropped on any edit) and replaces them – converges to the cold line.

## 2. Edits (all in core `PlanAnchors.follow`, used by app and planner via `PlanEdits` / `PlanPoints`)
Anchors follow their point like pins / names / nights (`PlanEdits.follow`, `keepMoved = false`).
| Edit | Rule |
|---|---|
| Delete P | its anchors go with it (owner). `prev→next` comes back from `LegCache` (the old base is usually still there) – instant |
| Undo / redo | the history snapshot holds the anchors (`PlanHistory` / page undo) – legs from the cache, nothing routed |
| Move P | P is a new point → anchors dropped; a move that stays ≤ 2 km of the current line is stitched again against it (Q3) |
| Insert N between `prev` and a `before` anchor (by line position) | P keeps its anchors (the route still passes them) |
| Insert N between an anchor and its P | **transfer**: the anchors between N and P move from P's `before` to N's `before` (N on the line: zero routing; N off the line: N gets its own stitch, the transferred anchors beyond N's 10 km anchor are dropped and the cut leg is concatenated – same invariant) |
| Delete / move / replace a neighbour | the facing side of P's anchors is dropped (the new neighbour's natural way need not pass them) |
| Reverse | `before` ⇄ `after`, lists reversed |
| Round trip / loopStart | no special case (the closing leg is a leg) |
| Activity / T level change | all anchors cleared (they lie on the other profile's line) – full routing, as today |
| „Ganz neu berechnen" | all anchors cleared, full re-route of the visible points |

All decisions (stitch or not, where the anchors go) are made **once, on the editing device**, from its line, and stored.
Other devices only read data – no device-dependent geometry decides a route.

## 3. Transport and storage
- **Plan code** (`PlanCode`, still VERSION 1): optional tag 7, layout as built on the wip branch (`PlanCode.kt` is the reference): varint format (1, any other = tag ignored), varint point count (must match), per point its remainder to 1e-6° (zigzag lat, lon, each −5…+5), then entries, each `varint byte length + entry` (varint index, count before, count after, the anchors as zigzag deltas in 1e-6°, the first to the point's exact value). Extra bytes at the end of an entry are skipped, so later formats can add fields. When tag 7 is written, base and remainder both come from the point rounded to 1e-6°, so `encode(decode(x)) == x`. An entry is dropped when its index is out of range, it is empty, has more than 8 per side, or an anchor's lat or lon is over 30° from its point (integer check, the same on JVM and Wasm; wide enough for speed-up 3's cuts). A plan holds at most 500 anchors (§8.8). **A code that would pass `MAX_TEXT − 256` with tag 7 is written without it**: the plan stays openable. **Older apps skip tag 7** → they show the same points, route in full (slower, possibly a slightly different route), never lose the plan. An older app that re-saves the plan writes it without tag 7 – the plan then degrades to plain routing everywhere (consistent, never broken). Readers ship one release before writers (§6).
- **Sync**: the item carries the plan code → anchors travel for free; merge stays "newer wins, older stays as a copy"
  (whole plan, no field merge). `SyncItem.routing` / `routingData` keep telling when two sides route on different data.
- **Saved route** (`Tour`): one new JSON column `anchors` (schema +1, like `days` in `MultiDay`; unreadable = none,
  reported). Same field in `TourPlan` (a done tour's plan copy), `BackupCodec`, `RouteItems` (route ⇄ sync item),
  Zuletzt drafts, `PlanStore`.
- **Reload**: all expanded legs (incl. the cut ones, `LegCache.put` → `LegFiles`) are in the leg store → instant; a new
  phone / browser routes the expanded list (cost as today, never more).
- **GPX export** (`PlanGpx`): the computed line (stitched) + the visible points as `<wpt>`; anchors are not exported. A
  re-import is a new plain plan (as for any GPX today) – the line in the file is the one every device shows.
- **Multi-day**: nights are visible points; anchors never carry a night and are never a day border. A night put on the
  line inside an anchor span = "insert N between anchor and P" → transfer, zero routing (`LegSplit` cuts the sub-leg).
  `PlanDays` works on the visible points + the line – unchanged.
- **Navigation**: follows the computed line; the rerouter (`Rerouter`, `TourSplit`) targets visible points only and
  ignores anchors (a get-back route is a fresh route anyway).

## 4. Where it lives
- **core/routing** (shared, the planner compiles it): `PlanAnchors.kt` – `Anchors`, `expand(points, anchors, pinned)`
  → expanded list + index map back; `forInsert(legLine, prev, next, p)` (stitch / cut rule, the thresholds as constants);
  `follow(before, after, …)` (§2). `Planner` and `PlanResult` take an `ExpandedPlan` (§8.1) and map `snapped` / `skipped` / gaps back to visible indices. `LegSplit.seed` learns to cut any
  line point of a known **chain** of legs and to concatenate two legs (for transfers). `PlanCode` tag 7, `SharedPlan.anchors`.
  No `PLANNER_VERSION` bump (plans without anchors route as before).
- **core/data, gpx, plansync**: storage fields (§3).
- **state** (app): `PlanDocument` / `PlanHistory` hold anchors; the add-point flow calls `forInsert`; `PlanRecalcSlice`
  passes anchors; "Ganz neu berechnen" action + `hasAnchors` for the menu row.
- **app**: the menu row (last row of the plan menu, only while `hasAnchors`; picture to the owner first), `TourDb` `onUpgrade` (plain SQLite).
- **planner (web/)**: `WebApi` / `WebPlanChain` pass anchors (page state + its undo), `WebLegSplit` replaced by core
  `LegSplit`, the same menu row with the app's string, `parity.json` entry.

## 5. The "same route everywhere" check
1. `PlanAnchorsTest` (core, fake engine, runs everywhere): expand / map back, every rule in §2, code round trip, an
   older reader (`PlanCode.decode(knownTags = 6)`) gets the same points.
2. `SameRouteTest` (core, real BRouter, where the rd5 tiles are – `ONPATH_PERF`-style switch, laptop + the planner CI if
   it has the tile cache): corpus = München–Venedig Bike + Hike T2 and two more long tours; 20 adds ≤ 2 km, 10 adds
   5–10 km with cuts, then a night in a span, a delete, an undo. Per case: **warm** (old legs cached, stitch via
   seed/cut) vs **cold** (empty cache, route the expanded list) → `RouteHash` (sha256 of the 1e-6 line only, §8.7) must be equal, distance within 0.5 %. The cold side reads the plan through `PlanCode` after the warm side's snap (§8.2, §8.10). The planner runs the same test compiled to Wasm (web/engine test) and must hit the same hashes,
   checked in as `core/src/test/resources/same-route/*.sha`. A mismatch fails the gate and turns on the §1 safety net.

## 6. Rollout
1. Core with `forInsert`, no UI, on the wip branch.
2. `SameRouteTest` green on JVM and Wasm – before any release reads tag 7 (§8.6).
3. Readers: tag 7, expansion, storage – app + planner route anchors but never create them (one release).
4. Writers: stitch on insert + transfers + „Ganz neu berechnen" (owner picture), app and planner in the same release.
5. Speed-up 3 cuts (same field, own corpus run).
6. Only if §5 found mismatches: idle re-route of cut legs.

## 7. Risks
- Sub-path invariant fails somewhere (BRouter heuristics, turn costs at the anchor) → warm and cold lines differ by a
  bit; caught by §5, healed by the safety net.
- A forgotten caller routes without anchors (navigation, `SyncedRouting`, `TrackToPlan`, thumbnails) → another line;
  mitigated by the one `ExpandedPlan` (§8.1), plus a test that every `Planner.plan` call site
  passes the plan's anchors.
- Routing-data update under an anchor (the way moved) → the route still passes the stored coordinate (a small bump);
  „Ganz neu berechnen" fixes it; acceptable.
- UI shows hidden points by mistake – they never enter the visible point list, only the Planner's input.
- Pre-feature apps silently drop anchors on re-save (Q2).

## 8. Agreed before code (2026-11-21; reasons: `docs/team/items/2026-11-21-detour-stitch-answer.md`)
1. **One list of legs.** `PlanAnchors.expand` returns `ExpandedPlan(points, pins, visibleIndex)`. `Planner`, `LegSplit.seed`, the planner's fetch list, „all legs fresh?“, `StoredLine.preview` and both alias steps all take it.
2. **Snap.** The alias runs over the expanded lists, old and new. §5's warm side is compared after the snap.
3. **Tour.** `TourEdits.followAhead`: the first point ahead loses its `before` anchors, all others keep theirs. `SyncedRoutingSlice` passes the tour's anchors. Own test (core feature).
4. **Visible numbers.** Error texts and the wait card count visible points and legs only.
5. **An anchor never makes a route worse.** A sub-leg without a route → that visible leg is routed once without its anchors and reported to `Problems`; the anchors stay stored until the next edit. The full leg enters the cache under its plain key only after it routed.
6. **Test before readers** (§6).
7. **Hash = the line only**; distance within 0.5 %. `LegSplit` shares totals in proportion, a cold device gets the router's own.
8. **Cap.** At most 500 anchors per plan (writer and reader); `LegCache.CAPACITY` 1,000; `StoredLine`'s temporary cache takes the expanded size; the planner's store and memory cache follow.
9. **Cuts stay out of plain plans.** A stitch's cut legs live under their own key (`LegCache.Key.cut`, also in `LegFiles` / `WebLegs` names and files), read only for a leg of an `ExpandedPlan` that touches an anchor. Routed legs stay under plain keys.
10. **Exact points (found while building).** `PlanCode` stores points in 1e-5°, the leg cache and anchors work in 1e-6°: after a link or sync a cold device would route `a→P` to a point up to ~0.7 m away, under another key. A plan with anchors therefore carries, first in tag 7, each point's remainder to 1e-6° (two small zigzag values, each −5…+5; out of range = the whole tag is dropped). Older readers skip it.
11. **`follow` never guesses.** Outside the common start and end, a point is matched by value only when that value occurs once on each side; otherwise it counts as new and loses its anchors. Losing anchors is safe (full routing), a wrong anchor is not. A rotated closed loop keeps its edges' anchors; a rotation with stops before the loop loses those of the affected sides (accepted).
12. **For the web wiring (laptop).** `WebApi.planParse` writes points with 5 decimals, which throws away the exact values of tag 7: it must keep 6. `web/app/src/share.ts` calls `qr.make()` without a guard and anchors make the link longer: check what it does on overflow before writers ship.

## Questions for the owner
1. Do speed-up 3's cut points also count for „Ganz neu berechnen" (row shows whenever the route keeps any point of an
   older line)? **Recommendation: yes** – one rule, one button.
2. An app from before this feature opens a stitched plan in full routing (slower, maybe slightly another route) and drops
   the anchors when it saves it; the plan is never lost. OK, with readers shipping one release before writers?
   **Recommendation: yes.**
3. Moving a stitched point: re-stitch when it stays ≤ 2 km of the line (fast), or always route in full (slow on long
   legs)? **Recommendation: re-stitch** – a move is an insert at another spot.

## Owner's answers (2026-11-21, laptop's chat)
1. Older apps: **yes** – readers ship one release before writers; an older app routes a stitched plan in full and drops the anchors on re-save (the plan is never lost).
2. A stitched point moved but still ≤ 2 km from the line: **stitched again** (a move = an insert at another spot).
3. Speed-up 3's cut points: **one rule** – „Ganz neu berechnen“ shows while the plan holds detours OR cut points and clears both.
