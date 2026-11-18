package btools.util

class ByteArrayUnifier(size: Int, validateImmutability: Boolean) : IByteArrayUnifier {
    private var byteArrayCache: Array<ByteArray?> = arrayOfNulls<ByteArray>(size)
    private var crcCrosscheck: IntArray? = null
    private var size: Int = size

    init {
        if (validateImmutability) crcCrosscheck = IntArray(size)
    }

    /**
     * Unify a byte array in order to reuse instances when possible.
     * The byte arrays are assumed to be treated as immutable,
     * allowing the reuse
     *
     * @param ab the byte array to unify
     * @return the cached instance or the input instanced if not cached
     */
    fun unify(ab: ByteArray): ByteArray {
        return unify(ab, 0, ab.size)
    }

    override fun unify(ab: ByteArray, offset: Int, len: Int): ByteArray {
        val crc = Crc32.crc(ab, offset, len)
        val idx = (crc and 0xfffffff) % size
        val abc = byteArrayCache[idx]
        if (abc != null && abc.size == len) {
            var i = 0
            while (i < len) {
                if (ab[offset + i] != abc[i])
                    break
                i++
            }
            if (i == len)
                return abc
        }
        val cc = crcCrosscheck
        if (cc != null) {
            if (byteArrayCache[idx] != null) {
                val abold = byteArrayCache[idx]!!
                val crcold = Crc32.crc(abold, 0, abold.size)
                if (crcold != cc[idx])
                    throw IllegalArgumentException("ByteArrayUnifier: immutablity validation failed!")
            }
            cc[idx] = crc
        }
        val nab = ByteArray(len)
        ab.copyInto(nab, 0, offset, offset + len)
        byteArrayCache[idx] = nab
        return nab
    }
}
