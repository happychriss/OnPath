# Navigation neu – voice, points, re-route, round trip (proposal for the owner, 2026-11-01)

Status: **owner 2026-11-01: "Ja, alles, in 3 Schritten"** – step 1 quiet + clear (Stopp = quiet, "Kein GPS" once,
"Bitte umdrehen" with the U sign), step 2 round trips + points, step 3 the new voice; numbers are starting values, tuned
after the owner's walks. Each step tested against the field tours, then on the Pixel.
Why: the owner's field day 2026-11-01 (Hilden, demo tour 59 + hikes 61–63, data in
`~/onpath-cells-work/field/2026-11-01-wanderungen/` on donald): "die Sprachansagen waren ziemlich unbrauchbar – da müssen
wir uns ein komplett neues Konzept überlegen … mal im Internet gucken, wie das andere machen". Research: OsmAnd and
Organic Maps / CoMaps source code, komoot / Locus / Garmin help pages, two studies (sources at the end).

## 1. What went wrong (from the data)

| # | Seen | Cause |
| --- | --- | --- |
| 1 | Almost every sentence on foot "In 50 Metern …", often "…, danach gleich …" | the lead rule: on foot ~40 s ahead clamped 40–80 m → always "50 m"; close junctions in parks merged into pairs |
| 2 | "links auf den Feldweg, danach gleich rechts" at a pedestrian crossing | two junction hints 8 m apart (onto the crossing, off it); every small way was "Feldweg" – *fixed*: JogRule + way words (00b9f006, 44544250) |
| 3 | Round trip (tour 61): after 46 m – 14 m from the start – 6 of 7 points "passed", later "Ziel erreicht" after 544 m | at a round trip's start the position also lies at the loop's end; a step back made the matcher jump to the closing part → progress ≈ the whole loop → every point "passed" by progress → the tour followed only "position → goal" |
| 4 | Tour 62: blue loop through the park not walked, "Ziel erreicht" while the screen said 54 m and "left" | after "Abseits der Route" the tour followed a new route along Neustraße; the original route ran as a loop through the park, its path along the park's east edge **2–25 m beside Neustraße** (OSM, measured on the track). On foot "on the route" = within ~30 m + accuracy, so the app took the owner (always on Neustraße) for being back on the original and switched to it at 17:42:55 – behind the loop: the point in the loop counted as done, 54 m left → "Ziel erreicht"; voice, vibration and screen spoke of two lines within 20 s |
| 5 | Re-route: "Neue Route" – no word and no sign that one must turn round, and where to | the sentence says only "new route" (+ the first junction when close); the U sign shows only in some cases |
| 6 | Replanning during a tour: the route vanished and did not come back | Plan hid the plan line while "followed" and drew the followed line grey / partial – *fixed*: no grey in Plan, the plan line always drawn (c2429f7a) |
| 7 | In Navigate one did not see where one had set a point | Navigate drew no plan points – *built*: points in Navigate, passed with ✓ (6b8751da) |
| 8 | Screen arrow ≠ voice ("Kehre rechts" for two park junctions) | the screen used the geometry, the voice the router's hints – *fixed*: one source (ScreenTurns, 4418dc36) |

## 2. How the good apps do it (short)

- **Two stages, the last one without a number.** Organic Maps: an early "In 200 Metern links." only when the turn is
  more than ~170 m away (on foot), then "Links." **without a distance** 15–25 m before the junction (bike 35–150 m).
  OsmAnd: no early stage on foot at all; "In 60 m links" ~22 s ahead, "Links" ~12 m before.
- **Strict junction filter**: only real forks; < 60° with only one way forward = no cue; turns < 15 m apart = one.
- **"Danach" only for close pairs**, and the second turn is then not announced again.
- **Off route = state + action**: how far off, the direction to the route, repeated with growing pauses (60 s, then
  ×2.5), "Wieder auf der Route" when back. "Bitte umdrehen" only with proof (heading > 135° wrong for 10 s). komoot
  (2026) re-routes **forward** to a later point of the route rather than saying "turn round".
