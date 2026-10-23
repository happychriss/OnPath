# OnPath – UI text review (2026-10-23)

Paths are relative to `app/src/main/java/com/onpath/app/`. Read-only review; nothing edited.
"(being reworked)" = map-download indicator texts the cloud session is changing right now.

## 1. Clear fixes

### Bugs / typos / doubled words
| file:line | current text | proposed text | why |
|---|---|---|---|
| MainActivity.kt:431 | "OnPath stopped unexpectedly last time (${crash.message})." → shows "…last time (OnPath stopped unexpectedly (main))." | "OnPath stopped unexpectedly last time. Details: Settings → Diagnostics." | says it twice + thread name |
| ui/MapFooter.kt:200 (TalkBack) | "Centre on my position. GPS ${status.label}" → "GPS GPS good" / "GPS Searching GPS" | "Centre on my position. ${status.label}" | doubled "GPS" |
| ui/TourBar.kt:95, ui/RideSummary.kt:79 | "…recycle bin for 30 days" (fixed number) | use `RecycleBin.DAYS` like Screens.kt:333 | could go out of step |
| ui/MapOverlays.kt:215 | "Removes ${points(index)} before" | "Removes the ${points} before it" | half sentence |
| navigation/JunctionCues.kt:93–98 (notification) | title "8.2 km to go", text "2 h 10 min to go · arrival 15:40 · 8.2 km left" | text: "2 h 10 min · arrival 15:40" (distance only once) | same distance twice |
| MainActivity.kt:293 | \"Allow all the time\" (straight quotes) | “Allow all the time” | same quotes as elsewhere |

### Jargon / technical words
| file:line | current text | proposed text | why |
|---|---|---|---|
| MainActivity.kt:125 | "Problem (${p.area}): ${p.message}. Details: Settings → Diagnostics." (area = "cells", "gps"…; message may be technical) | see Question 9 | jargon in a pop-up |
| recording/RecordingService.kt:207 | "Recording could not start: the tour database cannot be read." | "Recording could not start: your saved tours cannot be read." | "database" |
| recording/RecordingService.kt:219 | "Recording could not start (Android did not allow the background service). Open OnPath and try again." | "Recording could not start – Android blocked it. Open OnPath and try again." | "background service" |
| RecordingService.kt:247, :420; ui/AppViewModel.kt:1153 | "(storage full or database broken)" | "(phone storage full or damaged)" | "database" |
| ui/AppViewModel.kt:208 | "…once GPS has a fix." | "…once GPS has found your position." | "fix" |
| ui/NavigateScreen.kt:406 | " · last fix 4 min ago" | " · position 4 min old" | "fix" |
| navigation/NavText.kt:67 | " · fix 4 min old" | " · position 4 min old" | "fix", same as above |
| ui/NavigateScreen.kt:297 + time/TripTime.kt:37–38 | "estimate paused · no GPS" / "· no progress" | "time paused · no GPS" / "time paused · not moving along the route" | "no progress" unclear |
| ui/NavigateScreen.kt:287 | "— not on route" | "Not on the route" | stray dash |
| ui/AppViewModel.kt:2106 | "Could not re-route – still following the previous line." | "No new route found – still following the old one." | "re-route", "line" |
| ui/AppViewModel.kt:3254 | "Route updated – newer way data." | "Route updated with newer map data – please check it." | "way data" |
| ui/AppViewModel.kt:2663 | "Offline routing not available" | "Can't check routes right now" | jargon |
| navigation/NavText.kt:17 | "Off route – no routing data here" | "Off route – no map here to find a way back" (see Q5) | "routing data" |
| navigation/NavText.kt:18 | "Off route – routing not available" | "Off route – can't search a way back right now" | "routing" |
| ui/SettingsScreen.kt:526 | "Which part of the routing data you need (an area of about 1° × 1°)" | "Which part of the path network you need (an area of about 80 × 110 km)" | "routing data", "1°" |
| ui/MapLoad.kt:80 + :165 | "3 regions · about 27 MB", "2 regions · ≈ 18 MB" | "3 map areas · about 27 MB" | "region" → "map area" |
| cells/MapCells.kt:63–66 (shown in Settings → Offline maps row status) | "requested", "waiting (#3) – about 5 min", "building – base 40 %", "ready – downloading", "adding to the map" | "waiting", "waiting – about 5 min", "being prepared – 40 %", "downloading", "almost ready" | queue #, step names |
| cells/MapCells.kt:709 | "This area could not be prepared (${s.reason ?: "unknown reason"}). Planning still works with a straight line." | "This map area could not be prepared. Routes there are straight lines for now." | raw server reason |
| cells/CellApi.kt:104, :107 | "Download of ${f.name} stopped at 40 % …", "Download damaged (${f.name}) – loading again." | "Map download stopped at 40 % – continues when connected." / "Map download damaged – loading again." | file names |
| cells/MapCells.kt:644 | "This app version has no map server access (no app key)." | "This app version cannot download maps. Please install the latest version." | "app key" |
| backup/AndroidBackupStore.kt:140 | "folder ${d.path} cannot be created" | "the backup folder cannot be created" | file path |
| MainActivity.kt:458 | "No app to share with. The file is in the app's cache: ${f.name}" | "No app found to send the file with." | "cache", file name |
| ui/search/SearchScreen.kt:154 | "Online results: Photon · © OpenStreetMap" | "Online results © OpenStreetMap" | "Photon" unknown |
| update/UpdateUi.kt:97 | "Updates come from GitHub Releases." | "Updates come from the OnPath page on GitHub." | jargon |
| ui/CellCard.kt:57 (being reworked) | "Planning works on the overview map; routes without routing data are straight lines." | "You can still plan on the overview map; routes there are straight lines." | "routing data" |
| ui/BackgroundDownloads.kt:122, :71; ui/OfflineMapsSection.kt:88 (being reworked) | "Routing data: 2 parts", "routing data" | count into "map areas" or Q5 word | "routing data" |
| ui/OfflineMapsSection.kt:153 (being reworked) | "…loading – see the route sheet." | "…loading – see the blue bar on the map." | card goes away |
| ui/Screens.kt:104–167 (missing-data screen; only without map downloads) | "adb", "USB debugging", "Download routing data for this area (≈ 10 MB)" | fine as developer screen; if users can see it: "Download paths for route planning (≈ 10 MB)" | low priority |

