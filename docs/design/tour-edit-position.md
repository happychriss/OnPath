# Editing the plan during a tour: the position splits the plan

Status: **built 2026-10-31** (`navigation/TourSplit`, wired in `AppViewModel.change` / `step` / `followEditedPlan` / `addPoint` /
`answerRouteBack`; map: `MapController.setWaypoints(passed)`). Check on the next ride.
**Owner 2026-11-01: no grey route while planning** ("das Planen mit der grauen Route klappt überhaupt nicht – wir lassen
die blaue Route"; the blue line had vanished): in Plan during a tour the plan's route is drawn blue as without a tour,
the followed route stays blue, nothing pale / grey (§4).
Field case (owner, Hilden, bike, 2026-10-31): plan 1 → 2 → 3, he had ridden past 1 and 2 on another way; deleting 3
left the route 1 → 2 – short, behind him, under the orange track – and the app said nothing. He read it as "the whole
route is gone".

## 1. The rule (owner)
During a tour the user's position is a **virtual point** in the plan. It splits the plan points into
- **passed** – the points behind the user, and
- **ahead** – the rest, in plan order.

The tour follows **position → ahead points**. The virtual point is never drawn as a numbered point, never saved, never
in the Points list. Without a tour (plain planning) the position plays no part – everything stays as today.

## 2. When a point counts as passed
Today (`Navigator.markVisited`): within `NavSession.VISITED_M` = 40 m of the point during the tour. Two gaps, both in the
field case:
1. Riding past at 45 m does not count (2 in the field case lay about 40–50 m beside the track).
2. A plan edit starts a fresh `NavSession` and **`visited` is not carried over** (`NavSession.afterPlanEdit` carries only
   `reached` / `lead`) – after any edit every point counts as ahead again.

New:
- passed = came within **40 m (hike) / 60 m (bike activities)**, **or** the route progress went past the point (the
  point's position along the followed line ≤ the furthest progress on it), **or** a later point is passed (plan order:
  everything before a passed point is passed too), **or** (owner 2026-10-31) the user is clearly closer to the next
  point than to this one: straight-line distance to point N+1 at least **100 m** less than to point N, and N itself
  at least **200 m** away (clearly left behind). Catches the field case – off the route on another way, never close,
  no progress on the line: 2 lay ~870 m away, 3 ~590 m → 2 passed. Guards: only consecutive points; not before the
  route was reached (a tour started far away still counts from the start, C5); a round trip's goal never; on a
  hairpin trail the 100 m margin keeps a point below a switchback ahead.
- Carried over every plan edit, matched by **coordinate** (not by index – indices shift on delete / insert).
- In doubt a point is **ahead**: a wrong "ahead" shows as a line leading back to it, visible and fixed by deleting it;
  a wrong "passed" would hide a point silently.

## 3. Cases during a tour
Example plan 1 → 2 → 3 → 4, the user between 2 and 3 (1, 2 passed).

| Edit | Result |
| --- | --- |
| Delete 3 (the next point) | follow position → 4, at once, no question |
| Delete 4 (the destination) | follow position → 3; 3 is the destination |
| Delete 1 or 2 (passed) | ahead unchanged: **no new route, no message**; only the line behind changes |
| Add a point | it goes only between ahead points (or after the last), never behind the user |
| Move a point ahead | position → … recomputed |
| Off route | as today (C5): re-route from the position to the ahead points |
| Undo / Redo | point and the followed route as before (`FollowHistory`) |
| Before 1 (just started), delete 1 | position → 2 → 3 → 4 (the user is the start) |
| Save during the tour | the real plan points only, never the position |
| **Nothing ahead any more** (field case: delete 3 after passing 1 and 2; also a 2-point plan's goal deleted) | FRAGE **"Kein Ziel mehr vor dir."** – [Zurück zum Start] (route position → plan start, the tour follows it) / [Nur aufzeichnen] (guidance ends, recording goes on); Undo brings the point and the route back. Replaces `RouteBack` (today only for exactly 2 points) – one rule for both |
| All points deleted | as today: guidance ends, recording goes on, Undo restores (`PlanGuidance`) |
| Round trip 1 → 2 → 3 → 1, user between 2 and 3: delete 3 | position → 1 |
| Round trip, delete 1 (start = goal ahead) | the loop stays closed at the next point: 2 is start and goal, position → 3 → 2 |

## 4. On the map (Plan during a tour)
- The plan's route **blue**, as without a tour (never hidden); the followed line (position → ahead points) blue too.
  No pale / grey lines in Plan (owner 2026-11-01). Navigate keeps a re-route's thin grey original.
- Passed points grey (numbers kept, so "stop 3" still means the same point; no tick – owner 2026-11-01).
- The plan sheet's summary counts from the position (time / km to go), like Navigate.

## 5. Messages (FRAGE / HINWEIS / ANZEIGE, `docs/design/ui-style.md`)
What the map shows is not said again. Undo stays on the map in every case.

| Case | Message |
| --- | --- |
| Point ahead deleted / moved / added, points still ahead | none – the routing ring at the position, then the new line |
| Destination deleted, a point ahead becomes the destination | none – its red marker |
| Passed point deleted | none – only the plan line behind changes |
| A point counts as passed | none – grey point with ✓ |
| Nothing ahead any more | FRAGE "Kein Ziel mehr vor dir." · "Die Tour wird weiter aufgezeichnet. Rückgängig holt den Punkt zurück." – [Zurück zum Start] / [Nur aufzeichnen]; replaces `nav_route_back_title` / `_text` |
| … Zurück zum Start without a position | HINWEIS red, existing `msg_route_back_no_position` [OK] |
| … Nur aufzeichnen / all points deleted | ANZEIGE: status line "Tour läuft · nur Aufzeichnung" while it holds |
| No route to the points ahead (no data / no way) | HINWEIS red "Keine Route zu Punkt N gefunden. Die Tour wird weiter aufgezeichnet." [OK]; the guidance followed so far stays |
| Off route | unchanged (C5) |

## 6. Separation from the shared planner (owner 2026-10-31)
The browser planner and the app share the plan computation (web `appFiles`: `routing/Planner`, `PlanResult`,
`PlanEdits`, `PlanPoints`, `WaypointInsertion`, `RoutingContract` …). The position split is **only the app's tour** –
it decides *which part* of the plan is followed, never *how* a plan is computed or edited.
- New pure file `navigation/TourSplit.kt` (app only, **not** in the web `appFiles`): plan points + passed set +
  position → ahead points, or "nothing ahead". Passed-point rules (§2) live there too.
- The shared files stay unchanged: the plan is edited exactly as in the browser (`PlanPoints.delete` / `deleteAt` /
  `move`, `WaypointInsertion`), and the followed line is computed by the same `PlanResult` for the points
  `[position] + ahead`. "A new point only between ahead points" = `TourSplit` hands `WaypointInsertion` the ahead part
  only and maps the index back – `WaypointInsertion` itself does not know about tours.
- Guard: `SharedCoreRuleTest` – no file in the web `appFiles` list may reference `TourSplit` (or the position split);
  the web planner behaves exactly as before.

## 7. Tests first
Every row of §3 gets a unit test (pure rule: plan points + passed set + edit → ahead points / question), the field case
first. Core features C1 / C5 rows and `docs/release-rundgang.md` get the field case as a flow.
