# Handoff [tracking] – live tracking, 2026-11-20 afternoon

Start sentence for the fresh session: „Du bist die Rolle **tracking** (OnPath). Lies `docs/team/handoffs/handoff-tracking-2026-11-20.md`,
dann `docs/design/live-tracking.md` (Design, Entscheidungen §12, Bauplan §14 mit den „Built“-Absätzen).“

## What is on main
- `:core` `live/`: link code + keys, item formats, chunker, upload / viewer pacing, status-line rule, the uploader with its
  ports (`LiveUploader.kt`), incl. „the final ended head goes at once“ (1365b8a94). Tests `Live*Test` (50).
- `:app` `live/`: the Android edge (`LiveEdge.attach` in `OnPathApp`, lane beside the recording, `RecordingService`
  untouched) – **dormant: nothing calls `liveStart`**.
- `web/host` `live.js` + migration 0004, `web/app` viewer page `/live/` + `live-smoke.mjs`, engine `WebLive.kt`. Deployed
  by the laptop: page 200, API answers (401 / 404), tables exist. **No tour has ever been uploaded to the online server.**

## Open branches / pull requests
- **`feat/live-share-ui-2`** (pushed, 97eecb765, rebased on main 1365b8a94, gate --full green there; the older
  `feat/live-share-ui` is the first version – superseded, not force-pushed): slice `LiveShareSlice` + UI, built to the
  owner's two decisions:
  1. „Live teilen“ as the **first entry with a symbol in the ⚙ Settings opened from Navigate** (a card at the top, only
     while a tour records; sharing: state row, „Link nochmal teilen“, „Teilen beenden“, „Name auf der Seite“).
  2. The sign that sharing is on **„oben bei den Richtungen“**: three proposals pictured (A pill above the turn card, B a
     dot chip, C „● Live“ inside the turn card – recommended, the branch's code carries C; a free tour falls back to A).
  Gallery for the owner (public repo): https://github.com/happychriss/OnPath/blob/feat/live-share-ui-2/docs/design/live-app-pictures.md
  – also linked in the cloud doc. **Owner 2026-11-20 afternoon (chat boxes): the sign is C** (in the turn card; free tour = A's pill) **and „Ja,
  einbauen“** – the Settings entry as pictured goes to main so donald can ship it; merging makes sharing reachable for
  every user. In work: the review, then the merge.
  **Owner 2026-11-20 (chat, his pick 3 of five names): no „Live“ as a word – the entry is „Standort teilen“ / „Share
  location“, the sign „● Standort“ / „● Location“** (waiting: „Standort · wartet auf Netz“). „Link nochmal teilen“ /
  „Teilen beenden“ stay. [tracking]'s own follow-ups, told to him: share text „Verfolge meine Tour: ‹link›“ / „Follow my
  tour: ‹link›“, viewer page title „OnPath – geteilter Standort“ / „OnPath – shared location“. Change on the branch
  before the merge (strings `strings_live.xml`, web `strings_live.xml`, pictures re-recorded). Before the merge: a big-model review of the branch (recording
  is a core feature), the non-chosen variants removed. After it: `node web/parity/check.mjs --update` is the laptop's
  (Prefs.kt mirror + two new strings files) – a line for him; then a line for donald „ready to ship“. Known gaps: the
  Settings card stays if the tour ends while the page is open; the Android glue (share sheet, collector) has no test.
- **Pull request 17** (`pr/live-host-config`): `wrangler.toml` (per-IP limit `LIVE_RATE`, hourly clean-up cron,
  `run_worker_first`, `LIVE_NEW_PER_IP_DAY`) + `web.yml` (live-smoke in CI). Waits for the owner (backlog „Waiting“).
- `feat/live-server`, `feat/live-viewer`: merged content, kept only for the viewer pictures
  (`docs/design/img/live/` on `feat/live-viewer` – **not on main**; the cloud doc links them). Do not delete (prune rules).

## Waiting for the owner
- His remarks on the viewer page's look (he answered „Passt, aber Kleinigkeiten“ **before he could see the pictures** –
  not a yes). Cloud doc with links: https://claude.ai/artifact/PbX86VASovbaNPWDXY2FYn (complete: gallery link, the three sign proposals, the Settings pictures).
- Pull request 17.

## Still to do (design §10 / §14.7)
- Pixel walk before any release of the feature: screen-off network for hours, battery beside a walk without sharing,
  Android's SQLite under a long catch-up read while recording, a real upload to the online server, „Continue tour“ after
  a kill keeps the link. Phone rules: `docs/team/owner-preferences.md`.
- Privacy text (proposal in the design §13) – by pull request, app string + a line for cloud-web (website).
- Requirements: move „Live tracking“ from out of scope into scope with F-LIVE ids; a help line.
- Not built: „· Live“ in the tour notification (needs `RecordingService.kt`, which may only shrink), a Settings row to
  change the name, the slim engine for the viewer page (it pulls 1.9 MB gzip, the full planner engine), `gpsLost` on the page.
- The secret scanner: frozen test vectors carry `// ggignore` + a comment; keep that for every new frozen value.

## Rules that cost me time
- This session type cannot attach files to the chat and has no artifact upload: pictures reach the owner only as links
  (public repo) – say so, never write „Bilder liegen im Chat“.
- The push hook wants `tools/ci/gate.sh` run in a command *before* the one that pushes.
- `check-web.sh` does not run through on donald (Node 22.14, no JVM results / cell store): run the pieces, then watch
  `web.yml` on the push (`gh run list --workflow "Web planner"`); a newer push cancels the older run.
- Token rules (owner, 2026-11-20): helpers on Sonnet for reading / search / tests / pictures, one big-model review per
  piece; handoff at ~350k.
