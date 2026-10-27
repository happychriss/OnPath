package com.onpath.app.sync

/**
 * The pairing code (web planner phase 2, docs/design/web-sync.md §4): the browser shows it as a QR code, the app scans
 * it once – like WhatsApp Web. It carries one random secret that phone and browser then share; the secret never goes
 * to a server (it sits after "#" in the link). Everything else is derived from it ([SyncKeys]).
 *
 * Link: `<web planner>/#pair=<code>`. Format version 1 (bytes, then Base64url without padding, 44 characters):
 * version (1), secret (32 bytes). A newer version may append fields; version-1 readers ignore bytes after the secret.
 *
 * Reading never throws: anything else is null. Only text with "pair=" is read (a bare plan code also starts with
 * byte 1, so a bare code would be ambiguous).
 */
object PairCode {
    const val VERSION = 1
    const val SECRET_BYTES = 32
    private val MARKER = Regex("""(?:^|[#?&])pair=([A-Za-z0-9_-]+)""")

    /** The code for [secret] (32 random bytes from the platform's secure random). */
    fun encode(secret: ByteArray): String {
        require(secret.size == SECRET_BYTES) { "pairing secret must be $SECRET_BYTES bytes" }
        return B64.encode(byteArrayOf(VERSION.toByte()) + secret)
    }

    /** The pairing link at the web planner [base] (e.g. "https://plan.onpath.earth/"). */
    fun link(base: String, secret: ByteArray): String = base.substringBefore('#') + "#pair=" + encode(secret)

    /** The secret in [text] (a link with `#pair=`, or `pair=<code>`), or null. */
    fun parse(text: String): ByteArray? {
        val t = text.trim()
        if (t.isEmpty() || t.length > 4000) return null
        val code = MARKER.find(t)?.groupValues?.get(1) ?: return null
        val b = B64.decode(code) ?: return null
        if (b.size < 1 + SECRET_BYTES || b[0].toInt() != VERSION) return null
        return b.copyOfRange(1, 1 + SECRET_BYTES)
    }
}

/**
 * What phone and browser derive from the shared pairing secret (HKDF-SHA256, salt "OnPath sync v1"):
 * - [mailbox]: the mailbox id – the only name the server knows the pair by (16 bytes, 22 characters).
 * - [token]: proves the right to read and write that mailbox; the server keeps only its SHA-256 (32 bytes).
 * - content key: encrypts every item (AES-256-GCM); never leaves the device.
 * - [check]: six digits both screens show, so the user sees the phone scanned this browser's code.
 */
class SyncKeys(secret: ByteArray) {
    init { require(secret.size == PairCode.SECRET_BYTES) }

    internal val mailboxBytes = derive(secret, "mailbox id", 16)
    val mailbox: String = B64.encode(mailboxBytes)
    val token: String = B64.encode(derive(secret, "mailbox token", 32))
    internal val content: ByteArray = derive(secret, "content key", 32)
    val check: String = derive(secret, "check digits", 4).let { b ->
        var v = 0L
        for (x in b) v = (v shl 8) or (x.toLong() and 0xff)
        (v % 1_000_000).toString().padStart(6, '0').let { it.substring(0, 3) + " " + it.substring(3) }
    }

    private fun derive(secret: ByteArray, label: String, n: Int) =
        Hkdf.derive(secret, SALT, label.encodeToByteArray(), n)

    companion object {
        private val SALT = "OnPath sync v1".encodeToByteArray()

        /** The keys for a pairing link or code, or null when [text] holds none. */
        fun of(text: String): SyncKeys? = PairCode.parse(text)?.let(::SyncKeys)
    }
}

/** Base64url without padding (RFC 4648 §5), small and platform free (the same alphabet as PlanCode). */
internal object B64 {
    private const val ABC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun isCodeChar(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_'

    fun encode(b: ByteArray): String = buildString {
        var i = 0
        while (i < b.size) {
            val n = minOf(3, b.size - i)
            var v = 0
            for (k in 0 until 3) v = (v shl 8) or (if (k < n) b[i + k].toInt() and 0xff else 0)
            for (k in 0..n) append(ABC[(v shr (18 - 6 * k)) and 0x3f])
            i += 3
        }
    }

    fun decode(s: String): ByteArray? {
        if (s.length % 4 == 1) return null
        val out = ByteArray(s.length * 3 / 4)
        var o = 0
        var v = 0
        var bits = 0
        for (c in s) {
            val d = ABC.indexOf(c)
            if (d < 0) return null
            v = (v shl 6) or d; bits += 6
            if (bits >= 8) { bits -= 8; out[o++] = (v shr bits).toByte(); v = v and ((1 shl bits) - 1) }
        }
        return out.copyOf(o)
    }
}
