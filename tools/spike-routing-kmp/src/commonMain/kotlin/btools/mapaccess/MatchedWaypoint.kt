/**
 * Information on matched way point
 *
 * @author ab
 */
package btools.mapaccess

class MatchedWaypoint {
    companion object {
        const val WAYPOINT_TYPE_SHAPING: Byte = 1 // route next to this point
        const val WAYPOINT_TYPE_MEETING: Byte = 2 // visit this point
        const val WAYPOINT_TYPE_DIRECT: Byte = 3 // from this point go direct to next = beeline routing
        const val WAYPOINT_TYPE_ROUNDTRIP: Byte = 4 // from this point start round trip routing
    }

    var node1: OsmNode? = null
    var node2: OsmNode? = null
    var crosspoint: OsmNode? = null
    var waypoint: OsmNode? = null
    var correctedpoint: OsmNode? = null
    var name: String? = null // waypoint name used in error messages
    var radius: Double = 0.0 // distance in meter between waypoint and crosspoint
    var wpttype: Byte = WAYPOINT_TYPE_SHAPING
    var indexInTrack: Int = 0
    var directionToNext: Double = -1.0
    var directionDiff: Double = 361.0

    var wayNearest: MutableList<MatchedWaypoint> = ArrayList()
    var hasUpdate: Boolean = false

    // PORT-CUT writeToStream(DataOutput) and readFromStream(DataInput): only used for raw tracks / AreaReader (always off)
}
