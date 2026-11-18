/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */
package btools.mapaccess

open class OsmLink() {
    /**
     * The description bitmap contains the waytags (valid for both directions)
     */
    var descriptionBitmap: ByteArray? = null

    /**
     * The geometry contains intermediate nodes, null for none (valid for both directions)
     */
    var geometry: ByteArray? = null

    // a link logically knows only its target, but for the reverse link, source and target are swapped
    // (Java: protected, i.e. package visible too -> public here)
    var n1: OsmNode? = null
    var n2: OsmNode? = null

    // same for the next-link-for-node pointer: previous applies to the reverse link
    var previous: OsmLink? = null
    var next: OsmLink? = null

    private var reverselinkholder: OsmLinkHolder? = null
    private var firstlinkholder: OsmLinkHolder? = null

    constructor(source: OsmNode, target: OsmNode) : this() {
        n1 = source
        n2 = target
    }

    /**
     * Get the relevant target-node for the given source
     */
    // PORT-NOTE Java returns null when n1 == null too; every caller dereferences the result, so it is typed non-null here (NPE via !!)
    fun getTarget(source: OsmNode?): OsmNode {
        return (if (n2 !== source && n2 != null) n2 else n1)!!
    }

    /**
     * Get the relevant next-pointer for the given source
     */
    fun getNext(source: OsmNode?): OsmLink? {
        return if (n2 !== source && n2 != null) next else previous
    }

    /**
     * Reset this link for the given direction
     */
    fun clear(source: OsmNode?): OsmLink? {
        val n: OsmLink?
        if (n2 != null && n2 !== source) {
            n = next
            next = null
            n2 = null
            firstlinkholder = null
        } else if (n1 != null && n1 !== source) {
            n = previous
            previous = null
            n1 = null
            reverselinkholder = null
        } else {
            throw IllegalArgumentException("internal error: setNext: unknown source")
        }
        if (n1 == null && n2 == null) {
            descriptionBitmap = null
            geometry = null
        }
        return n
    }

    fun setFirstLinkHolder(holder: OsmLinkHolder?, source: OsmNode?) {
        if (n2 != null && n2 !== source) {
            firstlinkholder = holder
        } else if (n1 != null && n1 !== source) {
            reverselinkholder = holder
        } else {
            throw IllegalArgumentException("internal error: setFirstLinkHolder: unknown source")
        }
    }

    fun getFirstLinkHolder(source: OsmNode?): OsmLinkHolder? {
        if (n2 != null && n2 !== source) {
            return firstlinkholder
        } else if (n1 != null && n1 !== source) {
            return reverselinkholder
        } else {
            throw IllegalArgumentException("internal error: getFirstLinkHolder: unknown source")
        }
    }

    fun isReverse(source: OsmNode?): Boolean {
        return n1 !== source && n1 != null
    }

    fun isBidirectional(): Boolean {
        return n1 != null && n2 != null
    }

    fun isLinkUnused(): Boolean {
        return n1 == null && n2 == null
    }

    fun addLinkHolder(holder: OsmLinkHolder, source: OsmNode?) {
        val firstHolder = getFirstLinkHolder(source)
        if (firstHolder != null) {
            holder.setNextForLink(firstHolder)
        }
        setFirstLinkHolder(holder, source)
    }
}
