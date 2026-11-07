# Website screens – every app picture on onpath.earth, and which app screen it shows

Owner 2026-11-01: after a UI change only the pictures of the changed screens are taken again, not whole sets. This list
says which picture shows which screen: a role that changes a file in the right-hand column takes the pictures of that
row again (`tools/dev/website_shot.sh`, below) and puts the PNGs into `onpath.private/marketing/bilder/<date>-<unit>/`
with an `Ergebnis.md` (what each shows, what it replaces). cloud-web turns them into the site's WebP files
(`scripts/help_shots.py` in `happychriss/onpath.earth`; the file names are the keys there).

## Help pages (`onpath.earth/hilfe/…`, `/help/…` – `help_shots.py` IMAGES)

| Key | Source PNG DE · EN | What it shows | App screen (change here → new picture) |
| --- | --- | --- | --- |
| leer | `08a-erste-route-leer.png` · – | empty plan, "Route planen" + hold hint | `ui/PlanPanel.kt`, `ui/MapScreen.kt` |
| halten | `08b-erste-route-halten.png` · `halten-en.png` | hold on the map: "Neue Route – Hier starten · Hierhin" | `ui/MapOverlays.kt` (first-point menu) |
| punkt1 | `08c-erste-route-punkt1.png` · – | start set, waiting for the destination | `ui/PlanPanel.kt`, `ui/MapHost.kt` |
| ziel | `08d-erste-route-ziel-menue.png` · – | hold a place → set as destination | `ui/MapOverlays.kt` (POI / point menu) |
| fertig | `08e-erste-route-fertig.png` · – | route ready: time, length, notes, profile | `ui/PlanPanel.kt`, `ui/ElevationProfile.kt` |
| (new) | `08f-plan-punkteliste.png` · `plan-punkteliste-en.png` | plan panel fully up: profile + Points list | `ui/PlanPanel.kt`, `ui/PointsSection.kt` |
| plan | `02-plan-wandern.png` · `plan-wandern-en.png` | Hike T2 plan, two steep sections | `ui/PlanPanel.kt`, `ui/RouteWarnings.kt`, map style |
| ebike | `03-plan-ebike.png` · `plan-ebike-en.png` | the same route by e-bike | as `plan` |
| hinweise | `02b-plan-wandern-hinweise.png` · – | the route notes (steep sections) | `ui/RouteWarnings.kt`, `ui/PlanPanel.kt` |
| navi | `09a-unterwegs-navigation-stopp.png` · `navigation-stopp-en.png` | Navigate with the slider "Stopp · Pause · Beenden · Route ändern" | `ui/NavigateScreen.kt`, `ui/SlideToUnlock.kt` |
| tourmenue | `09b-unterwegs-tourmenue.png` · `tourmenue-en.png` | after the slide: tour running, ■, Weiter | `ui/TourBar.kt` |
| suche | `09b2-unterwegs-suche.png` · – | search during a tour | `ui/search/SearchScreen.kt` |
| ort | `09c-unterwegs-ort-halten.png` · `ort-google-maps-en.png` | a found place: add / open in Google Maps | `ui/MapOverlays.kt` (POI menu), `map/CandidateMarker.kt` |
| neue-route | `09d-unterwegs-neue-route.png` · – | the new route during a tour, offline | `ui/NavigateScreen.kt`, `ui/PlanPanel.kt` |
| zurueck | `09e-unterwegs-zurueck.png` · – | back in Navigate, new arrival | `ui/NavigateScreen.kt` |
| g-uebernommen | `google-ziel-uebernommen-de.png` · `…-en.png` | a place shared from Google Maps in OnPath | `map/CandidateMarker.kt`, `ui/MapOverlays.kt` |
| g-route | `google-ziel-route-de.png` · `…-en.png` | after "Hierhin": the route there | `ui/PlanPanel.kt` |
| ankunft | `05-ankunft.png` · `05-ankunft-en.png` | Navigate: next turn, time left, arrival | `ui/NavigateScreen.kt` (turn card, status card) |
| ruhe | `06-ruheanzeige.png` · `ruheanzeige-en.png` | rest view on the bike | `ui/RestView.kt`, `ui/SmartScreen.kt` |
| beenden | `10a-tour-beenden.png` · – | "Tour beenden?" | `ui/TourBar.kt` (end dialogs) |
| fahrt | `07-fahrt.png` · `fahrt-en.png` | a detected chairlift ride | `ui/RideSummary.kt` |
| gespeichert | `10b-tour-gespeichert.png` · – | the saved tour (GPX) | `ui/Screens.kt` |
| meine-routen | `10c-meine-routen.png` · – | My routes → Gemacht | `ui/Screens.kt` (My routes) |
| neustart-hinweis | `neustart-hinweis-de.png` · `…-en.png` | after a restart: the recording is saved | `ui/NavigateScreen.kt`, `ui/Screens.kt` |
| neustart | `neustart-de.png` · `neustart-en.png` | "Unterbrochene Tour": continue / delete / save | `ui/Screens.kt` |
| app-koppeln | `app-a1-de.png` · `app-a1-en.png` | ☰ open: "Mit Browser verbinden" (emulator, real map) | `ui/PlanTopBar.kt` (☰ menu) |
| app-pruefen | `app-a2-de.png` · `app-a2-en.png` | "Mit diesem Browser verbinden? Prüfe: … – Verbinden" (Paparazzi `settingsSyncAsk` / `syncAsk`) | `ui/SyncSettings.kt` |
| app-neu | `app-a3-de.png` · `app-a3-en.png` | My routes with a route from the browser ("Neu") (Paparazzi `toursFromBrowser`) | `ui/Screens.kt` (My routes), `ui/RouteTag.kt` |
| app-trennen | `app-a4-de.png` · `app-a4-en.png` | Settings → Browser-Abgleich paired, "Trennen" (Paparazzi `settingsSyncPaired` / `syncPaired`) | `ui/SyncSettings.kt` |
| (voice, later) | `sprachansagen-einrichten-de.png` · – | phone setup step "Sprachansagen" | `ui/setup/SetupGuideScreen.kt` |

