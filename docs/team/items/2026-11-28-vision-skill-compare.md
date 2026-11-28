# Vision skill (kunchenguid/vision) compared with our captain's guide and VISION.md

Source: github.com/kunchenguid/vision, `skills/vision/SKILL.md` + `README.md` (assets: board template + CSS only), commit
`acf4d65` of 2026-11-22, read 2026-11-28. **Licence: MIT** (© 2026 kunchenguid). Same repository as named in the project
note of 2026-11-26; the copy handed over that day is not in our repos, so it is not checked byte for byte (the commit
predates 2026-11-26). Our side: `docs/team/captains-guide.md` (all, §4 above all) and `VISION.md`, both on main at `93d8ce5`.
Listing only – no judgement, no recommendation.

## (a) In the skill, missing with us

| Skill rule (verbatim) | What it is for |
| --- | --- |
| "Every principle in the draft must be traceable to concrete evidence: named PRs or commits, files, docs, or reasoning the author approved into the vision document." | Each line has a source; values are not invented. |
| "Generic engineering virtues ("we value quality") are banned unless the history demonstrates them specifically." | Keeps lines specific to this project. |
| "list the author's merged PRs, aim for 30-100 titles, and read 8-15 full bodies" … "Extract recurring revealed values: what gets built, what gets refused, what class of bug gets fixed at the root" | Reads values from what was really built or refused, not only from what was said. |
| "Produce a private evidence sheet: value -> supporting PRs, commits, or files." | One place to trace every drafted line. |
| "8-12 concrete change proposals per vision, aimed at the draft's fault lines." (taxonomy: tempting-but-off-mission features, principle collisions, slippery slopes, scope expansions, identity questions) | Finds gaps before a real case hits them. |
| "No softball hypotheticals. … If you can predict the author's answer, replace the hypothetical." | Asks the owner only where the answer is really open. |
| "the concrete proposal (2-4 sentences), the principle it tests (quote the draft), and why the answer is non-obvious (steelman both sides)" | Each question names the line it tests and both sides. |
| "Each author verdict maps to a named edit in a changelog; the author must be able to see exactly how their answer changed the text." | The owner sees what his answer changed. |
| "full draft always fully visible, one hypothetical at a time in a card stack" (board on lavish-axi) | The owner answers with the whole text in view. |
| "propose line-level candidate changes from evidence newer than it, and never write a competing document" … "each independently acceptable" | Changes to an existing vision come as small separate pieces. |
| "`<!-- vision: author-approved YYYY-MM-DD -->`" | Dated baseline; later changes are measured from it. |
| "A reviewer who has never seen the board must be able to accept or resist a change from it alone." | The vision works without the chat it came from. |
| "Identity opener: "X exists so that ...", who it serves, and "It owns exactly one thing: ..."." / "3-6 principle sections" / "Explicit non-goals, named concretely" / "length a page or two (40-70 lines)" | A fixed anatomy and size limit. |
| "One sentence per line. Plain hyphens, never em dashes. No roadmap, no feature list, no marketing voice." / "declarative present tense" | Form rules for the text. |
| "where another section repeats the vision, it proposes a pointer or deletion and applies it only on your yes" | Removes copies of the vision in other files. |
| "Do not approve on their behalf; do not treat silence as approval." | Silence is not approval. |

## (b) With us, missing in the skill

| Our rule (where) | Note |
| --- | --- |
| "an order of priority for when principles collide" (guide §4; VISION: "never lose a tour, then offline on the way, then clear and calm, then powerful") | Skill anatomy has no priority order. |
| "It has a test sentence" (guide §4; VISION: "The test for everything: …") | Skill has only the aligns/resisted pair. |
| Use loop: function fits → scenarios → "Clearly covered → built without asking. Not clearly covered → a question to the owner" (guide §4) | Skill ends when the text is approved; it does not cover daily use. |
| The captain's three checks: fits the vision / asked for / built as decided (guide §4) | – |
| Judging an existing function: "ask *where it sits*" … "Name the situation" … "A count … is not an argument" (guide §4) | – |
| When the vision does not settle it, points 1–6: two principles pull apart → question; sameness never a reason; loser gets a remedy; unknown origin → ask for the situation; layout on a picture; role decisions shown "decided for you" (guide §4) | Skill's "principle collisions" are only a hypothetical type. |
| "Vision text or handling? … first try to fix the handling (this guide)" (guide §4) | – |
| Keeping the vision true: inventory of existing functions, consistency check of the vision itself (guide §4) | – |
| "A design document marks each row as the owner's word (with his quote and date) or a role's proposal; only his rows are requirements, and they get stable IDs" (guide §4) | – |
| "Detail rules live in the project's decision list, never in the vision." (guide §4) | – |
| A role thread reads "the first paragraph of the project's vision (up to the order of priority)" (guide §0, §6) | Skill assumes one reader of the whole text. |
| Roles, loop, board, working with the owner, start (guide §0–§3, §5–§6) | Skill has no counterpart; it covers writing the vision only. |

## (c) Contradictions

| Skill (verbatim) | Ours (verbatim) |
| --- | --- |
| "Fold the author's reasoning into the prose wherever it matters" | "Detail rules live in the project's decision list, never in the vision." (guide §4) |
| "A reviewer who has never seen the board must be able to accept or resist a change from it alone." / "never defers to another section … for its accept/resist test" | "How a function and its scenarios are checked against this vision is described in `docs/team/captains-guide.md` §4" (VISION) |
| "the vision (VISION.md …) is what a reviewer uses" (whole document) | A role thread reads "the first paragraph of the project's vision (up to the order of priority)" (guide §0) |
| "why the answer is non-obvious (steelman both sides)" – no recommendation on the card | "One question at a time, options short, recommendation first." (guide §5); except §4.4 "ask for the situation, with no recommendation" |
| "8-12 concrete change proposals per vision" answered in one review session | "Collected open questions come only when he asks for them." (guide §5); §4 lessons: "each one came from a real case" |
| "Never write, keep, or point to an answers file in the target repo." | "the answer is written into the requirements the same hour, so it is asked once" (guide §4.4) |
| "One sentence per line." | VISION joins clauses with ";" in one line, e.g. "Setting off in a new area needs the network once; a map that has to be built first takes a few minutes, and the app says so." |
| "No roadmap, no feature list" | VISION: "Android comes first; the iPhone comes only after the public launch" / "Later paid extras cover running costs …" |
