# Navigation cues – vibration, screen, lock screen

Status: 2026-10-30 (owner decisions of that day). One page for how OnPath tells you where to go while you follow a
route in Navigate. Three mechanisms work together: the **vibration** (felt in the pocket or on the handlebar), the
**screen** (map, rest view, pocket) and the **lock screen / notification**. Keep them consistent: when one of them
changes, check this page and the other two.

"Bike" = Bike, Gravel, E-bike, MTB, E-MTB (all five behave the same here). "Hike" = Hike T1–T5.

## 1. Vibration

All navigation vibrations go through `recording/Cues.kt` as *alarm* vibrations: they pass Do Not Disturb, silent mode
and the phone's touch-vibration setting, and are felt with the screen off. Settings → Phone setup → *Test vibration*
plays the real junction patterns.

### Junction cues (`navigation/JunctionCues`, `navigation/CueRule`)

| | Hike | Bike |
| --- | --- | --- |
| Setting | Settings → Navigation → *Vibrate at junctions*: Off / Always (default) / Screen locked | same setting |
| Pattern | 1 pulse = left, 2 pulses = right (250 ms pulses, 220 ms gap); roundabouts / U-turns by their side | same |
| Where | Only where one could go wrong (`CueRule`): clear turns, forks with a similar way, roundabouts, U-turns. Quiet: straight on along the clearly straightest way (≤ 45°, every other way ≥ 35° more), along the trail / street one is on (all other ways a smaller class, no step down, bend ≤ 75°), no other way to take | same rule |
| Way classes | hiking-mountain.brf's classes read as one class each: path (any surface) < track < service / cycleway < steps / pedestrian < streets (`CueRule.hikeClass`) – two paths at a fork are "similar" whatever their surface tags | the bike profiles' own classes (6 residential / service / cycleway, 4 path / track); for "a smaller one" three kinds of way (`CueRule.bikeKind`): track / path / steps (≤ 4) < street / bike way (residential, service, cycleway, paved track) < road (unclassified and up) – residential → service road or track → path is not a step down |
| Leaving for a smaller way | Leaving the way one is on for a smaller one while that way goes on (off the forest road onto a path) always vibrates – even straight on | same (owner 2026-10-30, after the hike): off the street straight on onto a field track / path, off the cycleway onto a path, off the main road into a side street |
| Lead | 25 s before the junction at the current speed, 25–60 m (speed unknown: 5 km/h → 35 m) | 8 s before, 30–100 m (speed unknown: 22 km/h → 44 m) |
| Speed | highest GPS speed of the last 10 s (`SpeedWindow`) | same |
| Close junctions | one cue when the next junction worth a cue is within 30 s of walking (at least 50 m) – before the first, with its pattern | within 12 s of riding (at least 50 m) |
| Off route | no junction cues (the off-route warning speaks), passed junctions never cue late | same |
| No junction data | GPX track / straight line: no junction cues. A route without way data (older session): every junction except slight bends | same |

Before 2026-10-30 the hike vibrated 40 m before *every* junction (none within 50 m of the one before). On the routed
South Tyrol hikes of `tools/routing/jvm-test/JunctionsTest.kt` that was 9 / 11 / 4 / 10 cues (Meran Obermais →
Tappeinerweg, Algund → Vellau, Meran 2000 → Ifinger hut, Meran → Dorf Tirol); now 8 / 11 / 4 / 9 – those routes are
mostly real turns; the quiet ones are bends of a road or track with a path branching off.

On the routed Hilden town rides of `tools/routing/jvm-test/BikeCuesTest.kt` (five rides, 19 – 37 junctions each) the bike's
"smaller way" rule turned one junction in all from quiet to a cue (MTB to Schloss Benrath: off the cycleway onto a
track, the cycleway bends 82° away); the cue counts stayed the same – that cue now comes before a close pair of turns
instead of at the second of them.

### Other vibrations (`recording/RecordingService`)

| What | When | Pattern | Setting |
| --- | --- | --- | --- |
| Off route | confirmed off the route (setting distance + GPS margin, 15 s and 25 m moved), and when a new way back arrives after a retry – **only after the route was reached once this tour** | long: 400 – 200 – 400 – 200 – 600 ms | *Off-route warning* (default on, 40 m) + *Vibrate when off route* (default on); hike and bike alike |
| Route not reached yet (far start, owner 2026-10-31) | never – "Route starts 31 km away" is a calm question (screen + silent notification) | none | – |
| Paused but moving | moved 150 m while paused | the same long pattern | always |
| Recording stopped / not saved | the tour could not be written | the same long pattern | always |

