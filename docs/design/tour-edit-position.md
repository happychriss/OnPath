# Editing the plan during a tour: the position splits the plan

Status: **agreed with the owner 2026-10-31, not built yet** (backlog "Plan edits during a tour").
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
  everything before a passed point is passed too).
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
- Line **ahead** (position → ahead points) strong blue as today; the plan line **behind** pale.
- Passed points grey with a ✓ (numbers kept, so "stop 3" still means the same point).
- The plan sheet's summary counts from the position (time / km to go), like Navigate.

## 5. Tests first
Every row of §3 gets a unit test (pure rule: plan points + passed set + edit → ahead points / question), the field case
first. Core features C1 / C5 rows and `docs/release-rundgang.md` get the field case as a flow.
