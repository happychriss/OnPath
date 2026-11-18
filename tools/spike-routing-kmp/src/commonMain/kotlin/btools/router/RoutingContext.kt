/**
 * Container for routig configs
 *
 * @author ab
 */
package btools.router

import btools.expressions.BExpressionContext
import btools.expressions.BExpressionContextNode
import btools.expressions.BExpressionContextWay
import btools.mapaccess.GeometryDecoder
import btools.mapaccess.MatchedWaypoint
import btools.mapaccess.OsmLink
import btools.mapaccess.OsmNode
import btools.util.CheapAngleMeter
import btools.util.CheapRuler
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

class RoutingContext {
  fun setAlternativeIdx(idx: Int) {
    alternativeIdx = idx
  }

  fun getAlternativeIdx(min: Int, max: Int): Int {
    return if (alternativeIdx < min) min else (if (alternativeIdx > max) max else alternativeIdx)
  }

  var alternativeIdx = 0
  var localFunction: String? = null
  var profileTimestamp: Long = 0

  var keyValues: MutableMap<String, String>? = null

  var rawTrackPath: String? = null
  var rawAreaPath: String? = null

  fun getProfileName(): String {
    var name = if (localFunction == null) "unknown" else localFunction!!
    if (name.endsWith(".brf")) name = name.substring(0, localFunction!!.length - 4)
    val idx = name.lastIndexOf('/') // File.separatorChar
    if (idx >= 0) name = name.substring(idx + 1)
    return name
  }

  var expctxWay: BExpressionContextWay? = null
  var expctxNode: BExpressionContextNode? = null

  var geometryDecoder = GeometryDecoder()

  var memoryclass = 64

  var carMode = false
  var bikeMode = false
  var footMode = false
  var considerTurnRestrictions = false
  var processUnusedTags = false
  var forceSecondaryData = false
  var pass1coefficient = 0.0
  var pass2coefficient = 0.0
  var elevationpenaltybuffer = 0
  var elevationmaxbuffer = 0
  var elevationbufferreduce = 0

  var cost1speed = 0.0
  var additionalcostfactor = 0.0
  var changetime = 0.0
  var buffertime = 0.0
  var waittimeadjustment = 0.0
  var inittimeadjustment = 0.0
  var starttimeoffset = 0.0
  var transitonly = false

  var waypointCatchingRange = 0.0
  var correctMisplacedViaPoints = false
  var correctMisplacedViaPointsDistance = 0.0
  var continueStraight = false
  var useDynamicDistance = false
  var buildBeelineOnRange = false

  var ai: AreaInfo? = null

  private fun setModel(className: String?) {
    if (className == null) {
      pm = StdModel()
    } else {
      // PORT-CUT Class.forName path-model plugins (only StdModel exists)
      throw RuntimeException("Cannot create path-model: " + className)
    }
    initModel()
  }

  fun initModel() {
    pm!!.init(expctxWay, expctxNode, keyValues)
  }

  fun getKeyValueChecksum(): Long {
    var s = 0L
    if (keyValues != null) {
      for (e in keyValues!!.entries) { // PORT-NOTE hash order (sum is commutative, order does not matter)
        s += (e.key.hashCode() + e.value.hashCode())
      }
    }
    return s
  }

