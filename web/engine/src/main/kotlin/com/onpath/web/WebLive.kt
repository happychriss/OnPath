package com.onpath.web

import com.onpath.app.data.Format
import com.onpath.app.live.LiveChunker
import com.onpath.app.live.LiveFix
import com.onpath.app.live.LiveHead
import com.onpath.app.live.LiveItem
import com.onpath.app.live.LiveKeys
import com.onpath.app.live.LivePoint
import com.onpath.app.live.LivePolicy
import com.onpath.app.live.LiveState
import com.onpath.app.live.LiveStatusLine
import com.onpath.app.routing.GeoPoint

/**
 * Live tracking for the viewer page (docs/design/live-tracking.md §3, §5.1, §14.5): the app's own code
 * (com.onpath.app.live) behind small string calls – the link's keys, opening a sealed item, which status line wins and
 * when the page asks the server again. Stateless: the page keeps the link's code in memory and passes it in.
 * No rule is written again in TypeScript. The exports (liveKeys, …) sit in WebApi.kt.
 *
 * The `seal…` calls are the phone's side for the browser test (live-smoke.mjs – a fake phone), as syncJoinBlob is for
 * the sync; the page never calls them.
 */
object WebLive {
    private fun str(s: String) = Engine.str(s)
    private fun num(v: Number?) = v?.toString() ?: "null"

    /** {box, token} – the box's name and the read token – for a live link or its code, or "null". */
    fun keys(link: String): String {
        val k = LiveKeys.of(link) ?: return "null"
        return "{\"box\":" + str(k.boxId) + ",\"token\":" + str(k.readToken) + "}"
    }

    /**
     * The sealed item [blob] (Base64url, as the server gives it) stored as [name], opened with the link's key:
     * head → {kind:"head", state:"recording"|"paused"|"ended", fix:{lat,lon,ele,time,accuracy}|null, gpsLost, arrived,
     *   offRouteM, distanceM, ascentM, movingS, startedMs, toGoM, arrivalMs, stillS, batteryPct, activity, name,
     *   counter, nextS, chunks, distanceText, movingText, ascentText, toGoText (the app's Format)};
     * route → {kind:"route", line:[[lat,lon],…]} (empty = the plan was dropped);
     * chunk → {kind:"chunk", n, points:[[seg,lat,lon,ele|null,timeMs],…]}.
     * "null" = not ours, damaged, moved, or a newer format.
     */
    fun open(link: String, name: String, blob: String): String {
        val keys = LiveKeys.of(link) ?: return "null"
        val plain = LiveItem.fromText(blob)?.let { LiveItem.open(keys, name, it) } ?: return "null"
        return when {
            name == LiveItem.HEAD -> LiveItem.readHead(plain)?.let(::headJson)
            name == LiveItem.ROUTE -> LiveItem.readRoute(plain)?.let { line ->
                line.joinToString(",", "{\"kind\":\"route\",\"line\":[", "]}") { "[" + it.lat + "," + it.lon + "]" }
            }
            else -> LiveItem.readChunk(plain)?.let { pts ->
                pts.joinToString(",", "{\"kind\":\"chunk\",\"n\":" + LiveItem.chunkNumber(name) + ",\"points\":[", "]}") {
                    "[" + it.seg + "," + it.lat + "," + it.lon + "," + num(it.eleM) + "," + it.timeMs + "]"
                }
            }
        } ?: "null"
    }

    private fun headJson(h: LiveHead): String = "{\"kind\":\"head\",\"state\":" + str(h.state.name.lowercase()) +
        ",\"fix\":" + (h.fix?.let { "{\"lat\":" + it.lat + ",\"lon\":" + it.lon + ",\"ele\":" + num(it.eleM) + ",\"time\":" + it.timeMs +
            ",\"accuracy\":" + num(it.accuracyM) + "}" } ?: "null") +
        ",\"gpsLost\":" + h.gpsLost + ",\"arrived\":" + h.arrived + ",\"offRouteM\":" + num(h.offRouteM) +
        ",\"distanceM\":" + h.distanceM + ",\"ascentM\":" + h.ascentM + ",\"movingS\":" + h.movingS + ",\"startedMs\":" + h.startedMs +
        ",\"toGoM\":" + num(h.toGoM) + ",\"arrivalMs\":" + num(h.arrivalMs) + ",\"stillS\":" + num(h.stillS) +
        ",\"batteryPct\":" + num(h.batteryPct) + ",\"activity\":" + str(h.activity) + ",\"name\":" + str(h.name) +
        ",\"counter\":" + h.counter + ",\"nextS\":" + h.nextS + ",\"chunks\":" + num(h.chunks) +
        // the numbers as the app writes them (data/Format, in the page's language – setLanguage)
        ",\"distanceText\":" + str(Format.distance(h.distanceM.toDouble())) + ",\"movingText\":" + str(Format.durationShort(h.movingS.toDouble())) +
        ",\"ascentText\":" + str(Format.meters(h.ascentM.toDouble())) +
        ",\"toGoText\":" + (h.toGoM?.let { str(Format.distance(it.toDouble())) } ?: "null") + "}"

    private fun head(link: String, headBlob: String): LiveHead? {
        if (headBlob.isEmpty()) return null
        val keys = LiveKeys.of(link) ?: return null
        return LiveItem.fromText(headBlob)?.let { LiveItem.open(keys, LiveItem.HEAD, it) }?.let(LiveItem::readHead)
    }