- **Round trips**: guided in their order; "arrived" only when the progress along the route is at the end – never just
  near the end coordinate.
- **After a turn, when the next is far**: one calming sentence ("Weiter 1,2 km geradeaus").
- **Repeat on demand**: a tap repeats the last sentence (people ask again in ~30 % of cases).

## 3. Proposal

### A. When the voice speaks (one junction, the vibration stays at the junction)
- **On foot**: one sentence **without a distance**, ~10 s before the junction (15–25 m): "Links." · "Links auf den
  Weg." · "Rechts halten." An early sentence with a distance only when the junction is ≥ 170 m away from where the last
  sentence was said: "In 200 Metern links." (200 / 300 / 500 m / 1 km).
- **Bike**: early "In 200 Metern links." ~25 s ahead (at least 100 m, rounded to 50 m), then "Links." without a number
  ~8 s ahead (35–150 m). Both scale with the speed.
- **"Danach"** only when the second junction is < 100 m (foot) / 200 m (bike) after the first, without its distance:
  "Links, danach rechts." – the second is then not said again (its vibration stays). Never three in one sentence.
- **Quiet stretch**: after a junction, when the next is > 500 m (foot) / 1 km (bike) away: "Weiter 1,2 Kilometer
  geradeaus." once.

### B. Which junctions (one list for voice, vibration, screen – built: CueRule + JogRule + ScreenTurns)
- As today plus the Organic Maps walking filter: a bend < 60° with only one way ahead within 95° is no junction;
  junctions < 15 m apart are one (JogRule already does opposite pairs < 12 m / 20 m).

### C. Plan points during a tour (owner 2026-11-01: "Punkt bleibt offen")
- A point counts as **reached only when one was near it**: 40 m on foot, 60 m on the bike. No longer by progress along
  the line, nor by "clearly closer to the next point" (that rule of 2026-10-31 goes – the owner now wants points kept).
- **An open point is not skipped silently**: the guidance stays "to the next open point". Caught again on the line behind
  an open point (tour 62), the route leads **back to that point** ("Punkt 2 liegt hinter dir – 120 m"), as a new route
  from the position. Deleting the point (Undo, Points list) is the way to skip it.
- **Parallel ways** (tour 62: park path 2–25 m beside the street): while one lies well on the line being followed, the
  guidance never switches to another line (back to the original after a re-route) – only when one is clearly closer to
  it than to the current one over ~30 m of walking, and **never past an open point**.
- On the map (Plan and Navigate, *built*): numbered points, reached ones with a small ✓; the route blue, nothing grey.
- A loop the route makes to reach a point ends **at the point**: the point is the "return point" – its marker on the
  line shows where one turns back.

### D. Round trips
- At the start the matcher never jumps to the closing part of the loop before half of it is done (tour 61).
- "Ziel erreicht" only when **every point is reached** and the progress is in the last 50 m of the line – never by being
  near the goal coordinate alone. Started half way round: the rest of the loop is guided in order (komoot).

### E. Off route and re-route
- **Off route** (said once, then after 60 s, then 150 s …, never while standing still): "Abseits der Route. Die Route
  liegt rechts, 40 Meter." – the side from the bearing to the nearest point of the route relative to the walking
  direction (vor dir / rechts / links / hinter dir).
- **Back on**: "Wieder auf der Route."
- **New route**: the action first – "Neue Route. Umdrehen, dann nach 80 Metern links." when it starts behind you (> 135°,
  the U sign on the map at the turning spot); "Neue Route. Rechts halten." when it starts to the side; else "Neue Route.
  In 200 Metern links." – and the route is preferred that rejoins **forward**, towards the next open point.

