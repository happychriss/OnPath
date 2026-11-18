/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */
package btools.router

import btools.mapaccess.OsmLink
import btools.mapaccess.OsmLinkHolder
import btools.mapaccess.OsmNode
import btools.mapaccess.OsmTransferNode
import btools.mapaccess.TurnRestriction
import btools.util.CheapRuler

abstract class OsmPath : OsmLinkHolder {
  /**
   * The cost of that path (a modified distance)
   */
  var cost: Int = 0

  // the elevation assumed for that path can have a value
  // if the corresponding node has not
  var selev: Short = 0

  var airdistance: Int = 0 // distance to endpos

  // PORT-NOTE @JvmField only to avoid a JVM getter clash with getSourceNode()/getTargetNode()/getLink(); ignored on native
  @kotlin.jvm.JvmField
  var sourceNode: OsmNode? = null
  @kotlin.jvm.JvmField
  var targetNode: OsmNode? = null

  @kotlin.jvm.JvmField
  var link: OsmLink? = null
  var originElement: OsmPathElement? = null
  var myElement: OsmPathElement? = null

  private var nextForLink: OsmLinkHolder? = null

  var treedepth: Int = 0

  // the position of the waypoint just before
  // this path position (for angle calculation)
  var originLon: Int = 0
  var originLat: Int = 0

  // the classifier of the segment just before this paths position
  var lastClassifier: Float = 0f
  var lastInitialCost: Float = 0f

  var priorityclassifier: Int = 0

  var bitfield: Int = PATH_START_BIT

  private fun getBit(mask: Int): Boolean {
    return (bitfield and mask) != 0
  }

  private fun setBit(mask: Int, bit: Boolean) {
    if (getBit(mask) != bit) {
      bitfield = bitfield xor mask
    }
  }

  fun didEnterDestinationArea(): Boolean {
    return !getBit(HAD_DESTINATION_START_BIT) && getBit(IS_ON_DESTINATION_BIT)
  }

  var message: MessageData? = null

  fun init(link: OsmLink) {
    this.link = link
    targetNode = link.getTarget(null)
    selev = targetNode!!.getSElev()

    originLon = -1
    originLat = -1
  }

  fun init(origin: OsmPath, link: OsmLink, refTrack: OsmTrack?, detailMode: Boolean, rc: RoutingContext) {
    if (origin.myElement == null) {
      origin.myElement = OsmPathElement.create(origin)
    }
    this.originElement = origin.myElement
    this.link = link
    this.sourceNode = origin.targetNode
    this.targetNode = link.getTarget(sourceNode!!)
    this.cost = origin.cost
    this.lastClassifier = origin.lastClassifier
    this.lastInitialCost = origin.lastInitialCost
    this.bitfield = origin.bitfield
    this.priorityclassifier = origin.priorityclassifier
    init(origin)
    addAddionalPenalty(refTrack, detailMode, origin, link, rc)
  }

  abstract fun init(orig: OsmPath)

  abstract fun resetState()

