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

    /** Tile name for a position: 5° steps, west/south as W/S. Bozen (46.5, 11.35) → "E10_N45". */
    fun tileName(lat: Double, lon: Double): String {
        val lo = (floor(lon / 5.0) * 5).toInt()
        val la = (floor(lat / 5.0) * 5).toInt()
        val ew = if (lo < 0) "W${-lo}" else "E$lo"
        val ns = if (la < 0) "S${-la}" else "N$la"
        return "${ew}_$ns.rd5"
    }

    /**
     * Downloads [name] into [segmentsDir]. [onProgress] gets (bytesDone, bytesTotal or -1).
     * Throws an exception with a plain message on failure; the partial file is kept for resuming.
     */
    fun download(name: String, segmentsDir: File, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        segmentsDir.mkdirs()
        val dest = File(segmentsDir, name)
        val part = File(segmentsDir, ".$name.part")
        var done = if (part.exists()) part.length() else 0L
        val conn = (URL(BASE_URL + name).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            if (done > 0) setRequestProperty("Range", "bytes=$done-")
        }
        try {
            val code = conn.responseCode
            if (code == 416) { // already complete
                if (!part.renameTo(dest)) Problems.error("data", "Downloaded routing data $name could not be saved (rename failed)")
                return
            }
            if (code != 200 && code != 206) throw IllegalStateException("Server answered $code for $name.")
            if (code == 200) done = 0 // server ignored the range: start over
            val total = conn.getHeaderFieldLong("Content-Length", -1).let { if (it > 0) it + done else -1 }
            if (total > 0 && (segmentsDir.usableSpace < total - done + 50L * 1024 * 1024)) {
                throw IllegalStateException("Not enough free storage for routing data (${total / 1_048_576} MB).")
            }
            conn.inputStream.use { inp ->
                java.io.FileOutputStream(part, code == 206).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var lastReport = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 1_000_000) { onProgress(done, total); lastReport = done }
                    }
                    out.fd.sync()
                }
            }
            if (total > 0 && part.length() != total) throw IllegalStateException("Download incomplete – tap again to resume.")
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) throw IllegalStateException("Could not save $name.")
            onProgress(done, total)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Newer routing data: asks brouter.de for the date of [name] and downloads it again when that is newer than the
     * file on the phone (the old file stays usable until the new one is complete). A half download of an older
     * version is dropped first. Returns true when a newer file was installed. Throws with a plain message on failure.
     */
    fun refreshIfNewer(name: String, segmentsDir: File): Boolean {
        val dest = File(segmentsDir, name)
        val conn = (URL(BASE_URL + name).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"; connectTimeout = 20_000; readTimeout = 30_000
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
