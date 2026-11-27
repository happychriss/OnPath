# App: multi-day day table like the planner (owner 2026-11-27)

Tag: [donald]. Owner: „du brauchst ja auch was im Handy“. Design: `2026-11-27-planner-route-type-and-day-table.md` §2,
picture `docs/design/img/profilauswahl/mehrtage-tabelle.png`.
- The multi-day day list (and the day choice of a multi-day tour, coordinate with the running „Mehrtagestouren in Meine Touren
  erkennbar machen“ work) as aligned columns: `Tag N` + grey „von → nach“ · km · gesamt · ↑ m · Zeit · moon / flag.
- On a phone width check first whether all five columns fit at the default font size; if not, sketch to the owner which
  column folds (proposal: „gesamt“ under km in grey) – do not decide alone.
- Over-limit day: warn yellow, exceeded values bold. Tap a day → that day's points, „Alle Tage“ always visible.
- Same shared code as the planner (`DayRowText`, `WebDays`), so both stay equal.
