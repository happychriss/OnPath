package com.onpath.app.data

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
                part.renameTo(dest); return
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
}
