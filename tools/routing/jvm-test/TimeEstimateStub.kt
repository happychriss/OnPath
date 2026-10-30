package com.onpath.app.time

/** JVM-test stand-in for TimeEstimate (the real one needs AppSettings / gpx): only the clock JunctionCues uses. */
object TimeEstimate {
    fun clock(epochMs: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = Math.round(epochMs / 300_000.0) * 300_000 }
        return "%d:%02d".format(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
    }
}
