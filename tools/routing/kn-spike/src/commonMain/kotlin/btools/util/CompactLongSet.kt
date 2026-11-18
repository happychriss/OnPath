// Port of btools.util.CompactLongSet (BRouter 1.7.11-beta, MIT)
package btools.util

/**
 * Memory efficient Set for long-keys
 */
open class CompactLongSet {
  private var al: Array<LongArray?>?
  private val pa: IntArray
  private var size = 0
  private val _maxKeepExponent = 14 // the maximum exponent to keep the invalid arrays

  init {
    // pointer array
    pa = IntArray(MAXLISTS)

    // allocate key lists
    val al0 = arrayOfNulls<LongArray>(MAXLISTS)
    al0[0] = LongArray(1) // make the first array (the transient buffer)
    al = al0
  }

  /**
   * @return the number of entries in this set
   */
  open fun size(): Int {
    return size
  }

  /**
   * add a long value to this set if not yet in.
   *
   * @return true if "id" already contained in this set.
   */
  open fun add(id: Long): Boolean {
    if (contains(id)) {
      return true
    }
    _add(id)
    return false
  }

  open fun fastAdd(id: Long) {
    _add(id)
  }

  private fun _add(id: Long) {
    if (size == Int.MAX_VALUE) {
      throw IllegalArgumentException("cannot grow beyond size Integer.MAX_VALUE")
    }
    val al = this.al!!

    // put the new entry in the first array
    al[0]!![0] = id

    // determine the first empty array
    var bp = size++ // treat size as bitpattern
    var idx = 1
    var n = 1

    pa[0] = 1
    pa[1] = 1

    while ((bp and 1) == 1) {
      bp = bp shr 1
      pa[idx++] = n
      n = n shl 1
    }

    // create it if not existant
    if (al[idx] == null) {
      al[idx] = LongArray(n)
    }
    val alt = al[idx]!!

    // now merge the contents of arrays 0...idx-1 into idx
    while (n > 0) {
      var maxId = 0L
      var maxIdx = -1

      for (i in 0 until idx) {
        val p = pa[i]
        if (p > 0) {
          val currentId = al[i]!![p - 1]
          if (maxIdx < 0 || currentId > maxId) {
            maxIdx = i
            maxId = currentId
          }
        }
      }

      // current maximum found, copy to target array
      if (n < alt.size && maxId == alt[n]) {
        throw IllegalArgumentException("duplicate key found in late check: $maxId")
      }
      --n
      alt[n] = maxId

      --pa[maxIdx]
    }

    // de-allocate empty arrays of a certain size (fix at 64kByte)
    while (idx-- > _maxKeepExponent) {
      al[idx] = null
    }
  }

  /**
   * @return true if "id" is contained in this set.
   */
  open fun contains(id: Long): Boolean {
    // determine the first empty array
    var bp = size // treat size as bitpattern
    var idx = 1

    while (bp != 0) {
      if ((bp and 1) == 1) {
        // array at idx is valid, check
        if (contains(idx, id)) {
          return true
        }
      }
      idx++
      bp = bp shr 1
    }
    return false
  }

  // does sorted array "a" contain "id" ?
  private fun contains(idx: Int, id: Long): Boolean {
    val a = al!![idx]!!
    var offset = a.size
    var n = 0

    while (true) {
      offset = offset shr 1
      if (offset <= 0) break
      val nn = n + offset
      if (a[nn] <= id) {
        n = nn
      }
    }
    return a[n] == id
  }

  // (protected in Java, called on another instance by FrozenLongSet)
  internal fun moveToFrozenArray(faid: LongArray) {
    val al = this.al!!
    for (i in 1 until MAXLISTS) {
      pa[i] = 0
    }

    for (ti in 0 until size) { // target-index
      var bp = size // treat size as bitpattern
      var minIdx = -1
      var minId = 0L
      var idx = 1
      while (bp != 0) {
        if ((bp and 1) == 1) {
          val p = pa[idx]
          if (p < al[idx]!!.size) {
            val currentId = al[idx]!![p]
            if (minIdx < 0 || currentId < minId) {
              minIdx = idx
              minId = currentId
            }
          }
        }
        idx++
        bp = bp shr 1
      }
      faid[ti] = minId
      pa[minIdx]++

      if (ti > 0 && faid[ti - 1] == minId) {
        throw IllegalArgumentException("duplicate key found in late check: $minId")
      }
    }

    // free the non-frozen array
    this.al = null
  }

  companion object {
    const val MAXLISTS = 31 // enough for size Integer.MAX_VALUE
  }
}
