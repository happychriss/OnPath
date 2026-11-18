// Port of btools.util.SortedHeap (BRouter 1.7.11-beta, MIT)
package btools.util

/**
 * Memory efficient and lightning fast heap to get the lowest-key value of a set of key-object pairs
 */
class SortedHeap<V> {
  private var size = 0
  private var peaksize = 0
  private var first: SortedBin
  private var second: SortedBin
  private var firstNonEmpty: SortedBin? = null

  init {
    // = clear()
    size = 0
    first = SortedBin(4, this)
    second = SortedBin(4, this)
    firstNonEmpty = null
  }

  /**
   * @return the lowest key value, or null if none
   */
  @Suppress("UNCHECKED_CAST")
  fun popLowestKeyValue(): V? {
    val bin = firstNonEmpty
    if (bin == null) {
      return null
    }
    size--
    val minBin = bin.getMinBin()
    return minBin.dropLowest() as V?
  }

  private class SortedBin(val binsize: Int, val parent: SortedHeap<*>) {
    var next: SortedBin? = null
    var nextNonEmpty: SortedBin? = null

    val al = IntArray(binsize) // key array
    val vla = arrayOfNulls<Any>(binsize) // value array
    var lv = 0 // low value
    var lp = binsize // low pointer

    fun next(): SortedBin {
      var nx = next
      if (nx == null) {
        nx = SortedBin(binsize shl 1, parent)
        next = nx
      }
      return nx
    }

    fun dropLowest(): Any? {
      val lpOld = lp
      if (++lp == binsize) {
        unlink()
      } else {
        lv = al[lp]
      }
      val res = vla[lpOld]
      vla[lpOld] = null
      return res
    }

    fun unlink() {
      var neBin = parent.firstNonEmpty!!
      if (neBin === this) {
        parent.firstNonEmpty = nextNonEmpty
        return
      }
      while (true) {
        val next = neBin.nextNonEmpty!!
        if (next === this) {
          neBin.nextNonEmpty = nextNonEmpty
          return
        }
        neBin = next
      }
    }

    fun add(key: Int, value: Any?) {
      var p = lp
      while (true) {
        if (p == binsize || key < al[p]) {
          al[p - 1] = key
          vla[p - 1] = value
          lv = al[--lp]
          return
        }
        al[p - 1] = al[p]
        vla[p - 1] = vla[p]
        p++
      }
    }

    // unrolled version of above for binsize = 4
    fun add4(key: Int, value: Any?) {
      var p = lp--
      if (p == 4 || key < al[p]) {
        al[p - 1] = key
        lv = key
        vla[p - 1] = value
        return
      }
      al[p - 1] = al[p]
      lv = al[p]
      vla[p - 1] = vla[p]
      p++

      if (p == 4 || key < al[p]) {
        al[p - 1] = key
        vla[p - 1] = value
        return
      }
      al[p - 1] = al[p]
      vla[p - 1] = vla[p]
      p++

      if (p == 4 || key < al[p]) {
        al[p - 1] = key
        vla[p - 1] = value
        return
      }
      al[p - 1] = al[p]
      vla[p - 1] = vla[p]

      al[p] = key
      vla[p] = value
    }

    // Java: unrolled 31 times (more non-empty bins cannot exist: sizes 4, 4, 8, 16 ... 2^30)
    fun getMinBin(): SortedBin {
      var minBin = this
      var bin = this.nextNonEmpty
      while (bin != null) {
        if (bin.lv < minBin.lv) minBin = bin
        bin = bin.nextNonEmpty
      }
      return minBin
    }
  }

  /**
   * add a key value pair to the heap
   *
   * @param key   the key to insert
   * @param value the value to insert object
   */
  fun add(key: Int, value: V) {
    size++

    if (first.lp == 0 && second.lp == 0) { // both full ?
      sortUp()
    }
    if (first.lp > 0) {
      first.add4(key, value)
      if (firstNonEmpty !== first) {
        first.nextNonEmpty = firstNonEmpty
        firstNonEmpty = first
      }
    } else { // second bin not full
      second.add4(key, value)
      if (first.nextNonEmpty !== second) {
        second.nextNonEmpty = first.nextNonEmpty
        first.nextNonEmpty = second
      }
    }
  }

  private fun sortUp() {
    if (size > peaksize) {
      peaksize = size
    }

    // determine the first array big enough to take them all
    var cnt = 8 // value count of first 2 bins is always 8
    var tbin = second // target bin
    var lastNonEmpty = second
    do {
      tbin = tbin.next()
      val nentries = tbin.binsize - tbin.lp
      if (nentries > 0) {
        cnt += nentries
        lastNonEmpty = tbin
      }
    } while (cnt > tbin.binsize)

    val al_t = tbin.al
    val vla_t = tbin.vla
    var tp = tbin.binsize - cnt // target pointer

    // unlink any higher, non-empty arrays
    val otherNonEmpty = lastNonEmpty.nextNonEmpty
    lastNonEmpty.nextNonEmpty = null

    // now merge the content of these non-empty bins into the target bin
    while (true) {
      val fne = firstNonEmpty ?: break
      // copy current minimum to target array
      val minBin = fne.getMinBin()
      al_t[tp] = minBin.lv
      vla_t[tp++] = minBin.dropLowest()
    }

    tp = tbin.binsize - cnt
    tbin.lp = tp // new target low pointer
    tbin.lv = tbin.al[tp]
    tbin.nextNonEmpty = otherNonEmpty
    firstNonEmpty = tbin
  }

  fun clear() {
    size = 0
    first = SortedBin(4, this)
    second = SortedBin(4, this)
    firstNonEmpty = null
  }

  fun getSize(): Int {
    return size
  }

  fun getPeakSize(): Int {
    return peaksize
  }

  fun getExtract(targetArray: Array<Any?>): Int {
    val tsize = targetArray.size
    val div = size / tsize + 1
    var tp = 0

    var lpi = 0
    var bin = firstNonEmpty
    while (bin != null) {
      lpi += bin.lp
      val vlai = bin.vla
      val n = bin.binsize
      while (lpi < n) {
        targetArray[tp++] = vlai[lpi]
        lpi += div
      }
      lpi -= n
      bin = bin.nextNonEmpty
    }
    return tp
  }
}
