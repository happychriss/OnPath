# OnPath — Requirements Specification

Oct 17, 2026 · Requirements maintained in Claude Docs

## 1. Vision and guiding principles

A simple hiking and biking app with Komoot-quality maps and smart routing that works fully offline on the phone. It does five things well: show the map, show where you are, plan a route, follow or record a route, and import/export GPX. Everything else is left out.

**Guiding principles** (every feature decision is checked against these, in this order):

1. **Robust first.** The app must never lose a track, a plan or the user's position because of missing network, GPS loss, a phone call, low battery or an app crash.
2. **Offline by design, not offline as a fallback.** After a region is downloaded, every feature works in airplane mode. The network is only used to download maps and app updates.
3. **On the phone.** Map rendering, routing, elevation, search and GPX processing all run on the device. No server is needed to use the app.
4. **Simple.** One main screen, few buttons, sensible defaults. A first-time user plans a route in under 60 seconds without help.
5. **Smart where it counts.** Routing understands the activity (hike vs. trekking bike vs. MTB), surfaces, path difficulty and elevation.
6. **Open.** Open source code, open map data (OpenStreetMap), open elevation data, worldwide coverage, no account required.

## 2. Scope

Version 1 is a single-user, offline tool for Android, with iOS as a second step. Social features and cloud services are deliberately out.

| In scope (v1) | Out of scope (v1, maybe later) | Never (keeps the app simple) |
| --- | --- | --- |
| Komoot-like topographic map, offline | Points of interest guides, photos, reviews | Social feed, followers, likes |
| Own position, heading, accuracy | Cloud sync between devices | Mandatory account or login |
| Route planning: start, end, via points, drag to change | Live tracking / sharing position with others | Ads, tracking, analytics without opt-in |
| Smart routing per activity type, fully offline | Weather, sunset times | Paid map regions |
| Elevation profile, distance, ascent, time estimate | Heart rate / sensor pairing (Bluetooth) |  |
| Follow a route with off-route warning | Full voice turn-by-turn in all languages |  |
| Record a trip (track) in the background | Smartwatch app |  |
| GPX import and export (route and track) | KML, FIT, TCX import |  |
| Offline map regions: download, update, delete | Complete offline address search for every house number worldwide (v1: offline POIs and streets, online search for the rest) |  |
| Light settings: units, language, battery mode | Tablet layout |  |

## 3. Functional requirements

Each requirement has an ID and a priority: **Must** (v1 is not shippable without it), **Should** (v1 if time allows), **Could** (later).

### 3.1 Map

| ID | Requirement | Priority |
| --- | --- | --- |
| F-MAP-01 | Show a topographic vector map with hillshading, contour lines, trails, paths, roads, water, forests, peaks, huts and place names. | Must |
| F-MAP-02 | Pan, pinch-zoom and rotate smoothly (60 fps target); double-tap to zoom; two-finger tilt optional. | Must |
| F-MAP-03 | Zoom levels 0 to 16 from offline data, overzoom to 18 without blur (vector rendering). | Must |
| F-MAP-04 | Highlight trails by type: hiking paths, mountain paths by difficulty (SAC T1–T5), cycleways, MTB trails, marked route networks. | Must |
| F-MAP-05 | Scale bar and compass button (tap: north-up / heading-up). | Must |
| F-MAP-06 | Map style follows activity: hiking emphasises footpaths, biking emphasises cycleways and surface. | Should |
| F-MAP-07 | Dark / night style. | Could |
| F-MAP-08 | Route network overlays, switchable from a layer button: three overlays from OSM route relations: hiking, cycling and MTB. Cycling overlay serves all three bike profiles. Initial release: cycling and hiking overlays (Must), MTB overlay next (Should). Routes are drawn as a soft green translucent band along the path like in Komoot, with route name or number on tap. Works offline (part of the region package); one overlay at a time, auto-selected by activity, can be turned off. | Must |
| F-MAP-09 | Outdoor POIs from OpenStreetMap, offline: viewpoints, restaurants/cafés, huts, drinking water and springs, shelters, picnic sites, toilets, parking, bus/train stops, waterfalls, castles. Tap a POI: name, type, opening hours, phone, website (from OSM), plus "Open in Google Maps" (online link-out for photos, reviews, tips). Google content is not stored in the app (licence). | Must |

### 3.2 Own position

