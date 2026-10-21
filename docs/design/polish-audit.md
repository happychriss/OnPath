# OnPath – UI smoothness & polish audit (read-only, 2026-10-20)

Status 2026-10-20: **done in 0.7.13:** A1 (hold ring fades in after ⅓ of the hold), A2 (padding re-applied after the follow transition), A3 (Navigate cards glide in/out), A6 (profile scrub: eased first move, gesture survives new fixes), A7 (dark status-bar icons), B1 (heading turned at display rate with ease-out, MapLibre compass animation off, deadband 2°). Open: A4, A5, A8–A18, B2–B6 – owner picks.

I read the code at `main` (0.7.11) and checked MapLibre 13.7.1's own behaviour in the bytecode of the cached AAR
(`LocationComponentConstants`, `LocationAnimatorCoordinator`, `LocationComponent`, `LocationCameraController`).
Items marked **[verified]** are confirmed in code or library bytecode. Items marked **[guess]** are likely but need
a look on the phone or a profiler trace.

**Overall:** the camera itself is mostly animated (fitTo 800 ms, flyTo 700, centreOn 600, north-up 600,
setCameraMode 750). The plan sheet animates its height (`animateContentSize`), and the slide switch springs back. The
jerkiness comes from four other things:
1. Compose UI that appears or disappears with no transition: the whole app has only 3 animations
   (`MapHost.kt:248`, `SlideToUnlock.kt:101`, `Panels.kt:92`).
2. Map rotation in heading-up arrives in steps.
3. A real MapLibre quirk: the heading-up padding is ignored while the camera is moving into follow mode.
4. A few recomposition storms tied to camera frames.

---

## A. Quick wins (S effort, low risk)

### A1 · HIGH – Hold ring flashes at the start of every pan and every quick tap (Plan)
- **Where:** `ui/MapHost.kt:162-173` (ring armed on `ACTION_DOWN`), `:241-260` (`HoldRing`), `ui/MapGestures.kt:75-81`
  (`buzz` = ONE for any press on the editable map).
- **What the user sees:** every finger-down on the Plan map shows the blue ring at once. A pan cancels it after 8 dp
  (1–3 frames, a flicker). A quick tap shows it about ⅓ full, then it vanishes. Release always cuts it off hard.
- **Why:** `holdAt` is set on `ACTION_DOWN` and cleared when the finger moves past the slop or is released. There is
  no delay and no fade.
- **Fix:** show the ring only after about 100–120 ms of stillness and fade it in over 80 ms. Keep the fill ending
  exactly at `HOLD_MS`, so the vibration timing does not change. Fade it out over 120 ms instead of removing it.
- **Effort:** S · **Risk:** low (gesture rules unchanged).

### A2 · HIGH – Heading-up padding is ignored when following starts → the dot is sometimes centred, sometimes 30 % lower **[verified]**
- **Where:** `map/MapController.kt:349-351`. `setCameraMode(mode, 750 …)` is followed straight away by
  `paddingWhileTracking(..., 750)`.
- **Why:** in MapLibre 13.7.1, `LocationComponent.paddingWhileTracking` returns early with "call is ignored because
  the camera mode is transitioning". `LocationCameraController.transitionToCurrentLocation` sets `isTransitioning`
  whenever tracking starts from NONE with a known location. That is the case for Plan → Navigate, locate tap, back
  from free look, after a profile scrub, and back to the app after a gesture. The padding is applied only when
  `setCamera` runs while the camera is already tracking (orientation toggle, `onReturnToApp` while following).
- **What the user sees:** after "Start navigation" the position stays centred (padding 0 is left over from
  `resetNorth`). Later, after an orientation toggle, the map slides 750 ms to put the dot 30 % lower. The layout is
  inconsistent and moves when you don't expect it.
- **Fix:** pass an `OnLocationCameraTransitionListener` to `setCameraMode`, and call `paddingWhileTracking` in
  `onLocationCameraTransitionFinished` (and in `onLocationCameraTransitionCanceled`). Better still, ease the camera
  padding together with the transition, so the dot glides once to its final place.
- **Check on the phone:** logcat should show the "ignored because … transitioning" line after Start navigation.
- **Effort:** S · **Risk:** low.

