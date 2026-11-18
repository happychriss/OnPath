/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */
package btools.router

import btools.expressions.BExpressionContext
import btools.expressions.BExpressionContextNode
import btools.expressions.BExpressionContextWay

class StdModel : OsmPathModel() {
  override fun createPrePath(): OsmPrePath? {
    return null
  }

  override fun createPath(): OsmPath {
    return StdPath()
  }

  var ctxWay: BExpressionContextWay? = null
  var ctxNode: BExpressionContextNode? = null

  override fun init(expctxWay: BExpressionContextWay, expctxNode: BExpressionContextNode, keyValues: Map<String, String>?) {
    ctxWay = expctxWay
    ctxNode = expctxNode

    val expctxGlobal: BExpressionContext = expctxWay // just one of them...
  }
}
