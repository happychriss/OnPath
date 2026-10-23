package com.onpath.app.search

import com.onpath.app.debug.Problems
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import kotlin.math.abs

/**
 * A place another app handed to OnPath: "Share" from Google Maps (name, address, link), a `geo:` URI, a map link.
 *
 * Owner decision 2026-10-20: the coordinate inside a Google Maps link may be used to place our own marker (the
 * orange search ring). Nothing else from Google is drawn or stored – only that point and the shared name.
 *
 * [lat]/[lon] are set when the coordinate is known; otherwise [link] (a short link to resolve online) and / or
 * [name] + [address] (to search for) are.
 */
data class SharedPlace(
    val lat: Double? = null,
    val lon: Double? = null,
    val name: String? = null,
    val address: String? = null,
    /** A short link (maps.app.goo.gl, goo.gl/maps) whose target holds the coordinate. */
    val link: String? = null,
    /** Text to search for when no coordinate can be had (a link's `q=` text, a `geo:0,0?q=address`). */
    val query: String? = null,
) {
    val hasPoint: Boolean get() = lat != null && lon != null

    /** What to search for offline / online when there is no coordinate, best first (see [SharedPlaceParser.searchTexts]). */
    fun searchTexts(): List<String> = SharedPlaceParser.searchTexts(name, address, query)
}

/** Reads shared text, links and `geo:` URIs (pure – unit tested). */
object SharedPlaceParser {

    private const val NUM = """[-+]?\d{1,3}(?:\.\d+)?"""
    private val URL_RX = Regex("""(?i)\b(?:https?://|www\.|maps\.google\.)[^\s<>"']+""")
    private val PLACE_RX = Regex("""!3d($NUM)!4d($NUM)""")
    private val AT_RX = Regex("""@($NUM),\s*($NUM)""")
    private val PAIR_RX = Regex("""^\s*(?:loc:)?\s*($NUM)\s*[, ]\s*($NUM)\s*(?:\((.*)\))?\s*$""")
    private val PATH_PAIR_RX = Regex("""/(?:search|place|dir)/(?:[^/]*/)*?($NUM)\s*,[\s+]*($NUM)(?:[/?,]|$)""")
    private val COORD_PARAMS = listOf("q", "query", "ll", "sll", "center", "daddr", "destination", "viewpoint")
    /** "46.6712, 11.1581" (decimal degrees in plain text). */
    private val TEXT_PAIR_RX = Regex("""(?<![\d.])([-+]?\d{1,2}\.\d{3,})\s*,\s*([-+]?\d{1,3}\.\d{3,})(?![\d.])""")
    /** 46°40'16.4"N 11°09'29.2"E – what Google shows for a dropped pin. */
    private val DMS_RX = Regex("""(\d{1,3})°\s*(\d{1,2})['′]\s*(\d{1,2}(?:\.\d+)?)["″]?\s*([NS])[\s,]+(\d{1,3})°\s*(\d{1,2})['′]\s*(\d{1,2}(?:\.\d+)?)["″]?\s*([EW])""")
    private val SHORT_HOSTS = listOf("maps.app.goo.gl", "goo.gl", "g.co")
    /** Names Google gives places without one. */
    private val NO_NAME = setOf("dropped pin", "markierte position", "gesetzte markierung", "segnaposto", "puntina inserita", "pin")

