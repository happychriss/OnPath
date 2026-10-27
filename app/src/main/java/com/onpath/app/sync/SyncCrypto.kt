package com.onpath.app.sync

/**
 * The small crypto set the plan sync needs (docs/design/web-sync.md §3), in pure Kotlin so the very same code runs in
 * the app and – compiled to WebAssembly by TeaVM, which has no javax.crypto – in the web planner: SHA-256, HMAC-SHA256,
 * HKDF-SHA256 and AES-256-GCM. Each is checked against the published test vectors (FIPS 180-4, RFC 4231, RFC 5869,
 * FIPS 197, the GCM spec's test cases) and against the JVM's own javax.crypto on random inputs (SyncCryptoTest), so a
 * platform implementation behind [Aead] (javax.crypto on Android, WebCrypto) gives byte-identical results.
 *
 * Not constant time (table lookups in AES): fine for encrypting one's own small plans on one's own device – there is
 * no remote party that could time it. The app may use javax.crypto instead (same bytes, proven by the test).
 */
object Sha256 {
    private val K = longArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ).map { it.toInt() }.toIntArray()
    private val H0 = longArrayOf(0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19)
        .map { it.toInt() }.toIntArray()

    fun hash(data: ByteArray): ByteArray {
        val padLen = (data.size + 9 + 63) / 64 * 64
        val m = data.copyOf(padLen)
        m[data.size] = 0x80.toByte()
        val bits = data.size.toLong() * 8
        for (i in 0 until 8) m[padLen - 1 - i] = (bits ushr (8 * i)).toByte()
        val h = H0.copyOf()
        val w = IntArray(64)
        for (off in 0 until padLen step 64) {
            for (t in 0 until 16) w[t] = int(m, off + 4 * t)
            for (t in 16 until 64) {
                val a = w[t - 15]; val b = w[t - 2]
                val s0 = a.rotateRight(7) xor a.rotateRight(18) xor (a ushr 3)
                val s1 = b.rotateRight(17) xor b.rotateRight(19) xor (b ushr 10)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
            for (t in 0 until 64) {
                val t1 = hh + (e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)) +
                    ((e and f) xor (e.inv() and g)) + K[t] + w[t]
                val t2 = (a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)) + ((a and b) xor (a and c) xor (b and c))
                hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
        }
        val out = ByteArray(32)
        for (i in 0 until 8) for (k in 0 until 4) out[4 * i + k] = (h[i] ushr (24 - 8 * k)).toByte()
        return out
    }

    private fun int(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) shl 24) or ((b[i + 1].toInt() and 0xff) shl 16) or
        ((b[i + 2].toInt() and 0xff) shl 8) or (b[i + 3].toInt() and 0xff)
}

/** HMAC-SHA256 (RFC 2104). */
object HmacSha256 {
    fun mac(key: ByteArray, data: ByteArray): ByteArray {
        val k = (if (key.size > 64) Sha256.hash(key) else key).copyOf(64)
        val inner = ByteArray(64) { (k[it].toInt() xor 0x36).toByte() }
        val outer = ByteArray(64) { (k[it].toInt() xor 0x5c).toByte() }
        return Sha256.hash(outer + Sha256.hash(inner + data))
    }
}

/** HKDF with SHA-256 (RFC 5869). */
object Hkdf {
    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray = HmacSha256.mac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 0..255 * 32)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var done = 0
        var i = 1
        while (done < length) {
            t = HmacSha256.mac(prk, t + info + byteArrayOf(i.toByte()))
            val n = minOf(32, length - done)
            t.copyInto(out, done, 0, n)
            done += n; i++
        }
        return out
    }

    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int) = expand(extract(salt, ikm), info, length)
}

/** Authenticated encryption as the sync uses it: AES-256-GCM, 12-byte nonce, 16-byte tag after the ciphertext. */
interface Aead {
    /** Ciphertext followed by the 16-byte tag. */
    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray

    /** The plain bytes, or null when the key, nonce, AAD or bytes do not match (tag check failed). */
    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray?
}

/** AES-256-GCM (FIPS 197 + NIST SP 800-38D), pure Kotlin. */
object AesGcm : Aead {
    const val TAG = 16
    const val NONCE = 12