  fun readGlobalConfig() {
    val expctxGlobal: BExpressionContext = expctxWay!! // just one of them...
    setModel(expctxGlobal._modelClass)

    carMode = 0f != expctxGlobal.getVariableValue("validForCars", 0f)
    bikeMode = 0f != expctxGlobal.getVariableValue("validForBikes", 0f)
    footMode = 0f != expctxGlobal.getVariableValue("validForFoot", 0f)

    consider_crossing = 0f != expctxGlobal.getVariableValue("consider_crossing", 0f)

    crossing_Prio_H = expctxGlobal.getVariableValue("crossing_Prio_H", 0f).toInt()
    crossing_Prio_L = expctxGlobal.getVariableValue("crossing_Prio_L", 0f).toInt()

    cost_ToLeft_from_H_class1 = expctxGlobal.getVariableValue("cost_ToLeft_from_H_class1", 0f).toInt()
    cost_ToLeft_from_H_class2 = expctxGlobal.getVariableValue("cost_ToLeft_from_H_class2", 0f).toInt()
    cost_ToLeft_from_H_class3 = expctxGlobal.getVariableValue("cost_ToLeft_from_H_class3", 0f).toInt()
    cost_ToLeft_from_H_class4 = expctxGlobal.getVariableValue("cost_ToLeft_from_H_class4", 0f).toInt()
    cost_ToLeft_from_H_class5 = expctxGlobal.getVariableValue("cost_ToLeft_from_H_class5", 0f).toInt()
    cost_ToLeft_from_H_class6 = expctxGlobal.getVariableValue("cost_ToLeft_from_H_class6", 0f).toInt()

    // for left-hand traffic
    cost_ToRight_from_H_class1 = expctxGlobal.getVariableValue("cost_ToRight_from_H_class1", 0f).toInt()
    cost_ToRight_from_H_class2 = expctxGlobal.getVariableValue("cost_ToRight_from_H_class2", 0f).toInt()
    cost_ToRight_from_H_class3 = expctxGlobal.getVariableValue("cost_ToRight_from_H_class3", 0f).toInt()
    cost_ToRight_from_H_class4 = expctxGlobal.getVariableValue("cost_ToRight_from_H_class4", 0f).toInt()
    cost_ToRight_from_H_class5 = expctxGlobal.getVariableValue("cost_ToRight_from_H_class5", 0f).toInt()
    cost_ToRight_from_H_class6 = expctxGlobal.getVariableValue("cost_ToRight_from_H_class6", 0f).toInt()


    waypointCatchingRange = expctxGlobal.getVariableValue("waypointCatchingRange", 250f).toDouble()

    // turn-restrictions not used per default for foot profiles
    considerTurnRestrictions = 0f != expctxGlobal.getVariableValue("considerTurnRestrictions", if (footMode) 0f else 1f)

    correctMisplacedViaPoints = 0f != expctxGlobal.getVariableValue("correctMisplacedViaPoints", 0f)
    correctMisplacedViaPointsDistance = expctxGlobal.getVariableValue("correctMisplacedViaPointsDistance", 400f).toDouble() // 0 == don't use distance

    continueStraight = 0f != expctxGlobal.getVariableValue("continueStraight", 0f)

    // process tags not used in the profile (to have them in the data-tab)
    processUnusedTags = 0f != expctxGlobal.getVariableValue("processUnusedTags", 0f)

    forceSecondaryData = 0f != expctxGlobal.getVariableValue("forceSecondaryData", 0f)
    pass1coefficient = expctxGlobal.getVariableValue("pass1coefficient", 1.5f).toDouble()
    pass2coefficient = expctxGlobal.getVariableValue("pass2coefficient", 0f).toDouble()
    elevationpenaltybuffer = (expctxGlobal.getVariableValue("elevationpenaltybuffer", 5f) * 1000000).toInt()
    elevationmaxbuffer = (expctxGlobal.getVariableValue("elevationmaxbuffer", 10f) * 1000000).toInt()
    elevationbufferreduce = (expctxGlobal.getVariableValue("elevationbufferreduce", 0f) * 10000).toInt()

    cost1speed = expctxGlobal.getVariableValue("cost1speed", 22f).toDouble()
    additionalcostfactor = expctxGlobal.getVariableValue("additionalcostfactor", 1.5f).toDouble()
    changetime = expctxGlobal.getVariableValue("changetime", 180f).toDouble()
    buffertime = expctxGlobal.getVariableValue("buffertime", 120f).toDouble()
    waittimeadjustment = expctxGlobal.getVariableValue("waittimeadjustment", 0.9f).toDouble()
    inittimeadjustment = expctxGlobal.getVariableValue("inittimeadjustment", 0.2f).toDouble()
    starttimeoffset = expctxGlobal.getVariableValue("starttimeoffset", 0f).toDouble()
    transitonly = expctxGlobal.getVariableValue("transitonly", 0f) != 0f

    showspeed = 0f != expctxGlobal.getVariableValue("showspeed", 0f)
    showSpeedProfile = 0f != expctxGlobal.getVariableValue("showSpeedProfile", 0f)
    inverseRouting = 0f != expctxGlobal.getVariableValue("inverseRouting", 0f)
    showTime = 0f != expctxGlobal.getVariableValue("showtime", 0f)

    val tiMode = expctxGlobal.getVariableValue("turnInstructionMode", 0f).toInt()
    if (tiMode != 1) { // automatic selection from coordinate source
      turnInstructionMode = tiMode
    }
    turnInstructionCatchingRange = expctxGlobal.getVariableValue("turnInstructionCatchingRange", 40f).toDouble()
    turnInstructionRoundabouts = expctxGlobal.getVariableValue("turnInstructionRoundabouts", if (footMode) 0f else 1f) != 0f

    // Speed computation model (for bikes)
    // Total mass (biker + bike + luggages or hiker), in kg
    totalMass = expctxGlobal.getVariableValue("totalMass", 90f).toDouble()
    // Max speed (before braking), in km/h in profile and m/s in code
    if (footMode) {
      maxSpeed = expctxGlobal.getVariableValue("maxSpeed", 6f).toDouble() / 3.6
    } else {
      maxSpeed = expctxGlobal.getVariableValue("maxSpeed", 45f).toDouble() / 3.6
    }
    // Equivalent surface for wind, S * C_x, F = -1/2 * S * C_x * v^2 = - S_C_x * v^2
    S_C_x = expctxGlobal.getVariableValue("S_C_x", 0.5f * 0.45f).toDouble()
    // Default resistance of the road, F = - m * g * C_r (for good quality road)
    defaultC_r = expctxGlobal.getVariableValue("C_r", 0.01f).toDouble()
    // Constant power of the biker (in W)
    bikerPower = expctxGlobal.getVariableValue("bikerPower", 100f).toDouble()

    useDynamicDistance = expctxGlobal.getVariableValue("use_dynamic_range", 1f) == 1f
    buildBeelineOnRange = expctxGlobal.getVariableValue("add_beeline", 0f) == 1f

    val test = expctxGlobal.getVariableValue("check_start_way", 1f) == 1f
    if (!test) freeNoWays()

  }

