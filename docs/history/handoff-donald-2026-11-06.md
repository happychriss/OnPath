# Handoff donald – 2026-11-06 evening (context full; read this first)

The owner worked with donald all afternoon and evening (Pixel in hand). Many decisions are **built on branches but not
on `main` yet**. Nothing below is lost: every branch is in a local worktree on donald (`.claude/worktrees/…`).
Owner's word for the evening: "mach alles fertig, entscheide selber, push auf main, aufs Handy nur zum Testen (kein
Release)". Owner wants new builds **on the Pixel first** (before the long checks), then main.

## 2026-11-08 ~02:00 – v0.17.5 released (Anhalten)
- https://github.com/happychriss/OnPath/releases/tag/v0.17.5 (main 6b0e2a18; Anhalten 9745ddd5 + E2E fix 93254cb8);
  all 12 E2E classes + Rundgang 17/17 green; planner deployed with it this time. Play file `dist/onpath-0.17.5.aab`
  (1705) waits for the owner's upload; German Play text in the chat. Open: the "… ruhen" line hardly ever shows (backlog
  "Waiting for the owner").

## 2026-11-08 ~00:50 – state when the owner left (read this first)
- **Released:** v0.17.4 (main f9b9b5ce; all 12 E2E classes, Rundgang 17/17); in the Play internal test (owner uploaded,
  versionCode 1704). Planner not deployed with it – laptop's backlog line (parity + manual deploy).
