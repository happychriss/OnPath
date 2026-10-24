# OnPath – simplify messages, UX and text sizes (review 2026-10-24, read-only, main @ 08505a9)

Paths are relative to `app/src/main/java/com/onpath/app/`. Nothing was edited. Proposals already decided in
`review-texts-2026-10-23.md` / `review-ux-2026-10-23.md` are not repeated. Language (EN + DE) is not covered.

---------------------------------------------------------------------------------------------------------------------

## A. The proposed system (one page)

**The question for every message:** does a hiker / biker need it *now*, and must they do something? If neither → drop
it, or show it as a quiet status where they would look anyway (sheet line, Settings).

### Four kinds, four looks

| Kind | Meaning | Look | Example |
|---|---|---|---|
| **INFO** | Good to know, nothing to do | No box. Grey text (`onSurfaceVariant`), optional small grey/blue icon ⓘ or spinner. Never amber/red. | "Saved as “Algund → Meran”", "Map still loading – straight line for now", "Route changed for E-bike" |
| **ACTION** | The app needs an answer | **Blue strip / card** (InfoBlue `#1565C0`), white text, ≤ 2 buttons, last = main (bold). Only for questions. | "Load detailed map here? · Not now · Load", "Use mobile data? · Wi-Fi only · Load now", off-route "Take new route / Keep" |
| **WARNING** | Something about the way / the situation to be careful about | **Amber**: ⚠ icon + amber text `#9A5B00` (on white) or amber container `#FFF0D2` with dark text. Tappable for details. | "1.2 km pushing · steps", "Off route", "No GPS · position 4 min old", map download failed |
| **ERROR** | Something you asked for did not work | **Red**: ✕/⚠ icon + red text `#C62828` or red container. Says what failed + one way out. | "Stop 2 → Destination: no path found · Show", "Track can't be saved – phone storage full" |

Rules
1. **One colour = one meaning.** Blue never carries a failure, amber never an FYI, red never a warning.
   Today blue carries questions *and* failures *and* "offline" (BackgroundDownloads), amber carries hazards *and*
   "map loading" *and* "route changed", red carries "off route" *and* unreachable points *and* "not suited".
2. **Placement by kind:**
   - Map questions (ACTION) → blue strip at the top (Plan) – as today.
   - Route state (INFO / WARNING / ERROR about *this* route) → one line each in the plan sheet, under the time line,
     order: ERROR, WARNING, INFO. At most one line per kind (+ "(+2 more)").
   - Navigate → only what matters while moving: turn, to-go/arrival, off route, GPS lost. Everything else waits for Plan.
   - Everything the user did not trigger and cannot change (internal problems, why a map area loads) → Settings only.
   - General message bar (top, "OK") → only for results of something the user just did that are not visible
     elsewhere, and errors. Errors get the ⚠ icon + red accent.
3. **Dialogs only for decisions that can lose something** (delete, end tour, replace an unsaved route, a route that is
   very long / partly straight lines, take a new route). Buttons are verbs ("Route back", not "Yes"). No body text
   that repeats the buttons.
4. **Text length:** title ≤ ~5 words, one detail line at most, no "why" sentences, no explanation of what a button does.
5. **Nothing disappears by itself** (owner rule) – unchanged; fewer messages make that rule cheap.

### Colour tokens (put them in `OnPathColors`, delete the local copies)
`InfoBlue #1565C0` (now private in BackgroundDownloads.kt:150) · `Warn #9A5B00` (= `Amber`) + `WarnContainer #FFF0D2`
· `Error #C62828` (= `Bad`) + `ErrorContainer #FDE0DC` · `Quiet = onSurfaceVariant #4A4F4A`.
Today there are 5 ambers (`#F9A825`, `#9A5B00`, `#B26A00` ×2, `#8A5A00`, plus `#FFF4E0` / `#FFF0D2` containers),
3 reds (`#C62828`, `#B3261E`, errorContainer) and 3 blues for "info/route" (`#1E3FAE`, `#1565C0`, `#1A73E8`).

### Type scale (5 sizes, all `sp` so the phone's font size still applies)