### Same thing, different names (see section 3)
| file:line | current text | proposed text | why |
|---|---|---|---|
| ui/Screens.kt:345 | "Replace current plan?" (text says "Your current route is not saved") | "Replace current route?" | plan → route |
| ui/AppViewModel.kt:2312, :2363 | "…Your plan is still here – please try again." | "…Your route is still here – please try again." | plan → route |
| backup/BackupRestore.kt:27–28 | "No tours or plans restored" / "3 tours and 5 plans restored" | "…tours or routes…" / "3 tours and 5 routes restored" | plan → route |
| ui/SettingsScreen.kt:506 | "Your tours, plans and your track never leave the phone…" | "Your tours and routes never leave the phone…" | plan/track → tour/route |
| ui/OfflineMapsSection.kt:209 | "…also those of your plan and saved routes…" | "…also those of your current route and saved routes…" | plan → route |
| ui/BackupScreens.kt:163 | "Restore tours from backup" | "Restore tours and routes" | routes too |
| ui/BackupScreens.kt:191 | "Restoring tours" | "Restoring tours and routes" | routes too |
| ui/AppViewModel.kt:3057 | "Your tours are unchanged." | "Your tours and routes are unchanged." | same as :3150 |
| ui/AppViewModel.kt:3052 | chooser title "Export all tours" | "Export all" | matches menu "Export all (ZIP)" |
| ui/SettingsScreen.kt:311 vs SettingsSummary.kt:50 | "Hillshade (relief)" vs "Hill shading" | "Hill shading" in both | one name |
| ui/SettingsScreen.kt:310 vs SettingsSummary.kt:50 | "Contour lines" vs "Contours" | "Contour lines" in both | one name |
| ui/SettingsSummary.kt:14 vs PaceSettings.kt:37 | page "Pace & time", section "Your pace" | "Your pace" both | one name |
| ui/PointsSection.kt:312, MapOverlays.kt:217 | "Same loop, starts here", "Start & goal" | "Same round trip, starts here"; "Start & end" (or Q2 word) | button says "Round trip" |
| navigation/NavText.kt:34 | "Rest of the tour 9.3 km…" (activity switch question) | "Rest of the route 9.3 km…" | it is the route |
| ui/MapLoad.kt:290 vs CellCard.kt:298 | "Try again" vs "Retry" | "Try again" | one label |
| ui/Panels.kt:169 | "Follow this track" (during recording) vs "Start navigation" | "Navigate this track" | matches "Navigate" |

