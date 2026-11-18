/**
 * Simple version of OsmPath just to get angle and priority of first segment
 *
 * @author ab
 */
package btools.router

import btools.mapaccess.OsmLink
import btools.mapaccess.OsmNode

abstract class OsmPrePath {
  var sourceNode: OsmNode? = null
  var targetNode: OsmNode? = null
  var link: OsmLink? = null

  var next: OsmPrePath? = null

  fun init(origin: OsmPath, link: OsmLink, rc: RoutingContext) {
    this.link = link
    this.sourceNode = origin.getTargetNode()
    this.targetNode = link.getTarget(sourceNode!!)
    initPrePath(origin, rc)
  }

  abstract fun initPrePath(origin: OsmPath, rc: RoutingContext)
}