  fun addAddionalPenalty(refTrack: OsmTrack?, detailMode: Boolean, origin: OsmPath, link: OsmLink, rc: RoutingContext) {
    val description: ByteArray? = link.descriptionBitmap
    if (description == null) { // could be a beeline path
      message = MessageData()
      if (message != null) {
        message!!.turnangle = 0f
        message!!.time = 1.toFloat()
        message!!.energy = 0.toFloat()
        message!!.priorityclassifier = 0
        message!!.classifiermask = 0
        message!!.lon = targetNode!!.getILon()
        message!!.lat = targetNode!!.getILat()
        message!!.ele = Short.MIN_VALUE
        message!!.linkdist = sourceNode!!.calcDistance(targetNode!!)
        message!!.wayKeyValues = "direct_segment=" + seg
        seg++
      }
      return
    }

    val recordTransferNodes = detailMode

    rc.nogoCost = 0.0

    // extract the 3 positions of the first section
    var lon0 = origin.originLon
    var lat0 = origin.originLat

    var lon1 = sourceNode!!.getILon()
    var lat1 = sourceNode!!.getILat()
    var ele1: Short = origin.selev

    var linkdisttotal = 0

    message = if (detailMode) MessageData() else null

    val isReverse = link.isReverse(sourceNode!!)

    // evaluate the way tags
    rc.expctxWay.evaluate(rc.inverseDirection xor isReverse, description)

    // and check if is useful
    if (rc.ai != null && rc.ai!!.polygon!!.isWithin(lon1, lat1)) {
      rc.ai!!.checkAreaInfo(rc.expctxWay, ele1.toDouble() / 4.0, description)
    }

    // calculate the costfactor inputs
    val costfactor: Float = rc.expctxWay.getCostfactor()
    val isTrafficBackbone = cost == 0 && rc.expctxWay.getIsTrafficBackbone() > 0.0f
    val lastpriorityclassifier = priorityclassifier
    priorityclassifier = rc.expctxWay.getPriorityClassifier().toInt()

    // *** add initial cost if the classifier changed
    val newClassifier: Float = rc.expctxWay.getInitialClassifier()
    val newInitialCost: Float = rc.expctxWay.getInitialcost()
    val classifierDiff: Float = newClassifier - lastClassifier
    if (newClassifier.toDouble() != 0.0 && lastClassifier.toDouble() != 0.0 && (classifierDiff.toDouble() > 0.0005 || classifierDiff.toDouble() < -0.0005)) {
      val initialcost: Float = if (rc.inverseDirection) lastInitialCost else newInitialCost
      if (initialcost.toDouble() >= 1000000.0) {
        cost = -1
        return
      }

      val iicost = initialcost.toInt()
      if (message != null) {
        message!!.linkinitcost += iicost
      }
      cost += iicost
    }
    lastClassifier = newClassifier
    lastInitialCost = newInitialCost

    // *** destination logic: no destination access in between
    val classifiermask = rc.expctxWay.getClassifierMask().toInt()
    val newDestination = (classifiermask and 64) != 0
    val oldDestination = getBit(IS_ON_DESTINATION_BIT)
    if (getBit(PATH_START_BIT)) {
      setBit(PATH_START_BIT, false)
      setBit(CAN_LEAVE_DESTINATION_BIT, newDestination)
      setBit(HAD_DESTINATION_START_BIT, newDestination)
    } else {
      if (oldDestination && !newDestination) {
        if (getBit(CAN_LEAVE_DESTINATION_BIT)) {
          setBit(CAN_LEAVE_DESTINATION_BIT, false)
        } else {
          cost = -1
          return
        }
      }
    }
    setBit(IS_ON_DESTINATION_BIT, newDestination)


    var transferNode: OsmTransferNode? = if (link.geometry == null) null
    else rc.geometryDecoder.decodeGeometry(link.geometry!!, sourceNode!!, targetNode!!, isReverse)

    var nsection = 0
    while (true) {

      originLon = lon1
      originLat = lat1

      val lon2: Int
      val lat2: Int
      val originEle2: Short

      if (transferNode == null) {
        lon2 = targetNode!!.ilon
        lat2 = targetNode!!.ilat
        originEle2 = targetNode!!.selev
      } else {
        lon2 = transferNode.ilon
        lat2 = transferNode.ilat
        originEle2 = transferNode.selev
      }
      var ele2: Short = originEle2

      var isStartpoint = lon0 == -1 && lat0 == -1

      // check turn restrictions (n detail mode (=final pass) no TR to not mess up voice hints)
      if (nsection == 0 && rc.considerTurnRestrictions && !detailMode && !isStartpoint) {
        if (if (rc.inverseDirection)
            TurnRestriction.isTurnForbidden(sourceNode!!.firstRestriction, lon2, lat2, lon0, lat0, rc.bikeMode || rc.footMode, rc.carMode)
          else TurnRestriction.isTurnForbidden(sourceNode!!.firstRestriction, lon0, lat0, lon2, lat2, rc.bikeMode || rc.footMode, rc.carMode)) {
          cost = -1
          return
        }
      }

      // if recording, new MessageData for each section (needed for turn-instructions)
      if (message != null && message!!.wayKeyValues != null) {
        originElement!!.message = message
        message = MessageData()
      }

      var dist = rc.calcDistance(lon1, lat1, lon2, lat2)

      var stopAtEndpoint = false
      if (rc.shortestmatch) {
        if (rc.isEndpoint) {
          stopAtEndpoint = true
          ele2 = interpolateEle(ele1, ele2, rc.wayfraction)
        } else {
          // we just start here, reset everything
          cost = 0
          resetState()
          lon0 = -1 // reset turncost-pipe
          lat0 = -1
          isStartpoint = true

          if (recordTransferNodes) {
            if (rc.wayfraction > 0.0) {
              ele1 = interpolateEle(ele1, ele2, 1.0 - rc.wayfraction)
              originElement = OsmPathElement.create(rc.ilonshortest, rc.ilatshortest, ele1, null)
            } else {
              originElement = null // prevent duplicate point
            }
          }

          if (rc.checkPendingEndpoint()) {
            dist = rc.calcDistance(rc.ilonshortest, rc.ilatshortest, lon2, lat2)
            if (rc.shortestmatch) {
              stopAtEndpoint = true
              ele2 = interpolateEle(ele1, ele2, rc.wayfraction)
            }
          }
        }
      }

      if (message != null) {
        message!!.linkdist += dist
      }
      linkdisttotal += dist

      // apply a start-direction if appropriate (by faking the origin position)
      if (isStartpoint) {
        if (rc.startDirectionValid) {
          val dir: Double = rc.startDirection!! * CheapRuler.DEG_TO_RAD
          val lonlat2m: DoubleArray = CheapRuler.getLonLatToMeterScales((lon0 + lat1) shr 1)
          lon0 = lon1 - (1000.0 * kotlin.math.sin(dir) / lonlat2m[0]).toInt()
          lat0 = lat1 - (1000.0 * kotlin.math.cos(dir) / lonlat2m[1]).toInt()
        } else {
          lon0 = lon1 - (lon2 - lon1)
          lat0 = lat1 - (lat2 - lat1)
        }
      }
      val angle: Double = rc.anglemeter.calcAngle(lon0, lat0, lon1, lat1, lon2, lat2)
      val cosangle: Double = rc.anglemeter.getCosAngle()

      // *** elevation stuff
      var delta_h = 0.0
      if (ele2 == Short.MIN_VALUE) ele2 = ele1
      if (ele1 != Short.MIN_VALUE) {
        delta_h = (ele2.toInt() - ele1.toInt()) / 4.0
        if (rc.inverseDirection) {
          delta_h = -delta_h
        }
      }


      val elevation: Double = if (ele2 == Short.MIN_VALUE) 100.0 else ele2.toDouble() / 4.0

      var sectionCost: Double = processWaySection(rc, dist.toDouble(), delta_h, elevation, angle, cosangle, isStartpoint, nsection, lastpriorityclassifier)
      if ((sectionCost < 0.0 || (costfactor.toDouble() > 9998.0 && !detailMode)) || sectionCost + cost >= 2000000000.0) {
        cost = -1
        return
      }

      if (isTrafficBackbone) {
        sectionCost = 0.0
      }

      cost += sectionCost.toInt()

      // compute kinematic
      computeKinematic(rc, dist.toDouble(), delta_h, detailMode)

      if (message != null) {
        message!!.turnangle = angle.toFloat()
        message!!.time = getTotalTime().toFloat()
        message!!.energy = getTotalEnergy().toFloat()
        message!!.priorityclassifier = priorityclassifier
        message!!.classifiermask = classifiermask
        message!!.lon = lon2
        message!!.lat = lat2
        message!!.ele = originEle2
        message!!.wayKeyValues = rc.expctxWay.getKeyValueDescription(isReverse, description)
      }

      if (stopAtEndpoint) {
        if (recordTransferNodes) {
          originElement = OsmPathElement.create(rc.ilonshortest, rc.ilatshortest, originEle2, originElement)
          originElement!!.cost = cost
          if (message != null) {
            originElement!!.message = message
          }
        }
        if (rc.nogoCost < 0) {
          cost = -1
        } else {
          cost = (cost + rc.nogoCost).toInt() // compound assignment int += double narrows
        }
        return
      }

      if (transferNode == null) {
        // *** penalty for being part of the reference track
        if (refTrack != null && refTrack.containsNode(targetNode!!) && refTrack.containsNode(sourceNode!!)) {
          val reftrackcost = linkdisttotal
          cost += reftrackcost
        }
        selev = ele2
        break
      }
      transferNode = transferNode.next

      if (recordTransferNodes) {
        originElement = OsmPathElement.create(lon2, lat2, originEle2, originElement)
        originElement!!.cost = cost
      }
      lon0 = lon1
      lat0 = lat1
      lon1 = lon2
      lat1 = lat2
      ele1 = ele2
      nsection++
    }

    // check for nogo-matches (after the *actual* start of segment)
    if (rc.nogoCost < 0) {
      cost = -1
      return
    } else {
      cost = (cost + rc.nogoCost).toInt() // compound assignment int += double narrows
    }

    // add target-node costs
    val targetCost: Double = processTargetNode(rc)
    if (targetCost < 0.0 || targetCost + cost >= 2000000000.0) {
      cost = -1
      return
    }
    cost += targetCost.toInt()
  }


