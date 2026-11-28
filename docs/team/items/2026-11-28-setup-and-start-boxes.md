# Phone setup and start boxes on other phones (read-only audit, 2026-11-28)

Owner 2026-11-28: "auch Leute mit altem Handy berücksichtigen". Code `708d199`. Paths: `PC` = core/.../setup/PhoneCheck.kt, `SG` = core/.../setup/SetupGuide.kt, `PSS` = app/.../ui/PhoneSetupState.kt, `MA` = app/.../MainActivity.kt.

## Steps before the first tour, per phone
Every step has "Später" (SetupGuideScreen.kt:294–373), the required ones too. Required (PC:155–159) = location, precise, location on:
"Später" there means the tour does not start (SG:53, PSS:150–166, end text `setup_tour_needs`), planning still works. Only these three protect the recording (R-06).
Welcome only when no location is granted yet (MA:449–451); first start asks location + precise + location on + voice (PC:180), "Los geht's"
asks location ×3 + notifications, battery, brand, battery saver (PC:181–182). Steps already fine are skipped (PC:185–188, SG:40).

| Case | First start (taps) | At "Los geht's" | Note |
| --- | --- | --- | --- |
| Pixel, Android 13+ | Welcome → Location → system dialog → end page (4) | Notifications → system dialog (2) | = the 6 taps / ~33 s measured |
| Android 8–12 (API 26–32) | same 4 | usually nothing: notifications are on by default | |
| Android 8 (API 26–27) | same 4 | battery + saver never checked: `sdk >= 28` (PhoneStateReader.kt:67–74, PC:150) | risk unseen on the oldest phones |
| Samsung | same 4 | + brand page (PC:149, 57–61): app settings / "Erledigt" / "Später" | once only (PC:207, PSS:65) |
| Xiaomi/Redmi/Poco, Huawei/Honor, OnePlus/Oppo/Realme | same 4 | + brand page, once only | vivo, Meizu, Asus etc. not in `Brand` → no hint |
| Battery saver on | – | + saver step only if its location mode stops GPS with the screen off (PC:76, 150) | also warned once during a tour (PC:82) |
| Location switched off | + "Standort einschalten" → phone settings (MA:809) | asked again (required) | |
| Voice prompts on, no offline voice | + voice step (PC:180, 191) | never at "Los geht's" | |

**Battery:** OnPath checks only "Restricted" (PC:138); it never asks for the battery-optimisation exception (no `IGNORE_BATTERY_OPTIMIZATIONS`
in app/). Samsung/Xiaomi default "Optimised" passes silently – the brand page is the only guard, and it comes once. Unused-app reset and
vibration are never in a guide, only Settings → Phone setup (PC:173–183).
## Same question asked again
- **Notifications:** "Später" at "Los geht's" snoozes 7 days (PSS:84–88, PC:127) – but the same tour then shows the FRAGE
  "Abzweigungen auf dem Sperrbildschirm?" in Navigate at once (NotificationsOff.kt:15, NavStartMessages.kt:62); "Später" there = this
  tour only, so it comes every tour. Two asks within seconds.
- **Location:** four places – welcome guide, locate button (MA:174), the map card "Detailkarte für deinen Standort?" while no
  permission (CellCards.kt:49, 82; BackgroundDownloads.kt:199) and "Los geht's" (MA:176, 193, 198, 431).
- Battery / saver: back after 7 days of snooze. Brand: never again before a tour. Voice: only at first start.
## What can appear at app start, in order
1. Full "missing data" screen – only when blocking data is missing (MA:158–161); a fresh install opens on the map. Blocks.
2. Welcome / setup guide – full-screen page over everything (MA:412–414). Blocks until "Jetzt einrichten" or "Später".
3. Unfinished tour (`UnfinishedDialog`, modal) after the service did not come back (TourSession.kt:216); waits while the guide is
   open (MA:430–432). Blocks until Weiter / Speichern / Löschen; "Weiter" opens the "Los geht's" guide.
4. Backup found (`RestoreOfferDialog`, modal) – only with no tours and Documents/OnPath present, once; waits for the location
   dialog, the guide, the map loading window and any tour (BackupScreens.kt:185–188, MA:254–259). Blocks while shown.
5. Map loading window (the overlay, MapDataFlow.kt:154) while a map area loads. Blocks the map by design (clear state).
6. One box on the map at a time, FRAGE first (MessageCard.kt:98–101), then this order (MapScreen.kt:495–511): slow plan, restore
   question, pair hint, map question (mobile data …), "Detailkarte für hier? · X MB" (only when it must be built, MapDataFlow.kt:746),
   update (no tour; checked once a day, MapScreen.kt:213, 503), error box ("unerwartet beendet" MA:705, failed backup
   AppViewModel.kt:1743, new serious problem MA:152), shared place, pairing note. Boxes are not modal: "Los geht's" stays reachable.
## Where it could be shorter or clearer (proposals, not decided)
1. Notifications: ask in one place only – either at "Los geht's" or as the Navigate question, not both in the same tour.
2. Welcome page: start straight with the location step (saves 1 tap; the welcome text promises "etwa eine Minute").
3. "Los geht's" closes silently; snoozed or missing items stay invisible for 7 days. A one-line state before walking
   ("Benachrichtigungen aus · Akku-Hinweis offen") would answer "was geht und was nicht".
4. Old/aggressive phones: add vivo, Meizu, Asus etc. to `Brand`; consider the battery-optimisation exception (one system dialog)
   instead of only checking "Restricted"; API 26–27 gets no battery check at all.
