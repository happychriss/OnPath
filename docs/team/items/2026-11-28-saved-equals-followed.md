# Saved in the planner = followed on the phone? (read-only check, 2026-11-28)

Order: captain, after the owner 08:58 („die Route, die ich im Planer sehe, wenn ich auf Speichern drücke, … ist die, die ich
auf dem Handy bekomme“). Only code and docs read on `main`; nothing built. „Not found“ = searched, nothing there.
## 1. What travels: points, not the line
- A plan item carries the **plan code** (points, activity, T level, pins, names; multi-day: days, limits, night marks,
  description; detour anchors as tag 7) – no geometry: `core/…/routing/PlanCode.kt:68-97`, `docs/design/web-sync.md:101`,
  `core/…/plansync/RouteItems.kt:20-24, 54-64, 85-88`. Plus the fingerprint (field 7) and the rd5 dates (field 8),
  `docs/design/routing-fingerprint.md` §2.
- **Single tour:** the phone stores the points, then routes them itself with its own engine and leg cache and stores
  that line: `app/…/plansync/PlanSyncPass.kt:30, 259, 269` (`toRoute`) → `state/…/sync/SyncedRoutingSlice.kt:96-140`.
- **Day of a multi-day tour:** the whole trip travels as one plan code (days in tags 3–5). The phone cuts the day out of
  the trip's points on its **own** routed line (`core/…/domain/plan/DayFollow.kt:53-94`, `state/…/MultiDayFlow.kt:247-257`)
  and routes that day plan. Grey day ends are found on the phone's line too, so with another line they can land elsewhere.
## 2. Does the phone route again?
- **On arrival:** yes, always (above). **On opening:** every leg fresh in the phone's leg store → that line, no router run;
  else a stand-in and a new run (`state/…/routes/RoutesSlice.kt:205-216, 272-301`, `core/…/routing/StoredLine.kt:5-17`).
  After newer rd5 data arrives the open plan is routed again (`app/…/ui/AppViewModel.kt:1796`, fingerprint doc §3).
- **„Los geht's“:** a separate re-route was not found; Navigate follows the plan's current line (detour-stitch.md §3 „Navigation“).
- **Inputs:** the plan code + the phone's BRouter, profiles, `PLANNER_VERSION` (5), „Markierten Wegen folgen“ level and
  rd5 tiles (`core/…/routing/RoutingFingerprint.kt:21, 30, 46-49`). The line can differ from the planner when any of these
  differs: another app/planner version, another trails setting on the two devices, other rd5 dates for the plan's tiles.
- **Grey line** „Im Browser mit einer anderen OnPath-Version geplant – Route kann leicht abweichen“
  (`strings/…/values-de/strings_sync.xml:60`): shown when a plan saved by **another device** is opened and its field 7
  differs from the phone's fingerprint, both readable (`core/…/plansync/SyncRouting.kt:58-60, 74-80`,
  `state/…/sync/SyncedRoutingSlice.kt:58-73`). It covers BRouter version, planner version, profiles and trails level
  only – **not the rd5 dates** (those trigger a quiet data refresh instead) and not the stitch state. So „no grey line“ does
  not prove the same line. It goes with the next edit.
## 3. Stitch (part re-planned, e.g. a hotel 2 km off)
- **Held state:** hidden via points („anchors“) per plan point, `Anchors(before, after)` – `docs/design/detour-stitch.md` §1.
  They are stored and synced in the plan code, tag 7 (`PlanCode.kt:87-97`, `RouteItems.kt:28-29, 60-61, 87`); not in GPX.
- **Can it differ from a full re-route?** Yes, by design: anchors force the route through stored points, a full re-route
  of the visible points does not (detour-stitch.md §1, option D). Between the editing device and a cold device the same
  anchors give the same line only after the **idle settling** (§8.15, owner's answer 4): app `PlanRecalcSlice.kt:173`
  (`routeTaken`), `core/…/routing/Planner.kt:260` (`settle`), `LegCache.kt:27-35`; planner `web/app/src/main.ts:1930-1942`.
  Save waits at most one leg for it (`main.ts:1936`): a Save before the settling ends can store points whose phone line
  differs slightly from the shown one (inference from the code, not measured).
- **Test:** `tools/routing/jvm-test/SameRouteTest.kt:1-25` (warm editing device vs cold device, RouteHash of the line, 4 Alpine
  tours) + `web/app/test/same-route-wasm.mjs:1-10` (the planner's Wasm must hit the same hashes,
  `core/src/test/resources/same-route/*.sha`). It compares stitched warm vs cold, **not** stitched vs „Ganz neu berechnen“.
  Needs `E10_N45.rd5`, 1–2 h; not in `.github/workflows/` or `tools/ci/gate.sh` (grep: nothing). Last run: backlog.md:90
  (2026-11-26, verify 112/112).
## 4. „Ganz neu berechnen“
- App: `state/…/plan/PlanEditState.kt:163-206` – clears all anchors (detours and cut points) of the current profile in one
  undo step, routes the visible points again; row only while such anchors exist and no tour runs (`MapScreen.kt:378`,
  `PlanTopBar.kt:221`). Not in `PlanRecalcSlice`.
- Planner: the same row, `web/app/src/main.ts:168, 2691-2717` (engine `recalcRow` / `recalcScratch`).
- Other line: whenever a stitched route is not what a full routing of the visible points gives – it is meant to change the
  line. If the planner's user pressed it and saved, both sides route the same plain points.
## 5. Test „planner line after Save = Navigate line on the phone“
- Not found. Nearest: SameRouteTest (JVM vs Wasm, cold, same plan code – the router side only, no sync, no settings, no
  Navigate) and `SyncRoutingTest` / `SyncMailboxE2ETest` (the fields through the mailbox, no line compared).
- Smallest place: a core JVM test with the fake engine (as `PlanAnchorsTest`): the planner side's `SharedPlan` with
  anchors → `PlanCode.encode` → a `SyncItem` → `RouteItems.planContent` → `PlanAnchors.expand` → `Planner.plan` with an
  empty `LegCache`, line hash equal to the editing side's settled line; once more for a day via `DayFollow.part`. The real
  router part stays SameRouteTest's; putting its `MODE=verify` into a CI job with the cached tile would guard it.
## Open for the captain
1. The grey line does not cover other rd5 dates for the plan's tiles – say it too, or keep it quiet as now?
2. Should Save wait for the settling to finish (planner and app), so the stored plan is the one shown?
