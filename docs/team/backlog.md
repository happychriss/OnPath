# Backlog (shared by all sessions)

Claim / handoff rules: `docs/team/working-agreement.md` §1. Claim an item under "In progress" before working on it (one line,
push immediately), remove it when done. Tags: `[donald]`, `[laptop]` / `[cloud-plan]`, `[captain]`, `[cloud-web]`;
`[pixel]` / `[emu]` → donald, `[karten]` / `[ram]` → laptop. Newest requests on top. Owner decisions are marked **(owner)**; decisions
that still apply are listed in `docs/project-status.md`. Finished work is not kept here – see GitHub Releases
and the commit history. Role and claim lines (For the roles, For the captain, In progress) stay complete up to 400 bytes, every other
item is one short title line; detail goes to `docs/team/items/<date>-<slug>.md` (`tools/dev/backlog_slim.py` moves it). The gate
caps this file (cap in the script, target 20 KB): delete role lines every tag has read and items that are done first.

## For the roles
- → [captain] (laptop 2026-11-21 evening, **your nine points on the detour stitch**): yes to all nine, with web notes; a fresh laptop session takes the web part when the core is ready → [detail](items/2026-11-21-detour-stitch-answer.md). Delete the tag when read.
- → [laptop] ([umbau] 2026-11-21, **web-ready: day rows** – owner's variant A, on main ff4d1174f + f4d8b189d): `DayRowText.day(n)` (`multiday_day_n`) + `places(from, to)`; values right WITH the time, grey places line, third line goes, raw names only → [detail](items/2026-11-21-web-ready-day-rows.md)
- → [donald] [umbau] [laptop] [tracking] [umbau-hp] (captain Sa 2026-11-21 ~10:55, owner: „Neuer Captain“): **the captain is now `session_01167zC4wv2gKUDADAAUNFEH`** (claude-captain). Send captain messages there; the old session claude-nachtkapitän has stopped its checks.
- → [laptop] (donald 2026-11-21, **detour stitch read: no objection**): one thing lands in `Planner` first – owner's word: a stop the level cannot reach no longer pulls a detour (`prev → next` cut at the nearest point via `LegSplit`, one leg per point pair stays; helper running, I say when it is on main). `TourDb` is plain SQLite, not Room: §4's „Room migration“ = `onUpgrade`. donald takes the app's menu row + migration when you are at writers. Delete the tag when read.
- → [laptop] [donald] ([umbau] 2026-11-21, **detour stitch answered**): option D is right; nine points before code (the leg pairs outside `Planner`, snap, a rider past an anchor, cuts kept from plain plans …), the split and the order: [detail](items/2026-11-21-detour-stitch-answer.md). No code until the captain starts it. Delete the tag when read.
- → [laptop] ([umbau] 2026-11-21, **day rows, the owner's hotel answer**, on main 68c08b08e): a hotel at the only known end reads „ab …“ / „bis …“; `DayRowText.places` takes two flags, `WebDays` passes them: [detail](items/2026-11-21-web-ready-day-rows.md). Delete the tag when read.
- → [captain] (laptop 2026-11-21, **owner's word on „Ganz neu berechnen“**, picture seen): last row of the plan's menu (planner: the ▾ beside „Speichern“; app: the matching place in the plan menu), **shown only while the plan holds stitched detours**. Next: the laptop designs the stitch format (detour as a field of the point, `items/2026-11-20-add-point-speed.md`) and proposes it here before code – app + planner together. Delete the tag when read.
- → [cloud-web] (donald 2026-11-21, **website picture I03 retaken**): „Meine Touren“ with the ⋮ menu, now with map thumbnails, chips and star, DE + EN in `onpath.private/marketing/bilder/aktuell/` (e63ce34) – please build it in.
- → [captain] (laptop 2026-11-21, **owner's word in the laptop's chat: „Block abschließen“**): the goal „mirrors under 15“ counts logic mirrors only – look, map style and browser-only mirrors (~26) do not count. No new cuts for Save, the style cluster or a synchronous core on the page. The laptop wires the small rests umbau already listed (ProfileRules, SearchLines, CellCards, ThumbRules, MapCredits, RoundTrip, DEBOUNCE_MS), then goes on to the long-tour speed-ups 2–3. Delete the tag when read.
- → [laptop] ([umbau] 2026-11-20 late night, **web-ready: ProfileRules + SearchLines** – on main f7212c8a1): ProfileFill / columnIndices / ProfileAxis in listed ui/ProfileRules.kt (steep colours stay a mirror); core search/SearchLines (decode, addRecent; encode uses String.format – check first). Style cluster needs a real cut – not now. Delete the tag when read.
- → [laptop] ([umbau] 2026-11-20 late night, **web-ready: RoundTrip** – on main 354c06aae): `RoundTrip` and `RouteNamer` moved to :core unchanged (RouteNamer left app/web-shared-files.txt – the core folder has it); the RoundTrip mirror (WebSync.kt, routefilter.ts) names the core file – drop it when the page calls it. Delete the tag when read.
- → [laptop] ([umbau] 2026-11-20 late night, **web-ready: ThumbRules, CellCards, MapCredits, SearchState.DEBOUNCE_MS** – from the mirror sort, on main 2b0142590): pure moves into :core / listed :state; the four mirror entries name the new files – drop each when the page calls it. RoundTrip next (needs RouteNamer) → [detail](items/2026-11-20-web-ready-four-pure-moves.md)
- → [laptop] [tracking] [umbau-hp] (donald 2026-11-20 ~19:40): **0.18.6 out** – the app-code pause is over. Delete your tag when read.
- → [laptop] ([umbau] 2026-11-20 night, **web-ready: the route run for the planner** – your (a)–(c), on main e9e89f0e1): `begin(askLong = false)`; „Plan anyway“ back via `RoutedBefore(longOk)`; `PlanTourPort.None` + `MapDataPort.None`, model `PlanRecalcNoTourTest` → [detail](items/2026-11-20-web-ready-route-run-for-the-planner.md)
- → [laptop] ([umbau] 2026-11-20 evening, **web-ready: RouteListRules** – your (4), on main b348b7d19): `ui/routes/RouteListRules.kt` listed – `ActivityFilter` (MENU, keep, saved), `Favourites.shown`, `RouteSortTexts`… → [detail](items/2026-11-20-web-ready-routelistrules.md)
- → [laptop] ([umbau] 2026-11-20 evening, **web-ready: the wait windows' rules** – your (3), on main 5f830bf12): moved unchanged (same packages) into listed `:state` files – `ui/progress/WaitRules.kt`… → [detail](items/2026-11-20-web-ready-the-wait-windows-rules.md)
- → [laptop] ([umbau] 2026-11-20 evening, **web-ready: SearchState** – your (2), on main eac733afc): first a pure move – `search/OfflineIndex.kt` (SearchQuery, SearchHit, SearchRanking), `PlaceIndex.kt`, `OnlineSearch.kt`… → [detail](items/2026-11-20-web-ready-searchstate.md)
- → [laptop] ([umbau] 2026-11-20 evening, **web-ready: PlanRecalcState** – your (1), the route run in stages, listed, on main d89b781c6): `begin(): Run?` → `PlanRecalcState.route(run, engine, legs, cancel, progress, onPlanner): PlanResult` +… → [detail](items/2026-11-20-web-ready-planrecalcstate.md)
- → [cloud-web] ([tracking] 2026-11-20 ~17:00): the website's privacy notice needs a „Standort teilen“ section – the app's text is in PR 19 (`settings_privacy_live_text`, DE + EN); go live together with the app's switch-on (a line from [tracking]).
- → [laptop] ([umbau] 2026-11-20 evening, **web-ready: TrackToPlanState + RoutesOpenState** – your cut, both listed, on main 7d953c4c1): `ui/routes/TrackToPlanState.kt` – planFromShown / planFromLine / cancelPlanFromTrack / busy on… → [detail](items/2026-11-20-web-ready-tracktoplanstate-routesopensta.md)
- → [donald] [laptop] ([tracking] 2026-11-20 ~16:30, **„Standort teilen“ on main, off for users**): shown only with developer tools unlocked (`LiveShares.FOR_EVERYONE = false`) – your updates stay safe.… → [detail](items/2026-11-20-standort-teilen-on-main-off-for-users.md)
- → [laptop] ([umbau] 2026-11-20 evening, **web-ready: RecentPlansState** – `ui/routes/RecentPlansState.kt` is listed, on main ae574634a): Zuletzt + recycle bin… → [detail](items/2026-11-20-web-ready-recentplansstate.md)
- → [laptop] ([umbau] 2026-11-20 night, **web-ready: SaveState** – `ui/routes/SaveState.kt` + `SaveStore.kt` are listed): Save's whole flow… → [detail](items/2026-11-20-web-ready-savestate.md)
- → [laptop] ([umbau] 2026-11-20 evening) **web-ready: HoldMeasure** (and **web-ready: PlanDocument(history)**, on main before it): core `domain/HoldMeasure.kt`… → [detail](items/2026-11-20-web-ready-holdmeasure.md)
- → [cloud-web] [laptop] (donald 2026-11-20 ~16:30, **website pictures after 0.18.2–0.18.4**): app side – only I02 changed (the „Mehr“ row), retaken DE + EN in `onpath.private/marketing/bilder/aktuell/` (f0442c4), [cloud-web] please build it in.… → [detail](items/2026-11-20-website-pictures-after-0-18-20-18-4.md)
- → [laptop] [umbau-hp] (captain 2026-11-20 ~14:00, **owner's token rules, all four**): handoff + fresh session at ~350k context; new sessions on Opus (Fable only for the routing port); helpers on Sonnet (reading, search, tests, pictures), one big-model review per piece; no full E2E / Rundgang per small update. In owner-preferences.md „How sessions work“.
- → [laptop] ([tracking] 2026-11-20 ~13:30, **live on main**): server + viewer page (`/live/`) merged, your slot is free – go on.… → [detail](items/2026-11-20-live-on-main.md)
- → [laptop] ([umbau] 2026-11-20 ~14:00, **Zuletzt: the app now updates the entry – the rule is in the shared code**): `RoutesState.keepDraft(key, unsaved, savedId, update, insert)` – a changed plan whose `savedId` is a DRAFT calls… → [detail](items/2026-11-20-zuletzt-the-app-now-updates-the-entry-th.md)
- → [laptop]… → [detail](items/2026-11-20-captain-to-donald-laptop-this-weekend-hi.md)
- → [IR] [umbau] (laptop 2026-11-20, owner): the planner's eye (hold it or Space) now hides a round-trip suggestion's loop + start dot and works while a loop is shown, also with an empty plan. The app's eye does not: add `suggest-loop` + casing to `ROUTE_OVERLAY_LAYERS` (MapController) and „loop shown“ to `MapControlsEnabled.eye`. Delete the tag when read.
- → [laptop] [umbau-hp] (captain 2026-11-20 ~10:15, **owner's weekend mode**): he tests 0.18.2 himself, bugs come via the captain, updates may be riskier; one block after the other to the end (planner + live tracking now, then multi-day rows → phase 3/4 → routing port); boxes allowed until he says „ab jetzt keine Fragefenster mehr“ → [detail](items/2026-11-20-after-the-release.md)
- → [mehrtage] (laptop 2026-11-20, **owner's yes**): detour stitching for long legs after the release, app + planner – decisions in `items/2026-11-20-add-point-speed.md`. Delete the tag when read.
- → [laptop] [umbau-hp] (captain 2026-11-20 morning, **the owner's plan for after 0.18.2**, asked one by one): first the planner on the shared code (laptop + umbau, mirrors → under 15), in parallel only live tracking; phase 3/4, routing port, multi-day rows wait (tokens). Plus the night answers to build and a tidy of „Next“ by each role → [detail](items/2026-11-20-after-the-release.md)
- → [laptop] (donald: round to green tonight, publish on the owner's own word in donald's chat – night answers)… → [detail](items/2026-11-20-donald-to-laptop-umbau-captain-2026-11.md)
- → [laptop] [captain] [mehrtage] [IR] [cloud-web] ([tidy] 2026-11-20 night): **the notes folder has moved – pull after 8136a0ea7.** Backlog, items, roles, handoffs, working agreement, owner preferences are in `docs/team/` now; `docs/project-status.md` stayed. A file still written under `docs/items/` lands at the old place – move it.
- → [mehrtage] (laptop 2026-11-20 ~00:40, **owner's wish for the day rows, after the release, app + planner the same**): „km A bis km B“ per day is not useful.… → [detail](items/2026-11-20-owner-s-wish-for-the-day-rows-after-the.md)
- → [laptop] ([umbau] 2026-11-20 ~04:30, **items 1 + 3 are built on the branch `wip/umbau-web-slices-2026-11-20` (a90e8f4a9, only `web/engine/**` + a hand-over file; not on main – yours to take, change or drop)**: all 29 files of…** → [detail](items/2026-11-20-umbau-to-laptop-all-29-files.md)
- → [laptop] [tracking] … → [detail](items/2026-11-19-captain-to-tidy-umbau-laptop-donald-umba.md)
- → [laptop] ([umbau-hp] 2026-11-20 night): **the three wait-window fixes are on main (07b07800, robust-smoke step 10) – not deployed, the deploy is yours**; local smokes green three times, fresh review „push“.… → [detail](items/2026-11-20-umbau-hp-hardening-findings.md)
- → [mehrtage] (laptop 2026-11-20 night, owner: „der Punkt ist ja auf der Route“): a night (or any point) put exactly ON the line made the planner route the two new legs – now the planner cuts the known leg there… → [detail](items/2026-11-20-laptop-to-mehrtage-umbau-a-night-or.md)
- → [mehrtage] (laptop 2026-11-20 night, owner: „der Punkt ist ja auf der Route“): a night (or any point) put exactly ON the line made the planner route the two new legs – now the planner cuts the known leg there… → [detail](items/2026-11-20-laptop-to-mehrtage-umbau-a-night-or.md)
- → [laptop] (captain 2026-11-19 ~23:00 CET, **owner's word for this night**): work through the night, do not stop; tokens are reset.… → [detail](items/2026-11-19-owner-s-word-for-this-night.md)
- → [laptop] (captain 2026-11-20 night, **owner's answer on the planner piece: „push the visible piece through“**): Friday's release takes items 1–5 incl.… → [detail](items/2026-11-20-owner-s-answer-on-the-planner-piece-push.md)
- → [laptop] (captain 2026-11-20 night, **owner's planner bug, live at once like the place filter**): plan München → Hilden – the wait says „ready in 5 s“, then hangs a long while.… → [detail](items/2026-11-20-owner-s-planner-bug-live-at-once-like-th.md)
- → [laptop] ([umbau] 2026-11-20 ~00:30, **item 2 of the planner cut is on main (967082e6c): 29 `:state` files are listed in `state/web-ready.txt`** – 6 slices… → [detail](items/2026-11-20-umbau-to-laptop-29-state-files.md)
- → [laptop]… → [detail](items/2026-11-19-captain-to-laptop-search-munchen-tap.md)
- → [laptop] … → [detail](items/2026-11-19-captain-to-umbau-laptop-donald-umbau-hp.md)
- → [laptop] … → [detail](items/2026-11-19-captain-to-umbau-laptop-the-planner-on.md)
- → [laptop] (donald read: waits for „main is clean for the release“, notes drafted in `docs/history/release-draft-v0.18.2.md`)… → [detail](items/2026-11-19-captain-to-umbau-donald-laptop-umbau-hp.md)
- → [laptop] … → [detail](items/2026-11-19-captain-to-umbau-donald-laptop-a-tidy-up.md)
- → [laptop] (donald 2026-11-19, the owner's Pixel log): **„Region package 7-63-42 failed on the server: Build step 'shade' failed“ – 15× since 2026-11-18** (INFO, the phone falls back). Yours to look at. Otherwise the log is clean (no ERROR). Newest main is on his Pixel since 21:23 (debug build over 0.18.1, CorePoiTapE2ETest green first).
- → [laptop] ([umbau] 2026-11-19 night) **phase 2 done – the view model is cut**: screen state and logic live in `state/src/main/kotlin/com/onpath/app/ui/…` (28 slices; ports in `ui/Ports.kt`), `AppViewModel.kt` is wiring… → [detail](items/2026-11-19-phase-2-done-the-view-model-is-cut.md)
- → [laptop] ([umbau] 2026-11-19): **a mirror moved:** „recalc's long-route ask“ now lives in `state/src/main/kotlin/com/onpath/app/ui/plan/PlanRecalcSlice.kt` (step 9b; body unchanged, `answerLongRoute` stays in `AppViewModel.kt`)… → [detail](items/2026-11-19-a-mirror-moved.md)
- → [umbau-hp] ([umbau] 2026-11-19): phase 3 is designed (`docs/design/umbau-phase-3.md`), small version by the owner's choice. For 5b the design recommends an own multiplatform module `:engine` (no texts → a real `linuxX64` target at once) that needs only the ports piece P1 and the hub piece H1 of phase 3 – I write here when those two are on main; that, not the phase 3 tag, is your start.
- → [laptop] (donald: V1 on main 2399c235b)… → [detail](items/2026-11-19-the-owner-s-answers-2.md)
- → [laptop] … → [detail](items/2026-11-19-the-owner-s-answers.md)
- → [laptop] … → [detail](items/2026-11-19-owner-s-rule-for-releases-from-now-on.md)
- → [laptop] (donald: app part running)… → [detail](items/2026-11-19-donald-to-laptop-captain-2026-11-4.md)
- → [laptop] (donald: app done bda58d531 – dots; Details stays, see „For the captain“) (captain 2026-11-19, owner's feedback on the route preview, „bitte überprüfen und wenn möglich direkt anpassen“): (1) the dashboard that swipes left /… → [detail](items/2026-11-19-donald-to-laptop-captain-2026-11-2.md)
- → [laptop] (donald: fix on main b3daed4c0 – cause not proven, see „For the captain“) (captain 2026-11-19, **owner's field report on 0.18.1 – a regression, first in line**): in „Meine Routen“ (Gemacht and Geplant) **the map…** → [detail](items/2026-11-19-donald-to-umbau-laptop-captain-2026-11.md)
- → [laptop] (donald: app done f1f2f174c – steps „Wege lesen · Runden suchen · bewerten“, map taps ignored while searching) round-trip search as form A… → [detail](items/2026-11-19-captain-to-donald-laptop-the-round-trip.md)
- → [laptop] (donald: pictures done, waiting for the owner)… → [detail](items/2026-11-19-donald-to-laptop-captain-2026-11-3.md)
- → [laptop] (app + shared core done by donald: the note is no longer made in `TourSuggester` – the planner gets it with the next build; check your display)… → [detail](items/2026-11-19-app-to-laptop-captain-2026-11.md)
- → [laptop] (donald 2026-11-19, **please deploy the planner**): the app **0.18.1 is out** (GitHub Release with APK), but the release run's planner deploy stopped at the parity check… → [detail](items/2026-11-19-please-deploy-the-planner.md)
- → [laptop] (donald 2026-11-19): parity re-recorded for 4 app mirrors so web.yml goes green for 0.18.1 – please mirror in the planner: **Undo / Redo in the full points list**… → [detail](items/2026-11-19-donald-to-laptop-parity-re-recorded.md)
- → [laptop] (donald: app part running, in 0.18.1) (captain 2026-11-19, owner asked again, his word: „Zuhause abschalten“): **Zuhause off – final.** The branch `wip/donald-2026-11-19-zuhause` is not merged (keep it parked); the „Zuhause“ row… → [detail](items/2026-11-19-donald-to-laptop-captain-2026-11.md)
- → [laptop] (captain 2026-11-19, owner: „ich bin jetzt offline, die App soll so bauen, wie sie meint“): the wait window and the two progress forms are **built and merged on your own judgement – no pictures first**; put the pictures as a line… → [detail](items/2026-11-19-captain-to-laptop-the-wait-window.md)
- → [laptop] (captain 2026-11-19, the owner's answers, asked one by one): (1) [umbau] **the three old weaknesses in the tour code: fix all three** right after the tour pieces, each its own small piece, a failing test first.… → [detail](items/2026-11-19-captain-to-laptop-1-umbau-the.md)
- → [umbau-hp] (captain 2026-11-19, owner: „ja, mache es wie deine Empfehlung“): **5b is a go – port the routing engine to Kotlin**, as `docs/design/routing-kmp-spike.md` recommends: after phase 3, 3–5 nights, the hardening list first; Android… → [detail](items/2026-11-19-captain-to-umbau-hp-5b-is-a.md)
- → [laptop] (captain 2026-11-19, the owner's answers): (1) **„Zuhause“ off for now**… → [detail](items/2026-11-19-zuhause-off-for-now.md)
- → [laptop] (captain 2026-11-19, owner: „baue die richtige Lösung vollständig rein, ich brauche die neue Version erst später“) **the wait window gets real progress – before 0.18.1, no interim form.** One bar 0–100 over all steps, never… → [detail](items/2026-11-19-captain-to-laptop-the-wait-window-2.md)
- → [laptop]… → [detail](items/2026-11-19-captain-to-donald-laptop-ich-kann-weiter.md)
- → [laptop] (donald 2026-11-19): **progress design on main** (98 % fix + wait window A + sign B, reviewed) – [laptop] what the planner mirrors → [detail](items/2026-11-19-progress-design-on-main.md)
- → [cloud-web] (IR 2026-11-19 ~00:30): **round-trip pictures, planner + phone, DE + EN** – real suggestions at Alken (Traumpfad Bleidenberger Ausblicke card first): keys **web-rundtour-wunsch** (N01), **web-rundtour** (N02 card row)… → [detail](items/2026-11-19-round-trip-pictures-planner-phone-de-en.md)
- → [laptop] ([umbau] 2026-11-19 night, an old race in the sync, found by the review while moving code – not fixed, yours to judge): **a browser plan that is being routed on the phone can overwrite a save the user makes meanwhile.**… → [detail](items/2026-11-19-umbau-to-laptop-a-browser-plan.md)
- → [cloud-web] (laptop 2026-11-19 ~01:10): **planner pictures in onpath.private `aktuell/` (164d311, 118a392)**: retaken B01–B04, B07, C01, C02, C06; new multi-day B11 Mehrtagestour planen, B12 Als Mehrtagestour planen, B13 Tage festlegen, B14 Übersicht (DE + EN; keys in `docs/design/website-screens.md`). B08–B10 stay the owner's 3D examples. Delete the tag when taken.
- → [cloud-web] (donald 2026-11-18 night): new phone pictures of multi-day tours (Meraner Höhenweg, 3 days), DE + EN: keys **mehrtage-tage** (M01 day overview), **mehrtage-unterwegs** (M02 „Tag 2 von 3“), **mehrtage-karte** (M03 nights on the map) – onpath.private 801d650, aktuell/, Note M in liste.md; flow M in website-screens.md.
- → [IR] [laptop]… → [detail](items/2026-11-18-owner-round-trips-go-on-for-everyone-ton.md)
- → [IR] [mehrtage] (captain 2026-11-18 late, owner: README and website pictures for the new features): the README now names multi-day tours (captain, this commit).… → [detail](items/2026-11-18-captain-to-laptop-ir-mehrtage-the-readme.md)
- → [IR] [mehrtage] (captain 2026-11-18 late, **the owner's answers to all open questions, one by one**): (1) [IR] sketch 8 – „Ja, so bauen“: the round-trip cards in one horizontal swipe row, app + planner.… → [detail](items/2026-11-18-the-owner-s-answers-to-all-open-question.md)
- → [mehrtage] [laptop]… → [detail](items/2026-11-18-captain-to-donald-mehrtage-laptop-led-by.md)
- → [cloud-web] (donald 2026-11-18): new app pictures after 0.18.0 to build in: **D09, F03, H04, J02 (DE + EN)** (onpath.private 251de76, aktuell/, notes in liste.md). The planner steps `pictures_affected.sh v0.17.10..v0.18.0` also lists (B01–B04, B07–B09, C01, C02, C06) are the laptop's to take; E05, G03, J03, L01 show no change for a viewer.
- → [cloud-web] (captain 2026-11-17, **owner: „Das Fragenregister brauchen wir nicht mehr … da braucht auch keiner mehr was eintragen“** and „dass ich selbst Rückmeldung geben muss, möchte ich vermeiden“): (1) no more entries on the… → [detail](items/2026-11-17-captain-to-cloud-web-1-no-more.md)
- → [cloud-web] (owner 2026-11-03, comparison card 5): **Play store screenshots** in the store pattern – a short benefit headline on top, the phone on OnPath green, the A-019 pictures.… → [detail](items/2026-11-03-play-store-screenshots.md)
- → [cloud-web] (donald 2026-11-03): pairing-help pictures app part done – `onpath.private/marketing/bilder/2026-11-03-donald/`
  (`app-a1…a4-de|en.png` + `Ergebnis.md`; A1 from the emulator with a real map, A2–A4 from Paparazzi); keys
  `app-koppeln` / `app-pruefen` / `app-neu` / `app-trennen` in `docs/design/website-screens.md`.
- → [cloud-web] (captain 2026-11-07, owner's word): no more question issues – ask the owner in your chat;
  unanswered at the end → "Waiting for the owner" here; the owner's word to all comes as a line here. Pull main and
  read CLAUDE.md once. Delete this line when read (each role removes its tag).
- → [cloud-web] (donald 2026-11-08): new app pictures to build in: D10, E02, F04, F05, G01, G02 (DE + EN) (onpath.private 5875c99, aktuell/)
<!-- The captain's word to computer-bound roles: - → [donald|laptop] what to do – captain YYYY-MM-DD. The role deletes the line when taken. -->

## For the captain
- ← [umbau] (2026-11-21): **planner block closed on the owner's word** („Block abschließen“, over your 08:30 line): no style-cluster cut built; importGpx (21) and the empty search (46) stay mirrors too – if they count as logic mirrors, say so. My claim is off; phase 3/4 waits for your word. Worktrees `agent-a42222aaab25bd23b`, `agent-a1e5af939680c4130` are merged.
- ← [umbau] (2026-11-20 ~04:30): **the planner's first proof – the engine half is built, on a branch for the laptop** (`wip/umbau-web-slices-2026-11-20`): the planner's build compiles all 29 listed `:state` files, `RoutesState` + `SaveDialog` run in… → [detail](items/2026-11-20-the-planner-s-first-proof-the-engine-hal.md)
- ← [umbau] (2026-11-20 ~02:00, **test clean-up done – all four helper runs reviewed and on main (63f6563e5); numbers before → after**, `wc -l` over `.kt` / count of `@Test`): app 28,284 → 27,540 lines (1,787 → 1,744 tests) · core 24,990 →… → [detail](items/2026-11-20-umbau-to-captain-numbers-before-after.md)
- ← [umbau] (2026-11-19 ~23:30, **the planner on the shared slices – the count, the cut, and an early warning**; counted on cfe64975d, method and tables: the analysis is in my chat, I put it into `docs/design/` on request).… → [detail](items/2026-11-19-the-planner-on-the-shared-slices-the-cou.md)
- ← [umbau-hp] (2026-11-19 night): **phase 4b done as far as it goes without app code**: menus and message boxes are component pictures (40 whole screens → 30, each batch full gate + fresh Opus review, one lost check found and restored); dialogs… → [detail](items/2026-11-19-phase-4b-done-as-far-as-it-goes-without.md)
- ← [umbau] (2026-11-19 night): **phase 2 is done: step 21 (forwarders out) on main (c5611563f)**, full E2E on GitHub 100/100. `AppViewModel` **5,760 → 1,936 lines**; 28 slices in `:state`, screens and E2E read `vm.<slice>.x`; 888 state tests.… → [detail](items/2026-11-19-phase-2-is-done-step-21-forwarders-out-o.md)
- → [laptop] (donald 2026-11-18 night, cleanup piece 2 – **touches `web/engine` imports only**): 33 Android-free files move `com.onpath.app.ui` → `com.onpath.app.domain`… → [detail](items/2026-11-18-touches-web-engine-imports-only-2.md)
<!-- The captain's word to computer-bound roles: - → [donald|laptop] what to do – captain YYYY-MM-DD. The role deletes the line when taken. -->

## For the captain
- ← [laptop] (2026-11-07) for cloud-web: the planner links privacy at https://onpath.earth/de/datenschutz/ (EN /privacy/) – the address in the privacy-check item, onpath.earth/datenschutz/, answers 404; maybe a redirect on the site. Access comments in web/host fixed (02a16286).
- ← [laptop] (2026-11-06) **One branch – what app sessions see:** an app push that changes a file the planner mirrors… → [detail](items/2026-11-06-one-branch-what-app-sessions-see.md)
<!-- A role's word to the captain (roles cannot message the captain): - ← [donald|laptop|cloud-web] what – YYYY-MM-DD. The captain deletes the line when read. -->
- ← [cloud-web] **A-022 words Plan · Tour · Route (owner decision 2026-11-03)** – for **donald** (app texts, ~60 DE + EN, glossary, screenshots) and **cloud-plan** (planner texts): Plan = what you plan and save, Tour = what you do / did, Route =… → [detail](items/2026-11-03-a-022-words-plan-tour-route-owner-decisi.md)

## In progress
- [umbau] (2026-11-21, captain's start) Detour stitch on `wip/umbau-2026-11-21-detour-stitch`, nothing of it on main until SameRouteTest passes on JVM + Wasm. Piece 1 (model, tag 7) done and reviewed; now piece 2 (`Planner` on `ExpandedPlan`, leg cache). Rules: `docs/design/detour-stitch.md` §8.
- [laptop] (2026-11-21) Long-tour speed-ups 2–3: stitch format design (detour as a field of the point) → proposal to [umbau] / [donald] in the backlog, then app + planner; „Ganz neu berechnen“ decided. Planner block closed. Owner of `web/` except tracking's live files.
- [tracking] (fresh session 2026-11-20 15:00) Share UI on main (dev tools only); next: privacy-text PR, owner's Pixel share, then FOR_EVERYONE = true + line for [donald]; state `docs/team/handoffs/handoff-tracking-2026-11-20.md`
- [donald] (fresh session 2026-11-21 evening, `handoffs/handoff-donald-2026-11-21.md`): POI symbol bug, ride question, the two open reviews, then update 0.18.7 (only with „Web planner“ green); nothing of the detour stitch in it
<!-- - [donald|laptop|captain|cloud-web] short description – since YYYY-MM-DD -->

## Next
- **[donald] Check with the owner's Pixel on 2026-11-18…** → [detail](items/2026-11-18-check-with-the-owner-s-pixel-on-2026-11.md)
- **[laptop] POI click on a slow phone after a pan…** → [detail](items/2026-11-17-poi-click-on-a-slow-phone-after-a-pan-20.md)
- **[IR] Tour suggestions – state…** → [detail](items/2026-11-17-tour-suggestions-state-docs-history-hand.md)
- **[laptop] Kept places in the planner – UI + browser…** → [detail](items/2026-11-15-kept-places-in-the-planner-ui-browser-st.md)
- **[laptop] routes-smoke step 4b flaky…** → [detail](items/2026-11-15-routes-smoke-step-4b-flaky-2026-11-15-1.md)
- **[laptop] Waiting for the owner: new planner smokes…** → [detail](items/2026-11-13-waiting-for-the-owner-new-planner-smokes.md)
- **[donald + laptop] Favourite tours…** → [detail](items/2026-11-13-favourite-tours-owner-2026-11-13-variant.md)
- **[donald] Beta bug button (owner 2026-11-10, chat)…** → [detail](items/2026-11-10-beta-bug-button-owner-2026-11-10-chat-st.md)
- **[donald] First feedback from a tester (owner 2026-11-08)** → [detail](items/2026-11-08-tester-first-feedback.md)
- **[donald] Design round after 0.17.4…** → [detail](items/2026-11-07-design-round-after-0-17-4-owner-2026-11.md)
- **[laptop] Idea: saved plans open without routing…** → [detail](items/2026-11-07-idea-saved-plans-open-without-routing-ag.md)
- **[donald] Bugs seen while taking the Meran 0.17…** → [detail](items/2026-11-07-bugs-seen-while-taking-the-meran-0-17-pi.md)
- **[donald] (low) Vellau → Leiteralm routes over the…** → [detail](items/2026-11-07-low-vellau-leiteralm-routes-over-the-gam.md)
- **[donald] Smoother on older phones…** → [detail](items/2026-11-07-smoother-on-older-phones-owner-field-202.md)
- **[donald] Handy einrichten: "Links vom Planer in…** → [detail](items/2026-11-07-handy-einrichten-links-vom-planer-in-onp.md)
- **[laptop] Pairing from a phone camera ends in the…** → [detail](items/2026-11-07-pairing-from-a-phone-camera-ends-in-the.md)
- **[donald] In-app privacy texts say more than the code…** → [detail](items/2026-11-07-in-app-privacy-texts-say-more-than-the-c.md)
- **[donald] Navigation neu – the rest of the owner's…** → [detail](items/2026-11-01-navigation-neu-the-rest-of-the-owner-s-f.md)
- **[laptop] Planer: gekoppelt = eine Liste, alles…** → [detail](items/2026-11-06-planer-gekoppelt-eine-liste-alles-synchr.md)
- **[donald] 250 tours per phone in the app…** → [detail](items/2026-11-07-250-tours-per-phone-in-the-app-owner-202.md)
- **[laptop] Planner follows the app's plan rules of…** → [detail](items/2026-11-06-planner-follows-the-app-s-plan-rules-of.md)
- **[cloud-plan] Planner follows the app's plan rules of…** → [detail](items/2026-11-06-planner-follows-the-app-s-plan-rules-of-2.md)
- **[laptop] Web planner – novice walkthrough…** → [detail](items/2026-11-06-web-planner-novice-walkthrough-owner-202.md)
- **[donald] Flow map + novice walkthrough…** → [detail](items/2026-11-06-flow-map-novice-walkthrough-docs-design.md)
- **[donald] Pixel review 2026-11-06 – open bugs** → [detail](items/2026-11-06-pixel-review-2026-11-06-open-bugs.md)
- **[donald → owner] Save-path proposals…** → [detail](items/2026-11-06-save-path-proposals-docs-design-save-pat.md)
- **[pixel] Owner's points of 2026-11-06 – check on…** → [detail](items/2026-11-06-owner-s-points-of-2026-11-06-check-on-th.md)
- **[donald] Voice: the roundabout sentence on the…** → [detail](items/2026-11-06-voice-the-roundabout-sentence-on-the-own.md)
- **[cloud-plan] Flaky web smokes on GitHub – the rest** → [detail](items/2026-11-06-flaky-web-smokes-on-github-the-rest.md)
- **[donald] Onboarding – first-use hints…** → [detail](items/2026-11-05-onboarding-first-use-hints-proposal-2026.md)
- **[cloud-plan] Web: within-a-leg wait bar** → [detail](items/2026-11-05-web-within-a-leg-wait-bar.md)
- **[pixel] Measure long bike routes on the Pixel…** → [detail](items/2026-11-05-measure-long-bike-routes-on-the-pixel-le.md)
- **[donald] Far start: ask before routing the way there…** → [detail](items/2026-11-05-far-start-ask-before-routing-the-way-the.md)
- **Docs pictures follow the UI by themselves…** → [detail](items/2026-11-03-docs-pictures-follow-the-ui-by-themselve.md)
- **[pixel] Check: red tile "Neu planen" in "Route…** → [detail](items/2026-11-03-check-red-tile-neu-planen-in-route-ander.md)
- **[pixel] Check: Plan is silent during a tour…** → [detail](items/2026-11-03-check-plan-is-silent-during-a-tour-built.md)
- **[pixel] Feel: holding beside the route…** → [detail](items/2026-11-03-feel-holding-beside-the-route-24-dp-buil.md)
- **[pixel] Field check: no GPS line while lying still…** → [detail](items/2026-11-02-field-check-no-gps-line-while-lying-stil.md)
- **Bike difficulty limit S0 / S1 / S2…** → [detail](items/2026-11-01-bike-difficulty-limit-s0-s1-s2-owner-202.md)
- **Way type – more kinds (owner idea 2026-11-01, rest):** → [detail](items/2026-11-01-way-type-more-kinds-owner-idea-2026-11-0.md)
- **Website and Google Play follow-ups…** → [detail](items/2026-10-31-website-and-google-play-follow-ups-marke.md)
- **From the external review (A-020 §4) – build only…** → [detail](items/2026-10-30-from-the-external-review-a-020-4-build-o.md)
- **On the next real ride (owner)** → [detail](items/2026-10-30-on-the-next-real-ride-owner.md)
- **Plan edits during a tour – built 2026-10-31…** → [detail](items/2026-10-31-plan-edits-during-a-tour-built-2026-10-3.md)
- **[pixel] Voice guidance – the owner's check on a ride** → [detail](items/2026-11-18-voice-guidance-the-owner-s-check-on-a-ri.md)
- **Web planner: mirror app changes (keep this list current)** → [detail](items/2026-10-30-web-planner-mirror-app-changes-keep-this.md)
- **Phone setup: check brand menu words on real phones** → [detail](items/2026-11-18-phone-setup-check-brand-menu-words-on-re.md)
- **Plan sync phone ⇄ browser (A-007) – app part…** → [detail](items/2026-11-18-plan-sync-phone-browser-a-007-app-part-b.md)
- **Check on the phone…** → [detail](items/2026-10-28-check-on-the-phone-0-11-6-0-12-0-built-w.md)
- **Smart screen while cycling – built (e6ee6ed)…** → [detail](items/2026-10-30-smart-screen-while-cycling-built-e6ee6ed.md)
- **Map: Knotenpunkte + cliffs/fords (owner 2026-10-30)** → [detail](items/2026-10-30-map-knotenpunkte-cliffs-fords-owner-2026.md)
- **Google Maps place → go there (owner)** → [detail](items/2026-11-18-google-maps-place-go-there-owner.md)
- **Two MainActivity instances at start…** → [detail](items/2026-11-18-two-mainactivity-instances-at-start-seen.md)
- **Route hints – open candidates…** → [detail](items/2026-11-18-route-hints-open-candidates-numbers-in-d.md)
- **requirements.md update (any session, owner checks)** → [detail](items/2026-11-18-requirements-md-update-any-session-owner.md)
- **Map: paths look interrupted at junctions…** → [detail](items/2026-11-18-map-paths-look-interrupted-at-junctions.md)
- **Router classes** → [detail](items/2026-11-18-router-classes.md)
- **[cloud-plan] Planner security headers (CSP etc.)** → [detail](items/2026-11-01-planner-security-headers-csp-etc.md)
- **CI follow-ups:** → [detail](items/2026-11-18-ci-follow-ups.md)
- **Small:** → [detail](items/2026-11-18-small.md)
- **Kept before pruning: `wip/donald-2026-11-08-smooth-map` be609d8d0** – smooth map, WIP, 2 commits not on main
- **Kept before pruning: `wip/donald-2026-11-13-planner-suggest` 8bae9a3ed** – planner suggestion framework (earlier design), 1 commit not on main
- **Kept before pruning: `wip/donald-2026-11-13-suggest-round7` fd2593f05** – tour suggestions round 7, unfinished, 1 commit not on main
- **Kept before pruning…** → [detail](items/2026-11-14-kept-before-pruning-wip-donald-2026-11-1.md)
- **Kept before pruning: `wip/donald-2026-11-14-suggest-next` f50159937** – tour suggestions, unfinished, 1 commit not on main
- **[donald] Rests of the off-route / ride-question reviews…** → [detail](items/2026-11-21-review-rests-off-route-ride-question.md)

## Waiting for the owner
- [laptop] 2026-11-20 night: **planner ready to deploy at 8a04b05cf** (on the shared code since the last deploy: edit / undo / cancel, holds + place menus, search, the route run incl. the long-route question, GPX → plan, search lines / round trip from core, the day rows variant A + „ab / bis Hotel“; GitHub run green after the robust-smoke race fix 083dcc17b; local checks + gate green, two reviews' findings fixed). Actions → Web planner → Run workflow, deploy + quick – or give the laptop the word in its chat.
- [laptop] 2026-11-20 evening: **planner ready to deploy…** → [detail](items/2026-11-20-planner-ready-to-deploy-at-1f5bfe959.md)
- [tracking] 2026-11-20: **merge PR 19** → [detail](items/2026-11-20-merge-pr-19.md)
- [tracking] 2026-11-20: **merge pull request 17** → [detail](items/2026-11-20-merge-pull-request-17.md)
- **[donald] Only our signed apps on the map server?…** → [detail](items/2026-11-07-only-our-signed-apps-on-the-map-server-o.md)
- **Map server access before the public launch** → [detail](items/2026-11-18-map-server-access-before-the-public-laun.md)

## Later
- **Multi-day tours – concept complete, nothing built…** → [detail](items/2026-11-17-multi-day-tours-concept-complete-nothing.md)
- **Plan sheet "a third lower"** – reached only ~23 % (d2885035); more needs touch targets smaller than 48 dp, owner
  said no (2026-10-31).
- **(optional) Gradle shared remote build cache** (e.g. a GCS bucket) for donald + CI + cloud sessions.
- **(low prio) Online place search depends on…** → [detail](items/2026-10-30-low-prio-online-place-search-depends-on.md)
- **iPhone version** – direction and order in `docs/design/multiplatform.md`; core logic free of Android
  (`SharedCoreRuleTest`).
- **Open source + self-hosted map servers…** → [detail](items/2026-11-18-open-source-self-hosted-map-servers-conc.md)
- **Replace the tour's route by a saved route during a tour** → [detail](items/2026-10-31-replace-the-tour-s-route-by-a-saved-rout.md)
- **Tour suggestions — feedback captured, not…** → [detail](items/2026-11-08-tour-suggestions-feedback-captured-not-s.md)
- **Ideas:** outdoor mode, way back on own track, hut info, first-start intro / help page.
- **Map wishes (only when the owner says so):** → [detail](items/2026-11-18-map-wishes-only-when-the-owner-says-so.md)
- **Unverified paths (owner: keep):** → [detail](items/2026-11-18-unverified-paths-owner-keep.md)
- Tune the difficulty thresholds (Easy / Moderate / Difficult) with real tours.
