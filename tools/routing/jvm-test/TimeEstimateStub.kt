package com.onpath.app.time

/**
 * JVM-test stand-in for TimeEstimate (the real one needs AppSettings / gpx): the clock JunctionCues uses, and a plan time of
 * zero so PlanResult compiles here (DetourHintJvmTest; no test reads that time).
 */
object TimeEstimate {
    fun planSeconds(points: List<com.onpath.app.routing.GeoPoint>, a: com.onpath.app.routing.Activity, f: Factors): Double = 0.0

    fun clock(epochMs: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = Math.round(epochMs / 300_000.0) * 300_000 }
        return "%d:%02d".format(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
    }
}
