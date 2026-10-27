package com.onpath.app.sync

/**
 * One synced thing (docs/design/web-sync.md §5): a saved plan, or a device's "hello" (its name, so the other side can
 * say "Paired with Pixel 8"). Deleting keeps a tombstone ([deleted], no content), so the delete reaches every device.
 */
data class SyncItem(
    /** Random id (16 bytes, Base64url, 22 characters) – the server sees it as the item's name, nothing else. */
    val id: String,
    /** Last change, ms since 1970 (the device's clock). Newer wins (owner 2026-10-26). */
    val updatedAt: Long,
    /** The device that made this version (8 random bytes, Base64url, 11 characters; chosen once per install). */
    val device: String,
    val deleted: Boolean = false,
    val kind: Int = PLAN,
    val title: String? = null,
    /** The plan as the app's plan code (PlanCode.encode – the same text as in a plan link). */
    val plan: String? = null,
    /** Device name (kind [DEVICE]). */
    val name: String? = null,
) {
    companion object {
        const val PLAN = 1
        const val DEVICE = 2
    }

    /** Same content (what the user sees), ignoring who changed it when. */
    fun sameContent(o: SyncItem) =
        kind == o.kind && deleted == o.deleted && title == o.title && plan == o.plan && name == o.name
}

/**
 * The item and blob formats. Pure Kotlin, shared by app and web planner; reading never throws.
 *
 * Item (the plain bytes, version 1): version (1), kind (1), id (16), device (8), updatedAt (varint ms),
 * flags (bit 0 = deleted), then optional fields until the end: tag (varint), length (varint), bytes –
 * 1 = title (UTF-8), 2 = plan code (ASCII), 3 = device name (UTF-8). Unknown tags are skipped (newer apps).
 *
 * Blob (what the server stores, version 1): version (1), nonce (12), AES-256-GCM(item) with the 16-byte tag. The
 * additional data binds the blob to its place: version byte + mailbox id (16 bytes) + item id (16 bytes), so the
 * server cannot move a blob to another item or mailbox unnoticed.
 */
object SyncFormat {
    const val ITEM_VERSION = 1
    const val BLOB_VERSION = 1
    const val ID_BYTES = 16
    const val DEVICE_BYTES = 8
    const val MAX_TITLE = 100
    const val MAX_PLAN = 16_000
    const val MAX_NAME = 60
    /** Largest blob a client writes (the server allows a little more). */
    const val MAX_BLOB = 24_000

    private const val TAG_TITLE = 1
    private const val TAG_PLAN = 2
    private const val TAG_NAME = 3

    /** A new item id from 16 random bytes. */
    fun newId(random: ByteArray): String { require(random.size >= ID_BYTES); return B64.encode(random.copyOf(ID_BYTES)) }

    /** A new device id from 8 random bytes. */
    fun newDevice(random: ByteArray): String { require(random.size >= DEVICE_BYTES); return B64.encode(random.copyOf(DEVICE_BYTES)) }

    fun isId(s: String) = s.length == 22 && B64.decode(s)?.size == ID_BYTES
    fun isDevice(s: String) = s.length == 11 && B64.decode(s)?.size == DEVICE_BYTES

    /** The id of a device's hello item (one per device, the same on every side). */
    fun deviceItemId(device: String): String =
        B64.encode(Sha256.hash("OnPath device ".encodeToByteArray() + device.encodeToByteArray()).copyOf(ID_BYTES))

    fun encode(item: SyncItem): ByteArray {
        val out = Out()
        out.byte(ITEM_VERSION); out.byte(item.kind)
        out.bytes(B64.decode(item.id)?.takeIf { it.size == ID_BYTES } ?: throw IllegalArgumentException("bad item id"))
        out.bytes(B64.decode(item.device)?.takeIf { it.size == DEVICE_BYTES } ?: throw IllegalArgumentException("bad device id"))
        out.varint(item.updatedAt.coerceAtLeast(0))
        out.byte(if (item.deleted) 1 else 0)
        clean(item.title, MAX_TITLE)?.let { out.field(TAG_TITLE, it.encodeToByteArray()) }
        item.plan?.filter(B64::isCodeChar)?.take(MAX_PLAN)?.takeIf { it.isNotEmpty() }?.let { out.field(TAG_PLAN, it.encodeToByteArray()) }
        clean(item.name, MAX_NAME)?.let { out.field(TAG_NAME, it.encodeToByteArray()) }
        return out.toArray()
    }

