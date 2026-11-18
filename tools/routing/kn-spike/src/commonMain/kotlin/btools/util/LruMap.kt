// Port of btools.util.LruMap (BRouter 1.7.11-beta, MIT)
package btools.util

/**
 * Something like LinkedHashMap, but purpose build, less dynamic and memory efficient
 */
class LruMap(bins: Int, size: Int) {
  private val hashbins: Int = bins
  private val maxsize: Int = size
  private var size = 0

  private var lru: LruMapNode? = null
  private var mru: LruMapNode? = null

  private val binArray: Array<LruMapNode?> = arrayOfNulls(hashbins)

  fun get(key: LruMapNode): LruMapNode? {
    val bin = (key.hash and 0xfffffff) % hashbins

    var e = binArray[bin]
    while (e != null) {
      if (key == e) {
        return e
      }
      e = e.nextInBin
    }
    return null
  }

  // put e to the mru end of the queue
  fun touch(e: LruMapNode) {
    val n = e.next
    val p = e.previous

    if (n == null) {
      return // already at mru
    }
    n.previous = p
    if (p != null) {
      p.next = n
    } else {
      lru = n
    }

    mru!!.next = e
    e.previous = mru
    e.next = null
    mru = e
  }

  fun removeLru(): LruMapNode? {
    if (size < maxsize) {
      return null
    }
    size--
    val lru = this.lru!!
    // unlink the lru from it's bin-queue
    val bin = (lru.hashCode() and 0xfffffff) % hashbins
    var e = binArray[bin]
    if (e === lru) {
      binArray[bin] = lru.nextInBin
    } else {
      while (e != null) {
        val prev: LruMapNode = e
        e = e.nextInBin
        if (e === lru) {
          prev.nextInBin = lru.nextInBin
          break
        }
      }
    }

    val res = lru
    this.lru = lru.next
    this.lru!!.previous = null
    return res
  }

  fun put(v: LruMapNode) {
    val bin = (v.hashCode() and 0xfffffff) % hashbins
    v.nextInBin = binArray[bin]
    binArray[bin] = v

    v.previous = mru
    v.next = null
    if (mru == null) {
      lru = v
    } else {
      mru!!.next = v
    }
    mru = v
    size++
  }
}