| Step | Size | Use | Never for |
|---|---|---|---|
| **Caption** | 12 | Map furniture only: scale bar, ©, profile axis labels, legend | messages, buttons, settings text |
| **Small** | 14 | Detail line under a title, list subtitles, settings explanations, chips | titles of notices |
| **Body** | 16 | Notice / strip / message titles, dialog text, list titles, buttons, sheet lines | – |
| **Title** | 22 | Plan sheet time, screen titles, **Navigate: time · distance to go**, "Off route" | – |
| **Display** | 30 bold | **Navigate: turn distance, arrival clock** | – |

Today: 12 sizes in use (9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 22, 28 sp + computed ones), 33× a hand-picked 13 sp,
41× 12 sp, 25× `bodySmall` (12 sp) – incl. warnings (ErrorBox 12 sp), the off-route distance in Navigate (12 sp, red),
the arrival target (11 sp) and the map scale (9 sp).
How: define an `OnPathType` `Typography` in `ui/Theme.kt` (bodySmall → 14, labelSmall → 12, labelMedium → 14,
headlineMedium → 30) so most screens follow at once; then remove `fontSize =` literals in `ui/` (a source-scan test
like `UserTextRuleTest` keeps them out, map furniture allowed).

**Navigate at a glance** (arm's length, sunlight, walking): the three numbers that matter – next turn, time/distance
to go, arrival – in Title/Display; everything else Small or in the opened card.

---------------------------------------------------------------------------------------------------------------------

## B. Proposals (most valuable first)

### 1. Navigate status card: bigger numbers for a glance
- **Today** (Navigate, top card): "2 h 54 min · 7.4 km" 16 sp semibold, "↑ 512 m ↓ 310 m" 14 sp, "arrive ≈" 12 sp,
  clock "16:40" 22 sp, target "Leiter Alm" 11 sp, "● moving 1:12 · breaks 0:11" 14 sp grey.
- **Proposal:** time · distance 22 sp semibold; ↑↓ 16 sp; clock 30 sp bold; "Arrive" (drop "≈") 14 sp; target 14 sp;
  recording line 14 sp. Keep the 48 dp activity icon.
- **Why:** the time to go is the most-read number in the app and is smaller than the Plan sheet's title.
- `ui/NavigateScreen.kt:294, 299, 323, 325, 328, 310`

### 2. Off route: one amber card instead of a red banner + a second "not on the route" text
- **Today:** red (error) banner "Off route – finding a way back…" (14 sp) + "120 m to the route · north-east · straight
  line"; *and* in the status card below "Not on the route" + "320 m from the route"; *and*, before the alarm, a red
  12 sp line "180 m from the route". Same fact up to three times, two different distances.
- **Proposal:** one card, WARNING look (amber container, big arrow 40 dp): title **"Off route"** (22 sp bold), detail
  **"120 m north-east"** (16 sp) + state in Small: "Looking for a way back…" / "No way back found" / "Following the
  original route" / "No map here". The status card keeps only the recording line while off route (no "Not on the
  route", no red 12 sp line).
- **Why:** off route is the #1 Navigate warning – one place, big, one distance.
- `ui/NavigateScreen.kt:181, 285–290, 302–303, 420–442`; `navigation/NavText.kt:13–21` (titles), `:57` (toRoute).
- Colour → **Decision C1**.

### 3. GPS lost in Navigate: show it as a warning, not as grey small text
- **Today:** "Waiting for GPS · position 4 min old" replaces "Recording" in the grey 14 sp recording line (maxLines 1,
  may be cut – backlog "Small"); time greyed with "time paused · no GPS".
- **Proposal:** an amber line with ⚠ in the status card: **"No GPS · position 4 min old"** (16 sp); the time-paused
  line then says only "Time paused".
- **Why:** with no fix the arrow and the to-go are stale – the user must know at a glance.
- `ui/NavigateScreen.kt:297, 405–407`

### 4. Plan sheet: one style per kind for route problems
- **Today**, three looks for three route problems:
  (a) red bold line "Last 180 m to “Taser Alm”: no way for Trekking bike · Show" (GapLine);
  (b) amber box, 12 sp: "Leg 1→2: No route found near this point. Shown as a straight dashed line. Try moving the
  point or changing the activity." (ErrorBox);
  (c) amber ⚠ line "1.2 km pushing · steps · 2 steep sections ›" (WarnLine).
- **Proposal:** (a) and (b) are the same kind (the route does not do what you asked) → **ERROR line**, same as (a):
  ✕ icon + red text + "Show": **"Stop 1 → 2: no path found"** / **"Last 180 m to “Taser Alm”: no way by trekking
  bike"**; one line, "(+1 more)". (c) stays the only amber line. Drop "Leg", "Shown as a straight dashed line" (the
  map shows it) and the advice sentence.
