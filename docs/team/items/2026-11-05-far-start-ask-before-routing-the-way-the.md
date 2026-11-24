# [donald] Far start: ask before routing the way there (owner 2026-11-05, corrected)

- **[donald] Far start: ask before routing the way there (owner 2026-11-05, corrected)** – a plan loaded in Meran, "Route
  starten" in Hilden → "Hinführen" routed the lead for a very long time, then "no route". No hard limit (owner: 100 km by
  bike is fine for some). Instead, before the lead is routed, when the way there is long (same idea as the plan's
  `LongRoute` question "Diese Route ist sehr lang", here for the lead – threshold e.g. straight line > 25 km, tune with
  the measurements): a box "Der Start ist 790 km entfernt. Trotzdem einen Weg dorthin berechnen? Das kann lange dauern."
  – Berechnen / Nur aufzeichnen. While it routes: the wait card with Abbrechen; at the time limit the slow-plan question
  "Weiter warten?". Field-bug rule: a reproducing test first (`NavPrompt.FarStart`).

**Measured 2026-11-21 (donald, real data, phone-like 192 MB heap, on the PC):** the way there no longer runs without end. „Hinführen“ stops at its 30 s budget and a cancel ends it at once (Düsseldorf → Vellau, ~630 km, hike and bike: `TIMEOUT` at 30.0 s, cancelled at 3.0 s, no crash). Bozen (~25 km) 0.3–0.5 s, Innsbruck (~65 km) 0.6–3.5 s, both found. A phone is slower, so its limit lies nearer. **What is left is the owner's box:** at ~630 km „Hinführen“ is a 30 s wait that cannot succeed. The box, the threshold and the wait card with „Weiter warten?“ are new UI → picture first, build after his yes.

**2026-11-21:** sketch in `onpath.private/ideen/2026-11-21-start-weit-weg/skizze.png`; the owner asked „Hatten wir die Frage nicht schon?“ – not a yes, nothing built. The app already asks („Route beginnt 630 km entfernt“ · Ohne Route weiter / Hinführen, since 10-31); the 11-05 wish adds the warning, Abbrechen and „Weiter warten“.

**Owner 2026-11-21: „Warnsatz mit Abbruch“** – today's box stays, no second question; from ~25 km a warning line; while searching a cancel. Picture: `onpath.private/ideen/2026-11-21-start-weit-weg/skizze-2-warnsatz-abbrechen.png`; built after his yes to it.
