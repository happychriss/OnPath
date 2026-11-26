# Night answers 2026-11-26/27

One line per question a role answered itself while the owner slept (owner-preferences „Night jobs“).

- Q live viewer: the pill said „Letzte Meldung 10:00“, the row „09:56 · coords“ – which time? → A one moment = the last upload (server clock, no phone skew): status line, pill, „vor N Min.“ count from it; the place row shows only coordinates; only a place > 10 min and > 2 intervals older than the upload says „Position von 09:30“; battery without a second time (laptop, ~23:40)
- Q „Etwas später als geplant“ rule? → A ETA − plannedArrivalMs ≥ 15 min and > 30 % of (planned arrival − last place's time); both phone clock (skew cancels); below off-route and pause, above „Alles okay“; no plannedArrivalMs (older app) → never (laptop, ~23:50)