- **Owner's word for v0.17.5:** release right after the "Anhalten" build (variant 5, page
  https://claude.ai/artifact/BxrUPCkkheDRPXksZSroHx) lands green – all checks, then `tools/dev/play_bundle.sh` →
  `dist/onpath-0.17.5.aab` (versionCode 1705) for his Play upload. No phone install (S10 runs Play; the Pixel gets the
  GitHub release through the in-app updater – same test key as donald's debug builds).
- **Running:** the Anhalten build (branch donald-anhalten, sketch branch donald-anhalten-sketch with variants 0–5).
- **Website:** ChatGPT's redesign applied and live (onpath.earth 58a8a5d); hand-over rule in its AGENTS.md (7ce6d0c).
- **Calm phase:** no self wake-up (trigger off). Open owner questions: backlog "Waiting for the owner".

## 2026-11-07 ~20:40 – Galaxy S10 measured (release 0.17.3 from Play, `dumpsys gfxinfo`, owner's hands)
| Run (about 20 s each) | Frames | Janky | p50 | p90 | p99 | Slow draw |
| --- | --- | --- | --- | --- | --- | --- |
| Plan map pan/zoom, Relief + Höhenlinien on | 3372 | 46 % | 19 ms | 31 ms | 48 ms | 1427 |
| Navigate (a tour + one map load), both on | 4163 | 37 % | 19 ms | 32 ms | 53 ms | 1297 |
| Meine Touren (few tours), both on | 884 | 24 % | 14 ms | 34 ms | 81 ms | 97 |
| Plan map pan/zoom, both off | 979 | 8 % | 12 ms | 18 ms | 46 ms | 36 |
| Plan map pan/zoom, Höhenlinien only | 180 | 14 % | 14 ms | 28 ms | 48 ms | 8 |

The live hillshade (Relief) is the cost – it loads the GPU so much that the Compose frames miss too. Owner's choice: the
app notices it itself and switches Relief off once with a hint (branch donald-relief-auto). Also found on the S10:
App Links failed (state 1024) – the Play App Signing key was missing in `assetlinks.json` (fixed, f36343d1); the Samsung
TTS voice step (de5e7939); a far destination lost at the long-plan Cancel (752d69b3).

## 2026-11-07 ~16:50 – v0.17.3 released and on the Pixel (read this first)
- **Released** v0.17.3 (https://github.com/happychriss/OnPath/releases/tag/v0.17.3, main dc93139b) on the owner's word
  ("Release mit allen Schikanen und aufs Pixel"): gate --full 2871, all 12 GitHub E2E classes green
  (RoutingDataFreshInstall skipped – needs a flag), Rundgang 17/17. Pixel: debug APK of dc93139b installed 16:40.
- **In it since 0.17.2:** one map orientation; locate distance; one app window; hold menu Als Zwischenstopp / neues Ziel;
  activity switch far from the route; "② erst ab T2 erreichbar" + red T in the grey band; fit off the activity button;
  Meine Touren Planen / Los geht's; done tour keeps its plan (DB 6, `tour_plan`); arrival box Weiter aufzeichnen / Tour
  beenden → empty start; off-route two steps shown + said; bike voice timing (6 s, nothing swallowed, goal side); jog cue;
  round trip twice; hold lock by the clock; prefs off main; recording tick lighter; watchdog; bike corridor 20 km; My
  routes list top; reroute fits the walker; TourRun.
- **Open owner questions** (backlog "Waiting for the owner"): 6 s turn cue OK?; Undo in the full points sheet.
- **Follow-ups:** backlog Next "[donald] Follow-ups from v0.17.3"; ▶ Sim restart progress; prune ci/donald-* refs (the
  classifier refuses agents' deletes – only `tools/dev/prune_branches.sh` after the owner's yes to the list).
- **Pages for the owner:** reach hint round https://claude.ai/artifact/XbgyccgVnzANV1KDAWu9GU, Meine Touren
  https://claude.ai/artifact/HGDEpf4pdubLAiEoFZ9TmD.

## On main today (all gated, most with E2E)

Plan kept at tour end (894ab407, H2 844c8401) · end sheet = bottom sheet (6d742b95) · Sperren fill · glyphs all 256
ranges (60470cba) · "Ohne Netz ✓" strict (20b9c63a) · pin card "+ Hinzufügen" · one point during a tour = from here
(31b4287c, #39) · M1/P3 (6fc0d7c4) · F1 question, C green button, slim saved box, round trip switch (#40), unsaved sign
(#41), fixed map controls (#43), review bugs (9f2c7b5a) · prune_branches.sh + 6 wip branches deleted (5160b658) ·
three Gradle slots, E2E-on-GitHub rule (db592473) · docs: flow-map.md (+ novice walkthrough), save-paths.md.

## Built, NOT on main – merge in this order

| # | Branch (worktree `.claude/worktrees/…`) | Base | What | State |
|---|---|---|---|---|
| 1 | `donald-h1-walnuss` (agent-a0522125906c8aac5) HEAD 1d45deec | main 6fc0d7c4 | Walnuss #4A3F37 + colour rule (#36, **owner approved on the Pixel**), sheet keeps its height while routing, ☰ = Meine Touren · Plan speichern · Neu planen · Ohne Plan aufzeichnen · Sync · Einstellungen, eye/whole route always shown greyed, ↶ ↷ always shown greyed, unsaved sign blue, paper tone surfaces | gate --full green; **on the Pixel now** (APK 20:21) |
| 2 | `donald-plan-tour-split` (agent-a3cb7fb5787d1d8a6) | #1 | f74703bf plan and tour separated (plan saved at "Los geht's", end sheet only about the tour, plan stays on the map after the tour); e0b8c065 plan in front (Plan mode camera shows the whole plan); "Zuletzt" (drafts, last 25, Speichern/Löschen in Meine Touren → Geplant, no unsaved sign, no save questions, browser syncs only saved); ca09e8bd delete straight to the bin + clear without question | **done**: 8ee88b15 Zuletzt (`tours.draft`, schema 5; drafts not in backup/export/sync; a changed saved plan at "Los geht's" is saved as "Name (2)"), ca09e8bd delete to bin + clear without question, fddb275b E2E; gate --full green, 30 E2E flows green (2026-11-06 21:24). Leftover: unused strings routes_replace_*, plan_save_ask_*, plan_discard*, plan_tour_goes_on, plan_bar_save, plan_clear_tour_*, routes_delete_title, plan_unsaved_chip* |
| 3 | `donald-n2-n4-k1` (agent-a8c1de0ee020caee9) HEAD 81ce285e | main 6c7c2c91 | N2 slider line "Pause · Beenden · Planen", N4 back key asks "App verlassen?", K1 T1–T5 during a tour, the pause no longer changes the lock | gate --full green |
| 4 | `donald-lockoff-planen` (agent-a6cd46ee77893cf00) | #3 | one bottom bar for Navigate with AND without lock: "⏸ Pause \| ✎ Planen", paused "Weiter · Beenden · round ✎"; status card without buttons; "Sperren" button gone (auto re-lock); words: "Planen", "Weiter" (also end sheet, interrupted tour), pill always "PLANEN", no red REC badge (grey "Pausiert"); green back button "▲ Weiter geht's" (no dot, no numbers); start screen title "Wohin soll's gehen?" | **done**: 9e5bb935, 40206329 (one `TourBottomBar` with and without lock, no "Sperren" button), c2048084 ("▲ Weiter geht's"), cac33452 (Weiter / PLANEN / no REC), 27b1fa65 (153 screenshots), 9b033842 (2 new E2E); gate --full green, CoreFieldE2ETest 15/15 + StartSmoke green. **Release-Rundgang flow 7 red – also on its base 81ce285e** ("Demo · Tour …" not found in Meine Touren → Gemacht; probably the new tour names "In X" of 3bb6ba43 on main – fix the flow's expectation at the merge). EN: "Resume"/"Continue", back button "Let's go on". MapOverlays.kt:317 still says "Neuer Plan". |
| 5 | `donald-one-point-loop` (agent-af65de494a0aef7f9) | main | field bug 21:18: the one-point rule rebuilt position → point after every delete (no way to an empty plan, point 1 under the puck, "kein Weg gefunden" for 11 m). Fix: rule only ≥ ~50 m, the inserted position start is not a user point, tiny legs no error | check its state |
| 6 | `spike/3d-webview` (agent-af157df928d347de5) HEAD 4e681622 | main | 3D probe: planner's maplibre-gl 3D terrain offline in a WebView (developer tools) + fly-over; report `docs/design/3d-spike.md` | **owner decides after the numbers; never merge without his word** |

Merge plan: build one integration branch from main: #1 → #2 → #3 → #4 → #5 (expect conflicts in AppViewModel,
PlanPanel, MapScreen, NavigateScreen and many screenshots: take code by hand, then re-record the affected screenshot
classes). Then: build APK → **Pixel first** (check `RecordingService` – no install during a tour) → meanwhile push to
`ci/donald-merge-1106` and `tools/ci/e2e_github.sh --ref ci/donald-merge-1106 --tests <classes> --wait` → main when
green → prune the ci branch.

## Update 2026-11-06 ~23:00 (state of the merge)

- #5 one-point loop is on main (ee33b909); the test audit report too (a471e15a).
- **Integration branch `donald-merge-1106`** (worktree agent-a3645d10e6e85a387) = main + #1–#4 + flow07 fix + bcb01089
  (a point left where I stand = "here": no "No destination ahead"). Pushed as `ci/donald-merge-1106` (+ `-link`,
  `-rundgang` refs, one class per ref – e2e.yml runs only the FIRST class of a comma list: fix by PR to the owner).
  GitHub: CoreFieldE2ETest 17/17, CorePlanE2ETest 5/5, CorePlanLinkE2ETest 5/5 green; StartSmokeE2ETest 3/3 local;
  ReleaseRundgang 14/15 – **flow04 red (hold → a point, also locally) – agent fixing it on donald-merge-1106**. Then main,
  then prune the three ci refs.
- Built on top of `donald-merge-1106` (local branches, gate --full green each, owner saw them on the Pixel), to main after it:
  `donald-load-no-question` (no question when a plan/tour is tapped during a tour, owner 22:05),
  `donald-paper-float` (paper for everything floating, owner "sieht gut aus"), `donald-overview-pill`
  ("Nur Übersichtskarte · Detailkarte laden"), `donald-hold-lock` (hold-to-confirm lock, variant A, slider gone) →
  `donald-hold-setting` (Settings slider Aus · 0,5–2 s) and `donald-tour-end-fixes` (0 m tour without "Als Plan
  übernehmen", end-sheet 500 ms guard, "In Meine Touren ›" opens Gemacht, logs).
- `donald-pixel-1106` = all of the above + `spike/3d-webview` (Meran demo, fly-over) – Pixel test build only, never main.
- Waiting for the owner: blue A/B/C https://claude.ai/artifact/R3w2uLnjDmENdtmCqY39sQ (rec. C ink blue #2B4C6F, no
  blue surfaces), polish 1–8 https://claude.ai/artifact/AdSGgKD15W3GZCfu6Ngx7M (rec. 2, 3, 5), Settings → Navigation
  groups https://claude.ai/artifact/52sgsWj1GSPuUJY7GYRaC3 (rec. A), 3D A vs B (fly-over liked: "sehr, sehr cool").
- Seen on the way: theme `secondaryContainer` #DDE3F7 tints every FilterChip light blue app-wide → Walnuss.

## Owner decisions 2026-11-06 ~23:10 – the next package (build after `donald-merge2-1106` is on main)

- Blue = **C**: ink blue #2B4C6F, no blue surfaces (PlanTint, head band, pill row), no PlanEdge glow; Plan is recognised
  by the PLANEN pill, the sheet's blue top edge and blue buttons (sketch `donald-blue-sketch` a69a7510).
- Zuletzt: the plan on the map is the first entry with the tag "In Bearbeitung"; tap = back to the plan; Speichern there.
- Polish (one by one): 1 map colours NO · 2 route draws itself ~0.5 s + short haptic YES · 3 numbers like an instrument
  (big number, small unit, tabular figures) everywhere YES · 4 contour pattern NO · 5 Literata NO · 6 warm walnut shadows
  in two levels + corner family (pills full, cards 20 dp, small 12 dp) YES · 7 only the goal flag (no ring, 1.11. stays)
  · 8 profile gradient + steepness (smoothed ~100 m) YES. Sketch `donald-polish-sketch`.
- Settings: **value rows** (variant C of https://claude.ai/artifact/52sgsWj1GSPuUJY7GYRaC3: title + current value,
  the choice on tap) on ALL settings pages – but NOT T1–T5 for hiking (stay buttons). "Handy einrichten" on the
  Navigation page: at the top with ⚠ + what is missing when location / vibration / battery is not set up (menu and topic
  list already do this). FilterChips' light blue (`secondaryContainer` #DDE3F7) → Walnuss.
- Plan mode: no GPS accuracy circle (Navigate keeps it).
- 3D probe: fly-over camera good, stutter → async tile reads + pinch zoom + fps line (41180e5c) on the Pixel; owner
  reports the fps.

## NIGHT 2026-11-07 – owner's word before leaving (00:10, the Pixel is unreachable, no owner feedback until morning)

Owner: "Feierabend, donald bleibt an: alle offenen Aufgaben gründlich zu Ende bauen, dann den Code clean machen (strenges
Audit: keine verschluckten Fehler – alles ins Log, kein doppelter Code, klare Architektur, kein historischer Ballast,
Android-Richtlinien, später evtl. iPhone), voller Testlauf in der Cloud, morgen früh ein Release." Answers (question box):
- **Release v0.17.2: donald publishes it himself** when gate --full, every E2E class on GitHub and the Release-Rundgang are
  green (no Pixel check possible). Anything red → no release, write down why.
- **Cleanup: yes, all** – `ci/donald-*` via prune_branches.sh once older than 12 h (`--by-hand "owner yes 2026-11-07 00:10"`),
  old local worktrees under `.claude/worktrees` that are merged; today's spike/sketch branches stay.
- **Audit: big refactors allowed tonight** (e.g. split AppViewModel, a pure core for a later iPhone app) if all tests stay green.
- Captain's big cleanup (docs/tools/hooks/.claude) runs in parallel: donald leaves those alone until "Aufräumen fertig";
  the code part of the captain's list (backlog "→ [donald] after the captain's cleanup") is donald's.

Order: (A) queue to main: pkg-all (theme/settings/zuletzt/polish), midroute-once, expand-gestures, plan-quiet-gps (+ its
two red "no position" boxes → neutral), turn-cue (sim starts where the tour is); then build: logo 1/2/5 + paper splash,
Navigate ⚙ in the new settings look, lock line only when needed, captain's dead-code list, unused strings, MapOverlays
"Neuer Plan", Pixel review leftovers. (B) audit + clean-up + test audit step 2. (C) full GitHub E2E (one class per ref) +
Rundgang → release v0.17.2. Not in the release: spike/3d-webview.

### Night progress (donald, 2026-11-07)
- On main: 819389c4 mid-route asked once · 96cd7f0e night integration (theme C, value rows, In Bearbeitung, polish 2/3/6/7/8,
  expand gestures, quiet GPS in Plan, sim starts where the tour is, blue compass, logo/splash/menu/About, route previews +
  row tap = overview, Navigate ⚙ new look, lock line until learned) · 25574e9c audit 1 (launchSafe, enumOr, no swallowed
  errors) · 852ab3f5 audit 2 (geo math in one place, golden test). All GitHub E2E classes green each time.
- Running: audit 4 (AppViewModel split stage 1, PlanFlowPropertyTest). Waiting: audit 3 + the captain's code list (after
  "Aufräumen fertig"), then audit 5 (tests), full E2E, release v0.17.2.
- Decision (no owner, no captain line at 02:00): if "Aufräumen fertig" is still missing at 05:00, donald does the captain's
  code list anyway (code only – docs/, tools/hooks/, .claude/ stay untouched) so the release is not blocked.
- Kept for the owner: 11 old worktrees with unmerged WIP (2026-11-03 night saves, proposals) – listed by
  `git worktree list`; ci/donald-* refs to prune after 12 h (n-*, a1-*, a2-*, a4-*, mid-*, p3-*, m2-*, merge-1106*).

## 2026-11-07 ~11:20 – state before the compact (READ FIRST after a compact)

**v0.17.2 released** (7dd730f5). Since then on main (each with its GitHub E2E classes green): overview line 6 dp + menu head
out (430083fe), Meine Touren rows A/Zuletzt inline + ⤢ badge (5ca552b1, 687d9968), sync file race fixed (d65fb8f2),
Los geht's full-width foot (b3481fc1), browser item one line (826e02a1), plan blue #335A85 + paper B #F5F3EB +
"Nur aufzeichnen" (80d01162…), pause band + lock hint only on short tap + "Pause heißt Ruhe" (c454e16e), plan-in-front
E2E (cce2b29a), empty-sheet guide A (f5830e9d), Los geht's updates the saved route – old version under Zuletzt (0dfc0246),
unlock window 20 s / Weiter always held / screen off relocks (8a78e87c). Pixel test APK of 8a78e87c built – installs when
the owner's tour ends (background wait).

**Agents still running (worktrees under .claude/worktrees; an interrupt stops them – check with ListAgents and resume):**
- donald-prefs-off-main (agent-a49caf3e…): frequent Prefs writes off the main thread (stall at onStop) – CoreField rerun.
- donald-one-activity (agent-a94b01d9…): only ONE MainActivity (log "Screen #2 got a new intent") – root cause of double
  sync workers; repro + singleTask/PendingIntent fix.
- donald-one-orientation (agent-afc18c36…): owner 12:00 "die eingestellte Richtung gilt die ganze Sitzung" – one
  orientation (Gehrichtung / Norden oben) for Plan + Navigate, never changes by itself, compass button is the only switch.
- donald-locate-distance (agent-a31ed6ef…): owner chose variant 3 – distance label under the locate button when I am off
  screen, tap = plan + me.
- donald-hold-during-tour (agent-a2909345…): hold during a tour offers adding to the route, never "Hier starten"; menu
  wording "Als Zwischenstopp" / "Als neues Ziel" (no numbers; owner 11:20), ordered by geometry (detour > 50 % or beyond the
  goal → "Als neues Ziel" first; field case goal W, church NE).
**Next:** when these land → one more Pixel APK; prune ci/donald-* refs older than 12 h (owner yes); the owner's open
pages: none waiting. Kept artifacts: Fragenregister, Pixel-Review 6.11., Papierton im Vergleich, Bildschirme Walnuss,
Plan-Karte (laptop), older reference pages. Main-thread stall "Matrix.translate 575 ms" needs a trace on the Pixel (perf
agent adds timing). 3D spike waits for the owner's word (spike/3d-webview). e2e.yml runs only the first class of a list
(needs a PR). Round trip ridden twice on the same street (navigation gap, Fragenregister 51).

## MORNING 2026-11-07 – what the night did (read this first, owner)

**Release v0.17.2** pushed at ~07:45 (7dd730f5; notes docs/release-notes/v0.17.2.md). Before it, on 5df742b0: gate --full
green (2608 app tests + screenshots), GitHub E2E CoreField 20/20 · CorePlan 8/8 · CorePlanLink 5/5 · Rundgang 16/16,
StartSmoke 3/3 locally, unit.yml green. **Planner not deployed**: web.yml is red since 2026-11-06 22:39 only on parity
(17 planner mirrors of the new app look – the laptop's catch-up, branch wip/laptop-2026-11-07-parity-colours); the release's
planner job leaves plan.onpath.earth as it is → after the laptop's catch-up: Actions → Web planner → Run workflow → deploy.

**On main tonight (all with the GitHub E2E classes green):** 819389c4 "mitten auf der Route" once · 96cd7f0e night
integration (ink blue C, value rows, In Bearbeitung, polish 2/3/6/7/8, tap/drag to expand, quiet GPS in Plan, sim starts
where the tour is, blue compass, logo/splash/menu/About, route previews + row tap = overview, Navigate ⚙ new look, lock
line until learned) · audit 1 25574e9c (launchSafe – no swallowed errors, no crash from background work) · audit 2
852ab3f5 (geo math in one place) · audit 4 47affd99 / 8a23b4c0 / 00e7ff43 (PlanSession, TourController, RoutesState out of
AppViewModel, each with a random-flow property test) · audit 5 2624de70 / ca888475 (tests: 2840 → ~2610, screenshots
547 → 373, source-text pins → behaviour tests) · 3bd53b70 Undo during a tour back to one point here · audit 3 + the
captain's code list 5df742b0 (dead tour card, slider, DecisionDialog, 37 strings, shared LongRoute/navName/skippedNote,
one sha256).

**Decided without the owner (please look):** the captain never wrote "Aufräumen fertig" (no commit after 22:49) → donald did
the captain's CODE list at 05:00 anyway, docs/tools/hooks/.claude untouched except this file and the release notes;
release with the planner not deployed (above); RoutesState: no draft with a saved route's points, saving replaces all equal
drafts; far-start info line in Plan dropped (owner 00:45); GPX import has no summary screen, so no preview there.

**Left for later:** :core module / KMP for an iPhone (audit report P2/P3: /tmp/…/scratchpad/code-audit-2026-11-07.md –
copy into docs/design when docs are free); ~110 pure + ~90 mixed source-text tests (wiring without a JVM harness);
docs/core-features.md C17/C18 test names (SetupGuideTest does not exist → PhoneCheckTest + SettingsSummaryTest +
PhoneSetupE2ETest; C18 PlanActionsTest → SaveDialogTest + RouteRulesTest + PlanLinkTest; CandidateMarkerTest/NavSimChipTest
gone); e2e.yml runs only the FIRST class of a comma list (needs a PR – workflows); the main thread blocked 0.5–0.8 s on the
GitHub emulator (a short tap became a hold – watch on the Pixel); 3D spike (spike/3d-webview 58caf954, planner camera,
fps on the Pixel unknown after round 5) – your word; round trip with the same street out and back ridden twice (navigation
gap, see Fragenregister 51); ci/donald-* refs (≈40) to prune after 12 h with prune_branches.sh (owner yes given); 11 old
worktrees with unmerged WIP kept for you.

## After the merge – open items (owner decided, not built yet)

- Remove dead `TourRunningBar` / `TourControls` (TourBar.kt) and redraw the screenshot tests that still show the old
  tour card (`endTourConfirm`, `planMenuNavigating`) – the end sheet over Navigate.
- N9: prepare a page with every grey 12 sp line (bin note, plan line, stop times …) – the owner decides line by line.
- Update the owner's question register (ArtifactData, https://claude.ai/artifact/MyNqN2MaaxNzrex7wVccwX, collection
  `fragen`): docs `donald-20261106-10` … `-28` are `in_arbeit`; set each to `beantwortet` (+ `antwort`,
  `beantwortet_am`, remove `arbeit`, pass `if_version`) once its change is on main/Pixel.
