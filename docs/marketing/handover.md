# Marketing & Google Play – handover to a new cloud session (2026-10-24)

Started by the web-planner session on the owner's wish ("Marketing und Google Play in einen anderen Chat
auslagern – Text, Bilder, Video"). Work on branch **`marketing`** (made from `main`). Nothing goes to `main` without
the owner's OK; the app on `main` is developed by another session – pull main into this branch when useful, never push
to main. Repo is **public**: never commit secrets, keys, keystore or service-account files.

## Owner and how to talk to him

- Owner happychriss writes German, often from the phone, sometimes from the car. Reply in German, plain words, no
  jargon. Ask **one question at a time** with AskUserQuestion, each self-contained (hours may pass), answerable by
  tapping, recommendation first. "[No preference]" = no decision yet: use the recommendation as a working
  assumption, write it down as "to be confirmed", don't build on it irreversibly.
- Public texts: factual, like telling a friend about it – no marketing speak, no superlatives (see the README tone).

## What OnPath is (read first)

`CLAUDE.md`, `README.md` (the current public voice), `docs/requirements.md` §1–2 (principles: robust, offline, on the
phone, simple, open; no account, no ads, no tracking, no paid regions), `docs/komoot-comparison.md` (why people
switch), `docs/core-features.md`, `docs/project-status.md`, `docs/design/web-planner.md` (browser planner spike on
branch `web-spike`: routing runs in the browser, identical to the phone; paused).

## Scope of this session

1. **Price model** – decide with the owner first (texts depend on it). Draft: `docs/design/funding.md` (copied
   here from `web-spike`). State 2026-10-24:
   - Owner's three wishes (§7): not taken for granted (a price exists); a fixed share of every payment to an aid
     organisation, a share to the owner (fixed costs, time), shown openly ("cash book"); message in the background:
     one person + AI built it, so it can be fair – hikers mainly want a simple, robust app and a fair price.
   - §8 overview of models and reception. Recommendation: free core forever + **OnPath Plus, one-time OR yearly**
     (user chooses, like OsmAnd), optional free try of Plus features; Plus = amount and comfort (more new areas,
     web ⇄ phone sync end-to-end encrypted, future multi-day planning), never a region.
   - Last question asked (not answered – owner moved here): "Plus einmalig ODER Jahr / nur einmalig / nur Abo /
     gratis + freiwillig". Then: which aid organisation, split, prices, shop (merchant of record for web/F-Droid,
     Play Billing in Play), tax adviser before the first euro.
2. **Google Play listing**: app name, short (80) + full (4000) description DE + EN, screenshots (phone, 2–8),
   feature graphic 1024×500, icon 512, privacy policy page, data-safety form answers, content rating; internal test
   track (backlog: secret release key + `PLAY_SERVICE_ACCOUNT_JSON` as GitHub secrets – the owner adds secrets, never
   in the repo). Store texts as files in the repo in the fastlane layout (`fastlane/metadata/android/<locale>/…`),
   which F-Droid also reads.
3. **Images**: screenshots made from the app – Paparazzi (`./gradlew :app:recordPaparazziDebug`, map is a
   placeholder) or the emulator e2e run (`.github/workflows/e2e.yml`, real map) – and graphics designed in HTML and
   rendered in headless Chromium (Playwright pre-installed, `PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers`, never run
   "playwright install").
4. **Video**: plan + script; screen recordings from the emulator (`adb screenrecord` in a CI job) cut with ffmpeg and
   text overlays are possible here; outdoor footage only the owner can film – give him a short shot list.
5. Website / landing text later (no public page without the owner's OK).

Previews for the owner: private claude.ai Artifacts. Note progress in this file; small commits on `marketing`.
