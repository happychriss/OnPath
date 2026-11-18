/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */
package btools.mapaccess

class OsmTransferNode {
    var next: OsmTransferNode? = null

    var ilon: Int = 0
    var ilat: Int = 0
    var selev: Short = 0
}
