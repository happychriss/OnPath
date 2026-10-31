# UX concept: two modes – Plan and Navigate (owner decision)

Search mockups: `docs/ux-search-proposals.md` (proposal C's "Add to route" card is reused inside Plan mode).

## The two modes
| | **Plan** | **Navigate** |
| --- | --- | --- |
| Purpose | Build / change the route, look around, manage tours and settings | Walk or ride: map follows you, nothing changes by accident |
| Map taps | **Hold only** (0.3 s): map → add point (first one: "Start here / Go here (from my location)"), POI → "Add to route / Open in Google Maps", route point → point menu. Quick taps change nothing, nothing is dragged (see "Plan gestures") | **Nothing is added or moved.** POI tap shows info only. Pan/zoom allowed; "centre on me" re-follows |
| Shown | Plan sheet: activity, stats, profile, undo/redo, route actions in ⋯ (save/export/reverse/round trip/clear); top ☰: My routes, start recording, Settings (see "Menus") | Full map, route (line only – no numbered plan points, owner), position, top strip "to go: km · ↑m · time", GPS dot on the locate button |
| Switch | **"Start navigation"** – normal big button (safe direction). With a tour running: **"Continue navigation"** | **Slide-to-plan switch** at the bottom: "Tour menu" / „Tour-Menü“ with a small second line "Change route · Pause · End" / „Route ändern · Pause · Beenden“ (owner 2026-10-31; was "Plan" / „Planen“, before that "Edit route"). The small line only when it fits (large font: main line only, never cut); without a recording only "Change route" / „Route ändern“ (`ui/SlideToUnlock.kt`, `SlideLabel`) – the only way out of Navigate (protects against pocket/glove touches) |

## Rules
1. **Recording never stops when switching.** Starting navigation starts (or continues) the recording; going back to
   Plan keeps recording. A small bar in Plan shows "Tour running · 6.4 km · Continue navigation".
2. **Continue = smart re-route:** the route is recalculated from the current position to the remaining stops
   (stops already passed are dropped; a new point was placed by `WaypointInsertion`).
   **Plan edited during a tour (owner):** round trip, Undo, points added / removed, reverse – the tour
   follows the new plan **at once, without a question** (followed line, target and arrival change as soon as the
   route is computed; also while still in Plan). "Continue navigation" is just the way back to Navigate. A line that
   is the same route again (e.g. Undo to it) keeps the running session. A followed GPX track and a free recording are
   not changed by plan edits; a plan with fewer than two points changes nothing. A saved route cannot be loaded during
   the tour (owner 2026-10-31, see "My routes").
3. **Ending a tour:** (a) on arrival (within ~50 m of the destination) the app asks "You have arrived – end tour?"
   (End & save / Keep going), or (b) in Plan mode via "End tour" (with confirmation). Never from Navigate directly.
4. **Navigate without a route** = free recording (position + track only).
5. **App start:** opens in Plan; if a tour is still running (e.g. after a phone restart) it opens in Navigate.
   In Plan the map centres once on the position (`ui/StartCamera.kt`): a fresh fix at once, else after 4 s the last
   known position, else the saved camera stays (a fix within 60 s still centres). Once the user moved the map it
   never jumps. No following – the map stays where the user pans it.
6. Off-route warning only in Navigate (settings).
7. **After End tour:** "Tour saved" (Done / GPX / Delete); Delete asks "Delete “Name”?" first (stays until answered,
   Cancel goes back to "Tour saved").

## Menus (owner spec, rules in `ui/RouteRules.kt`)
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

**Plan sheet:** the primary button exists from two points: "Start navigation · Hike" with a route line (also a
straight-line fallback; disabled while the points wait for their line), "Continue navigation" while a tour runs. With
no point or one, a free recording is ☰ → Start recording. One rule for the words and what Start does (`PlanStart`,
owner 2026-10-30): nothing to follow = **"Start recording · Hike"** with the record dot – also in the track panel for a
track without a line (an empty recorded tour, a GPX with one point; during a tour its button is off). Its ⋯ holds everything about this route: Save route, Export GPX, Reverse,
Round trip, Clear (Clear is disabled while navigating). The sheet shows the save state as text, not as a message: "Saved as “Name”" or
"“Name” · changed, not saved".

**My routes** (`RoutesScreen`): one list of planned routes, recorded tours and imported GPX (type icon, name,
type + date, distance · ascent · time), newest first. Import GPX at the top (the imported track is then shown on the
map). Per item: tap = open (planned route with via points → into the plan; tracks → shown on the map), Export
and ⋮ with **Rename** (name dialog, the sheet's "Saved as" follows), Export (GPX share), Delete (asks "Delete “Name”?";
the dialog stays until Cancel / Delete, a tap outside does nothing).
**During a tour no new route is loaded** (owner 2026-10-31: "too much automation – as a hiker I wouldn't trust it";
`RouteRules.openAsk` → `BLOCKED_DURING_TOUR`). The list looks as always (no hint under the routes – nobody reads it);
a tap on a planned route is the action, the answer is a HINWEIS box under the tabs of My routes, over the list: **"End the
running tour first, then this route can be loaded."** / „Erst die laufende Tour beenden, dann lässt sich diese Route
laden.“ – **OK** (48 dp). It stays until OK or until My routes is left. Nothing changes: plan, followed route, activity
and the recording. Tracks open as before (shown as a track; "Start navigation · …" in the track panel follows one).
While the plan belongs to a saved route, the followed route carries its name ("Back to …", tour log).

**Loading over an unsaved plan:** "Replace current plan?" – **Save first** (the Save / Save as new choice or the name
dialog as above, then load) · **Replace**
(tap outside / Back = cancel). The plan counts as unsaved when it has two or more points and they differ (lat/lon)
from what was last saved or loaded (persisted, follows the snapping of points onto the way). Replacing is also one
undo step. Tracks open next to the plan without asking.

## Screen basics
- Plan is the home; My routes + Settings via the top bar in Plan (no separate Plan / Record / My tours bar).
- GPS status as a dot on the locate button (no GPS chip).
- Map controls (one group on the right): **compass always** (Plan: needle turned with the map, tap = north up;
  Navigate: walking direction / north up toggle), eye (only with a route / track), **Show whole route** ⛶ under the eye
  (owner 2026-10-31, same icon as the browser planner: only when there is a line – Plan: a shown track, else the plan,
  else the followed line; Navigate: the followed line incl. the lead-in to the start; hidden in the Points step), locate.
  A tap stops following, then fits the line + all route points north up and flat (not closer than z16, as the browser),
  clear of the top cards / message box and the sheet / slider (`FitPaddingRule`, the same padding as opening a route);
  Navigate also includes where you are.
  Following comes back only with locate. No box, no dialog (`ui/WholeRoute.kt`). The same fit runs by itself once
  when a plan link opens and when the plan gets its first route (first destination, "Go here") – never after later
  edits, not when the map was touched meanwhile (`WholeRoute.firstRoute`, field A-019). A search result is centred in
  the same free room. MapLibre's own compass is off.
- Own position in both modes: small blue dot with accuracy circle and a small direction triangle at its edge
  (MapLibre COMPASS render mode; direction = compass standing, GPS bearing moving – `map/HeadingEngine`); grey when stale.
- One primary action per mode: Plan → "Start navigation" ("Start recording" without a route), Navigate → slide "Tour menu" (back to Plan).

## Plan gestures (owner spec, `ui/MapGestures.kt`)
**Hold only.** A hold is ≥ 0.3 s (`HOLD_MS`) with a ring filling under the finger; the vibration comes the moment
the ring is full (not on release). A longer press is the same as a hold. Quick taps never change anything, and
nothing is dragged any more (points and the route line can't be moved by dragging).

| Hold on… | Vibration | Result |
| --- | --- | --- |
| Empty map, plan still empty (also after Clear) | 1× | Menu **Start here** · **Go here (from my location)** (start = GPS position, this spot = destination; disabled with "Location off" / "No location permission" / "Searching GPS…" without a fresh fix) |
| Empty map or the route line | 1× | New point (smart insertion). No message |
| POI that is not a route point | 1× | Menu **Add to route** · **Open in Google Maps** |
| Route point (marker; a POI that already is a stop counts as a marker) | 2× (double pulse) | Point menu, only sensible options: **Make starting point** (not on the start), **End route here** (middle points only), **Open in Google Maps** (named place / POI only), **Delete** (always; Undo brings it back) |

- Marker beats POI beats route line / map. Markers are hit in screen space (48 dp, nearest wins when they overlap).
- Menus are anchored next to their point, follow the map while panning (between the top controls and the sheet),
  close when their point leaves the map. **No Cancel button:** a tap anywhere else or Back closes a menu. A hold on
  another marker / POI switches to its menu; a hold on the empty map while a menu is open only closes it.
- Navigate (and a shown track): nothing is added or moved; a POI tap opens read-only details. Search results keep
  the place card. The empty plan sheet says how it works: "Hold the map to start there – or to go there from your
  location. Hold a point or place for options." (no "Start from my location" button – the first-hold menu does it).
- **No message disappears by itself** (owner rule for the whole app – rain, stress): no "point added" notices, no
  "hold to add" hint, no timed bubbles; a message box stays until it is answered / OK is tapped (no dark message bar).

## Messages (owner 2026-10-31, `docs/design/ui-style.md`)
Three kinds: **FRAGE** (a question box, blue edge, two verb buttons), **HINWEIS** (a box with one button – OK or the
one way out; grey, amber ⚠ or red ⚠ edge), **ANZEIGE** (no box: plan sheet lines, the off-route card, the download
chip, the grey pill, lines in My routes / Settings). All boxes are one look (`MessageCard`: white card, coloured left
edge), **one at a time** (a question first, the others wait) and sit **12 dp under the top element**:
- **Plan:** under the search pill + ☰ row – pairing step, mobile data?, map for here?, new version (Later / Install),
  "Paired." / "Unpaired.", a map failure (Try again), an error. The plan sheet is drawn over the box; while a box shows,
  the download chip and the grey pill wait.
- **Navigate:** under the turn card / status card – the re-route question and the activity-switch question (moved here
  from the bottom, 56 dp buttons; drawn over the map controls), "Notifications off" (OK; a tap = the setup step), an
  error. While the app is on screen the question is not a heads-up notification too (quiet channel, lock screen keeps it).
- **My routes / Settings:** under the tabs / title, over the list – "End the running tour first …", an error.

## Layout rule (owner)
Everything about **this route** lives in the route sheet; the **library and app** live at the top.
| Where | What |
| --- | --- |
| Search pill | only search |
| ☰ (round button right of the search pill) | My routes (open = load, import, rename, export, delete), Start recording (no route), Settings, Check for updates |
| Sheet, first line | activity button (icon ▾) · time · ⋯ (during a tour the activity button opens the check-and-switch sheet, owner) |
| Map buttons (right) | compass · eye · Show whole route ⛶ · Undo · Redo (Redo only when there is something to redo, owner) · locate |
| Sheet ⋯ | Save route (or Save / Save as new), Export GPX, Time details (plain; formulas in Settings → Diagnostics → Developer tools), Clear (also during a tour: ends the route guidance, Undo brings it back) |

## Activity while navigating (owner: "a wrong activity is a disaster")
- **Visible:** the Navigate status card starts with the activity icon (48 dp button):
  `[🥾]  7.4 km to go · 2 h 54 min` / `↑ 512 m ↓ 310 m` / `● Recording · …`. The plan sheet's primary button names it:
  **"Start navigation · Hike"**, **"Continue navigation · Gravelbike"**. Only the icon, "Resume" and the profile
  chevron (48 dp, right) are buttons – the rest of the card is not clickable (gloves).
- **Tap the icon → check-and-switch sheet** (`ui/ActivitySwitchSheet.kt`), stays until a choice is made (Back = Keep):
  "You are navigating as: Hike (T3)", then every other activity with how the **rest of the followed route** fits it –
  "✓ fits" or the problems ("⚠ 1.4 km not suited for Gravelbike, steps 40 m · +900 m") and the new time;
  a spinner per row while checking; "Keep Hike (T3)".
- **How it is checked (offline):** the rest of the tour is routed with BRouter for each activity in the background
  (position → remaining stops, like "Continue navigation"; a followed GPX track keeps its shape through vias every 2 km;
  15 s per activity, one after the other). The followed line has no per-way tags, so the fit is read from that route
  (`navigation/ActivityCheck`): parts of the followed rest it does not use (≥ 150 m and ≥ 3 %) = "not suited for
  <activity>" (no access, too hard, above the SAC limit – the profile avoids them); plus from its way types / surfaces:
  steps (bikes), unpaved (Bike / E-bike), rough trail (Gravelbike), busy road (Hike). Chosen because the same route is
  then the proposal – choosing needs no second wait.
- **Choosing an activity** shows that route as the dashed proposal with the usual persistent question (the box under the status card)
  ("New route for MTB – Rest of the tour 8.6 km (+1.2 km) · 38 min" · Take new route / Keep original, also in the
  notification, silent). It stays until answered, also while on the route. **Take** = the new line becomes the tour's
  line for the new activity (no grey line of the old one), the tour is stored with the new activity, the app switches
  (`selectActivity`), recording continues. **Keep** = nothing changes (off route → the normal way-back question).
  Free recording (no route): choosing switches at once. Works in every direction (Bike → Hike too).
