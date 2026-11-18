// Port of btools.mapaccess.OsmTransferNode (BRouter 1.7.11-beta, MIT)
package btools.mapaccess

import kotlin.jvm.JvmField

/**
 * Container for link between two Osm nodes
 */
class OsmTransferNode {
  @JvmField
  var next: OsmTransferNode? = null

  @JvmField
  var ilon: Int = 0

  @JvmField
  var ilat: Int = 0

  @JvmField
  var selev: Short = 0
}