    /**
     * Text from ACTION_SEND (e.g. "Hotel Aurora\nVia Roma 3, 39012 Meran\nhttps://maps.app.goo.gl/x"), [subject]
     * = EXTRA_SUBJECT. Null when there is nothing place-like in it.
     */
    fun fromText(text: String?, subject: String? = null): SharedPlace? {
        val t = text?.trim().orEmpty()
        val sub = subject?.trim()?.takeIf { it.isNotEmpty() && URL_RX.find(it) == null }
        if (t.isEmpty() && sub == null) return null
        val urls = URL_RX.findAll(t).map { it.value.trimEnd('.', ',', ')', ';') }.toList()
        val rest = URL_RX.replace(t, "\n")
        val lines = rest.lines().map { it.trim().trim(',', '–', '-', '·').trim() }.filter { it.isNotEmpty() }

        // The coordinate: from a full link, else from plain coordinates in the text.
        var point: Pair<Double, Double>? = null
        var linkName: String? = null
        var linkQuery: String? = null
        var short: String? = null
        for (u in urls) {
            val info = fromUrl(u) ?: continue
            if (point == null && info.hasPoint) point = info.lat!! to info.lon!!
            if (linkName == null) linkName = info.name
            if (linkQuery == null) linkQuery = info.query
            if (short == null) short = info.link
        }
        val textLines = ArrayList<String>()
        for (l in lines) {
            val c = coordsInText(l)
            if (c != null && isOnlyCoordinate(l)) { if (point == null) point = c; continue }
            textLines += l
        }
        if (point == null) point = coordsInText(rest)
        // Line 1 = name, the rest = address. A "Dropped pin" has no name; the subject only fills a missing name.
        val named = textLines.filter { it.lowercase() !in NO_NAME }
        val dropped = named.size < textLines.size
        var name = if (dropped) null else named.firstOrNull()
        val address = (if (dropped) named else named.drop(1)).joinToString(", ").takeIf { it.isNotEmpty() }
        if (name == null) name = sub?.takeIf { it.lowercase() !in NO_NAME && it != address } ?: linkName
        if (name == null && address == null && point == null && short == null && linkQuery == null) return null
        return SharedPlace(point?.first, point?.second, name, address, if (point == null) short else null, linkQuery)
    }

    private fun isOnlyCoordinate(line: String): Boolean =
        line.replace(TEXT_PAIR_RX, "").replace(DMS_RX, "").trim().isEmpty()

    /** Decimal "lat, lon" or degrees-minutes-seconds in free text. */
    fun coordsInText(s: String): Pair<Double, Double>? {
        DMS_RX.find(s)?.let { m ->
            val g = m.groupValues
            fun dms(d: String, mi: String, se: String, h: String) =
                (d.toDouble() + mi.toDouble() / 60 + se.toDouble() / 3600) * (if (h == "S" || h == "W") -1 else 1)
            val lat = dms(g[1], g[2], g[3], g[4]); val lon = dms(g[5], g[6], g[7], g[8])
            if (valid(lat, lon)) return lat to lon
        }
        for (m in TEXT_PAIR_RX.findAll(s)) {
            val lat = m.groupValues[1].toDouble(); val lon = m.groupValues[2].toDouble()
            if (valid(lat, lon)) return lat to lon
        }
        return null
    }

    fun isShortLink(url: String): Boolean {
        val u = withScheme(url)
        val host = hostOf(u) ?: return false
        return when (host) {
            "maps.app.goo.gl" -> true
            "goo.gl", "g.co" -> pathOf(u).startsWith("/maps") || pathOf(u).startsWith("/kgs")
            else -> false
        }
    }

    /** Is [url] a Google Maps link (full or short)? */
    fun isMapsLink(url: String): Boolean {
        val u = withScheme(url)
        val host = hostOf(u) ?: return false
        if (isShortLink(u)) return true
        if (host == "maps.google.com" || host.startsWith("maps.google.")) return true
        return (host.endsWith("google.com") || host.contains(".google.") || host.startsWith("google.")) && pathOf(u).startsWith("/maps")
    }

    /**
     * A single link: the coordinate when the link contains one (`!3d…!4d…` = the place, preferred over `@lat,lon` =
     * the camera), the place name from `/maps/place/Name/`, a short link to resolve, or a `q=` text to search.
     * Null for links that are not map links.
     */
    fun fromUrl(url: String): SharedPlace? {
        val raw = withScheme(url.trim())
        if (raw.startsWith("geo:", ignoreCase = true)) return fromGeoUri(raw)
        if (isShortLink(raw)) return SharedPlace(link = raw)
        val host = hostOf(raw).orEmpty()
        // consent.google.com/ml?continue=<encoded maps link>: read the link inside.
        val inner = param(raw, "continue")?.takeIf { host.startsWith("consent.") }
        if (inner != null) return fromUrl(inner)
        if (!isMapsLink(raw) && !host.endsWith("openstreetmap.org") && !host.endsWith("osm.org")) return null
        val c = coordsInUrl(raw)
        // Google's place path is "Name, Street, No, Postcode Town, Country": name and address apart, so the address
        // can be searched on its own (field test 2026-10-20: "PRIMUS Garden Bistrot, Via Goethe, 28, 39012 Merano"
        // found nothing online and landed far away; "Via Goethe, 28, 39012 Merano" is found exactly)
        val (name, addr) = splitPlace(placeName(raw))
        val q = (param(raw, "q") ?: param(raw, "query"))?.takeIf { PAIR_RX.matchEntire(it.replace('+', ' ')) == null }?.trim()?.takeIf { it.isNotEmpty() }
        if (c == null && name == null && q == null) return null
        return SharedPlace(c?.first, c?.second, name ?: if (c != null) q else null, address = addr, query = q)
    }

