# Website screens – every picture on onpath.earth, in the store and in the film, and which screen it shows

Owner 2026-11-01: after a UI change only the pictures of the changed screens are taken again, not whole sets. This list
says which picture shows which screen: a role that changes a file in the right-hand column takes the pictures of that
row again (`tools/dev/website_shot.sh`, below) and drops the PNGs into `onpath.private/marketing/bilder/aktuell/` with
one line each in its `liste.md` (key · app / planner version · date). cloud-web turns them into the site's WebP files
(`scripts/help_shots.py` in `happychriss/onpath.earth`; the keys are the names there).

**Round "Meran 0.17" (owner 2026-11-07):** every picture new from app 0.17.3 and today's planner, **all in Meran**
(scenario below). Rows marked **new** are not on the site yet; cloud-web builds them in when they arrive. Rows marked
**gone** show a screen that no longer exists – don't take them. Old sets (Vellau / Partschins, 0.13–0.16) stay in
`bilder/` as history.

## The Meran scenario (same for app, planner, store and film)

- **Emulator** as before: AVD `OnPath_Play36` (1080 × 2400, density 356, Android 16), status bar demo mode (09:30,
  Wi-Fi, full battery), app language DE then EN. Map areas around Meran on the phone (Meran town, Dorf Tirol, Algund).
  Flight mode on for every "on the way" picture (the ✈ shows it). No DEMO mark, no Sim chip, never the owner's position.
- **Hike (Wandern T2), the main plan:** start in Meran town, about 2–3 h, one or two steep sections (so the notes and the
  profile have something to show), a hut or inn on the way that can be added as a stop. Suggestion: Meran (Pulverturm /
  Tappeinerweg) → Dorf Tirol → up towards Schloss Tirol / Hochmuth. The unit picks what the offline map routes well, and
  writes the **plan link** into `bilder/aktuell/liste.md` – every later picture and the planner use that one link.
- **The new idea on the way:** an inn or café near the route, found with the search offline, added "Als Zwischenstopp".
- **Bike (E-Bike / Fahrrad):** Meran → Etschradweg → Algund / Naturns, for the bike pictures, the rest view and the voice.
- **T band:** a plan whose area has trails above T2 nearby (Meraner Höhenweg / Ifinger side), so the grey band with its
  red "T3" label shows.
- **Shared from Google Maps:** a place in Meran (an inn of the hike), Google Maps → Teilen → OnPath.

## App pictures (donald, `tools/dev/website_shot.sh`)

Each key DE + EN: `<key>-de.png` · `<key>-en.png`. "Where" = help page (`hilfe/<slug>`) or home (`/`) or store (S1–S6).

