package com.onpath.app.net

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The one resumable download of the app (review Arch 6: four hand-rolled copies with different care) – the app update,
 * map cells and routing data use it. GET [url] into [part], continuing from its length (Range):
 * - redirects followed by hand, to **https only** (a downgrade to http is refused), at most [MAX_REDIRECTS];
 * - 200 although a range was asked: the server ignored it, start over; 416: complete when the size matches (or is not
 *   known), else the part is not a prefix of the file – deleted, [Restart];
 * - written with fsync; more bytes than [expectedSize] or a wrong [sha256]: the part is deleted, [ChecksumMismatch];
 *   fewer: [Incomplete], the part stays for the next try.
 * The caller makes the connection ([open]: timeouts, User-Agent, keys), maps a failed status ([failure]) and renames
 * the returned part. Blocking.
 */
object HttpDownload {
    const val MAX_REDIRECTS = 5

    /** The server's file changed under a part: the part was deleted, the next try starts from zero. */
    class Restart : IOException("The download has to start over.")
    /** More than announced or a wrong checksum: the part was deleted. */
    class ChecksumMismatch : IOException("The downloaded file is damaged.")
    /** The stream ended early: the part stays for resuming. */
    class Incomplete(val have: Long, val expected: Long) : IOException("Download incomplete ($have of $expected bytes).")
    /** A redirect to plain http. */
    class InsecureRedirect(to: String) : IOException("Refused a redirect to an insecure address ($to).")

    fun resume(
        url: String,
        part: File,
        expectedSize: Long? = null,
        sha256: String? = null,
        open: (URL) -> HttpURLConnection,
        failure: (Int, HttpURLConnection) -> Exception = { code, _ -> IOException("Server answered $code") },
        onStart: (total: Long) -> Unit = {},
        onBytes: (done: Long, total: Long) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
        cancelError: () -> Exception = { IOException("Download cancelled") },
        allowHttp: Boolean = false,
    ): File {
        part.parentFile?.mkdirs()
        val size = expectedSize?.takeIf { it > 0 }
        if (size != null && part.length() > size) part.delete()
        var have = if (part.isFile) part.length() else 0L
        if (size == null || have < size) {
            var u = URL(url)
            var hops = 0
            var conn: HttpURLConnection
            while (true) {
                // the caller's address is taken as given; a redirect must stay on https (no downgrade)
                if (hops > 0 && u.protocol != "https" && !allowHttp) throw InsecureRedirect(u.toString())
                conn = open(u).apply {
                    instanceFollowRedirects = false
                    if (have > 0) setRequestProperty("Range", "bytes=$have-")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (loc == null || hops++ >= MAX_REDIRECTS) throw IOException("Too many or broken redirects ($code)")
                    u = URL(u, loc)
                    continue
                }
                break
            }
            try {
                when (val code = conn.responseCode) {
                    206 -> {}
                    200 -> have = 0 // the range was ignored: start over
                    416 -> if (size == null || have == size) Unit else { part.delete(); throw Restart() }
                    else -> throw failure(code, conn)
                }
                if (conn.responseCode != 416) {
                    val total = size ?: conn.contentLengthLong.takeIf { it > 0 }?.plus(have) ?: -1L
                    onStart(total)
                    conn.inputStream.use { inp ->
                        FileOutputStream(part, have > 0).use { out ->
                            val buf = ByteArray(128 * 1024)
                            var done = have; var last = have
                            while (true) {
                                if (cancelled()) throw cancelError()
                                val n = inp.read(buf); if (n < 0) break
                                out.write(buf, 0, n); done += n
                                if (size != null && done > size) break // more than announced: checked below
                                if (done - last >= 256 * 1024) { onBytes(done, total); last = done }
                            }
                            out.flush(); out.fd.sync()
                        }
                    }
                    if (size == null && total > 0 && part.length() < total) throw Incomplete(part.length(), total)
                }
            } finally { conn.disconnect() }
        }
        val len = part.length()
        if (size != null && len < size) throw Incomplete(len, size)
        if ((size != null && len > size) || (sha256 != null && !sha256Of(part).equals(sha256, ignoreCase = true))) {
            part.delete()
            throw ChecksumMismatch()
        }
        onBytes(len, size ?: len)
        return part
    }

    fun sha256Of(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp ->
            val buf = ByteArray(256 * 1024)
            while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