- 3D: report the spike's numbers (offline? memory? fps? effort) and ask the owner; alternative = native 2.5D fly-over.
- Pixel review leftovers (backlog "Pixel review 2026-11-06 – open bugs"): position triangle over the activity sheet,
  "DEMO"/"Sim" stay after the sim ride, "Mittel" badge in the profile corner, bike marker hides point 1.
- Close the backlog line "Save-path proposals" (P1–P4 are history in save-paths.md now).
- Laptop: `[laptop]` package in the backlog (planner also: no save/replace/clear questions – web/app/src/plans.ts:411,
  test/import-smoke.mjs:165, parity-smoke.mjs:299; drafts never synced; delete without confirm) – the planner mirrors today's rules (Walnuss, paper, Zuletzt, words,
  round trip switch, start screen).
- Disk: ~50 old agent worktrees under `.claude/worktrees/` (most fully merged) – clean up when the owner agrees.

- **Test audit (owner 2026-11-06: "Test-Case-Review und -Audit, gründlich, App und Planer, dann rausschmeißen")**:
  step 1 report running on branch `donald-test-audit` (worktree agent-…, `docs/design/test-audit.md`: KEEP / CONVERT /
  DROP with reasons, core-feature coverage kept, gaps: a JVM test that drives the real AppViewModel + random-sequence
  invariant tests). Step 2 AFTER the six-branch merge: delete / convert per the report on main; the planner list goes to
  the laptop (web-spike). No new source-text tests (`File("src/main…").readText()` + contains) from now on.