| ID | Requirement | Priority |
| --- | --- | --- |
| F-POS-01 | Show position as a dot with heading arrow and accuracy circle. | Must |
| F-POS-02 | "Center on me" button; follow mode that turns off when the user pans the map. | Must |
| F-POS-03 | Clear state when there is no fix: grey dot with "last known position, 4 min ago" or "Searching GPS". | Must |
| F-POS-04 | Show current altitude (GPS corrected with barometer and elevation model when available). | Should |
| F-POS-05 | Long-press anywhere shows coordinates and elevation of that point; copy or share them. | Should |

### 3.3 Route planning

| ID | Requirement | Priority |
| --- | --- | --- |
| F-PLAN-01 | Choose activity type first (see 3.4); it can be changed later and the route recalculates. | Must |
| F-PLAN-02 | Set start point by tap, by "my position" or by place search. | Must |
| F-PLAN-03 | Set end point the same way; option "round trip back to start". | Must |
| F-PLAN-04 | Add, move (drag), reorder and delete via points; insert a via point by dragging the route line. | Must |
| F-PLAN-05 | Route is calculated on the phone, offline, in under 2 seconds for 50 km and under 10 seconds for 300 km on a mid-range phone. | Must |
| F-PLAN-06 | Show summary: distance, ascent, descent, estimated time, highest point, surface mix (paved / unpaved / trail), difficulty. | Must |
| F-PLAN-07 | Interactive elevation profile; touching the profile moves a marker on the map and vice versa. | Must |
| F-PLAN-08 | Undo / redo for every planning step. | Must |
| F-PLAN-09 | Per segment option "straight line" for off-trail sections (e.g. across a meadow). | Should |
| F-PLAN-10 | Save route with name; edit saved route later. | Must |
| F-PLAN-11 | Warn about route problems: very steep sections, pushing the bike, ferry, terrain above the chosen T level, private or closed paths. | Should |
| F-PLAN-12 | Smart place search with one search box, hybrid: (a) offline index in each region package, from OSM: towns, addresses, hotels and huts, bus stops, train stations, parking, peaks, passes, viewpoints; tolerant of typos and partial words, ranked by distance to the map view; (b) when online, also an online search provider (e.g. Google Places API, or an open option such as Photon / Pelias) for richer results like hotel names and addresses. Online results are cached on the phone; the search box always works offline, online only improves results. Provider sits behind one interface so it can be swapped (cost decision later). | Must |

### 3.4 Smart routing by activity

Routing uses OSM tags (highway type, surface, smoothness, tracktype, sac\_scale, mtb:scale, bicycle/foot access, route relations) plus elevation. Each activity is a routing profile.

| Profile | Prefers | Avoids | Speed model | Priority |
| --- | --- | --- | --- | --- |
| Bike – touring | Paved cycleways, quiet asphalt roads, cycle network routes | Unpaved tracks, steps, busy roads, very steep climbs | Power-based, about 20 km/h on flat | Must |
| Bike – trekking | Mix of quiet roads, good gravel and forest tracks, cycle network routes | Rough trails, steps, busy roads, climbs over 12% | Power-based with luggage, about 16 km/h on flat | Must |
| Bike – e-bike | Same as trekking; accepts steeper climbs and longer days | Rough trails, steps, busy roads | Pedelec assist up to 25 km/h; climbs cost less time | Must |
| MTB | Tracks and singletrails up to MTB scale S2 (option S3+) | Main roads, steps, no-bike paths | Terrain-dependent | Should |
| MTB – e-bike | Same trails as MTB; steep uphill tracks acceptable | Main roads, steps, no-bike paths | Terrain-dependent, assisted climbs | Should |
| Hike (T1–T5) | Footpaths, marked hiking routes, tracks; mountain paths up to the chosen Swiss SAC level (T1 easy hiking to T5 very demanding alpine; default T3) | Main roads, long asphalt stretches, paths above the chosen T level, glaciers | 4 km/h on flat, plus 1 h per 300 m up and 1 h per 500 m down (DIN 33466 style) | Must |

These six profiles aim to cover about 70% of use cases. Road bike and gravel are left out of v1 and can be added later as extra profiles.

