package com.onpath.app.data

import com.onpath.app.debug.Problems
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.floor

/**
 * Downloads BRouter routing data (5°×5° tiles, e.g. E10_N45.rd5) straight from brouter.de on the phone.
 * Written to a temp file and renamed when complete; an interrupted download resumes where it stopped.
 */
object SegmentDownloader {

    const val BASE_URL = "https://brouter.de/brouter/segments4/"

    /**
     * Who asks brouter.de (a volunteer's server, review Arch 7; owner 2026-11-02, onpath.private#5 A): the app and version
     * plus our site, so its operator can reach us when OnPath's traffic is a problem. Every brouter.de request sends it.
     */
    val USER_AGENT: String get() = userAgent(com.onpath.app.BuildConfig.VERSION_NAME)

    /** `OnPath/<version> (+https://onpath.earth)` – pure, for the test. */
    fun userAgent(version: String): String = "OnPath/$version (+https://onpath.earth)"

    /** Tile name for a position: 5° steps, west/south as W/S. Bozen (46.5, 11.35) → "E10_N45". */
    fun tileName(lat: Double, lon: Double): String = com.onpath.app.routing.Rd5Parts.tileName(lat, lon)

    /**
     * Downloads [name] into [segmentsDir]. [onProgress] gets (bytesDone, bytesTotal or -1).
     * Throws an exception with a plain message on failure; the partial file is kept for resuming. [cancelled] is asked
     * between blocks: true stops with [Cancelled] (partial file kept). The size is checked against Content-Length
     * (brouter.de publishes no checksums); the file appears under its name only when complete (atomic rename).
     */
    fun download(name: String, segmentsDir: File, onProgress: (Long, Long) -> Unit = { _, _ -> },
                 cancelled: () -> Boolean = { false }) {
        segmentsDir.mkdirs()
        val dest = File(segmentsDir, name)
        val part = File(segmentsDir, ".$name.part")
        // the one resumable download (net/HttpDownload, review Arch 6); brouter.de publishes no checksums – size only
        try {
            com.onpath.app.net.HttpDownload.resume(BASE_URL + name, part,
                open = { u -> (u.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000; readTimeout = 30_000
                    setRequestProperty("User-Agent", USER_AGENT)
                } },
                failure = { code, _ -> IllegalStateException("Server answered $code for $name.") },
                onStart = { total ->
                    val left = total - (if (part.exists()) part.length() else 0L)
                    if (total > 0 && segmentsDir.usableSpace < left + 50L * 1024 * 1024) {
                        throw IllegalStateException("Not enough free storage for routing data (${total / 1_048_576} MB).")
                    }
                },
                onBytes = { done, total -> onProgress(done, total) },
                cancelled = cancelled, cancelError = { Cancelled() },
            )
        } catch (e: com.onpath.app.net.HttpDownload.Incomplete) {
            throw IllegalStateException("Download incomplete – tap again to resume.", e)
        }
        // over the old file in one step: the router never finds the tile missing; on failure the part stays
        try { AtomicFiles.replace(part, dest) } catch (e: java.io.IOException) { throw IllegalStateException("Could not save $name.", e) }
    }

    /** The download was stopped by the user (Cancel); the partial file stays for resuming. */
    class Cancelled : Exception("Routing data download stopped.")

    /**
     * Newer routing data: asks brouter.de for the date of [name] and downloads it again when that is newer than the
     * file on the phone (the old file stays usable until the new one is complete). A half download of an older
     * version is dropped first. Returns true when a newer file was installed. Throws with a plain message on failure.
     */
    fun refreshIfNewer(name: String, segmentsDir: File): Boolean {
        val dest = File(segmentsDir, name)
        val conn = (URL(BASE_URL + name).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"; connectTimeout = 20_000; readTimeout = 30_000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        val serverDate = try {
            if (conn.responseCode != 200) throw IllegalStateException("Server answered ${conn.responseCode} for $name.")
            conn.lastModified
        } finally { conn.disconnect() }
        if (!RoutingDataCheck.isNewer(serverDate, dest.takeIf { it.isFile }?.lastModified())) return false
        val part = File(segmentsDir, ".$name.part")
        if (part.exists() && part.lastModified() < serverDate) part.delete() // part of an older version
        download(name, segmentsDir)
        return true
    }
}

/** Pure rules of the routing data refresh (unit-tested). */
object RoutingDataCheck {
    /** The server's file ([serverDate], 0 = unknown) is newer than the local one (its download time). */
    fun isNewer(serverDate: Long, localDate: Long?): Boolean = serverDate > 0 && localDate != null && serverDate > localDate

    /**
     * The route changed in a way the user would notice: a point of one line is more than [toleranceM] from every
     * point of the other (sampled), or the length differs by more than 2 %.
     */
    fun differs(a: List<com.onpath.app.routing.GeoPoint>, b: List<com.onpath.app.routing.GeoPoint>, toleranceM: Double = 30.0): Boolean {
        if (a.size < 2 || b.size < 2) return a.size != b.size
        val la = length(a); val lb = length(b)
        if (kotlin.math.abs(la - lb) > 0.02 * maxOf(la, lb)) return true
        fun far(x: List<com.onpath.app.routing.GeoPoint>, y: List<com.onpath.app.routing.GeoPoint>): Boolean {
            val step = maxOf(1, x.size / 200)
            for (i in x.indices step step) if (y.minOf { metres(x[i], it) } > toleranceM) return true
            return false
        }
        return far(a, b) || far(b, a)
    }

    private fun length(l: List<com.onpath.app.routing.GeoPoint>) = l.zipWithNext { p, q -> metres(p, q) }.sum()

    private fun metres(p: com.onpath.app.routing.GeoPoint, q: com.onpath.app.routing.GeoPoint): Double {
        val k = Math.cos(Math.toRadians((p.lat + q.lat) / 2))
        val dx = (q.lon - p.lon) * k * 111_320.0; val dy = (q.lat - p.lat) * 111_320.0
        return Math.sqrt(dx * dx + dy * dy)
    }
}