  fun freeNoWays() {
    val expctxGlobal: BExpressionContext? = expctxWay
    if (expctxGlobal != null) expctxGlobal.freeNoWays()
  }

  var poipoints: MutableList<OsmNodeNamed>? = null

  var nogopoints: MutableList<OsmNodeNamed>? = null
  private var nogopoints_all: MutableList<OsmNodeNamed>? = null // full list not filtered for wayoints-in-nogos
  private var keepnogopoints: MutableList<OsmNodeNamed>? = null
  private var pendingEndpoint: OsmNodeNamed? = null

  var startDirection: Int? = null
  var startDirectionValid = false
  var forceUseStartDirection = false
  var roundTripStartDirection: Int? = null
  var roundTripDistance: Int? = null
  var roundTripDirectionAdd: Int? = null
  var roundTripPoints: Int? = null
  var allowSamewayback = false

  var anglemeter = CheapAngleMeter()

  var nogoCost = 0.0
  var isEndpoint = false

  var shortestmatch = false
  var wayfraction = 0.0
  var ilatshortest = 0
  var ilonshortest = 0

  var inverseDirection = false

  var showspeed = false
  var showSpeedProfile = false
  var inverseRouting = false
  var showTime = false
  var hasDirectRouting = false

  var outputFormat = "gpx"
  var exportWaypoints = false
  var exportCorrectedWaypoints = false

  var firstPrePath: OsmPrePath? = null

  var turnInstructionMode = 0 // 0=none, 1=auto, 2=locus, 3=osmand, 4=comment-style, 5=gpsies-style
  var turnInstructionCatchingRange = 0.0
  var turnInstructionRoundabouts = false

  // Speed computation model (for bikes)
  var totalMass = 0.0
  var maxSpeed = 0.0
  var S_C_x = 0.0
  var defaultC_r = 0.0
  var bikerPower = 0.0

