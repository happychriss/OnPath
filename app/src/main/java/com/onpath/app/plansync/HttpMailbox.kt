package com.onpath.app.plansync

import com.onpath.app.debug.Problems
import com.onpath.app.sync.Mailbox
import com.onpath.app.sync.SyncFormat
import com.onpath.app.sync.SyncKeys
import com.onpath.app.sync.SyncRound
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The mailbox over HTTPS (web/host/src/sync.js on plan.onpath.earth, docs/design/web-sync.md §8): HttpURLConnection,
 * [TIMEOUT_MS] for connect and read, the token as `Authorization: Bearer`. Never throws: no answer is
 * [Mailbox.Reply.OFFLINE], a non-2xx answer its status (with the server's short reason). Blocking – background only.
 */
class HttpMailbox(private val base: String, private val keys: SyncKeys) : Mailbox {
    private val box get() = base.trimEnd('/') + "/sync/v1/" + keys.mailbox

    override fun claim() = call("POST", box)
    override fun read(since: Long) = call("GET", "$box?since=$since")
    override fun write(id: String, base: Long, blob: ByteArray) = call("PUT", "$box/$id", blob, base)
    override fun end() = call("DELETE", box)

    private fun call(method: String, url: String, body: ByteArray? = null, baseRev: Long? = null): Mailbox.Reply {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = TIMEOUT_MS; readTimeout = TIMEOUT_MS
                useCaches = false
                setRequestProperty("Authorization", "Bearer " + keys.token)
                setRequestProperty("Accept", "application/json")
                if (baseRev != null) setRequestProperty("x-onpath-base", baseRev.toString())
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/octet-stream")
                    setFixedLengthStreamingMode(body.size)
                    outputStream.use { it.write(body) }
                }
            }
            val status = c.responseCode
            val text = (if (status in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            reply(status, text, retryAfter(c.getHeaderField("retry-after")))
        } catch (e: IOException) {
            // expected: no network, timeout, DNS – the caller says "offline" (INFO)
            Problems.info("sync", "Sync server not reached ($method): ${e.javaClass.simpleName}")
            Mailbox.Reply(Mailbox.Reply.OFFLINE)
        } catch (e: Exception) {
            Problems.warn("sync", "Sync request failed ($method)", e)
            Mailbox.Reply(Mailbox.Reply.OFFLINE)
        } finally {
            c?.disconnect()
        }
    }

    private fun reply(status: Int, text: String, retryAfterS: Long = 0): Mailbox.Reply {
        val j = try { if (text.isBlank()) null else JSONObject(text) } catch (e: Exception) {
            // a 5xx page from the platform (not JSON) is the server busy – a quiet retry (SyncRound.busy), noted as INFO
            if (SyncRound.busy(status)) Problems.info("sync", "Sync server busy (HTTP $status, no JSON answer)")
            else Problems.warn("sync", "Sync server answer unreadable (HTTP $status, ${text.length} characters)", e)
            null
        }
        val error = j?.optString("error")?.takeIf { it.isNotEmpty() }?.take(120)
        val rev = j?.optLong("rev", 0) ?: 0
        if (status != 200 || j == null || !j.has("items")) {
            // a 200 GET without a readable page is a broken answer, not "no items"
            if (status == 200 && j == null) return Mailbox.Reply(502, error = "unreadable answer")
            return Mailbox.Reply(status, rev, error = error, retryAfterS = retryAfterS)
        }
        val arr = j.optJSONArray("items") ?: return Mailbox.Reply(502, error = "unreadable answer")
        val items = ArrayList<Triple<String, Long, ByteArray>>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id"); val r = o.optLong("rev", -1)
            val blob = SyncFormat.fromText(o.optString("blob"))
            if (!SyncFormat.isId(id) || r < 0 || blob == null) { Problems.warn("sync", "Sync server item unreadable – skipped"); continue }
            items += Triple(id, r, blob)
        }
        return Mailbox.Reply(status, rev, Mailbox.Page(rev, j.optBoolean("more", false), items))
    }

    companion object {
        const val TIMEOUT_MS = 15_000

        /** The retry-after header in seconds (0 = none / not a number – an HTTP date is not used by the host). */
        fun retryAfter(header: String?): Long = header?.trim()?.toLongOrNull()?.coerceIn(0, 86_400) ?: 0
    }
}