- F-ROUTE-01 (Must): routing respects legal access (bicycle=no, foot=no, private, closed).
- F-ROUTE-02 (Must): routing uses elevation; steep climbs cost time and effort, not only distance.
- F-ROUTE-03 (Should): one slider "fitness" (easy / normal / fit) adjusts time estimates; no other tuning in v1.
- F-ROUTE-04 (Could): advanced users can load their own profile file.

### 3.5 Navigation and recording

| ID | Requirement | Priority |
| --- | --- | --- |
| F-NAV-01 | "Start" on a route: map follows position, route shown, remaining distance, ascent and time shown. | Must |
| F-NAV-02 | Off-route detection (more than 40 m for 15 s, configurable): vibration and sound, then offer "reroute back to route" or "recalculate to destination", offline. | Must |
| F-NAV-03 | Record track at the same time as navigating; also record without a route ("free tour"). | Must |
| F-NAV-04 | Works with screen off and app in background; persistent notification shows distance and status; pause / resume / stop. | Must |
| F-NAV-05 | Voice hints at junctions with offline text-to-speech. | Could (off-route warning is enough for v1) |
| F-NAV-06 | After the tour: summary (distance, time moving, ascent), save, export GPX, delete. | Must |

### 3.6 GPX

| ID | Requirement | Priority |
| --- | --- | --- |
| F-GPX-01 | Import GPX 1.0 and 1.1: tracks, routes and waypoints, multiple segments, with or without elevation and time. | Must |
| F-GPX-02 | Import from file picker, "share to app" and "open with" (email, browser, messenger). | Must |
| F-GPX-03 | Tolerant parser: accepts broken or non-standard files (missing header, wrong namespaces, huge files up to 50 MB) and reports what was skipped instead of failing. | Must |
| F-GPX-04 | Imported GPX can be shown, followed (navigation) or converted to an editable planned route (snap to trails). | Must |
| F-GPX-05 | Missing elevation is filled from the offline elevation model. | Should |
| F-GPX-06 | Export planned routes and recorded tracks as valid GPX 1.1; share via the system share sheet. | Must |
| F-GPX-07 | Warn if an imported track lies outside downloaded map regions and offer to download them. | Should |
| F-GPX-08 | Read GPX files exported from Komoot (planned tours and recorded activities) correctly, keeping tour name and waypoints, so users can bring their Komoot tours over. | Should |

### 3.7 Offline regions

| ID | Requirement | Priority |
| --- | --- | --- |
| F-OFF-01 | Download a region (country or sub-region, e.g. "Bavaria", "Tyrol") containing map, hillshade, contours, routing data and search index as one package. | Must |
| F-OFF-02 | Always secure the area around every saved, imported or started route: the app automatically downloads a corridor (default 10 km) of map, routing and elevation data when online, so the route works offline without the user thinking about it. Shows "Ready offline" or "Waiting for connection" on each route. | Must |
| F-OFF-03 | Show size before download; downloads pause and resume over unstable connections; Wi-Fi only option. | Must |
| F-OFF-04 | Monthly map updates, optional; old data keeps working until the new package is fully verified. | Must |
| F-OFF-05 | List of regions with size, date and delete button; total storage used. | Must |
| F-OFF-06 | App updates from the phone: each version is published as a GitHub Release (built automatically by GitHub Actions). Settings → "Check for updates" (plus a quiet daily check when online) shows release notes and installs the new APK in place with one tap; tours and data are kept. Never installs without asking; offline it stays silent. | Must |

## 4. Robustness requirements

Robustness is the main reason this app exists, so each failure case has a defined behaviour and a test. The rule: **the user never loses data, and the app always says honestly what it knows.**

