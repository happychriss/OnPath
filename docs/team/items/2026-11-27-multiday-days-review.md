# Review: feat/multiday-days @ 47ba80dc4 (second model, 2026-11-27)

Reviewer: donald thread „Einstellungen übersichtlicher gestalten“ (captain's order). Read only – nothing fixed here; the
Mehrtagestouren thread decides and fixes. Focus: schema 11 (`tour_plan.day`), migration 10 → 11 with existing tours,
backup round trip, a tour that points at a deleted route.

## OK (checked, no change needed)
- **Migration 10 → 11:** `ADD COLUMN day INTEGER`, existing rows NULL; `stepsFrom(9)` re-adds a missing column quietly on
  every open, so a swallowed ALTER cannot leave version 11 without the column. `TourDbMigrationTest` covers it.
- **Downgrade:** `onDowngrade` is a no-op; an older app (schema 10) writes `tour_plan` with 14 columns – `day` stays NULL,
  no crash; going up again skips the existing column. Fine.
- **Write side is consistent:** `planArgs` drops `day` when `sourceUuid` is null, and `PLAN_DAY` reads `plan_uuid` only with
  a day and `plan_day` only with a source – both or neither.
- **Backup:** `TourPlan.toJson` writes `"day"`, `fromJson` reads it (`optInt`, > 0); an old backup restores with no day, an
  older app ignores the key. `BackupTest` round trip is there.
- **Route deleted:** `DayPick.route` / `named` skip a route in the bin → Gemacht shows the plan copy's name, `planOpens =
  false` (no dead link); restoring it from the bin brings the link back; deleting the recorded tour takes the ✓ away.

## Findings
1. **Days are matched by number only – an edit after riding moves the ✓ (should fix or decide).** `ridden()` maps
   `planUuid + planDay`. If the multi-day route is changed after day 2 was ridden – a night added / removed, „Tage“ or the
   day limits changed, a point inserted before day 2, Umkehren – the days renumber, but the recorded tours still say „day 2“:
   the ✓ and „Tag 3 von …“ then sit on another stretch, and `next()` highlights the wrong day. Options: store the day's two
   end points (or the day's start line point) in `tour_plan` beside `day` and only tick a row whose ends still match; or
   drop the ticks of a route whose days changed since the tour (compare the route's `updated` with the tour's
   `createdMs`). Owner question: after editing a route, keep „gefahren“ marks or start fresh?
2. **„Speichern als Kopie“ / a route re-imported gets a new uuid → its ridden days are gone.** Probably right (a copy is a
   new route), but say it in the kdoc so nobody „fixes“ it.
3. **The day sticks to a plan that left the day before recording.** `followedDay()` = `flow.day` while following and the
   part is a single day. If the user loads day 3, then adds or moves a point (the plan is no longer exactly day 3) and starts
   the recording, the copy is still stamped day 3 – check that an edit clears `flow.day` (the kdoc says „null = … the plan
   left the day“; I did not find the edit hook that sets it – please point a test at it: load day 3, insert a point, record →
   `planDay == null`).
4. **Not synced:** `planUuid` / `planDay` live only in `tour_plan` on the phone. The browser (and a second phone via sync)
   never sees ridden days. Fine for now – add a parity line for [laptop] if the planner's day table should show ✓.
5. **A recording that never ended** (`state = "active"` after a crash, then resumed) is excluded while active – correct; after
   „Unfinished → save“ it counts. No issue, just noting the test `ridden` has no case for it.
6. **Several tours ride the same day:** `maxOf(createdMs)` → the newest date is shown. OK; consider „2×“ later, not needed.
7. **Test gap:** no test for finding 1 (edit after riding) and finding 3 (edit before recording). Suggest both in `DayPickTest`
   / `TourFollowFlowTest` before main.

## Verdict
Schema 11, migration and backup: good to go. Before main: finding 3 needs a test (and a fix if it fails); finding 1 needs
the owner's word (or the end-point check).