- **Why:** colour tells the kind; "Leg 1→2" is jargon; 12 sp is too small for a problem.
- `routing/Planner.kt:149`, `:123` (timeout text); `ui/PlanPanel.kt:248–250, 307–318, 452–461`

### 5. "Map still loading" is info, not a warning
- **Today** (plan sheet, amber ⚠ box): "The map for this area is still loading – the route is shown as a straight line
  until then." / "The map for this area loads when you're online – the route is shown as a straight line until then."
- **Proposal:** INFO line with a small spinner (grey): **"Map loading – straight line for now"** / offline:
  **"Map loads when online – straight line for now"**.
- **Why:** nothing to do and nothing dangerous; it disappears by itself when the map arrives.
- `data/RoutingDataLoader.kt:57–58`; shown via `ui/PlanPanel.kt:249` (ErrorBox)

### 6. "Route changed for …" is info, not a hazard
- **Today** (plan sheet, amber ⚠ line, same look as pushing/steps): "Route changed for Trekking bike – please check it ›".
- **Proposal:** INFO line (blue ⓘ, grey text, ›): **"New route for Trekking bike – check it"**. Amber stays for real
  spots on the way.
- **Why:** the user just changed the activity themselves; amber here teaches people to ignore amber.
- `ui/RouteWarnings.kt:160`; `ui/PlanPanel.kt:250, 323–335`

### 7. Blue strip = questions only
- **Today** (Plan, blue strip at the top) carries: questions (mobile data, "Detailed map for here?"), failures (⚠ in
  blue: "This area could not be prepared…" + Not now / Try again), statuses with × ("No detailed map here yet – it
  downloads when you're online", "Waiting for GPS to get the detailed map – or hold the map where you want to go"),
  and always a "why" line ("Getting the detailed map because you planned here.").
- **Proposal:** blue only when a button is needed (questions, "Allow location"). Failure → **amber strip**:
  "Map for this area failed · Try again". Pure statuses (offline, waiting for GPS) → the quiet grey pill that the
  overview hint already uses ("Overview only – …"): **"No detailed map – loads when online"**, no ×. Drop all "why"
  lines (`CellWhy` texts).
- **Why:** the strip then always means "answer me"; statuses no longer need a × to get rid of them.
- `ui/BackgroundDownloads.kt:262–286, 300–323`; `ui/CellCard.kt:84–86, 108, 122–126, 240–249`

### 8. "Detailed map for here?" – shorter
- **Today:** title "Detailed map for here? (≈ 21 MB)" + "It is prepared for you first – takes a few minutes. Until then
  the overview map shows. Setting a point here also loads it." · Not now · Load.
- **Proposal:** **"Load detailed map here? · 21 MB"**; detail only when it must be built: **"Takes a few minutes."**
- **Why:** three sentences for a yes/no; the last two explain things the user does not need to decide.
- `ui/BackgroundDownloads.kt:292–295`

### 9. Mobile-data question – shorter
- **Today:** "Download 3 more map areas (≈ 25 MB) over mobile data?" · Only on Wi-Fi · Load now.
- **Proposal:** **"Use mobile data? · 3 map areas, 25 MB"** · **Wi-Fi only** · **Load now**.
- `ui/CellCard.kt:98`; buttons `ui/BackgroundDownloads.kt:275`

### 10. Message bar (dark, top, OK): mark errors
- **Today:** every message looks the same – "Copied.", "Track saved to My routes.", "Importing GPX…" and "The track
  cannot be saved any more (phone storage full or damaged)…" are the same dark bar.
- **Proposal:** errors get a ⚠ icon and a red left edge (ERROR); info keeps today's look. Progress texts that clear
  themselves ("Preparing the export…", "Importing GPX…", "Creating diagnostics…", "Backing up all tracks and routes…")
  move into the place where the user started it (spinner on the button / row) instead of the bar.
- **Why:** a storage error must not look like "Copied."
- `MainActivity.kt:285–293` (bar), `:261, 482`; `ui/AppViewModel.kt:3334, 3458`

### 11. Dialog buttons as verbs, no repeated body text
- "Route back to the start?" – **Yes / No** → **Route back / Just record**; body → "You deleted the destination.
  Undo brings it back." (`ui/TourBar.kt:176–180`)
- "End this tour?" / "You have arrived – end tour?" – body "Save keeps the track in My routes. Delete throws it away."
  → drop the body (`ui/TourBar.kt:106`).
- "Delete this track?" – "Recording stops and the track is not kept. You can still get it back from the recycle bin for
  30 days (My routes → ⋮ → Recycle bin)." → **"It stays in the recycle bin for 30 days."** (`ui/TourBar.kt:94–95`,
  same in `ui/RideSummary.kt:78–79`, `ui/Screens.kt:452`).
