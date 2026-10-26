package com.onpath.app.gpx

import com.onpath.app.debug.Problems
import com.onpath.strings.*
import org.xml.sax.Attributes
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.xml.parsers.SAXParserFactory

/**
 * Tolerant GPX 1.0/1.1 reader.
 *
 * - Streams with SAX, ignores namespaces and prefixes (so wrong/missing namespaces are fine).
 * - Reads trk/trkseg/trkpt, rte/rtept and wpt; ele and time are optional.
 * - If the XML is broken it keeps everything read before the error and then tries a
 *   regex-based recovery scan; whichever finds more points wins. Never throws for bad
 *   content, only reports warnings. Throws [GpxException] when nothing usable is found.
 */
object GpxParser {

    const val MAX_BYTES = 60L * 1024 * 1024

    fun parse(input: InputStream): GpxData {
        val bytes = readLimited(input)
        return parse(bytes)
    }

    fun parse(text: String): GpxData = parse(text.toByteArray(Charsets.UTF_8))

    fun parse(raw: ByteArray): GpxData {
        val bytes = trimToFirstTag(raw)
        if (bytes.isEmpty()) throw GpxException(Txt.s(Res.string.routes_gpx_empty))

        val handler = Handler()
        // the parser's own error text is technical: it goes to the problem log only, the user gets the plain warning below
        var broken = false
        try {
            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = false
            factory.isValidating = false
            trySetFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            trySetFeature(factory, "http://xml.org/sax/features/external-general-entities", false)
            trySetFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false)
            factory.newSAXParser().parse(ByteArrayInputStream(bytes), handler)
        } catch (e: SAXException) {
            broken = true
            Problems.warn("gpx", "GPX file is not valid XML – imported the readable / recovered part", e)
        } catch (e: Exception) {
            broken = true
            Problems.warn("gpx", "GPX file could not be read fully – imported the readable / recovered part", e)
        }
        var result = handler.result()
        val warnings = handler.warnings.toMutableList()

