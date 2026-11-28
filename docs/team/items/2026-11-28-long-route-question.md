# Long routes: the question, app and planner alike (owner 2026-11-28 10:50 / 10:52, captain thread)
Finding: `/mnt/project-files/longroute-finding.md` (HP, main 5ce87e342). On 27.11. „Zwischenziel“ went out of the app only
(52246f476 left the planner out); both sides still have „Trotzdem planen“ (PlanStatus.kt:87).

**Owner:** „Das Zwischenziel sollte raus … schwierig für den Benutzer.“ – „Hat mit Mehrtagestour nichts zu tun: entweder passt
rein – dann warten / abbrechen – oder passt nicht.“ For „passt nicht“ (10:50): a hint „erst eine kürzere Route planen und dann
ein neues Ziel eingeben“, „sonst ist der User wirklich aufgeschmissen“.

1. **Fits** (the device can compute it): at once up to ~30 s; above, the app's question „Dauert etwa …“ [Abbrechen] [Berechnen].
   No „Zwischenziel“ sentence or button, no „Trotzdem planen“ – app and planner alike.
2. **Does not fit** (memory): no waiting offered; a plain message with the hint: plan a shorter route first, then enter the
   new goal. No word „Zwischenziel“, no pointer to multi-day. The exact text goes to the owner before it ships.
The planner-only far-away dialog („start a new route there“) stays.

## The text for „does not fit“ (owner 2026-11-28 11:09, his own words)
Title **„Zu lange Route für die Berechnung“**, line **„Wähle ein näheres Ziel. Danach planst du von dort weiter.“**, button [OK]
(takes the point back). Planner and app („Zu weit für das Handy“ is replaced by it) alike. EN proposal, not yet approved:
„Route too long to calculate“ / „Pick a closer destination. Then plan on from there.“
