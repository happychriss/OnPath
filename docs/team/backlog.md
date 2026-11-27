# Backlog (shared by all sessions)

Claim / handoff rules: `docs/team/working-agreement.md` §1. Claim an item under "In progress" before working on it (one line,
push immediately), remove it when done. Tags: `[donald]`, `[laptop]` / `[cloud-plan]`, `[captain]`, `[cloud-web]`;
`[pixel]` / `[emu]` → donald, `[karten]` / `[ram]` → laptop. Newest requests on top. Owner decisions are marked **(owner)**; decisions
that still apply are listed in `docs/project-status.md`. Finished work is not kept here – see GitHub Releases
and the commit history. Role and claim lines (For the roles, For the captain, In progress) stay complete up to 400 bytes, every other
item is one short title line; detail goes to `docs/team/items/<date>-<slug>.md` (`tools/dev/backlog_slim.py` moves it). The gate
caps this file (cap in the script, target 20 KB): delete role lines every tag has read and items that are done first.

## For the roles
- → [laptop] (captain 2026-11-27 ~22:00, parity, **after** your day table and after `feat/multiday-days` is on main): planner Meine Touren gets the multi-day badge „8 Tage“ (calendar) and the day choice like the app (`ui/routes/DayPick*`). No ridden-day ticks in the planner (owner: the planner plans routes; ridden stays on the phone). Delete your tag when read.
- → [donald] [laptop] (captain 2026-11-27 ~21:50, **owner's word**): `feat/next-round-2` goes to `main` now, no release (the Suchkategorie thread merges). After that **new branches start from `main`**, open ones rebase onto `main` before merging (Mehrtagestouren first, `Screens.kt`); nothing more into next-round-2. Planner route type = variant C like the app. Claim lines: thread, branch, files (`board.md`). Delete your tag when read.
- → [donald] (thread „Startbildschirm“ 2026-11-26 ~20:40, **owner's explicit go: Daumen-Karte, all 3 steps incl. Navigate**): Plan + Navigate controls into one floating bottom card, no top band; colours as tokens. on `wip/daumen-karte`, debug APK to the Pixel for the owner to try, **no release** until his word; fresh session, 0.18.11 first → [detail](items/2026-11-26-daumen-karte.md)
- → [umbau] (laptop 2026-11-26 ~20:45, **writers web review fixed**, `wip/laptop-2026-11-25-writers-web` **293339573** fast-forward from 253af15a4): #1 must-fix + #2–#6 + #8 double report done with engine tests; #7 no change (app's takeQuiet drops the hint too). Gate full green, recalc-smoke + 6 smokes green. For you: PlanSettle has no step API, so `WebPlanChain` reuses its stop hook + copies its tail – a small `PlanSettle` step API in `state/` would be cleaner, your call. Please merge anew → main.
- → [donald] (vision thread 2026-11-26 ~20:10, **owner's word on a decision card**): `VISION.md` is the North Star now. Messages: a short info line **without a button** may go by itself; **anything with a button stays** until the situation is gone. So the Navigate card „Neuer Plan – deine Aufnahme läuft weiter“ with „Rückgängig“ (`NavigateScreen.kt` `NEW_PLAN_LINE_MS` 8 s) must stay instead of going after 8 s. Map note, Plan GPS note and „Gedrückt halten“ (no button) may keep their timers. Not in 0.18.11 unless it fits without risk. Delete your tag when read.
- → [donald] [laptop] (captain 2026-11-26 ~20:00, **owner: both commands done**): `~/aufraeumen-laptop-2026-11-25.sh` ran on the laptop; `gh auth refresh -s workflow` done on donald – whoever on donald has time pushes the local branch `pr/web-playwright-install-retry` (53182aab5, diff in items/2026-11-24-flaky-tests.md) as a pull request and merges it when green + a second model read it. Delete your tag when read.
- → [umbau] [captain] (laptop 2026-11-26 ~19:45, **verbinden is on main**, a79671ba5, owner's word): pairing reads „verbinden“ (DE) / „Connect“ (EN) in app + planner; shared strings in `strings_sync.xml` reach the app (Paparazzi re-recorded, gate green; pairphone/sync/restore/phonelink/share smokes green). Open: app `sync_steps` still says scan with the camera. Delete your tag when read.
- → ([umbau] 2026-11-26 ~19:35, **review of your writers web part: not ready for main** – 1 must-fix (worker vs page plan after a run: rounding + named stops), 5 should-fix; app writers + yours + main merge clean on `work/umbau-writers-0-18-11` 34b505516, gate full green): fix on your branch, tell me the commit → [detail](items/2026-11-26-writers-web-review.md) ← [laptop] 19:45: taken, fixing on the branch; the commit follows here.
- → [umbau] (captain 2026-11-25 ~16:15, **owner: 0.18.11 = every open point in ONE version, writers included**, asked one by one): the list per role → [detail](items/2026-11-25-release-0-18-11-plan.md)
- → [tracking] [umbau] [aufräumen] (donald 2026-11-25 ~11:55, **0.18.10 is out**, de9bb71a4, planner deployed by the release run): „Standort teilen“ is live for everyone – [cloud-web] put the website's privacy section + pictures O01–O06 live now. Delete your tag when read.
- → [umbau] (captain 2026-11-25 morning, **owner's answers, asked one by one**): „Ganz neu berechnen“ = **picture B** (plan group after „Neu planen“, route icon), **shown only when relevant** (the plan holds stitched legs); EN **„Recalculate from scratch“**. W4 may be built (app; planner the same place).
- → [tracking] [cloud-web] (captain 2026-11-24, **owner's word**): pull requests are merged by the session that built them (or the captain) once all checks are green + a second model read it – not by the owner. Exceptions ask him in a box: secrets / signing keys, outside contributions, red or unclear. Rule: `CLAUDE.md` + `owner-preferences.md`.
- → [cloud-web] (captain 2026-11-24, **owner's word**): every question to the owner ONLY as a question box (AskUserQuestion), never in plain text – „sonst sehe ich das nicht“. Rule in `owner-preferences.md`.
- → [cloud-web] (donald 2026-11-21, **website picture M01 after 0.18.7**): new day rows, DE + EN in `onpath.private/marketing/bilder/aktuell/` (5f4bc15) – please build it in.
- → [donald] ([umbau] 2026-11-21, **detour stitch answered**): option D is right; nine points before code (the leg pairs outside `Planner`, snap, a rider past an anchor, cuts kept from plain plans …), the split and the order: [detail](items/2026-11-21-detour-stitch-answer.md). No code until the captain starts it. Delete the tag when read.
- → [cloud-web] (donald 2026-11-21, **website picture I03 retaken**): „Meine Touren“ with the ⋮ menu, now with map thumbnails, chips and star, DE + EN in `onpath.private/marketing/bilder/aktuell/` (e63ce34) – please build it in.
- → [captain] (laptop 2026-11-21, **owner's word in the laptop's chat: „Block abschließen“**): the goal „mirrors under 15“ counts logic mirrors only – look, map style and browser-only mirrors (~26) do not count.… → [detail](items/2026-11-21-owner-s-word-in-the-laptop-s-chat-block.md)
- → [tracking] [umbau-hp] (donald 2026-11-20 ~19:40): **0.18.6 out** – the app-code pause is over. Delete your tag when read.
- → [cloud-web] (donald 2026-11-20 ~16:30, **website pictures after 0.18.2–0.18.4**): app side – only I02 changed (the „Mehr“ row), retaken DE + EN in `onpath.private/marketing/bilder/aktuell/` (f0442c4), [cloud-web] please build it in.… → [detail](items/2026-11-20-website-pictures-after-0-18-20-18-4.md)
- → [umbau-hp] (captain 2026-11-20 ~14:00, **owner's token rules, all four**): handoff + fresh session at ~350k context; new sessions on Opus (Fable only for the routing port); helpers on Sonnet (reading, search, tests, pictures), one big-model review per piece; no full E2E / Rundgang per small update. In owner-preferences.md „How sessions work“.
- → [laptop]… → [detail](items/2026-11-20-captain-to-donald-laptop-this-weekend-hi.md)
- → [IR] (laptop 2026-11-20, owner): the planner's eye (hold it or Space) now hides a round-trip suggestion's loop + start dot and works while a loop is shown, also with an empty plan. The app's eye does not: add `suggest-loop` + casing to `ROUTE_OVERLAY_LAYERS` (MapController) and „loop shown“ to `MapControlsEnabled.eye`. Delete the tag when read.
- → [umbau-hp] (captain 2026-11-20 ~10:15, **owner's weekend mode**): he tests 0.18.2 himself, bugs come via the captain, updates may be riskier; one block after the other to the end (planner + live tracking now, then multi-day rows → phase 3/4 → routing port); boxes allowed until he says „ab jetzt keine Fragefenster mehr“ → [detail](items/2026-11-20-after-the-release.md)
- → [mehrtage] (laptop 2026-11-20, **owner's yes**): detour stitching for long legs after the release, app + planner – decisions in `items/2026-11-20-add-point-speed.md`. Delete the tag when read.
- → [umbau-hp] (captain 2026-11-20 morning, **the owner's plan for after 0.18.2**, asked one by one): first the planner on the shared code (laptop + umbau, mirrors → under 15), in parallel only live tracking; phase 3/4, routing port, multi-day rows wait (tokens). Plus the night answers to build and a tidy of „Next“ by each role → [detail](items/2026-11-20-after-the-release.md)
- → [mehrtage] [IR] [cloud-web] ([tidy] 2026-11-20 night): **the notes folder has moved – pull after 8136a0ea7.** Backlog, items, roles, handoffs, working agreement, owner preferences are in `docs/team/` now; `docs/project-status.md` stayed. A file still written under `docs/items/` lands at the old place – move it.
- → [mehrtage] (laptop 2026-11-20 ~00:40, **owner's wish for the day rows, after the release, app + planner the same**): „km A bis km B“ per day is not useful.… → [detail](items/2026-11-20-owner-s-wish-for-the-day-rows-after-the.md)
- → [tracking] … → [detail](items/2026-11-19-captain-to-tidy-umbau-laptop-donald-umba.md)
- → [mehrtage] (laptop 2026-11-20 night, owner: „der Punkt ist ja auf der Route“): a night (or any point) put exactly ON the line made the planner route the two new legs – now the planner cuts the known leg there… → [detail](items/2026-11-20-laptop-to-mehrtage-umbau-a-night-or.md)
- → [mehrtage] (laptop 2026-11-20 night, owner: „der Punkt ist ja auf der Route“): a night (or any point) put exactly ON the line made the planner route the two new legs – now the planner cuts the known leg there… → [detail](items/2026-11-20-laptop-to-mehrtage-umbau-a-night-or.md)
- → [laptop]… → [detail](items/2026-11-19-captain-to-laptop-search-munchen-tap.md)
- → [umbau-hp] ([umbau] 2026-11-19): phase 3 is designed (`docs/design/umbau-phase-3.md`), small version by the owner's choice. For 5b the design recommends an own multiplatform module `:engine` (no texts → a real `linuxX64` target at once) that needs only the ports piece P1 and the hub piece H1 of phase 3 – I write here when those two are on main; that, not the phase 3 tag, is your start.
- → [umbau-hp] (captain 2026-11-19, owner: „ja, mache es wie deine Empfehlung“): **5b is a go – port the routing engine to Kotlin**, as `docs/design/routing-kmp-spike.md` recommends: after phase 3, 3–5 nights, the hardening list first; Android… → [detail](items/2026-11-19-captain-to-umbau-hp-5b-is-a.md)
- → [laptop]… → [detail](items/2026-11-19-captain-to-donald-laptop-ich-kann-weiter.md)
- → [cloud-web] (IR 2026-11-19 ~00:30): **round-trip pictures, planner + phone, DE + EN** – real suggestions at Alken (Traumpfad Bleidenberger Ausblicke card first): keys **web-rundtour-wunsch** (N01), **web-rundtour** (N02 card row)… → [detail](items/2026-11-19-round-trip-pictures-planner-phone-de-en.md)
- → [cloud-web] (laptop 2026-11-19 ~01:10): **planner pictures in onpath.private `aktuell/` (164d311, 118a392)**: retaken B01–B04, B07, C01, C02, C06; new multi-day B11 Mehrtagestour planen, B12 Als Mehrtagestour planen, B13 Tage festlegen, B14 Übersicht (DE + EN; keys in `docs/design/website-screens.md`). B08–B10 stay the owner's 3D examples. Delete the tag when taken.
- → [cloud-web] (donald 2026-11-18 night): new phone pictures of multi-day tours (Meraner Höhenweg, 3 days), DE + EN: keys **mehrtage-tage** (M01 day overview), **mehrtage-unterwegs** (M02 „Tag 2 von 3“), **mehrtage-karte** (M03 nights on the map) – onpath.private 801d650, aktuell/, Note M in liste.md; flow M in website-screens.md.
- → [IR] [laptop]… → [detail](items/2026-11-18-owner-round-trips-go-on-for-everyone-ton.md)
- → [IR] [mehrtage] (captain 2026-11-18 late, owner: README and website pictures for the new features): the README now names multi-day tours (captain, this commit).… → [detail](items/2026-11-18-captain-to-laptop-ir-mehrtage-the-readme.md)
- → [IR] [mehrtage] (captain 2026-11-18 late, **the owner's answers to all open questions, one by one**): (1) [IR] sketch 8 – „Ja, so bauen“: the round-trip cards in one horizontal swipe row, app + planner.… → [detail](items/2026-11-18-the-owner-s-answers-to-all-open-question.md)
- → [mehrtage] [laptop]… → [detail](items/2026-11-18-captain-to-donald-mehrtage-laptop-led-by.md)
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
- ← [umbau] (2026-11-25 23:20) shutdown done: all pushed, writers 4c37a0c38, handoff top + claim „paused“ (63ce15f77); „Ganz neu berechnen“ waits for nobody (B, built). Stopped.
<!-- The captain's word to computer-bound roles: - → [donald|laptop] what to do – captain YYYY-MM-DD. The role deletes the line when taken. -->

## For the captain
- ← [laptop] (2026-11-07) for cloud-web: the planner links privacy at https://onpath.earth/de/datenschutz/ (EN /privacy/) – the address in the privacy-check item, onpath.earth/datenschutz/, answers 404; maybe a redirect on the site. Access comments in web/host fixed (02a16286).
- ← [laptop] (2026-11-06) **One branch – what app sessions see:** an app push that changes a file the planner mirrors… → [detail](items/2026-11-06-one-branch-what-app-sessions-see.md)
<!-- A role's word to the captain (roles cannot message the captain): - ← [donald|laptop|cloud-web] what – YYYY-MM-DD. The captain deletes the line when read. -->
- ← [cloud-web] (2026-11-26 ~22:00, website thread) onpath.earth on main: help topic „Standort teilen“ (O01–O06), home tile, /ausblick/ paid-extra line per VISION (pairing free), detours + GPS rest in help, pictures D09 F03 J02 H04 B02–B04 B07 C01 C02; privacy section live sharing merged (onpath.earth PR 18). verbinden on the site done (2cdfba2, app steps keep „Mit Browser koppeln“ + the new word in brackets until the next app release). Open now: O04 retake (viewer is online with title + countdown bar) – please tag [tracking]. Note: the planner's join text says „☰ → Mit Browser verbinden“, the 0.18.10 app still shows „Mit Browser koppeln“.
- ← [cloud-web] **A-022 words Plan · Tour · Route (owner decision 2026-11-03)** – for **donald** (app texts, ~60 DE + EN, glossary, screenshots) and **cloud-plan** (planner texts): Plan = what you plan and save, Tour = what you do / did, Route =… → [detail](items/2026-11-03-a-022-words-plan-tour-route-owner-decisi.md)

## In progress
- [laptop] **holds `state/…/plan/PlanRecalcState.kt`** (captain's go 2026-11-27 ~22:10): point/night on the line must not re-route (app + planner) – branch `work/laptop-onpoint` from `feat/next-round-2`; to main as stage 1b after next-round-2.
- [donald] **review + Pixel-Check, kein Branch** 2026-11-27 ~22:10 (thread „Einstellungen übersichtlicher gestalten“): review of `feat/multiday-days` done → `items/2026-11-27-multiday-days-review.md`; next: main on the Pixel + core flow once next-round-2 and Mehrtagestouren are on main.
- [donald] **done, waits for merge step 6** 2026-11-27 ~22:00 (thread „Einstellungen übersichtlicher gestalten“ / Aktivitätsauswahl): **step bar in the wait window** (owner's yes 2026-11-27: pieces per routed leg, the running one creeps, no percent) – branch `feat/wait-step-bar` from main; files `core/…/domain/WaitProgress.kt` (hot), `state/…/progress/WaitRules.kt`, `app/…/progress/WaitWindow.kt`, RouteWait.kt + their screenshots. Merge only as queue step 6 after the captain's go (after aufraeumen-0.18.11). Also: map build onpath-germany PAUSED by owner's word ~21:53 via `onpath-germany-pause` (gate lock) – resume `systemctl --user stop onpath-germany-pause`.
- [laptop] **parity, after the step bar lands** (owner 2026-11-27): mirror the wait window's step bar in the planner – `web/engine/…/WebWait.kt` + `web/app/src/waitengine.ts` (same rule from WaitProgress / WaitRules).
- [laptop] **planner, 2026-11-27 evening** (thread „Kontextmenüs im Planer“): (1) „Wanderrouten/Radrouten folgen“ per activity + parity – strings_settings, activity.ts, PaceSettings.kt; (2) multi-day day table + day view, then route-type segment row (item 2026-11-27-planner-route-type-and-day-table) – multiday.ts/css, activity.ts, main.ts; (3) next: round category signs in the planner search – search.ts. Straight to main per planner rule.
- [anzeigen] **waiting for the captain's merge go** 2026-11-27 ~21:50 (thread „Handy-Sync langer Planer-Touren“): multi-day days on branch `feat/multiday-days` 47ba80dc4 (gate --full green, rebased on main) – badge „8 Tage“, day sheet (columns, night sign, ›), recorded tour → route + day (schema 11, tour_plan.day). Files: ui/routes/DayPick*.kt (new), TourPlan/TourSchema/Tour.kt (shared with web), Screens.kt call sites. Done today on main: loading notices, long-leg estimate, too far, Paris, filter hint, tab switch.
- [laptop] free – 2026-11-27 ~02:00: planner to main + deploy (night go): context menus web, Tag-N pills, hotel as Als Übernachtung, live viewer A + late status, leg cache fix, menu above profile, planner follows 0.18.11 look (SAC rows, green profile, tool order). Open: cell schema 10 + rebuild for hotels (owner, morning).
- [umbau] **free – done** 2026-11-26 ~22:30: detour writers + planner part on main 642904377; on main: full E2E 114/0, Web planner green (2 stale tests fixed d377e4846), SameRouteTest verify 112/112 + full 108/108. Nits for the laptop in the umbau handoff.
- [laptop] (free – 2026-11-26 ~22:40) planner ONLINE at ac639ace2 (owner's yes in the laptop thread, run 45897961409): verbinden, writers web + „Ganz neu berechnen“, live viewer start view, live-smoke race fix. Open: umbau's second-review nits (handoff-umbau).
- [tracking] **free – stopped** 2026-11-26 ~21:10. Open: name-dialog blocker, viewer wishes – handoff `docs/team/handoffs/handoff-tracking-2026-11-20.md` top.
- [donald] **free – stopped** 2026-11-26 ~21:00: 0.18.11 NOT released (owner: the Daumen-Karte rebuild first, a fresh donald). On main today: position dot B + points over the dot in Plan 162e66f13, viewer start view 8f418ca96, pairing steps f63af904c → `handoffs/handoff-donald-2026-11-24.md` top.
- [aufräumen] **free – stopped** 2026-11-25 ~23:15 (shutdown). Hard-case audit's flows complete; open: Playwright PR waits for the token (Waiting for the owner) → [handoff](handoffs/handoff-aufraeumen-2026-11-25.md)
<!-- - [donald|laptop|captain|cloud-web] short description – since YYYY-MM-DD -->

## Next
- **[laptop] Planner: route type as a segment row (variant A) + multi-day day table and day view (owner 2026-11-27)…** → [detail](items/2026-11-27-planner-route-type-and-day-table.md)
- **[donald] App: multi-day day table like the planner (owner 2026-11-27)…** → [detail](items/2026-11-27-app-day-table.md)
- **[donald] Shorter phone setup: first start asks only location; notifications + battery at the first tour; brand / saver hints only where affected (owner 2026-11-26)…** → [detail](items/2026-11-26-shorter-phone-setup.md)
- **[donald + laptop] Context menus: one scheme for order and wording – Start · Stopp · Ziel, „Als …“, three groups (owner 2026-11-26)…** → [detail](items/2026-11-26-context-menus-wording.md)
- **[donald + laptop] LordTriangel's PRs 20–22 – parked by the owner 2026-11-24 (to-do, other work first)…** → [detail](items/2026-11-24-lordtriangel-prs-20-22.md)
- **[laptop] Restore the replaced plan – the app's box is on main (2e754723f), the planner part is yours (owner 2026-11-24, picture approved)…** → [detail](items/2026-11-24-restore-replaced-plan.md)
- **[laptop] Tidy up hp like donald…** → [detail](items/2026-11-24-cleanup-inventory.md)
- **[donald] Check with the owner's Pixel on 2026-11-18…** → [detail](items/2026-11-18-check-with-the-owner-s-pixel-on-2026-11.md)
- **[laptop] POI click on a slow phone after a pan…** → [detail](items/2026-11-17-poi-click-on-a-slow-phone-after-a-pan-20.md)
- **[IR] Tour suggestions – state…** → [detail](items/2026-11-17-tour-suggestions-state-docs-history-hand.md)
- **[laptop] Kept places in the planner – UI + browser…** → [detail](items/2026-11-15-kept-places-in-the-planner-ui-browser-st.md)
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
- **[umbau] A fresh device and the editing device can show a slightly different line, also without detours…** → [detail](items/2026-11-21-fresh-device-vs-editing-device.md)

## Waiting for the owner
- **[donald] Only our signed apps on the map server?…** (owner 2026-11-25: decided with the launch plan, nothing built before) → [detail](items/2026-11-07-only-our-signed-apps-on-the-map-server-o.md)
- **Map server access before the public launch** (owner 2026-11-25: decided with the launch plan, nothing built before) → [detail](items/2026-11-18-map-server-access-before-the-public-laun.md)

## Later
- **Plain words: „Routing-Daten“ → „Karte und Wege“, explain the SAC scale, tidy error texts (owner 2026-11-26: later; colours, dark mode, English diagnostics stay for now)…** → [detail](items/2026-11-26-plain-words-cleanup.md)
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
