package btools.util

/**
 * dynamic list of primitive longs
 */
class LongList(capacity: Int) {
    private var a: LongArray = if (capacity < 4) LongArray(4) else LongArray(capacity)
    private var size: Int = 0

    fun add(value: Long) {
        if (size == a.size) {
            val aa = LongArray(2 * size)
            a.copyInto(aa, 0, 0, size)
            a = aa
        }
        a[size++] = value
    }

    fun get(idx: Int): Long {
        if (idx >= size) {
            throw IndexOutOfBoundsException("list size=" + size + " idx=" + idx)
        }
        return a[idx]
    }

    fun size(): Int {
        return size
    }
}