| ID | Situation | Required behaviour |
| --- | --- | --- |
| R-01 | No mobile network / airplane mode | Every feature works: map, position, planning, routing, search, navigation, recording, GPX import/export. No spinner, no error, no "you are offline" banner in normal use. |
| R-02 | Network drops during a map download | Download pauses and resumes automatically; partial files are never used; checksum verified before activation. |
| R-03 | GPS turned off by the user | Clear banner "Location is off" with one button to the system setting. Map, planning and GPX keep working. A running recording pauses and resumes automatically when GPS returns. |
| R-04 | GPS signal lost (forest, canyon, tunnel) | Show last known position greyed out with age. Recording marks a gap (new track segment) instead of drawing a straight jump. No false off-route alarms during signal loss. |
| R-05 | Bad GPS fixes (jumps, low accuracy) | Filter points with poor accuracy or impossible speed; smooth elevation; distance and ascent are not inflated by GPS noise. |
| R-06 | Location permission denied or "only while using" | App explains why background location is needed for recording, works with reduced function, never crashes or loops on permission dialogs. |
| R-07 | App killed by the system, phone restarts, battery dies | Recording is written to the database every point (at least every 5 s); on next start the app offers "Continue tour" or "Save tour". Nothing recorded is lost. |
| R-08 | App crash | Same as R-07. Planned routes auto-save after every change. Crash reports are stored locally and only sent if the user agrees. |
| R-09 | Battery saver / aggressive OEM background limits (Samsung, Xiaomi, Huawei) | Recording runs as a foreground service; first-run guide helps the user exempt the app; app detects when it was throttled and tells the user. |
| R-10 | Low battery (below 15%) | Offer a battery saver mode: lower GPS rate, darker map, screen off hints. Recording continues. |
| R-11 | Storage full | Check free space before downloads and imports; recording reserves space and warns early; never corrupt existing data. |
| R-12 | Route outside downloaded regions | Plan straight-line segments where no routing data exists, mark them clearly, offer download. |
| R-13 | No route found (blocked access, island, gap in data) | Explain why in plain words and suggest: move the point, change activity, or use a straight segment. |
| R-14 | Corrupt or huge GPX file | Import what is valid, report what was skipped, never freeze the UI (parsing runs in the background). |
| R-15 | Interruptions: phone call, other navigation app, screen rotation, split screen | Navigation and recording continue unchanged; UI state is restored exactly. |
| R-16 | Map update fails or is broken | Keep the previous working version; roll back automatically. |
| R-17 | Clock or time zone changes during a tour | Track timestamps use GPS/UTC time; statistics stay correct. |

**Data safety rules**

- All user data (routes, tracks, settings) lives in one local SQLite database with write-ahead logging and an automatic daily backup copy.
- "Export everything" creates one ZIP of GPX files, so users are never locked in.
- Deleting a tour moves it to a "recently deleted" list for 30 days.

## 5. Map data and map style

All map material comes from open, worldwide sources: OpenStreetMap for content and Copernicus elevation data for relief. Komoot itself is built on OpenStreetMap, so the same data quality is available; the difference is the cartography, which we design ourselves.

### 5.1 Data sources

| Layer | Source | Licence | Used for |
| --- | --- | --- | --- |
| Roads, paths, trails, land use, water, buildings, names | OpenStreetMap | ODbL (attribution, share-alike for the database) | Map, routing, search |
| Elevation model (about 30 m grid) | Copernicus DEM GLO-30 | Free use with attribution | Hillshade, contours, elevation profile, routing climbs |
| Fallback elevation in gaps | SRTM / other open DEMs | Public domain | Same |
| Hiking and cycling route networks | OSM route relations | ODbL | Marked routes overlay, routing preference |

### 5.2 Package format (prepared on our build server, used offline on the phone)

- **Vector tiles** in a single-file archive per region (PMTiles or MBTiles), zoom 0 to 14, overzoomed on the phone. Built from OSM with an open tile generator (e.g. Planetiler) using our own outdoor schema that keeps trail tags: sac\_scale, mtb:scale, surface, smoothness, tracktype, trail\_visibility, access.
- **Hillshade** as raster tiles (WebP) or terrain-RGB tiles from the DEM, so the phone can render shading at any angle.
- **Contour lines** as vector tiles (10 m / 20 m / 50 m / 100 m by zoom), generated from the DEM.
- **Routing data** for the routing engine, per region (see section 6).
- **Search index**: place names, streets, hotels, huts, bus stops, train stations, parking, peaks, passes, viewpoints.
- One manifest per region with version, date, size and checksums. Target size: a region of about 70,000 km² (e.g. Bavaria) below 1.5 GB in total, to be measured in milestone 1.

### 5.3 Komoot-like style requirements

We build our own MapLibre style, inspired by the Komoot look but not copied (their style is their design). Style goals:

