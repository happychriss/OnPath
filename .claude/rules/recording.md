---
paths:
  - "app/src/main/java/com/onpath/app/recording/**"
  - "app/src/main/java/com/onpath/app/setup/**"
  - "app/src/main/java/com/onpath/app/backup/**"
  - "app/src/main/java/com/onpath/app/data/**"
  - "app/src/main/java/com/onpath/app/search/**"
  - "app/src/main/java/com/onpath/app/update/**"
  - "app/src/main/java/com/onpath/app/debug/**"
  - "app/src/androidTest/**"
  - "docs/core-features.md"
---
# Recording, data, phone setup, search, updates (loads when you touch those packages or the E2E tests)

| Path | What |
| --- | --- |
| `  recording/` | `RecordingService` (foreground location service, owns navigation / re-routing; every DB write guarded, failures are loud – see `safeDb` / `failLoud`), `LocationTracker`, `PointFilter` (which fixes become points: accuracy, jumps, standing still), `DemoLocation` + `DemoPath` (Developer tools → Demo: simulated fixes along the plan into both trackers, real GPS ignored, tours "Demo · …" – `docs/core-features.md`) |
| `  data/` | `TourDb` (SQLite WAL), `Prefs`, `DataFiles` (required data check), `Geo` helpers, `AppSettings` (Settings page model), `FollowProgress` (remaining distance while following) |
| `  search/`, `update/`, `debug/` | Place / address search (offline index + Photon); in-app updates (`AppUpdater`); `Problems` log, health / jank logging |
| `  setup/` | Phone setup rules (pure): `PhoneCheck` (location precise / on, notifications, vibration felt, battery, brand killers, battery saver, unused-app reset; required vs recommended; "removed by the phone"), `SetupGuide` (step flow) – UI in `ui/setup/` (`PhoneStateReader`, `SetupGuideScreen`, `PhoneSetupSection`), see `docs/design/onboarding-phone-check.md` |
| `  backup/` | Backups: auto GPX copy to Documents/OnPath, restore, recycle bin – see `docs/core-features.md` C11 |

- **Robustness rules** (requirements section 4): never crash on missing files – show a clear screen;
  write every recorded point to SQLite immediately; long work off the main thread; show plain-language
  errors (no stack traces) to the user.

- Emulator (core features end to end): `./gradlew connectedDebugAndroidTest -Ponpath.e2e=true` (x86_64 emulator; on donald only through `tools/dev/device.sh emulator -- …`; CI `.github/workflows/e2e.yml` on every push to main – a red run is fixed at once). `-Ponpath.e2e=true` adds x86_64 libs – never for a released APK. Release-Rundgang: `tools/ci/release_rundgang.sh`, flows in `docs/release-rundgang.md`; every field bug adds one.
