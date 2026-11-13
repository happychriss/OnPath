# Picture list – every screen of OnPath as flows (website, help, store, film, other uses)

**One list for every use** (owner 2026-11-07): the pictures are sorted into **flows** – short stories in Meran, step
by step, the way a person uses OnPath. Taken in order, each flow is easy to reproduce and easy to understand; the same
pictures serve the website, the help, Google Play, the film and anything later. Steps the website does not use yet are
in the list on purpose (marked "–" under "Used on"): they are important moments of the app.

**File names say what the picture shows:** `<flow><step>-<what you see>-de.png` / `-en.png`, e.g.
`D05-handy-route-fertig-los-gehts-de.png`. The letter + number keep the order; never reuse a name for another content.
"Key" is the short name the website uses (`help_shots.py` in `happychriss/onpath.earth` maps key → file).

**After a UI change** only the steps of the changed screens are taken again – see "Refresh after an app change" below
(the "Snapshot (Paparazzi)" column finds them, the right-hand column is the fallback). New PNGs go to
`onpath.private/marketing/bilder/aktuell/`, one line each in its `liste.md` (file · app / planner version · date).
The whole picture flow for every helper (also without git): `onpath.private/marketing/bilder/ABLAUF.md`.

## Refresh after an app change

1. `tools/dev/pictures_affected.sh v0.17.5` (the last release whose pictures are on the site; or `A..B`) – prints the
   affected steps with their two files, DE + EN. A step counts when one of its snapshots in
   `app/src/test/snapshots/images/` changed (column "Snapshot (Paparazzi)": a bare name like `navigatePausedLocked`
   matches that test in every class – German, English, large font; `Class_method` only that class), or, for a step
   with "–" there, when a file of its last column changed (bare names like `main.ts` = planner files under `web/`).
   B, C01, C02, C06 are planner pictures: the laptop takes them (`web/app/test/flows-shots.mjs`), not donald.
2. Retake only those, in the Meran scenario, with `tools/dev/website_shot.sh` (start → setup de → plan → … shot →
   setup en → … → stop).
3. Check each picture by its screen text, not by looking: `adb -s emulator-5600 shell uiautomator dump` and grep the
   words the step shows ("Angehalten seit", "Planen" …) before `shot`.
4. Copy the PNGs into `onpath.private/marketing/bilder/aktuell/` (same names), one line per file in `liste.md`, commit
   "Erneuert nach 0.17.6: E01, E04, E06 (DE + EN)".
