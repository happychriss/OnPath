package com.onpath.app.recording

/**
 * Ascent / descent of a recording (owner 2026-10-30): from the terrain model (DEM, the map's terrain.pmtiles) under each
 * recorded point, not from GPS altitude – GPS altitude wanders by 10–30 m on phones without a barometer and counts that
 * as climbing. Pure, unit-tested; the stored points keep their GPS altitude (raw data never changed).
 *
 * DEM: the heights are averaged over every [DEM_WINDOW_M] walked (z13 terrain ≈ 13 m per pixel, built from ~30 m
 * data – finer steps are noise: on a steep slope a few metres of GPS position error sideways are metres of height),
 * then counted with a [DEM_THRESHOLD_M] hysteresis. Where there is no terrain (outside the downloaded maps), GPS
 * altitude as before: [GPS_THRESHOLD_M] hysteresis per point. A switch between the two starts a new reference (their
 * heights differ by tens of metres – that is no climb).
 *
 * Each call returns what it adds (up, down) – the caller keeps the totals (they survive a restart in the database).
 */
class AscentCounter {
    companion object {
        const val DEM_WINDOW_M = 20.0
        const val DEM_THRESHOLD_M = 3.0
        /** The recording's GPS hysteresis before DEM heights (rides/RideView uses the same for GPS heights). */
        const val GPS_THRESHOLD_M = 5.0
        private val NOTHING = 0.0 to 0.0
    }

    enum class Source { DEM, GPS }

    /** Where the heights come from now (null = no height yet). */
    var source: Source? = null; private set
    private var ref: Double? = null
    private var winSum = 0.0
    private var winN = 0
    private var winLen = 0.0

    /**
     * One accepted point: its terrain height [demM] (null = no terrain there), its GPS height [gpsM] (null = none) and
     * the distance from the previous accepted point [movedM].
     */
    fun add(demM: Double?, gpsM: Double?, movedM: Double): Pair<Double, Double> {
        val dem = demM?.takeUnless { it.isNaN() }
        val gps = gpsM?.takeUnless { it.isNaN() }
        if (dem != null) {
            if (source != Source.DEM) { source = Source.DEM; ref = null; clearWindow() }
            if (ref == null) { ref = dem; return NOTHING }
            winSum += dem; winN++; winLen += movedM.coerceAtLeast(0.0)
            if (winLen < DEM_WINDOW_M) return NOTHING
            val mean = winSum / winN
            clearWindow()
            return step(mean, DEM_THRESHOLD_M)
        }
        if (gps != null) {
            if (source != Source.GPS) { source = Source.GPS; ref = null; clearWindow() }
            return step(gps, GPS_THRESHOLD_M)
        }
        return NOTHING
    }

    /** End of the tour: the last part of a DEM window counts too. */
    fun flush(): Pair<Double, Double> {
        if (source != Source.DEM || winN == 0) return NOTHING
        val mean = winSum / winN
        clearWindow()
        return step(mean, DEM_THRESHOLD_M)
    }

    /** A new reference: no climb across (demo location started / ended, a resumed recording). */
    fun restart() { source = null; ref = null; clearWindow() }

    private fun clearWindow() { winSum = 0.0; winN = 0; winLen = 0.0 }

    private fun step(v: Double, threshold: Double): Pair<Double, Double> {
        val r = ref ?: run { ref = v; return NOTHING }
        val d = v - r
        return when {
            d >= threshold -> { ref = v; d to 0.0 }
            d <= -threshold -> { ref = v; 0.0 to -d }
            else -> NOTHING
        }
    }
}