### Clarity / wording
| file:line | current text | proposed text | why |
|---|---|---|---|
| ui/PlanStatus.kt:26–27 | "Hold the map to start there – or to go there from your location. Hold a point or place for options. To just record, use ☰ → Start recording." | "Hold the map where you want to start or go. To only record: ☰ → Start recording." | long for a sheet line |
| ui/SettingsScreen.kt:322 | "When you hold the map: 1× (map, line, place), 2× on a route point" | "Short buzz when you hold the map, two buzzes on a route point" | cryptic |
| ui/SettingsScreen.kt:363–364 | "After a restart or update you tap Continue. To continue by itself: location → “Allow all the time”." | "After a phone restart or app update, tap Continue to go on recording. To make this automatic, set location to “Allow all the time”." | hard to read |
| ui/SettingsScreen.kt:380 | switch "Diagnostics export" (only shows the button) | "Show export button" or drop the switch | unclear what the switch does |
| ui/TourBar.kt:174–175 | "The goal is deleted. Yes: the route leads from here back to the start. No: the tour keeps recording without a route. Undo brings the goal back either way." | "You deleted the destination. Yes: go back to the start from here. No: keep recording without a route. Undo brings the destination back." | long, "goal" |
| ui/TourBar.kt:189–190 | "It would take about 29 days on foot. Calculating it and loading the maps along it takes a long time. Plan it anyway?" | "It would take about 29 days on foot. Calculating it and loading its maps takes long. Plan it anyway?" | shorter |
| ui/RouteDialogs.kt:79–80 | "The dashed parts are straight lines, not paths. Off-route warnings and turn hints follow them anyway. Move those points or change the activity, or navigate as it is." | "The dashed parts are straight lines, not paths. Move those points or change the activity – or navigate anyway." | 2nd sentence confuses |
| ui/RouteDialogs.kt:40 | title "Not saved" used for save, rename, delete, restore errors | "Something went wrong" (or one title per case) | wrong title for delete/restore |
| ui/AppViewModel.kt:1172 | "The tour could not be deleted – it is in My routes, delete it there." | "The tour could not be deleted here. You can delete it in My routes." | clearer |
| ui/AppViewModel.kt:2520 | "Check the location permission (precise) and notifications…" | "Allow precise location and notifications for OnPath, then try again." | clearer |
| ui/AppViewModel.kt:1904 | "Couldn't find this place – try again with internet" | "Couldn't find this place. Try again when you have internet." | one sentence |
| ui/RouteWarnings.kt:160 | "Route changed for trekking bike T… – please check" (hike: "for hike T3") | "Route changed for Hike T3 – please check it" | casing (Q6) |
| ui/PlanGaps.kt:58 | "First 1.2 km from \"Hut\" not possible by trekking bike" | "First 1.2 km from “Hut”: no way for trekking bike" | grammar, quotes |
| ui/MapLoad.kt:83 | "(Settings: maps only on Wi-Fi)" | "(Settings → Offline maps → Wi-Fi only)" | name differs from switch |
| ui/CellCard.kt:134 (being reworked) | " – Settings: maps only on Wi-Fi" | same as above | name differs |
| ui/OfflineMapsSection.kt:161–162 | "Nothing loads over mobile data, not even your own area" / "Your own area loads on mobile data; more areas ask first" | "Nothing loads on mobile data, not even where you are" / "Where you are loads on mobile data; other areas ask first" | "own area" unclear |
| ui/OfflineMapsSection.kt:168 | "…Used areas also update monthly by themselves." | "Map areas you use update once a month on their own." | wording |
| ui/CellCard.kt:84–85 (being reworked) | "Waiting for your position to get the detailed map for your area – or set a point on the map" | "Waiting for GPS to get the detailed map – or hold the map where you want to go" | long; "set a point" |
| ui/CellCard.kt:336 | "Overview only – set a point and the detailed map follows" | "Overview only – hold the map here to get the detailed map" | "set a point" vague |
| ui/BackgroundDownloads.kt:63 (being reworked) | "Newer version of 3 areas you have – it replaces the current map when loaded" | "Newer map for 3 of your areas – replaces the old one when loaded" | grammar |
| ui/TimeDetailsScreen.kt:63 | "At Average pace" | "At average pace" | capital A |
| ui/SettingsScreen.kt:314, MapFooter.kt:158–160, AppViewModel.kt:208 | "Walking direction" (also for bikes) | see Q8 | bikes |
| ui/ActivitySwitchSheet.kt:95 | "rest of the route" (small caption) | "for the rest of the route" | reads as fragment |

## 2. Questions for the owner (answer with a number or yes/no)

