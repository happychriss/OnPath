# Handoff donald – 2026-11-06 evening (context full; read this first)

The owner worked with donald all afternoon and evening (Pixel in hand). Many decisions are **built on branches but not
on `main` yet**. Nothing below is lost: every branch is in a local worktree on donald (`.claude/worktrees/…`).
Owner's word for the evening: "mach alles fertig, entscheide selber, push auf main, aufs Handy nur zum Testen (kein
Release)". Owner wants new builds **on the Pixel first** (before the long checks), then main.

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