    fun decode(bytes: ByteArray): SyncItem? = try {
        val r = In(bytes)
        if (r.byte() != ITEM_VERSION) null else {
            val kind = r.byte()
            val id = B64.encode(r.take(ID_BYTES))
            val device = B64.encode(r.take(DEVICE_BYTES))
            val at = r.varint()
            val flags = r.byte()
            var title: String? = null; var plan: String? = null; var name: String? = null
            while (r.more()) {
                val tag = r.varint()
                val len = r.varint()
                require(len in 0..r.left().toLong())
                val v = r.take(len.toInt()).decodeToString()
                when (tag.toInt()) {
                    TAG_TITLE -> title = clean(v, MAX_TITLE)
                    TAG_PLAN -> plan = v.takeIf { it.isNotEmpty() && it.length <= MAX_PLAN && it.all(B64::isCodeChar) }
                    TAG_NAME -> name = clean(v, MAX_NAME)
                    else -> Unit // a field from a newer app: skip it
                }
            }
            SyncItem(id, at, device, flags and 1 != 0, kind, title, plan, name)
        }
    } catch (_: IndexOutOfBoundsException) {
        null // expected: cut-off bytes (damaged local store line); the caller counts it as unreadable
    } catch (_: IllegalArgumentException) {
        null // expected: lengths or numbers out of range in damaged bytes
    }

    /** The blob for [item] in the mailbox of [keys]; [nonce] = 12 fresh random bytes (never reuse one). */
    fun seal(keys: SyncKeys, item: SyncItem, nonce: ByteArray, aead: Aead = AesGcm): ByteArray {
        require(nonce.size == AesGcm.NONCE)
        val sealed = aead.seal(keys.content, nonce, aad(keys, item.id), encode(item))
        return byteArrayOf(BLOB_VERSION.toByte()) + nonce + sealed
    }

    /** The item in a [blob] stored as [itemId], or null (wrong key, damaged, moved, newer format). */
    fun open(keys: SyncKeys, itemId: String, blob: ByteArray, aead: Aead = AesGcm): SyncItem? {
        if (blob.size < 1 + AesGcm.NONCE + AesGcm.TAG || blob[0].toInt() != BLOB_VERSION || !isId(itemId)) return null
        val plain = aead.open(keys.content, blob.copyOfRange(1, 13), aad(keys, itemId), blob.copyOfRange(13, blob.size)) ?: return null
        return decode(plain)?.takeIf { it.id == itemId }
    }

    /** Base64url for blobs on the wire (the server's JSON and its D1 rows). */
    fun toText(b: ByteArray) = B64.encode(b)
    fun fromText(s: String): ByteArray? = B64.decode(s)

    private fun aad(keys: SyncKeys, itemId: String) =
        byteArrayOf(BLOB_VERSION.toByte()) + keys.mailboxBytes + (B64.decode(itemId) ?: ByteArray(0))

    private fun clean(s: String?, max: Int): String? =
        s?.filter { it >= ' ' }?.trim()?.take(max)?.trim()?.takeIf { it.isNotEmpty() }

    private class Out {
        private var buf = ByteArray(128)
        private var size = 0
        fun byte(v: Int) { if (size == buf.size) buf = buf.copyOf(buf.size * 2); buf[size++] = v.toByte() }
        fun bytes(b: ByteArray) = b.forEach { byte(it.toInt()) }
        fun varint(v: Long) { var x = v; while (x >= 0x80) { byte(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }; byte(x.toInt()) }
        fun field(tag: Int, data: ByteArray) { varint(tag.toLong()); varint(data.size.toLong()); bytes(data) }
        fun toArray() = buf.copyOf(size)
    }

    private class In(private val b: ByteArray) {
        private var pos = 0
        fun more() = pos < b.size
        fun left() = b.size - pos
        fun byte(): Int = b[pos++].toInt() and 0xff
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val v = byte()
                result = result or ((v and 0x7f).toLong() shl shift)
                if (v and 0x80 == 0) return result
                shift += 7
                require(shift < 63)
            }
        }
        fun take(n: Int): ByteArray { if (n > left()) throw IndexOutOfBoundsException(); return b.copyOfRange(pos, pos + n).also { pos += n } }
    }
}
