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
| 4 | `donald-lockoff-planen` (agent-a6cd46ee77893cf00) | #3 | one bottom bar for Navigate with AND without lock: "⏸ Pause \| ✎ Planen", paused "Weiter · Beenden · round ✎"; status card without buttons; "Sperren" button gone (auto re-lock); words: "Planen", "Weiter" (also end sheet, interrupted tour), pill always "PLANEN", no red REC badge (grey "Pausiert"); green back button "▲ Weiter geht's" (no dot, no numbers); start screen title "Wohin soll's gehen?" | agent was finishing its E2E – check there |
| 5 | `donald-one-point-loop` (agent-af65de494a0aef7f9) | main | field bug 21:18: the one-point rule rebuilt position → point after every delete (no way to an empty plan, point 1 under the puck, "kein Weg gefunden" for 11 m). Fix: rule only ≥ ~50 m, the inserted position start is not a user point, tiny legs no error | check its state |
| 6 | `spike/3d-webview` (agent-af157df928d347de5) HEAD 4e681622 | main | 3D probe: planner's maplibre-gl 3D terrain offline in a WebView (developer tools) + fly-over; report `docs/design/3d-spike.md` | **owner decides after the numbers; never merge without his word** |

Merge plan: build one integration branch from main: #1 → #2 → #3 → #4 → #5 (expect conflicts in AppViewModel,
PlanPanel, MapScreen, NavigateScreen and many screenshots: take code by hand, then re-record the affected screenshot
classes). Then: build APK → **Pixel first** (check `RecordingService` – no install during a tour) → meanwhile push to
`ci/donald-merge-1106` and `tools/ci/e2e_github.sh --ref ci/donald-merge-1106 --tests <classes> --wait` → main when
green → prune the ci branch.

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
