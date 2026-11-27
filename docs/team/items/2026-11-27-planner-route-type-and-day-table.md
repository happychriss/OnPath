# Planner: route type as a segment row + multi-day day table (owner 2026-11-27)

Tag: [laptop] (planner). Design only so far – nothing built. Pictures: `docs/design/img/profilauswahl/`
(full set incl. the rejected variants B–D: project files `profilauswahl/`).

## 1. Route type – variant A (owner's pick)
Today one drop-down mixes three things: activity, hiking difficulty T1–T5, and pace + „Wander-/Radrouten folgen“.
Owner chose **variant A** (`a-segmentleiste.png`, `a-tagestour.png`):
- The six activities as one **segment row of icons** (platform pattern of Apple Maps / Google Maps travel modes), one click,
  always visible – no drop-down.
- Below it **chips only for what fits the activity**: `T2 · Bergwandern ▾` only for Wandern (popover with T1–T5, a 5-step
  meter + the existing one-line meanings, check mark), pace `◷ Mittel ▾`, routes `Standard ▾` (current word: „Wanderrouten
  folgen“ / „Radrouten folgen“, 5abac1f90).
- **Panel order** (owner: the panel is the plan, search belongs on top): search field at the top of the panel → route points →
  route type (segment row + chips) → result (km, ↑↓, time) → Umkehren / Teilen. Route type sits right above the result on
  purpose: switching to Gravel shows the time change in the same spot (Apple / Google / Komoot all keep the mode in the route
  card). Rejected: route type as a capsule on the map (B), tiles (C), a sentence (D).
- With long plans: route type + result as a fixed footer of the panel, only the points scroll (proposal, not yet confirmed).
- **Icons:** today Fahrrad, Gravel, E-Bike, E-MTB share one bike glyph. Make the six distinguishable: E = bolt, MTB = peak +
  heavier tyres, Gravel = knobby (dashed) tyres. Selection colour = GreenLight (`--chip`), plan actions stay blue.

## 2. Multi-day: day table + day view
- **Overview = a table of days** (`mehrtage-tabelle.png`), owner: keep today's day rows (white cards, blue stage chips
  „N Tage · h/Tag · m↑/Tag“) but values in **aligned columns**: `Tag N` (grey „von → nach“ under it) · **km** · **gesamt**
  (running total – owner 2026-11-27 asked for „Gesamtkilometer“; this replaces the 2026-11-20 „no running km“) · **↑ m** ·
  **Zeit** · night moon / goal flag. Right-aligned tabular numbers, a header row, a total row. No points in the overview.
- A day over the limits: row in warn yellow, only the exceeded values bold.
- Route type in the multi-day overview: one compact line at the bottom („Fahrrad · Mittel · Standard – alle Tage“).
- **Click a day → day view** (`mehrtage-tag-geoeffnet.png`): large „‹ Alle Tage“ button (40 px, always visible) + ‹ 2/3 ›
  arrows; day stats; yellow fact line when over („1 h 10 und 120 m über deinem Tag“); search „Ort zu Tag N hinzufügen“; the
  day's points editable; map: only this day in route blue, other days grey.
- **Map → day** (owner): clicking a day's stretch opens that day; holding on a stretch to add a point opens that day too and the
  point belongs to it.
- The app gets the same table: separate item [donald] `2026-11-27-app-day-table.md` – keep both on the shared day-row code
  (`DayRowText`, `WebDays`).
