# How the owner likes to work (for every Claude session)

Shared by every machine (laptop, desktop "donald", cloud). Product rules (UX, texts) are in `CLAUDE.md` and the
`docs/backlog.md` owner decisions; this file is about **how we work together**.

## Talking with the owner
- **One question at a time**, with a short table of options and a recommendation first; the owner answers tersely.
- Plain words, short updates, outcome first. No diffs or file dumps in the terminal; quiet tool calls, one-line results.
- Never name internals toward the owner as product words ("cells", "routing data") – it's "map areas", "the map".
- Komoot is the UX reference (the owner is a long-time Komoot user).

## Product rules seen again and again (details in CLAUDE.md / backlog)
- **No messages that disappear by themselves** – everything stays until answered or the situation is gone
  ("we are a hiking app – it can rain, stress…"). Nothing may flash.
- **Hold-only gestures in Plan**, no quick-tap actions, no dragging points (delete + add).
- **Re-routing only after asking**; skip passed stops; show the original route grey.
- **Core features**: a field bug first gets a reproducing test, then the fix.
- **Public texts** (README, pitch, store): factual and calm, like telling a friend or a magazine review – what
  happens, no slogans, no superlatives, no exclamation marks, no competitor bashing; Komoot only as dated, sourced
  facts with credit where it is stronger. Everyday usability first; internals are basic requirements. Show drafts
  to the owner before anything goes public.
- **Marketing material is private**: it lives in the owner's local Gitea repo `cneuhaus/Marketing`, folder `OnPath/`
  (zo.fritz.box:3000) – never in this public GitHub repo.

## How sessions work
- **Main session talks and distributes; work runs in background agents** (builds, checks, merges, installs). The
  main session keeps the overview of all agents (task → state → next step), stops stuck ones, and reports without
  being asked.
- **Keep the owner's computer usable:** heavy jobs `nice -n 19 ionice -c3`, ≤ 4 workers (`--max-workers=4`,
  Docker `--cpus=2`, `OMP_NUM_THREADS=1`); prefer the cloud build for big map jobs. No local emulator – e2e runs in
  CI or on the owner's phone.
- **Code first for bugs:** when diagnostics exist, find the cause by reading the code and reproduce it in a JVM test.
  Use the phone only briefly to confirm, say how long, say when it is free again, and restore the owner's app state
  before handing it back (a long trial-and-error session on the phone at bedtime was not OK).
- **Don't weaken CI gates** without the owner's explicit words. Releases are batched unless the owner asks.

## The owner's phone (Pixel 8, adb)
- Install builds with `adb install -r` (**no wipe** – tours and maps stay) unless the owner asks for a fresh install.
  Never `pm clear` / uninstall by accident (that also deletes the map data in `Android/data/…/files/onpath/`).
- Check first that no tour is recording (`adb shell dumpsys activity services com.onpath.app` → RecordingService);
  if one runs, don't install.
- Don't drive the phone (`adb input`) while the owner uses it, e.g. on a hike.
- Map files pushed with `adb push` belong to the shell user – fix permissions so the app can write them.
- Debug builds must include the world overview: `-Ponpath.overviewAssets=<dir with overview/*.pmtiles>`; with the
  configuration cache a changed property may be ignored – use `--no-configuration-cache` and check the APK contains
  `assets/overview/*.pmtiles`.

## Worktrees and agents
- Remove an agent's worktree only after its completion notice AND when no message to it is pending (twice a resumed
  agent lost its work).
- Temporary folders (`/tmp/…/scratchpad`) are wiped on restart – anything worth keeping goes to the repo, the
  Marketing repo, or a project folder (research files were lost this way).
