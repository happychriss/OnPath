package com.onpath.app.gpx

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Writes valid GPX 1.1. Planned routes are written as a track (most apps prefer trk). */
object GpxWriter {

    fun write(
        name: String,
        segments: List<List<TrackPoint>>,
        waypoints: List<GpxWaypoint> = emptyList(),
        timeMs: Long = System.currentTimeMillis(),
        /** Extra XML inside `<metadata><extensions>` (the backup record, backup/BackupCodec); needs [extensionNs]. */
        extensions: String? = null,
        /** Namespace declarations for [extensions], e.g. `xmlns:onpath="…"`. */
        extensionNs: String? = null,
        /** Extra XML per segment (`<trkseg><extensions>`, after its points), aligned with [segments]; null = none. */
        segmentExtensions: List<String?>? = null,
        /** The sport as `<trk><type>` (Komoot's string, [SportType.typeOf]); null = none. */
        type: String? = null,
    ): String {
        val sb = StringBuilder(64 + segments.sumOf { it.size } * 110)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"OnPath\" xmlns=\"http://www.topografix.com/GPX/1/1\" ")
        sb.append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
        extensionNs?.let { sb.append(it).append(' ') }
        sb.append("xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
        sb.append("  <metadata>\n    <name>").append(esc(name)).append("</name>\n")
        sb.append("    <time>").append(time(timeMs)).append("</time>\n")
        extensions?.let { sb.append("    <extensions>").append(it).append("</extensions>\n") }
        sb.append("  </metadata>\n")
        for (w in waypoints) {
            sb.append("  <wpt lat=\"").append(coord(w.lat)).append("\" lon=\"").append(coord(w.lon)).append("\">")
            w.ele?.let { sb.append("<ele>").append(fmt1(it)).append("</ele>") }
            w.name?.let { sb.append("<name>").append(esc(it)).append("</name>") }
            sb.append("</wpt>\n")
        }
        sb.append("  <trk>\n    <name>").append(esc(name)).append("</name>\n")
        type?.takeIf { it.isNotBlank() }?.let { sb.append("    <type>").append(esc(it)).append("</type>\n") }
        for ((si, seg) in segments.withIndex()) {
            if (seg.isEmpty()) continue
            sb.append("    <trkseg>\n")
            for (p in seg) {
                sb.append("      <trkpt lat=\"").append(coord(p.lat)).append("\" lon=\"").append(coord(p.lon)).append("\">")
                p.ele?.let { sb.append("<ele>").append(fmt1(it)).append("</ele>") }
                p.timeMs?.let { sb.append("<time>").append(time(it)).append("</time>") }
                sb.append("</trkpt>\n")
            }
            segmentExtensions?.getOrNull(si)?.let { sb.append("      <extensions>").append(it).append("</extensions>\n") }
            sb.append("    </trkseg>\n")
        }
        sb.append("  </trk>\n</gpx>\n")
        return sb.toString()
    }

    private fun coord(v: Double) = String.format(Locale.US, "%.7f", v)
    private fun fmt1(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun time(ms: Long) = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(ms))

    fun esc(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
            '"' -> append("&quot;"); '\'' -> append("&apos;")
            else -> if (c.code >= 0x20 || c == '\n' || c == '\t') append(c)
        }
    }

    /** Safe file name for exports. */
    /** File name suffix by kind, not translated (a language switch must not rename the files): plan / rec / none. */
    fun kindSuffix(planned: Boolean, recorded: Boolean): String? = when { planned -> "plan"; recorded -> "rec"; else -> null }

    fun fileName(name: String, suffix: String? = null): String {
        val base = name.replace(Regex("[^A-Za-z0-9äöüÄÖÜß _.-]"), "_").trim().take(60).ifBlank { "onpath" }
        // "Hilden - plan.gpx" / "Hilden - rec.gpx": a plan and its ride never overwrite each other (owner 2026-10-28)
        val s = suffix?.replace(Regex("[^A-Za-z0-9äöüÄÖÜß _.-]"), "_")?.trim()?.takeIf { it.isNotEmpty() }
        return (if (s != null) "$base - $s" else base) + ".gpx"
    }
}
