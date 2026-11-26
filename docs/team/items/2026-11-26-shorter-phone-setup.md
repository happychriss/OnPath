# Shorter phone setup at first start (owner 2026-11-26)

**(owner)** Decided in the thread „App-Oberfläche gegen Vision prüfen“ on 2026-11-26 („Die Verkürzung der Einrichtung
gefällt mir, das können wir so machen“). Background: VISION.md „Simple and calm“ – the way in stays short, platform
standard, ask only when it is needed. Not part of the Daumen-Karte work unless the captain says so.

Today the guide (`ui/setup/SetupGuideScreen.kt`, `ui/PhoneSetupState.kt`, `MainActivity`) can show up to nine steps:
location, precise location, location on, notifications, vibration, battery, phone brand, battery saver, unused-app
pausing, voice prompts.

## Target
- **First start:** only location (incl. precise / location on when needed).
- **First tour start („Los geht’s“):** notifications and the battery exception, asked there, with the reason in one
  line. The tour starts either way; a „no“ never blocks it.
- **Phone brand hints and battery saver:** only on phones that are affected (brand list, saver on), or after a
  recording had a gap / was stopped by the system (watchdog, interrupted tour).
- **Vibration:** no question; it simply works (setting stays in Settings → Navigation).
- **Voice prompts:** no step at first start; the language follows the phone. Open point for donald: if the phone
  lacks the voice, say so at the first tour with voice, not up front.
- **Unused-app pausing:** not at first start; after the first saved tour at the earliest, or only in Settings → Phone
  setup.
- Settings → Phone setup keeps showing every check with ✓ / ⚠ / ✗ as today.

Robustness first: nothing here may make a recording more likely to stop. If a check moves later, the place it moves
to must come before the tour can lose points (battery exception before the first recording).