### F. Repeat
- A tap on the turn card repeats the last sentence (also with the screen locked: the notification's button).

## 4. Owner's decisions and additions (2026-11-01)
1. All proposals taken ("Ja, alles, in 3 Schritten"): on foot "Links." 15–25 m before; off route with side + distance;
   a skipped point leads back at once (deleting it skips it) – try first, tune after walks.
2. **Radius rule** (owner's idea): a junction is said only when the direction really changes – the heading entering a
   circle of ~20 m (bike ~30 m) around the junction against the heading leaving it, and a real other way there. Changing
   the side of the street, a zigzag without a junction, an offset: nothing (generalises JogRule).
3. **"Navigation Stopp" = the phone is quiet**: no voice, no junction vibration, no off-route warning – also with the
   screen off (replaces review H1's "Plan off screen = warnings on"). Walking off without "Weiter" (app off screen):
   after ~150 m the guidance comes back by itself and says so (chosen for the owner; he can object).
4. **"Kein GPS" once**: said again only after GPS was good for a while and one walked on.
5. **Re-route that starts behind you**: "Neue Route. Bitte umdrehen." + the U sign on the map + the turn card shows
   "Umdrehen" – after an automatic re-route the screen must always show where to go ("sieht man plötzlich gar nichts").
6. ☰ menu: "Meine Routen" first (later, backlog).
7. **Turn hints: one setting "Aus · Vibration · Stimme"**, default Stimme – either / or, never both (*built* bdf5ce87).
8. **On foot "Gleich links." ~10 m before**, no distance (*built* ae7922f3); off the route on foot from 25 m + accuracy
   (*built* f2b6561d); the jog rule works on the phone now (angle from the shape, *built* 5820ef33).
9. **A point is reached within 25 m on foot / 40 m on the bike, only from a fix good to 20 m** (*built* 7d9adfb9).
10. **Points like Komoot**: start dark green, stops near black, goal dark red, no ring – map (Plan + Navigate) and list
    (*built* 598c7d50).
11. **Standing still = frozen** (owner: "großartige Idee"): the phone's step detector (no Google services) – no steps for
    ~30 s on foot → the position is held: no track points, no jumping dot, no off-route / "Kein GPS" / voice /
    vibration, the time counts as a break; steps again → GPS as usual. Navigation pauses with it. Bike: as today (GPS
    speed). Tour 63's restaurant (53 min sitting) recorded 1.8 km of zigzag, up to 188 m from where he sat. Next to build.

12. **Rosellen bike replay (owner 2026-11-01)** – built the same evening: the bike voice in two stages ("In 150 Metern
    links." ~25 s ahead, "Gleich links." ~8 s ahead, "danach" within 100 m, a turn just ahead never lost in the pause);
    leaving the route on the bike told by direction + speed (20 m, ≥ 9 km/h, confirmed in ~4 s); every new route said
    ("Neue Route. Bitte umdrehen."); the off-route question at once with a spinner on "Neue Route"; with the voice on the
    off-route warning is spoken, not vibrated; a turn onto another kind of way is a cue even with no other way (dual
    carriageway); the radius rule's first half (a corner drawn as a curve); out-and-back to a point: "Umdrehen";
    arrival only with every point reached and progress at the end; a point "reached" at the closest approach.

## 5. How it is tested
- The field tours as replays on the JVM with the real routing data (tracks of tours 59–63 → the sentences, the screen
  turns, reached points, arrival) – each problem in §1 a pass / fail case (like `HildenVoiceTest`).
- Unit tests per rule (timing, filter, chaining, off-route side, reached radius, round-trip jump, arrival).
- Release-Rundgang: a round trip with a skipped point and an early step back at the start.

## Sources
OsmAnd `AnnounceTimeDistances.java`, `VoiceRouter.java`, `RoutingHelper.java` (github.com/osmandapp/OsmAnd), docs
docs.osmand.net (voice-prompt-triggering); Organic Maps `turns_notification_manager.cpp`, `turns_sound_settings.cpp`,
`pedestrian_directions.cpp` (github.com/organicmaps/organicmaps), CoMaps (codeberg.org/comaps); komoot help center
(navigation FAQ, voice issues, feature releases 2026); Locus Map manual (navigation settings, off-route alerts); Garmin
Edge 850 manual (re-routing a course); Google blog 2019 (detailed walking voice guidance); Golab 2021 (TU Wien, "It's
also about timing"); Rehrl et al. 2010 / 2014 (Salzburg Research).