5. `tools/dev/website_publish.sh <onpath.earth checkout> <png…>` – WebP with the site's own names and sizes
   (help pictures through the site's `scripts/help_shots.py`, home pictures in `assets/app/` replaced in place); then
   in onpath.earth `python3 build.py`, `python3 scripts/check_site.py`, `node --check assets/app.js`, commit, push.
   The page texts need no change.

Keep the column right: a new snapshot test for a screen of a step → add its name; `tools/dev/pictures_affected.sh
--check` lists names that match no PNG any more (a renamed or deleted test).

## The Meran scenario (the same for every flow)

- **App:** emulator AVD `OnPath_Play36` (1080 × 2400, density 356, Android 16), `tools/dev/website_shot.sh`, status bar
  demo mode (09:30, Wi-Fi, full battery), DE then EN. Map areas of Meran, Algund and Vellau on the phone. Flight
  mode on for everything "on the way" (✈ visible). No DEMO mark, no Sim chip, never the owner's real position.
- **Planner:** 1440 × 900 (film 2560 × 1440), `web/app/test/flows-shots.mjs` (pairing: `pair-shots.mjs`), DE then EN; pairing codes blurred.
- **The hike (Wandern T2), fixed (owner 2026-11-07): Vellau (Korblift-Talstation) → Leiteralm**, above Algund / Meran –
  the A-019 plan link (`onpath.private/marketing/bilder/a019/Ergebnis.md`, "Plan-Link"); maps already on the emulator.
  Check first that 0.17 routes it well (A-019 found a detour east and over the Gamplweg instead of the way along the
  Korblift) – if not, say so in `liste.md` before taking pictures.
- **The inn on the way:** Gasthof Hochmuth on the Meraner Höhenweg, found offline and added "Als Zwischenstopp" (F).
- **The lift (H03):** the Korblift / chairlift Algund → Vellau before the start, so the ride check shows a real ride.
- **The bike route (E-Bike):** Meran → Etschradweg → Algund / Naturns.
- **Plausible numbers** (no 19-minute tour with 710 hm). Every picture DE + EN.

## A – First start and setup (app)

| Step | File | What you see | Key · used on | Snapshot (Paparazzi) | Change here → new picture |
| --- | --- | --- | --- | --- | --- |
| A01 | `A01-start-bildschirm` | start screen: paper, icon, wordmark | – | `splash` | start screen / theme |
| A02 | `A02-handy-einrichten` | Einstellungen → Handy einrichten: Standort, Vibration, Akku with what is missing | handy-einrichten · help akku-sicherung | `settingsPage` `settingsPageLower` | `ui/setup/SetupGuideScreen.kt` |
| A03 | `A03-sprachansagen-testen` | setup step voice: "Stimme testen" | – (voice help later) | `testStayedSilent` `onlineVoiceOnly` | `ui/setup/SetupGuideScreen.kt` |
| A04 | `A04-offline-karten` | Einstellungen → Offline-Karten: Meran area ready | – (help offline) | `settingsOfflineMaps` | `ui/SettingsScreen.kt` (Offline-Karten) |

## B – At home: plan in the browser (planner)

| Step | File | What you see | Key · used on | Snapshot (Paparazzi) | Planner file |
| --- | --- | --- | --- | --- | --- |
| B01 | `B01-web-start-leer` | plan.onpath.earth opened, map of Meran, empty plan | – | – | `main.ts`, `style.css` |
| B02 | `B02-web-route-fertig` | the hike: route, length · ↑ · ↓, points, profile along the bottom | web-plan (help), web-wandern (home) · home, route-planen, film 1 | – | `main.ts`, `sheetstep.ts`, `profile.ts` |
| B03 | `B03-web-grosses-profil` | "⤢ Großes Profil": big profile, steepness colours, cursor linked to the map | web-profil · route-planen | – | `profile.ts`, `terrainview.ts` |
| B04 | `B04-web-3d-gelaende` | the route in 3D over the terrain, "▶ Abfliegen" beside it | web-3d · home, route-planen, store S6, film 1 | – | `terrainview.ts` |
| B05 | `B05-web-3d-abfliegen` | during the flyover: camera along the route, Pause / Stopp, 1× / 2× | – (film 1) | – | `flyover.ts` |
| B06 | `B06-web-teilen-qr` | "Teilen" with the plan's QR code | web-teilen (help), web-qr (home) · home, route-planen | – | `share.ts` |
| B07 | `B07-web-meine-touren` | Meine Touren with route pictures, "In Bearbeitung" (no large overview in the planner, owner 2026-11-13) | web-touren · route-planen | – | `plans.ts` |
| B08 | `B08-web-3d-route-ueber-meran` | 3D showcase: a hike route above Algund, Meran, Dorf Tirol and Schenna behind (owner's example) | – · home hero / store S6 candidate, film 1 | – | `terrainview.ts` |
| B09 | `B09-web-3d-partschins-grosses-profil` | 3D with the big profile and its steepness colours | – · route-planen candidate | – | `terrainview.ts`, `profile.ts` |
| B10 | `B10-web-3d-abfliegen-partschins` | during the flyover: Pause · Stopp · 1×, the dot on the route and in the profile | – · film 1 | – | `flyover.ts` |

## C – Pair browser and phone (planner + app)

| Step | File | What you see | Key · used on | Snapshot (Paparazzi) | File |
| --- | --- | --- | --- | --- | --- |
| C01 | `C01-web-handy-fenster` | the Phone window, not paired, "Wie funktioniert das?" open | web-handy · route-planen (koppeln) | – | `plans.ts` (`phoneDialog`) |
| C02 | `C02-web-koppel-code` | "Handy verbinden": QR (blurred), the way in the app, check digits | web-koppeln · route-planen (koppeln) | – | `plans.ts` (`pairDialog`) |
| C03 | `C03-handy-menue-koppeln` | app ☰ open: "Mit Browser koppeln" | app-koppeln · route-planen (koppeln) | `planMenuOpen` | `ui/PlanTopBar.kt` |
| C04 | `C04-handy-pruefziffern` | "Mit diesem Browser verbinden? Prüfe: …" · Verbinden | – | `settingsSyncAsk` | `ui/SyncSettings.kt` |
| C05 | `C05-handy-verbunden` | paired: what you save in the browser comes to the phone | app-verbunden · route-planen (koppeln) | `planPairedNote` | `ui/SyncSettings.kt`, plan note |
| C06 | `C06-web-aufs-handy-sichern` | browser paired: phone name + dot, "Aufs Handy sichern" → "✓ Auf dem Handy" | – | – | `main.ts` (header), `plans.ts` |
| C07 | `C07-handy-plan-aus-browser` | Meine Touren with the plan from the browser, "Neu" (top part) | app-routen · route-planen (koppeln) | `toursFromBrowser` | `ui/Screens.kt`, `ui/RouteTag.kt` |
| C08 | `C08-handy-trennen` | Einstellungen → Browser-Abgleich paired, "Trennen" (top part) | app-trennen · route-planen (koppeln) | `settingsSyncPaired` | `ui/SyncSettings.kt` |

C04, C05, C07, C08 may stay Paparazzi snapshots (`GermanScreenshotTest_settingsSyncAsk`, `_planPairedNote`,
`_toursFromBrowser`, `_settingsSyncPaired`; English `ScreenshotTest_…`) – they need no real browser.

## D – Plan the hike on the phone (app)

| Step | File | What you see | Key · used on | Snapshot (Paparazzi) | Change here → new picture |
| --- | --- | --- | --- | --- | --- |
| D01 | `D01-handy-leerer-plan` | empty start sheet "Ziel finden › · Route anpassen · Los geht's", map of Meran | leer · erste-schritte | `planEmpty` | `ui/PlanPanel.kt`, `ui/MapScreen.kt` |
| D02 | `D02-handy-aktivitaet-waehlen` | activity button open: activities, T1–T5 under Wandern | aktivitaet · route-planen | `pickerHikeT2` | activity sheet, `ui/PlanPanel.kt` |
| D03 | `D03-handy-karte-halten-hierhin` | map held: "Hier starten" · "Hierhin" | halten · erste-schritte, home (step 1) | `firstPointMenu` | `ui/MapOverlays.kt` |
| D04 | `D04-handy-start-gesetzt` | start set, waiting for the goal | punkt1 · route-planen | `planOnePoint` | `ui/PlanPanel.kt`, `ui/MapHost.kt` |
| D05 | `D05-handy-route-fertig-los-gehts` | route ready: "PLANEN · Ohne Netz ✓", length · ↑ · ↓, profile, big green "Los geht's" | fertig · erste-schritte, home, store S1 | `plan` | `ui/PlanPanel.kt`, `ui/ElevationProfile.kt` |
| D06 | `D06-handy-plan-wandern-t2` | the whole hike on the map, steep parts marked (hero picture) | plan (help), plan-wandern (home) · home hero, route-planen, store S5 | `planWholeRoute` | `ui/PlanPanel.kt`, `ui/RouteWarnings.kt`, map style |
| D07 | `D07-handy-hinweise-steil` | the route notes open: where it gets steep | hinweise · route-planen | `routeSpotsList` `routeSpotsListHints` | `ui/RouteWarnings.kt` |
| D08 | `D08-handy-t-band-t3` | grey band with red "T3" beside the T2 route | t-band · route-planen | – | `map/ActivityLayers.kt`, map style |
| D09 | `D09-handy-ort-halten-als-zwischenstopp` | a place held near the route: "Als Zwischenstopp" / "Als neues Ziel" · Mehr | ziel · erste-schritte, route-planen | `poiMenu` | `ui/MapOverlays.kt` |
| D10 | `D10-handy-erreichbar-ab-t2` | a stop the route does not reach: "② erst ab T2 erreichbar" | erreichbar · route-planen | `planReachHike` | `ui/PlanPanel.kt` |
| D11 | `D11-handy-punkteliste` | plan sheet fully up: profile + all points | punkteliste · route-planen | `planPointsFull` | `ui/PlanPanel.kt`, `ui/PointsSection.kt` |
| D12 | `D12-handy-ohne-netz-erklaert` | a tap on "Ohne Netz ✓": what is on the phone | ohne-netz · offline | `planOfflineReady` | `ui/PlanTopBar.kt` |
| D13 | `D13-handy-rundtour-umkehren` | the light blue row: ✕ Neu planen · ⇅ Umkehren · ↻ Rundtour | – | `planRoundTripOn` | `ui/PlanPanel.kt` |

## E – On the way: navigate (app, flight mode)

| Step | File | What you see | Key · used on | Snapshot (Paparazzi) | Change here → new picture |
| --- | --- | --- | --- | --- | --- |
| E01 | `E01-handy-navigation-gesperrt` | Navigate: turn card, status card, bar **Anhalten \| Planen** (locked, pale = hold) | navi · erste-schritte, home (step 3), navigation | `navigate` `navigateTurn` | `ui/NavigateScreen.kt`, `ui/TourActionBar.kt` |
| E02 | `E02-handy-gedrueckt-halten` | a button filling while held, "Gedrückt halten" | entsperren · navigation | `navigateLockedHint` | `ui/TourActionBar.kt`, `domain/tour/UnlockWindow.kt` |
| E03 | `E03-handy-abzweigung-ankunft` | next turn close up, time left, "Ankunft 13:55" with the goal | ankunft · home, navigation, store S3 | `navigateTurnOnto` `navigateArrival` | `ui/NavigateScreen.kt` |
| E04 | `E04-handy-statuskarte-offen` | status card opened: moving time, profile, next stop "an 14:20" | – | `navigateProfileStops` `navigateProfile` `navigateNextStop` | `ui/NavigateScreen.kt` |
| E05 | `E05-sperrbildschirm` | lock screen: "Links in 300 m · Ankunft 13:00" | – (help navigation) | – | notification (`recording/`) |
| E06 | `E06-handy-pause` | stopped (0.17.5+): card head "⏸ Angehalten seit 12:04", ▶ Weiter · ■ Tour beenden · ✎ Planen | pause · navigation | `navigatePausedLocked` `navigatePausedOpen` | `ui/TourActionBar.kt` |
| E07 | `E07-einstellungen-hinweise-stimme` | Einstellungen → Navigation: "Hinweise an Abzweigungen" Aus · Vibration · Stimme, "Stimme testen" | hinweise-stimme · navigation, store S3 | `settingsNavigation` | `ui/SettingsScreen.kt` (Navigation) |

## F – On the way: a new idea – stop at the inn (app, flight mode)

| Step | File | What you see | Key · used on | Snapshot (Paparazzi) | Change here → new picture |
| --- | --- | --- | --- | --- | --- |
| F01 | `F01-handy-planen-waehrend-tour` | after holding Planen: plan with the light blue band, recording on | – | `planTourRunning` `planTourRolledUp` | `ui/PlanPanel.kt` (during a tour) |
| F02 | `F02-handy-suche-einkehr-offline` | search for the inn, ✈, results from the phone | suche · unterwegs-aendern, orte | `searchResults` `searchEmptyOffline` | `ui/search/SearchScreen.kt` |
| F03 | `F03-handy-pin-karte-einkehr` | the inn found: pin card "+ Zur Route" · Mehr (→ "In Google Maps öffnen") | ort (help), ort-google-maps (home) · home, unterwegs-aendern, orte | `foundPlacePin` `placeCard` `candidateDe` `candidateEn` | `ui/MapOverlays.kt`, `map/CandidateMarker.kt` |
| F04 | `F04-handy-neue-route-mit-einkehr` | the new route with the inn as stop, ✈, recording on | neue-route · unterwegs-aendern, offline, store S2 | `planTourNewPlan` | `ui/PlanPanel.kt` |
| F05 | `F05-handy-weiter-gehts` | "Weiter geht's" – back in Navigate, new arrival | zurueck · unterwegs-aendern | `navigateNewPlan` | `ui/NavigateScreen.kt` |

## G – Off the route (app)

| Step | File | What you see | Key · used on | Snapshot (Paparazzi) | Change here → new picture |
| --- | --- | --- | --- | --- | --- |
| G01 | `G01-handy-abseits-der-route` | amber card "Abseits der Route – Rückweg wird gesucht…" | abseits · unterwegs-aendern | `navigateOffRouteSearching` | `ui/NavigateScreen.kt` |
| G02 | `G02-handy-neue-route-frage` | the question: "Neue Route" \| "Original behalten" | abseits-frage · unterwegs-aendern | `navigateRerouteQuestion` | `ui/NavigateScreen.kt` |
| G03 | `G03-handy-alte-route-grau` | after "Neue Route": the original as thin grey line – **not possible in 0.17.3:** no grey original line is drawn (owner: the route stays blue) – take it if that comes | – | – | `ui/MapScreen.kt` |
| G04 | `G04-handy-stopp-auslassen` | "Über ③" or "③ auslassen" – **not seen in 0.17.3:** with the inn as stop the question offered only "Original behalten \| Neue Route" – suspected bug, donald | – | `navigateRerouteSkipStop` `navigateRerouteSkipStopNamed` | `ui/NavigateScreen.kt` |

## H – Arrive, end and keep the tour (app)

| Step | File | What you see | Key · used on | Snapshot (Paparazzi) | Change here → new picture |
| --- | --- | --- | --- | --- | --- |
| H01 | `H01-handy-angekommen` | "Du bist angekommen." · Weiter aufzeichnen \| Tour beenden | angekommen · navigation, film 5 | `arrival` | `ui/TourBar.kt` |
| H02 | `H02-handy-tour-beenden` | "Tour beenden?": numbers, green "Tour speichern", Weiter \| Löschen | beenden · navigation | `endTourConfirm` | `ui/TourBar.kt` |
| H03 | `H03-handy-liftfahrt-erkannt` | the ride check after the cable car: what should count | fahrt · home, navigation | `endTourWithRides` `endTourLiftOnly` | `ui/RideSummary.kt` |
| H04 | `H04-handy-gemacht-gerade-gespeichert` | Meine Touren → Gemacht, today's tour "✓ Gerade gespeichert" | gemacht · gpx | `toursDone` `savedTourBox` | `ui/Screens.kt` |

## I – Meine Touren and GPX (app)

| Step | File | What you see | Key · used on | Snapshot (Paparazzi) | Change here → new picture |
| --- | --- | --- | --- | --- | --- |
| I01 | `I01-handy-meine-touren-geplant` | Geplant: Zuletzt / Gespeichert, route pictures, "In Bearbeitung" | meine-touren · gpx, store S4 | `tours` `toursRecent` | `ui/Screens.kt`, `ui/RoutesTab.kt`, `ui/RoutePreview.kt` |
| I02 | `I02-handy-uebersicht-los-gehts` | a route's large overview: map picture, "▶ Los geht's", Abbrechen \| Planen | uebersicht · gpx, film 2 | `routesOverview` | `ui/Screens.kt` |
| I03 | `I03-handy-touren-menue` | ⋮ on a row: Planen, Los geht's, Umbenennen, Als Kopie speichern, GPX exportieren, Plan teilen, Löschen | – | `routesItemMenuPlanned` | `ui/Screens.kt` |
| I04 | `I04-handy-gpx-als-plan` | an imported GPX track opened: "Als Plan übernehmen" | gpx-plan · gpx | `track` | `ui/Panels.kt` |
| I05 | `I05-handy-papierkorb` | Papierkorb with a deleted tour (30 days) | – (help akku-sicherung) | `recycleBin` | `ui/Screens.kt` |

## J – A place from Google Maps (app)

| Step | File | What you see | Key · used on | Snapshot (Paparazzi) | Change here → new picture |
| --- | --- | --- | --- | --- | --- |
| J01 | `J01-google-maps-teilen-onpath` | Google Maps: the inn → Teilen → OnPath in the share sheet (no private contacts) | – | – | – (Android) |
| J02 | `J02-handy-ort-aus-google` | the shared place in OnPath | g-uebernommen · orte | `sharedPlaceMenu` | `map/CandidateMarker.kt`, `ui/MapOverlays.kt` |
| J03 | `J03-handy-route-zum-ort` | after "Hierhin": the route there | g-route · orte | – | `ui/PlanPanel.kt` |

## K – By bike (app, E-Bike)

| Step | File | What you see | Key · used on | Snapshot (Paparazzi) | Change here → new picture |
| --- | --- | --- | --- | --- | --- |
| K01 | `K01-handy-plan-ebike` | the bike route by E-Bike: surface, push section | ebike (help), plan-ebike (home) · home, route-planen | `planSummaryBike` `planProfileBike` | `ui/PlanPanel.kt`, `ui/RouteWarnings.kt` |
| K02 | `K02-handy-ruheanzeige` | rest view on the handlebar: arrow, distance, arrival | ruhe (help), ruheanzeige (home) · home, navigation | `RestViewScreenshotGermanTest_turn` | `ui/RestView.kt`, `ui/SmartScreen.kt` |
| K03 | `K03-handy-rad-abzweigung` | the map back shortly before the turn | – | – | `ui/SmartScreen.kt` |

## L – Safe: restart and backup (app)

| Step | File | What you see | Key · used on | Snapshot (Paparazzi) | Change here → new picture |
| --- | --- | --- | --- | --- | --- |
| L01 | `L01-handy-neustart-hinweis` | after a restart: the recording is saved | neustart-hinweis · probleme | – | `ui/NavigateScreen.kt`, `ui/Screens.kt` |
| L02 | `L02-handy-unterbrochene-tour` | "Unterbrochene Tour": continue / delete / save | neustart · probleme | `unfinishedTour` | `ui/Screens.kt` |
| L03 | `L03-einstellungen-sicherung` | Einstellungen → Sicherung: the backup folder | sicherung · akku-sicherung | `settingsBackup` `SettingsScreenshotTest_backup` | `ui/SettingsScreen.kt` |
| L04 | `L04-einstellungen-datenschutz` | Einstellungen → Datenschutz: what leaves the phone | – | `settingsPrivacyFull` `SettingsScreenshotTest_privacy` | `ui/SettingsScreen.kt` |

## Store (Google Play) – made from the flows, no extra shots

S1 "Plane in Ruhe – auch ohne Netz" → D05 · S2 "Unterwegs umplanen – ganz einfach" → F04 · S3 "Klare Ansagen an jeder
Abzweigung" → E03 (+ E07) · S4 "Deine Daten bleiben bei dir" → I01 · S5 "Ohne Werbung. Ohne Konto. Ohne Tracking." → D06 ·
S6 "Am PC planen, auf dem Handy losgehen" → B04 + D05. (Proposal from the privacy check, owner to decide: S4 / S5 say a
little more than the code does – e.g. "Deine Touren auf deinem Handy" and "Ohne Werbung. Ohne Konto. Ohne Analyse.")

## Film (about 48 s) – recordings along the flows

Screen recordings (MP4; app 1080 × 2400, planner 2560 × 1440) – not into a repo (R2 / donald), only their place in
`liste.md`. One still per scene comes from the flow pictures.

| Scene | Recording | Flow steps |
| --- | --- | --- |
| 1 At home | `film-1-browser-planen`: plan link opens, route draws itself, 3D + Abfliegen 8–10 s | B02, B04, B05 |
| 2 The same plan on the phone | `film-2-handy-los-gehts`: Meine Touren → overview → "▶ Los geht's" | I01, I02 |
| 3 On the way | `film-3-unterwegs`: Navigate locked, a turn is said (voice on) | E01, E03 |
| 4 A new idea | `film-4-einkehr`: hold Planen → search offline → pin card → Als Zwischenstopp → new route → Weiter geht's | F01–F05 |
| 5 Arrive | `film-5-ankommen`: "Du bist angekommen." → Tour beenden → Tour speichern | H01, H02, H04 |

## Old names (until the website is switched over)

`meine-routen` → I01 (`meine-touren`) · `gespeichert` → H04 (`gemacht`) · `tourmenue` → gone (slider) · `app-pruefen` →
C04 · `app-neu` → C07. Older sets stay in `onpath.private/marketing/bilder/` as history (`a019*` Vellau 0.13,
`2026-11-01-donald`, `2026-11-03-donald`, `2026-11-02-hp`, `2026-11-03-hp`).

Texts the planner shows (time, distance, notes, points …) come from the app's shared strings – a change in
`strings/…/strings_plan.xml` that the planner shows also means new B pictures.
