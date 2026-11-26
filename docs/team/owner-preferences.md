# How the owner likes to work (for every Claude session)

Shared by every session (donald, laptop, captain, cloud-web). Product rules (UX, texts, what the app does) are in
`docs/project-status.md` "Decisions"; this file is about **how we work together**. It is the home of the rules for
**questions to the owner** – other files link here.

## Talking with the owner
- **EVERY question to the owner goes in a question box (AskUserQuestion) – never in plain text** (owner 2026-11-24, emphatic:
  „fragen immer über die fragen box, sonst sehe ich das nicht!!!“). A question in plain chat text is not seen. This
  overrides older „ask in plain text“ words in handoffs / the backlog. A night without him: the night-job rule below still
  decides whether to ask at all.
- **Pull requests: the sessions merge, not the owner** (owner 2026-11-24: „ich mag nicht selber mergen, das kannst du!“, then
  „allgemein, wenn alles grün“): the role that built a pull request (or the captain) merges it once every check is green and a
  second model read it, then one line „For the captain“. Exceptions that still ask him in a box: secrets and signing keys,
  outside contributions (forks), anything red or unclear.
- **One question at a time**, with a short table of options and a recommendation first; the owner answers tersely.
  Owner 2026-11-01, the general motto: open questions always as a **question box with answer options, one after the
  other**, each with 2–3 lines of background – never a list of questions in prose. Ask in your own chat (owner
  2026-11-07: no more question issues – he always answered in the chat). Not answered when the session stops → one line
  under "Waiting for the owner" in `docs/team/backlog.md` (who, date, question, recommendation); whoever acts on the answer
  deletes it. "Nie warten": take the recommendation, note it, go on.
- **No numbers toward the owner** (owner 2026-11-02: "sowas wie #19 kann ich mir nicht merken"): in the chat and in
  push messages name a question, commit or item by a short description ("die Frage zum Startknopf"), never by
  its number alone. Numbers stay in the backlog and commit messages for the sessions; the owner may answer
  with a word ("Startknopf: A").
- **Planner deploys in the test phase** (owner 2026-11-06): the planner goes online right after the affected local tests – GitHub's full run continues behind it; a red run is fixed and deployed again (`docs/team/roles/cloud-plan.md`).
- **No Fragenregister any more** (owner 2026-11-17: „brauchen wir nicht mehr“): the page is not written any more; answer in the chat only.
- **The owner does not relay between sessions** (owner 2026-11-17): a word for the captain goes into the backlog under "For the captain" (`← [role] …`) – the captain reads it whenever the owner next writes to it; never ask the owner to pass a message on.
- Plain words, short updates, outcome first. No diffs or file dumps in the terminal; quiet tool calls, one-line results.
- Never name internals toward the owner as product words ("cells", "routing data") – it's "map areas", "the map".
- Komoot is the UX reference (the owner is a long-time Komoot user).

## Product rules
One place: `docs/project-status.md` "Decisions" (no disappearing messages, the owner's chosen words stay, hold-only
gestures, re-routing only after asking, core-feature bugs test first, public text tone, private material in
`happychriss/onpath.private`).

## How sessions work
- **The owner must notice when a session waits** (owner 2026-11-02): on donald / the laptop the Claude push is on
  (`/config`: "Push when actions required" + "Push when Claude decides" – `docs/team/roles/multi-agent-lernen.md` §2); an
  open question outlives the session only as a line under "Waiting for the owner" in `docs/team/backlog.md`.
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
  Release-Rundgang run on GitHub (`tools/ci/e2e_github.sh`) – `docs/team/working-agreement.md` "E2E".
- **Code first for bugs:** when diagnostics exist, find the cause by reading the code and reproduce it in a JVM test.
  Use the phone only briefly to confirm, say how long, say when it is free again, and restore the owner's app state
  before handing it back (a long trial-and-error session on the phone was not OK).
- **Night jobs – the standing rules** (owner 2026-11-20 ~00:05, „merken wir uns für weitere Nachtjobs“): when the owner
  goes to bed with work running, without him repeating it: (1) **no AskUserQuestion boxes** – a box blocks the session
  all night; every role answers its own questions with its recommendation and logs one line `Q … → A … (role, time)` in
  the night's `docs/team/items/<date>-night-answers.md`; (2) work through, never stop with „done“ while the backlog has
  work – a worn-out session writes a handoff and asks for a fresh one; (3) a night captain checks every 15 min (pull,
  „For the captain“, each session's state), wakes stopped sessions with their next step, unblocks a session stuck on a
  box (interrupt + its recommendation as the answer), logs a line only when it acted; (4) **a German morning report** at
  ~06:30 in `docs/team/items/<date>-morning-report.md` (plain words: done, release candidate ready?, the night's
  answers, what needs his word, who works on what). Without „Nachtregel gilt“ (below), publishing needs **his own word
  in the chat of the role that publishes** (owner 2026-11-20: never relayed by the captain) – so such a night ends with
  the release ready to the last step.
- **„Nachtregel gilt“ – the night release rule** (owner 2026-11-26, from the night 26./27.11.: „dann muss ich nicht jeden
  einzeln freigeben“). It is on from the moment the owner **himself** writes „Nachtregel gilt“ – in a role's thread, or to
  the captain, who then passes **his message itself** (quoted / attached, never paraphrased) to every role's thread. It
  holds until he writes again in the morning or says it ends. On top of the night-job rules above it allows, without a
  further question:
  - **Code:** write code on branches, test, commit, push. A red test is a bug to find and fix – keep going, don't stop.
  - **Merge + app release:** merge to `main` and publish the app when `tools/ci/gate.sh --full` is green (plus the
    release round the plan names). Anything red stays out and goes into the morning report; core red → nothing is published.
  - **Website:** switch the website branch live together with the release.
  - **Planner + map:** deploy the planner and the map when green; a map rebuild is allowed once the extraction it uses
    is tested.
  - **Pixel:** when it is on USB, a session may end a running tour to install a test build – the tour is saved, never
    deleted, and the session checks in the log that it was saved.
  - **Taste questions:** decide by `VISION.md`; when it is close, build both variants behind a debug switch and say so in
    the morning report.
  - **Tokens:** at most one helper per session, targeted tests, `--full` only before the merge, no screenshot loops.
  - **Versions:** plain – the next number, no historical in-between versions.
  - **Search:** never from `/`; `/mnt/DATEN` is off limits.
  - **Morning:** the report reaches the owner through the captain.

  **Never under the night rule:** force-push or history rewrite, deleting user data (tours, tracks, settings on the
  phone or the server), anything with money or accounts (stores, payments, sign-ups, keys, secrets).
- **Tokens (owner 2026-11-20, all four chosen):** (1) a session writes a handoff at **~350k context** and the captain
  starts a fresh one – every step re-reads the whole context, a 560k session costs ~4× a 150k one per step;
  (2) **new sessions on Opus**, Fable only for the routing port (Fable costs 2× Opus, 4× Sonnet); (3) **helpers on
  Sonnet** for reading, searching, test runs and pictures (set the model when you start one), one review per piece
  with the big model, not per commit; (4) weekend / small updates: gate + the change's tests, **no full E2E /
  Rundgang per update**.
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

## Setup work (owner 2026-11-05, after the setup audit – lessons in `docs/team/roles/multi-agent-lernen.md` §4)
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