    override fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        val aes = Aes256(key)
        val j0 = j0(nonce)
        val ct = ctr(aes, j0, plain)
        return ct + tag(aes, j0, aad, ct)
    }

    override fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? {
        if (sealed.size < TAG || nonce.size != NONCE || key.size != 32) return null
        val aes = Aes256(key)
        val j0 = j0(nonce)
        val ct = sealed.copyOfRange(0, sealed.size - TAG)
        val want = tag(aes, j0, aad, ct)
        var diff = 0
        for (i in 0 until TAG) diff = diff or (want[i].toInt() xor sealed[sealed.size - TAG + i].toInt())
        return if (diff == 0) ctr(aes, j0, ct) else null
    }

    private fun j0(nonce: ByteArray): ByteArray {
        require(nonce.size == NONCE) { "nonce must be 12 bytes" }
        return nonce.copyOf(16).also { it[15] = 1 }
    }

    private fun ctr(aes: Aes256, j0: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        val counter = j0.copyOf()
        var off = 0
        while (off < data.size) {
            for (i in 15 downTo 12) { counter[i] = (counter[i] + 1).toByte(); if (counter[i].toInt() != 0) break }
            val ks = aes.encrypt(counter)
            val n = minOf(16, data.size - off)
            for (i in 0 until n) out[off + i] = (data[off + i].toInt() xor ks[i].toInt()).toByte()
            off += n
        }
        return out
    }

    private fun tag(aes: Aes256, j0: ByteArray, aad: ByteArray, ct: ByteArray): ByteArray {
        val h = aes.encrypt(ByteArray(16))
        val hh = long(h, 0); val hl = long(h, 8)
        var xh = 0L; var xl = 0L
        fun absorb(b: ByteArray) {
            var off = 0
            while (off < b.size) {
                val block = b.copyOfRange(off, minOf(off + 16, b.size)).copyOf(16)
                xh = xh xor long(block, 0); xl = xl xor long(block, 8)
                val (zh, zl) = mul(xh, xl, hh, hl)
                xh = zh; xl = zl
                off += 16
            }
        }
        absorb(aad); absorb(ct)
        xh = xh xor (aad.size.toLong() * 8); xl = xl xor (ct.size.toLong() * 8)
        val (sh, sl) = mul(xh, xl, hh, hl)
        val s = ByteArray(16)
        for (i in 0 until 8) { s[i] = (sh ushr (56 - 8 * i)).toByte(); s[8 + i] = (sl ushr (56 - 8 * i)).toByte() }
        val e = aes.encrypt(j0)
        return ByteArray(16) { (s[it].toInt() xor e[it].toInt()).toByte() }
    }

    /** Multiplication in GF(2^128) with GCM's bit order (SP 800-38D, algorithm 1). */
    private fun mul(xh: Long, xl: Long, yh: Long, yl: Long): Pair<Long, Long> {
        var zh = 0L; var zl = 0L
        var vh = yh; var vl = yl
        for (i in 0 until 128) {
            val bit = if (i < 64) (xh ushr (63 - i)) and 1L else (xl ushr (127 - i)) and 1L
            if (bit != 0L) { zh = zh xor vh; zl = zl xor vl }
            val lsb = vl and 1L
            vl = (vl ushr 1) or (vh shl 63)
            vh = vh ushr 1
            if (lsb != 0L) vh = vh xor -0x1f00000000000000L // 0xE1 << 56
        }
        return zh to zl
    }

    private fun long(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xff)
        return v
    }
}

/** AES with a 256-bit key, encryption only (all GCM needs). */
internal class Aes256(key: ByteArray) {
    private val rk = IntArray(60)

    init {
        require(key.size == 32) { "AES-256 needs a 32-byte key" }
        for (i in 0 until 8) rk[i] = ((key[4 * i].toInt() and 0xff) shl 24) or ((key[4 * i + 1].toInt() and 0xff) shl 16) or
            ((key[4 * i + 2].toInt() and 0xff) shl 8) or (key[4 * i + 3].toInt() and 0xff)
        var rcon = 1
        for (i in 8 until 60) {
            var t = rk[i - 1]
            if (i % 8 == 0) {
                t = sub((t shl 8) or (t ushr 24)) xor (rcon shl 24)
                rcon = xtime(rcon)
            } else if (i % 8 == 4) t = sub(t)
            rk[i] = rk[i - 8] xor t
        }
    }

    fun encrypt(block: ByteArray): ByteArray {
        val s = IntArray(16) { block[it].toInt() and 0xff }
        addKey(s, 0)
        for (round in 1 until 14) {
            subShift(s)
            for (c in 0 until 4) {
                val a0 = s[4 * c]; val a1 = s[4 * c + 1]; val a2 = s[4 * c + 2]; val a3 = s[4 * c + 3]
                s[4 * c] = xtime(a0) xor xtime(a1) xor a1 xor a2 xor a3
                s[4 * c + 1] = a0 xor xtime(a1) xor xtime(a2) xor a2 xor a3
                s[4 * c + 2] = a0 xor a1 xor xtime(a2) xor xtime(a3) xor a3
                s[4 * c + 3] = xtime(a0) xor a0 xor a1 xor a2 xor xtime(a3)
            }
            addKey(s, round)
        }
        subShift(s)
        addKey(s, 14)
        return ByteArray(16) { s[it].toByte() }
    }

    private fun addKey(s: IntArray, round: Int) {
        for (c in 0 until 4) {
            val w = rk[4 * round + c]
            for (r in 0 until 4) s[4 * c + r] = s[4 * c + r] xor ((w ushr (24 - 8 * r)) and 0xff)
        }
    }

    /** SubBytes, then ShiftRows (row r moves left by r columns). */
    private fun subShift(s: IntArray) {
        val t = IntArray(16) { SBOX[s[it]] }
        for (c in 0 until 4) for (r in 0 until 4) s[4 * c + r] = t[4 * ((c + r) % 4) + r]
    }

    private fun sub(w: Int) = (SBOX[(w ushr 24) and 0xff] shl 24) or (SBOX[(w ushr 16) and 0xff] shl 16) or
        (SBOX[(w ushr 8) and 0xff] shl 8) or SBOX[w and 0xff]

    companion object {
        fun xtime(x: Int) = ((x shl 1) xor (if (x and 0x80 != 0) 0x1b else 0)) and 0xff

        /** The S-box, computed (multiplicative inverse in GF(2^8) + affine map) instead of a typed table. */
        val SBOX = IntArray(256).also { s ->
            fun rotl(x: Int, n: Int) = ((x shl n) or (x ushr (8 - n))) and 0xff
            var p = 1
            var q = 1
            do {
                p = (p xor (p shl 1) xor (if (p and 0x80 != 0) 0x1b else 0)) and 0xff // p * 3
                q = q xor (q shl 1); q = q xor (q shl 2); q = q xor (q shl 4); q = q and 0xff // q / 3
                if (q and 0x80 != 0) q = q xor 0x09
                s[p] = (q xor rotl(q, 1) xor rotl(q, 2) xor rotl(q, 3) xor rotl(q, 4) xor 0x63) and 0xff
            } while (p != 1)
            s[0] = 0x63
        }
    }
}
