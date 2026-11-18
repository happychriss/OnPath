// Port of btools.util.ByteArrayUnifier (BRouter 1.7.11-beta, MIT)
package btools.util

class ByteArrayUnifier(private val size: Int, validateImmutability: Boolean) : IByteArrayUnifier {
  private val byteArrayCache: Array<ByteArray?> = arrayOfNulls(size)
  private val crcCrosscheck: IntArray? = if (validateImmutability) IntArray(size) else null

  /**
   * Unify a byte array in order to reuse instances when possible.
   * The byte arrays are assumed to be treated as immutable, allowing the reuse
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
        if (ab[offset + i] != abc[i]) break
        i++
      }
      if (i == len) return abc
    }
    if (crcCrosscheck != null) {
      val abold = byteArrayCache[idx]
      if (abold != null) {
        val crcold = Crc32.crc(abold, 0, abold.size)
        if (crcold != crcCrosscheck[idx]) {
          throw IllegalArgumentException("ByteArrayUnifier: immutablity validation failed!")
        }
      }
      crcCrosscheck[idx] = crc
    }
    val nab = ByteArray(len)
    ab.copyInto(nab, 0, offset, offset + len)
    byteArrayCache[idx] = nab
    return nab
  }
}