### A3 · HIGH – Navigate cards pop in and push each other (turn card, off-route banner, re-route question, update banner)
- **Where:** `ui/NavigateScreen.kt:169-176` (TurnCard and OffRouteBanner above the status card, in a `Column`
  with `spacedBy`), `:180-183` (re-route question above the map controls), `ui/MapScreen.kt:160-163` (UpdateBanner
  above the footer).
- **What the user sees:** about 500 m before a turn, an 86 dp card appears in one frame and the status card jumps
  down. It jumps up again after the turn. When the question card appears, the map buttons jump up by the card's
  height.
- **Fix:** wrap each one in `AnimatedVisibility(expandVertically + fadeIn, ~220 ms, FastOutSlowIn)` with the
  matching exit. The owner rule "no message disappears by itself" is untouched: this only changes how they appear
  and leave.
- **Effort:** S · **Risk:** low.

### A4 · HIGH – Plan sheet "breathes" on every edit; the old line stays on the map unchanged while routing
- **Where:** `ui/PlanPanel.kt:167-169` (mini profile and full profile hidden while `ui.routing`, progress bar
  inserted), `:249` (difficulty badge hidden while routing). `AppViewModel.recalc` (`:961-998`) keeps the old
  `route`, so there is always something to show.
- **What the user sees:** after each hold or undo, the sheet shrinks by about 55 dp, a progress bar appears, then the
  sheet grows back when the route arrives. The map controls ride up and down with it. On the map, the old blue line
  stays fully opaque until the new one replaces it in one frame.