## 2. Screen

Only in Navigate while a tour follows a route. `ui/SmartScreen.kt` (`ScreenPolicy`, `SmartScreen`), `ui/PocketScreen.kt`
+ `ui/PocketLogic.kt`, `ui/RestView.kt`.

| | Hike | Bike |
| --- | --- | --- |
| Setting | Settings → Navigation → *Keep screen on while hiking* (default on) | Settings → Navigation → *Screen while cycling*: Always on / Smart (default) / Like the phone |
| Keep-on | on (switch on) / the phone's timeout (switch off) | Always on and Smart: on. Like the phone: the phone's timeout |
| Pocket (proximity sensor) | covered → screen off at once, uncovered → on again | only after **5 s covered without a break** (a hand reaching for the handlebar phone is ignored), then like the hike; uncovered → on, and the next cover waits 5 s again. Always on and Smart; not with Like the phone (nothing kept on) |
| Long in the pocket | covered 60 s: keep-on dropped, the phone sleeps normally (screen stays off until taken out; recording goes on) | same (60 s from the start of the cover) |
| No proximity sensor | plain keep-screen-on | same |
| Rest view | never | Smart only: far from the next event a dim black screen with the next turn's sign, distance, time left and arrival; the map draws nothing under it |
| When the map shows (Smart) | – | 20 s before the next event at the current speed (≥ 100 m) until 10 s after it; 30 s after any touch; while something needs you (off route, a question, GPS lost / waiting, paused, map moved by hand, a dialog) and 10 s after. Events = the turn card's turns, every junction except slight bends (also the quiet ones – owner: "every turn"), roundabouts, the destination; within 30 m = one event |

## 3. Lock screen and notification

| What | Hike and bike alike |
| --- | --- |
| Tour notification | Following a route: channel `navigation` (importance DEFAULT, so Android 14+ shows it on the lock screen; no sound, no vibration, no light, never a popup). A plain recording: the quiet channel `recording`. Same notification id – switching is an update, the service never restarts (`recording/TourChannels`) |
| Its text (`navigation/NavNotification`) | Title: the next junction within 500 m, "Left in 120 m" (10 m steps, 50 m above 500 m) – **every** junction BRouter names, also the ones that do not vibrate – else "8.2 km to go"; or the status: Paused / Searching GPS… / Off route. Below: time to go · arrival · distance left (the distance only once). Updated at most every 10 s. Buttons: Pause / Resume, never Stop |
| Off-route question | Channel `offroute-question` (importance HIGH, no channel vibration – the service vibrates itself): "You're 120 m off the route" · "Back on the route after 600 m · 350 m longer" with *New route* / *Keep original*, answerable on the lock screen, stays until answered or back on the route |
| Far start (route never reached yet) | Always the quiet `navigation` channel, **silent** (no sound, no vibration, no popup – also when the app is not on screen): "Route starts 31 km away" · "south-west (direct) · recording" with *Guide me* / *Just record*; "No way to the route found – recording continues" with *OK*. Status word stays "Recording", never "Off route" |
| Navigate over the lock screen | While a tour runs and the app is in Navigate, a short press of the power button shows Navigate without unlocking (`ui/LockScreenNav`, `MainActivity.showOverLockScreen`). Plan, My routes and Settings ask to unlock first |
| Notifications off | the setup guide and a chip in Navigate say what is missing: "No turns and no “New route?” on the lock screen" |

## Consistency notes

- The **vibration** filters junctions (`CueRule`); the **lock screen** and the **map wake-up** do not – they name / show
  every junction (the map: except slight bends). On purpose: looking costs nothing, buzzing at every junction does.
- Both activities use the same rule and the same speed window; only the timing (seconds of walking / riding) and the
  way classes differ.
- Pocket behaviour differs only in the 5 s wait on the bike; the 60 s sleep is the same.
- Tests: `JunctionCuesTest`, `CueRuleTest`, `PocketLogicTest`, `SmartScreenTest`, `TourChannelsTest`, `NavTextTest`, `FarStartTest`,
  `LockScreenNavTest`; real routes: `tools/routing/jvm-test/JunctionsTest.kt` (South Tyrol, hike + bike),
  `BikeCuesTest.kt` (Hilden town rides).