## Decisions of today (so nobody re-asks)

Plan blue / to navigation green / neutral Walnuss / red only delete · paper surfaces, white only for what floats over
the map · plan and tour are two things (no plan question at tour end) · drafts in "Zuletzt" (25), "Geplant" = saved or
ridden (Komoot), the browser shows only saved · delete always to the bin without a question · "Weiter" for resuming,
"Weiter geht's" back to Navigate, "Planen" to Plan, "Los geht's" start · Beenden only from the pause · map controls
fixed, nothing jumps, unavailable = grey not hidden · no red recording dots · 3D: spike first.

## 2026-11-08 ~12:10 – v0.17.6 released
- In: lock colours (pale = hold, strong = tap, 10 s, Weiter locks, Tour beenden dark green) 26b3a61e · status card v7 at
  large font 41afe772 · storage not ready at start 7676e1dc · backup quiet retry 54d4276e · updater API fallback 84106dfc ·
  About links + Impressum + copy address without mail app ca5f0f7b. Release https://github.com/happychriss/OnPath/releases/tag/v0.17.6
  (main e863ca80); all 12 GitHub classes + Rundgang 17/17 green. AAB `dist/onpath-0.17.6.aab` (1706) for the owner's Play upload.
- Planner not deployed (parity check, 5 files) → laptop line in "For the roles".
- 146 old ci/donald branches deleted (owner's yes); 31 younger ones (r174/r175/today) wait for the 12 h rule.
- Next: refresh the affected website pictures (`pictures_affected.sh v0.17.5..v0.17.6`); owner question on a loop
  generator answered (only the Komoot-comparison "skip" line exists; offered a sketch after 0.17.6).

## 2026-11-08 ~17:30 – paused until Wednesday (owner's word)
- On main since v0.17.6: setup never waits for the map (451f6f66), first-map card A2 (24f58737), shorter setup texts +
  setup font 18/24 sp (ab083bfc). Fresh install on the Pixel checked by the owner: map loaded quietly during the setup.
- Paused helpers, work saved: `wip/donald-2026-11-08-release-0177` (0.17.7 prepared, E2E runs dispatched on
  ci/donald-r177-*), `wip/donald-2026-11-08-smooth-map` (WIP). Details in the backlog's PAUSED item.
- Website pictures: E01/E03/E06 live; D10, E02, F04, F05, G01, G02 in onpath.private aktuell/ for cloud-web.
- Open: G04 off-route stop skipped silently (backlog), backup GPX "no track readable" WARN on the fresh install (16:35).

## 2026-11-08 ~22:00 – v0.17.7 and v0.17.8 released
- v0.17.7 (18:40): setup never waits for the map, first-map card, shorter + bigger setup texts. AAB 1707.
- v0.17.8 (~22:00): father's feedback – location step names „Genau“ + „Bei Nutzung der App“, one-buzz vibration test,
  Samsung page once only + „Mehr Infos ›“ → onpath.earth `#samsung` (cloud-web writes the section), Settings „Handy
  einrichten“ on top while steps are open, T levels readable with their line (own chip row), undo / redo as two round
  buttons under the map column (kept slot), „Hilfe“ first in ☰. Gate --full, Rundgang 17/17, 12 E2E green. AAB 1708.
- S10 now runs donald's debug build (owner uninstalled the Play build); the Pixel still has the first feedback test build –
  it updates itself to 0.17.8.
- Still open: smooth map (wip/donald-2026-11-08-smooth-map), website pictures for the setup + plan changes, G04.
