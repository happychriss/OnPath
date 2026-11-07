# How the owner likes to work (for every Claude session)

Shared by every session (donald, laptop, captain, cloud-web). Product rules (UX, texts, what the app does) are in
`docs/project-status.md` "Decisions"; this file is about **how we work together**. It is the home of the rules for
**questions to the owner** – other files link here.

## Talking with the owner
- **One question at a time**, with a short table of options and a recommendation first; the owner answers tersely.
  Owner 2026-11-01, the general motto: open questions always as a **question box with answer options, one after the
  other**, each with 2–3 lines of background – never a list of questions in prose. Ask in your own chat (owner
  2026-11-07: no more question issues – he always answered in the chat). Not answered when the session stops → one line
  under "Waiting for the owner" in `docs/backlog.md` (who, date, question, recommendation); whoever acts on the answer
  deletes it. "Nie warten": take the recommendation, note it, go on.
- **No numbers toward the owner** (owner 2026-11-02: "sowas wie #19 kann ich mir nicht merken"): in the chat and in
  push messages name a question, commit or item by a short description ("die Frage zum Startknopf"), never by
  its number alone. Numbers stay in the backlog and commit messages for the sessions; the owner may answer
  with a word ("Startknopf: A").
- **Planner deploys in the test phase** (owner 2026-11-06): the planner goes online right after the affected local tests – GitHub's full run continues behind it; a red run is fixed and deployed again (`docs/roles/cloud-plan.md`).
- **Fragenregister** (owner 2026-11-06): every question the owner asks a session in its chat goes onto the page
  https://claude.ai/artifact/MyNqN2MaaxNzrex7wVccwX (`ArtifactData`, collection `fragen`), so no answer gets lost in the
  scroll. When the question comes: `set` doc `<unit>-<YYYYMMDD>-<n>` with `sitzung` (your unit), `frage` (short, his words),
  `gefragt_am` (ISO UTC), `status` `in_arbeit` (or `beantwortet` at once), `arbeit` (one line: what you are doing),
  `gelesen` false. When answered: `update` (pass `if_version`) `status` `beantwortet`, `antwort` (2–5 plain lines, the
  details stay in the chat), `beantwortet_am`, `arbeit` removed. Small talk and "ok / ja" are not questions.
- Plain words, short updates, outcome first. No diffs or file dumps in the terminal; quiet tool calls, one-line results.
- Never name internals toward the owner as product words ("cells", "routing data") – it's "map areas", "the map".
- Komoot is the UX reference (the owner is a long-time Komoot user).

## Product rules
One place: `docs/project-status.md` "Decisions" (no disappearing messages, the owner's chosen words stay, hold-only
gestures, re-routing only after asking, core-feature bugs test first, public text tone, private material in
`happychriss/onpath.private`).

## How sessions work
- **The owner must notice when a session waits** (owner 2026-11-02): on donald / the laptop the Claude push is on
  (`/config`: "Push when actions required" + "Push when Claude decides" – `docs/roles/multi-agent-lernen.md` §2); an
  open question outlives the session only as a line under "Waiting for the owner" in `docs/backlog.md`.
- **Main session talks and distributes; work runs in background agents** (builds, checks, merges, installs). The
  main session keeps the overview of all agents (task → state → next step), stops stuck ones, and reports without
  being asked. **The chat stays responsive** (owner 2026-11-01): a request that takes longer than ~2 minutes and stands
  on its own goes to a background agent – preferably a fork, which keeps the whole conversation – and the session
  answers at once "läuft: …"; talk, decisions, questions and small changes are done directly (a fresh agent would lose
  the context, and for small things it is slower). A request that needs the same files, Gradle or the same device as a
  running agent waits: "wartet auf X". Not "everything in the background" – that costs context.
- **Keep the owner's computer usable:** Gradle only through `tools/ci/gate.sh` or `tools/dev/gradle_slot.sh` (three
  builds per computer, one while the emulator runs; `gate.sh` uses `--max-workers=2`, nice + ionice); other heavy jobs
  `nice -n 19 ionice -c3`, Docker `--cpus=2`, `OMP_NUM_THREADS=1`. E2E (owner 2026-11-06): the local emulator on donald
  only for the test(s) a change is about, one at a time via `tools/dev/device.sh`; whole E2E classes and the
  Release-Rundgang run on GitHub (`tools/ci/e2e_github.sh`) – `docs/working-agreement.md` "E2E".
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

## Setup work (owner 2026-11-05, after the setup audit – lessons in `docs/roles/multi-agent-lernen.md` §4)
- **Research and check before building**: docs, existing solutions, the sessions' real transcripts; prove platform
  facts with a small test. Never sell an assumption as a fact.
- No new mechanism without a real case; unused for a week → removed. Hooks / `gate.sh` / locks hit every session at
  once → test every combination before the push. Measure before asking the owner. One file per topic. At most one
  setup change per day.

## Worktrees and agents
- Sub-agents (owner 2026-11-01: split work over 3–4 when it costs little overhead): one worktree per editing agent,
  one agent per device (`tools/dev/device.sh`), builds take one of the Gradle slots per computer (`tools/dev/gradle_slot.sh`).
- Remove an agent's worktree only after its completion notice AND when no message to it is pending (twice a resumed
  agent lost its work).
- Temporary folders (`/tmp/…/scratchpad`) are wiped on restart – anything worth keeping goes to the repo,
  `onpath.private`, or a project folder (research files were lost this way).
