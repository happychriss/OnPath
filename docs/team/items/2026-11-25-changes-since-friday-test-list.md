# Changes since Friday 20.11. morning (0.18.2 → 0.18.10, plus 0.18.11 in work) and what to test live

Owner 2026-11-25: „a full list of changes and enhancements and what needs to be tested since Friday morning“ – he
tests on the full live system. ☐ = test on the phone / in the browser. Released: 0.18.2–0.18.6 (Fri 20.11.),
0.18.7 (Sat), 0.18.8 + 0.18.9 (Sun), 0.18.10 (Wed 25.11.). 0.18.11 = being built (plan:
[2026-11-25-release-0-18-11-plan.md](2026-11-25-release-0-18-11-plan.md)).

## 1. On a tour (recording + navigation)
- 0.18.10 **„Standort teilen“** – tour bubble above the map bubble: share button + ⚙ (tour settings); first tap asks
  for a name; tap again stops. Viewer page in the browser.
  ☐ share on a real walk with the screen off for a while · ☐ open the link on another phone/PC · ☐ stop sharing → the page
  shows the end · ☐ kill/continue the tour – the link keeps going
- Viewer page (online now, not in an APK): **countdown bar „nächster Punkt in 1:20“**, late → amber „wartet auf das
  Handy …“; heading **„Geteilter Standort“** when no name. ☐ watch the bar for two updates · ☐ phone in a tunnel/flight mode → amber
- 0.18.10 **GPS rests while the phone lies still** (motion sensor; GPS off after 1 h without moving).
  ☐ phone flat on a table 10 min during a tour – no GPS zigzag · ☐ pick it up and walk – recording resumes at once
- 0.18.10 **a single GPS jump** no longer gives a false off-route / arrival / arrival time. ☐ (only in the field, notice if it happens)
- 0.18.10 **clock set back during a tour** – „noch“ and arrival start anew.
- 0.18.10 **tour the system closed** counts its full distance on Continue **and** Save. ☐ kill the app mid-tour (swipe away), Continue, later Save – km right
- 0.18.10 **storage full** – the warning goes away when space is back.
- 0.18.8 **arrival no longer waits for a stop the route cannot reach.** ☐ plan with an unreachable stop, walk to the end – „Du bist angekommen“
- 0.18.7 **off the route the voice says side + metres** when sure („Die Route liegt rechts, 40 Meter.“). ☐ leave the route on purpose, both sides
- 0.18.7 **far start**: from 25 km a warning line; „Abbrechen“ during the search goes back to the question.
- 0.18.7 **a ride in the tour** → asked on Save (with / without); editing rides updates list picture, overview and map.
  ☐ a tour with a bus/lift part, Save, then edit the ride
- 0.18.5 **an interrupted tour always gets „Weiter / Speichern / Löschen“** after a restart.
  ☐ restart the phone mid-tour → the question comes
- 0.18.2 activity switch during a tour is retried; the tour keeps an activity changed on the way.

## 2. Planning (app)
- 0.18.10 **detours in plain words**: „Umweg 17,4 km notwendig, ② erst ab Wandern T2 erreichbar“ and
  „② macht 2,8 km Umweg – mit Wandern T2 kürzer“. ☐ a stop only reachable with a higher T level
- 0.18.10 **„Alten Plan wiederherstellen?“** when a new plan replaces the old one. ☐ make a plan, start a new one, take „wiederherstellen“
- 0.18.10 **„Zuletzt“ keeps activity + T level** of a replaced plan.
- 0.18.10 plans with **detour points from the browser planner** open and are routed through them.
- 0.18.4 **„Zuletzt“**: a plan opened from there and changed updates its entry (no second draft, nothing to the bin; Undo keeps older versions).
- 0.18.4 **a hold always gives a point** – also right after the first point, while its route is still calculated.
- 0.18.4 **a place from its menu shows its symbol at once** („Hier starten“, „Als Ziel“, „Hierhin“ on a hut …).
- 0.18.5 **a place's menu always opens**, also while the plan changes. 0.18.6 it opens **below the search bar**.
- 0.18.3 / 0.18.2 **a stop set right on the route splits it at once** – nothing recalculated, km / climb / time unchanged.
  ☐ long multi-day plan, add a stop on the line
- 0.18.7 clearer **day rows** on multi-day tours; holding the eye on a round-trip suggestion hides that loop.
- 0.18.2 **round trips**: the search waits in the wait window with steps + „Abbrechen“; touching the map no longer stops
  it; tap a card's text to read it all („▴ weniger“). ☐ one „Rundtour von hier“
- 0.18.2 a map area the plan no longer needs is not downloaded; areas on the 0° meridian / equator work.

## 3. Map symbols (POI) – core feature C19
- 0.18.7 tap/hold asks the map when the app's symbol list is out of date · 0.18.8 symbol presses are logged (no places)
  · 0.18.9 **answers right after the map has drawn**, also while following; a second press no longer sets a stray point.
  ☐ **tap and hold several symbols (phone in the hand and on a table), also right after panning** → then
  Einstellungen → Diagnose → export (donald reads `[poi] Press … end=`)

## 4. My routes
- 0.18.2 route pictures always show the map (all made fresh once), page dots, „Mehr“ instead of „Details“ (time bar
  with breaks, up/down speed, planned vs needed). ☐ open a few old tours

## 5. Browser planner (plan.onpath.earth – live with 0.18.10)
- 0.18.10 detour lines in plain words; the wait window blocks the map; plan sync on a weak connection: no „(Kopie)“,
  a cut connection = offline.
- 0.18.4 a click while the route is calculated always lands · 0.18.2 same wait window as the app, search filter by
  kind at once, no stale online results, tab icon, a point on the route splits the leg at once, no „3 minutes“ flash.
  ☐ plan in the browser, send to the phone, edit on both while weak network

## 6. Coming in 0.18.11 (being built – test after it is live)
- **Detour writers**: the app and planner set detour points themselves when a stop needs one; menu row **„Ganz neu
  berechnen“** (EN „Recalculate from scratch“, plan group, only when the plan has detour parts).
  ☐ a plan with a detour stop, move the stop, „Ganz neu berechnen“ · ☐ phone and browser show the same line
- **Navigation fixes**: no silent jump to a later leg on loops whose legs run close together; a passed stop no longer
  listed ahead; arrival not counted early by GPS noise; fewer wrong-side sentences.
  ☐ a loop where out and back run ~100 m apart, with stops
- **Privacy text** exact (names place search Photon + router brouter.de) – you see the lines first.
- **Planner**: restore-old-plan box; pairing via phone camera opens OnPath + one word „koppeln“; POI click on a slow
  phone after panning never adds a point; security headers. ☐ scan the pairing QR with the camera app
- Small: log wording, a far-start detail, Pixel-review leftovers that are still there.