  // variables in the profile to activate "crossing costs" at nodes with "estimated_crossing_class" not null
  var consider_crossing = false // consider crossing

  var crossing_Prio_H = 0 // min value to considered a HW as "highprio"
  var crossing_Prio_L = 0 // max value to considered a HW as "lowprio"

  // cost when turning left from a Highprio to a lowprio
  var cost_ToLeft_from_H_class1 = 0
  var cost_ToLeft_from_H_class2 = 0
  var cost_ToLeft_from_H_class3 = 0
  var cost_ToLeft_from_H_class4 = 0
  var cost_ToLeft_from_H_class5 = 0
  var cost_ToLeft_from_H_class6 = 0

  // cost when turning Right from a Highprio to a lowprio
  var cost_ToRight_from_H_class1 = 0
  var cost_ToRight_from_H_class2 = 0
  var cost_ToRight_from_H_class3 = 0
  var cost_ToRight_from_H_class4 = 0
  var cost_ToRight_from_H_class5 = 0
  var cost_ToRight_from_H_class6 = 0

  companion object {
    fun prepareNogoPoints(nogos: List<OsmNodeNamed>) {
      for (nogo in nogos) {
        if (nogo is OsmNogoPolygon) {
          continue
        }
        var s = nogo.name!!
        val idx = s.indexOf(' ')
        if (idx > 0) s = s.substring(0, idx)
        var ir = 20 // default radius
        if (s.length > 4) {
          try {
            ir = s.substring(4).toInt()
          } catch (e: Exception) { /* expected: ignore, keep the default radius */ }
        }
        // Radius of the nogo point in meters
        nogo.radius = ir.toDouble()
      }
    }
  }

  /**
   * restore the full nogolist previously saved by cleanNogoList
   */
  fun restoreNogoList() {
    nogopoints = nogopoints_all
  }

  /**
   * clean the nogolist (previoulsy saved by saveFullNogolist())
   * by removing nogos with waypoints within
   *
   * @return true if all wayoints are all in the same (full-weigth) nogo area (triggering bee-line-mode)
   */
  fun cleanNogoList(waypoints: List<OsmNode>) {
    nogopoints_all = nogopoints
    if (nogopoints == null) return
    val nogos: MutableList<OsmNodeNamed> = ArrayList()
    for (nogo in nogopoints!!) {
      var goodGuy = true
      for (wp in waypoints) {
        if (wp.calcDistance(nogo) < nogo.radius
          && (nogo !is OsmNogoPolygon
            || (if (nogo.isClosed)
            nogo.isWithin(wp.ilon, wp.ilat)
          else
            nogo.isOnPolyline(wp.ilon, wp.ilat)))
        ) {
          goodGuy = false
        }
      }
      if (goodGuy) nogos.add(nogo)
    }
    nogopoints = if (nogos.isEmpty()) null else nogos
  }

  fun checkMatchedWaypointAgainstNogos(matchedWaypoints: MutableList<MatchedWaypoint>) {
    if (nogopoints == null) return
    val theSize = matchedWaypoints.size
    if (theSize < 2) return
    var removed = 0
    val newMatchedWaypoints: MutableList<MatchedWaypoint> = ArrayList()
    var prevMwp: MatchedWaypoint? = null
    var prevMwpIsInside = false
    for (i in 0 until theSize) {
      val mwp = matchedWaypoints.get(i)
      var isInsideNogo = false
      val wp: OsmNode = mwp.crosspoint!!
      for (nogo in nogopoints!!) {
        if (nogo.nogoWeight.isNaN()
          && wp.calcDistance(nogo) < nogo.radius
          && (nogo !is OsmNogoPolygon
            || (if (nogo.isClosed)
            nogo.isWithin(wp.ilon, wp.ilat)
          else
            nogo.isOnPolyline(wp.ilon, wp.ilat)))
        ) {
          isInsideNogo = true
          break
        }
      }
      if (isInsideNogo) {
        var useAnyway = false
        if (prevMwp == null) useAnyway = true
        else if (mwp.wpttype == MatchedWaypoint.WAYPOINT_TYPE_DIRECT) useAnyway = true
        else if (prevMwp.wpttype == MatchedWaypoint.WAYPOINT_TYPE_DIRECT) useAnyway = true
        else if (prevMwpIsInside) useAnyway = true
        else if (i == theSize - 1) {
          throw IllegalArgumentException("last wpt in restricted area ")
        }
        if (useAnyway) {
          prevMwpIsInside = true
          newMatchedWaypoints.add(mwp)
        } else {
          removed++
          prevMwpIsInside = false
        }

      } else {
        prevMwpIsInside = false
        newMatchedWaypoints.add(mwp)
      }
      prevMwp = mwp
    }
    if (newMatchedWaypoints.size < 2) {
      throw IllegalArgumentException("a wpt in restricted area ")
    }
    if (removed > 0) {
      matchedWaypoints.clear()
      matchedWaypoints.addAll(newMatchedWaypoints)
    }
  }