    /** The coordinate inside a map link, or null. Order: place (!3d!4d), coordinate parameters, path, camera (@). */
    fun coordsInUrl(url: String): Pair<Double, Double>? {
        val d = decode(url)
        PLACE_RX.find(d)?.let { m -> pair(m.groupValues[1], m.groupValues[2])?.let { return it } }
        for (k in COORD_PARAMS) param(url, k)?.let { v -> pairOf(v)?.let { return it.first to it.second } }
        PATH_PAIR_RX.find(d)?.let { m -> pair(m.groupValues[1], m.groupValues[2])?.let { return it } }
        // openstreetmap.org/?mlat=46.6&mlon=11.1 (a marker) or #map=15/46.6/11.1 (the view)
        val mlat = param(url, "mlat"); val mlon = param(url, "mlon")
        if (mlat != null && mlon != null) pair(mlat.trim(), mlon.trim())?.let { return it }
        Regex("""#map=\d+(?:\.\d+)?/($NUM)/($NUM)""").find(url)?.let { m -> pair(m.groupValues[1], m.groupValues[2])?.let { return it } }
        AT_RX.find(d)?.let { m -> pair(m.groupValues[1], m.groupValues[2])?.let { return it } }
        return null
    }

    /**
     * Coordinates in a downloaded page – only the place marker (`!3d…!4d…`), never the page's start view: tested
     * 2026-10-20, a Google Maps page for a link without coordinates opens centred on the requester's IP location
     * (APP_INITIALIZATION_STATE / `center=` pointed to Germany for a place in India).
     */
    fun coordsInHtml(body: String): Pair<Double, Double>? {
        val d = body.replace("\\u0021", "!").replace("\\u003d", "=").replace("\\u0026", "&").replace("%21", "!")
        PLACE_RX.find(d)?.let { m -> pair(m.groupValues[1], m.groupValues[2])?.let { return it } }
        return null
    }

    /** `geo:46.6,11.1`, `geo:46.6,11.1?z=15`, `geo:0,0?q=46.6,11.1(Name)`, `geo:0,0?q=Via Roma 3, Meran`. */
    fun fromGeoUri(uri: String): SharedPlace? {
        if (!uri.startsWith("geo:", ignoreCase = true)) return null
        val body = uri.substring(4).removePrefix("//")
        val path = body.substringBefore('?').substringBefore(';')
        val q = param("x:?" + body.substringAfter('?', ""), "q")?.trim()?.takeIf { it.isNotEmpty() }
        val base = pairOf(path)
        q?.let { text ->
            PAIR_RX.matchEntire(text)?.let { m ->
                val p = pair(m.groupValues[1], m.groupValues[2])
                if (p != null) return SharedPlace(p.first, p.second, m.groupValues[3].trim().takeIf { it.isNotEmpty() })
            }
            // geo:lat,lon?q=text: Android maps search the text near that point; we take the point, the text as name.
            if (base != null) return SharedPlace(base.first, base.second, text.substringBefore('(').trim().ifEmpty { null })
            val label = Regex("""\(([^)]*)\)\s*$""").find(text)?.groupValues?.get(1)?.trim()?.ifEmpty { null }
            val addr = text.replace(Regex("""\([^)]*\)\s*$"""), "").trim()
            return SharedPlace(name = label, address = addr.ifEmpty { null }, query = addr.ifEmpty { label })
        }
        return base?.let { SharedPlace(it.first, it.second) }
    }

    /**
     * Texts to search for when no coordinate is known, best first: the address as Google wrote it but without
     * country and province codes ("Via Roma 3, 39012 Meran BZ, Italien" → "Via Roma 3, 39012 Meran"), then
     * "name address", the name, then a link's `q=` text.
     */
    fun searchTexts(name: String?, address: String?, query: String?): List<String> {
        val out = LinkedHashSet<String>()
        val addr = address?.let { cleanAddress(it) }?.takeIf { it.isNotBlank() }
        val nm = name?.trim()?.takeIf { it.isNotEmpty() }
        if (addr != null) out += addr
        if (nm != null && addr != null) out += "$nm, $addr"
        if (nm != null) out += cleanAddress(nm).ifBlank { nm }
        query?.let { cleanAddress(it) }?.takeIf { it.isNotBlank() }?.let { out += it }
        return out.toList()
    }