## Home page (`onpath.earth/`, `assets/app/*-de|en.webp`)

`plan-wandern`, `plan-ebike`, `ankunft`, `ruheanzeige`, `fahrt`, `ort-google-maps` – the same screens as the help keys
`plan`, `ebike`, `ankunft`, `ruhe`, `fahrt`, `ort` above. `web-wandern`, `web-qr` – the browser planner (cloud-plan): the same pictures as the help keys `web-plan`, `web-teilen` (section "Browser planner" below).

## Browser planner (laptop) – `web/app/test/pair-shots.mjs` on `main`

One script takes every planner picture of the site, DE + EN, on the laptop (no phone, no live site): the A-019 plan link
(Vellau Korblift → Leiteralm, Hike T2), the map from a format-1 map folder, a real pairing with the fake phone "Pixel 8"
against a local test mailbox (the pairing code is blurred anyway). Run it on `main`:
`cd web/app && npm run build && node test/pair-shots.mjs <segmentsDir> <mapFolder> <outDir>` – then the PNGs go to
`onpath.private/marketing/bilder/<date>-hp/` with an `Ergebnis.md`, as above. File names = `<script name>-de|en.png`.

| Site key | Script name (DE · EN) | What it shows | Planner file (change here → new picture) |
| --- | --- | --- | --- |
| web-plan (help), web-wandern (home) | `web-plan-de.png` · `-en` | 1440 × 900: the route, time, profile, Points (sheet's Points step) | `web/app/src/main.ts` (sheet, profile, Points rows), `sheetstep.ts`, `style.css`, map style |
| web-teilen (help), web-qr (home) | `web-teilen-de.png` · `-en` | "Share" with the plan's QR code (public plan link) | `web/app/src/share.ts` |
| web-handy (help) | `web-handy-de.png` · `-en` | the Phone window, not paired, "How does it work?" open | `web/app/src/plans.ts` (`phoneDialog`) |
| web-koppeln (help) | `web-w2-de.png` · `-en` | "Pair phone": QR (blurred), the way in the app, check digits | `web/app/src/plans.ts` (`pairDialog`) |
| (new) W1 | `web-w1-de.png` · `-en` | not paired: header "Save" (dark) + "Pair phone" | `web/app/src/main.ts` (header: `saveEntry`, `syncBadge`), `style.css` |
| (new) W3 | `web-w3-de.png` · `-en` | paired, route not on the phone: "Save to phone" dark + phone pill "Pixel 8 · synced 2 min ago" | as W1 |
| (new) W4 | `web-w4-de.png` · `-en` | after the click: "✓ On the phone", "Saved as …" in the sheet | as W1, `plans.ts` (`saveRoute`) |
| (new) W5 | `web-w5-de.png` · `-en` | My routes paired: 🖥 📱, one waiting (bold), one "New" from the phone | `web/app/src/plans.ts` (`myPlansDialog`) |
| (new) W6 | `web-w6-de.png` · `-en` | the Phone window paired: name + dot, "Sync now", red "Unpair" | `web/app/src/plans.ts` (`phoneDialog`) |
| (film) | `web-plan-vellau-leiteralm-de.png` · `-en` | 2560 × 1440, profile step, no dialog (`marketing/video-aufnahmen-uebergabe.md` §2) | as web-plan |

Texts the engine writes (time, distance, notes, Points rows, "Neu planen" …) come from the app's shared strings – a
change in `strings/…/strings_plan.xml` that the planner shows also means new web pictures. Older sets:
`bilder/2026-11-01-web/` (cloud-web, live site), `bilder/2026-11-02-hp/` (W1–W6 + film, before "Neu planen").

## Taking a picture (`tools/dev/website_shot.sh`, donald)

The A-019 setup, so new pictures match the old ones: emulator AVD `OnPath_Play36` (Pixel-7 profile 1080 × 2400,
density 356, Android 16, maps of Partschins already on it), the app language set per app (DE / EN), Android's status
bar demo mode (09:30, Wi-Fi, full battery, no icons), the position by the emulator's GPS (never a demo ride: no DEMO
mark, no Sim chip; never the owner's real position), the plan from the A-019 plan link (Vellau Korblift → Leiteralm,
Hike T2). `website_shot.sh start` → `setup de|en` → drive the app (adb input / by hand) → `shot <file.png>` → `stop`.
First used 2026-11-01 (cloud-app): `onpath.private/marketing/bilder/2026-11-01-donald/`.
