// Port of btools.codec.MicroCache (BRouter 1.7.11-beta, MIT)
package btools.codec

import btools.util.ByteDataWriter
import kotlin.jvm.JvmField

/**
 * a micro-cache is a data cache for an area of some square kilometers or some
 * hundreds or thousands nodes
 *
 * This is the basic io-unit: always a full microcache is loaded from the
 * data-file if a node is requested at a position not yet covered by the caches
 * already loaded
 *
 * Left out (rd5 writing / rd5 diff files only): encodeMicroCache, compareWith, summary, _compareWith,
 * calcDelta, addDelta.
 * Port note: Java's null `ab` / `faid` / `fapos` are zero-length arrays here.
 */
open class MicroCache(ab: ByteArray?) : ByteDataWriter(ab) {
  var faid: IntArray = EMPTY_INTS
  var fapos: IntArray = EMPTY_INTS

  @JvmField
  var size = 0

  private var delcount = 0
  private var delbytes = 0
  private var p2size = 0 // next power of 2 of size

  // cache control: a virgin cache can be
  // put to ghost state for later recovery
  var virgin = true
  var ghost = false

  protected fun init(size: Int) {
    this.size = size
    delcount = 0
    delbytes = 0
    p2size = 0x40000000
    while (p2size > size) p2size = p2size shr 1
  }

  fun finishNode(id: Long) {
    fapos[size] = aboffset
    faid[size] = shrinkId(id)
    size++
  }

  fun discardNode() {
    aboffset = startPos(size)
  }

  fun getSize(): Int {
    return size
  }

  fun getDataSize(): Int {
    return ab.size
  }

  /**
   * Set the internal reader (aboffset, aboffsetEnd) to the body data for the given id
   *
   * If a node is not found in an empty cache, this is usually an edge-effect
   * (data-file does not exist or neighboured data-files of differnt age),
   * but is can as well be a symptom of a node-identity breaking bug.
   *
   * @return true if id was found
   */
  fun getAndClear(id64: Long): Boolean {
    if (size == 0) {
      return false
    }
    val id = shrinkId(id64)
    val a = faid
    var offset = p2size
    var n = 0

    while (offset > 0) {
      val nn = n + offset
      if (nn < size && a[nn] <= id) {
        n = nn
      }
      offset = offset shr 1
    }
    if (a[n] == id) {
      if ((fapos[n] and 0x80000000.toInt()) == 0) {
        aboffset = startPos(n)
        aboffsetEnd = fapos[n]
        fapos[n] = fapos[n] or 0x80000000.toInt() // mark deleted
        delbytes += aboffsetEnd - aboffset
        delcount++
        return true
      } else { // .. marked as deleted
        // throw new RuntimeException( "MicroCache: node already consumed: id=" + id );
      }
    }
    return false
  }

  protected fun startPos(n: Int): Int {
    return if (n > 0) fapos[n - 1] and 0x7fffffff else 0
  }

  fun collect(threshold: Int): Int {
    if (delcount <= threshold) {
      return 0
    }

    virgin = false

    val nsize = size - delcount
    if (nsize == 0) {
      faid = EMPTY_INTS
      fapos = EMPTY_INTS
    } else {
      val nfaid = IntArray(nsize)
      val nfapos = IntArray(nsize)
      var idx = 0

      val nab = ByteArray(ab.size - delbytes)
      var nab_off = 0
      for (i in 0 until size) {
        val pos = fapos[i]
        if ((pos and 0x80000000.toInt()) == 0) {
          val start = startPos(i)
          val end = fapos[i]
          val len = end - start
          ab.copyInto(nab, nab_off, start, start + len)
          nfaid[idx] = faid[i]
          nab_off += len
          nfapos[idx] = nab_off
          idx++
        }
      }
      faid = nfaid
      fapos = nfapos
      ab = nab
    }
    val deleted = delbytes
    init(nsize)
    return deleted
  }

  fun unGhost() {
    ghost = false
    delcount = 0
    delbytes = 0
    for (i in 0 until size) {
      fapos[i] = fapos[i] and 0x7fffffff // clear deleted flags
    }
  }

  /**
   * @return the 64-bit global id for the given cache-position
   */
  fun getIdForIndex(i: Int): Long {
    val id32 = faid[i]
    return expandId(id32)
  }

  /**
   * expand a 32-bit micro-cache-internal id into a 64-bit (lon|lat) global-id
   */
  open fun expandId(id32: Int): Long {
    throw IllegalArgumentException("expandId for empty cache")
  }

  /**
   * shrink a 64-bit (lon|lat) global-id into a a 32-bit micro-cache-internal id
   */
  open fun shrinkId(id64: Long): Int {
    throw IllegalArgumentException("shrinkId for empty cache")
  }

  /**
   * @return true if the given lon/lat position is internal for that micro-cache
   */
  open fun isInternal(ilon: Int, ilat: Int): Boolean {
    throw IllegalArgumentException("isInternal for empty cache")
  }

  companion object {
    private val EMPTY_INTS = IntArray(0)

    var debug = false

    val emptyNonVirgin: MicroCache = MicroCache(null)

    init {
      emptyNonVirgin.virgin = false
    }

    fun emptyCache(): MicroCache {
      return MicroCache(null) // TODO: singleton?
    }
  }
}