  fun allInOneNogo(waypoints: List<OsmNode>): Boolean {
    if (nogopoints == null) return false
    var allInTotal = false
    for (nogo in nogopoints!!) {
      var allIn = nogo.nogoWeight.isNaN()
      for (wp in waypoints) {
        val dist = wp.calcDistance(nogo)
        if (dist < nogo.radius
          && (nogo !is OsmNogoPolygon
            || (if (nogo.isClosed)
            nogo.isWithin(wp.ilon, wp.ilat)
          else
            nogo.isOnPolyline(wp.ilon, wp.ilat)))
        ) {
          continue
        }
        allIn = false
      }
      allInTotal = allInTotal or allIn
    }
    return allInTotal
  }

  fun getNogoChecksums(): LongArray {
    val cs = LongArray(3)
    val n = if (nogopoints == null) 0 else nogopoints!!.size
    for (i in 0 until n) {
      val nogo = nogopoints!!.get(i)
      cs[0] += nogo.ilon.toLong()
      cs[1] += nogo.ilat.toLong()
      // 10 is an arbitrary constant to get sub-integer precision in the checksum
      cs[2] += (nogo.radius * 10.0).toLong()
    }
    return cs
  }

  fun setWaypoint(wp: OsmNodeNamed, endpoint: Boolean) {
    setWaypoint(wp, null, endpoint)
  }

  fun setWaypoint(wp: OsmNodeNamed, pendingEndpoint: OsmNodeNamed?, endpoint: Boolean) {
    keepnogopoints = nogopoints
    nogopoints = ArrayList()
    nogopoints!!.add(wp)
    if (keepnogopoints != null) nogopoints!!.addAll(keepnogopoints!!)
    isEndpoint = endpoint
    this.pendingEndpoint = pendingEndpoint
  }

  fun checkPendingEndpoint(): Boolean {
    if (pendingEndpoint != null) {
      isEndpoint = true
      nogopoints!!.set(0, pendingEndpoint!!)
      pendingEndpoint = null
      return true
    }
    return false
  }

  fun unsetWaypoint() {
    nogopoints = keepnogopoints
    pendingEndpoint = null
    isEndpoint = false
  }

