# OnPath

An open-source Android app for everyday hikes and leisure bike rides. Choose a destination, check the route and
start navigating. You can also plan in your browser and take the plan with you.

Change your plans along the way, even without reception once the area's maps have downloaded. Your recording
continues while you edit, and navigation carries on from where you are.

**[Website & guides](https://onpath.earth/) · [Plan in your browser](https://plan.onpath.earth/) ·
[Try the Android app](https://onpath.earth/try/) · [APK releases](https://github.com/happychriss/OnPath/releases)**

No OnPath account · No ads · No analytics · German and English · Apache-2.0

> **Early beta.** OnPath is still young and needs testing on more phones and paths.
> Google Play testing currently requires a personal invitation and approval.
> Start with a short, familiar route. For long or remote outings, use OnPath alongside an established navigation tool.
> See [test access](https://onpath.earth/try/).

## Plan, check, go

1. **Choose your destination.** Search for a place or hold it on the map to plan from your location.
   Add stops or choose a different starting point.
2. **Check the way ahead.** Review time, distance, climbing, the elevation profile and route notices.
   The route follows your activity: Hike, Bike, Gravelbike, E-bike, MTB or E-MTB.
3. **Start your outing.** OnPath guides you and records your tour. Choose spoken directions or vibration at junctions,
   with the next turn and estimated arrival visible on screen.

The [browser planner](https://plan.onpath.earth/) offers a larger map, elevation profile, 3D terrain and a route
flyover. Transfer a plan by QR code, or pair your browser and phone to sync saved plans without an account.

## On the way

- **Change the plan and carry on.** Add a café, shorten the route or choose a different way home.
  Hold the planning control to unlock it, edit the plan, then return to navigation. Your recording continues.
- **Search and re-plan offline.** Maps and place data download automatically while you plan.
  In fully downloaded areas, search, navigation and route calculation work on the phone without a connection.
  Wait for downloads to finish before leaving reception; new areas still need internet.
- **Keep your attention on the path.** Spoken directions or vibration draw attention to junctions.
  The arrival estimate adapts to your pace. If you leave the route, OnPath asks before adopting a new one.
  Editing controls are protected against accidental touches.
- **Keep your own tours.** Recorded tours stay on your phone. Import GPX tracks to follow them, or turn them into
  an editable plan and check the calculated route. Export individual tours as GPX or everything as a ZIP.
  GPX backups and a 30-day recycle bin help you keep your work.

<table>
  <tr>
    <th>Plan and check</th>
    <th>Follow your route</th>
    <th>Add a stop along the way</th>
  </tr>
  <tr>
    <td><img src="https://raw.githubusercontent.com/happychriss/onpath.earth/main/assets/help/fertig-en.webp" width="220" alt="OnPath plan with route map, elevation profile, route notices and Let's go button"></td>
    <td><img src="https://raw.githubusercontent.com/happychriss/onpath.earth/main/assets/help/navi-en.webp" width="220" alt="OnPath navigation showing the next left turn, remaining distance and estimated arrival"></td>
    <td><img src="https://raw.githubusercontent.com/happychriss/onpath.earth/main/assets/help/ort-en.webp" width="220" alt="Adding a mountain inn as a stop while the tour recording continues"></td>
  </tr>
</table>

Real app screenshots; navigation is demonstrated with simulated location. More examples and instructions are on
the [website](https://onpath.earth/).

## A few useful details

- **Routes for your activity.** Planning considers paths, surfaces and access tags. Hiking has a SAC difficulty
  setting (T1–T5). Route notices highlight relevant entries such as steep sections, steps, pushing sections or ferries.
  They depend on OpenStreetMap data; missing tags and changing conditions still require your judgement.
- **Places from other apps.** Share a supported place link from Google Maps or a message into OnPath and use it as
  a start, stop or destination. From a place in OnPath, open Google Maps for information such as opening hours.
  Resolving short links and viewing online information may need a connection.
- **Continue after interruptions.** Recorded points are saved as the tour progresses, and a tour can continue after
  the app or phone restarts. Changing a plan does not end the recording.
- **Bus or cable car during a tour.** OnPath identifies possible rides. When you finish, choose how those sections
  count towards the saved tour.

## Try it and give feedback

You need **Android 8 or newer**. There is no iPhone app.

- **Google Play test:** request a personal invitation through the [test page](https://onpath.earth/try/).
  The app is not yet generally available on Google Play.
- **For technically experienced testers:** APKs are available under
  [GitHub Releases](https://github.com/happychriss/OnPath/releases). These test builds have their own updater.
  Play builds update through Google Play. Back up your tours before switching distribution channels:
  signatures may differ, so changing channels may require reinstalling.
- **Feedback:** use [Issues](https://github.com/happychriss/OnPath/issues) for reproducible problems or
  [Discussions](https://github.com/happychriss/OnPath/discussions) for questions and experiences.
  Say what you tried, what happened, what you expected, and your phone model and app version.
  Settings → Diagnostics → Export creates a diagnostic file; nothing is sent automatically.
  Review it before sharing.

Current limitations include the early map-service setup, downloads that can take a few minutes for a new area,
no automatic round-trip suggestions, and limited field testing across devices. Offline spoken directions require
a suitable text-to-speech voice installed on the phone. The [project status](docs/project-status.md) and
[backlog](docs/backlog.md) track ongoing work.

## How it works

| Part | Implementation |
|---|---|
| Android app | Kotlin, Jetpack Compose; navigation, route calculation and recording on the phone |
| Maps | MapLibre Native with OpenStreetMap-based vector maps in PMTiles; areas downloaded as needed |
| Routing | Embedded [BRouter](https://github.com/abrensch/brouter), with profiles for hiking and different bikes |
| Browser planner | TypeScript interface with shared Kotlin planning logic compiled for the web |
| Files and optional sync | GPX import/export and backups; optional end-to-end encrypted browser sync |

The Android app does not depend on Google Play Services. Recording a tour and planning with downloaded maps do not
require a server connection. Downloads, online search and optional browser sync do use network services.

Pairing enables encrypted sync of saved plans and imported tracks; recorded tours stay on the phone.
Without pairing, the app does not contact the sync service. Settings → Privacy explains the app's network requests;
the website has [privacy details](https://onpath.earth/privacy/).

## Build and contribute

Use **JDK 17** and **Android SDK 36**:

```sh
./gradlew assembleDebug
```

A source build has no map-service key by default. See the [map-hosting guide](docs/design/map-hosting.md) for
service configuration.

Start with the [contributor guide](CLAUDE.md), [requirements](docs/requirements.md),
[architecture contract](docs/architecture-contract.md) and [core features](docs/core-features.md).
The browser planner lives in [web/](web/); routing and map tools live in [tools/](tools/).

Useful contributions include testing on different phones, clear bug reports, wording and accessibility improvements,
and focused fixes. For a larger change, open an issue first to agree on scope.
Follow the repository's working agreement and run `tools/ci/gate.sh` before pushing a change.

## Why I am building it

I am Christian, a hiker, cyclist and developer. I used Komoot happily for many years, but wanted planning and changing
a route on the phone to feel simpler, including when there is no signal. OnPath is my attempt to make that everyday
outing easier: plan your way, change your mind and keep your tour, without a social feed or prompts to post it.

I build it in the open so others can inspect it, use it and help improve it.
[More about the project](https://onpath.earth/about/).
For my IT background and other projects, visit [44-2.de](https://44-2.de/).

Planning, navigation and recording remain free. Future paid extras are intended to help cover operating costs,
development work and a donation; details are not decided.
[Funding plans](https://onpath.earth/whats-next/).

## Credits

Map data © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors (ODbL) · © OpenMapTiles ·
Terrain: Terrarium tiles (Mapzen / AWS Terrain Tiles; SRTM, Copernicus and others) · World overview: Natural Earth
(public domain) · Routing: [BRouter](https://github.com/abrensch/brouter) (MIT) · Map rendering: MapLibre Native (BSD).

## License

Apache License 2.0, see [LICENSE](LICENSE).
