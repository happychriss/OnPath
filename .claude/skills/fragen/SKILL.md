---
name: fragen
description: Walk the owner through his open questions one at a time – the issues with label frage in happychriss/onpath.private – most important first, each as a question box with background, options and a recommendation; record each answer in its issue. Use when the owner types /fragen or asks "was ist offen?", "welche Fragen habe ich?".
user-invocable: true
---

# /fragen – die offenen Fragen des Owners, eine nach der anderen

Idea from `/ahoy` in github.com/kunchenguid/firstmate; our dashboard is GitHub issues (`tools/ask.sh`, label `frage`).
Talk to the owner in German, plain words (`docs/owner-preferences.md`).

1. `tools/ask.sh offen --json` – every open question with its body and all comments.
2. **Already answered** (a comment from the owner – anything that is not "gelesen – …", "Erledigt: …" or "Antwort im
   Chat: …" written by a session): do not ask again. Mention them in one line at the start ("#10 und #14 hast du schon
   beantwortet, die Sitzungen setzen sie um").
3. **Order by impact, your pick – say so:** ⏸ blocking first (a session waits), then what holds a release or testers,
   then what costs money or data, then the rest; old before new when equal.
4. Start with one short line: how many are open, how many already answered.
5. **One question at a time** as a question box (AskUserQuestion): header = the issue number; the question = the issue's
   question in plain words + 2–3 lines background from its body (who asks, why it matters); the options = the issue's
   A / B / C with the recommendation first and marked "(Empfehlung)"; keep the option texts short.
6. After each answer: `tools/ask.sh antwort <nr> "<Buchstabe> – <Optionstext> (Owner via /fragen)"`. A free-text answer
   goes in as written. Then the next question. Never close an issue – the asking session acts and closes it.
7. "später", "weiter", "skip" → leave that issue untouched, go on. "Stopp" → end and say how many are left.
8. At the end: one line – answered N, left M, which sessions will act (from the `[name]` in the titles).

Do not change anything else: no backlog edits, no code, no pushes.
