# UX concept: two modes – Plan and Navigate (owner decision 2026-10-18)

Replaces the earlier home / plan / record mix. Mockups of the old proposals: `docs/ux-search-proposals.md`
(proposal C's "Add to route" card is reused inside Plan mode later).

## The two modes
| | **Plan** | **Navigate** |
| --- | --- | --- |
| Purpose | Build / change the route, look around, manage tours and settings | Walk or ride: map follows you, nothing changes by accident |
| Map taps | **Hold only** (0.3 s): map → add point (first one: "Start here / Go here (from my location)"), POI → "Add to route / Open in Google Maps", route point → point menu. Quick taps change nothing, nothing is dragged (see "Plan gestures") | **Nothing is added or moved.** POI tap shows info only. Pan/zoom allowed; "centre on me" re-follows |
| Shown | Plan sheet: activity, stats, profile, undo/redo, route actions in ⋯ (save/export/reverse/round trip/clear); top ☰: My routes, start recording, Settings (see "Menus") | Full map, route (line only – no numbered plan points, owner 2026-10-22), position, top strip "to go: km · ↑m · time", GPS dot on the locate button |
| Switch | **"Start navigation"** – normal big button (safe direction). With a tour running: **"Continue navigation"** | **Slide-to-plan switch** "→ slide to plan" at the bottom – the only way out of Navigate (protects against pocket/glove touches) |

## Rules
1. **Recording never stops when switching.** Starting navigation starts (or continues) the recording; going back to
   Plan keeps recording. A small bar in Plan shows "Tour running · 6.4 km · Continue navigation".
2. **Continue = smart re-route:** the route is recalculated from the current position to the remaining stops
   (stops already passed are dropped; a new point was placed by `WaypointInsertion`).
   **Plan edited during a tour (owner 2026-10-19):** round trip, Undo, points added / removed, reverse – the tour
   follows the new plan **at once, without a question** (followed line, target and arrival change as soon as the
   route is computed; also while still in Plan). "Continue navigation" is just the way back to Navigate. A line that
   is the same route again (e.g. Undo to it) keeps the running session. A followed GPX track and a free recording are
   not changed by plan edits; a plan with fewer than two points changes nothing.
3. **Ending a tour:** (a) on arrival (within ~50 m of the destination) the app asks "You have arrived – end tour?"
   (End & save / Keep going), or (b) in Plan mode via "End tour" (with confirmation). Never from Navigate directly.
4. **Navigate without a route** = free recording (position + track only).
5. **App start:** opens in Plan; if a tour is still running (e.g. after a phone restart) it opens in Navigate.
   In Plan the map centres once on the position (`ui/StartCamera.kt`): a fresh fix at once, else after 4 s the last
   known position, else the saved camera stays (a fix within 60 s still centres). Once the user moved the map it
   never jumps. No following – the map stays where the user pans it.
6. Off-route warning only in Navigate; stays as today (settings).
7. **After End tour:** "Tour saved" (Done / GPX / Delete); Delete asks "Delete “Name”?" first (stays until answered,
   Cancel goes back to "Tour saved").

## Menus (owner spec 2026-10-18, rules in `ui/RouteRules.kt`)
Disabled items stay visible, greyed – nothing appears or vanishes by surprise. "Navigating" = Navigate mode or a
tour recording (also while looking at Plan during a tour).

**☰ (round button right of the search pill)** – library and app (see "Layout rule" below):

| Item | Enabled when | Does |
| --- | --- | --- |
| My routes | always | The routes list (below): tap = open / load |
| Start recording | no planned route (fewer than 2 points) and nothing recording | Free recording without a route (closes a shown track) |
| Settings, Check for updates | always (updates: only when the build supports them) | |

**Save route** (sheet ⋯): plan loaded from / last saved as a route that still exists: **Save** (overwrite it, name
stays) · **Save as new…** (name dialog); a tap outside / Back cancels. Otherwise the name dialog (new entry).
Rule: `RouteRules.saveMode`.

**Plan sheet:** the primary button exists only with a route ("Start navigation", disabled while calculating) or
while a tour runs ("Continue navigation"). Its ⋯ holds everything about this route: Save route, Export GPX, Reverse,
Round trip, Clear (Clear is disabled while navigating). The sheet shows the save state as text, not as a message: "Saved as “Name”" or
"“Name” · changed, not saved".

**My routes** (`RoutesScreen`): one list of planned routes, recorded tours and imported GPX (type icon, name,
type + date, distance · ascent · time), newest first. Import GPX at the top (the imported track is then shown on the
map). Per item: tap = open (planned route with via points → into the plan; tracks → shown on the map), Export
and ⋮ with **Rename** (name dialog, the sheet's "Saved as" follows), Export (GPX share), Delete (asks "Delete “Name”?";
the dialog stays until Cancel / Delete, a tap outside does nothing).
While navigating, planned routes cannot be loaded into the plan ("Can be loaded after the tour"); tracks can be shown.

**Loading over an unsaved plan:** "Replace current plan?" – **Save first** (the Save / Save as new choice or the name
dialog as above, then load) · **Replace**
(tap outside / Back = cancel). The plan counts as unsaved when it has two or more points and they differ (lat/lon)
from what was last saved or loaded (persisted, follows the snapping of points onto the way). Replacing is also one
undo step. Tracks open next to the plan without asking.

## UX cleanup that comes with it
- Remove the separate Plan / Record / My tours bar; Plan is the home. My tours + Settings via the top bar in Plan.
- Remove the eye button (not needed: Navigate never edits) – keep compact/mini sheet sizes in Plan.
- GPS status as a dot on the locate button; the big GPS chip goes away.
- Map controls (one group on the right): **compass always** (Plan: needle turned with the map, tap = north up;
  Navigate: walking direction / north up toggle), eye (only with a route / track), locate. MapLibre's own compass is off.
- Own position in both modes: small blue dot with accuracy circle and a small direction triangle at its edge
  (MapLibre COMPASS render mode; direction = compass standing, GPS bearing moving – `map/HeadingEngine`); grey when stale.
- One primary action per mode: Plan → "Start navigation", Navigate → slide to plan.

## Plan gestures (owner spec v3, 2026-10-18, `ui/MapGestures.kt`)
**Hold only.** A hold is ≥ 0.3 s (`HOLD_MS`) with a ring filling under the finger; the vibration comes the moment
the ring is full (not on release). A longer press is the same as a hold. Quick taps never change anything, and
nothing is dragged any more (points and the route line can't be moved by dragging).

| Hold on… | Vibration | Result |
| --- | --- | --- |
| Empty map, plan still empty (also after Clear) | 1× | Menu **Start here** · **Go here (from my location)** (start = GPS position, this spot = destination; disabled with "Location off" / "No location permission" / "Searching GPS…" without a fresh fix) |
| Empty map or the route line | 1× | New point (smart insertion, as before). No message |
| POI that is not a route point | 1× | Menu **Add to route** · **Open in Google Maps** |
| Route point (marker; a POI that already is a stop counts as a marker) | 2× (double pulse) | Point menu, only sensible options: **Make starting point** (not on the start), **End route here** (middle points only), **Open in Google Maps** (named place / POI only), **Delete** (always; Undo brings it back) |

- Marker beats POI beats route line / map. Markers are hit in screen space (26 dp, nearest wins when they overlap).
- Menus are anchored next to their point, follow the map while panning (between the top controls and the sheet),
  close when their point leaves the map. **No Cancel button:** a tap anywhere else or Back closes a menu. A hold on
  another marker / POI switches to its menu; a hold on the empty map while a menu is open only closes it.
- Navigate (and a shown track): nothing is added or moved; a POI tap opens read-only details. Search results keep
  the place card. The empty plan sheet says how it works: "Hold the map to start there – or to go there from your
  location. Hold a point or place for options." (no "Start from my location" button – the first-hold menu does it).
- **No message disappears by itself** (owner rule for the whole app – rain, stress): no "point added" notices, no
  "hold to add" hint, no timed bubbles; the general message bar stays until OK is tapped.


## Layout rule (owner, 2026-10-18)
Everything about **this route** lives in the route sheet; the **library and app** live at the top.
| Where | What |
| --- | --- |
| Search pill | only search |
| ☰ (round button right of the search pill) | My routes (open = load, import, rename, export, delete), Start recording (no route), Settings, Check for updates |
| Sheet, first line | activity button (icon ▾) · time · ⋯ (during a tour the activity button opens the check-and-switch sheet, owner 2026-10-23) |
| Map buttons (right) | compass · eye · Undo · Redo (Redo only when there is something to redo, owner 2026-10-23) · locate |
| Sheet ⋯ | Save route (or Save / Save as new), Export GPX, Time details (plain; formulas in Settings → Diagnostics → Developer tools), Clear (also during a tour: ends the route guidance, Undo brings it back) |

## Activity while navigating (owner, 2026-10-18: "a wrong activity is a disaster")
- **Visible:** the Navigate status card starts with the activity icon (48 dp button):
  `[🥾]  7.4 km to go · 2 h 54 min` / `↑ 512 m ↓ 310 m` / `● Recording · …`. The plan sheet's primary button names it:
  **"Start navigation · Hike"**, **"Continue navigation · Gravel bike"**. Only the icon, "Resume" and the profile
  chevron (48 dp, right) are buttons – the rest of the card is not clickable (gloves).
- **Tap the icon → check-and-switch sheet** (`ui/ActivitySwitchSheet.kt`), stays until a choice is made (Back = Keep):
  "You are navigating as: Hike (T3)", then every other activity with how the **rest of the followed route** fits it –
  "✓ fits" or the problems ("⚠ 1.4 km not suited for trekking bikes, steps 40 m · +900 m") and the new time;
  a spinner per row while checking; "Keep Hike (T3)".
- **How it is checked (offline):** the rest of the tour is routed with BRouter for each activity in the background
  (position → remaining stops, like "Continue navigation"; a followed GPX track keeps its shape through vias every 2 km;
  15 s per activity, one after the other). The followed line has no per-way tags, so the fit is read from that route
  (`navigation/ActivityCheck`): parts of the followed rest it does not use (≥ 150 m and ≥ 3 %) = "not suited for
  <activity>" (no access, too hard, above the SAC limit – the profile avoids them); plus from its way types / surfaces:
  steps (bikes), unpaved (touring / e-bike), rough trail (trekking), busy road (hike). Chosen because the same route is
  then the proposal – choosing needs no second wait.
- **Choosing an activity** shows that route as the dashed proposal with the usual persistent question
  ("New route for MTB – Rest of the tour 8.6 km (+1.2 km) · 38 min" · Take new route / Keep original, also in the
  notification, silent). It stays until answered, also while on the route. **Take** = the new line becomes the tour's
  line for the new activity (no grey line of the old one), the tour is stored with the new activity, the app switches
  (`selectActivity`), recording continues. **Keep** = nothing changes (off route → the normal way-back question).
  Free recording (no route): choosing switches at once. Works in every direction (Bike → Hike too).