- **Calm, light base**: soft pastel greens for forest and meadow, light beige for open land, clear blue water, white roads with thin grey casing. Low visual noise; buildings only at high zoom.
- **Strong but soft relief**: multi-directional hillshade blended under the base, plus contour lines that fade in from zoom 12. Mountains must "read" at a glance.
- **Trails are the hero**: footpaths and tracks drawn darker and more visible than in a normal street map; difficulty shown by dash pattern and colour (easy, mountain, alpine); unpaved vs. paved clearly different.
- **Labels**: peaks with elevation, huts, passes, viewpoints, water sources, shelters; clean sans-serif font; no clutter.
- **Planned route**: thick, high-contrast line with white outline, readable on every background; via points as numbered markers.
- **Activity emphasis**: hiking style highlights footpaths and hiking routes; bike style highlights cycleways, surface and cycle networks.
- **Quality bar**: side-by-side screenshots against Komoot at zoom 10, 13, 15 and 17 in five test areas (Alps, low mountain range, city, coast, flat farmland) are reviewed before release.

## 6. Architecture and tech stack

The phone does all the work; the only server is a monthly build pipeline that turns open data into downloadable region packages on static file hosting. There is no backend to run, no user accounts and no API that can go down during a hike.

### 6.1 Recommended stack (all open source)

| Part | Choice | Why | Alternative |
| --- | --- | --- | --- |
| App platform | Android first, Kotlin + Jetpack Compose | Best control over background GPS, foreground services and battery behaviour, which drive robustness | Flutter (one codebase, but GPS background handling is less direct) |
| Shared logic | Kotlin Multiplatform modules (GPX, track processing, storage, statistics) | Reuse for the iOS app later | Plain Kotlin, port later |
| Map rendering | MapLibre Native (BSD) | GPU vector rendering, offline tile archives, style spec, hillshade layers, used widely | Mapsforge (older, raster-like rendering) |
| Routing engine | BRouter (MIT), embedded on the phone | Built for offline bike and hiking routing, elevation-aware, compact worldwide routing files, scriptable profiles; proven in OsmAnd and Locus | GraphHopper (Apache 2.0, Java) or Valhalla (MIT, C++, also runs on iOS) |
| Database | SQLite with WAL (via Room or SQLDelight) | Crash-safe, single file, easy backup | — |
| Offline search | SQLite full-text search (FTS5) per region, plus optional online provider (Google Places or Photon / Pelias) when connected | Small, fast, no extra engine | — |
| Elevation | DEM tiles in the region package, read on the phone | Profiles, GPX enrichment, altitude correction | — |
| Voice hints | System text-to-speech with offline voices | No network | — |
| Location | Android fused/GNSS location in a foreground service; barometer when present | Accuracy and survival in background | — |
| Map data pipeline | Planetiler (Apache 2.0) + GDAL for hillshade and contours + BRouter map creator | Open, fast, reproducible | OpenMapTiles tools |
| Hosting | Static files on a CDN or object storage | Cheap, no server logic | Mirror on community hosts |

### 6.2 Main components on the phone

The diagram below shows how the parts connect. Arrows show who uses whom; every arrow stays on the phone except the dashed download link.

&#91;embedded content: app architecture · on-device components and the monthly data build\]

Only the download manager touches the network; once a region is on the phone, map, routing, recording and GPX never need it.

## 7. UX and screens

The app has one main screen (the map) and four secondary screens. Every action is reachable in at most two taps from the map.

| Screen | Content | Main actions |
| --- | --- | --- |
| Map (home) | Full-screen map, position dot, small top bar with activity icon, bottom buttons: Plan, Record, My tours | Center on me, compass, long-press for point info |
| Plan | Map with route, bottom sheet with summary and elevation profile, activity selector | Tap to add points, drag to move, undo, save, start |
| Navigate / Record | Map in follow mode, large numbers: remaining distance, ascent, time; status of GPS and recording | Pause, stop, reroute, lock screen |
| My tours | List of planned routes, recorded tracks and imported GPX, with small map preview | Open, follow, edit, export GPX, delete |
| Offline maps | World map with region outlines, downloaded regions, storage used | Download, update, delete |
| Settings | Units, language, battery mode, voice on/off, off-route distance, data export, licences | — |

**Interaction rules**

- Large touch targets (at least 48 dp) usable with gloves and in sunlight; high-contrast mode.
- Status always visible in one small indicator: GPS (good / weak / off) and recording (on / paused). No other badges.
- No pop-ups during navigation except off-route and critical battery.
- First start: three short screens (what the app does, location permission, download your first region). No account.
- Languages for v1: English and German; all text in resource files for community translation.