- "This route is very long" – body → **"About 29 days on foot. Calculating and loading its maps takes long."**
  (`ui/TourBar.kt:197–198`).
- Icons: End tour / Route back / Long route all use the 🏁 flag – give Long route ⚠ (it is a warning).

### 12. "Keep recording after a restart?" at the first Start navigation
- **Today:** the moment the user taps Start navigation for the first time, a dialog with four sentences asks for
  "Allow all the time" (then Android's own screen).
- **Proposal:** see **Decision C2**. Text if kept: title **"Keep recording after a restart?"**, body **"Needs location
  “Allow all the time” – used only during a tour."** · Not now · Allow.
- `MainActivity.kt:298–312`; trigger `ui/AppViewModel.kt:2738–2746`

### 13. Navigate recording line: only when it matters
- **Today:** "● moving 1:12 · breaks 0:11" always under the to-go numbers.
- **Proposal:** see **Decision C3**.
- `ui/NavigateScreen.kt:306–313, 405–416`

### 14. Pace note in Navigate: plain words or drop
- **Today** (opened Navigate card): "Your pace on this trip: up 0.9× · down 1.1×".
- **Proposal:** drop it (the arrival already uses it) – or plain: **"Faster than planned uphill"**. See **Decision C4**.
- `ui/NavigateScreen.kt:349–352, 561–565`

### 15. "New route" note in Navigate – shorter
- **Today:** "New route: arrival 12:45, was 13:15" (stays until the next change).
- **Proposal:** **"New route · was 13:15"** (the new arrival is already big on the right), Small, grey (INFO).
- `ui/NavigateScreen.kt:339–343`; text from `time/` (`lineNote.text()`)

### 16. Off-route direction: drop "straight line"
- **Today:** "120 m to the route · north-east · straight line".
- **Proposal:** **"120 m north-east (direct)"** – or "as the crow flies".
- **Why:** "straight line" reads like a route kind (the plan uses "straight line" for unrouted legs).
- `navigation/NavText.kt:57`

### 17. Activity check sheet: amber for "not suited"
- **Today** (Navigate → activity icon): "⚠ 1.4 km not suited for trekking bikes, steps 40 m · +900 m" in red.
- **Proposal:** amber (WARNING); red only for "No route found for it" (ERROR).
- `ui/ActivitySwitchSheet.kt:131–132`

### 18. One type scale in the theme
- **Today:** no own `Typography`; 12 sizes; 13 sp and 12 sp hand-set in Settings, Offline maps, search, POI sheet.
- **Proposal:** `OnPathType` in `ui/Theme.kt` (see A), then replace literals; source-scan test against new `fontSize =`
  in `ui/` (map furniture allowed).
- Biggest offenders: `ui/SettingsScreen.kt` (43), `ui/NavigateScreen.kt` (22), `ui/Screens.kt` (21),
  `ui/OfflineMapsSection.kt` (18), `ui/PlanPanel.kt` (14).

### 19. Small text that people actually need to read → 14 sp minimum
- Plan sheet: SavedLine "Saved as “…”" 12 → 14 (`ui/PlanPanel.kt:346`), "Calculating the route…" 12 → 14 (`:240`),
  ErrorBox 12 → gone (#4).
- Blue strip detail 12 → 14, chip 13 → 14 (`ui/BackgroundDownloads.kt:166–169, 312`).
- Overview hint 12 → 14 (`ui/CellCard.kt:244`). Position line in Navigate 12 → 14 (`ui/NavigateScreen.kt:395`).
- Points list subtitles ("Start", "Destination") 12 → 14 (`ui/PointsSection.kt:268`).

### 20. Map furniture readable
- Scale bar label 9 sp → 12 sp, "© OpenStreetMap" 10 sp → 12 sp (`ui/MapFooter.kt:63, 88`); profile axes 11 → 12
  (`ui/ElevationProfile.kt:81`).

### 21. Plan sheet: drop the gradient legend under the profile
- **Today:** "— < 5 %  — 5–10 %  — 10–15 %  — > 15 %" at 11 sp under every Plan profile.
- **Proposal:** keep it only on Route details (it is already there).
- **Why:** frees a line in the profile step; the colours are obvious steep/not steep.
- `ui/PlanPanel.kt:263`; `ui/RouteDetailsScreen.kt:62` keeps it

### 22. Settings → Diagnostics → Developer tools out of sight
- **Today** (every user): map server URL with key info, "Download routing data (map centre)", "Map areas (debug)",
  "Request map area at map centre", "Time estimate (formulas)", a big **green filled** "Import map data (.zip) from this
  phone" button, data folder path, file list.
- **Proposal:** show "Developer tools" only after tapping the version in About 7 times (Android convention) or only in
  debug builds. See **Decision C5**.
- `ui/SettingsScreen.kt:397–~600`

### 23. Diagnostics: drop the "Show export button" switch
- **Today:** a switch "Show export button" + 5-line explanation, then the button.
- **Proposal:** just the button **"Send problem report"** + one line "Leave the map at the problem spot first."
- `ui/SettingsScreen.kt:380–390`

### 24. Settings list: Diagnostics row in red
- **Today:** "Diagnostics · 2 problems · 1 serious" in red on the Settings list.
- **Proposal:** see **Decision C6**. `ui/SettingsSummary.kt:82–88`

### 25. Settings pages: one short line per option at most
- **Offline maps** today has 6 explanation paragraphs (intro, Cancel all, Wi-Fi switch, Update maps now, Clean up,
  Clear all). Proposal: intro → drop; "Cancel all stops these downloads; the maps you have stay." → drop (button says
  it); Update → "Monthly by itself; this loads now."; Clean up → "Shows what goes first."; Clear all → "The world
  overview stays." (`ui/OfflineMapsSection.kt:126–181`).
- **Navigation:** "Recording with the screen off works best with battery usage set to “Unrestricted”." → "Best with
  battery: Unrestricted"; "A tour continues by itself after a restart or update (location: Allow all the time)." →
  drop if C2 = 1 (`ui/SettingsScreen.kt:355–370`).

### 26. Settings list: group the rarely used rows
- **Today:** 10 equal rows; Updates, Privacy, Diagnostics, About are rare.
- **Proposal:** see **Decision C7**. `ui/SettingsSummary.kt`, `ui/SettingsScreen.kt`

### 27. One-point plan hint – shorter
- **Today:** "Hold the map to add the destination. Hold a point or place for options."
- **Proposal:** **"Hold the map to set the destination."** `ui/PlanStatus.kt:51`

### 28. GPX import message – plain
- **Today:** "Imported “Name”: 1234 points, 3 waypoints." + one technical warning per line, until OK.
- **Proposal:** **"Imported “Name” – some parts were skipped."** (details into Diagnostics).
- `ui/AppViewModel.kt:3490–3495`

### 29. Trivial / cleanup (group)
- `ConfirmClearDialog` ("Clear this route?") is dead: `confirmClear` is never set true (`ui/PlanExtras.kt`,
  `ui/MapScreen.kt:195`) – delete.
- Local colours to tokens: `#B26A00` (`ui/PlanPanel.kt:456`, `ui/MapLoad.kt:276`), `#FFF4E0`/`#5A3A00`
  (`ui/PlanPanel.kt:454–458`), difficulty `#8A5A00`/`#B3261E` (`ui/RouteBreakdown.kt:151–153`), `ARROW_BLUE #1A73E8`
  (`ui/FacingArrow.kt:40`), `InfoBlue` (`ui/BackgroundDownloads.kt:150`).
- Update card title 15 sp → 16 (`ui/UpdateUi.kt:47`); TimeDetails 11 sp header → 12 (`ui/TimeDetailsScreen.kt:172`).
- Blue strip × is 40 dp (`ui/BackgroundDownloads.kt:308`) – 48 dp like everything else (gloves).

---------------------------------------------------------------------------------------------------------------------

## C. Owner decisions (tap a number; recommendation first)

**C1 – Colour of the off-route card in Navigate** (#2)
1. **Amber** (warning): amber container, dark text, big arrow – red stays for "something failed". *(recommended)*
2. Red as today (strongest alarm), but one card and bigger text.
3. Amber while finding / kept, red only for "No way back found".

**C2 – "Keep recording after a restart?" (Allow all the time)** (#12)
1. **Ask at the end of the first tour** ("Tour saved" is a calm moment), never at Start; Settings → Navigation keeps
   the quiet hint. *(recommended)*
2. Keep at the first Start navigation, shorter text.
3. Never ask; only the hint in Settings → Navigation.

**C3 – Navigate recording line "● moving 1:12 · breaks 0:11"** (#13)
1. **Collapsed card: only the orange dot (next to the activity icon) while all is normal; the line shows when it
   matters ("Paused", "No GPS"); moving / breaks move into the opened card.** *(recommended)*
2. Keep as is (14 sp).
3. Show "● 5.3 km recorded" instead of moving / breaks.

**C4 – Pace note "Your pace on this trip: up 0.9× · down 1.1×"** (#14)
1. **Drop it** (the arrival already follows your pace). *(recommended)*
2. Plain words: "Faster than planned uphill".
3. Keep.

**C5 – Developer tools in Settings → Diagnostics** (#22)
1. **Hidden until the version in About is tapped 7×** (works in release builds for field tests). *(recommended)*
2. Only in debug builds.
3. Keep visible, but collapsed and with the Import button as a normal outlined button.

**C6 – "2 problems · 1 serious" in red on the Settings list** (#24)
1. **Grey, neutral text ("For bug reports"); red only while a new ERROR has not been looked at yet.** *(recommended)*
2. Keep red whenever there is an ERROR in the list.
3. No count on the list at all.

**C7 – Settings list grouping** (#26)
1. **Two groups: "Your settings" (Pace, Points of interest, Map, Navigation, Offline maps, Backup) and a smaller "App"
   group below (Updates, Privacy, Diagnostics, About).** *(recommended)*
2. Merge Updates, Privacy, Diagnostics into one "About OnPath" page.
3. Keep 10 equal rows.

**C8 – Statuses in the blue strip (offline, waiting for GPS)** (#7)
1. **Quiet grey pill under the search bar (like "Overview only – …"), no ×; blue only for questions.** *(recommended)*
2. Keep them in the blue strip but drop the "why" line.
3. Keep as is.

**C9 – Progress messages in the top bar ("Importing GPX…", "Preparing the export…")** (#10)
1. **Spinner where it was started (button / My routes row); the bar only for the result if it is not visible, or an
   error.** *(recommended)*
2. Keep them in the bar.
