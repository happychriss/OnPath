package com.onpath.app.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/** [PairCode.parse] finds its code by hand: it accepts and refuses exactly what the Regex it replaced did. */
class PairCodeMarkerTest {
    private val regex = Regex("""(?:^|[#?&])pair=([A-Za-z0-9_-]+)""")
    private val secret = ByteArray(PairCode.SECRET_BYTES) { (it * 7 + 1).toByte() }
    private val code = PairCode.encode(secret)

    private fun before(text: String): ByteArray? {
        val t = text.trim()
        if (t.isEmpty() || t.length > 4000) return null
        val c = regex.find(t)?.groupValues?.get(1) ?: return null
        val b = B64.decode(c) ?: return null
        if (b.size < 1 + PairCode.SECRET_BYTES || b[0].toInt() != PairCode.VERSION) return null
        return b.copyOfRange(1, 1 + PairCode.SECRET_BYTES)
    }

    @Test fun theSameAnswerAsTheRegex() {
        val parts = listOf("pair=", "#pair=", "?pair=", "&pair=", "repair=", "#Pair=", "pair", "=", "#", "?", "&", " ", "\n", "\t", "ü", "/",
            "https://plan.onpath.earth/", code, code.dropLast(1), code + "A", code + "AAAA", "x", "AQ", "-", "_", "%", "p=")
        val r = Random(20261122)
        repeat(50_000) {
            val text = buildString { repeat(r.nextInt(1, 8)) { append(parts[r.nextInt(parts.size)]) } }
            val want = before(text)
            if (want == null) assertNull(text, PairCode.parse(text)) else assertArrayEquals(text, want, PairCode.parse(text))
        }
    }

    @Test fun theLengthLimitStays() {
        val at = "#pair=$code"
        assertArrayEquals(secret, PairCode.parse(at + " ".repeat(5000)))
        assertArrayEquals(secret, PairCode.parse(at + "&" + "x".repeat(4000 - at.length - 1)))
        assertNull(PairCode.parse(at + "&" + "x".repeat(4000 - at.length)))
    }
}
