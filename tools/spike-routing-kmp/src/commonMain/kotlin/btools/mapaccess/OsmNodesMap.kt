/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */
package btools.mapaccess

import btools.util.ByteArrayUnifier

class OsmNodesMap {
    // PORT-NOTE hash order: this is a plain java.util.HashMap keyed by OsmNode (hashCode = ilon + ilat); collectOutreachers iterates
    // hmap.values(), and that order differs between the JVM and Kotlin/Native (only the nodesCreated count / which nodes vanish first)
    private val hmap: HashMap<OsmNode, OsmNode> = HashMap(4096)

    private val abUnifier: ByteArrayUnifier = ByteArrayUnifier(16384, false)

    private val testKey: OsmNode = OsmNode()

    var nodesCreated: Int = 0
    var maxmem: Long = 0
    private var currentmaxmem: Long = 4000000 // start with 4 MB
    var lastVisitID: Int = 1000
    var baseID: Int = 1000

    var destination: OsmNode? = null
    var currentPathCost: Int = 0
    var currentMaxCost: Int = 1000000000

    var endNode1: OsmNode? = null
    var endNode2: OsmNode? = null

    var cleanupMode: Int = 0

    fun cleanupAndCount(nodes: Array<OsmNode?>) {
        if (cleanupMode == 0) {
            justCount(nodes)
        } else {
            cleanupPeninsulas(nodes)
        }
    }

    private fun justCount(nodes: Array<OsmNode?>) {
        for (i in 0 until nodes.size) {
            val n = nodes[i]!!
            if (n.firstlink != null) {
                nodesCreated++
            }
        }
    }

    private fun cleanupPeninsulas(nodes: Array<OsmNode?>) {
        baseID = lastVisitID++
        for (i in 0 until nodes.size) { // loop over nodes again just for housekeeping
            val n = nodes[i]!!
            if (n.firstlink != null) {
                if (n.visitID == 1) {
                    // PORT-NOTE memory: Java catches StackOverflowError around this recursion (and ignores it). Not catchable in
                    // Kotlin common; on Native a very deep recursion would crash instead.
                    minVisitIdInSubtree(null, n)
                }
            }
        }
    }

    private fun minVisitIdInSubtree(source: OsmNode?, n: OsmNode): Int {
        if (n.visitID == 1) n.visitID = baseID // border node
        else n.visitID = lastVisitID++
        var minId = n.visitID
        nodesCreated++

        var nextLink: OsmLink? = null
        // Java: for (l = n.firstlink; l != null; l = nextLink) with `continue` in the body. The update runs at the top here
        // (l is not changed in the body, nextLink is not changed after being read), so `continue` behaves like in the for loop.
        var lNext: OsmLink? = n.firstlink
        while (lNext != null) {
            val l: OsmLink = lNext
            nextLink = l.getNext(n)
            lNext = nextLink

            val t = l.getTarget(n)
            if (t === source) continue
            if (t.isHollow()) continue

            var minIdSub = t.visitID
            if (minIdSub == 1) {
                minIdSub = baseID
            } else if (minIdSub == 0) {
                val nodesCreatedUntilHere = nodesCreated
                minIdSub = minVisitIdInSubtree(n, t)
                if (minIdSub > n.visitID) { // peninsula ?
                    nodesCreated = nodesCreatedUntilHere
                    n.unlinkLink(l)
                    t.unlinkLink(l)
                }
            } else if (minIdSub < baseID) {
                continue
            } else if (cleanupMode == 2) {
                minIdSub = baseID // in tree-mode, hitting anything is like a gateway
            }
            if (minIdSub < minId) minId = minIdSub
        }
        return minId
    }


    fun isInMemoryBounds(npaths: Int, extend: Boolean): Boolean {
//    long total = nodesCreated * 76L + linksCreated * 48L;
        var total: Long = nodesCreated * 95L + npaths * 200L

        if (extend) {
            total += 100000

            // when extending, try to have 1 MB  space
            val delta = total + 1900000 - currentmaxmem
            if (delta > 0) {
                currentmaxmem += delta
                if (currentmaxmem > maxmem) {
                    currentmaxmem = maxmem
                }
            }
        }
        return total <= currentmaxmem
    }

    private var nodes2check: ArrayList<OsmNode>? = null

    // is there an escape from this node
    // to a hollow node (or destination node) ?
    fun canEscape(n0: OsmNode): Boolean {
        var sawLowIDs = false
        lastVisitID++
        nodes2check!!.clear()
        nodes2check!!.add(n0)
        while (!nodes2check!!.isEmpty()) {
            val n = nodes2check!!.removeAt(nodes2check!!.size - 1)
            if (n.visitID < baseID) {
                n.visitID = lastVisitID
                nodesCreated++
                var l: OsmLink? = n.firstlink
                while (l != null) {
                    val t = l.getTarget(n)
                    nodes2check!!.add(t)
                    l = l.getNext(n)
                }
            } else if (n.visitID < lastVisitID) {
                sawLowIDs = true
            }
        }
        if (sawLowIDs) {
            return true
        }

        nodes2check!!.add(n0)
        while (!nodes2check!!.isEmpty()) {
            val n = nodes2check!!.removeAt(nodes2check!!.size - 1)
            if (n.visitID == lastVisitID) {
                n.visitID = lastVisitID
                nodesCreated--
                var l: OsmLink? = n.firstlink
                while (l != null) {
                    val t = l.getTarget(n)
                    nodes2check!!.add(t)
                    l = l.getNext(n)
                }
                n.vanish()
            }
        }

        return false
    }

    private fun addActiveNode(nodes2check: MutableList<OsmNode>, n: OsmNode) {
        n.visitID = lastVisitID
        nodesCreated++
        nodes2check.add(n)
    }

    fun clearTemp() {
        nodes2check = null
    }

    fun collectOutreachers() {
        nodes2check = ArrayList(nodesCreated)
        nodesCreated = 0
        for (n in hmap.values) { // PORT-NOTE hash order
            addActiveNode(nodes2check!!, n)
        }

        lastVisitID++
        baseID = lastVisitID

        while (!nodes2check!!.isEmpty()) {
            val n = nodes2check!!.removeAt(nodes2check!!.size - 1)
            n.visitID = lastVisitID

            var l: OsmLink? = n.firstlink
            while (l != null) {
                val t = l.getTarget(n)
                if (t.visitID != lastVisitID) {
                    addActiveNode(nodes2check!!, t)
                }
                l = l.getNext(n)
            }
            if (destination != null && currentMaxCost < 1000000000) {
                val distance = n.calcDistance(destination!!)
                if (distance > currentMaxCost - currentPathCost + 100) {
                    n.vanish()
                }
            }
            if (n.firstlink == null) {
                nodesCreated--
            }
        }
    }


    fun getByteArrayUnifier(): ByteArrayUnifier {
        return abUnifier
    }

    /**
     * Get a node from the map
     *
     * @return the node for the given id if exist, else null
     */
    fun get(ilon: Int, ilat: Int): OsmNode? {
        testKey.ilon = ilon
        testKey.ilat = ilat
        return hmap.get(testKey)
    }


    fun remove(node: OsmNode) {
        if (node !== endNode1 && node !== endNode2) { // keep endnodes in hollow-map even when loaded
            hmap.remove(node) // (needed for escape analysis)
        }
    }

    /**
     * Put a node into the map
     *
     * @return the previous node if that id existed, else null
     */
    fun put(node: OsmNode): OsmNode? {
        return hmap.put(node, node)
    }

    // PORT-CUT test cleanup (addLinks, main): only a manual test in Java
}