  fun interpolateEle(e1: Short, e2: Short, fraction: Double): Short {
    if (e1 == Short.MIN_VALUE || e2 == Short.MIN_VALUE) {
      return Short.MIN_VALUE
    }
    return (e1.toDouble() * (1.0 - fraction) + e2.toDouble() * fraction).toInt().toShort()
  }

  abstract fun processWaySection(rc: RoutingContext, distance: Double, delta_h: Double, elevation: Double, angle: Double, cosangle: Double, isStartpoint: Boolean, nsection: Int, lastpriorityclassifier: Int): Double

  abstract fun processTargetNode(rc: RoutingContext): Double

  open fun computeKinematic(rc: RoutingContext, dist: Double, delta_h: Double, detailMode: Boolean) {
  }

  abstract fun elevationCorrection(): Int

  abstract fun definitlyWorseThan(p: OsmPath): Boolean

  fun getSourceNode(): OsmNode? {
    return sourceNode
  }

  fun getTargetNode(): OsmNode? {
    return targetNode
  }

  fun getLink(): OsmLink? {
    return link
  }


  override fun setNextForLink(holder: OsmLinkHolder?) {
    nextForLink = holder
  }

  override fun getNextForLink(): OsmLinkHolder? {
    return nextForLink
  }

  open fun getTotalTime(): Double {
    return 0.0
  }

  open fun getTotalEnergy(): Double {
    return 0.0
  }

  companion object {
    private const val PATH_START_BIT = 1
    private const val CAN_LEAVE_DESTINATION_BIT = 2
    private const val IS_ON_DESTINATION_BIT = 4
    private const val HAD_DESTINATION_START_BIT = 8

    var seg: Int = 1
  }
}
