# Low battery during Navigate + recording – what the app does today (read-only, 2026-11-28)
Against VISION.md:17 (5a9489605) "… a battery that will not last, the app does not decide alone, it asks; … arriving comes before
recording." Code `8e67a1e`. No proposal.
## 1. What happens at which level
- **Battery level (%): the tour never reads it.** Only live sharing does (`live/LiveEdge.kt:83–85`): below 15 % it uploads every 600 s
  instead of 120 s (`LiveUploader.kt:375, 522`, `LivePolicy.kt:10–12, 39–40`), the viewer sees "Akku 12 %" (`LiveStatusLine.kt:14–15`).
- **Low-battery note, rest view, guidance or screen off by battery level: not found.** Rest view / keep-screen-on follow activity and
  pocket (`ScreenPolicy`, `PocketScreen.kt:27`), not the battery.
- **Battery saver on (user, or the phone itself at 15–20 %):** watched (`RecordingService.kt:484`); only if its location mode stops GPS
  with the screen off: one notification + vibration per tour, "Energiesparmodus stoppt GPS … Schalte ihn für die Tour aus."
  (`RecordingService.kt:696–714`, `PhoneCheck.kt:82`, `strings_navigate.xml:150–151`). Other saver modes: nothing.
- Not battery-driven: slower GPS at rest, GPS off after 60 min lying still (`RestWatch.kt:43, 81`); paused = 10 s GPS, no wake lock
  (`RecordingService.kt:549`). Battery empty: points are in the DB, next start asks continue / save (R-07, `TourSession.kt:216`).
- R-10 "low battery mode below 15 %" is **not planned (2026-11-28)** (`docs/requirements.md:163`).
## 2. Ask or decide alone?
Neither – at a low level nothing happens, so nothing is asked. The saver case is a notice without a choice (it only opens the app).
## 3. What goes first, guidance or recording?
Not found: no code drops either. In practice the phone decides – a GPS-stopping saver hits both (same GPS), an empty battery ends both.
## 4. Where it differs from the new sentence
- "it asks": no question for "battery will not last"; the app never estimates whether the battery lasts to the goal (no level, no %/h –
  the metric in `docs/design/battery-audit.md:267` is not built).
- "arriving before recording": no choice is offered, so no order exists. `docs/requirements.md:257` allows a "critical battery" pop-up
  during navigation – none is built.

## Owner's answer (2026-11-28 10:32, captain thread)
„Eine zusätzliche 20 %-Warnung bringt nichts, kommt von Android. Es sollte auf jeden Fall eine Möglichkeit geben, auf ‚Sparen‘
umzustellen (weniger GPS-Scans, langsameres Live-Teilen – das soll aber an bleiben, sonst machen sich die Angehörigen Sorgen,
gerade bei wenig Batterie). Diese Abschätzung wäre schon nicht schlecht: nach 30 min Wanderung könnte klar sein, wie lange man
noch braucht und wie viel Strom verbraucht wird.“
→ No own %-threshold warning. A „Sparen“ switch the user can set any time (fewer GPS fixes; live sharing slower but **stays on**).
After ~30 min of a navigated tour the app estimates whether the battery lasts to the goal; if not, it **asks** whether to switch
to „Sparen“ (VISION: it asks, arriving first). Nothing else is dropped without asking (screen, recording: not part of it).

## Owner's pick (2026-11-28 10:38, picture akku-varianten.png)
„A und die Frage.“ → switch in ⚙ (group „Akku“ on top, reachable during the tour) + the question once after ~30 min.
Not B (no battery line in the tour card). Captain's reading until the owner says otherwise: ask when the estimate reaches the
goal with < 10 % (measured from ≥ 30 min and ≥ 3 % drop, once per tour); „Akku sparen“ is a setting and stays set; no
„Sparen an“ sign on the tour card (not asked for). Next task (owner, same message): tidy up the settings opened during a route.

## GPS interval in „Sparen“ (owner 2026-11-28 10:55)
„3 s ist auch zu wenig zum Sparen, reichen da nicht alle 30 s?“ → captain's task to donald (branch `feat/akku-sparen`, no merge
yet): on foot 30 s, bike 10 s; back to 1 s within ~150 m of the next turn or when off the route (arriving first). Known
cost, told to the owner: leaving the route on a straight stretch is noticed up to 30 s late; the track cuts corners. The
saving is not measured – a Pixel measurement (with / without, ~1 h each) is proposed before a release.
**Owner 10:56:** „Besser man merkt es nach 30 s, als man hat keinen Akku.“ → 30 s accepted, incl. the late off-route notice.

## „Sparen“ from the question lasts for this tour only (captain, night mode 2026-11-28 12:40, for the owner's review)
Found by cloud-web: tapping [Sparen] in the question set the setting for all later tours – they would be recorded coarser
without the user noticing (VISION: resist what surprises the user). Decided by the vision while the owner is away: [Sparen]
in the question holds until this tour ends; the switch under ⚙ shows it as on meanwhile; a switch set by hand in ⚙ stays set.
Not confirmed by the owner in words: this rule, the 10 % threshold, the 3 % minimum drop.

## Always for the current tour only (owner 2026-11-28 12:55) – replaces the split of 12:40
„Wir leiten einfach um auf diese Einstellungsseite, und bei der Einstellung steht dick und fest, dass sie immer nur für die
aktuelle Route gilt. Dann hat sich das Problem komplett erledigt.“
→ [Sparen] in the question opens ⚙ at „Akku sparen“ (the user sets the switch there). The switch **always** holds for the
current tour only and is off again when the tour ends – also when set by hand. The row says so in bold. Captain's reading
for the case no tour runs: the switch set before the start holds for the next tour, then off. Pill „Akku sparen an“: open
(owner's „vielleicht“ of 11:40, picture akku-pille.png) – not built unless he picks it.
