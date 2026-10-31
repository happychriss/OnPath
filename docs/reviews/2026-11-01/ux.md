# OnPath pre-release UX review – first-time tester view (2026-10-31)

Reviewer focus: new-user logic, consistency vs Komoot / OsmAnd / Google Maps, EN+DE texts, app vs web planner.
Read-only review: code, docs, string resources, committed Paparazzi screenshots. S = severity, C = certainty.

## BLOCKER

### 1. Empty plan says "Select a point" – but a tap does nothing (hold only)
- Where: Plan, empty sheet – `strings/…/values/strings_plan.xml:59` `plan_hint_first_point` ("Select a point on the map, where to start or where to go." / „Wähle einen Punkt auf der Karte …“), used in `ui/PlanStatus.kt:51`; screenshots `planEmpty`, `cellCardFirstStart`, `overviewHint`. Gesture rule `ui/MapGestures.kt:63-64` (TAP → `Action.None`).
- Scenario: a tester opens the app for the first time and reads "Select a point". They tap the map (as in Komoot, Google Maps, and OnPath's own web planner). Nothing happens: no ring, no vibration, no hint. They tap again or give up and say "I can't plan a route".
- The doc (`docs/ux-two-modes.md:142`) promises "Hold the map to start there – or to go there from your location. Hold a point or place for options." The string never got that text. After one point the hint does say "Hold the map …" (`plan_hint_destination`), so the very first step is the only one that misleads.
- Suggestion: change the text to the doc's wording (EN + DE: „Halte die Karte gedrückt …“). Also consider one small grey hint after 2–3 quick taps on the empty map ("Hold to set a point"). It is not a timed toast; it stays until the first hold.
- Role: cloud-app. C: high.

### 2. "Tour menu" slide lands on a screen with no menu and no Pause / End
- Where: Navigate slider `ui/SlideToUnlock.kt:86-140`, `plan_slide_to_plan` "Tour menu" + sub-line "Change route · Pause · End" (`strings_plan.xml:177-178`); the result is screenshot `planTourRunningCollapsed`.
- Scenario: at the summit the tester wants to end the tour. They find "Tour menu – … End" and slide it. They get the Plan map with one row "● Tour running · 1.7 km · 0:14:38 [Continue]". There is no menu, no End and no Pause. The obvious button is Continue, which takes them back to Navigate, so they go round in a loop. End tour only shows after dragging the sheet up, which nothing hints at.
- Also: tapping the slider does nothing (only a drag works, no nudge animation). The label "→ Tour menu" looks like a button, while Komoot and phone lock screens say "Slide to …".
- Suggestion: after the slide, open the sheet at the step with [Pause] [End tour] visible (the first step is now too small for what the label promises). Or rename the slider to what it does ("Slide to change route" / „Zum Ändern schieben“). Give a tap on the slider a short thumb bounce plus the word "Slide".
- Role: cloud-app. C: high (code + screenshot).

### 3. Every fresh install asks "Earlier tracks and routes? … Restore"
- Where: first start, `ui/AppViewModel.kt:4665-4672` (`offer = !restoreOffered && db.countAll()==0`); screenshot `restoreOffer`.
- Scenario: a brand-new tester who has never used OnPath gets, right after the welcome page, a modal about restoring backups from Documents/OnPath with "Use this folder" and "Allow" instructions. Many will tap Restore (the right-hand, "positive" button), land in Android's folder picker, and get lost or grant folder access for nothing. It is the second screen they ever see.
- Suggestion: offer it only when a backup is likely (e.g. a MediaStore query finds OnPath GPX files in Documents/OnPath, if that is possible after a reinstall); otherwise reword it: title "Used OnPath before?", buttons "No, I'm new" (primary) · "Restore". Alternatively move it to My routes' empty state.
- Role: cloud-app. C: high that it shows to everyone; medium on how many tap Restore.

## HIGH

### 4. App and web planner use opposite gestures: tap+drag (web) vs hold, no drag (app)
- Where: web `web_hint_first_point` "Click the map …", `web_marker_title` "click for options, drag to move" (`web/strings/values/strings_web.xml:41,43`), `web_about_how_1`; app `MapGestures` hold-only, no dragging.
- Scenario: a tester plans in the browser first (pairing invites this), drags a point to fix the route, then opens the phone. Tapping and dragging do nothing. Deleting a point means hold → menu → Delete, then hold again to add. "One product" breaks on the most basic action.
- Suggestion: a deliberate difference (touch vs mouse) is fine, but say it. Add one line in the app's empty-sheet hint and in the web's About "On the phone: hold instead of click". Longer term, consider "hold a point → Move" in the app's point menu.
- Role: cloud-app + cloud-plan. C: high.

### 5. Quick tap on a POI / hut in Plan does nothing (in Navigate it shows details)
- Where: `ui/MapGestures.kt:63-64`. Not editable + TAP on POI → details; editable (Plan) + TAP → `None`.
- Scenario: the tester sees a hut icon and taps it to read its name or opening hours, as in every map app. Nothing happens in Plan, but the same tap in Navigate opens details. They decide POIs "don't work". Holding a POI gives "Add to route · Open in Google Maps", still not the details.
- Suggestion: a quick tap on a POI in Plan opens the same read-only details sheet (with "Add to route" in it). That changes nothing in the plan, so the "taps never change anything" rule still holds.
- Role: cloud-app. C: high.

### 6. "✕ Close" next to "End tour" during a tour – ambiguous, and doc vs code disagree
- Where: Plan during a tour, screenshot `planTourRunning`: [Pause] [End tour], then right below "✕ Close · Reverse · Round trip". `plan_close_plan` = "Close" / „Schließen“ (`strings_plan.xml:84`), `AppViewModel.clearPlan()` `:2538-2549`.
- Scenario: the tester wants to "close" the tour screen and taps ✕ Close. The plan disappears from the map, but the recording runs on. Per the code, the followed line also stays while recording (`if (… recState.value == null) clearFollow()`), and rule 2 says fewer than two points change nothing. So they see an empty plan and a Navigate screen that still guides along the old line. `docs/ux-two-modes.md` (layout table) says instead "Close … during a tour: ends the route guidance". In Komoot/Google Maps an ✕ in a sheet closes the panel, not the data.
- The doc calls it "Close plan", but the shipped label is just "Close". The same ✕ icon also deletes single rows in the Points list (`planPointsFull`).
- Suggestion: label it "Close plan" / „Plan schließen“. During a tour, either hide it or name it "Stop guidance" and make it do exactly that. Fix the doc/code contradiction.
- Role: cloud-app. C: medium-high (behaviour during the tour inferred from code + docs).

### 7. Two different off-route questions with different words and button order
- Where: Navigate, screenshots `navigateRerouteQuestion` ("You're 120 m off the route" · [Keep original] [New route]) vs `navigateRerouteSkipStop` ("Off route – re-plan?" · [Yes] / [Yes, skip stop 3] / [Keep original]). `strings_navigate.xml:58,93`.
- Scenario: wet hands, moving, glance at the phone. Once the safe "keep" is on the left and the action on the right. Next time a big "Yes" is on top and "Keep original" at the bottom. "Re-plan?" / „Neu planen?“ also sounds like "go to Plan mode" (the slider is "Switch to plan mode" / „Zum Planen wechseln“). Testers will tap the wrong one.
- Suggestion: one question shape. Title "You're 120 m off the route", buttons "New route" / "New route without stop 3" / "Keep original", always in the same order and position. Drop "re-plan" / „neu planen“.
- Role: cloud-app. C: high.

### 8. Share and Export are in the web sheet but hidden in the app (My routes → ⋮ only)
- Where: web `main.ts:159` (Share button in the plan sheet); app: Share plan / Export GPX only in My routes → ⋮ (`docs/ux-two-modes.md:80`).
- Scenario: a tester plans a route on the phone and wants to send it to a friend or to Komoot. The sheet and ☰ have no Share or Export. They must Save (name dialog), open My routes, find the row, tap ⋮ and then Share plan. Most testers won't find it, and Komoot/Google Maps put share on the route itself. In the browser the button sits right on the sheet.
- Suggestion: add "Share" (link + GPX) to ☰ next to Save route, or to the sheet's action row. At least make app and web match.
- Role: cloud-app (+ cloud-plan for parity). C: high.

### 9. Hint texts and errors tell users to "move" a point – the app cannot move points
- Where: `plan_err_too_far` / `plan_err_not_mapped` "Move it closer to a path", `plan_err_no_route_for_try` "Try moving a point", `plan_err_island` "Move it.", `plan_err_timeout_hike` "move the point" (DE „Verschiebe …“) – `strings_plan.xml:202` ff. Also `plan_point_move_desc` "Move point (hold and drag)" is only for reordering in the list.
- Scenario: "Hütte is too far (180 m) from any path … Move it closer". The tester tries to drag the marker on the map and nothing happens (by design, no dragging). They are stuck.
- Suggestion: reword to the real action, e.g. "Delete it and hold the map closer to a path" / „Lösche ihn und setze ihn näher an einem Weg neu“. Or offer a "Snap to nearest path" button in the error.
- Role: cloud-app. C: high.

### 10. Same thing named route / plan / tour / track inconsistently
- Where (examples): "Close plan" (doc) vs "Replace current **route**?" (`routes_replace_title`) vs doc "Replace current **plan**?"; "Share **plan**" vs "Save **route**"; the "Done" tab lists "**Recorded track**" (`routes_kind_recorded`) while its empty text says "No **tours** recorded yet" (`routes_empty_done`); "End **tour**" → "**Track** saved" (`routes_track_saved`); C12 doc "Split into two **tours**" vs string "Split into two **tracks**" (`nav_ride_split_two`); "Plan links" vs "route from the link"; DE "Gemacht" tab with „Aufgezeichneter Track“.
- Scenario: a tester can't tell whether a "plan", a "route" and a "tour" are one thing or three. For example, they look for a recorded "tour" in My routes and find "Recorded track", or wonder whether "Close plan" deletes the saved "route".
- Suggestion: a small glossary, then fix the strings. **Route** = something planned (saved or not, never "plan" in the UI). **Tour** = something done/recorded (also "Tour saved", "Split into two tours", "Recorded tour"). **Track** only for imported GPX lines. Put it in `docs/i18n.md`.
- Role: cloud-app (+ cloud-plan, shared strings). C: high.

## MEDIUM

### 11. Spoken directions say "on" in Settings but stay silent until a test in Phone setup
- Where: `data/AppSettings.kt:57-62` (voiceBike/voiceHike default true, speaks only after `Prefs.voiceHeard`), `settings_nav_voice_sub` (`strings_settings.xml:162`).
- Scenario: the tester chose "Later" on the welcome page or skipped the voice step. Settings → Navigation shows "Spoken directions when hiking: on". On the trail the phone never speaks. They report "voice is broken". The sub-line hint is easy to miss.
- Suggestion: until the test was heard, show the switch state as "Needs a quick test" with a Test button right there, or speak anyway at normal volume (the test is only a volume check).
- Role: cloud-app. C: high.

### 12. "Go here" in the first-point menu doesn't say "from my location"
- Where: screenshot `firstPointMenu`, `plan_go_here` "Go here" / „Hierhin“ (`strings_plan.xml:167`); doc specifies "Go here (from my location)".
- Scenario: the tester holds the map at the hut and sees "Start here" and "Go here", which sound like two names for the same thing. They pick "Go here" and are surprised that the route starts at their home. Or, without GPS, the item is greyed with no idea why it matters.
- Suggestion: "Go here from my location" / „Von meinem Standort hierhin“ (two lines are fine).
- Role: cloud-app. C: high.

### 13. "Round trip" doesn't generate a loop (Komoot meaning) and is greyed with one point
- Where: `planOnePoint` screenshot (Round trip disabled), `plan_round_trip`.
- Scenario: Komoot users know "Round trip" as "make me a loop of X km from here". With one point set they tap it, but it is greyed. With two points it only adds the way back to the start. Testers will call it broken or useless.
- Suggestion: name it "Back to start" / „Zurück zum Start“ (that is what it does), or add a line in the button's description. Keep "Round trip" for a future generator.
- Role: cloud-app (+ web, same string). C: medium.

### 14. Arrival: a centred system dialog with Delete beside Save
- Where: screenshot `arrival` ("You have arrived – end tour?" · Keep going · **Delete** · Save) and `endTourConfirm` (Cancel · Delete · Save).
- Scenario: a tired tester at the destination wants "End" and sees three text buttons, one of them a red Delete for the whole recording, next to Save. The doc says End & save / Keep going. The dialog is also the old AlertDialog style, not the app's message card under the status (`docs/design/ui-style.md`).
- Suggestion: arrival = "End & save" (primary) · "Keep going". Delete only later ("Tour saved" → Delete already exists).
- Role: cloud-app. C: high (screenshot).

### 15. Words owners banned still reach users: "routing data", "routing profiles"
- Where: `plan_from_track_failed` "… the routing data may be missing there", `plan_from_track_wait` "Routing data for this track is loading", `plan_err_no_data` "No routing data for this area.", `plan_err_profiles` "Routing profiles could not be installed", `plan_err_engine` "Routing engine …", `sync_routing_other` "with other routing"; web `web_planned_in_data` "routing data X MB". The owner rule is "never the words routing data – it's 'the map'".
- Scenario: a tester taps "Plan a route from it" on an imported GPX and reads "routing data may be missing". They don't know what to download or where.
- Suggestion: "The map for this track is still loading – try again in a moment." / "No detailed map here yet." Same in DE.
- Role: cloud-app (+ cloud-plan for the web line). C: high.

### 16. Web promises sync "while OnPath is open on the phone" – the app only polls on My routes / Browser sync
- Where: web `web_phone_how_2` (`strings_web.xml:185`) vs app `SyncPoll` (every 60 s only on My routes / Browser sync, not on the Plan map; `docs/core-features.md` C16). The paired note "What you save in the browser comes to your phone" says no when either.
- Scenario: the tester saves a route in the browser and looks at the phone, which shows the Plan map and is open. Nothing arrives, minutes later still nothing. They think sync is broken. It arrives only when they open My routes.
- Suggestion: change the web text to "… when you open My routes in the app (or the app starts)". Or poll on Plan too while paired and the browser is live. The web also has "Sync now" while the app deliberately has none: fine, but mention "Open My routes on the phone".
- Role: cloud-plan (text) / cloud-app (poll). C: high.

### 17. Plan's time and Navigate's remaining time look different and the "to go" label is missing
- Where: Plan "4 h 36 min · 12.5 km" vs Navigate status "2:54 h 7.4 km" (screenshot `navigate`). The doc specifies "7.4 km to go · 2 h 54 min".
- Scenario: the tester can't tell whether "2:54 h 7.4 km" is time walked or time left (the Plan sheet during a tour shows "1.7 km · 0:14:38" for elapsed). "2:54" also reads like a clock time.
- Suggestion: "7.4 km to go · 2 h 54 min" (DE „noch 7,4 km · 2 h 54 min“), the same format as Plan.
- Role: cloud-app. C: medium (doc vs screenshot).

### 18. DE: two words for recording – „Aufnahme“ and „Aufzeichnung“
- Where: `nav_far_detail` „… · Aufnahme läuft“, `nav_far_record` „Nur aufnehmen“ vs `nav_just_record` „Nur aufzeichnen“, „Aufzeichnung starten“ and every other place (16×). Also `maps_overview_hint` „lange drücken“ vs everywhere else „gedrückt halten“, and track = „Fahrweg“ (way types) vs „Feldweg“ (voice).
- Scenario: the same "Just record" button reads „Nur aufnehmen“ in one box and „Nur aufzeichnen“ in the next.
- Suggestion: „Aufzeichnung / aufzeichnen“ and „gedrückt halten“ throughout. Pick one word for track.
- Role: cloud-app. C: high.

## LOW

### 19. "Hike · T2" chip and the red "Difficult" badge on an ordinary hike
- Where: screenshots `plan`, `planEmpty` ("Hike · T2"), `plan` (red "Difficult" for a T2 4.5 h hike).
- Scenario: "T2" means nothing to people outside the Alps (SAC scale). A normal day hike labelled red "Difficult" alarms people or makes them doubt the app.
- Suggestion: on first use show "T2 · mountain hiking" in the chip or picker. Make the difficulty badge red only for "Difficult" that comes from technique, amber for length/ascent.
- Role: cloud-app. C: medium.

### 20. Setup's location text promises "tells you about every turn"
- Where: `setup_loc_why` (`strings_setup.xml:20`) vs vibration/voice "only where you could go wrong".
- Scenario: the tester expects a cue at every junction, gets none at most of them, and thinks guidance has failed.
- Suggestion: "… and guides you at the junctions where you could go wrong."
- Role: cloud-app. C: high.

### 21. Small text polish
- `routes_rename_imported` "Rename imported gpx" uses a lowercase "gpx" (`strings_routes.xml:43`).
- `nav_title_turn_round` "turn round" vs `nav_dir_turn_around` "Turn around". Use one form.
- `sync_menu_synced` DE „Abgleich vor 1 min“ reads oddly; „abgeglichen vor 1 min“ is better.
- Profile x-axis ends at "6.0 km" while the route is "12.5 km" in `plan`, `planTourRunning`, `track` screenshots. Probably the fixture, but check that the real profile axis uses the route length.
- Role: cloud-app. C: medium.

### 22. Two "Continue" buttons during a tour in Plan
- Where: `planTourRunning`: [Continue] at the top of the sheet, and the primary "Continue navigation · Hike" further down.
- Scenario: harmless, but it reads like two different actions. Testers wonder which one resumes the recording.
- Suggestion: keep only the top one during a tour, or label both the same.
- Role: cloud-app. C: medium.

## What works well (keep)
The welcome page is calm, with no system dialog first. The setup guide texts are concrete per brand. The candidate marker / search card wording is good. The EN/DE string sets are complete (same keys in every file, app and web). The web planner reuses the app's strings, so most words already match.
