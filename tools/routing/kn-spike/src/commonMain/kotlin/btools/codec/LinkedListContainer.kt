// Port of btools.codec.LinkedListContainer (BRouter 1.7.11-beta, MIT)
package btools.codec

/**
 * Simple container for a list of lists of integers
 */
class LinkedListContainer(nlists: Int, defaultbuffer: IntArray?) {
  private var ia: IntArray = defaultbuffer ?: IntArray(nlists * 4) // prev, data, prev, data, ...
  private var size = 0
  private val startpointer = IntArray(nlists) // 0=void, odd=head-data-cell
  private var listpointer = 0

  /**
   * Add a data element to the given list
   */
  fun addDataElement(listNr: Int, data: Int) {
    if (size + 2 > ia.size) {
      resize()
    }
    ia[size++] = startpointer[listNr]
    startpointer[listNr] = size
    ia[size++] = data
  }

  /**
   * Initialize a list for reading
   *
   * @return the number of entries in that list
   */
  fun initList(listNr: Int): Int {
    var cnt = 0
    listpointer = startpointer[listNr]
    var lp = listpointer
    while (lp != 0) {
      lp = ia[lp - 1]
      cnt++
    }
    return cnt
  }

  /**
   * Get a data element from the list previously initialized.
   * Data elements are return in reverse order (lifo)
   */
  fun getDataElement(): Int {
    if (listpointer == 0) {
      throw IllegalArgumentException("no more element!")
    }
    val data = ia[listpointer]
    listpointer = ia[listpointer - 1]
    return data
  }

  private fun resize() {
    val ia2 = IntArray(2 * ia.size)
    ia.copyInto(ia2, 0, 0, ia.size)
    ia = ia2
  }
}
