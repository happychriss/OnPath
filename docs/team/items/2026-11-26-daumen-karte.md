# Daumen-Karte: Planen und Navigieren neu gelegt (owner 2026-11-26)

Picture page (the agreed design, read it first): https://claude.ai/artifact/7KYwpPhWZqMbJhfc5ctpsk
The four first concepts, for background: https://claude.ai/artifact/UnGGDjPQZqGyGgAksMS4yL

## The owner's words (2026-11-26 evening, project thread)
- "Ich mag die Option B, die komplette für Navigieren und Planen, wirklich sehr. Auch wenn der Umbau ein bisschen größer ist."
- No tab / recording badge in Navigate: "Wenn ich wandere, habe ich immer Aufnahme … Die Navigation soll möglichst frei
  von irgendwas sein." The PLANEN tab stays in Plan.
- "In der Navigation sind alle Meldungen und alle Zeichen nicht blau, sondern grün. Das Einzige, was in der Navigation
  blau ist, ist der Button zum Anhalten und zum Planen." (read literally: both blue; a short check with the owner is open)
- "Du kannst auch alle 3 Sachen gleichzeitig machen. Diese Freigabe gebe ich explizit, inklusive Navigieren."
- "Bei den Farbtönen flexibel sein, vielleicht drehen wir später daran" → every new colour is a named token in
  `OnPathColors`, never a hex in a screen.
- "Beim Bauen direkt die Dateien für die Webseite anpassen" → retake the affected app pictures (DE + EN) into
  onpath.private `marketing/bilder/aktuell/` and tell [cloud-web] with the keys.

## How it ships (owner 2026-11-26 20:37)
"Wir müssen kein volles Release machen zunächst. Es reicht, wenn ihr mir die umgebaute Version aufs Handy pusht und ich
damit experimentieren kann. Und danach machen wir ein Release."
→ Build on a `wip/daumen-karte` branch (not main, so 0.18.11 stays untouched), debug APK onto the owner's Pixel
(`tools/dev/device.sh pixel`), he experiments, colours may still change; main + release only after his word.

## What changes
1. **Empty Plan start (B1):** no plan sheet while the plan is empty and no `EmptyGuide` steps. A floating card at the
   bottom (12 dp from the edges, Paper, 22 dp corners): search field "Wohin soll's gehen?" (owner's text
   `plan_hint_first_point_question` moves into the field) + ☰ right of it in the same row; below it two chips: the
   activity ("Wandern · T2", replaces the round activity button at the map's left) and "Meine Touren". Blue PLANEN tab
   on the card's top edge. No "Zuletzt" list (owner).
2. **Plan with a plan:** the top band (search pill + ☰) goes. The card grows into today's plan sheet with its three
   steps; its top row = summary (time · km · ↑) + 🔍 (add a goal) + ☰ – same row, same place as in 1. Elevation profile
   stays in the half step. ☰ opens upward over the thumb, same entries and order as today.
   Message boxes: 12 dp under the status bar (`MessagePlacement` PLAN: top element = status bar). Map controls column moves
   up. Download chip and „Nur Übersichtskarte · Detailkarte laden" need a new place: top left.
3. **Navigate:** status card stays on top; Anhalten + Planen move into the same floating bottom card (no tab). The hold
   lock (pale = hold, strong = tap) must read as clearly as today. All signs and message boxes in Navigate green
   (FRAGE edge + buttons green there – an exception to "blue edge = question" in `docs/design/ui-style.md`, update it);
   blue only Anhalten + Planen. Ships only after a real tour (VISION).

## Notes
- Files mainly: `PlanTopBar`, `PlanPanel`, `MapScreen`, `MapFooter`, `NavigateScreen` / `TourBar`, `EmptyGuide`,
  `MessagePlacement` (+ its 17 users), ui-style.md, flow docs. ~230 Plan/Navigate snapshots to re-record.
- Not a new feature: only new places for existing controls. Nothing may disappear by itself; the flow
  "choose a destination, check the route, go" stays as short.
