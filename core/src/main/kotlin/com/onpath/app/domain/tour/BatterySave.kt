package com.onpath.app.domain.tour

/**
 * „Akku sparen“ (Chriss 2026-11-28 10:32 / 10:38; R-10): a setting the user sets any time (⚙ → Akku) or with „Sparen“ in
 * the question ([BatteryEstimate.asks]); it stays set. While on: GPS every [GPS_MS] instead of every second
 * (`RestWatch.normalMs`), live sharing uploads at its slow rate (`LiveUploader`) – it stays on (Chriss: „sonst machen sich
 * die Angehörigen Sorgen“). Nothing else is dropped: screen, guidance and recording stay as they are.
 * The app writes [on] from the stored setting at start and at every change; the recording reads it.
 */
object BatterySave {
    /** GPS rate while saving: a fix every 3 s – about 4 m walking, 20 m at 25 km/h, still before a junction's cue. */
    const val GPS_MS = 3_000L

    @Volatile var on: Boolean = false
}