| Key | What it shows (0.17.3) | Where | App screen (change here → new picture) |
| --- | --- | --- | --- |
| leer | empty start sheet: "Ziel finden › · Route anpassen · Los geht's", "Meine Touren öffnen", map of Meran | erste-schritte | `ui/PlanPanel.kt`, `ui/MapScreen.kt` |
| halten | hold the map: first-point menu | erste-schritte | `ui/MapOverlays.kt` |
| punkt1 | start set, waiting for the goal | route-planen | `ui/PlanPanel.kt`, `ui/MapHost.kt` |
| ziel | hold a place → "Als Zwischenstopp" / "Als neues Ziel" · Mehr | erste-schritte, route-planen | `ui/MapOverlays.kt` (POI / point menu) |
| fertig | route ready: PLANEN · Ohne Netz ✓, length · ↑ · ↓, profile, big green "Los geht's" | erste-schritte, home, S1 | `ui/PlanPanel.kt`, `ui/ElevationProfile.kt` |
| punkteliste | plan sheet fully up: profile + all points | route-planen | `ui/PlanPanel.kt`, `ui/PointsSection.kt` |
| plan | the hike, Wandern T2: map, steep parts, profile (hero picture of the home page) | home (hero, Wandern), route-planen | `ui/PlanPanel.kt`, `ui/RouteWarnings.kt`, map style |
| ebike | the bike route by E-Bike (surface, push) | home (E-Bike), route-planen | as `plan` |
| hinweise | the route notes open (steep sections) | route-planen | `ui/RouteWarnings.kt`, `ui/PlanPanel.kt` |
| aktivitaet **new** | the activity button opened: activities + T1–T5 row | route-planen | activity sheet (`ui/…Activity…`), `ui/PlanPanel.kt` |
| t-band **new** | Wandern T2 with the grey band "T3" beside the route | route-planen | `map/ActivityLayers.kt`, map style |
| erreichbar **new** | a stop the route does not reach: "② erst ab T2 erreichbar" | route-planen | `ui/PlanPanel.kt` (reach line) |
| ohne-netz **new** | a tap on "Ohne Netz ✓": what is on the phone | offline | `ui/PlanTopBar.kt` / the PLANEN pill |
| navi | Navigate, locked: turn card, status card, bar **Pause \| Planen** | erste-schritte, navigation | `ui/NavigateScreen.kt`, `ui/TourActionBar.kt` |
| entsperren **new** | a button filling while held ("Gedrückt halten") | navigation | `ui/TourActionBar.kt`, `domain/tour/UnlockWindow.kt` |
| pause **new** | paused: band "⏸ Pause · 12:04", Weiter · Beenden · ✎ | navigation | `ui/TourActionBar.kt`, `ui/NavigateScreen.kt` |
| ankunft | Navigate: next turn, time left, "Ankunft 13:55" | home, navigation, S3 | `ui/NavigateScreen.kt` |
| ruhe | rest view on the bike | home, navigation | `ui/RestView.kt`, `ui/SmartScreen.kt` |
| abseits **new** | amber card "Abseits der Route", then the question Neue Route \| Original behalten (two pictures: `abseits`, `abseits-frage`) | navigation | `ui/NavigateScreen.kt` (reroute card) |
| tourmenue **gone** | (the slider's tour menu – replaced by `pause`) | – | – |
| suche | during the tour: Planen → search the inn offline | unterwegs-aendern, orte | `ui/search/SearchScreen.kt` |
| ort | the found inn: pin card "+ Zur Route" / Hinzufügen · Mehr (→ "In Google Maps öffnen") | unterwegs-aendern, orte, home | `ui/MapOverlays.kt`, `map/CandidateMarker.kt` |
| neue-route | the new route with the inn as stop, recording on, ✈ | unterwegs-aendern, offline, S2 | `ui/PlanPanel.kt` (plan during a tour) |
| zurueck | "Weiter geht's" – back in Navigate, new arrival | unterwegs-aendern | `ui/NavigateScreen.kt` |
| g-uebernommen | a place shared from Google Maps in OnPath | orte | `map/CandidateMarker.kt`, `ui/MapOverlays.kt` |
| g-route | after "Hierhin": the route there | orte | `ui/PlanPanel.kt` |
| angekommen **new** | arrival box: "Du bist angekommen." · Weiter aufzeichnen \| Tour beenden | navigation | `ui/TourBar.kt` |
| beenden | "Tour beenden?": numbers, green "Tour speichern", Weiter \| Löschen | navigation | `ui/TourBar.kt` |
| fahrt | the ride check after a lift / bus | home, navigation | `ui/RideSummary.kt` |
| meine-touren (was meine-routen) | Meine Touren → Geplant: Zuletzt / Gespeichert, small route pictures, "In Bearbeitung" | gpx, home | `ui/Screens.kt`, `ui/RoutesTab.kt`, `ui/RoutePreview.kt` |
| uebersicht **new** | a route's large overview: map picture, "▶ Los geht's", Abbrechen \| Planen | gpx | `ui/Screens.kt` (overview) |
| gemacht (was gespeichert) | Meine Touren → Gemacht with today's tour marked ("✓ Gerade gespeichert") – plausible numbers, no 19-min 710 hm | gpx | `ui/Screens.kt` |
| gpx-plan **new** | an imported GPX track opened, "Als Plan übernehmen" | gpx | `ui/Panels.kt` |
| hinweise-stimme **new** | Einstellungen → Navigation: "Hinweise an Abzweigungen" Aus · Vibration · Stimme, "Stimme testen" | navigation | `ui/SettingsScreen.kt` (Navigation page) |
| handy-einrichten **new** | Einstellungen → Handy einrichten (battery etc.) | akku-sicherung | `ui/setup/SetupGuideScreen.kt` |
| sicherung **new** | Einstellungen → Sicherung (backup folder) | akku-sicherung | `ui/SettingsScreen.kt` (Sicherung) |
| neustart-hinweis | after a restart: the recording is saved | probleme | `ui/NavigateScreen.kt`, `ui/Screens.kt` |
| neustart | "Unterbrochene Tour": continue / delete / save | probleme | `ui/Screens.kt` |
| app-koppeln | ☰ open: "Mit Browser koppeln" | route-planen (koppeln) | `ui/PlanTopBar.kt` (☰ menu) |
| app-verbunden (was app-pruefen) | paired: what you save in the browser comes to the phone | route-planen (koppeln) | `ui/SyncSettings.kt`, plan note |
| app-routen (was app-neu) | Meine Touren with a plan from the browser ("Neu") – top part | route-planen (koppeln) | `ui/Screens.kt`, `ui/RouteTag.kt` |
| app-trennen | Einstellungen → Browser-Abgleich paired, "Trennen" – top part | route-planen (koppeln) | `ui/SyncSettings.kt` |

The pairing pictures may stay Paparazzi snapshots (`GermanScreenshotTest_planPairHint`, `_planPairedNote`,
`_toursFromBrowser`, `_settingsSyncPaired`, English `ScreenshotTest_…`) – they need no real browser.

## Browser planner (laptop / cloud-plan) – `web/app/test/pair-shots.mjs` on `main`

Same Meran plan link as the app. 1440 × 900, DE + EN (`<key>-de|en.png`); the pairing code blurred.

| Key | What it shows | Where | Planner file |
| --- | --- | --- | --- |
| web-plan (home: web-wandern) | the hike: route, length · ↑ · ↓, points, profile along the bottom | home, route-planen | `web/app/src/main.ts`, `sheetstep.ts`, `profile.ts`, `style.css` |
| web-3d **new** | the same route in **3D** (terrain tilted, steepness colours), "▶ Abfliegen" beside it | home, route-planen | `web/app/src/terrainview.ts`, `flyover.ts` |
| web-profil **new** | "⤢ Großes Profil": big profile linked to the map | route-planen | `web/app/src/profile.ts` |
| web-touren **new** | Meine Touren with route pictures, "In Bearbeitung", the large overview | route-planen | `web/app/src/plans.ts` |
| web-teilen (home: web-qr) | "Teilen" with the plan's QR code | home, route-planen | `web/app/src/share.ts` |
| web-handy | the Phone window, not paired, "Wie funktioniert das?" open | route-planen (koppeln) | `web/app/src/plans.ts` (`phoneDialog`) |
| web-koppeln (`web-w2`) | "Handy verbinden": QR (blurred), the way in the app, check digits | route-planen (koppeln) | `web/app/src/plans.ts` (`pairDialog`) |
| web-w3 · web-w4 · web-w6 | paired: "Aufs Handy sichern" → "✓ Auf dem Handy"; the Phone window with "Trennen" | route-planen (koppeln) | `main.ts` (header), `plans.ts` |

Texts the engine writes (time, distance, notes, points, "Neu planen" …) come from the app's shared strings – a change in
`strings/…/strings_plan.xml` that the planner shows also means new web pictures.

## Google Play (store) – S1–S6, the owner's headlines (backlog "For the roles")

S1 "Plane in Ruhe – auch ohne Netz" → `fertig` · S2 "Unterwegs umplanen – ganz einfach" → `neue-route` · S3 "Klare
Ansagen an jeder Abzweigung" → `ankunft` (+ `hinweise-stimme`) · S4 "Deine Daten bleiben bei dir" → `meine-touren` ·
S5 "Ohne Werbung. Ohne Konto. Ohne Tracking." → `plan` · S6 "Am PC planen, auf dem Handy losgehen" → `web-3d` + `fertig`.
cloud-web builds the store pictures from these keys; no extra shots.

## Film (about 48 s; story and old material: `onpath.private/marketing/video-aufnahmen-uebergabe.md`)

The same Meran story, as screen recordings (MP4, 1080 × 2400 app, 2560 × 1440 planner) plus a still per scene:

| Scene | Recording |
| --- | --- |
| 1 At home: plan in the browser | `web-film-plan`: the plan link opens, the route draws itself, then **3D + ▶ Abfliegen** for 8–10 s |
| 2 The same plan on the phone | `film-handy`: Meine Touren → the plan → overview → "▶ Los geht's" |
| 3 On the way | `film-unterwegs`: Navigate locked, a turn is said (voice on) |
| 4 A new idea | `film-umplanen`: hold Planen → search the inn offline (✈) → pin card → "Als Zwischenstopp" → the new route → "Weiter geht's" |
| 5 Arrive | `film-ankunft`: arrival box "Du bist angekommen." → Tour beenden → Tour speichern |

## Taking a picture (`tools/dev/website_shot.sh`, donald)

`website_shot.sh start` → `setup de|en` → `gps <lon> <lat>` (Meran, e.g. 11.1600 46.6700) → drive the app (adb input /
by hand) → `shot <file.png>` → `stop`. Older sets: `bilder/a019*` (Vellau, 0.13), `2026-11-01-donald`, `2026-11-03-donald`
(pairing), `2026-11-02-hp` / `2026-11-03-hp` (planner W1–W6).
