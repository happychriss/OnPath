---
paths:
  - "app/src/main/java/com/onpath/app/ui/**"
  - "app/src/test/java/com/onpath/app/ui/**"
  - "app/src/test/snapshots/**"
  - "strings/**"
  - "docs/design/ui-style.md"
---
# UI, texts, messages (loads when you touch ui/, strings/ or the screenshots)

| Path | What |
| --- | --- |
| `  ui/` | `AppViewModel` (all UI state, Plan ⇄ Navigate switching), `AppMode` (PLAN / NAVIGATE + mode on start, see `docs/ux-two-modes.md`), `MapScreen` (map + Plan mode controls), `NavigateScreen` (Navigate: remaining strip, recording chip, arrival check), `SmartScreen` + `RestView` (cycling: dim rest view on black far from the next turn, map paused under it – when it shows, `ScreenPolicy` hike vs bike), `SlideToUnlock` (slide-to-plan switch "Stop" + small line "Pause · Change route · End", dropped when it does not fit, only "Change route" without a recording – `SlideLabel`; `SlideThreshold`; with a recording the slide opens `TourActionBar` Pause · Change route · End in its place), `PlanTopBar` (search pill + round ☰ menu: My routes, Save route (with a plan; green dot while unsaved), Start recording, Pair with browser, Settings; Import GPX, Save as copy, Export GPX, Share route are in My routes → ⋮), `MapFooter` (scale, attribution, map FABs: compass, eye, Show whole route ⛶ – `WholeRoute` (when it shows, bounds) + `FitPaddingRule` (one padding for every route fit), undo / redo, locate with GPS dot), `TourBar` (Tour running status + End tour, arrival dialogs), `MapHost` (MapView in Compose; map taps edit only when `canEditMap`), `PlanPanel` (plan sheet in three steps – summary with activity + the pill row ✕ New plan · ⇅ Reverse · ↻ Round trip (label beside the icon, no ⋯; during a tour the first step is "Continue navigation" + "Save route"), profile, Points list; one primary button; route lines by kind: red ERROR, amber WARNING, grey INFO; Save's name dialog + "Discard changes?" in `RouteDialogs`), `RouteBreakdown` (way type / surface bars, difficulty badge), `SettingsScreen` (topic list → one page per topic, `SettingsPage` in `SettingsSummary`: Pace, POIs, Map, Navigation, Offline maps, Backup, Browser sync (`SyncSettings`), Privacy, Updates, Diagnostics, About), `Panels`, `Screens`, `ElevationProfile` (gradient colours), `Theme` (`OnPathColors` message tokens + `OnPathType` type scale 12/14/16/22/30 – no hand-set `fontSize` in ui/, `FontSizeRuleTest`), `Notices` (`vm.say(text, kind, place)` → `NoticeRouting`: a line in My routes / Settings where the user acted, else the red HINWEIS box for errors only; `Busy` = spinner where work was started), `MessageCard` (the one message box: FRAGE / HINWEIS kinds, `Messages.pick` one at a time, `MessagePlacement` – `docs/design/ui-style.md`) |
| `strings/` | User texts EN + DE as Compose Multiplatform resources (small KMP module), `Txt` / `str()` – see `docs/i18n.md`; review round trip `tools/i18n/` |

- **UI style + messages** (owner): see **`docs/design/ui-style.md`** (colours, type scale, buttons, dialogs, message
  system, positions). In short: exactly three kinds – **FRAGE** (box, blue edge, two verb buttons, until answered),
  **HINWEIS** (box with one button – OK or the one way out; grey edge, amber ⚠ attention, red ⚠ error; never goes by
  itself), **ANZEIGE** (no box: a line / card in place while the state holds). Boxes = `MessageCard` (`ui/MessageCard.kt`),
  one at a time (`Messages.pick`, FRAGE first), 12 dp under the top element (`MessagePlacement`). Colours from
  `OnPathColors`, sizes from `OnPathType` only. Dialogs only for decisions that can lose something. **No dark message
  bar**: a result goes where the user acted (`vm.say(text, NoticeKind, NoticePlace)` → `NoticeRouting`). Developer tools
  (Settings → Diagnostics) appear after 7 taps on the version in Settings → About (remembered; also in debug builds – testers get those).
- **Map labels** in German (`name:de` → `name`). **UI texts EN + DE** (owner, `docs/i18n.md`): every user
  text in `strings/…/composeResources/values[-de]/strings_<area>.xml` (Compose Multiplatform resources), used via
  `str(Res.string.x)` in Compose / `Txt.s(…)` elsewhere – no new hard-coded texts; review sheet round trip
  `tools/i18n/i18n.py export|import`. Diagnostics, Developer tools and the problem log stay English.
- **Activities** (exactly six): Bike, Gravelbike, E-bike, MTB, E-MTB, Hike (SAC T1–T5,
  default T2, chosen in the activity picker; the app remembers the last activity and T level). Names + the one picker line
  under each: `Activity.label` / `line`, chip `Activity.chip` ("Gravel") (enum names BIKE_TOURING / BIKE_TREKKING … unchanged and the only
  stored form). Profiles
  (`BRouterEngine.profileFile` – the one source; map layers, route warnings and the activity check ask it): Bike +
  E-bike → `touring.brf`, Gravelbike → `trekking.brf`, MTB + E-MTB → `mtb.brf`, Hike → `hiking-mountain.brf` with the
  T limit (E-bike / E-MTB change only power, speed and mass). In a sentence the activity reads "Hike T3"
  (`LastActivity.name`), on the chip "Hike · T3".

- Screenshot tests: `./gradlew :app:recordPaparazziDebug` / `verifyPaparazziDebug` (map replaced by a placeholder), images in `app/src/test/snapshots/images/` – the way to check layouts without a device. `gate.sh` runs the check when ui / texts / snapshots changed.
