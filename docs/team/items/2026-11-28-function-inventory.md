# App function inventory (2026-11-28, origin/main 6849a4afc)

This lists every function a user can see or trigger in the Android app as found in the code on origin/main (screens, sheets, menus, hold menus, settings rows, dialogs, boxes, notifications, background behaviour), one table row per function, grouped by area. A row is one thing a user can see or do (a menu row, a button, a setting, a dialog, an automatic behaviour); a few closely tied pieces share a row. It is a plain inventory: it judges nothing, includes features the owner asked for himself, and omits nothing it found. The multi-day parts being rebuilt now are one line only (see My routes: branch feat/multiday-one-day).

How to read the columns: "taps" count from the app start in Plan with an empty map. "Origin" is a requirement ID from docs/requirements.md (F-/R- IDs) or C-numbers from docs/core-features.md; IDs of the form A-0xx appear only in code comments / backlog (the review list, not in requirements.md) and are marked so; owner quotes carry the date found in code comments or commit messages; "keine Anforderung gefunden" means neither was found. Sizes are rough estimates from wc -l of the main files (and of the functions inside large files) plus the sum of test files whose names match the feature (JVM + androidTest); the test sums are matches by file name, not exact attribution. Browser column: judged from web/app/src/*.ts, web/parity.json and docs/design/web-planner.md (the planner deliberately has no navigation, recording, settings, backup, setup).

## Plan (Planen) – map, card, menus, hold gestures

Tap counts start at the app start in Plan with an empty map (card: search field, ☰, activity chip, chip "Meine Touren"). "hold" = press-and-hold on the map (1 gesture). Test sizes are sums of files whose names match the feature, not exact.

| Name as shown | Where / how (taps from start) | For | Origin | Size (code + tests, rough) | In browser planner? |
|---|---|---|---|---|---|
| Daumen-Karte (the Plan card at the bottom: search row, chips, facts, pills, Los-geht's button) | the start screen itself (0) | Vorbereitung, unterwegs | owner 2026-11-26 "Daumen-Karte" (ThumbCard.kt header, docs/team/items/2026-11-26-daumen-karte.md) | ThumbCard.kt ~127, PlanPanel.kt ~790, StepSheet.kt ~394, SheetWithFooter ~61 + 7 test files ~700 | partly – side panel instead of a card (web/app/src/main.ts, sheetstep.ts) |
| Wohin soll's gehen? (search field on the card) | 1 tap on the field | Vorbereitung, unterwegs | F-PLAN-12; owner 2026-11-27 variant A | ThumbCard.kt (ThumbSearchField ~20); search see "Search" | yes (search.ts) |
| 🔍 (search square on the card, with a route) / "Ziel suchen" (field with only the start point) | 1 tap once a point exists | Vorbereitung | owner 2026-11-26/27 (ThumbHead comment) | PlanPanel.kt ThumbHead ~50 | yes (search.ts) |
| ☰ Menü button (square on the card; amber dot = browser-sync problem) | 1 tap | Vorbereitung, unterwegs | owner 2026-11-26 (moved into the card); dot owner 2026-10-30 | PlanTopBar.kt ~258 + ~3 tests | partly – menus.ts (planner has its own menu) |
| ☰ → "Hilfe" (?) | 2 taps; opens the help page on onpath.earth in the browser | Vorbereitung | owner 2026-11-08 ("father's feedback") | PlanTopBar.kt ~5, AboutLinks.kt ~159 | no |
| ☰ → "Handy einrichten" (red ⚠, only while a tour needs something from the phone) | 2 taps, shown only when needed | Vorbereitung | owner 2026-10-30; C17 | PlanTopBar.kt ~5; setup see "Setup" | no |
| ☰ → "Meine Touren" | 2 taps | Vorbereitung, nach der Tour | owner 2026-11-01/06 (menu order); docs/requirements.md §7 "My tours" | PlanTopBar.kt ~5; list see "My routes" | yes (plans.ts) |
| ☰ → "Plan speichern" (needs a plan; opens the name dialog) | 2 taps | Vorbereitung | F-PLAN-10; owner 2026-10-31 "the one place of Save"; C18 | PlanTopBar.kt ~5; SaveState.kt ~488 + SaveStore/SaveSlice ~250 + ~5 tests ~700 | yes (plans.ts) |
| ☰ → "Neu planen" | 2 taps (needs ≥1 point) | Vorbereitung | owner 2026-11-06 ("habe ich oben im Menü gesucht") | PlanTopBar.kt ~5, PlanEditSlice ~45 | yes (main.ts) |
| ☰ → "Ganz neu berechnen" (drops the plan's detour state and recalculates) | 2 taps (needs a route) | Vorbereitung | owner 2026-11-25 picture B | PlanEditSlice.kt/PlanRecalcSlice ~300, ~2 tests ~200 | no |
| ☰ → "Nur aufzeichnen" (record without a route; runs the setup guide first) | 2 taps, not while a tour records | unterwegs | owner 2026-11-06 flow map H1; F-NAV-03 | PlanTopBar.kt ~5, TourSession.kt ~454 | no |
| ☰ → "Mit Browser verbinden" / "Browser-Verbindung" | 2 taps → camera / Settings page | Vorbereitung | A-007 (code comment, not in requirements.md); owner 2026-10-30/11-07 | PlanTopBar.kt ~30, SyncSettings.kt ~451 | yes (sync.ts) |
| ☰ → "Einstellungen" | 2 taps | alle | docs/requirements.md §7 Settings | see "Settings" | no |
| ☰ → "Nach Updates suchen" – code exists in PlanMenu but is not wired in Plan (onUpdates = null; "Settings has the Updates section", owner) | not reachable from ☰ | – | owner (MapScreen.kt comment, no date) | PlanTopBar.kt ~5 | no |
| Aktivität-Chip ("Wandern · T2", "Fahrrad" …) | 1 tap → picker sheet | Vorbereitung, unterwegs | F-PLAN-01; owner 2026-11-26 chip in the card | ThumbCard.kt ~40, ActivityPicker.kt ~205 + ~5 tests ~600 | yes (activity.ts) |
| Aktivität picker: Fahrrad · Gravelbike · E-Bike · MTB · E-MTB · Wandern (+ group switch Rad / Wandern) | 2 taps | Vorbereitung | F-PLAN-01; owner 2026-11-13/26 (variant C) | ActivityPicker.kt ~205 | yes (activity.ts ~217) |
| Wanderschwierigkeit T1–T5 (SAC) rows in the picker | 3 taps | Vorbereitung | F-PLAN-11 (T level); F-ROUTE-01..04; owner 2026-11-26 night (rows) | ActivityPicker.kt, Screens.kt SacScale ~30 | yes (activity.ts) |
| Chip "Meine Touren" on the empty card | 1 tap | Vorbereitung, nach der Tour | owner 2026-11-26 (ThumbHead) | PlanPanel.kt ~5 | yes (plans.ts) |
| Hold on the empty map (empty plan): menu "Als Ziel" (from my position) · "Als Start" · "Rundtouren vorschlagen" | 1 hold + 1 tap | Vorbereitung, unterwegs | owner 2026-11-26/27; F-PLAN-02/03; C2 | MapOverlays.kt FirstPointMenu ~35; MapHost.kt gestures ~540; state/PlaceAddState ~474 + tests ~3200 (gesture group) | yes (main.ts, menus.ts) |
| Hold on the empty map with ≥1 point: point set at once, or small menu "Als Stopp" / "Als Ziel" when in doubt | 1 hold (+1 tap) | Vorbereitung | F-PLAN-13 (code), F-PLAN-15; owner 2026-11-15 | MapOverlays.kt HoldAddMenu ~15; PlaceAddState ~474 | yes (main.ts holdAddMenu) |
| Hold on the route line (inserts a stop in that leg) | 1 hold | Vorbereitung | F-PLAN-04 (insert by line); C2 | MapHost.kt + PlaceAddState (smart insertion, WaypointInsertion in core) | yes |
| Hold on a route point → point menu: "Als Start" (removes the points before) · "Als Start der Rundtour" · "Merken"/"Gemerkt" · "In Google Maps öffnen ↗" · "Löschen" | 1 hold + 1 tap | Vorbereitung | C1; owner 2026-11-26/27 | MapOverlays.kt PointMenu ~40; PlaceMenusState ~303 + PointMenu tests (gesture group) | yes (menus.ts) |
| Hold on a POI symbol (Plan) → menu with the POI's name: Als Start/Als Ziel/Als Stopp · Rundtouren vorschlagen · Merken · In Google Maps öffnen | 1 hold + 1 tap | Vorbereitung | C19; owner 2026-11-26 | MapOverlays.kt PoiMenu ~60 | yes (main.ts) |
| Tap on a POI symbol → place card with details | 1 tap | Vorbereitung, unterwegs | C19; F-MAP-09 | PlaceCard.kt ~131, PlaceCardUi.kt | yes |
| Tap on the empty map → hint "Gedrückt halten, um hier einen Punkt zu setzen." | 1 tap | Vorbereitung | owner 2026-11-03 | PlanPanel.kt TapHoldHint ~15 | no |
| Hold ring + vibration while holding the map ("Vibration beim Planen" switch in Settings) | hold | Vorbereitung | owner 2026-11-26 (ArmedHold); Settings text | MapHost.kt HoldRing ~30, Haptics.kt ~35 | no (no vibration) |
| Found place's pin with name chip "Name ›" and its menu (incl. "Nadel entfernen") | after picking a search result | Vorbereitung | owner 2026-11-02 / 2026-11-13 | MapOverlays.kt PinChip/PinMenuOverlay ~200 | yes (search.ts) |
| Plan sheet steps: rolled up / summary / profile / points (drag handle, "Blatt vergrößern" / "Blatt verkleinern") | drag the card | Vorbereitung | owner 2026-11-05 (four steps) | StepSheet.kt ~394, PlanSize ~33, SheetRise ~23 + ~3 tests | partly (sheetstep.ts) |
| Facts row: time (big) · km · ↑ · ↓ | in the card once a route exists | Vorbereitung, unterwegs | F-PLAN-06 | PlanPanel.kt ThumbHead ~50, core time model | yes (planstats.ts) |
| Zeitdetails screen (how the time was calculated) | tap on the time = 1 tap after a route | Vorbereitung | owner 2026-10/11 (time model; docs/plan-time-estimate.md) | TimeDetailsScreen.kt ~172, TimeDetailsUi ~12 + ~2 tests | no |
| Pill "Neu planen" (✕) | in the plan row from 1 point on | Vorbereitung | owner 2026-11-05 "R5" | PlanPanel.kt PlanHead ~80 + PlanRow.kt | yes |
| Pill "Umkehren" (⇅) | from 2 points | Vorbereitung | owner 2026-11-05 "R5" / #40 (no requirement ID) | PlanPanel.kt PlanHead | yes |
| Pill "Rundtour" (↻ switch) | from 2 points | Vorbereitung | F-PLAN-03; owner 2026-11-06 #40 | PlanPanel.kt PlanHead; core RoundTrip rules | yes |
| Höhenprofil (colour by steepness; slide a finger: dot runs on the map) | in profile step | Vorbereitung | F-PLAN-07; owner 2026-11-06/26 | ElevationProfile.kt ~337, ProfileScrub ~28, ProfileRules ~74 | yes (profile.ts ~317) |
| Schwierigkeits-Abzeichen "Leicht / Mittel / Schwer" (→ Routendetails) | tap on the profile's top right | Vorbereitung | F-PLAN-06/11 | RouteBreakdown.kt ~125 | yes |
| Routendetails screen: Wegtypen, Untergrund, difficulty reasons, big profile | 1 tap on the badge | Vorbereitung | owner (RouteDetailsScreen); F-PLAN-06 | RouteDetailsScreen.kt ~74, RouteBreakdown ~125, RouteSpotsList ~58 | partly (main.ts) |
| Amber warning line + list "Auf dieser Route" ("Stellen anzeigen"): ferry, construction, pushing, steps, steep, above your T level, no bikes, ford, barrier … | tap on the line | Vorbereitung | F-PLAN-11; C3 | PlanPanel.kt WarnLine ~20, RouteSpotsList ~58, core RouteWarnings ~345 + ~3 tests | partly (main.ts) |
| Grey line "Neue Route für … – bitte prüfen" (after an activity change) | tap dismisses | Vorbereitung | owner (plan_route_changed) | PlanPanel.kt InfoLine ~20 | no |
| Grey line "Im Browser mit einer anderen OnPath-Version geplant – Route kann leicht abweichen" | state line | Vorbereitung | A-007 / routing fingerprint (docs/design/routing-fingerprint.md) | SyncedRoutingSlice.kt ~145 + tests | partly |
| Red line "Punkt n nicht erreichbar …" + "Anzeigen" (map moves to the gap) | in summary | Vorbereitung | R-12/R-13; F-PLAN-11 | PlanPanel.kt ErrorLine, core PlanGaps | partly (plangaps.ts ~78) |
| Notice "Route nicht berechnet …" with "Trotzdem planen" / "Berechnen" | in summary | Vorbereitung | owner 2026-11-03 (#21 B), 2026-11-27 | NotRoutedBar.kt ~37 | no |
| Punkte list (full sheet): ≡ drag to reorder, ✕ delete (no question), tap shows point on map | drag sheet up | Vorbereitung | C9; F-PLAN-04 | PointsSection.kt ~315 + CorePointsListE2E ~260 | yes (sortable.ts) |
| "Rückgängig" / "Wiederholen" pill on the map (and at "Punkte") | on map once there is history | Vorbereitung | F-PLAN-08; owner 2026-11-08 (own pill with words) | MapFooter.kt UndoPill ~40; PlanEditState ~384 | yes |
| "Los geht's · <Ziel>" / "Los geht's · ohne Route" button (primary) → setup guide → Navigate | on the card's foot | unterwegs | F-NAV-01 | PlanPanel.kt GoButton/PlanFoot ~80; TourSession ~454 | no |
| "Weiter geht's" (green way back to Navigate during a tour) and "Weiter geht's · Ziel" (re-route and continue) | foot of the plan during a tour | unterwegs | owner 2026-11-06 C | BackToNavigation.kt ~40, PlanPanel.kt | no |
| "Gespeichert als „…“" / "„…“ · geändert, nicht gespeichert" line | in the sheet | Vorbereitung | owner 2026-11-06 | PlanPanel.kt SavedLine ~15 | partly |
| Pill "Offline verfügbar / lädt / unvollständig" + one-line explanation | state on top right; tap = explanation | Vorbereitung | R-01; owner 2026-11-03 comparison card 3; 2026-11-27 words | OfflineReady (core) ~99, PlanPanel.kt ~30 + tests | no |
| Mode tab "PLANEN" (during a tour: red dot, time, "Beenden") | top of the card | unterwegs | owner 2026-11-06/27 variant 1 | PlanTopBar.kt PlanModeLabel ~40; ModeSwitch.kt ~157 | no |
| Compass button (tap: north up / heading up; shows rotation) | map control column, 1 tap | alle | F-MAP-05 | MapFooter.kt CompassControl ~20 + MapControls tests ~450 | partly (maplibre control) |
| Standort button (locate / follow; shows distance to me; greyed without GPS; asks permission) | 1 tap | alle | F-POS-02/03; R-03/R-06; owner 2026-11-07 (distance label) | MapFooter.kt LocateControl ~40, PlanGps.kt ~76 | partly (position.ts ~86) |
| "Ganze Route zeigen" (⛶) | 1 tap | Vorbereitung | owner 2026-11-03/26 (WholeRoute) | WholeRoute.kt ~152 | yes (⛶) |
| Auge: hold = hide route/track to see the map ("Halten, um die Route auszublenden") | hold on control | Vorbereitung, unterwegs | owner 2026-11-03 (novice fix) | MapFooter.kt EyeControl ~25 | no |
| Long-press tooltips on every map control | hold on a control | alle | owner 2026-11-03 | MapFooter.kt MapTip ~10 | no |
| Scale bar + short map credit ("Kartenquellen anzeigen" → Rechtliches & Quellen) | bottom of map; credit 1 tap | alle | F-MAP-05; owner 2026-11-27 (credit place) | MapFooter.kt ~484 | partly |
| Wait window "Route wird berechnet" (steps, Abbrechen) / "Die Route ist lang … Weiter warten" | appears after seconds of routing | Vorbereitung | owner 2026-11-19 (form A), 2026-11-03 (#21 B) | progress/WaitWindow ~169, RouteWait ~49, WaitRules ~222 + ~3 tests | yes (waitengine.ts, loadoverlay.ts) |
| Dialog "Teile dieser Route wurden nicht gefunden" (Trotzdem navigieren / Route korrigieren) | when starting a plan with straight legs | unterwegs | R-12; C3 | RouteDialogs.kt ~25 | no |
| Dialog "Plan speichern" / "Als Kopie speichern" (name field) | via ☰ or ⋮ → 3 taps | Vorbereitung | C18; owner 2026-10-31 | RouteDialogs.kt SaveNameDialog ~25 | yes |
| Dialog "Etwas ist schiefgegangen" (save problem) | on failure | alle | R-08/robustness | RouteDialogs.kt ~10 | no |
| Dialog "Plan wird aus der Tour erstellt …" (Abbrechen) | after "Als Plan übernehmen" | nach der Tour | owner 2026-11-01 | RouteDialogs.kt ~15; TrackToPlanState ~137 | no |
| Box "Deinen alten Plan wiederherstellen?" (Wiederherstellen / Behalten) | after a new plan replaced the one on the map | Vorbereitung | owner 2026-11-24 (approved from a picture) | RestoreAskMessage.kt ~25, RestoreAsk ~43, RecentPlansState ~227 | no |
| Plan during a tour: line "Neuer Plan – deine Aufnahme läuft weiter" and "Rückgängig" line | in Plan while recording | unterwegs | owner 2026-11-27 | NavigateScreen.kt NewPlanLine ~17 | no |
| Long route boxes: "Diese Route ist sehr lang" (Trotzdem planen / Abbrechen), "Lange Strecke: …" (Zwischenziel setzen / Warten), "Zu weit für das Handy" | when a very long plan or leg is calculated | Vorbereitung | owner 2026-11-03 (#21); docs/design/long-bike-routes.md | LongLegAsk.kt ~70, TourBar.kt LongRouteDialog ~40, LongRouteCancelCamera ~36 + ~2 tests | partly (longroute.ts ~166) |
| "Als Plan übernehmen" (done tour → editable plan) | My routes ⋮ / overview | nach der Tour | F-GPX-04; owner 2026-11-06 F1 | TrackToPlanState.kt ~137 + tests | no |
| Karte: verschieben, zoomen, drehen, Doppeltipp-Zoom (offline vector map, hillshade, contours, trails by difficulty, water, forest, place names; zoom to 18) | the map | alle | F-MAP-01..04; R-01; C6 | MapHost.kt ~541, MapScreen.kt ~901, StyleLoader (map pkg) + C6 tests (map group ~2270) | yes (maplibre-gl, mapcontent.ts ~136) |
| Positionspunkt (dot + heading arrow + accuracy circle; grey when no fix, "DEMO" under demo) | the map | alle | F-POS-01/03/04 | MapOverlays/MapFooter, PlanGps.kt ~76, LocationTracker ~297 | partly (position.ts ~86) |
| POI symbols on the map (viewpoints, huts, water, food, transport …; hidden groups per Settings) | the map | alle | F-MAP-09; C19 | PoiHit/ShownPois (poi/), MapHost.kt | yes (poidensity.ts) |
| Meldungs-Boxen (MessageCard): one box at a time at the top – FRAGE (buttons), HINWEIS (quiet), HINWEIS_WARN (stays until tapped); states are lines, not boxes | top of the map | alle | owner 2026-10-31 (docs/design/ui-style.md) | MessageCard.kt ~254, NoticeBoard (state) ~51, NoticeTypes ~77, MessagePlacement + ~7 test files ~610 | partly (msgcard.ts ~104) |
| Box "OnPath x.y ist verfügbar" (Installieren / Später) and download progress card | over the map in Plan | alle | F-OFF-06; A-003 | UpdateUi.kt ~100 | no |
| Quiet error/info lines in other screens (NoticeLine in Settings / My routes) | per screen | alle | owner 2026-10-31 | Notices.kt, NoticeBoard ~51 | no |
| Page fade transitions between map, My routes, Settings, Search | all full-screen pages | alle | owner 2026-11-12 (PageFade) | PageFade.kt, ModeSwitch.kt ~157 | no |

## Search (Suche) and places

| Name as shown | Where / how (taps from start) | For | Origin | Size (code + tests, rough) | In browser planner? |
|---|---|---|---|---|---|
| Suchansicht (full screen: grey field "Orte oder Adressen suchen", "Abbrechen", ✕ clear) | 1 tap on the card's field | Vorbereitung, unterwegs | F-PLAN-12; owner 2026-11-27 variant A ("Apple-style") | search/SearchScreen.kt ~456, SearchHost ~75, SearchSession ~286, SearchState ~247, SearchFlow ~266 + 22 test files ~2640 | yes (search.ts ~474) |
| Kategorie-Zeichen: Orte · Essen · Schlafen · Sehenswert · Wasser · Bahn · Parken (filter on typed text) | in search, 2 taps | Vorbereitung, unterwegs | F-MAP-09 / F-PLAN-12; owner 2026-11-27 | SearchScreen.kt ~60, SearchKinds (core) + SearchChipsScreenshotTest ~59 | yes |
| One mixed result list: offline places, POIs, trails ("Weg"), addresses, streets, shops, peaks, passes; "nahe deiner Route", "online" tags | typing | Vorbereitung, unterwegs | F-PLAN-12 (hybrid offline + online) | SearchScreen.kt ~150; search/DiskIndex ~357, SearchAreas ~370, SearchDb ~101 + ranking tests ~200 | yes |
| "Offline-Suche wird vorbereitet…" / "Nichts dieser Art in der Offline-Karte." / "Kein Treffer in der Offline-Karte – …" | state lines | Vorbereitung | R-01; F-PLAN-12 | SearchScreen.kt ~20, SearchIndexSwapTest ~86 | partly |
| Online-Suche (Photon) with "Online-Suche läuft…" and "Online-Ergebnisse © OpenStreetMap" | automatic when network exists | Vorbereitung | F-PLAN-12 (b); Settings → Datenschutz lists it | SearchSession.kt, core OnlineSearch, OnlineSearchTest ~133 | yes |
| "Zuletzt gesucht" list with "Löschen" (incl. entries tagged "Geteilt") | empty search | Vorbereitung | owner 2026-11-15/27; shared entries owner 2026-11-02 | SearchScreen.kt recentSection ~60; SearchStore ~89 | yes (recent.ts ~315) |
| "Favoriten" (max 5) with "Alle Favoriten ›" | empty search | Vorbereitung | F-PLAN-14 (owner 2026-11-15) | SearchScreen.kt ~40; KeptPlacesSlice ~101 + SavedPlacesTest ~110 | yes (keptplaces.ts ~187) |
| "Zuhause" row and "Als Zuhause festlegen / Zuhause entfernen" – code exists, switched OFF (HOME_ROW_SHOWN = false) | not visible | – | owner 2026-11-19 via captain: "so wichtig ist das Zuhause eigentlich auch nicht. Können wir das erstmal abschalten" | SearchScreen.kt ~20, HomeRowHiddenTest ~40 | partly (pinmenu.ts keeps it hidden too) |
| "Google Maps ↗" fixed row above the keyboard (hands the query to Google Maps) | in search | Vorbereitung | owner 2026-11-27 | SearchScreen.kt ~30 | partly |
| Picked result → place card + pin on the map (name chip, menu) | 2–3 taps | Vorbereitung | F-PLAN-12; owner 2026-11-13 | PlaceCard.kt ~131, SearchFlow ~266, PinMenuItems (core) | yes |
| Place card: one primary button saying what it does ("Als Ziel", "Als Stopp 2", "Aus dem Plan entfernen"), "Als Start", "Als Ziel", "Google Maps ↗" | with place card open | Vorbereitung | owner 2026-11-02/27; C19 | PlaceCard.kt ~131, PlaceCardUi ~18 , AddPreviewAndDataTest ~117 | yes |
| Place card info: name, kind, elevation, address, opening hours, cuisine, phone, website | place card | Vorbereitung, unterwegs | F-MAP-09 | PlaceCard.kt, core Place ~166 | partly |
| Add preview "liegt auf deiner Route" / "passt dort am besten" / "ca. X Umweg" | in place card/menu when a route exists | Vorbereitung | owner 2026-11-15/26 (PlaceAddMenu) | PlaceAddMenu.kt ~240 + tests | yes |
| "Merken" ☆ / "Gemerkt" ★ in every place's / named point's menu | place menu row | Vorbereitung | F-PLAN-14 (owner 2026-11-15, „Orte merken“) | KeptPlacesSlice ~101, PlacesBackup ~50 | yes (keptplaces.ts) |
| Place shared into OnPath from another app (Google Maps → Share; text or URL) → "Geteilter Ort wird gesucht…", ring on map | Android share sheet | Vorbereitung | A-020 (code comment, not in requirements.md); owner 2026-11-02 | IncomingIntent.kt ~40, core SharedPlace* ~260 + tests ~520 | yes (share.ts ~63) |
| Box "Ort nicht eindeutig" (button Suchen) / "Dieser Ort wurde nicht gefunden …" / "OnPath hat im geteilten Text keinen Ort gefunden." | after sharing a place | Vorbereitung | A-020 | MapScreen.kt sharedUnsureMessage ~10 | no |
| geo: links open a place | external app | Vorbereitung | no requirement found | AndroidManifest (geo scheme), IncomingUri ~26 | no |
| Address from coordinates note "Position aus der Adresse ermittelt" | place card | Vorbereitung | A-020 | AndroidPlaceGeocoder ~59 + AddressPin tests ~168 | partly |
| Route names from search ("Rundtour zu …", "Von … nach …", "Nach … über …") used as default plan name | automatic at save | Vorbereitung | owner 2026-11 (RouteNamer) – no dated quote found | search RouteNamer ~140, RouteNamerTest ~101 | partly |

## Navigate / tour (Navigation, Aufnahme, Tour beenden)

| Name as shown | Where / how (taps from start) | For | Origin | Size (code + tests, rough) | In browser planner? |
|---|---|---|---|---|---|
| Navigate mode (map follows position, route, ⚙ + controls in green) | Los geht's: 1 tap on the card's button + setup/permissions (1–3 more) | unterwegs | F-NAV-01, F-NAV-03; C5 | NavigateScreen.kt ~1302 (largest UI file), TourSession ~454, TourController ~172, TourFollow ~294, core navigation ~2000 + ~10 test files ~1370 | no (owner: not in the browser on purpose, web-planner.md §6) |
| Status card (distance to go, ascent, time, arrival "Ankunft", tap to fold; "Höhenprofil anzeigen") | Navigate, always | unterwegs | F-NAV-01; owner 2026-11-05/26 | NavigateScreen.kt NavStatusCard ~230, CardFit ~25 | no |
| Turn card (arrow, "Links in 300 m", roundabout exit, way word "auf den Radweg") | Navigate, at junctions | unterwegs | F-NAV-05 (visual part); owner idea 2026-11-01 | NavigateScreen.kt TurnCard ~30, RoundaboutSign ~75, core TurnDetector ~133 | no |
| Stops ahead ("Stopp 2 an 16:35", "Ziel · Name") | Navigate with stops | unterwegs | owner 2026-11-06 | NavigateScreen.kt StopSign/StopAheadRow ~40, StopText ~74 | no |
| Position line with "Position kopieren" (coordinates, "kopiert ✓") | tap on the position line | unterwegs | F-POS-05 (partly: copy only, see Counted) | NavigateScreen.kt PositionLine ~30 | no |
| GPS state lines: "GPS gut/schwach/wird gesucht", "Position n min alt", "Ungefähre Position (WLAN, ±m)", "Kein GPS", "Zeit pausiert" | Navigate card | unterwegs | F-POS-03; R-04; owner 2026-11-02/06 | NavigateScreen.kt ~80; NoGpsShow (recording/Cues.kt) | no |
| "schneller / langsamer als geplant" pace line (uphill/flat/downhill) | Navigate card | unterwegs | owner 2026-11 (tour analysis); PaceModel | NavigateScreen.kt paceNote ~25; core time model | no |
| Activity chip on the card → sheet "Du navigierst als … – Wechseln zu … für den Rest der Route" (checks the rest of the route per activity) | tap on the chip in Navigate | unterwegs | owner 2026-11 (activity check); F-PLAN-01 | ActivitySwitchSheet.kt ~162, ActivityCheckSlice ~304, ActivityCheckUi ~75 + tests | no |
| Off-route card/question: "Du bist 120 m von der Route weg" → "Neue Route" / "Original behalten" (states: searching, found, no map, no way, "umdrehen") | automatic when off route | unterwegs | F-NAV-02; C5; owner 2026-10-31/11-01 (one question shape) | NavigateScreen.kt OffRouteCard/rerouteMessage ~70; core OffRouteDetector ~474, OffRouteSay ~93 + ~10 tests ~1370 | no |
| Via-stop question "Über <Stopp>: …" / "<Stopp> auslassen" / "Ohne Stopp" | when off route and a stop lies ahead | unterwegs | owner 2026-11-01 | NavigateScreen.kt ~30, core StopSkip | no |
| Box "Route beginnt 31 km entfernt" – Hinführen / Ohne Route weiter / Abbrechen / OK on "Kein Weg…" | at start when far from route | unterwegs | owner 2026-10-31 | NavStartMessages.kt ~65 | no |
| Box "Du stehst mitten auf der Route" – "Zu ① führen" / "Hier einsteigen" | at start mid-route | unterwegs | owner 2026-11-03/04 | NavStartMessages.kt ~12 | no |
| Box "Kein Ziel mehr vor dir." – "Zurück zum Start" / "Ohne Route weiter" | after Plan edit removed the goal | unterwegs | owner 2026-11 (RouteBackDialog) | TourBar.kt RouteBackDialog ~20 | no |
| Arrival box "Du bist angekommen." – Tour beenden / Weiter aufzeichnen / Tour löschen | on arrival (within ~200 m) | unterwegs | F-NAV-06; owner 2026-11-06 | TourBar.kt ArrivalDialog ~75, EndAsk (state) ~7 + tests | no |
| Tour bar: "Anhalten" and "Planen" (hold to unlock), when paused "Weiter" and "Tour beenden"; "Angehalten seit 14:32" | bottom of Navigate | unterwegs | F-NAV-04; owner 2026-11-06/07/08/26 | TourActionBar.kt ~253, HoldToConfirm.kt ~178 + ~10 tests ~1800 | no |
| Hold-lock: buttons need a hold (~1 s, "Gedrückt halten"), 10 s window after a fired hold; setting "Knöpfe während der Tour halten" | Navigate with lock on | unterwegs | owner 2026-11-05/06/08 (variant A) | HoldToConfirm.kt ~178, UnlockWindow (core) ~57 | no |
| Big blue "Planen" button (when lock is off / no recording) | Navigate | unterwegs | owner 2026-11-06 | TourActionBar.kt ChangePlanButton ~25 | no |
| Sheet "Tour beenden?" – distance · time · ascent, big "Tour speichern", "Weiter", quiet red "Tour löschen" (+ "Kommt für 30 Tage in den Papierkorb") | Navigate: Tour beenden 2 taps | nach der Tour | F-NAV-06; C11; owner 2026-11-01/06/26 | TourBar.kt EndTourDialog ~220, TourEndSlice ~414 + ~10 tests | no |
| Ride question at the end ("Am Ende bist du gefahren" / "Unterwegs bist du gefahren": Nur bis … speichern, Alles speichern Fahrten nicht zählen, In zwei Touren aufteilen, So speichern wie gefahren) | after Tour speichern when a vehicle ride was detected | nach der Tour | C12; docs/design/vehicle-detection.md | RideQuestion.kt ~112, RideSummary.kt ~182 + Ride tests ~870 | no |
| "Fahrten bearbeiten" (ride summary dialog with map + timeline) | My routes ⋮ on a done tour | nach der Tour | C12 | RideSummary.kt ~182 | no |
| Dialog "Unterbrochene Tour" – Weiter / Speichern / Löschen | at app start after a crash/reboot | unterwegs, nach der Tour | R-07/R-08; C4 | UnfinishedDialog.kt ~25, TourInterruptedReceiver ~63 + UnfinishedTourSetupE2E ~111 | no |
| ⚙ "Navigationseinstellungen" (in the control column) → on-the-way settings list | Navigate, 1 tap | unterwegs | owner 2026-11-07 | MapFooter.kt ~25; SettingsScreen onTheWay ~60 | no |
| "Standort teilen" button on the controls | Navigate, 1 tap | unterwegs | owner 2026-11-20/24 | see "Sharing" | web viewer only (web/app/src/live) |
| Navigate map controls: Kompass · Standort · Ganze Route zeigen · Auge (green) | Navigate | unterwegs | F-POS-02, F-MAP-05 | MapFooter.kt (shared with Plan) | partly |
| Ruheansicht (smart screen for bikes: dark until just before a junction; tap shows the map) | Settings "Bildschirm beim Radfahren: Smart" | unterwegs | owner 2026-11 (battery-audit.md); no dated quote found in code | RestView.kt ~172, SmartScreen (core) ~200 + ~4 tests ~600 | no |
| Keep screen on while hiking (pocket-aware via proximity sensor) | Settings "Bildschirm beim Wandern anlassen" | unterwegs | owner (docs/design/battery-audit.md) | PocketScreen.kt ~128, PocketLogic (core) + tests | no |
| Box "Abzweigungen auf dem Sperrbildschirm?" (Einschalten / Später) when notifications are off | in Navigate | unterwegs | owner 2026-11 (notifications) – no dated quote found | NavStartMessages.kt notificationsOffMessage ~10 | no |
| Banner "Position (WLAN)" indoor start (provisional fix until first GPS) | Navigate | unterwegs | owner (NavigateScreen comment "started indoors") | NavigateScreen.kt ~40 + CoreIndoorStartE2E ~87 | no |
| Done-tour pace "Zeit pausiert", pauses: automatic pause on standing ("kein GPS", "kein Fortschritt") | Navigate | unterwegs | F-NAV-06 | core time model | no |
| Demo ride chip (simulated GPS speeds ×) | Navigate when Demo location is on | Vorbereitung (screenshots) | owner (Demo location) | NavSimChip.kt ~69, DemoLocation.kt ~236 + Demo tests ~446 | no |
| Back-button rule in Navigate (back never ends the tour) | system Back | unterwegs | R-15 | NavigateScreen.kt BackStaysRule ~12 | no |

## My routes (Meine Touren)

| Name as shown | Where / how (taps from start) | For | Origin | Size (code + tests, rough) | In browser planner? |
|---|---|---|---|---|---|
| Meine Touren screen (list of plans, done tours, imported GPX) | 1 tap on chip, or ☰ → 2 | Vorbereitung, nach der Tour | docs/requirements.md §7 "My tours"; F-PLAN-10 | Screens.kt RoutesScreen ~330 (of 958), RoutesSlice ~400, RoutesState ~304, RoutesGoSlice ~285, RoutesOpenState ~161 + 20 test files ~3940 | yes (plans.ts ~1077) |
| Tabs "Geplant" / "Gemacht" | top, 1 tap | Vorbereitung, nach der Tour | keine Anforderung gefunden (RoutesTabSwitch.kt has no dated quote) | RoutesTabSwitch.kt ~59 | partly ("Gemacht" app only: ridden tours, owner) |
| Search "Touren durchsuchen" | top | Vorbereitung | keine Anforderung gefunden (L1 list design, no dated quote) | Screens.kt RoutesSearchField ~20, RouteListRules ~75 | yes (plans.ts) |
| "GPX importieren" | top button | Vorbereitung | F-GPX-01/02 | ImportExport.kt ~289 | yes (gpximport.ts ~398) |
| ⋮ of the list: "Alle exportieren (ZIP)" · "Papierkorb" | 2 taps | nach der Tour | C11; F-GPX-06 | Screens.kt RoutesMenu ~15, ImportExport exportAll ~40 | no |
| Sort chip "Sortieren: Neueste zuerst / Älteste / Länge / Dauer / Anstieg" (tap again flips) | chips row | Vorbereitung | owner 2026-11-12 (variant C) | RouteSortChips.kt ~83 + tests | yes (routesort.ts ~73) |
| Chip "Favoriten" (only favourites) + ☆/★ star on each row | chips row / row | Vorbereitung | owner 2026-11-13 (variant C) | Favourites.kt ~44, RoutesSlice | yes |
| Activity filter chip ("Art: Wandern … Alle") – preset with the planning activity | chips row | Vorbereitung | owner 2026-11-13 | ActivityFilter.kt ~96 + RoutesActivityFilterE2E ~51 | yes (routefilter.ts ~82) |
| Section headers "Zuletzt" (drafts, auto-kept plans) and "Gespeichert" | list | Vorbereitung | owner 2026-11-06/24 | RecentPlans.kt ~123, RecentPlansState ~227 | no |
| Tour row (84 dp map tile, name, activity line "Wandern · T2 · ↻ Rundtour", facts; tags "Neu"/"Geändert"/"In Bearbeitung"; "Unterwegs" while recording; "✓ Gerade gespeichert") | list | Vorbereitung, nach der Tour | owner 2026-11-07/13/16 (L1) | Screens.kt TourCard ~110, RoutePreview.kt ~405, RouteThumbImages ~68 + RouteThumbE2E ~58 | yes (routethumbs.ts ~318) |
| Row ⋮ menu: "Planen" · "Los geht's" (planned) · "Als Plan übernehmen" (done) · "Umbenennen" · "Als Kopie speichern" · "GPX exportieren" · "Plan teilen" · "Fahrten bearbeiten" · "Löschen" | row ⋮, 2–3 taps | Vorbereitung, nach der Tour | owner 2026-11-06/07/13; F-GPX-06 | Screens.kt TourItemMenu/TourMenuItems ~70, RouteRowActions ~96 | partly (plans.ts) |
| Delete (no question; goes to Papierkorb; not for the running tour) | ⋮ → Löschen | nach der Tour | C11; owner 2026-11-01 ("ist ja sowieso alles im Papierkorb") | RoutesSlice ~40, Backups.kt RecycleBin ~60 + CoreDeleteE2E ~146 | no |
| Rename dialog ("Plan/Tour/Importierte GPX umbenennen") | ⋮ → Umbenennen | Vorbereitung | owner (names) | Screens.kt NameDialog ~20 | yes |
| Tap on a row → big overview (map picture, swipe between tours, page dots "3 von 12", buttons "Planen" / "Folgen" / cancel) | 1 tap on row | Vorbereitung | owner 2026-11-13/26 | RoutePreview.kt RouteOverview ~200, OverviewSnapshotOf ~25, DotWindow + OverviewSwipeE2E ~77 | partly (overview.ts ~138) |
| Done-tour overview (Rückblick): Strecke, Aufstieg, Abstieg, Gesamtzeit, In Bewegung, Pausen, "n % schneller/langsamer als geplant", längste Pause, Höchster Punkt, Schnellster km, mini profile, "Teilen" (track / plan) | tap a "Gemacht" row | nach der Tour | F-NAV-07; owner 2026-11-15 (round 3 tour analysis) | DoneTourOverview.kt ~364, TourReview (core) ~258 + review tests ~730 + DoneTourReviewE2E ~74 | no |
| "Mehr" sheet (planned vs needed, clock bar, rates) | done-tour overview, 2 taps | nach der Tour | owner 2026-11-15/16 | RouteMore.kt ~305 | no |
| Dialog "Tour gespeichert" (In Meine Touren ›) / "Kurze Aufzeichnung" (Rückgängig) | after Tour speichern | nach der Tour | owner 2026-11-06 #35; C11 | Screens.kt SavedTourDialog/ShortRecordingDialog ~55 | no |
| Draft actions on a "Zuletzt" row: Speichern · Löschen · Planen | row | Vorbereitung | owner 2026-11-06 | Screens.kt DraftActions ~55 | no |
| Papierkorb screen (restore, "Jetzt löschen" with confirm, "bleiben 30 Tage") | list ⋮ → Papierkorb, 3 taps | nach der Tour | C11; R-07 (never lose a track) | BackupScreens.kt RecycleBinScreen ~100 + ~3 tests | no |
| Pull to sync (pull down the list when paired) | list | Vorbereitung | A-007; owner 2026-11 | Screens.kt PullToSync ~10 | yes (sync) |
| Empty states ("Noch keine Pläne …", "Noch keine Touren.") | list | – | – | Screens.kt ~10 | partly |
| Multi-day parts (badge „N Tage“, day sheet, ✓ done days, „Tag 3 von …“) | – | – | F-PLAN-16..22, F-NAV-08..12 | being rebuilt on branch feat/multiday-one-day (not listed separately) | planner plans them (multiday.ts ~722) |

## Settings (Einstellungen)

Reached by ☰ → Einstellungen (2 taps). Start list = six topics in two groups: "Deine Einstellungen" (Planen, Karte, Navigation, Sicherung & Browser) and "App" (Datenschutz, Über OnPath) – owner 2026-11-27 ("six topics instead of eleven"). Sub pages are 1 tap deeper. All of this is phone-only (the browser planner has no settings page).

| Name as shown | Where / how (taps from start) | For | Origin | Size (code + tests, rough) | In browser planner? |
|---|---|---|---|---|---|
| Einstellungen start list with a line per topic (summaries like "Tempo … · Routen …", "Letzte Sicherung …", "Update verfügbar") | 2 | alle | owner 2026-11-27; docs §7 Settings | SettingsScreen.kt ~753, SettingsSummary ~225, SettingsPage (state) ~62, SettingRows ~285 + 7 test files ~1660 | no |
| Planen → "Dein Tempo" (Wandern levels Gemütlich…Sehr fit; Radfahren per bike; "lernt bei jeder Tour") | 3 | Vorbereitung | owner 2026-11-27; pace learning docs/plan-time-estimate.md; F-PLAN-06 | PaceSettings.kt ~100, core PaceModel ~327 + Pace tests ~920 | no |
| Planen → "Wanderrouten folgen" / "Radrouten folgen" (Weniger · Standard · Mehr) | 3 | Vorbereitung | owner 2026-11-27; F-MAP-04/08 (marked routes) | PaceSettings.kt MarkedTrailsRow ~40 | no |
| Planen → "Vibration beim Planen" (switch) | 3 | Vorbereitung | owner 2026-11-26 | SettingsScreen.kt ~5 | no |
| Karte → "Kartenstil" (only if more than one theme exists) | 3 | alle | F-MAP-06/07 | SettingsScreen.kt MapSection ~20 | partly (one style) |
| Karte → "Höhenlinien" (switch) | 3 | alle | F-MAP-01 | SettingsScreen.kt ~3 | partly |
| Karte → "Relief" (switch; auto-off notice "Relief aus – die Karte läuft so flüssiger") | 3 | alle | F-MAP-01; owner 2026-11-07 (ReliefAuto) | SettingsScreen.kt ~3, ReliefAuto (core) ~74 | partly |
| Karte → "Kartenausrichtung" (In Bewegungsrichtung / Norden oben) | 3 | unterwegs | F-MAP-05 | SettingsScreen.kt ~8 | no |
| Karte → "Interessante Orte" (7 groups: Essen & Trinken, Hütten & Camping, Wasser, Sehenswertes & Aussicht, Verkehr, Einrichtungen, Sonstiges; per-kind switches, "Alle ausblenden") | 4 | alle | F-MAP-09 | SettingsScreen.kt PoiSection ~45, PoiGroups (core) | partly (poidensity.ts) |
| Karte → "Offline-Karten" page: area list (name, MB, date, status), per-area Delete with confirm, total, "Alle abbrechen", "Karten nur über WLAN laden", "Karten jetzt aktualisieren", "Aufräumen" (shows what goes and stays), "Alle löschen" (Weltübersicht bleibt) | 4 | Vorbereitung | F-OFF-03/04/05; C13/C14 | OfflineMapsSection.kt ~245, OfflineResult ~40 + map-download tests (group) ~2270 | no (planner loads areas itself, areas.ts ~314) |
| Navigation → "Handy einrichten" row (red ⚠ and what is missing; else last row) | 3 | Vorbereitung | owner 2026-10-30/11-27; C17 | SettingRows.kt ~285, PhoneSetupSection ~113 | no |
| Navigation → "Warnung abseits der Route" (switch) | 3 | unterwegs | F-NAV-02 | SettingsScreen.kt NavigationSection ~55 | no |
| Navigation → "Warnen nach" (distance menu in m) | 3 | unterwegs | F-NAV-02 ("configurable") | AppSettings.OFF_ROUTE_CHOICES (data) | no |
| Navigation → "Vibrieren abseits der Route" (switch) | 3 | unterwegs | owner 2026-10-31 | SettingsScreen.kt | no |
| Navigation → "Hinweise an Abzweigungen" (Aus · Vibration · Stimme) + one test button "Vibration testen" / "Stimme testen" | 3 | unterwegs | F-NAV-05; owner 2026-11-01 (either/or), 2026-11-27 (value row) | SettingsScreen.kt ~30, Cues.kt ~50, Speaker.kt ~396, VoiceGuide ~713 + Voice/Cue tests ~1940 | no |
| Navigation → "Bildschirm beim Wandern anlassen" (switch) | 3 | unterwegs | owner (battery-audit.md) | SettingsScreen.kt, PocketScreen ~128 | no |
| Navigation → "Bildschirm beim Radfahren" (Immer an · Smart · Wie das Handy) | 3 | unterwegs | owner 2026-11 (smart screen) | SettingsScreen.kt BikeScreenRow ~15, RestView ~172 | no |
| Navigation → "Position auf der Route" (snap dot to route, switch) | 3 | unterwegs | owner 2026-11 – no dated quote found | SettingsScreen.kt ~3, core SnapRules | no |
| Navigation → "Knöpfe während der Tour halten" (slider Aus … s) | 3 | unterwegs | owner 2026-11-05/06 | SettingsScreen.kt HoldTimeRow ~25 | no |
| Sicherung & Browser → Sicherung: folder line, last backup, "Speicherzugriff erlauben", "Jetzt alles sichern", "Anderen Ordner wählen (z. B. Drive, Nextcloud)", "Wieder Documents/OnPath verwenden", "Touren und Pläne wiederherstellen" | 3 | nach der Tour | C11; R-07 | BackupScreens.kt BackupSection ~75, Backups.kt ~329, AndroidBackupStore ~264 + 6 test files ~1035 | no |
| Sicherung & Browser → Browser-Abgleich: status, "Kamera öffnen", planner name, last sync, "Trennen" | 3 | Vorbereitung | A-007; C16 | SyncSettings.kt SyncSection ~100 (file ~451), PlanSync ~456, PlanSyncPass ~409, SyncDriver ~165 + 29 files ~5600 | yes (sync.ts ~1129) |
| Datenschutz (what leaves the phone: maps, routing, search, update, links, sync, live share, diagnostics) | 3 | alle | owner 2026-11 (A-020 review; privacy text) | SettingsAboutPages.kt PrivacySection ~60 | no (website) |
| Über OnPath: logo, version (7 taps → Developer tools), update state with button, "Hilfe & erste Schritte ↗", "Diagnose ›", "Rechtliches & Quellen ›" | 3 | alle | owner 2026-11-27 (slimmed) | SettingsAboutPages.kt ~192, UpdateUi ~100 | no |
| Updates: "Nach Updates suchen" / "Version x ist verfügbar" / "x herunterladen und installieren" / "Du hast die neueste Version." | in Über OnPath, 3–4 | alle | F-OFF-06; A-003 | UpdateUi.kt ~100, AppUpdates ~96, AppUpdater ~216 + 3 tests ~254 | no |
| Rechtliches & Quellen: Website · Kontakt · Datenschutzerklärung · Impressum · Quellcode (copyable) · Quellen (OSM, OpenMapTiles, Terrarium, Natural Earth, BRouter, Maki/Temaki, MapLibre) · Lizenzen (list with "Lizenztext") | 4 | alle | A-020 (licences page); ODbL credit F-MAP | AboutLinks.kt ~159, SettingsAboutPages.kt CreditsSection/LicencesSection ~100 | no |
| Diagnose: "Send problem report" (share a file), app language line, routing fingerprint line, Problems list (folded, entries/errors count) + clear | 4 | alle | R-08 (crash reports local, sent only if user agrees); owner 2026-11-02 | SettingsScreen.kt DiagnosticsSection/ProblemList ~70, AppDiagnostics ~35, debug/Problems | no |
| Einstellungen from Navigate's ⚙ ("on the way" list: Navigation page first, then Sicherung & Browser / Karte … and "Alle Einstellungen ›") | Navigate → ⚙ | unterwegs | owner 2026-11-07 | SettingsScreen.kt onTheWay ~45, SettingsSummary.onTheWay | no |

## Developer tools (Entwicklertools) – hidden; unlocked by 7 taps on the version in Über OnPath (remembered; always on in debug builds)

Path: ☰ → Einstellungen → Über OnPath → 7 taps on the version → "Diagnose ›" → card "Developer tools" (closed at first). Taps from start: 2 + 1 + 7 + 1 + 1 = 12. Texts are English only. Origin of the whole page: owner (SettingsScreen comment "Developer tools shown in Diagnostics (owner): hidden until the version line … tapped 7 times", no date); no requirement ID.

| Name as shown | Where / how | For | Origin | Size (code + tests, rough) | In browser planner? |
|---|---|---|---|---|---|
| Unlock: 7 taps on the version line | Über OnPath | Vorbereitung (tester) | owner (undated comment); DevToolsUnlock.kt | DevToolsUnlock (state) ~17 + AboutSection ~30 | no |
| Developer tools card header (fold) | Diagnose | tester | owner (undated) | SettingsDevTools.kt ~298 + ~3 tests (in the 1660 settings group) | no |
| "Map server: …" line | card | tester | – | SettingsDevTools.kt ~3 | no |
| "Download routing data (map centre)" button | card | tester | docs C10 (normally comes with the map) | SettingsDevTools.kt ~5, RoutingLoads.kt ~18 | no |
| "Map areas (debug)": installed areas with "Delete" each, total MB, areas in progress, "Request map area at map centre" | card | tester | owner (map cells; docs/design/map-cells.md) | SettingsDevTools.kt MapCellsDebugList ~30 | no |
| "Time estimate of the route (formulas)" (greyed without a route) | card | tester | owner 2026-11 (time model) | SettingsDevTools.kt ~5, TimeDetailsScreen ~172 | no |
| "Map style": test style status + "Remove" + "Reload map style" | card | tester | owner (tools/dev/push_style.sh) | SettingsDevTools.kt TestStyleRow ~15 | no |
| "Demo location": "Demo: ride the planned route/track", "Demo: stand at the map centre", "Demo: <recorded tour>" (ride again at recorded times), speed buttons ×, "Stand still" / "Go on", "Stop demo – real GPS again" | card | tester, screenshots | owner (screenshots); docs/core-features/README.md | SettingsDevTools.kt DemoRow ~55, DemoLocation.kt ~236, NavSimChip ~69 + Demo tests ~446 | no |
| "Tourvorschläge" switch (on by default since 2026-11-18; off-switch) | card | tester | owner 2026-11-18 (tour-suggestions.md §7) | SettingsDevTools.kt ~3, SuggestSlice ~287 | no (planner has ?debug) |
| "Day table: all columns in one row" switch | card | tester | owner (multi-day layout) – belongs to the multi-day rebuild | SettingsDevTools.kt ~6, DayTableUi ~117 | n/a |
| "App language": System / English / Deutsch | card | tester | docs/i18n.md | SettingsDevTools.kt LanguageRow ~20 | partly (i18n.ts) |
| "Map data": import a map package or style file (.zip, .pmtiles, .geojson, .rd5, onpath-*.json) from this phone | card | tester | owner (offline import path; routes_data_import) | ImportExport.kt ~100 (map import), DataActions ~30 | no |
| "Data folder" path + file checks ("… – missing") + "Map package: region · area · OSM date" (+ warning "From another package …") | card | tester | R-16; owner | SettingsDevTools.kt ~25, DataFiles (data) ~147 | no |

## Setup (Handy einrichten / phone setup guide)

The guide is one full-screen step flow (SetupGuideScreen.kt ~378, PhoneSetupState ~168, PhoneStateReader ~98, core PhoneCheck ~247 + PhoneCheck/Setup tests ~760 + PhoneSetupE2E ~53). Whole feature: C17, R-06, R-09, docs/design/onboarding-phone-check.md; it is phone-only (no browser equivalent).

| Name as shown | Where / how (taps from start) | For | Origin | Size (code + tests, rough) | In browser planner? |
|---|---|---|---|---|---|
| "Willkommen bei OnPath" (Jetzt einrichten / Später) | first start (0) | Vorbereitung | docs §7 "first start: three short screens"; C17 | SetupGuideScreen.kt ~60 | no |
| Guide before a tour (same steps, only what is missing; "Später" on a recommended step snoozes 7 days) | after Los geht's / Nur aufzeichnen, 1–3 taps | Vorbereitung | C17; R-06/R-09; owner 2026-10-30 | PhoneSetupState.kt ~168, PhoneCheck (core) ~247 | no |
| Step "Dein Standort" (permission "Genau" + "Bei Nutzung der App"; "Android fragt nicht noch einmal" path to App settings) | guide | Vorbereitung | R-06 | SetupGuideScreen.kt ~40 | no |
| Step "Genauer Standort" | guide | Vorbereitung | R-05/R-06 | ~25 | no |
| Step "Standort ist aus" (phone location switched off) | guide | Vorbereitung | R-03 | ~25 | no |
| Step "Benachrichtigungen" | guide | Vorbereitung | owner 2026-10-30; R-09 | ~25 | no |
| Step "Vibration" (Testen → "Hast du einmal und dann zweimal Vibrieren gespürt?" Ja / Nein; "Toneinstellungen öffnen") | guide, also Settings → Navigation → "Vibration testen" | Vorbereitung | owner 2026-10/11-08 (test = one pulse); F-NAV-05 | ~45 + Cues.kt ~50 | no |
| Step "Sprachansagen" (Testen → "Hast du den Satz gehört?"; "Stimme laden", "Sprachdienste von Google", "Einstellungen Sprachausgabe", mute / call / failure diagnoses) | guide, Settings → "Stimme testen" | Vorbereitung | F-NAV-05; docs/design/voice-guidance.md | ~70 + Speaker.kt ~396 + VoiceFix ~33 | no |
| Step "Akku" ("Uneingeschränkt" instructions) + maker-specific step "<Marke>-Handys" (+ "Mehr Infos ›") | guide | Vorbereitung | R-09 | ~50 | no |
| Step "Energiesparmodus ist an" (saver stops GPS with screen off on some phones) | guide | Vorbereitung | R-10 (partly; no own battery-saver mode, see Counted) | ~30 | no |
| Step "Wenn du OnPath länger nicht nutzt" (Android resets unused-app permissions; "An – nach langer Pause fragt OnPath neu") | guide | Vorbereitung | R-06/R-09; PhoneCheck.RESET_DAYS = 60 | ~30 | no |
| End screens "Alles eingerichtet" / "Fast fertig" | guide | Vorbereitung | C17 | ~20 | no |
| Settings → Navigation → "Handy einrichten" page: rows "Eingerichtet / Nicht eingerichtet / Noch nicht getestet" with "Einrichten" per row; groups "Für eine Tour nötig / Empfohlen / Optional" | 4 | Vorbereitung | owner 2026-10-30/11-27 | PhoneSetupSection.kt ~113 | no |
| Message "Die Tour ist nicht gestartet. Erlaube OnPath den genauen Standort …" | when recording cannot start | unterwegs | R-06 | strings_messages; RecordingService ~20 | no |

## Sharing & sync (Standort teilen, Browser-Abgleich, Backup, GPX in/out)

| Name as shown | Where / how (taps from start) | For | Origin | Size (code + tests, rough) | In browser planner? |
|---|---|---|---|---|---|
| "Standort teilen" (live link of a running tour; Android share sheet with text "Verfolge meine Tour: <link>") | Navigate: share button on the controls (1) or Settings from ⚙ (first row) | unterwegs | owner 2026-11-20 ("in der Navigation bei den Einstellungen, als erster Eintrag"), docs/design/live-tracking.md; commit 36ef7b048 | LiveShareUi.kt ~261, LiveShareWiring ~136, LiveShareSlice (state) ~199, app/live ~640 + 14 test files ~2700 + LiveShareNameE2E ~47 | viewer page only (web/app/src/live ~670) |
| Dialog "Dein Name für die Seite" (asked once, may stay empty; "Name auf der Seite" row to change) | first share | unterwegs | owner 2026-11-20 | LiveShareUi.kt LiveNameDialog ~20 | no |
| "Link nochmal teilen" / "Teilen beenden" / sign "Standort" and grey "Standort · wartet auf Netz" | while sharing | unterwegs | owner 2026-11-20/24 | LiveShareUi.kt ~120 | no |
| Message "Das Teilen wurde beendet. Der Link gilt nicht mehr." | when the server ends it | unterwegs | owner 2026-11-20 | LiveShareUi.kt ~10 | no |
| Pairing with the browser: QR scan (system camera) of plan.onpath.earth "Handy verbinden"; step line "Öffne am PC …"; confirm dialog "Mit diesem Browser verbinden?" with six digits | ☰ → Mit Browser verbinden (2) + camera | Vorbereitung | A-007; C16; owner 2026-10-30 | PairingFlow.kt ~156, SyncSettings PairEntry/PairLanding ~80, PlanLinkFlow ~122 + C16 tests | yes (sync.ts) |
| "Browser trennen?" dialog (Trennen) | Settings → Sicherung & Browser → Trennen (4) | Vorbereitung | A-007 | SyncSettings.kt ~25 | yes |
| Sync status lines/boxes ("Wartet auf den Browser …", "Browser-Abgleich ist voll", "Der Sync-Server lehnt die Pläne ab", "Seit über 6 Stunden nicht abgeglichen", "Im Browser getrennt", "Pläne gleichen sich nach der Tour ab") | ☰ dot + Settings | Vorbereitung | A-007 | SyncSettings.kt ~120, SyncNote (state) | yes (sync.ts) |
| Plan sync itself: saved plans + imported GPX tracks both ways (encrypted, EU server), after a change and at app open; conflict copy "Inzwischen im Browser geändert – als „…“ gespeichert" | automatic | Vorbereitung | A-007; docs/design/web-sync.md | PlanSync.kt ~456, PlanSyncPass ~409, PlaceSync ~103, SyncDriver ~165 | yes (sync.ts ~1129) |
| "Plan teilen" (plan link https://plan.onpath.earth/#p=… as text via share sheet) | row ⋮ → Plan teilen (3) | Vorbereitung | A-005; C15 | RouteRowActions/ImportExport ~40, core PlanLinks ~99 | yes (share.ts) |
| Open a plan link (App Link, QR/camera, or pasted text) → plan opens; "Plan-Links öffnen sich nach der Tour"; "Dieser Plan-Link ist nicht lesbar" | external | Vorbereitung | A-005; C15 | MainActivity.kt ~60, PlanLinkFlow ~122 + CorePlanLinkE2E ~130 | yes (it creates them) |
| GPX import: "GPX importieren" (file picker), "Open with"/"Share to OnPath" (GPX mime types) → track/route/waypoints, Komoot activity mapping, "Aktivität aus der Datei übernommen" | My routes (2) / external | Vorbereitung | F-GPX-01..05, F-GPX-08; C7 | ImportExport.kt ~289, core GpxParser ~290, Gpx tests ~555 | yes (gpximport.ts ~398) |
| GPX import messages ("Einige Teile der Datei wurden übersprungen", "Die Datei ist leer…", ">50 MB") | after import | Vorbereitung | F-GPX-03; R-14 | ImportRules (state) ~21, ImportExport ~40 | partly |
| GPX export: "GPX exportieren" per row, "Alle exportieren (ZIP)", done-tour "Teilen" (track or plan GPX) → Android share sheet | My routes (3) | nach der Tour | F-GPX-06 | ImportExport.kt ~100, DoneTourOverview ~40 | yes (plans.ts export) |
| Backup: every saved plan and tour also as GPX in Documents/OnPath (or chosen folder), automatic on save and at app start; "Jetzt alles sichern" | automatic + Settings (3) | nach der Tour | C11; R-07 | Backups.kt ~329, AndroidBackupStore ~264, PlacesBackup ~50 + ~1035 tests | no |
| Dialog "OnPath-Sicherung gefunden" (Neu anfangen / Wiederherstellen) on a fresh install with a backup | app start | nach der Tour | C11 | BackupScreens.kt RestoreOfferDialog ~15 | no |
| Dialog "Touren und Pläne werden wiederhergestellt" (progress) + result line "n Touren und m Pläne wiederhergestellt · k schon auf diesem Handy" | Settings → Sicherung (3) | nach der Tour | C11 | BackupScreens.kt ~30, BackupRestore ~61 | no |
| Places file (favourites, Zuhause) in the backup and in sync | automatic | Vorbereitung | F-PLAN-14 owner 2026-11-15 | PlacesBackup.kt ~50, PlaceSync ~103 | yes (keptplaces.ts) |

## Maps & downloads (Karten laden)

| Name as shown | Where / how (taps from start) | For | Origin | Size (code + tests, rough) | In browser planner? |
|---|---|---|---|---|---|
| Card "Noch keine Karte – Lädt, sobald du online bist" (first start without map) | start screen | Vorbereitung | docs §7 "download your first region"; owner 2026-11 | FirstMapCard.kt ~99 + FirstMapDownloadE2E ~167 | no |
| Map notice at the top (box/pill): "Detailkarte für deinen Standort?" (Standort erlauben), "Warte auf GPS für die Detailkarte", "Keine Detailkarte – lädt, sobald du online bist", "Lade 2 von 5 · 30 MB", "Karte für dieses Gebiet wird vorbereitet – etwa 3 min", "n Kartengebiete nicht geladen", "Detailkarte bereit zum Laden" (Nur WLAN / Jetzt laden) | automatic | alle | F-OFF-01/03; R-02; owner 2026-11 (map-updates-ux.md) | CellCards.kt (state) ~281, BackgroundDownloads.kt mapNotice* ~60, CellCard.kt ~82 | partly (mapnotice.ts ~77) |
| Box "Detailkarte für hier? · 45 MB" (Laden / Nicht jetzt) when tapping a spot with no OnPath map | tap on world map | Vorbereitung | owner 2026-11 (map-cells.md) | BackgroundDownloads.kt hereAskMessage ~12, MapDataFlow ~989 | partly |
| Dialog "Karte für dieses Gebiet laden?" (Laden / Nicht jetzt) | before a large download | Vorbereitung | F-OFF-03 ("size before download") | MapLoad.kt LoadAskDialog ~15 | no |
| Load window "Karte für deine Gegend wird geladen" (steps vorbereiten · laden · einfügen, time estimate, "Jetzt über mobile Daten laden", failure "konnte nicht geladen werden" + retry) | automatic / after a hold with no map | Vorbereitung | owner 2026-11-19 (wait window form A); R-02 | MapLoad.kt ~275, MapWaits ~67, WaitWindow ~169, MapWaitRules ~189 + map group ~2270 | yes (loadoverlay.ts ~421) |
| Chip "Karte" under ☰ (background download, after 10 s) → sheet "Laden im Hintergrund" with "Alle abbrechen" | automatic | alle | owner 2026-11-03 (comparison card), F-OFF-02/03 | BackgroundDownloads.kt ~239 | no |
| Automatic corridor download around every saved/imported/started route; routing parts load with each map area | automatic | Vorbereitung | F-OFF-02; C10, C13 | MapDataFlow.kt ~989, cells/AppCells ~45, MapLoadService ~172 + NoStaleDownloadsE2E ~69 | partly (areas.ts, cells.ts) |
| Monthly map update + "Karten jetzt aktualisieren" (only changed files, "Neuere Karte wird geladen…") | automatic / Settings | Vorbereitung | F-OFF-04, F-OFF-07 (owner 2026-11-17) | core cells (not measured), OfflineMapsSection | no |
| Screen "Offline-Kartendaten fehlen" (found/missing list, "Kartendaten (.zip) von diesem Handy importieren", "Wege … laden (≈ 10 MB)", "Befehl kopieren" for adb, "Erneut prüfen", "Ohne Kartendaten weiter") | when map data is missing | Vorbereitung | R-01/R-16; C6 | Screens.kt MissingDataScreen/DataActions ~110 | no |
| Map-package import from file (.zip/.rd5/.pmtiles) with result lines | Developer tools / missing-data screen | Vorbereitung | owner (offline import) | ImportExport.kt ~100 | no |
| Notification channel "Karten laden" ("Wird vorbereitet") for downloads | background | alle | F-OFF-03 | MapLoadService | no |

## Tour suggestions (Rundtouren vorschlagen)

Whole feature: owner (docs/design/tour-suggestions.md; on by default since 2026-11-18, "Tourvorschläge" off-switch in Developer tools; owner 2026-11-13 "Rundtour von hier" flow). No requirement ID found.

| Name as shown | Where / how (taps from start) | For | Origin | Size (code + tests, rough) | In browser planner? |
|---|---|---|---|---|---|
| Entry "Rundtouren vorschlagen" in hold menus (empty map, POI, found place) | hold + 1 tap | Vorbereitung | owner 2026-11-26/27 (PinMenuItems LOOP group) | MapOverlays.kt ~15, AppSuggestHost ~54 | yes (suggest.ts ~399) |
| Wish sheet: "Aktivität", "Zeit" (h range), "Höhenmeter" (bis/ab/egal), "Ziel" (Gipfel · Wasser · Aussicht · Einkehr · Kultur) | after entry | Vorbereitung | owner 2026-11-13..19 | SuggestSheet.kt ~525, SuggestSlice ~287 + 6 test files ~1020 | yes |
| Wait window "Rundtouren werden gesucht" (Wege lesen · Runden suchen · bewerten, Abbrechen) | after Suchen | Vorbereitung | owner 2026-11-19 | progress/SuggestWait.kt ~129 | yes (waitengine.ts) |
| Result cards: name line ("durch …", "vorbei an …"), badge "Ausgeschildert · <Name>" or "Vorschlag", pills ("grün", "ruhig", "viele Highlights", "kreuzt sich" …), axes Natur/Ruhe/Weg/Besonderes/Form, "Ganzen Text zeigen" | in sheet | Vorbereitung | owner 2026-11-15/17 ("official loops over own comments", Traumpfade) | SuggestSheet.kt (shared), core suggest (not measured) | yes |
| "Übernehmen" (makes a plan), "Wunsch ändern" | sheet | Vorbereitung | owner 2026-11-19 | SuggestSheet.kt, AppSuggest ~77 | yes |
| Notes when nothing fits ("Von hier ließ sich keine passende Runde finden.", "Die Karte wird hier gerade aktualisiert …", "Unter … Höhenmetern gibt es … keine Runde") and "Gerade keine Vorschläge möglich" | sheet | Vorbereitung | owner 2026-11-19 | SuggestSheet.kt ~40 | yes |
| Dialog while loading map data for the search ("Kartendaten werden geladen … 40 %", "Wegedaten werden geladen …") | sheet | Vorbereitung | owner 2026-11-19 | SuggestSheet.kt ~15 | yes |

## Background behaviour and notifications

| Name as shown | Where / how | For | Origin | Size (code + tests, rough) | In browser planner? |
|---|---|---|---|---|---|
| Recording service (foreground, location type): records a point every ≥1 s, writes each point to the database, survives screen off | starts with Los geht's / Nur aufzeichnen | unterwegs | F-NAV-03/04; R-07/R-09; C4 | recording/RecordingService.kt ~1774 (largest single file), LocationTracker ~297 + 7 test files ~730 | no |
| Persistent notification (channel "Aufnahme": distance + time; channel "Navigation": "Links in 300 m · Ankunft 16:35 · noch 12 km") with action "Anhalten"/"Weiter" | while a tour runs | unterwegs | F-NAV-04; owner 2026-11 (lock-screen cues) | RecordingService.kt notification ~250 | no |
| Notification question on off route: channel "Abseits der Route" with actions "Neue Route" / "Original behalten" / "Ohne Stopp" / "Hinführen" / "Hier einsteigen" | while a tour runs | unterwegs | owner 2026-10-31/11-01/11-03 | RecordingService.kt ~150, RecordingWire | no |
| Voice hints (offline TTS: "In 100 Metern links", "Ziel erreicht", "Abseits der Route. Bitte aufs Handy schauen.", "Wieder auf der Route.", "Neue Route.", "Kein GPS-Signal.", stops, direction of the goal) | Navigate with "Stimme" | unterwegs | F-NAV-05; docs/design/voice-guidance.md; owner 2026-11-07 (goal side) | Speaker.kt ~396, VoiceGuide.kt ~713 (core) + voice tests ~1940 | no |
| Vibration cues at junctions (1× links, 2× rechts) and off-route alarm (alarm usage: passes silent mode) | Navigate with "Vibration" | unterwegs | F-NAV-02/05; owner 2026-11-01 | Cues.kt ~50, JunctionCues (core) | no |
| Off-route detection (>40 m for 15 s while moving, configurable), re-route offline via next route point | automatic | unterwegs | F-NAV-02; C5 | core OffRouteDetector ~474 + RoutePrompt/NavPrompt (state) | no |
| Arrival detection (within ~200 m of the goal) → arrival box / voice "Ziel erreicht" | automatic | unterwegs | F-NAV-08 (multi-day); F-NAV-06; owner 2026-11-06 | NavigateScreen/TourEndSlice (shared), VoiceGuide | no |
| GPS rest: slower GPS while standing still, GPS off after an hour lying still, back at first move | automatic | unterwegs | owner 2026-11-24 (battery); R-10 (partly) | RestGps.kt ~52, StillSense ~115, core RestWatch ~115 + ~600 tests | no |
| Notification "Energiesparmodus stoppt GPS" (phone-specific) | when detected | unterwegs | R-09/R-10 | RecordingService.kt ~30 | no |
| Notification "Tour bekommt kein GPS" (watchdog restarted the tour, no fix for 5 min) | automatic | unterwegs | R-04/R-07 | RecordingService.kt ~40 | no |
| Notification "Tour unterbrochen" after phone reboot / app update ("öffne OnPath und tippe auf Weiter") | boot / package-replaced receiver | unterwegs | R-07; C4 | TourInterruptedReceiver.kt ~63 | no |
| Notification "Tour gerettet" (storage problem repaired) / "Tour kann nicht gespeichert werden" / "Tour gestoppt: …" reasons | automatic | unterwegs | R-07/R-11; C4 | RecordingService.kt ~80 | no |
| Box "OnPath wurde beim letzten Mal unerwartet beendet. Details: Einstellungen → Diagnose." | next start after a crash | alle | R-08 | MainActivity.kt/Problems ~30 | no |
| Problems log (every recovered error → debug/Problems; viewable in Diagnose, exported with the report) | automatic | alle | R-08; CLAUDE.md rule | debug/Problems ~231 | partly (diag.ts ~71) |
| Daily quiet update check (GitHub Releases, ≤1/day); box "OnPath x.y ist verfügbar" (Installieren / Später), progress card, resumes after a broken connection; during a tour a warning "Du bist unterwegs – Nach der Tour / Jetzt installieren" | automatic + ☰/Settings | alle | F-OFF-06; A-003; C8 | AppUpdater.kt ~216, AppUpdates ~96, UpdateUi ~100 + 3 tests ~254 | no |
| Motion sensor while recording (decides "still" for GPS rest, voice, off-route) | automatic | unterwegs | owner 2026-11-24; 2026-11-02 | StillSense.kt ~115 | no |
| Keep-screen-on / pocket proximity guard | Navigate | unterwegs | owner (battery-audit.md) | PocketScreen.kt ~128 | no |
| Automatic backup writes (see Sharing) and recycle-bin purge after 30 days at start | automatic | nach der Tour | C11 | Backups.kt ~329 | no |
| Plan sync pass and live-lane uploads in the background (retry when network returns) | automatic | Vorbereitung, unterwegs | A-007; owner 2026-11-20 | PlanSync.kt ~456, live/LiveLane ~184, HttpLiveBox ~92 | yes (sync.ts) |
| Map downloads in the background (foreground service type dataSync, notification channel "Karten laden") | automatic | Vorbereitung | F-OFF-02/03; R-02 | MapLoadService, MapDataFlow ~989 | no |
| Demo location (simulated GPS) | Developer tools | tester | owner (screenshots) | DemoLocation.kt ~236 | no |


## Counted

| Area | Rows |
|---|---|
| Plan | 70 |
| Search | 19 |
| Navigate / tour | 31 |
| My routes | 22 |
| Settings | 27 |
| Developer tools | 13 |
| Setup | 14 |
| Sharing & sync | 17 |
| Maps & downloads | 11 |
| Tour suggestions | 7 |
| Background behaviour and notifications | 21 |
| **Total** | **252** |

Whole-code scale for orientation (main code only, lines): app module ~42,400, core ~40,700, state ~12,800; tests ~95,000 lines in 617 files (incl. 31 androidTest files); browser planner web/app/src ~13,400 lines of TypeScript.

### Requirements in docs/requirements.md for which no matching function was found (not placed)
- F-POS-05 (long-press shows coordinates + elevation of a point, copy/share): a hold in Plan adds a point or opens a menu; the only coordinate copy found is Navigate's "Position kopieren".
- F-MAP-08 (route-network overlays switchable from a layer button: hiking / cycling / MTB): no layer button among the map controls (compass, Standort, Ganze Route, Auge, ⚙, Teilen); only Settings → Planen "Wanderrouten/Radrouten folgen" (Weniger / Standard / Mehr) was found.
- F-MAP-07 (dark / night style): only the "Kartenstil" row, shown when more than one theme exists; no night switch found.
- F-PLAN-04 (drag a via point on the map): MapHost.kt says "A longer press is just a hold (no dragging)"; moving is done by reordering in the Punkte list; insert-by-line is done with a hold.
- F-PLAN-09 (per-segment "straight line" option): not found as a user option; straight dashed legs appear only automatically where no route exists (R-12).
- R-10 (battery-saver mode with lower GPS rate, darker map): no own mode; found are GPS rest while standing still and the phone's "Energiesparmodus" warning step.
- F-OFF-05 "list of regions with size, date and delete button; total storage": present (Settings → Offline-Karten), listed above.

### Not examined in detail / uncertain
- AppViewModel.kt (~1900 lines) and the remaining files under ui/ (Theme.kt, MapDataFlow internals, MapWaits, DayTableView, MultiDay*) were read by their headers and string keys only; rows for them rest on strings_*.xml, comments and function lists, not a line-by-line read.
- Behaviour that exists only as a string without a UI path I confirmed (for example some msg_* texts) is counted inside the row of its feature, not as its own row.
- "Taps" for items behind permission dialogs, camera or the system share sheet are approximate (system steps not counted).
- The "In browser planner?" column is by file and parity manifest; I did not run the planner.
- Not listed separately on purpose: all multi-day parts (branch feat/multiday-one-day), the Play-store build variant (app/src/play), tools/ scripts and CI.