- **Fix:**
  - Keep the old profile and badge, dimmed (alpha 0.5, animated).
  - Put the progress bar in a slot that always has the same height (or draw it over the profile's top edge).
  - Dim `planned-route` on the map while routing, using `lineOpacity` with a 200 ms `TransitionOptions`.
- **Effort:** S · **Risk:** low.

### A5 · MEDIUM-HIGH – Navigate overlay rebuilds on every frame while the map rotates
- **Where:** `ui/MapScreen.kt:100` (`onCamera` sets `bearing` and `cameraTick` on every camera-move frame), `:112`
  (`bearing` passed by value into `NavigateMode`), `ui/NavigateScreen.kt:461,505` (used only for the off-route arrow).
- **Why:** each rotation frame (heading-up: 500 ms of animation per heading update, about 1 Hz) recomposes
  `NavigateMode`. That rebuilds `NavUi`: `subList` profile (`:499`), position strings (`:516-521`), and a new
  `ToGo`. `NavStatusCard` and `ElevationProfile` recompose too. `remember(points)` then compares the whole remaining
  list element by element (`ui/ElevationProfile.kt:57`), and the Canvas redraws every gradient segment (`:135-138`).
- **Also:** `cameraTick` is read in `MapScreen`'s body (`:115`), so `MapScreen` recomposes on every camera frame in
  both modes, even with no hold menu open. That part is cheap, because the children are skipped thanks to strong
  skipping, but it is unnecessary.
- **What the user sees:** dropped frames while the map turns, plus CPU and battery cost while you look at the phone
  **[guess on size: measure with a Perfetto / Compose recomposition-count trace]**.
- **Fix:**
  - Pass `bearing` as a `() -> Float` and rotate the arrow in `Modifier.graphicsLayer { rotationZ = … }`.
  - Same for the compass needle (`ui/MapFooter.kt:165` uses `.rotate()`, which reads in composition).
  - Read `cameraTick` only inside `MapOverlayHost` / `Anchored`, and only when a menu is open.
  - Decimate the profile to about one point per pixel and cache the Path.
- **Effort:** S–M · **Risk:** low.

### A6 · MEDIUM-HIGH – Profile scrub in Navigate: the first touch jumps the map; a new GPS fix can break the scrub
- **Where:** `map/MapController.kt:574-576` (`panTo` = `moveCamera`, instant), `ui/AppViewModel.kt:187-201`.
  Scrub loss: `ui/NavigateScreen.kt:499` (a new `subList` each time the start index moves), `ui/ElevationProfile.kt:57,66,82,93`
  (`pointerInput(data)` and `remember(points)` are keyed on the data).
- **What the user sees:** touching the profile moves the map, possibly several km, in one frame.
  **[guess, strong]** While walking, the remaining profile changes with the next fix. That restarts `pointerInput`
  in the middle of the drag, so the gesture goes dead until the finger is lifted. `onDragEnd` / `onRelease` never
  fire, so `navScrubEnd` is never called: the dot stays and the map does not go back to following after 4 s.
- **Fix:**
  - Ease the first `PanTo` (250–300 ms), then use instant moves while dragging.
  - Use `pointerInput(Unit)` with `rememberUpdatedState(data)`, and freeze the profile list while a finger is down.
  - Add a fallback timeout for `navScrubEnd`.
- **Effort:** S · **Risk:** low.

### A7 · MEDIUM – Status-bar icons turn white over the light map when the phone is in dark mode **[guess, high confidence]**
- **Where:** `MainActivity.kt:77` (`enableEdgeToEdge()` with the default `SystemBarStyle.auto`), `ui/Theme.kt:28-68`
  (light colour scheme only), `res/values/themes.xml` (Material.Light parent).
- **Why:** `auto` picks icon colour from the *system* dark mode, not from the app. The app is always light, so in
  dark mode the clock and battery icons are light on the light map and search bar.
- **Fix:** `enableEdgeToEdge(SystemBarStyle.light(TRANSPARENT, TRANSPARENT), SystemBarStyle.light(TRANSPARENT, TRANSPARENT))`.
  A real dark theme is a separate product decision.
- **Effort:** S · **Risk:** low.

### A8 · MEDIUM – Slide-to-plan finishes with a snap and no feedback
- **Where:** `ui/SlideToUnlock.kt:99-101` (on success `offset = 0f` and then `slid()` in the same frame),
  `ui/MapScreen.kt:108-113` (Plan / Navigate UI swapped in one frame).
- **What the user sees:** the thumb jumps back to the start, and the whole Navigate UI is replaced instantly while
  the camera still animates (600 ms north-up). There is no haptic at the threshold or at completion.
- **Fix:**
  - Leave the thumb at the end.
  - Give a `CONFIRM` haptic (API 30+, `LONG_PRESS` as fallback), and optionally a light `CLOCK_TICK` when crossing 85 %.
  - Cross-fade or slide the mode UI with `AnimatedContent(vm.mode)` over about 250 ms.
- **Effort:** S · **Risk:** low.

### A9 · MEDIUM – Sheet swipe snaps back with no animation and gives no feedback upwards
- **Where:** `ui/Panels.kt:71-85`. `drag` follows the finger down only (×0.5) and is set to `0f` on release.
- **What the user sees:** after a swipe the sheet jumps back from its offset in one frame, while its height starts
  animating. A swipe up does nothing visible until release.
- **Fix:** use an `Animatable` for the offset and `animateTo(0f, spring())`; add a rubber band upwards.
  Bigger option: `AnchoredDraggable` with velocity and three anchors (MINI / COMPACT / FULL), see B4.
- **Effort:** S · **Risk:** low.

### A10 · MEDIUM – Map controls change height when the eye or undo button appears
- **Where:** `ui/MapFooter.kt:118-128`, `ui/MapScreen.kt:86-89` (undo only when `canUndo`, eye only with a
  route or track).
- **What the user sees:** the button pill grows or shrinks by 48 dp in one frame after the first edit or after Clear.
  The compass also switches between the needle and the arrow icon instantly (`MapFooter.kt:164-165`).
- **Fix:** follow the owner rule "nothing appears or vanishes by surprise" (docs/ux-two-modes.md, Menus): undo
  always visible and greyed when there is nothing to undo; eye with `AnimatedVisibility`; `Crossfade` for the
  compass icon.
- **Effort:** S · **Risk:** low.

### A11 · MEDIUM – Hold menus appear with no transition and trail the map by a frame
- **Where:** `ui/MapOverlays.kt:116-148` (position recomputed in composition from `cameraTick`),
  `ui/MapScreen.kt:100,115`.
- **What the user sees:** the menus pop in and out. While panning they "swim" a little behind their point.
- **Fix:** `scaleIn(transformOrigin = anchor) + fadeIn` over 120–150 ms. Place the menu in a `Modifier.layout {}`
  that reads a camera-tick State, so only layout runs, not recomposition.
- **Effort:** S · **Risk:** low.

### A12 · LOW-MEDIUM – Full-screen views and the message bar pop in
- **Where:** `MainActivity.kt:125-168` (My routes, Time details, Settings), `ui/search/SearchHost.kt:36-37`,
  message Snackbar at `MainActivity.kt:174-179`, ActivitySwitchSheet at `NavigateScreen.kt:189`.
- **Fix:** `AnimatedVisibility` (screens slide in from the right with a fade, about 250 ms; the Snackbar slides down
  with a fade; the activity sheet slides up). Predictive back can come later.
- **Effort:** S · **Risk:** low.

### A13 · LOW – Startup goes through three colours: white → beige → land colour **[verified colours]**
- **Where:** `res/values/themes.xml` (`windowBackground` white). MapLibre's default `foregroundLoadColor` is
  0xFFF0E9E1, shown until the style loads (`MapHost.kt:61` creates `MapView(context)` without options). The style
  background is `#f6f4ec`.
- **Fix:** set `windowBackground` to #F6F4EC, and create the map with
  `MapView(context, MapLibreMapOptions.createFromAttributes(context).foregroundLoadColor(0xFFF6F4EC))`.
- **Effort:** S · **Risk:** low.

### A14 · LOW – Haptics feel buzzy and are used in only two places
- **Where:** `ui/MapGestures.kt:98-101` (30 ms waveforms), `ui/MapHost.kt:210-221` (raw `Vibrator`). Nothing else in
  the UI gives haptics (searched `performHapticFeedback` / `vibrate`).
- **Fix:**
  - On API 30+, use `VibrationEffect.startComposition().addPrimitive(PRIMITIVE_CLICK)` for a hold, and two clicks
    with a gap for a route point. Fall back to the current waveform.
  - Add one small consistent set elsewhere: `CONFIRM` for slide complete and Take new route, `TICK` when the sheet
    snaps.
  - **[guess]** Raw `vibrate()` without usage attributes may ignore the user's "touch vibration" setting; the
    primitives respect it.
- **Effort:** S · **Risk:** low.

### A15 · LOW – Heavy lookup on every finger-down in Plan **[guess on cost]**
- **Where:** `ui/MapHost.kt:165-171` runs `waypointAt` and `findPoiAt` on `ACTION_DOWN`. `poi/PoiTap.kt:19-25`
  builds `style.layers` (JNI wrappers for about 117 layers) and runs `queryRenderedFeatures` on every touch.
- **What the user sees:** the first frame of a pan can come late.
- **Fix:** cache the POI layer ids per style, and run the query about 100 ms into the hold (together with A1), only
  if the finger is still down.
- **Effort:** S · **Risk:** low.

### A16 · LOW – Swiping the sheet down over the full profile scrubs instead
- **Where:** `ui/ElevationProfile.kt:82-92` (`detectDragGestures` takes both axes) inside the vertically draggable
  sheet (`ui/Panels.kt:72-85`, used at `ui/PlanPanel.kt:172`).
- **Fix:** use `detectHorizontalDragGestures`, so vertical drags reach the sheet.
- **Effort:** S · **Risk:** low.

### A17 · LOW – Lines don't quite meet the moving dot
- **Where:** `ui/MapScreen.kt:261-265` (guide line and recorded track drawn from the raw fix).
- **What the user sees:** the dot glides about 1.1 s between fixes (MapLibre animates over the time between
  updates × 1.1, at most 2 s **[verified]**). The orange track tip and the red dashed guide jump once per fix and
  briefly leave a gap to the dot.
- **Fix:** accept it, or draw the last segment from the previous fix. MapLibre 13.7.1 has no listener for the
  animated position.
- **Effort:** S · **Risk:** low.

### A18 · LOW – Starting navigation can show one frame with no line or with both lines **[guess]**
- **Where:** `ui/MapScreen.kt:229-244`. The planned line is hidden, and the followed line is set and restyled, in
  three separate effects that can land in different frames.
- **Fix:** one `MapController.showFollow(...)` call that swaps both lines in the same frame.
- **Effort:** S · **Risk:** low.

---

## B. Bigger work

### B1 · HIGH – Heading-up turns the map in steps instead of continuously **[verified mechanism]**
- **Where:** `map/HeadingEngine.kt:38-41` (`DEADBAND_DEG = 3`, alphas 0.85 for the route / 0.8 for GPS / 0.12 for
  the compass), `:136-143` (step + deadband), `:210-213` (route bearing emitted once per fix). MapLibre animates each
  compass value over a fixed `COMPASS_UPDATE_RATE_MS = 500` **[verified]**, while the position animates over about
  1.1 s per 1 Hz fix.
- **What the user sees:**
  - At a bend in route-up mode, the map turns for 0.5 s, stops for 0.5 s, and turns again. Rotation and sliding are
    out of step ("turn, then slide").
  - Standing on the compass, the map ticks in jumps of 3° or more.
  - `setRouteHeading` can run twice per fix (`MapScreen.kt:257`, keyed on both `shownAlongM` and `location`), so
    the smoothing step is applied twice.
- **Fix:**
  - HeadingEngine produces its own smooth heading at display rate (Choreographer), with a critically damped spring
    towards the target, about 0.8–1 s for route/GPS and faster for the compass.
  - Set `LocationComponentOptions.compassAnimationEnabled(false)`, so MapLibre does not animate a second time.
  - Use the deadband only as a start threshold: begin turning after 3°, then follow smoothly until within 0.5°.
  - Emit once per fix.
- **Effort:** M · **Risk:** medium (navigation-critical; `HeadingFilter` already has unit tests to extend).

### B2 · MEDIUM – Plan ⇄ Navigate switch: no transition for the UI
- **Where:** `ui/MapScreen.kt:108-113`, `ui/AppViewModel.kt:1327-1334`.
- **What the user sees:** top bar, sheet, button size (48 → 56 dp, `MapFooter.kt:117`) and cards all change in one
  frame, while the camera animates for 600–750 ms.
- **Fix:** `AnimatedContent(vm.mode)`. Plan's sheet slides down and the search bar slides up. Navigate's status card
  slides in from the top and the slide switch from the bottom. Buttons animate their size (`animateDpAsState`).
  Pair this with A2 so the camera arrives at its final framing in one move.
- **Effort:** M · **Risk:** low.

### B3 · MEDIUM – The dot "slides" after a gap; it jitters while standing still
- **Where:** `MainActivity.kt:247-256` (app GPS listener stopped in `onStop`, restarted in `onStart`),
  `ui/MapHost.kt:74` (`onReturnToApp` re-centres on the last, old fix), `map/MapController.kt:308-311`.
- **What the user sees:** out of the pocket, the camera first moves to the old position. The next fix then animates
  the dot and camera over up to 2 s **[verified cap]** across the distance walked; it is instant only when the jump
  leaves the screen. Standing still, raw 1 Hz GPS noise makes the dot wander inside the accuracy circle.
- **Fix:**
  - Seed the app tracker with the service's latest fix on `onStart` (the service records all the time).
  - For the first fix after a gap of more than about 5 s, skip the animation (`trackingAnimationDurationMultiplier`
    near 0 for that one update, or `forceLocationUpdate(listOf(loc), false)` after resetting).
  - Add a display filter: below 0.5 m/s, don't move the dot while the new fix lies inside the shown accuracy.
- **Effort:** M · **Risk:** low–medium (display only; recording is untouched).

### B4 · MEDIUM – A real bottom sheet
- **Where:** `ui/Panels.kt:61-106`, `ui/PlanPanel.kt:146-191`, `ui/MapScreen.kt:165-175`.
- **What the user sees:** discrete steps on swipe, and an instant swap between plan sheet, place card and track
  panel (`MapScreen.kt:173-174`, `PlanBottom` `:183-195`).
- **Fix:** `AnchoredDraggable` sheet with velocity, rubber band and three anchors, plus `AnimatedContent` (fade and
  size) when the sheet content changes. This replaces A9.
- **Effort:** M · **Risk:** low–medium (Paparazzi snapshots will change).
- **Done (sheet part, after field test 0.8.9):** the plan sheet follows the finger, settles with a spring by distance
  and fling (`SheetDrag`), nested scroll with the Points list. `AnimatedContent` between panels still open.

### B5 · MEDIUM – Main-thread work exactly when Navigate starts or a re-route arrives
- **Where:** `ui/NavigateScreen.kt:464-469` (`FollowProgress`, `TurnDetector.turns`, `Geo.length`,
  `RouteTimes.of` inside `remember(line)` during composition), `ui/MapScreen.kt:252` (`NavLine` likewise).
  `MapScreen.kt:265` re-sends the whole recorded track to MapLibre for every new point; together with
  `RecordingService.publish` copying the whole track (`RecordingService.kt:655`), this cost grows with the length of
  the tour.
- **What the user sees:** **[guess]** a hitch at Start navigation or after "Take new route", during the 750 ms camera
  animation, and slow growth of per-fix work over long days.
- **Fix:**
  - `produceState` on `Dispatchers.Default` for the line-derived objects.
  - Recorded track as two sources: a "body" refreshed every N points and a short "tail" refreshed every fix.
- **Effort:** M · **Risk:** low.

### B6 · LOW-MEDIUM – Map-content pops in the style and data **[guess, needs a look on the phone]**
- **Labels during heading-up:** MapLibre re-places labels during rotation, so labels fade in and out (default 300 ms
  fade) as the map turns. Possible mitigations: fewer, more important labels in Navigate, `text-padding` lower for
  top labels, or `symbol-sort-key` so important ones win consistently (`tools/make_style.py:482-627`).
- **Large GeoJSON overlays at style load:** `paths`, `ways`, `trails` and `pois` (style sources, `onpath.json`)
  are parsed at style load, so they can appear after the base map and cost memory. Long term, build them into
  PMTiles in the pipeline (needs the laptop's agreement).
- **Instant toggles:** `setRouteOverlaysVisible` (eye, `MapController.kt:367-379`), the search ring and the turn
  arrow change visibility or data instantly. A 150 ms `lineOpacity` / `iconOpacity` transition would soften the eye.
- **Sharp corners on paths:** already known, see the pipeline smoothing proposal.

---

## C. Smaller "un-Apple-like" details (low impact, S each)
- **Spacing:** side margins mix 12 and 16 dp: Navigate top cards 12 (`NavigateScreen.kt:166`), slide switch 16
  (`:185`), footer 12/16 (`MapFooter.kt:58`), banners 12. Pick one grid (16).
- **Typography:** raw `fontSize` sits next to theme typography: `Stat` 28/18 sp (`Panels.kt:111`), menu items 16 sp
  (`MapOverlays.kt:242`), 9 sp attribution and scale (`MapFooter.kt:61,85`), 11–13 sp in route cards
  (`Screens.kt`). Map these to `MaterialTheme.typography`.
- **Hard-coded colours** that won't follow a future dark theme: profile labels and grid (`ElevationProfile.kt:68-69`),
  ErrorBox (`PlanPanel.kt:320-324`), white TourCard (`Screens.kt:322`), slide track (`SlideToUnlock.kt:75`).
- **Lists:** search result lists have no keys and no `animateItem()` (`SearchScreen.kt:138-160`). Routes have keys
  but delete and rename don't animate (`Screens.kt:263`). A `SimpleDateFormat` is created for every row
  (`Screens.kt:333`).
- **Renderer flags:** `setCamera` sets `renderMode` and `isCompassEnabled` on every call (`MapController.kt:336,346,355`).
  Setting them only when they change is cheap insurance against puck re-layout **[guess]**.

## D. Checked and already fine
- Camera moves are animated (`MapController.kt:349,387,397,579,587,592`). North-up on leaving Navigate is animated.
- There is no frame cap while the map is visible (owner rule), and the sheet height animates.
- Hold vibration fires exactly when the ring is full; menus never close on a timer.
- POI details use `ModalBottomSheet` and the dialogs are standard Material 3, so both are animated.
- The routes list has stable keys.

## Suggested order
1. A2, A1, A3, A4, A5 (one small batch, all S) – the most visible gain.
2. B1 (smooth heading) – the biggest "Apple feel" item in Navigate.
3. A6–A12 and B2 (transitions everywhere).
4. B3–B5, then C.
