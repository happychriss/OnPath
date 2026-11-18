/**
 * Information on matched way point
 *
 * @author ab
 */
package btools.router

class MessageData {
  var linkdist: Int = 0
  var linkelevationcost: Int = 0
  var linkturncost: Int = 0
  var linknodecost: Int = 0
  var linkinitcost: Int = 0

  var costfactor: Float = 0f
  var priorityclassifier: Int = 0
  var classifiermask: Int = 0
  var turnangle: Float = 0f
  var wayKeyValues: String? = null
  var nodeKeyValues: String? = null

  var lon: Int = 0
  var lat: Int = 0
  var ele: Short = 0

  var time: Float = 0f
  var energy: Float = 0f

  // speed profile
  var vmaxExplicit: Int = -1
  var vmax: Int = -1
  var vmin: Int = -1
  var vnode0: Int = 999
  var vnode1: Int = 999
  var extraTime: Int = 0

  fun toMessage(): String? {
    if (wayKeyValues == null) {
      return null
    }

    val iCost = (costfactor * 1000 + 0.5f).toInt()
    return (lon - 180000000).toString() + "\t" +
      (lat - 90000000).toString() + "\t" +
      (ele.toInt() / 4).toString() + "\t" +
      linkdist.toString() + "\t" +
      iCost.toString() + "\t" +
      linkelevationcost.toString() +
      "\t" + linkturncost.toString() +
      "\t" + linknodecost.toString() +
      "\t" + linkinitcost.toString() +
      "\t" + wayKeyValues +
      "\t" + (if (nodeKeyValues == null) "" else nodeKeyValues) +
      "\t" + (time.toInt()).toString() +
      "\t" + (energy.toInt()).toString()
  }

  fun add(d: MessageData) {
    linkdist += d.linkdist
    linkelevationcost += d.linkelevationcost
    linkturncost += d.linkturncost
    linknodecost += d.linknodecost
    linkinitcost += d.linkinitcost
  }

  // PORT-NOTE Java clone(): shallow copy of every field (all primitives or immutable Strings)
  fun copy(): MessageData {
    val c = MessageData()
    c.linkdist = linkdist
    c.linkelevationcost = linkelevationcost
    c.linkturncost = linkturncost
    c.linknodecost = linknodecost
    c.linkinitcost = linkinitcost
    c.costfactor = costfactor
    c.priorityclassifier = priorityclassifier
    c.classifiermask = classifiermask
    c.turnangle = turnangle
    c.wayKeyValues = wayKeyValues
    c.nodeKeyValues = nodeKeyValues
    c.lon = lon
    c.lat = lat
    c.ele = ele
    c.time = time
    c.energy = energy
    c.vmaxExplicit = vmaxExplicit
    c.vmax = vmax
    c.vmin = vmin
    c.vnode0 = vnode0
    c.vnode1 = vnode1
    c.extraTime = extraTime
    return c
  }

  fun clone(): MessageData = copy()

  override fun toString(): String {
    return "dist=" + linkdist + " prio=" + priorityclassifier + " turn=" + turnangle
  }

  fun getPrio(): Int {
    return priorityclassifier
  }

  fun isBadOneway(): Boolean {
    return (classifiermask and 1) != 0
  }

  fun isGoodOneway(): Boolean {
    return (classifiermask and 2) != 0
  }

  fun isRoundabout(): Boolean {
    return (classifiermask and 4) != 0
  }

  fun isLinktType(): Boolean {
    return (classifiermask and 8) != 0
  }

  fun isGoodForCars(): Boolean {
    return (classifiermask and 16) != 0
  }

}