    /** Drops a trailing country, two-letter province codes ("BZ") and Plus Codes ("MRV7+2Q"). */
    fun cleanAddress(s: String): String {
        val parts = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        while (parts.size > 1 && SearchText.normalize(parts.last()) in AddressQuery.COUNTRIES) parts.removeAt(parts.lastIndex)
        return parts.map { p ->
            p.split(Regex("\\s+")).filterNot { w -> (w.length == 2 && w.all { it.isUpperCase() }) || w.matches(Regex("[23456789CFGHJMPQRVWX]{4,8}\\+[23456789CFGHJMPQRVWX]{2,3}")) }
                .joinToString(" ")
        }.filter { it.isNotEmpty() }.joinToString(", ")
    }

    // ---- helpers ----

    fun valid(lat: Double, lon: Double): Boolean =
        lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 && !(abs(lat) < 1e-9 && abs(lon) < 1e-9)

    private fun pair(a: String, b: String): Pair<Double, Double>? {
        val lat = a.toDoubleOrNull() ?: return null
        val lon = b.toDoubleOrNull() ?: return null
        return if (valid(lat, lon)) lat to lon else null
    }

    private fun pairOf(v: String): Pair<Double, Double>? =
        PAIR_RX.matchEntire(v.replace('+', ' '))?.let { pair(it.groupValues[1], it.groupValues[2]) }

    private fun withScheme(u: String): String =
        if (u.startsWith("http", ignoreCase = true) || u.startsWith("geo:", ignoreCase = true)) u else "https://$u"

    private fun hostOf(u: String): String? = try { URI(u.substringBefore('#').replace(" ", "%20").replace("|", "%7C")).host?.lowercase() } catch (e: Exception) {
        // expected: a broken link is "not a map link"; host read by hand
        Regex("""^[a-z]+://([^/?#:]+)""", RegexOption.IGNORE_CASE).find(u)?.groupValues?.get(1)?.lowercase()
    }

    private fun pathOf(u: String): String = Regex("""^[a-z]+://[^/?#]+(/[^?#]*)?""", RegexOption.IGNORE_CASE).find(u)?.groupValues?.get(1).orEmpty()

    private fun decode(s: String): String = try { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (e: Exception) {
        s // expected: a stray "%" in a link – read it as it is
    }

    /** First value of query parameter [key] (decoded, "+" = space), or null. */
    fun param(url: String, key: String): String? {
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return null
        for (kv in query.split('&')) {
            val i = kv.indexOf('=')
            if (i <= 0) continue
            if (kv.substring(0, i).equals(key, ignoreCase = true)) {
                val v = kv.substring(i + 1).replace('+', ' ')
                return try { URLDecoder.decode(v.replace("%(?![0-9a-fA-F]{2})".toRegex(), "%25"), "UTF-8") } catch (e: Exception) { v } // expected: odd escaping – raw text
            }
        }
        return null
    }

    /** "Name, Via Roma, 3, 39012 Meran BZ, Italien" → name + address (only when the rest looks like an address). */
    fun splitPlace(n: String?): Pair<String?, String?> {
        if (n == null) return null to null
        val parts = n.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 3) return n to null
        val rest = parts.drop(1)
        if (rest.none { p -> p.any(Char::isDigit) }) return n to null
        return parts[0] to rest.joinToString(", ")
    }

    /** "Moonraft+Innovation+Labs" from `/maps/place/Moonraft+Innovation+Labs/@…` (not when it is a coordinate). */
    private fun placeName(url: String): String? {
        val seg = Regex("""/maps/place/([^/?#@]+)""").find(url)?.groupValues?.get(1) ?: return null
        val n = try { URLDecoder.decode(seg.replace("%(?![0-9a-fA-F]{2})".toRegex(), "%25"), "UTF-8") } catch (e: Exception) { seg } // expected: odd escaping
        return n.trim().takeIf { it.isNotEmpty() && pairOf(it) == null && coordsInText(it) == null }
    }
}