  fun calcDistance(lon1a: Int, lat1a: Int, lon2a: Int, lat2a: Int): Int {
    var lon1 = lon1a
    var lat1 = lat1a
    var lon2 = lon2a
    var lat2 = lat2a
    val lonlat2m = CheapRuler.getLonLatToMeterScales((lat1 + lat2) shr 1)
    val dlon2m = lonlat2m[0]
    val dlat2m = lonlat2m[1]
    var dx = (lon2 - lon1) * dlon2m
    var dy = (lat2 - lat1) * dlat2m
    var d = sqrt(dy * dy + dx * dx)

    shortestmatch = false

    if (nogopoints != null && !nogopoints!!.isEmpty() && d > 0.0) {
      for (ngidx in 0 until nogopoints!!.size) {
        val nogo = nogopoints!!.get(ngidx)
        val x1 = (lon1 - nogo.ilon) * dlon2m
        val y1 = (lat1 - nogo.ilat) * dlat2m
        val x2 = (lon2 - nogo.ilon) * dlon2m
        val y2 = (lat2 - nogo.ilat) * dlat2m
        val r12 = x1 * x1 + y1 * y1
        val r22 = x2 * x2 + y2 * y2
        var radius = abs(if (r12 < r22) y1 * dx - x1 * dy else y2 * dx - x2 * dy) / d

        if (radius < nogo.radius) { // 20m
          var s1 = x1 * dx + y1 * dy
          var s2 = x2 * dx + y2 * dy


          if (s1 < 0.0) {
            s1 = -s1
            s2 = -s2
          }
          if (s2 > 0.0) {
            radius = sqrt(if (s1 < s2) r12 else r22)
            if (radius > nogo.radius) continue
          }
          if (nogo.isNogo) {
            if (nogo !is OsmNogoPolygon) {  // nogo is a circle
              if (nogo.nogoWeight.isNaN()) {
                // default nogo behaviour (ignore completely)
                nogoCost = -1.0
              } else {
                // nogo weight, compute distance within the circle
                nogoCost = nogo.distanceWithinRadius(lon1, lat1, lon2, lat2, d) * nogo.nogoWeight
              }
            } else if (nogo.intersects(lon1, lat1, lon2, lat2)) {
              // nogo is a polyline/polygon, we have to check there is indeed
              // an intersection in this case (radius check is not enough).
              if (nogo.nogoWeight.isNaN()) {
                // default nogo behaviour (ignore completely)
                nogoCost = -1.0
              } else {
                if (nogo.isClosed) {
                  // compute distance within the polygon
                  nogoCost = nogo.distanceWithinPolygon(lon1, lat1, lon2, lat2) * nogo.nogoWeight
                } else {
                  // for a polyline, just add a constant penalty
                  nogoCost = nogo.nogoWeight
                }
              }
            }
          } else {
            shortestmatch = true
            nogo.radius = radius // shortest distance to way
            // calculate remaining distance
            if (s2 < 0.0) {
              wayfraction = -s2 / (d * d)
              val xm = x2 - wayfraction * dx
              val ym = y2 - wayfraction * dy
              ilonshortest = (xm / dlon2m + nogo.ilon).toInt()
              ilatshortest = (ym / dlat2m + nogo.ilat).toInt()
            } else if (s1 > s2) {
              wayfraction = 0.0
              ilonshortest = lon2
              ilatshortest = lat2
            } else {
              wayfraction = 1.0
              ilonshortest = lon1
              ilatshortest = lat1
            }

            // here it gets nasty: there can be nogo-points in the list
            // *after* the shortest distance point. In case of a shortest-match
            // we use the reduced way segment for nogo-matching, in order not
            // to cut our escape-way if we placed a nogo just in front of where we are
            if (isEndpoint) {
              wayfraction = 1.0 - wayfraction
              lon2 = ilonshortest
              lat2 = ilatshortest
            } else {
              nogoCost = 0.0
              lon1 = ilonshortest
              lat1 = ilatshortest
            }
            dx = (lon2 - lon1) * dlon2m
            dy = (lat2 - lat1) * dlat2m
            d = sqrt(dy * dy + dx * dx)
          }
        }
      }
    }
    return maxOf(1.0, floor(d + 0.5).toLong().toDouble()).toInt() // Math.round(double) -> long, widened to double for Math.max
  }

  var pm: OsmPathModel? = null

  fun createPrePath(origin: OsmPath, link: OsmLink): OsmPrePath? {
    val p = pm!!.createPrePath()
    if (p != null) {
      p.init(origin, link, this)
    }
    return p
  }

  fun createPath(link: OsmLink): OsmPath {
    val p = pm!!.createPath()
    p.init(link)
    return p
  }

  fun createPath(origin: OsmPath, link: OsmLink, refTrack: OsmTrack?, detailMode: Boolean): OsmPath {
    val p = pm!!.createPath()
    p.init(origin, link, refTrack, detailMode, this)
    return p
  }

}
