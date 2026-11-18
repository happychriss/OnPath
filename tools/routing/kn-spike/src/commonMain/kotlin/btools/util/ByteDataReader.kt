// Port of btools.util.ByteDataReader (BRouter 1.7.11-beta, MIT)
package btools.util

/**
 * fast data-reading from a byte-array
 *
 * Port note: Java's `ab` may be null (empty cache); here it is never null, a null array is held as
 * the shared zero-length [EMPTY] (so `ab == null ? 0 : ab.length` is just `ab.size`).
 */
open class ByteDataReader {
  var ab: ByteArray
  var aboffset: Int = 0
  var aboffsetEnd: Int

  constructor(byteArray: ByteArray?) {
    ab = byteArray ?: EMPTY
    aboffsetEnd = ab.size
  }

  constructor(byteArray: ByteArray?, offset: Int) {
    ab = byteArray ?: EMPTY
    aboffset = offset
    aboffsetEnd = ab.size
  }

  fun reset(byteArray: ByteArray?) {
    ab = byteArray ?: EMPTY
    aboffset = 0
    aboffsetEnd = ab.size
  }

  fun readInt(): Int {
    val i3 = ab[aboffset++].toInt() and 0xff
    val i2 = ab[aboffset++].toInt() and 0xff
    val i1 = ab[aboffset++].toInt() and 0xff
    val i0 = ab[aboffset++].toInt() and 0xff
    return (i3 shl 24) + (i2 shl 16) + (i1 shl 8) + i0
  }

  fun readLong(): Long {
    val i7 = (ab[aboffset++].toInt() and 0xff).toLong()
    val i6 = (ab[aboffset++].toInt() and 0xff).toLong()
    val i5 = (ab[aboffset++].toInt() and 0xff).toLong()
    val i4 = (ab[aboffset++].toInt() and 0xff).toLong()
    val i3 = (ab[aboffset++].toInt() and 0xff).toLong()
    val i2 = (ab[aboffset++].toInt() and 0xff).toLong()
    val i1 = (ab[aboffset++].toInt() and 0xff).toLong()
    val i0 = (ab[aboffset++].toInt() and 0xff).toLong()
    return (i7 shl 56) + (i6 shl 48) + (i5 shl 40) + (i4 shl 32) + (i3 shl 24) + (i2 shl 16) + (i1 shl 8) + i0
  }

  fun readBoolean(): Boolean {
    val i0 = ab[aboffset++].toInt() and 0xff
    return i0 != 0
  }

  fun readByte(): Byte {
    val i0 = ab[aboffset++].toInt() and 0xff
    return i0.toByte()
  }

  fun readShort(): Short {
    val i1 = ab[aboffset++].toInt() and 0xff
    val i0 = ab[aboffset++].toInt() and 0xff
    return ((i1 shl 8) or i0).toShort()
  }

  /**
   * Read a size value and return a pointer to the end of a data section of that size
   *
   * @return the pointer to the first byte after that section
   */
  fun getEndPointer(): Int {
    val size = readVarLengthUnsigned()
    return aboffset + size
  }

  fun readDataUntil(endPointer: Int): ByteArray? {
    val size = endPointer - aboffset
    if (size == 0) {
      return null
    }
    val data = ByteArray(size)
    readFully(data)
    return data
  }

  fun readVarBytes(): ByteArray? {
    val len = readVarLengthUnsigned()
    if (len == 0) {
      return null
    }
    val bytes = ByteArray(len)
    readFully(bytes)
    return bytes
  }

  fun readVarLengthSigned(): Int {
    val v = readVarLengthUnsigned()
    return if ((v and 1) == 0) v shr 1 else -(v shr 1)
  }

  fun readVarLengthUnsigned(): Int {
    var b: Int = ab[aboffset++].toInt()
    var v = b and 0x7f
    if (b >= 0) return v
    b = ab[aboffset++].toInt()
    v = v or ((b and 0x7f) shl 7)
    if (b >= 0) return v
    b = ab[aboffset++].toInt()
    v = v or ((b and 0x7f) shl 14)
    if (b >= 0) return v
    b = ab[aboffset++].toInt()
    v = v or ((b and 0x7f) shl 21)
    if (b >= 0) return v
    b = ab[aboffset++].toInt()
    v = v or ((b and 0xf) shl 28)
    return v
  }

  fun readFully(ta: ByteArray) {
    ab.copyInto(ta, 0, aboffset, aboffset + ta.size)
    aboffset += ta.size
  }

  fun hasMoreData(): Boolean {
    return aboffset < aboffsetEnd
  }

  override fun toString(): String {
    val sb = StringBuilder("[")
    for (i in ab.indices) {
      sb.append(if (i == 0) " " else ", ").append(ab[i].toInt().toString())
    }
    sb.append(" ]")
    return sb.toString()
  }

  companion object {
    /** stands for Java's null array */
    val EMPTY = ByteArray(0)
  }
}
