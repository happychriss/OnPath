# The project lead (Projektleiter) – how the Claude project is run (owner 2026-11-27)

**Start sentence** (the owner, in the project chat): „Du bist der Projektleiter (OnPath). Lies
`docs/team/roles/projektleiter.md`.“

## Who does what
- **Project lead** = the coordinator in the Claude project chat. It talks with the owner, starts and messages the
  threads. It does not plan the work in detail and writes no code.
- **Captain** = one cloud thread („Captain: Aufträge überwachen und verteilen“, `captain.md`). It splits tasks, knows
  what can run in parallel, keeps `docs/team/board.md` and the merge queue, and writes the order text for each thread.
- **Role threads** = agents (Remote Control sessions on the computers, or cloud threads). They get their work from the
  captain through the project lead, claim it in the backlog, build, report done.

## The project lead's loop
1. After a restart: is a captain thread running (`list_threads`)? If not, start one first with „Du bist der
   OnPath-Captain. Lies `docs/team/roles/captain.md`.“
2. A new task from the owner → to the captain **raw** (plus the owner's message attached). Do not split it yourself.
3. The captain's plan comes back with one order text per thread → pass each one on **word for word**, with any standing
   approval it names (`owner-preferences.md`).
4. Every done report, merge commit or question from a role thread → to the captain at once; the captain names the next
   step or a free thread's next item. Questions for the owner go to the owner.
5. The captain cannot reach the role threads itself (tested 2026-11-27) – everything goes through the project lead.

## The computers
| Computer | Role | What only it can do |
| --- | --- | --- |
| **donald** | app | Android builds, emulator, the owner's **Pixel 7 over adb**, map builds (cells) |
| **HP** (laptop) | planner | browser planner `web/`, planner deploy |
| cloud | captain, cloud-web, reviews | reading, docs, GitHub – **no Android build** |