/** What one HTTP request answered: status, `Location` header, body (only read when not a redirect). */
data class HttpHop(val code: Int, val location: String?, val body: String?)

/**
 * Follows a short map link (maps.app.goo.gl/…) to the link with the coordinate – off the main thread, ≤ [maxHops]
 * redirects, no cookies. [fetch] is replaced in tests. Never throws: null = no coordinate (offline, unknown link).
 */
class ShortLinkResolver(
    private val fetch: (String) -> HttpHop,
    private val maxHops: Int = 5,
    /** Stop at a redirect that names the place with an address (search that; no ~200 KB page download – faster). */
    private val stopAtAddress: Boolean = false,
) {
    /** The coordinate (and name / q text found on the way) or null when nothing could be read. */
    data class Result(val place: SharedPlace?, val reason: String?, val fromPage: Boolean = false)

    fun resolve(link: String): Result {
        var url = link
        var lastInfo: SharedPlace? = null
        try {
            repeat(maxHops + 1) {
                val hop = fetch(url)
                val loc = hop.location?.let { absolute(url, it) }
                if (hop.code in 300..399 && loc != null) {
                    val info = SharedPlaceParser.fromUrl(loc)
                    if (info != null && info.hasPoint) return Result(info.copy(link = null), null)
                    if (info != null && info.link == null) lastInfo = merge(lastInfo, info)
                    if (stopAtAddress && lastInfo?.address != null) return Result(lastInfo, "address in the link")
                    url = loc
                    return@repeat
                }
                if (hop.code == 200) {
                    val c = hop.body?.let { SharedPlaceParser.coordsInHtml(it) }
                    if (c != null) return Result((lastInfo ?: SharedPlace()).copy(lat = c.first, lon = c.second, link = null), null, fromPage = true)
                }
                return Result(lastInfo, "no coordinate in the link (HTTP ${hop.code})")
            }
            return Result(lastInfo, "too many redirects")
        } catch (e: Exception) {
            Problems.info("search", "Shared map link could not be opened (offline?)", e)
            return Result(lastInfo, "network error (see the problem log)")
        }
    }

    private fun merge(a: SharedPlace?, b: SharedPlace) =
        if (a == null) b else a.copy(name = a.name ?: b.name, address = a.address ?: b.address, query = a.query ?: b.query)

    private fun absolute(base: String, loc: String): String = try { URL(URL(base), loc).toString() } catch (e: Exception) {
        loc // expected: Location is already absolute or unparsable – use as is
    }

    companion object {
        /** The real network: HttpURLConnection without automatic redirects, [timeoutMs] each, body ≤ 2 MB. */
        fun http(userAgent: String, timeoutMs: Int = 6000): (String) -> HttpHop = { url ->
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
                c.setRequestProperty("User-Agent", userAgent)
                c.setRequestProperty("Accept-Language", "de,en;q=0.8")
                val code = c.responseCode
                val loc = c.getHeaderField("Location")
                val body = if (code == 200) (c.inputStream.use { s ->
                    val buf = java.io.ByteArrayOutputStream(); val b = ByteArray(16384); var n = 0
                    while (buf.size() < 2_000_000) { n = s.read(b); if (n < 0) break; buf.write(b, 0, n) }
                    buf.toString("UTF-8")
                }) else null
                HttpHop(code, loc, body)
            } finally {
                c.disconnect()
            }
        }
    }
}

/**
 * Where the position of a shared place came from. Owner 2026-10-22: a place positioned by searching its address
 * (not a coordinate in the link / text / map page) gets a small note on its card that stays ("Position from the
 * address") – the address search may land on the street rather than the door, or on a namesake.
 */
enum class SharedPosition {
    /** A coordinate in the shared text or link (also after following a short link). */
    LINK,
    /** A coordinate read from the downloaded map page, confirmed (or not checkable) by the address. */
    PAGE,
    /** Found by searching the address / name (offline or online), or the address overruled the page's point. */
    ADDRESS;

    /** The persistent note for the place card, or null. */
    val note: String? get() = if (this == ADDRESS) NOTE else null

    companion object {
        const val NOTE = "Position from the address"

        /** [point]: the shown point came from the link / page; [addressWon]: the address search replaced it. */
        fun of(point: Boolean, fromPage: Boolean, addressWon: Boolean): SharedPosition = when {
            !point || addressWon -> ADDRESS
            fromPage -> PAGE
            else -> LINK
        }
    }
}
