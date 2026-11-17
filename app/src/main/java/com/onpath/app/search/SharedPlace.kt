package com.onpath.app.search

import com.onpath.strings.*
import com.onpath.app.debug.Problems
import java.net.HttpURLConnection
import java.net.URL

/** What one HTTP request answered: status and `Location` header (a page's content is never read). */
data class HttpHop(val code: Int, val location: String?)

/**
 * Follows a short map link (maps.app.goo.gl/…) the way a browser does before it opens a page: one request per
 * redirect, only the status and the `Location` header are read – off the main thread, ≤ [maxHops] redirects, no
 * cookies. What the resolved link itself says is taken (a coordinate `!3d…!4d…` / `@lat,lon` / `?q=lat,lon`, the
 * place name and address in its path, a `q=` text). A Google Maps page is never requested and no page content is
 * read (owner 2026-10-31, A-020: the former read of the place marker from the page's HTML is gone).
 * [fetch] is replaced in tests. Never throws: null = nothing in the link (offline, unknown link).
 */
class ShortLinkResolver(
    private val fetch: (String) -> HttpHop,
    private val maxHops: Int = 5,
    /** Stop at a redirect that names the place with an address (search that at once). */
    private val stopAtAddress: Boolean = false,
) {
    /** The coordinate (and name / q text found on the way) or null when nothing could be read. */
    data class Result(val place: SharedPlace?, val reason: String?)

    fun resolve(link: String): Result {
        var url = link
        var lastInfo: SharedPlace? = null
        try {
            repeat(maxHops + 1) {
                // the redirect ends on a Google Maps page: what its link says was read; the page itself is not loaded
                if (url != link && SharedPlaceParser.isMapsLink(url) && !SharedPlaceParser.isShortLink(url))
                    return Result(lastInfo, "no coordinate in the link (the Google Maps page is not loaded)")
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
        /** The real network: HttpURLConnection without automatic redirects, [timeoutMs] each; no body is read. */
        fun http(userAgent: String, timeoutMs: Int = 6000): (String) -> HttpHop = { url ->
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
                c.setRequestProperty("User-Agent", userAgent)
                c.setRequestProperty("Accept-Language", "de,en;q=0.8")
                HttpHop(c.responseCode, c.getHeaderField("Location"))
            } finally {
                c.disconnect()
            }
        }
    }
}

/**
 * Where the position of a shared place came from. A place positioned by searching its address
 * (not a coordinate in the shared text / link) gets a small note on its card that stays ("Position from the
 * address") – the address search may land on the street rather than the door, or on a namesake.
 */
enum class SharedPosition {
    /** A coordinate in the shared text or link (also after following a short link). */
    LINK,
    /** Found by searching the address / name (offline index, Photon, the phone's address service). */
    ADDRESS;

    /** The persistent note for the place card, or null. */
    val note: String? get() = if (this == ADDRESS) NOTE else null

    companion object {
        /** The note in the app's language (strings_search.xml). */
        val NOTE: String get() = Txt.s(Res.string.search_note_from_address)

        /** [point]: the shown point came from the shared text / link. */
        fun of(point: Boolean): SharedPosition = if (point) LINK else ADDRESS
    }
}
