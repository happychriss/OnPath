# iPhone later: one shared Kotlin core (owner question)

Not now – the product is in field tests (bike routing, maps, bug fixes). This note fixes the direction so that work
done now does not have to be redone.

## Direction
Kotlin Multiplatform + Compose Multiplatform: one repo, one Gradle build, one version, one release workflow (APK +
iOS archive on a macOS runner). Map style, PMTiles, sprites, fonts and the data pipeline stay as they are; MapLibre
Native exists for iOS.

## Order (owner agreed)
1. Now: field tests, bike routing, map tuning, bug fixes.
2. Done: EN + DE – strings in **Compose Multiplatform resources** (module `strings/`, `docs/i18n.md`), not Android
   `res/values`: same effort, and the texts never move twice.
3. Later, on a quiet stable release: split the app into a shared `core` module (no Android) and the Android layer –
   its own step, no features mixed in. Interfaces for location, files, clock, vibration, notifications; SQLite via
   SQLDelight; org.json replaced by a small JSON helper or kotlinx.serialization.
4. Only when iOS is a real goal: BRouter on iOS. BRouter is Java + Android only; no open-source iOS port is known
   (Cartograph Maps runs it on iOS, closed source). Try J2ObjC on BRouter's core for a few days (Hilden + Meran on the
   simulator vs Android); if too slow / unstable, port the core to Kotlin common. Ask the BRouter maintainer first.
5. iOS-only parts: background location mode instead of the foreground service, no in-app updater (App Store /
   TestFlight), share sheet, notifications.

## Rules
- Planner, navigation, time, rides, GPX stay free of Android / MapLibre / BRouter / org.json – guarded by
  `SharedCoreRuleTest` (known exceptions listed there; the list may only shrink).
- New user texts go through the string resources (`docs/i18n.md`).
