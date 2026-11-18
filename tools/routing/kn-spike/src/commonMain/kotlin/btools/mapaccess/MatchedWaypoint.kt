// Port of btools.mapaccess.MatchedWaypoint (BRouter 1.7.11-beta, MIT)
// Left out: writeToStream / readFromStream (DataOutput / DataInput, only used for the raw-track files).
package btools.mapaccess

import kotlin.jvm.JvmField

/**
 * Information on matched way point
 */
class MatchedWaypoint {
  @JvmField
  var node1: OsmNode? = null

  @JvmField
  var node2: OsmNode? = null

  @JvmField
  var crosspoint: OsmNode? = null

  @JvmField
  var waypoint: OsmNode? = null

  @JvmField
  var correctedpoint: OsmNode? = null

  @JvmField
  var name: String? = null // waypoint name used in error messages

  @JvmField
  var radius: Double = 0.0 // distance in meter between waypoint and crosspoint

  @JvmField
  var wpttype: Byte = WAYPOINT_TYPE_SHAPING

  @JvmField
  var indexInTrack: Int = 0

  @JvmField
  var directionToNext: Double = -1.0

  @JvmField
  var directionDiff: Double = 361.0

  @JvmField
  var wayNearest: MutableList<MatchedWaypoint> = ArrayList()

  @JvmField
  var hasUpdate: Boolean = false

  companion object {
    const val WAYPOINT_TYPE_SHAPING: Byte = 1 // route next to this point
    const val WAYPOINT_TYPE_MEETING: Byte = 2 // visit this point
    const val WAYPOINT_TYPE_DIRECT: Byte = 3 // from this point go direct to next = beeline routing
    const val WAYPOINT_TYPE_ROUNDTRIP: Byte = 4 // from this point start round trip routing
  }
}
