# OnPath

An open-source Android app (Apache-2.0) for planning and following hikes and bike tours, also where there is no
signal.

- **Plan and re-route without signal.** The route is calculated on the phone (with
  [BRouter](https://github.com/abrensch/brouter)). Once the map for an area is on the phone – it downloads by itself
  when you plan there – you can plan, change the route and get a new one on the trail without a connection.
- **Find places offline, or take them from Google Maps.** Addresses with house numbers, villages, huts and peaks are
  found without a connection. A place you share from Google Maps opens in OnPath and can be added to the route.
- **Change the plan halfway and carry on.** Slide to the planning view, change the route, go back. The recording keeps
  running, points you have already passed are skipped, and navigation continues from where you are. Every change can be
  undone.
- **No account, and your tours are plain files.** No sign-up, no ads, no analytics, no Google Play Services. Every
  finished tour and saved route is also written as a GPX file to a folder you choose.

> **Work in progress.** OnPath is young and changes often. So far it is used and tested mainly by its author, in
> South Tyrol (Italy), on one phone. If you would like to try it and tell me what does not work, see
> [Become a tester](#become-a-tester).

## Why I am building it

I have used Komoot a lot since its early years and I still think it is a good app. On my own tours a few things kept
getting in the way: planning and changing a route on the phone felt fiddly, changing the plan halfway through a hike
was awkward, there were many questions when saving, the route could not be recalculated without mobile data, and once
my planned route got changed while the phone was in my pocket. OnPath is my attempt at an app that handles these
everyday situations calmly. I build it in the open so others can use it, check it and improve it.

## More on a tour

- **The map is ready before you lose signal.** After you plan a route, the maps along it (about 10 km either side) are
  loaded in the background, and on a tour the next areas are fetched before you reach them. The map never changes
  under you while you navigate.
- **The arrival time adjusts to you.** It starts from your fitness setting, and after about 15 minutes and 1 km it
  follows your actual pace on this tour – uphill, flat and downhill separately, breaks left out. It does not jump
  around with every GPS fix.
- **Nothing is re-routed without asking.** If you leave the route, the phone vibrates and asks whether you want a new
  route. The original stays visible in grey.
- **A tour ends only when you end it.** The lock-screen notification can pause a tour but has no stop button. Every
  recorded point is stored the moment it is recorded, and a tour continues after the phone restarts.
- **Cable car, bus or a lift from a friend.** Rides inside a tour are recognised from speed and height. At the end you
  choose whether to keep them, leave them out of distance and time, or split the tour. Nothing changes while you ride.
- **The route does not change by accident.** While navigating, touching the map does nothing. To edit, you slide a
  switch most of the way across. Even when planning, a short tap changes nothing – only holding a finger on the map
  adds or selects a point.
- **Few questions when saving.** Ending a tour is one tap on Save; the tour is named after the places it passed.
  Saving a planned route is three taps with a suggested name. Your current plan is kept even if you never save it.
- **A glance at the lock screen** shows the next junction, time to go and arrival, without unlocking.
- **You can follow by feel.** Before a junction the phone vibrates twice for left and three times for right. The
  screen switches off in your pocket and shows your position when you take the phone out.

## Planning

- Hold the map to set points; the route follows paths and roads for six activities: hike, touring bike, trekking bike,
  e-bike, MTB and E-MTB.
- For hiking you choose a level from T1 to T5 (SAC scale). Trails above that level are
  not used, and via ferratas never – useful when you plan for someone else, for example a family walk.
- If a point cannot be reached with the chosen activity (a bike destination at a mountain hut), the route ends where it
  can, the rest is drawn as a grey dashed line, and a red line in the plan says how far is missing. It does not quietly
  make a detour.
- The map marks ways by what the route planner will do with them for your activity: green good, black possible, grey
  too difficult, red not allowed.
- One line in the plan lists what to know about the route, for example "1.2 km pushing · steps · 2 steep sections";
  tapping it shows each place on the map.
- Elevation profile with gradient colours, way types and surfaces, and a time estimate with five fitness levels.
- Round trip, reverse, undo / redo.

## Finding places

- Offline search: street and house number, villages, huts, peaks, stations and trails from the downloaded map data,
  with German and Italian names. Results are one list, best match first.
- With a connection, online results (OpenStreetMap-based Photon) are added below. Only a rough position (about 1 km)
  is sent.
- Share a place from Google Maps (or any map link) to OnPath: the map moves there and marks it, and you can add it as
  start, stop or destination. Only the place's position and name are kept.
- From a place in OnPath you can open it in Google Maps, for example for opening hours, photos and reviews.

## Maps and your data

- Maps come from OpenStreetMap and download by themselves for the area you plan in and along your route (about 10 MB
  per area). "Clean up" removes areas you no longer need.
- Settings → Privacy lists everything that leaves the phone (for example which map area is needed – an area, not
  your position).
- Besides the GPX folder: export everything as one ZIP, deleted tours stay in a recycle bin for 30 days, and after a
  reinstall OnPath offers to restore from the folder.

## Compared with Komoot

As of **22 October 2026**, from Komoot's public help pages and shop. Apps change – please tell me if something here is
out of date.

| | OnPath | Komoot |
|---|---|---|
| Plan, edit and re-route without mobile data | Yes, on the phone | Needs internet; offline you can follow a route downloaded before ([help](https://support.komoot.com/hc/en-us/articles/10356476920986), [help](https://support.komoot.com/hc/en-us/articles/10605424981402)) |
| Continue from where you are after changing the route on a tour | Yes | Not offered; edit in the planner and start again ([help](https://support.komoot.com/hc/en-us/articles/10605424981402)) |
| Account | None | Required ([help](https://support.komoot.com/hc/en-us/articles/10194701438234)) |
| Maps | Free, download by themselves | One region free; regions €3.99, bundle €8.99, World Pack €29.99; Premium €59.99 a year ([shop](https://www.komoot.com/de-de/shop), [premium](https://www.komoot.com/premium)) |
| Getting your tours out | Automatic GPX copy to your folder, export all as ZIP | GPX per route, one at a time ([help](https://support.komoot.com/hc/en-us/articles/10115477099674)) |

Komoot does many things OnPath does not: an iPhone app and a planner in the browser, spoken directions, watches and
bike computers, tips and photos from other users, multi-day planning, and many years of use worldwide.

If you are looking for a full offline map and navigation app with many more features, OsmAnd is open source and
also calculates routes on the phone; the [BRouter](https://github.com/abrensch/brouter) Android app, whose routing
engine OnPath uses, does too. OnPath is narrower: planning and following a hike or bike tour on the phone, with as few
surprises as possible.

*Komoot is a trademark of komoot GmbH. OnPath is an independent project and is not affiliated with Komoot.*

## What is missing or limited

- Android 8 or newer only; no iPhone version, no planner on the computer.
- Installed from [GitHub Releases](https://github.com/happychriss/OnPath/releases), not yet from Google Play or F-Droid.
  Android may show a warning because the app is signed with a test key. The app then updates itself.
- The map service is still a test setup. The first map for a new area needs a connection and can take a few minutes.
- No spoken directions, no automatic loop suggestions, points are moved by deleting and adding (no dragging).
- Imported GPX tracks can be followed but not yet turned into an editable plan.
- English interface; map labels in German where available. Battery use has not been measured yet.

## Become a tester

I am looking for people who hike or ride, ideally in the Alps, and who are happy to report what does not work.

- You need an Android phone (Android 8 or newer) and some patience with a young app.
- Say hello in **[Discussions → Become a tester](https://github.com/happychriss/OnPath/discussions/1)**: your phone
  model, where you go, and what you do (hiking, bike touring, MTB …).
- Problems and ideas go to Discussions or [Issues](https://github.com/happychriss/OnPath/issues). In the app,
  Settings → Diagnostics → Export creates a file you can attach; nothing is sent automatically.

## Plans

- The app stays free and open source.
- Building and hosting the maps costs money. The plan is to cover this with voluntary support, publish the costs and
  give any surplus to a good cause. Running your own map service will be possible.
- Next: more field testing, Google Play and F-Droid, a permanent map service.

## Credits

Map data © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors (ODbL) · © OpenMapTiles ·
Terrain: Terrarium tiles (Mapzen / AWS Terrain Tiles; SRTM, Copernicus and others) · World overview: Natural Earth
(public domain) · Routing: [BRouter](https://github.com/abrensch/brouter) (MIT) · Map rendering: MapLibre Native (BSD).

## For developers

[Guide for contributors and AI assistants](CLAUDE.md) · [Requirements](docs/requirements.md) ·
[Project status](docs/project-status.md) · [Architecture contract](docs/architecture-contract.md).
Build: `./gradlew assembleDebug` (JDK 17+, Android SDK 35).

## License

Apache License 2.0, see [LICENSE](LICENSE).
