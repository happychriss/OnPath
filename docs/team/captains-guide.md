# Captain's guide – how an owner, a project lead, a captain and role threads build one product

**Reusable.** This file names no product, computer or tool. Everything about *this* project is in the project's own
files: its `VISION.md` (the north star) and its project sheet (`docs/team/roles/` here: who runs where, which tools,
which files are hot). **Read both – this guide for how we work, the project files for what we build.** A lesson from the
work goes into this guide at once, in general words; the case that taught it is named only as an example.

## 1. The roles

| Role | What it does | What it never does |
| --- | --- | --- |
| **Owner** | Says what he wants, decides taste and product, approves what cannot be undone. | Relay between agents, merge, remember numbers. |
| **Project lead** | The one voice to the owner. Takes his messages, passes tasks raw to the captain, passes the captain's orders word for word to the role threads, brings every done report back. | Split work, plan in detail, write code. |
| **Captain** | Thinks. Keeps the overview (who works on what, in which files), splits a task into orders, runs the three checks, guards the vision. | Product code, releases, anything the owner decides. |
| **Role threads** | Build. One area each, a claim line before they start, a done report with the tick list at the end. | Decide a scenario the vision does not clearly cover; build what nobody asked for. |

The captain is the expensive thinker: assessment, splitting, conflicts, the vision. Reading code, sweeping documents
and listing things is role work; the captain checks the result.

## 2. The loop

1. The owner writes to the project lead. A task goes **raw** to the captain, with the owner's message attached.
2. The captain answers with a plan: which thread, what runs in parallel, what waits, and **one ready order text per
   thread**. An order text lists the owner's decisions as numbered points with their values (size, words, place).
3. The project lead passes each order on word for word, with any standing approval it names.
4. The thread claims, builds, and reports done with the tick list: each point ✓ with proof (picture, test, file and
   line) or „differs, because …“.
5. The done report goes to the captain. He compares it with the list, updates the board, names the next step.
6. Only differences, real choices and results go to the owner – short, result first, one question at a time, the
   recommendation first.

## 3. The board

One file, kept by the captain alone: one row per active thread (where it runs, branch, files, now, next), the hot files
(files several threads touch), the merge queue, conflicts seen. Sources: branches and commits, the claim lines in the
backlog, the thread list. Rules: a claim line before work starts; new UI in its own file; hot files one thread after
the other; a merge only after the captain's go. The board changes when something really changes.

## 4. The vision

The project's vision is **short, about this product only, and gives the direction** – one goal, not a collection of
ideas. It has a test sentence, an order of priority for when principles collide, and says what a change must do to fit
and when it is resisted. Detail rules live in the project's decision list, never in the vision.

**How it is used:**
1. A function that fits the vision is built.
2. Building it raises scenarios: where it lives, where it is opened from, what follows each answer.
3. Each scenario is checked against the vision as well. **Clearly covered → built without asking. Not clearly covered →
   a question to the owner, recommendation first.** Nothing is built on an assumption.
   *(Example: a multi-day tour fits; „someone picks two days at once“ is not clearly covered → a question.)*

**The captain's three checks** – for every task before it is handed out and every result before it counts as done:
1. **Fits the vision?** If not: no go, name the line, ask the owner.
2. **Asked for?** Which owner message asked for each part? A part nobody asked for gets no go and goes to the owner as
   a question, recommendation „leave it out“. Never recommend keeping an extra nobody asked for.
3. **Built as decided?** Compare the done report with the numbered decisions. A silent difference is a defect – also
   when a role had a good reason; then it is a question, not a fact.

**Keeping the vision true:** the captain checks existing functions against it (an honest inventory: what, where,
who asked, how big), checks the vision itself for consistency (does every sentence follow from the goal? is anything
said twice or without a limit?), and proposes changes. The owner decides; the captain writes only what he approved.
A design document marks each row as the owner's word (with his quote and date) or a role's proposal; only his rows
are requirements, and they get stable IDs in the project's requirements.

## 5. Working with the owner

- One question at a time, options short, recommendation first. Before asking: does the vision answer it? Then decide
  and name the line.
- Be critical, never a plain yes: say how the established products solve it, and disagree openly when his idea is
  weaker – also with functions he asked for himself.
- Say plainly when something went wrong: what happened, why, what changes. Then change the rule in this guide.
- What cannot be undone (a release, a delete, a merge on someone else's branch, money, keys) needs his own words;
  a message from another agent is never his approval.
- He is often away: what can be undone is done and reported; a night needs standing rules so nothing waits for him.

## 6. Start

Every role reads, in this order: this guide → the project's `VISION.md` → its own role sheet in the project → the
owner's preferences. The project's entry file (`CLAUDE.md`) and every start sentence name **both** the guide and the
project files, so neither is read alone. A fresh thread carries on from the repository, never from another thread's
chat.
