package btools.util

/**
 * Frozen instance of Memory efficient Set
 *
 * This one is readily sorted into a singe array for faster access
 */
class FrozenLongSet : CompactLongSet {
    private var faid: LongArray
    private var size: Int = 0
    private var p2size: Int = 0 // next power of 2 of size

    constructor(set: CompactLongSet) : super() {
        size = set.size()

        faid = LongArray(size)

        set.moveToFrozenArray(faid)

        p2size = 0x40000000
        while (p2size > size) p2size = p2size shr 1
    }

    override fun add(id: Long): Boolean {
        throw RuntimeException("cannot add on FrozenLongSet")
    }

    override fun fastAdd(id: Long) {
        throw RuntimeException("cannot add on FrozenLongSet")
    }

    /**
     * @return the number of entries in this set
     */
    override fun size(): Int {
        return size
    }


    /**
     * @return true if "id" is contained in this set.
     */
    override fun contains(id: Long): Boolean {
        if (size == 0) {
            return false
        }
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
        return a[n] == id
    }
}