        if (broken) {
            val recovered = RegexRecovery.parse(String(bytes, Charsets.UTF_8))
            if (recovered.pointCount + recovered.waypoints.size > result.pointCount + result.waypoints.size) {
                warnings += "The file is not valid XML; recovered ${recovered.pointCount} points by scanning it."
                result = recovered.copy(name = recovered.name ?: result.name)
            } else {
                warnings += "The file is damaged; imported the readable part (${result.pointCount} points)."
            }
        }
        if (result.isEmpty) throw GpxException(Txt.s(Res.string.routes_gpx_nothing))
        return result.copy(warnings = warnings + result.warnings)
    }

    private fun trySetFeature(f: SAXParserFactory, name: String, value: Boolean) {
        // Not every XML parser knows every feature. These switch off external entities (XXE) – record it when one
        // cannot be set, so a parser without that protection does not go unnoticed (INFO: Android's parser is safe).
        try { f.setFeature(name, value) } catch (e: Exception) { Problems.info("gpx", "XML parser feature not supported: $name", e) }
    }

    private fun readLimited(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) throw GpxException(Txt.s(Res.string.routes_gpx_too_large, (MAX_BYTES / (1024 * 1024)).toInt()))
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Drops a BOM and any junk before the first '<'. */
    private fun trimToFirstTag(raw: ByteArray): ByteArray {
        val idx = raw.indexOfFirst { it == '<'.code.toByte() }
        if (idx < 0) return ByteArray(0)
        return if (idx == 0) raw else raw.copyOfRange(idx, raw.size)
    }

    internal fun parseTime(s: String?): Long? {
        val t = s?.trim().orEmpty()
        if (t.isEmpty()) return null
        return try {
            OffsetDateTime.parse(t).toInstant().toEpochMilli()
        } catch (_: Exception) {
            try { Instant.parse(t).toEpochMilli() } catch (_: Exception) {
                try { LocalDateTime.parse(t).toInstant(ZoneOffset.UTC).toEpochMilli() } catch (_: Exception) {
                    Problems.warn("gpx", "GPX time value unreadable, point kept without time"); null
                }
            }
        }
    }

    internal fun parseCoord(s: String?, limit: Double): Double? {
        val v = s?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: return null
        return if (v.isNaN() || v < -limit || v > limit) null else v
    }

    private fun local(qName: String): String = qName.substringAfterLast(':').lowercase()

    /** SAX handler that keeps partial results if parsing stops half way. */
    private class Handler : DefaultHandler() {
        val warnings = mutableListOf<String>()
        private var gpxName: String? = null
        private val lines = mutableListOf<GpxLine>()
        private val wpts = mutableListOf<GpxWaypoint>()

        // current state
        private var lineName: String? = null
        private var lineType: String? = null
        private var lineIsRoute = false
        private var inLine = false
        private val segs = mutableListOf<MutableList<TrackPoint>>()
        private var curSeg: MutableList<TrackPoint>? = null
        private var ptKind: String? = null // trkpt / rtept / wpt
        private var ptLat: Double? = null
        private var ptLon: Double? = null
        private var ptEle: Double? = null
        private var ptTime: Long? = null
        private var ptName: String? = null
        private val stack = ArrayDeque<String>()
        private val text = StringBuilder()
        private var badPoints = 0

        override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
            val name = local(qName)
            stack.addLast(name)
            text.setLength(0)
            when (name) {
                "trk", "rte" -> {
                    inLine = true; lineIsRoute = name == "rte"; lineName = null; lineType = null
                    segs.clear(); curSeg = if (lineIsRoute) mutableListOf<TrackPoint>().also { segs += it } else null
                }
                "trkseg" -> curSeg = mutableListOf<TrackPoint>().also { segs += it }
                "trkpt", "rtept", "wpt" -> {
                    ptKind = name
                    ptLat = parseCoord(attr(attrs, "lat"), 90.0)
                    ptLon = parseCoord(attr(attrs, "lon"), 180.0)
                    ptEle = null; ptTime = null; ptName = null
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.appendRange(ch, start, start + length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            val name = local(qName)
            val parent = stack.getOrNull(stack.size - 2)
            val value = text.toString().trim()
            when (name) {
                "ele" -> if (ptKind != null) ptEle = value.replace(',', '.').toDoubleOrNull()?.takeIf { !it.isNaN() }
                "time" -> if (ptKind != null) ptTime = parseTime(value)
                "name" -> when {
                    ptKind != null -> ptName = value
                    parent == "trk" || parent == "rte" -> lineName = value
                    (parent == "metadata" || parent == "gpx") && gpxName == null -> gpxName = value
                }
                "trkpt", "rtept" -> {
                    val lat = ptLat; val lon = ptLon
                    if (lat != null && lon != null) {
                        val seg = curSeg ?: mutableListOf<TrackPoint>().also { segs += it; curSeg = it }
                        seg += TrackPoint(lat, lon, ptEle, ptTime)
                    } else badPoints++
                    ptKind = null
                }
                "wpt" -> {
                    val lat = ptLat; val lon = ptLon
                    if (lat != null && lon != null) wpts += GpxWaypoint(lat, lon, ptName?.ifBlank { null }, ptEle)
                    else badPoints++
                    ptKind = null
                }
                // the line's sport (Komoot); a wpt's own <type> is not the line's
                "type" -> if (ptKind == null && (parent == "trk" || parent == "rte")) lineType = value.ifBlank { null }
                "trkseg" -> curSeg = null
                "trk", "rte" -> closeLine()
            }
            if (stack.isNotEmpty()) stack.removeLast()
            text.setLength(0)
        }

        private fun closeLine() {
            if (!inLine) return
            val clean = segs.filter { it.isNotEmpty() }.map { it.toList() }
            if (clean.isNotEmpty()) lines += GpxLine(lineName?.ifBlank { null }, clean, lineIsRoute, lineType)
            segs.clear(); curSeg = null; inLine = false
        }

        private fun attr(attrs: Attributes, name: String): String? {
            for (i in 0 until attrs.length) {
                if (local(attrs.getQName(i)) == name) return attrs.getValue(i)
            }
            return null
        }

        /** Result so far, including an unterminated trk/rte if the file was cut off. */
        fun result(): GpxData {
            closeLine()
            if (badPoints > 0) Problems.warn("gpx", "GPX: skipped $badPoints points without valid coordinates")
            val w = if (badPoints > 0) listOf("Skipped $badPoints points without valid coordinates.") else emptyList()
            return GpxData(gpxName ?: lines.firstOrNull()?.name, lines.toList(), wpts.toList(), w)
        }
    }
}

class GpxException(message: String) : Exception(message)

/** Last-resort scanner for files that are not well-formed XML. */
internal object RegexRecovery {
    private val pointRe = Regex(
        "<(?:\\w+:)?(trkpt|rtept|wpt)\\b([^>]*?)(/>|>(.*?)</(?:\\w+:)?\\1\\s*>)",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val latRe = Regex("\\blat\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val lonRe = Regex("\\blon\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val eleRe = Regex("<(?:\\w+:)?ele\\s*>([^<]*)<", RegexOption.IGNORE_CASE)
    private val timeRe = Regex("<(?:\\w+:)?time\\s*>([^<]*)<", RegexOption.IGNORE_CASE)
    private val nameRe = Regex("<(?:\\w+:)?name\\s*>([^<]*)<", RegexOption.IGNORE_CASE)
    private val typeRe = Regex("<(?:\\w+:)?type\\s*>([^<]*)<", RegexOption.IGNORE_CASE)
    private val lineRe = Regex("<(?:\\w+:)?(?:trk|rte)\\b", RegexOption.IGNORE_CASE)
    private val segRe = Regex("<(?:\\w+:)?(trkseg|trk|rte)\\b", RegexOption.IGNORE_CASE)

    fun parse(text: String): GpxData {
        val breaks = segRe.findAll(text).map { it.range.first }.toList()
        val segments = mutableListOf<MutableList<TrackPoint>>()
        val wpts = mutableListOf<GpxWaypoint>()
        var current: MutableList<TrackPoint>? = null
        var breakIdx = 0
        for (m in pointRe.findAll(text)) {
            // new segment when a trkseg/trk/rte tag appears before this point
            while (breakIdx < breaks.size && breaks[breakIdx] < m.range.first) { current = null; breakIdx++ }
            val attrs = m.groupValues[2]
            val body = m.groupValues.getOrNull(4).orEmpty()
            val lat = GpxParser.parseCoord(latRe.find(attrs)?.groupValues?.get(1), 90.0) ?: continue
            val lon = GpxParser.parseCoord(lonRe.find(attrs)?.groupValues?.get(1), 180.0) ?: continue
            val ele = eleRe.find(body)?.groupValues?.get(1)?.trim()?.toDoubleOrNull()
            if (m.groupValues[1].equals("wpt", true)) {
                wpts += GpxWaypoint(lat, lon, nameRe.find(body)?.groupValues?.get(1)?.trim(), ele)
            } else {
                val seg = current ?: mutableListOf<TrackPoint>().also { segments += it; current = it }
                seg += TrackPoint(lat, lon, ele, GpxParser.parseTime(timeRe.find(body)?.groupValues?.get(1)))
            }
        }
        val clean = segments.filter { it.isNotEmpty() }
        // the first <type> after the first trk / rte (a waypoint's type before it is not the sport)
        val type = lineRe.find(text)?.let { typeRe.find(text, it.range.first) }?.groupValues?.get(1)?.trim()?.ifBlank { null }
        val lines = if (clean.isEmpty()) emptyList() else listOf(GpxLine(null, clean, false, type))
        val name = nameRe.find(text)?.groupValues?.get(1)?.trim()?.ifBlank { null }
        return GpxData(name, lines, wpts)
    }
}