1. **Mixed languages on a German phone.** When the phone is set to German, some parts switch to German (place menu "Zur Route hinzufügen", "In Google Maps öffnen", place sheet "Öffnungszeiten", place types like "Schutzhütte"), everything else stays English. What do you want? **1** all English always · **2** English buttons, only place types/names in German · **3** keep as it is.
2. **Name of the last point of a route.** Today: "Destination" (map label, place card "As destination"), "Goal" (points list, "The goal is deleted", "set a new goal"), "finish" ("Start & finish here"). Pick one: **1** Destination · **2** Goal · **3** Finish.
3. **Points in the middle.** Map labels and search say "Stop 2" / "as stop 2"; the points list and messages say "point 2". **1** "Stop" for middle points, "point" for any point · **2** "Point" everywhere · **3** keep.
4. **Name of a recorded tour in My routes.** The list shows "Recorded track"; dialogs say "tour" ("Tour saved", "Delete this tour?"). The other two kinds are "Planned route" and "Imported GPX". **1** "Tour" (and "Route", "Imported GPX") · **2** keep "Recorded track" · **3** "Recorded tour".
5. **The data the app needs to find paths** ("routing data") still shows in: off-route banner "no routing data here", the download dialog "Routing data: 2 parts", Settings → Privacy. How should users see it? **1** never name it – count it as part of "the map" · **2** "path network" · **3** "route data".
6. **Activity names.** Today a trekking bike is "Bike – trekking" (picker, Navigate), "Trekking" (chip), "Trekking bike" (Start button), "trekking bike" (warnings); MTB e-bike is "MTB – e-bike" and "E-MTB". **1** one set everywhere: Touring bike, Trekking bike, E-bike, MTB, E-MTB, Hike (chip may shorten) · **2** picker style "Bike – trekking" everywhere · **3** keep.
7. **Time details screen (⋯ → Time details)** shows formulas: "power model … rolling resistance", "Applied ↓ / → / ↑ × 1.15", columns "Base", "Slope only". **1** move it to Diagnostics → Developer tools; ⋯ keeps only moving time, with breaks, pace · **2** keep in ⋯ but rewrite plainly · **3** keep as is.
8. **"Walking direction"** is used for the map-turn setting and the compass button, also when cycling. **1** "Direction of travel" · **2** keep "Walking direction" · **3** "Heading up".
9. **Pop-up on a new problem** reads "Problem (cells): <technical message>. Details: Settings → Diagnostics." **1** fixed plain text: "Something went wrong. It was noted in Settings → Diagnostics." · **2** keep the message but drop the "(cells)" part · **3** no pop-up, only a red mark at Settings → Diagnostics.
10. **Tour bar button** says "End" (next to Continue / Pause); you know it as "End tour". **1** "End tour" (shorter "Pause" icon-only if needed) · **2** keep "End".

## 3. Naming consistency

| Thing | Names found | Recommend |
|---|---|---|
| The line you plan | route, plan ("Replace current plan?", "your plan", "plans restored"), line ("previous line") | **route** (mode name "Plan" stays) |
| A recorded walk/ride | tour, recorded track, track ("your track") | **tour** (Q4) |
| Saved list | My routes | My routes (fine) |
| Downloaded map piece | map area, area, region ("3 regions"), "Map area 2", cell (only debug: AppViewModel.kt:288 "Cell … requested") | **map area** |
| Path-finding data | routing data, routing, way data, parts | Q5 |
| Last point | Destination, Goal, finish | Q2 |
| Middle point | stop, point | Q3 |
| Closed route | Round trip, loop ("Same loop"), "Start & goal" | **round trip** |
| Activity | 4 spellings each (see Q6) | Q6 |
| GPS position | position, location, fix | **position** (never "fix") |
| Retry button | Try again, Retry, "Tap again" | **Try again** |
| Map relief | Hillshade (relief), Hill shading | **Hill shading** |
| Contours | Contour lines, Contours | **Contour lines** |
| Pace page | Pace & time, Your pace | **Your pace** |
| Wi-Fi setting | "Download maps only on Wi-Fi", "Settings: maps only on Wi-Fi" | **Download maps only on Wi-Fi** (quote exactly) |
| Export | Export GPX, Export all (ZIP), "Export all tours" | Export GPX / **Export all** |
| Percent | "40 %" (most), "40%" (MapLoad.kt:282) | pick one; "40 %" is already the majority |
| Undo bin | recycle bin, "My routes → ⋮ → Recycle bin" | fine |
