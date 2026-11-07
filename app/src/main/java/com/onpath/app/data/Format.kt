package com.onpath.app.data

/**
 * Numbers and units as the user reads them. Decimals follow the text language: "12.5 km" in English, "12,5 km" in
 * German. Units stay the same in both (km, m, h, min – as on Alpine signposts:
 * "1 h 05 min"). Machine formats (GPX, file names, logs) never use these.
 */
object Format {
    /** Locale of shown numbers: the phone's German variant when the texts are German, else US English. */
    fun locale(): java.util.Locale =
        if (com.onpath.strings.Txt.language() == "de") java.util.Locale.getDefault() else java.util.Locale.US

    /** A decimal number for the user: number(12.46, 1) = "12.5" / "12,5". */
    fun number(v: Double, decimals: Int): String = String.format(locale(), "%.${decimals}f", v)

    fun distance(m: Double): String = when {
        m < 1000 -> "${m.toInt()} m"
        m < 100_000 -> String.format(locale(), "%.1f km", m / 1000)
        else -> "${(m / 1000).toInt()} km"
    }

    fun duration(s: Double): String {
        val total = (s / 60).toLong()
        val h = total / 60
        val m = total % 60
        return if (h > 0) "${h} h ${m.toString().padStart(2, '0')} min" else "$m min"
    }

    /** Short time to go for the Navigate strip (owner): "2:50 h", under an hour "25 min". */
    fun durationShort(s: Double): String {
        val total = (s / 60).toLong()
        val h = total / 60
        val m = total % 60
        return if (h > 0) "$h:${m.toString().padStart(2, '0')} h" else "$m min"
    }

    fun clock(s: Long): String {
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return "%d:%02d:%02d".format(h, m, sec)
    }

    fun meters(m: Double) = "${m.toInt()} m"
}
