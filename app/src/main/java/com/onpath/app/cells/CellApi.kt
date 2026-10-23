package com.onpath.app.cells

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import javax.net.ssl.SSLException

/**
 * Client of the doorman API (docs/design/map-cells.md §8, docs/design/map-hosting.md): request, status, manifest,
 * file download with HTTP Range resume. Plain HttpURLConnection like AppUpdater / SegmentDownloader – no extra
 * dependency. Blocking: call from a background thread. Every failure is an [ApiException] with a plain message.
 */
class CellApi(
    /** "https://maps-test.free-your-pictures.org/v1" */
    private val apiUrl: String,
    private val appKey: String,
    private val appVersion: String,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) {
    /** One cell in a request / status answer. [state]: none, queued, building, ready, failed. */
    data class CellStatus(
        val id: String, val state: String,
        val step: String? = null, val progress: Double? = null, val etaS: Int? = null, val position: Int? = null,
        val version: String? = null, val osmDate: String? = null, val size: Long? = null, val reason: String? = null,
        /** "ready" while a newer version is being built (the app keeps asking instead of stopping). */
        val updating: Boolean = false,
    )

    data class StatusAnswer(val cells: List<CellStatus>, val pollS: Int)

    enum class Failure { OFFLINE, UNREACHABLE, REFUSED, BUSY, NOT_READY, SERVER, BAD_ANSWER }

    class ApiException(val failure: Failure, message: String, val retryAfterS: Int? = null, cause: Throwable? = null) :
        IOException(message, cause)

    /** POST /cells/request – ask for cells ([reason]: point, near, corridor, refresh). */
    fun request(ids: List<String>, reason: String, formats: List<Int> = listOf(FORMAT)): StatusAnswer {
        val body = JSONObject().put("cells", JSONArray(ids)).put("reason", reason).put("formats", JSONArray(formats))
        return parseStatus(call("POST", "$apiUrl/cells/request", body.toString()))
    }

    /** GET /cells/status?ids=… */
    fun status(ids: List<String>, format: Int = FORMAT): StatusAnswer =
        parseStatus(call("GET", "$apiUrl/cells/status?ids=${ids.joinToString(",")}&format=$format", null))

    /** GET /cells/z/x/y/manifest – the current manifest, [Failure.NOT_READY] when the cell is not built yet. */
    fun manifest(cell: CellId, format: Int = FORMAT): CellManifest {
        val text = call("GET", "$apiUrl/cells/${cell.path}/manifest?format=$format", null)
        return try { CellManifest.parse(text) } catch (e: Exception) {
            throw ApiException(Failure.BAD_ANSWER, "The map server sent an unusable file list for this area.", cause = e)
        }
    }

    /**
     * Downloads manifest file [f] to [part], resuming from its current length (Range). Checks size and sha256 at the
     * end: on a mismatch [part] is deleted and [ChecksumException] thrown. [onBytes] gets the bytes of this file so far.
     * The caller renames [part] when it returns.
     */
    fun download(f: CellManifest.File, part: File, onBytes: (Long) -> Unit = {}) {
        part.parentFile?.mkdirs()
        if (part.length() > f.size) part.delete()
        var have = if (part.isFile) part.length() else 0L
        if (have < f.size) {
            val conn = open("GET", "$apiUrl/files/${f.url}")
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            try {
                val code = guard { conn.responseCode }
                when {
                    code == 206 -> {}
                    code == 200 -> have = 0 // range ignored: start over
                    code == 416 -> { // our part is not a prefix of the server's file: start over
                        part.delete()
                        throw ApiException(Failure.BAD_ANSWER, "Download of ${f.name} must start again – trying again.")
                    }
                    else -> throw failureFor(code, conn)
                }
                guard {
                    conn.inputStream.use { inp ->
                        FileOutputStream(part, have > 0).use { out ->
                            val buf = ByteArray(128 * 1024)
                            var done = have; var last = 0L
                            while (true) {
                                val n = inp.read(buf); if (n < 0) break
                                out.write(buf, 0, n); done += n
                                if (done > f.size) break // more than announced: checked below
                                if (done - last >= 256 * 1024) { onBytes(done); last = done }
                            }
                            out.flush(); out.fd.sync()
                        }
                    }
                }
            } finally { conn.disconnect() }
        }
        val len = part.length()
        if (len < f.size) throw ApiException(Failure.UNREACHABLE, "Download of ${f.name} stopped at ${len * 100 / maxOf(1, f.size)} % – continues when connected.")
        if (len != f.size || sha256(part) != f.sha256) {
            part.delete()
            throw ChecksumException("Download damaged (${f.name}) – loading again.")
        }
        onBytes(len)
    }

    class ChecksumException(message: String) : IOException(message)

    // ------------------------------------------------------------------------------------------- HTTP

    private fun open(method: String, url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs; readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("X-OnPath-Key", appKey)
            setRequestProperty("X-OnPath-App", appVersion)
            setRequestProperty("User-Agent", "OnPath-Android/$appVersion")
            setRequestProperty("Accept", "application/json")
        }

    private fun call(method: String, url: String, body: String?): String {
        val conn = open(method, url)
        try {
            return guard {
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                if (code != 200) throw failureFor(code, conn)
                conn.inputStream.bufferedReader().use { it.readText() }
            }
        } finally { conn.disconnect() }
    }

    private fun failureFor(code: Int, conn: HttpURLConnection): ApiException {
        val detail = try { conn.errorStream?.bufferedReader()?.use { it.readText() }?.let { JSONObject(it).optString("error") } } catch (e: Exception) {
            null // expected: error body missing or not JSON – the status code says enough
        }
        val retry = conn.getHeaderField("Retry-After")?.trim()?.toIntOrNull()
        return when {
            code == 401 || code == 403 -> ApiException(Failure.REFUSED, "Map server refused the request – update the app.")
            code == 429 -> ApiException(Failure.BUSY, "The map server is busy – trying again later.", retry ?: 60, technical(code, detail))
            code == 404 && detail == "not ready" -> ApiException(Failure.NOT_READY, "The map for this area is not ready yet.")
            code >= 500 -> ApiException(Failure.SERVER, "Map server not reachable – trying again.", retry)
            else -> ApiException(Failure.BAD_ANSWER, "The map server sent an unexpected answer – trying again later.", cause = technical(code, detail))
        }
    }

    /** The server's code and error text: for the problem log (the cause of an [ApiException]), never shown to the user. */
    private fun technical(code: Int, detail: String?) = IOException("HTTP $code${detail?.let { ": $it" } ?: ""}")

    /** Network exceptions → [ApiException] (offline / unreachable). */
    private inline fun <T> guard(block: () -> T): T = try { block() } catch (e: ApiException) { throw e } catch (e: ChecksumException) { throw e } catch (e: IOException) {
        throw when (e) {
            is UnknownHostException -> ApiException(Failure.OFFLINE, "No connection – the detailed map for this area will load when you are online.", cause = e)
            is ConnectException, is SocketTimeoutException, is SSLException -> ApiException(Failure.UNREACHABLE, "Map server not reachable – trying again.", cause = e)
            else -> ApiException(Failure.UNREACHABLE, "Connection to the map server broke – trying again.", cause = e)
        }
    }

    companion object {
        /** Map package format this app understands for cells (map-cells.md Q11: format 2 only). */
        const val FORMAT = 2

        /**
         * Content schema this app's style needs (tools/cells/cells.py CELL_SCHEMA): 2 = overlays layer "streets"
         * (small streets at z13+), 3 = router classes per way, blocking barriers, SAC on hiking route pieces
         * (docs/map-style-notes.md "Router classes"). Installed cells of an older schema are requested again
         * (MapCells.outdated); until then the style draws them with the tag rules.
         */
        const val CELL_SCHEMA = 3

        /** "2026-10-20-s2" → 2; versions named by the OSM date alone are schema 1. */
        fun schemaOf(version: String?): Int = Regex("""-s(\d+)$""").find(version.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 1

        fun parseStatus(json: String): StatusAnswer = try {
            val o = JSONObject(json)
            val a = o.optJSONArray("cells") ?: JSONArray()
            StatusAnswer((0 until a.length()).map { i ->
                val c = a.getJSONObject(i)
                fun s(k: String) = c.optString(k).ifBlank { null }
                CellStatus(c.getString("id"), c.optString("state", "none"), s("step"),
                    if (c.has("progress")) c.optDouble("progress") else null,
                    if (c.has("eta_s")) c.optInt("eta_s") else null, if (c.has("position")) c.optInt("position") else null,
                    s("version"), s("osm_date"), if (c.has("size")) c.optLong("size") else null, s("reason"),
                    c.optBoolean("updating", false))
            }, o.optInt("poll_s", 30).coerceIn(5, 600))
        } catch (e: Exception) {
            throw ApiException(Failure.BAD_ANSWER, "The map server sent an unusable answer.", cause = e)
        }

        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { inp ->
                val buf = ByteArray(256 * 1024)
                while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
