# UX concept: two modes – Plan and Navigate (owner decision 2026-10-18)

Replaces the earlier home / plan / record mix. Mockups of the old proposals: `docs/ux-search-proposals.md`
(proposal C's "Add to route" card is reused inside Plan mode later).

## The two modes
| | **Plan** | **Navigate** |
| --- | --- | --- |
| Purpose | Build / change the route, look around, manage tours and settings | Walk or ride: map follows you, nothing changes by accident |
| Map taps | Hold 0.3 s adds a point (smart insertion), POI tap → small Google Maps bubble, POI hold → added as stop, marker tap → small "Delete point / Cancel / End route here" overlay, long press drags points / line (see "Plan gestures") | **Nothing is added or moved.** POI tap shows info only. Pan/zoom allowed; "centre on me" re-follows |
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

## Plan gestures (owner spec 2026-10-18, `ui/MapGestures.kt`)
Marker beats POI beats route line beats empty map. Markers are hit in screen space (26 dp, nearest marker wins
when they overlap zoomed out). The overlays follow the map while panning and close when their point leaves the map.

| | quick tap | hold ≥ 0.3 s (ring + vibration) | long press |
| --- | --- | --- | --- |
| empty map | hint "Hold briefly to add a point" (once) | add point (smart insertion) | add point |
| route line | as empty map | add point (inserted into that leg) | drag out a new stop |
| POI | bubble: name + "Open in Google Maps" (closes after 8 s) | add as pinned, named stop | add as pinned, named stop |
| marker (incl. a POI that is a stop) | overlay: Delete point / Cancel (+ End route here for via points) | same overlay | drag the point |
| with an overlay open | closes it (a tap on another marker / POI switches) | closes it | marker: drag; else closes |

Navigate (and a shown track): nothing is added or moved; a POI tap opens read-only details. Search results keep
the place card. The plan sheet has no stop list any more – points are managed on the map. Notices only when the
app guessed (a stop in the middle): "Added as a stop on the way" / "Added between A and B" · "End here instead".
