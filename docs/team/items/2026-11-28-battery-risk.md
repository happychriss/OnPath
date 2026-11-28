# Battery risk for the recording (read-only research, 2026-11-28)

Follow-up to [setup-and-start-boxes](2026-11-28-setup-and-start-boxes.md). Today the app checks only "Restricted" (`PhoneCheck.kt:138`),
shows the brand page once (Samsung, Xiaomi, Huawei, OnePlus/Oppo/realme) and never reads `isIgnoringBatteryOptimizations` (not in app/ or core/).
## Does "Optimised" stop a location foreground service with the screen off?
- **Stock Android / Pixel: no evidence.** Android docs: a foreground service keeps the app out of App Standby; Doze "suspends network
  access" and "ignores wake locks" ([Doze](https://developer.android.com/training/monitoring-device-state/doze-standby)); GPS is not named.
  dontkillmyapp/google: the old Doze bug that stopped foreground services was "fixed in 7.0". Our own audit says the same
  (`docs/design/battery-audit.md:197–199`): "Optimised" only hurts the START_STICKY restart after a kill and long stationary breaks.
- **Samsung: yes, documented – but by Samsung's own sleep lists, not the Android setting alone.** dontkillmyapp/samsung: "Apps can no
  longer hold wake lock in foreground services", "Even disabling the system battery restrictions does not save the app from being
  killed"; fix = *Put unused apps to sleep* off, app not in *Sleeping / Deep sleeping*. 07/2024: Samsung "promised to drop the
  non-standard optimizations" (unverified since). Android Central, One UI 8: GPS froze in Komoot even with "Unrestricted".
- **Xiaomi / Huawei / OnePlus:** dontkillmyapp ranks them #2, #1, #3; fixes are brand settings (Autostart, "No restrictions", lock in
  Recents), not the Android exemption. Organic Maps FAQ: default settings "may stop or kill" the app on Samsung, Huawei, Google, Xiaomi,
  OnePlus, Meizu, Asus, Wiko, Lenovo, Oppo, Vivo, Realme, Sony, Motorola, HTC.
- **Missing in our `Brand` list:** Meizu (#5), Asus (#6), Wiko (#9), Lenovo (#10), vivo (#11) (dontkillmyapp.com ranking).
- **Android 8 (API 26–27):** we read neither restriction nor saver there (`PhoneStateReader.kt:67–74`). No source says 8 is worse; unchecked.
## May a recording app ask for the exemption (Play)?
- Android docs, Play note: direct requests are prohibited "unless the core function of the app is adversely affected"; the table
  of acceptable cases lists chat/VoIP without push, safety apps, task automation – **location tracking / fitness is not listed**.
- The Play Console permission pages (answer/16558241, preview 16909972) say nothing about `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
- So: a direct dialog is a judgement call at review, not clearly allowed. The no-permission path is always fine:
  `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` (the list) or App info. Our earlier decision (`onboarding-phone-check.md:34, 180`) stands.
## What comparable apps do (documented only)
- OsmAnd docs: "Exclude OsmAnd from Battery optimization", power saving off; adaptive battery "can still kill foreground services
  such as OsmAnd's trip recording". Manual steps, no in-app dialog mentioned.
- Organic Maps FAQ: manual steps, links to dontkillmyapp. Komoot: only forum posts (manual steps). Whether any of them shows the
  system exemption dialog in the app: **unverified** (would need their manifests / APKs).
## Our own field cases
- None found. docs/core-features, docs/team/items and onpath.private name Galaxy S10 tests (voice, pairing, speed), no recording stop
  on Samsung/Xiaomi. Recorded detection exists (unfinished-tour question, `TourInterruptedReceiver`, `GpsWatchdog`), but Diagnostics
  does not record the exemption state, so a future gap cannot be tied to "Optimised" vs "Unrestricted".
## Recommendation (for the captain / owner)
1. Diagnostics + phone check: read `isIgnoringBatteryOptimizations` and log it at tour start (cheap, no permission, makes field cases explainable).
2. On brand phones (incl. the added Meizu, Asus, Wiko, Lenovo, vivo): the battery step at "Los geht's" while not exempt → App info /
   the exemption list (no dialog permission). Stock Android/Pixel: keep as today ("Restricted" only) – no evidence of harm.
3. Do not add `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` before the Play test; revisit only with a field case on a brand phone.
4. Field test: one 2 h screen-off hike on a Samsung with default settings, before/after (battery-audit.md:73 already asks for it).
Unproven: Samsung behaviour after 2024, One UI 8 freeze cause, Android 8 risk, what Komoot/OsmAnd do in code.