## 8. Non-functional requirements

Targets are measured on a reference mid-range phone (about 3 years old, 4 GB RAM) and a current flagship.

| Area | Requirement | Target |
| --- | --- | --- |
| Startup | Cold start to usable map | under 2 s |
| Map | Frame rate while panning and zooming | 60 fps flagship, 30 fps minimum mid-range |
| Routing | Route calculation, offline | 50 km under 2 s; 300 km bike route under 10 s |
| Battery | Recording with screen off, GPS every second | under 5% per hour (battery saver mode: under 3%) |
| Battery | Navigation with screen on | under 15% per hour |
| Memory | Peak RAM use | under 400 MB |
| Storage | App install size without maps | under 40 MB |
| Reliability | Crash-free sessions | above 99.8% |
| Reliability | Recorded tracks lost | zero (tested by killing the app, rebooting, draining battery) |
| Accuracy | Recorded distance vs. reference track | within 2% |
| Accuracy | Recorded ascent vs. elevation model | within 10% |
| Compatibility | Android versions | Android 9 and newer |
| Accessibility | Font scaling, screen reader labels, colour-blind safe route colours | Required |
| Privacy | No location data leaves the phone; no trackers; no account | Required |
| Licensing | App source code under Apache-2.0; all data attributions shown in the app | Required |
| Maintainability | Map packages rebuilt automatically every month; app builds reproducible | Required |

**Testing for robustness**: an automated test suite replays recorded GPS logs (including signal loss, jumps and tunnels), kills the app mid-tour, toggles airplane mode and GPS, fills storage, and imports a library of 200+ real-world broken GPX files. Field tests run in the Alps, a forest, a city and on long bike tours before each release.

## 9. Roadmap, risks and open questions

The build order puts the map style and robustness first, because those are what make or break the app. Each milestone ends with a usable app.

### 9.1 Milestones

1. **M1 – Map proof.** Data pipeline for one test region (Bozen / Meran / Algund area, South Tyrol), Komoot-like style v1, offline map with position on Android. Exit: style review passes against Komoot screenshots; package size measured.
2. **M2 – Robust recording.** Foreground recording, crash and reboot recovery, GPS loss handling, GPX export. Exit: zero lost tracks in the kill/reboot/airplane test suite.
3. **M3 – Offline routing.** BRouter embedded, six activity profiles, planning UI with via points, elevation profile, undo. Exit: routing targets in section 8 met; field test of 20 planned routes.
4. **M4 – GPX and navigation.** Tolerant GPX import, follow route, off-route alert and offline reroute. Exit: 200-file GPX test library imports without crash.
5. **M5 – Worldwide regions.** Monthly automated build for all regions, download manager with resume and rollback. Exit: public beta.
6. **M6 – iOS.** Port with shared Kotlin core and the chosen iOS routing engine.

### 9.2 Risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Map style does not reach Komoot quality | Users stay with Komoot | Dedicated cartography work in M1, side-by-side reviews, reuse ideas from open outdoor styles (OpenTopoMap, Waymarked Trails, Protomaps) |
| Region packages too large for phones | Users cannot store their areas | Tune zoom levels and layers, compress hillshade, offer route-corridor downloads |
| Android OEM battery killers stop recording | Lost tracks, broken trust | Foreground service, in-app guide per manufacturer, throttling detection, field tests on Samsung and Xiaomi |
| BRouter is Java-only, not available on iOS | iOS delayed | Decide in M3 whether to switch both platforms to Valhalla, or use a different engine on iOS |
| OSM data gaps (missing surface or sac\_scale tags) | Wrong routing in some areas | Conservative defaults per road type; show "surface unknown" in summary |
| Hosting cost for worldwide packages | Ongoing cost | Static files only; community mirrors; per-region downloads |

### 9.3 Open questions

- [x] Platform: Android first; iOS later.
- [x] Licence: Apache-2.0.
- [x] Voice: the off-route warning is enough; no turn-by-turn voice in v1.
- [x] Komoot GPX: reading GPX exported from Komoot is a Should (F-GPX-08).
- [x] First test region: Bozen / Meran / Algund (South Tyrol).
- [x] App name: OnPath. Code: github.com/happychriss/OnPath.
- [x] Distribution: Google Play only to start (test builds sideloaded as APK).
