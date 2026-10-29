package com.onpath.app.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * The sync's pure-Kotlin crypto against the published test vectors and against the JVM's javax.crypto (the same
 * classes Android has), so the browser (this code in Wasm) and the app (this code or javax.crypto) agree to the byte.
 */
class SyncCryptoTest {
    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test fun sha256Fips180Vectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hash(ByteArray(0)).hex())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hash("abc".toByteArray()).hex())
        assertEquals("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hash("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".toByteArray()).hex())
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", Sha256.hash(ByteArray(1_000_000) { 'a'.code.toByte() }).hex())
    }

    @Test fun hmacRfc4231Vectors() {
        assertEquals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            HmacSha256.mac(ByteArray(20) { 0x0b }, "Hi There".toByteArray()).hex())
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            HmacSha256.mac("Jefe".toByteArray(), "what do ya want for nothing?".toByteArray()).hex())
        assertEquals("60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54",
            HmacSha256.mac(ByteArray(131) { 0xaa.toByte() }, "Test Using Larger Than Block-Size Key - Hash Key First".toByteArray()).hex())
    }

    @Test fun hkdfRfc5869Vectors() {
        val ikm = ByteArray(22) { 0x0b }
        val prk = Hkdf.extract(hex("000102030405060708090a0b0c"), ikm)
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", prk.hex())
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Hkdf.expand(prk, hex("f0f1f2f3f4f5f6f7f8f9"), 42).hex())
        assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            Hkdf.derive(ikm, ByteArray(0), ByteArray(0), 42).hex())
    }

    @Test fun aes256Fips197Vector() {
        val key = ByteArray(32) { it.toByte() }
        assertEquals("8ea2b7ca516745bfeafc49904b496089", Aes256(key).encrypt(hex("00112233445566778899aabbccddeeff")).hex())
    }

    @Test fun aesGcmSpecTestCases() {
        // The GCM specification (McGrew & Viega), test cases 13–16 (AES-256)
        val zero = ByteArray(32)
        assertEquals("530f8afbc74536b9a963b4f1c4cb738b", AesGcm.seal(zero, ByteArray(12), ByteArray(0), ByteArray(0)).hex())
        assertEquals("cea7403d4d606b6e074ec5d3baf39d18" + "d0d1c8a799996bf0265b98b5d48ab919",
            AesGcm.seal(zero, ByteArray(12), ByteArray(0), ByteArray(16)).hex())
        val k = hex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308")
        val iv = hex("cafebabefacedbaddecaf888")
        val p = hex("d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b391aafd255")
        val c = "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662898015ad"
        assertEquals(c + "b094dac5d93471bdec1a502270e3cc6c", AesGcm.seal(k, iv, ByteArray(0), p).hex())
        val aad = hex("feedfacedeadbeeffeedfacedeadbeefabaddad2")
        val sealed = AesGcm.seal(k, iv, aad, p.copyOf(60))
        assertEquals(c.substring(0, 120) + "76fc6ece0f4e1768cddf8853bb2d551b", sealed.hex())
        assertArrayEquals(p.copyOf(60), AesGcm.open(k, iv, aad, sealed))
    }

    @Test fun gcmRefusesAnyChange() {
        val k = ByteArray(32) { 7 }; val iv = ByteArray(12) { 1 }; val aad = byteArrayOf(1, 2, 3)
        val sealed = AesGcm.seal(k, iv, aad, "Waalweg".toByteArray())
        for (i in sealed.indices) {
            val bad = sealed.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertNull("byte $i", AesGcm.open(k, iv, aad, bad))
        }
        assertNull(AesGcm.open(k, iv, byteArrayOf(1, 2, 4), sealed))
        assertNull(AesGcm.open(k, ByteArray(12) { 2 }, aad, sealed))
        assertNull(AesGcm.open(ByteArray(32) { 8 }, iv, aad, sealed))
        assertNull(AesGcm.open(k, iv, aad, sealed.copyOf(10)))
    }

    /** The same bytes as javax.crypto (Android's provider gives the same; the app may use either). */
    @Test fun sameAsJavaxCryptoOnRandomInputs() {
        val rnd = Random(42)
        val md = MessageDigest.getInstance("SHA-256")
        repeat(200) { n ->
            val data = rnd.nextBytes(rnd.nextInt(0, 300))
            assertArrayEquals(md.digest(data), Sha256.hash(data))
            val key = rnd.nextBytes(rnd.nextInt(1, 100))
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
            assertArrayEquals(mac.doFinal(data), HmacSha256.mac(key, data))
            val k = rnd.nextBytes(32); val iv = rnd.nextBytes(12); val aad = rnd.nextBytes(rnd.nextInt(0, 40))
            val jca = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, iv)); updateAAD(aad)
            }.doFinal(data)
            assertArrayEquals("case $n", jca, AesGcm.seal(k, iv, aad, data))
            assertArrayEquals(data, AesGcm.open(k, iv, aad, jca))
        }
    }
}