    /**
     * The status line (LiveStatusLine – §3's table and its priority): {key, minutes, metres, atMs, amber, arrived}, key =
     * "stopped" | "broken" | "no_data" | "ended" | "no_contact" | "off_route" | "pause" | "moving".
     * [headBlob] = the newest head as the server gave it ("" = none yet); [sinceLastWriteMs] = the server's `now` minus
     * its `lastWrite` plus the time since that answer; [endedMs] = the server's end mark, < 0 = none.
     * A link without a key, or a head that does not open with it, is "broken" whatever [broken] says.
     */
    fun status(link: String, headBlob: String, gone: Boolean, broken: Boolean, sinceLastWriteMs: Double, endedMs: Double): String {
        val h = head(link, headBlob)
        val bad = broken || LiveKeys.of(link) == null || (headBlob.isNotEmpty() && h == null)
        val l = LiveStatusLine.of(h, gone, bad, sinceLastWriteMs.toLong(), endedMs.takeIf { it >= 0 }?.toLong())
        return "{\"key\":" + str(l.key.name.lowercase()) + ",\"minutes\":" + num(l.minutes) + ",\"metres\":" + num(l.metres) +
            ",\"atMs\":" + num(l.atMs) + ",\"amber\":" + l.amber + ",\"arrived\":" + l.arrived + "}"
    }

    /**
     * Milliseconds until the page asks the server again (LivePolicy.viewerNext, §5.1); -1 = never again.
     * [nextS] < 0 = no head read yet; [retryAfterMs] < 0 = the server was not busy.
     */
    fun next(sinceLastWriteMs: Double, nextS: Int, emptyAsks: Int, over: Boolean, retryAfterMs: Double): Double =
        LivePolicy.viewerNext(sinceLastWriteMs.toLong(), nextS.takeIf { it >= 0 }, emptyAsks, over,
            retryAfterMs.takeIf { it >= 0 }?.toLong())?.toDouble() ?: -1.0

    // ---- the phone's side, for the browser test only ----

    private fun b64(s: String): ByteArray = LiveItem.fromText(s) ?: ByteArray(0)
    private fun seal(link: String, name: String, plain: ByteArray, nonce: String): String {
        val keys = LiveKeys.of(link) ?: return ""
        val n = b64(nonce).takeIf { it.size >= 12 }?.copyOf(12) ?: return ""
        return LiveItem.toText(LiveItem.seal(keys, name, plain, n))
    }

    /** The link for 32 random bytes (Base64url), or "". */
    fun link(random: String, base: String): String =
        b64(random).takeIf { it.size >= 32 }?.let { com.onpath.app.live.LiveCode.link(it.copyOf(32), base) } ?: ""

    /**
     * A sealed head. [fields] = "key=value" joined by "&" (missing = the default): state (recording / paused / ended),
     * lat, lon, ele, time, accuracy, gpsLost, arrived, offRouteM, distanceM, ascentM, movingS, startedMs, toGoM,
     * arrivalMs, stillS, batteryPct, activity, name, counter, nextS, chunks.
     */
    fun sealHead(link: String, fields: String, nonce: String): String {
        val f = fields.split('&').mapNotNull { p -> p.indexOf('=').takeIf { it > 0 }?.let { p.substring(0, it) to p.substring(it + 1) } }.toMap()
        fun int(k: String) = f[k]?.toDoubleOrNull()?.toInt()
        fun long(k: String) = f[k]?.toDoubleOrNull()?.toLong()
        val lat = f["lat"]?.toDoubleOrNull(); val lon = f["lon"]?.toDoubleOrNull()
        val h = LiveHead(
            state = LiveState.entries.firstOrNull { it.name.lowercase() == f["state"] } ?: LiveState.RECORDING,
            fix = if (lat != null && lon != null) LiveFix(lat, lon, int("ele"), long("time") ?: 0, int("accuracy")) else null,
            gpsLost = f["gpsLost"] == "true", arrived = f["arrived"] == "true", offRouteM = int("offRouteM"),
            distanceM = int("distanceM") ?: 0, ascentM = int("ascentM") ?: 0, movingS = int("movingS") ?: 0,
            startedMs = long("startedMs") ?: 0, toGoM = int("toGoM"), arrivalMs = long("arrivalMs"), stillS = int("stillS"),
            batteryPct = int("batteryPct"), activity = f["activity"] ?: "", name = f["name"] ?: "", counter = int("counter") ?: 0,
            nextS = int("nextS") ?: LivePolicy.MOVING_S, chunks = int("chunks"),
        )
        return seal(link, LiveItem.HEAD, LiveItem.head(h), nonce)
    }

    /** A sealed route; [line] = "lat,lon;lat,lon;…" ("" = the plan was dropped). */
    fun sealRoute(link: String, line: String, nonce: String): String =
        seal(link, LiveItem.ROUTE, LiveItem.route(pairs(line).map { GeoPoint(it[0], it[1]) }), nonce)

    /** Sealed chunk number [n]; [points] = "seg,lat,lon,ele,timeMs;…" (ele may be empty). "" when they do not fit one chunk. */
    fun sealChunk(link: String, n: Int, points: String, nonce: String): String {
        val pts = pairs(points).filter { it.size >= 5 }.map { LivePoint(it[0].toInt(), it[1], it[2], it[3].takeIf { e -> !e.isNaN() }?.toInt(), it[4].toLong()) }
        val cut = LiveChunker.next(pts) ?: return ""
        if (cut.count != pts.size) return ""
        return seal(link, LiveItem.chunkName(n), cut.bytes, nonce)
    }

    private fun pairs(text: String): List<List<Double>> = text.split(';').filter { it.isNotBlank() }
        .map { p -> p.split(',').map { it.trim().toDoubleOrNull() ?: Double.NaN } }.filter { it.size >= 2 && !it[0].isNaN() && !it[1].isNaN() }
}
