# UX concept: two modes – Plan and Navigate (owner decision 2026-10-18)

Replaces the earlier home / plan / record mix. Mockups of the old proposals: `docs/ux-search-proposals.md`
(proposal C's "Add to route" card is reused inside Plan mode later).

## The two modes
| | **Plan** | **Navigate** |
| --- | --- | --- |
| Purpose | Build / change the route, look around, manage tours and settings | Walk or ride: map follows you, nothing changes by accident |
| Map taps | **Hold only** (0.3 s): map → add point (first one: "Start here / Go here from my location"), POI → "Add to route / Open in Google Maps", route point → point menu. Quick taps change nothing, nothing is dragged (see "Plan gestures") | **Nothing is added or moved.** POI tap shows info only. Pan/zoom allowed; "centre on me" re-follows |
| Shown | Plan sheet: named stops, stats, profile, actions (undo/redo/clear/reverse/round trip/save/GPX in ⋯), activity, My tours, Settings | Full map, route, position, top strip "to go: km · ↑m · time", GPS dot on the locate button |
| Switch | **"Start navigation"** – normal big button (safe direction). With a tour running: **"Continue navigation"** | **Slide-to-plan switch** "→ slide to plan" at the bottom – the only way out of Navigate (protects against pocket/glove touches) |

## Rules
1. **Recording never stops when switching.** Starting navigation starts (or continues) the recording; going back to
   Plan keeps recording. A small bar in Plan shows "Tour running · 6.4 km · Continue navigation".
2. **Continue = smart re-route:** the route is recalculated from the current position to the remaining stops
   (stops already passed are dropped; a new point was placed by `WaypointInsertion`).
3. **Ending a tour:** (a) on arrival (within ~50 m of the destination) the app asks "You have arrived – end tour?"
   (End & save / Keep going), or (b) in Plan mode via "End tour" (with confirmation). Never from Navigate directly.
4. **Navigate without a route** = free recording (position + track only).
5. **App start:** opens in Plan; if a tour is still running (e.g. after a phone restart) it opens in Navigate.
6. Off-route warning only in Navigate; stays as today (settings).

## UX cleanup that comes with it
- Remove the separate Plan / Record / My tours bar; Plan is the home. My tours + Settings via the top bar in Plan.
- Remove the eye button (not needed: Navigate never edits) – keep compact/mini sheet sizes in Plan.
- GPS status as a dot on the locate button; the big GPS chip goes away.
- One primary action per mode: Plan → "Start navigation", Navigate → slide to plan.

## Plan gestures (owner spec v3, 2026-10-18, `ui/MapGestures.kt`)
**Hold only.** A hold is ≥ 0.3 s (`HOLD_MS`) with a ring filling under the finger; the vibration comes the moment
the ring is full (not on release). A longer press is the same as a hold. Quick taps never change anything, and
nothing is dragged any more (points and the route line can't be moved by dragging).

| Hold on… | Vibration | Result |
| --- | --- | --- |
| Empty map, plan still empty (also after Clear) | 1× | Menu **Start here** · **Go here from my location** (start = GPS position, this spot = destination; disabled with "Location off" / "No location permission" / "Searching GPS…" without a fresh fix) |
| Empty map or the route line | 1× | New point (smart insertion, as before). No message |
| POI that is not a route point | 1× | Menu **Add to route** · **Open in Google Maps** |
| Route point (marker; a POI that already is a stop counts as a marker) | 2× (double pulse) | Point menu, only sensible options: **Make starting point** (not on the start), **End route here** (middle points only), **Open in Google Maps** (named place / POI only), **Delete** (always; Undo brings it back) |

- Marker beats POI beats route line / map. Markers are hit in screen space (26 dp, nearest wins when they overlap).
- Menus are anchored next to their point, follow the map while panning (between the top controls and the sheet),
  close when their point leaves the map. **No Cancel button:** a tap anywhere else or Back closes a menu. A hold on
  another marker / POI switches to its menu; a hold on the empty map while a menu is open only closes it.
- Navigate (and a shown track): nothing is added or moved; a POI tap opens read-only details. Search results keep
  the place card. The empty plan sheet says how it works: "Hold the map to add a point. Hold a point or place for
  options."
- **No message disappears by itself** (owner rule for the whole app – rain, stress): no "point added" notices, no
  "hold to add" hint, no timed bubbles; the general message bar stays until OK is tapped.
