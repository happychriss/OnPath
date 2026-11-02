# Design review 2026-11-01: OnPath vs Komoot / Organic Maps / OsmAnd

Asked by the owner on 2026-11-01, 22:19 ("wie du das findest im Vergleich zu den anderen Apps, zu Komoot, und
insgesamt zum Design – ist das klar geworden jetzt, wie das funktioniert?"), answered by cloud-app at 22:20 on the
night draft (now on `main`: slider → Pause · Route ändern · Beenden, the plan puck while changing the route, Delete
without a second question, the turn card always on top, the plan buttons as pills). Saved from the chat by donald
2026-11-02 – the chat itself is gone.

## What is clear now (Komoot level or better)
- **Navigation:** the green turn card is always on top (no turn near → "↑ Geradeaus · 2,4 km"), under it time, km
  and arrival, at the bottom the green slider. Calm, it frames the map like Komoot and Google Maps. Nothing on top
  jumps any more – the owner's point (22:14: "dann bleibt das wesentlich stabil und springt nicht immer").
- **Words, not signs:** Pause, Route ändern, Beenden, Aufnahme speichern, Neu planen, Hierhin. Every button says what
  it does. Before: "Stopp", ■ and "Weiter" with three different meanings. Komoot is not clearer.
- **The slider as a lock:** Komoot has none – in the pocket one taps things. With gloves on the bike our way is
  better, as long as plain buttons come after it. They do now.
- **Points and colours:** green, black, red and numbers like Komoot. Familiar.
- **Voice:** two steps on the bike, "Gleich links" on foot, "Bitte umdrehen", "Wieder auf der Route" – close to
  Organic Maps and OsmAnd. Komoot says less, and earlier.

## What still catches
1. **"Tour" and "Aufnahme" both still in the app.** In "Meine Routen" entries are called "Tour 1. Nov.", there is
   "Unterbrochene Tour", on the way it is "Aufnahme" now. Komoot separates **"Geplant"** and **"Unterwegs"**.
   Proposal: one pair – **Route** for planned, **Tour** for ridden, "Aufnahme" only for the running recording (REC);
   then two tabs in "Meine Routen". Needs the owner's word (the owner's words).
2. **Changing the route still looks almost like planning at home.** The activity sign at the position and "Navigation
   fortsetzen" at the bottom help; the top is the same – on purpose: the owner turned down a green "✎ Route ändern …
   Fertig" header (22:07, search + ☰ on top "wie er früher war"). Enough for a start; check on a real ride.
3. **The status card is dense:** time, km, ↑↓, arrival and the goal's name. Komoot shows less on the way. Try on the
   bike first, then shorten.
4. **Untested** at the time – done by donald on 2026-11-02 (tests adapted, screenshots re-recorded, gate green).

## The owner's choices in the draft (chat 21:38–22:17, for reference)
- Kept the slider ("den Schieber möchte ich haben"); the slider unlocks the buttons; "→ Stopp" on it, "Pause · Route
  ändern · Beenden" under it. Paused → "Fortsetzen" at once, no slider.
- Changing the route: "Navigation fortsetzen" + "Route speichern" at the bottom; the position is the activity sign in a
  white circle with a green ring; in Navigate the blue dot stays.
- "Aufnahme beenden?" with "Aufnahme speichern"; the box "Neue / Geänderte Route auch speichern" only when there is a
  new or changed route, empty at first. Delete without a second question, the grey bin note under it.
- Box and slider green like the turn card ("dass alles einheitlich ist"), End with a light red sign – check outdoors
  that the small line on green reads well.
- Turn card always on top ("↑ Geradeaus · 2,4 km"). "✕ Neu planen" (was "Plan schließen"), the three buttons as light
  pills. "Hierhin" stays (owner words stay).
- Open: a message cut off at 22:17 ("Ich mag trotzdem …") – never finished.
- The owner never said "passt" to the whole draft; it was on the Pixel as a test build. Brought to `main` by the
  captain's order (2026-11-01 night).

## Proposed order
1. Make the draft firm: tests, flows, pictures, push (done 2026-11-02).
2. The word pair Route / Tour made consistent – a small round, with the owner's OK (backlog).
3. A real ride, watching points 2 and 3.

Verdict: route, guidance and recording are now visibly three things – that was the main problem before. What is
missing is a consistent naming.
