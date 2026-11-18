package btools.router

import btools.mapaccess.MatchedWaypoint
import btools.mapaccess.NodesCache
import btools.mapaccess.OsmLink
import btools.mapaccess.OsmLinkHolder
import btools.mapaccess.OsmNode
import btools.mapaccess.OsmNodePairSet
import btools.mapaccess.OsmPos
import btools.platform.File
import btools.platform.currentTimeMillis
import btools.util.CheapAngleMeter
import btools.util.CompactLongMap
import btools.util.SortedHeap
import kotlin.math.abs

// PORT-CUT extends Thread: plain class, doRun runs on the caller; synchronized(openSet) blocks are plain blocks
class RoutingEngine(
  outfileBase: String?, logfileBase: String?, segmentDir: File,
  waypoints: MutableList<OsmNodeNamed>, rc: RoutingContext, engineMode: Int = 0
) {

  companion object {
    const val BROUTER_ENGINEMODE_ROUTING = 0
    const val BROUTER_ENGINEMODE_SEED = 1
    const val BROUTER_ENGINEMODE_GETELEV = 2
    const val BROUTER_ENGINEMODE_GETINFO = 3
    const val BROUTER_ENGINEMODE_ROUNDTRIP = 4
  }

  private var nodesCache: NodesCache? = null
  private var openSet = SortedHeap<OsmPath>()
  private var finished = false

  var waypoints: MutableList<OsmNodeNamed>
  var extraWaypoints: MutableList<OsmNodeNamed>? = null
  var matchedWaypoints: MutableList<MatchedWaypoint>? = null
  private var linksProcessed = 0

  private var nodeLimit = 0 // used for target island search
  private var MAXNODES_ISLAND_CHECK = 500
  var islandNodePairs = OsmNodePairSet(MAXNODES_ISLAND_CHECK) // PORT: non-private, the app reads it
  private var useNodePoints = false // use the start/end nodes  instead of crosspoint

  private var engineMode = 0

  private var MAX_STEPS_CHECK = 500

  private var ROUNDTRIP_DEFAULT_DIRECTIONADD = 45

  private var MAX_DYNAMIC_RANGE = 60000

  var foundTrack = OsmTrack()
  private var foundRawTrack: OsmTrack? = null
  private var alternativeIndex = 0

  var outputMessage: String? = null
  var errorMessage: String? = null

  private var terminated = false // PORT-NOTE was volatile, no threads now

  var segmentDir: File
  private var outfileBase: String?
  private var logfileBase: String?
  private var infoLogEnabled = false
  // PORT-CUT infoLogWriter (debug.txt) and stackSampler
  var routingContext: RoutingContext

  var airDistanceCostFactor = 0.0
  var lastAirDistanceCostFactor = 0.0

  private var guideTrack: OsmTrack? = null

  private var matchPath: OsmPathElement? = null

  private var startTime: Long = 0
  private var maxRunningTime: Long = 0
  var boundary: SearchBoundary? = null

  var quite = false

  // PORT-CUT extract (only used by getOpenSet)

  private var directWeaving = true // !Boolean.getBoolean("disableDirectWeaving")
  private var outfile: String? = null

  init {
    this.segmentDir = segmentDir
    this.outfileBase = outfileBase
    this.logfileBase = logfileBase
    this.waypoints = waypoints
    this.infoLogEnabled = outfileBase != null
    this.routingContext = rc
    this.engineMode = engineMode

    // PORT-CUT debug.txt log writer and stacks.txt stack sampler (baseFolder handling)
    val cachedProfile = ProfileCache.parseProfile(rc)
    if (hasInfo()) {
      logInfo("parsed profile " + rc.localFunction + " cached=" + cachedProfile)
    }

  }

  private fun hasInfo(): Boolean {
    return infoLogEnabled
  }

  private fun logInfo(s: String) {
    if (infoLogEnabled) {
      println(s)
    }
    // PORT-CUT infoLogWriter
  }

  private fun logThrowable(t: Throwable) {
    logInfo(t.stackTraceToString())
  }

  fun run() {
    doRun(0)
  }

  fun doRun(maxRunningTime: Long) {

    when (engineMode) {
      BROUTER_ENGINEMODE_ROUTING -> {
        // PORT-CUT single waypoint of type WAYPOINT_TYPE_ROUNDTRIP switching to BROUTER_ENGINEMODE_ROUNDTRIP
        if (waypoints.size < 2) {
          throw IllegalArgumentException("we need two lat/lon points at least!")
        }
        doRouting(maxRunningTime)
      }
      // PORT-CUT engine modes SEED, GETELEV, GETINFO, ROUNDTRIP
      else -> throw IllegalArgumentException("not a valid engine mode")
    }
  }


  fun doRouting(maxRunningTime: Long) {
    try {
      startTime = currentTimeMillis()
      val startTime0 = startTime
      this.maxRunningTime = maxRunningTime

      if (routingContext.allowSamewayback) {
        if (waypoints.size == 2) {
          val onn = OsmNodeNamed(OsmNode(waypoints.get(0).ilon, waypoints.get(0).ilat))
          onn.name = "to"
          waypoints.add(onn)
        } else {
          waypoints.get(waypoints.size - 1).name = "via" + (waypoints.size - 1) + "_center"
          val newpoints: MutableList<OsmNodeNamed> = ArrayList()
          var i = waypoints.size - 2
          while (i >= 0) {
            // System.out.println("back " + waypoints.get(i));
            val onn = OsmNodeNamed(OsmNode(waypoints.get(i).ilon, waypoints.get(i).ilat))
            onn.name = "via"
            newpoints.add(onn)
            i--
          }
          newpoints.get(newpoints.size - 1).name = "to"
          waypoints.addAll(newpoints)
        }
      }

      // check for round trip
      val tmppoints: MutableList<OsmNodeNamed> = ArrayList()
      for (onn in waypoints) {
        if (onn.wpttype == MatchedWaypoint.WAYPOINT_TYPE_ROUNDTRIP) {
          // PORT-CUT round trip (getRandomDirectionFromData, buildPointsFromCircle)
          throw IllegalArgumentException("round trip is not supported")
        } else {
          tmppoints.add(onn)
        }
      }

      waypoints.clear()
      waypoints.addAll(tmppoints)

      val nsections = waypoints.size - 1
      val refTracks = arrayOfNulls<OsmTrack>(nsections) // used ways for alternatives
      val lastTracks = arrayOfNulls<OsmTrack>(nsections)
      var track: OsmTrack? = null
      val messageList: MutableList<String> = ArrayList()
      var i = 0
      while (true) {
        track = findTrack(refTracks, lastTracks)

        // we are only looking for info
        if (routingContext.ai != null) return

        track!!.message = "track-length = " + track.distance + " filtered ascend = " + track.ascend +
          " plain-ascend = " + track.plainAscend + " cost=" + track.cost
        if (track.energy != 0) {
          // PORT-CUT Formatter.getFormattedEnergy / getFormattedTime2 (message text only)
          track.message += " energy=" + track.energy + " time=" + track.getTotalSeconds()
        }
        track.name = "brouter_" + routingContext.getProfileName() + "_" + i

        messageList.add(track.message!!)
        track.messageList = messageList
        if (outfileBase != null) {
          val filename = outfileBase + i + "." + routingContext.outputFormat
          // PORT-CUT reading the old track from a GPX file for comparison (oldTrack is always null)
          track.exportWaypoints = routingContext.exportWaypoints
          track.exportCorrectedWaypoints = routingContext.exportCorrectedWaypoints
          // PORT-CUT Format* output (gpx/json/kml) and writing the output file

          foundTrack = track
          alternativeIndex = i
          outfile = filename
        } else {
          if (i == routingContext.getAlternativeIdx(0, 3)) {
            // PORT-CUT CSV report and printing the gpx (Format*)
            foundTrack = track
          } else {
            i++
            continue
          }
        }
        if (logfileBase != null) {
          // PORT-CUT FormatCsv log file
        }
        break
      }
      val endTime = currentTimeMillis()
      logInfo("execution time = " + (endTime - startTime0) / 1000.0 + " seconds")
    } catch (e: IllegalArgumentException) {
      logException(e)
    } catch (e: Exception) {
      logException(e)
      logThrowable(e)
    } catch (e: Error) { // PORT-NOTE memory: Java catches Error (OutOfMemoryError); on native this is rarely reached
      cleanOnOOM()
      logException(e)
      logThrowable(e)
    } finally {
      if (hasInfo() && routingContext.expctxWay != null) {
        logInfo("expression cache stats=" + routingContext.expctxWay!!.cacheStats())
      }

      ProfileCache.releaseProfile(routingContext)

      if (nodesCache != null) {
        if (hasInfo() && nodesCache != null) {
          logInfo("NodesCache status before close=" + nodesCache!!.formatStatus())
        }
        nodesCache!!.close()
        nodesCache = null
      }
      openSet.clear()
      finished = true // this signals termination to outside

      // PORT-CUT closing infoLogWriter and stackSampler
    }
  }

  // PORT-CUT doGetInfo, doRoundTrip, buildPointsFromCircle, getRandomDirectionFromData (engine modes GETINFO/GETELEV/ROUNDTRIP, AreaReader)

  private fun postElevationCheck(track: OsmTrack) {
    var lastPt: OsmPathElement? = null
    var startPt: OsmPathElement? = null
    var lastElev: Short = Short.MIN_VALUE
    var startElev: Short = Short.MIN_VALUE
    var endElev: Short = Short.MIN_VALUE
    var startIdx = 0
    var endIdx = -1
    var dist = 0
    val ourSize = track.nodes.size
    for (idx in 0 until ourSize) {
      val n = track.nodes.get(idx)
      if (n.getSElev() == Short.MIN_VALUE && lastElev != Short.MIN_VALUE && idx < ourSize - 1) {
        // start one point before entry point to get better elevation results
        if (idx > 1)
          startElev = track.nodes.get(idx - 2).getSElev()
        if (startElev == Short.MIN_VALUE)
          startElev = lastElev
        startIdx = idx
        startPt = lastPt
        dist = 0
        if (lastPt != null)
          dist += n.calcDistance(lastPt)
      } else if (n.getSElev() != Short.MIN_VALUE && lastElev == Short.MIN_VALUE && startElev != Short.MIN_VALUE) {
        // end one point behind exit point to get better elevation results
        if (idx + 1 < track.nodes.size)
          endElev = track.nodes.get(idx + 1).getSElev()
        if (endElev == Short.MIN_VALUE)
          endElev = n.getSElev()
        endIdx = idx
        var tmpPt = track.nodes.get(if (startIdx > 1) startIdx - 2 else startIdx - 1)
        val diffElev = endElev - startElev
        dist += tmpPt.calcDistance(startPt!!)
        dist += n.calcDistance(lastPt!!)
        var distRest = dist
        var incline = diffElev / (dist / 100.0)
        var lastMsg = ""
        var tmpincline = 0.0
        var startincline = 0.0
        var selev: Double = track.nodes.get(if (startIdx > 1) startIdx - 2 else startIdx - 1).getSElev().toDouble()
        var hasInclineTags = false
        for (i in startIdx - 1 until endIdx + 1) {
          val tmp = track.nodes.get(i)
          if (tmp.message != null) {
            val md = tmp.message!!.copy()
            val msg = md.wayKeyValues!!
            if (msg != lastMsg) {
              val revers = msg.contains("reversedirection=yes")
              var pos = msg.indexOf("incline=")
              if (pos != -1) {
                hasInclineTags = true
                var s = msg.substring(pos + 8)
                pos = s.indexOf(" ")
                if (pos != -1)
                  s = s.substring(0, pos)

                if (s.length > 0) {
                  try {
                    var ind = s.indexOf("%")
                    if (ind != -1)
                      s = s.substring(0, ind)
                    ind = s.indexOf("°")
                    if (ind != -1)
                      s = s.substring(0, ind)
                    tmpincline = s.trim().toDouble()
                    if (revers)
                      tmpincline *= -1.0
                  } catch (e: NumberFormatException) {
                    tmpincline = 0.0
                  }
                }
              } else {
                tmpincline = 0.0
              }
              if (startincline == 0.0) {
                startincline = tmpincline
              } else if (startincline < 0 && tmpincline > 0) {
                // for the way up find the exit point
                val diff = endElev - selev
                tmpincline = diff / (distRest / 100.0)
              }
            }
            lastMsg = msg
          }
          val tmpdist = tmp.calcDistance(tmpPt)
          distRest -= tmpdist
          if (hasInclineTags)
            incline = tmpincline
          selev = (selev + (tmpdist / 100.0 * incline))
          tmp.setSElev(selev.toInt().toShort())
          tmp.message!!.ele = selev.toInt().toShort()
          tmpPt = tmp
        }
        dist = 0
      } else if (n.getSElev() != Short.MIN_VALUE && lastElev == Short.MIN_VALUE && startIdx == 0) {
        // fill at start
        for (i in 0 until idx) {
          track.nodes.get(i).setSElev(n.getSElev())
        }
      } else if (n.getSElev() == Short.MIN_VALUE && idx == track.nodes.size - 1) {
        // fill at end
        startIdx = idx
        for (i in startIdx until track.nodes.size) {
          track.nodes.get(i).setSElev(lastElev)
        }
      } else if (n.getSElev() == Short.MIN_VALUE) {
        if (lastPt != null)
          dist += n.calcDistance(lastPt)
      }
      lastElev = n.getSElev()
      lastPt = n
    }

  }

  private fun logException(t: Throwable) {
    errorMessage = if (t is RuntimeException) t.message else t.toString()
    logInfo("Error (linksProcessed=" + linksProcessed + " open paths: " + openSet.getSize() + "): " + errorMessage)
  }


  // PORT-CUT doSearch (seeded search mode)

  fun cleanOnOOM() {
    terminate()
  }

  private fun findTrack(refTracks: Array<OsmTrack?>, lastTracks: Array<OsmTrack?>): OsmTrack? {
    while (true) {
      try {
        return tryFindTrack(refTracks, lastTracks)
      } catch (rie: RoutingIslandException) {
        if (routingContext.useDynamicDistance) {
          for (mwp in matchedWaypoints!!) {
            if (mwp.name!!.contains("_add")) {
              val n1 = mwp.node1!!.getIdFromPos()
              val n2 = mwp.node2!!.getIdFromPos()
              islandNodePairs.addTempPair(n1, n2)
            }
          }
        }
        islandNodePairs.freezeTempPairs()
        nodesCache!!.clean(true)
        matchedWaypoints = null
      }
    }
  }

  private fun tryFindTrack(refTracks0: Array<OsmTrack?>, lastTracks0: Array<OsmTrack?>): OsmTrack? {
    var refTracks = refTracks0
    var lastTracks = lastTracks0
    val totaltrack = OsmTrack()
    var nUnmatched = waypoints.size
    var hasDirectRouting = false

    if (useNodePoints && extraWaypoints != null) {
      // add extra waypoints from the last broken round
      for (wp in extraWaypoints!!) {
        if (wp.wpttype == MatchedWaypoint.WAYPOINT_TYPE_DIRECT) hasDirectRouting = true
        if (wp.name!!.startsWith("from")) {
          waypoints.add(1, wp)
          waypoints.get(0).wpttype = MatchedWaypoint.WAYPOINT_TYPE_DIRECT
          nUnmatched++
        } else {
          waypoints.add(waypoints.size - 1, wp)
          waypoints.get(waypoints.size - 2).wpttype = MatchedWaypoint.WAYPOINT_TYPE_DIRECT
          nUnmatched++
        }
      }
      extraWaypoints = null
    }
    if (lastTracks.size < waypoints.size - 1) {
      refTracks = arrayOfNulls<OsmTrack>(waypoints.size - 1) // used ways for alternatives
      lastTracks = arrayOfNulls<OsmTrack>(waypoints.size - 1)
      hasDirectRouting = true
    }
    for (wp in waypoints) {
      var type = ""
      when (wp.wpttype) {
        MatchedWaypoint.WAYPOINT_TYPE_DIRECT -> type = " beeline"
        MatchedWaypoint.WAYPOINT_TYPE_MEETING -> type = " via"
        MatchedWaypoint.WAYPOINT_TYPE_ROUNDTRIP -> type = " roundtrip"
        else -> {}
      }
      if (hasInfo()) logInfo("wp=" + wp + type)
      if (wp.wpttype == MatchedWaypoint.WAYPOINT_TYPE_DIRECT) hasDirectRouting = true
    }

    // check for a track for that target
    var nearbyTrack: OsmTrack? = null
    if (!hasDirectRouting && lastTracks[waypoints.size - 2] == null) {
      // PORT-CUT OsmTrack.readBinary (rawTrackPath is always null -> nearbyTrack stays null)
      nearbyTrack = readNearbyTrackCut()
      if (nearbyTrack != null) {
        nUnmatched--
      }
      if (hasInfo()) {
        val found = nearbyTrack != null
        val dirty = found && nearbyTrack!!.isDirty
        logInfo("read referenceTrack, found=" + found + " dirty=" + dirty + " ")
      }
    }

    if (matchedWaypoints == null) { // could exist from the previous alternative level
      matchedWaypoints = ArrayList()
      for (i in 0 until nUnmatched) {
        val mwp = MatchedWaypoint()
        mwp.waypoint = waypoints.get(i)
        mwp.name = waypoints.get(i).name
        mwp.wpttype = waypoints.get(i).wpttype
        matchedWaypoints!!.add(mwp)
      }
      val startSize = matchedWaypoints!!.size
      matchWaypointsToNodes(matchedWaypoints!!)
      if (startSize < matchedWaypoints!!.size) {
        refTracks = arrayOfNulls<OsmTrack>(matchedWaypoints!!.size - 1) // used ways for alternatives
        lastTracks = arrayOfNulls<OsmTrack>(matchedWaypoints!!.size - 1)
        hasDirectRouting = true
      }

      for (mwp in matchedWaypoints!!) {
        if (hasInfo() && matchedWaypoints!!.size != nUnmatched) {
          var type = ""
          when (mwp.wpttype) {
            MatchedWaypoint.WAYPOINT_TYPE_DIRECT -> type = " beeline"
            MatchedWaypoint.WAYPOINT_TYPE_MEETING -> type = " via"
            MatchedWaypoint.WAYPOINT_TYPE_ROUNDTRIP -> type = " roundtrip"
            else -> {}
          }
          logInfo("new wp=" + mwp.waypoint + " " + mwp.crosspoint + type)
        }
      }

      routingContext.checkMatchedWaypointAgainstNogos(matchedWaypoints!!)

      // detect target islands: restricted search in inverse direction
      routingContext.inverseDirection = !routingContext.inverseRouting
      airDistanceCostFactor = 0.0
      for (i in 0 until matchedWaypoints!!.size - 1) {
        nodeLimit = MAXNODES_ISLAND_CHECK
        if (matchedWaypoints!!.get(i).wpttype == MatchedWaypoint.WAYPOINT_TYPE_DIRECT) continue
        if (routingContext.inverseRouting) {
          val seg = findTrack("start-island-check", matchedWaypoints!!.get(i), matchedWaypoints!!.get(i + 1), null, null, false)
          if (seg == null && nodeLimit > 0) {
            throw IllegalArgumentException("start island detected for section " + i)
          }
        } else {
          val seg = findTrack("target-island-check", matchedWaypoints!!.get(i + 1), matchedWaypoints!!.get(i), null, null, false)
          if (seg == null && nodeLimit > 0) {
            throw IllegalArgumentException("target island detected for section " + i)
          }
        }
      }
      routingContext.inverseDirection = false
      nodeLimit = 0

      if (nearbyTrack != null) {
        matchedWaypoints!!.add(nearbyTrack.endPoint!!)
      }
    } else {
      if (lastTracks.size < matchedWaypoints!!.size - 1) {
        refTracks = arrayOfNulls<OsmTrack>(matchedWaypoints!!.size - 1) // used ways for alternatives
        lastTracks = arrayOfNulls<OsmTrack>(matchedWaypoints!!.size - 1)
        hasDirectRouting = true
      }
    }
    for (mwp in matchedWaypoints!!) {
      //System.out.println(FormatGpx.getWaypoint(mwp.waypoint.ilon, mwp.waypoint.ilat, mwp.name, null));
      //System.out.println(FormatGpx.getWaypoint(mwp.crosspoint.ilon, mwp.crosspoint.ilat, mwp.name+"_cp", null));
    }

    routingContext.hasDirectRouting = hasDirectRouting

    OsmPath.seg = 1 // set segment counter
    for (i in 0 until matchedWaypoints!!.size - 1) {
      if (lastTracks[i] != null) {
        if (refTracks[i] == null) refTracks[i] = OsmTrack()
        refTracks[i]!!.addNodes(lastTracks[i]!!)
      }

      val seg: OsmTrack?
      val wptIndex: Int
      if (routingContext.inverseRouting) {
        routingContext.inverseDirection = true
        seg = searchTrack(matchedWaypoints!!.get(i + 1), matchedWaypoints!!.get(i), null, refTracks[i])
        routingContext.inverseDirection = false
        wptIndex = i + 1
      } else {
        seg = searchTrack(matchedWaypoints!!.get(i), matchedWaypoints!!.get(i + 1), if (i == matchedWaypoints!!.size - 2) nearbyTrack else null, refTracks[i])
        wptIndex = i
        if (routingContext.continueStraight) {
          if (i < matchedWaypoints!!.size - 2) {
            val lastPoint: OsmNode = if (seg!!.containsNode(matchedWaypoints!!.get(i + 1).node1!!)) matchedWaypoints!!.get(i + 1).node1!! else matchedWaypoints!!.get(i + 1).node2!!
            val nogo = OsmNodeNamed(lastPoint)
            nogo.radius = 5.0
            nogo.name = "nogo" + (i + 1)
            nogo.nogoWeight = 9999.0
            nogo.isNogo = true
            if (routingContext.nogopoints == null) routingContext.nogopoints = ArrayList()
            routingContext.nogopoints!!.add(nogo)
          }
        }
      }
      if (seg == null)
        return null

      if (routingContext.ai != null) return null

      var changed = false
      if (routingContext.correctMisplacedViaPoints &&
        matchedWaypoints!!.get(i).wpttype != MatchedWaypoint.WAYPOINT_TYPE_DIRECT &&
        matchedWaypoints!!.get(i).wpttype != MatchedWaypoint.WAYPOINT_TYPE_MEETING &&
        !routingContext.allowSamewayback) {
        changed = snapPathConnection(totaltrack, seg, if (routingContext.inverseRouting) matchedWaypoints!!.get(i + 1) else matchedWaypoints!!.get(i))
      }
      if (wptIndex > 0)
        matchedWaypoints!!.get(wptIndex).indexInTrack = totaltrack.nodes.size - 1

      totaltrack.appendTrack(seg)
      lastTracks[i] = seg
    }

    postElevationCheck(totaltrack)

    recalcTrack(totaltrack)

    matchedWaypoints!!.get(matchedWaypoints!!.size - 1).indexInTrack = totaltrack.nodes.size - 1
    totaltrack.matchedWaypoints = matchedWaypoints
    totaltrack.processVoiceHints(routingContext)
    totaltrack.prepareSpeedProfile(routingContext)

    totaltrack.showTime = routingContext.showTime
    totaltrack.params = routingContext.keyValues

    if (routingContext.poipoints != null)
      totaltrack.pois = routingContext.poipoints!!

    return totaltrack
  }

  // PORT-CUT OsmTrack.readBinary (raw track reuse through files)
  private fun readNearbyTrackCut(): OsmTrack? {
    return null
  }

  fun getExtraSegment(start: OsmPathElement?, end: OsmPathElement?): OsmTrack? {

    if (start == null || end == null) return null

    val wptlist: MutableList<MatchedWaypoint> = ArrayList()
    val wpt1 = MatchedWaypoint()
    wpt1.waypoint = OsmNode(start.getILon(), start.getILat())
    wpt1.name = "wptx1"
    wpt1.crosspoint = OsmNode(start.getILon(), start.getILat())
    wpt1.node1 = OsmNode(start.getILon(), start.getILat())
    wpt1.node2 = OsmNode(end.getILon(), end.getILat())
    wptlist.add(wpt1)
    val wpt2 = MatchedWaypoint()
    wpt2.waypoint = OsmNode(end.getILon(), end.getILat())
    wpt2.name = "wptx2"
    wpt2.crosspoint = OsmNode(end.getILon(), end.getILat())
    wpt2.node2 = OsmNode(start.getILon(), start.getILat())
    wpt2.node1 = OsmNode(end.getILon(), end.getILat())
    wptlist.add(wpt2)

    val mwp1 = wptlist.get(0)
    val mwp2 = wptlist.get(1)

    var mid: OsmTrack? = null

    val corr = routingContext.correctMisplacedViaPoints
    routingContext.correctMisplacedViaPoints = false

    guideTrack = OsmTrack()
    guideTrack!!.addNode(start)
    guideTrack!!.addNode(end)

    mid = findTrack("getinfo", mwp1, mwp2, null, null, false)

    guideTrack = null
    routingContext.correctMisplacedViaPoints = corr

    return mid
  }

  private fun snapRoundaboutConnection(tt: OsmTrack, t: OsmTrack, indexStart: Int, indexEnd0: Int, indexMeeting: Int, startWp: MatchedWaypoint): Int {
    var indexEnd = indexEnd0

    val indexMeetingBack = (if (indexMeeting == -1) tt.nodes.size - 1 else indexMeeting)
    var indexMeetingFore = 0
    var indexStartBack = indexStart
    var indexStartFore = 0

    val ptStart = tt.nodes.get(indexStartBack)
    val ptMeeting = tt.nodes.get(indexMeetingBack)
    val ptEnd = t.nodes.get(indexEnd)

    val bMeetingIsOnRoundabout = ptMeeting.message!!.isRoundabout()
    var bMeetsRoundaboutStart = false
    var wayDistance = 0

    var i: Int
    var last_n: OsmPathElement? = null

    i = 0
    while (i < indexEnd) {
      val n = t.nodes.get(i)
      if (last_n != null) wayDistance += n.calcDistance(last_n)
      last_n = n
      if (n.positionEquals(ptStart)) {
        indexStartFore = i
        bMeetsRoundaboutStart = true
      }
      if (n.positionEquals(ptMeeting)) {
        indexMeetingFore = i
      }
      i++
    }

    if (routingContext.correctMisplacedViaPointsDistance > 0 &&
      wayDistance > routingContext.correctMisplacedViaPointsDistance) {
      return 0
    }

    if (!bMeetsRoundaboutStart && bMeetingIsOnRoundabout) {
      indexEnd = indexMeetingFore
    }
    if (bMeetsRoundaboutStart && bMeetingIsOnRoundabout) {
      indexEnd = indexStartFore
    }

    val removeList: MutableList<OsmPathElement> = ArrayList()
    if (!bMeetsRoundaboutStart) {
      indexStartBack = indexMeetingBack
      while (!tt.nodes.get(indexStartBack).message!!.isRoundabout()) {
        indexStartBack--
        if (indexStartBack == 2) break
      }
    }

    i = indexStartBack + 1
    while (i < tt.nodes.size) {
      val n = tt.nodes.get(i)
      val detours = tt.getFromDetourMap(n.getIdFromPos())
      if (detours != null) {
        var h: OsmTrack.OsmPathElementHolder? = detours
        while (h != null) {
          h = h.nextHolder
        }
      }
      removeList.add(n)
      i++
    }

    var ttend: OsmPathElement? = null
    if (!bMeetingIsOnRoundabout && !bMeetsRoundaboutStart) {
      ttend = tt.nodes.get(indexStartBack)
      val ttend_detours = tt.getFromDetourMap(ttend.getIdFromPos())
      if (ttend_detours != null) {
        tt.registerDetourForId(ttend.getIdFromPos(), null)
      }
    }

    for (e in removeList) {
      tt.nodes.remove(e)
    }
    removeList.clear()


    i = 0
    while (i < indexEnd) {
      val n = t.nodes.get(i)
      if (n.positionEquals(if (bMeetsRoundaboutStart) ptStart else ptEnd)) break
      if (!bMeetingIsOnRoundabout && !bMeetsRoundaboutStart && n.message!!.isRoundabout()) break

      val detours = t.getFromDetourMap(n.getIdFromPos())
      if (detours != null) {
        var h: OsmTrack.OsmPathElementHolder? = detours
        while (h != null) {
          h = h.nextHolder
        }
      }
      removeList.add(n)
      i++
    }

    // time hold
    var atime = 0f
    var aenergy = 0f
    var acost = 0
    if (i > 1) {
      atime = t.nodes.get(i).getTime()
      aenergy = t.nodes.get(i).getEnergy()
      acost = t.nodes.get(i).cost
    }

    for (e in removeList) {
      t.nodes.remove(e)
    }
    removeList.clear()

    if (atime > 0f) {
      for (e in t.nodes) {
        e.setTime(e.getTime() - atime)
        e.setEnergy(e.getEnergy() - aenergy)
        e.cost = e.cost - acost
      }
    }

    if (!bMeetingIsOnRoundabout && !bMeetsRoundaboutStart) {

      val ttend_detours = tt.getFromDetourMap(ttend!!.getIdFromPos())

      var mid: OsmTrack? = null
      if (ttend_detours != null && ttend_detours.node != null) {
        mid = getExtraSegment(ttend, ttend_detours.node)
      }
      val tt_end = tt.nodes.get(tt.nodes.size - 1)

      val last_cost = tt_end.cost
      val last_time = tt_end.getTime()
      val last_energy = tt_end.getEnergy()
      var tmp_cost = 0
      var tmp_time = 0f
      var tmp_energy = 0f

      if (mid != null) {
        var start = false
        for (e in mid.nodes) {
          if (start) {
            if (e.positionEquals(ttend_detours!!.node!!)) {
              tmp_cost = e.cost
              tmp_time = e.getTime()
              tmp_energy = e.getEnergy()
              break
            }
            e.cost = last_cost + e.cost
            e.setTime(last_time + e.getTime())
            e.setEnergy(last_energy + e.getEnergy())
            tt.nodes.add(e)
          }
          if (e.positionEquals(tt_end)) start = true
        }

        ttend_detours!!.node!!.cost = last_cost + tmp_cost
        ttend_detours.node!!.setTime(last_time + tmp_time)
        ttend_detours.node!!.setEnergy(last_energy + tmp_energy)
        tt.nodes.add(ttend_detours.node!!)
        t.nodes.add(0, ttend_detours.node!!)
      }

    }

    tt.cost = tt.nodes.get(tt.nodes.size - 1).cost
    t.cost = t.nodes.get(t.nodes.size - 1).cost

    startWp.correctedpoint = OsmNode(ptStart.getILon(), ptStart.getILat())

    return (t.nodes.size)
  }

  // check for way back on way point
  private fun snapPathConnection(tt: OsmTrack, t: OsmTrack, startWp: MatchedWaypoint): Boolean {
    if (!startWp.name!!.startsWith("via") && !startWp.name!!.startsWith("rt"))
      return false

    val ourSize = tt.nodes.size
    if (ourSize > 0) {
      val testPoint = tt.nodes.get(ourSize - 1)
      if (routingContext.poipoints != null) {
        for (node in routingContext.poipoints!!) {

          val lon0 = tt.nodes.get(ourSize - 2).getILon()
          val lat0 = tt.nodes.get(ourSize - 2).getILat()
          val lon1 = startWp.crosspoint!!.ilon
          val lat1 = startWp.crosspoint!!.ilat
          val lon2 = node.ilon
          val lat2 = node.ilat
          val angle3 = routingContext.anglemeter.calcAngle(lon0, lat0, lon1, lat1, lon2, lat2)
          val dist = node.calcDistance(startWp.crosspoint!!)
          if (dist < routingContext.waypointCatchingRange)
            return false
        }
      }
      val removeBackList: MutableList<OsmPathElement> = ArrayList()
      val removeForeList: MutableList<OsmPathElement> = ArrayList()
      val removeVoiceHintList: MutableList<Int> = ArrayList()
      var last: OsmPathElement? = null
      var lastJunction: OsmPathElement? = null
      val lastJunctions = CompactLongMap<OsmTrack.OsmPathElementHolder>()
      var newJunction: OsmPathElement? = null
      var newTarget: OsmPathElement? = null
      var tmpback: OsmPathElement? = null
      var tmpfore: OsmPathElement? = null
      var tmpStart: OsmPathElement? = null
      var indexback = ourSize - 1
      var indexfore = 0
      val stop = (if (indexback - MAX_STEPS_CHECK > 1) indexback - MAX_STEPS_CHECK else 1)
      var wayDistance = 0.0
      var nextDist = 0.0
      var bCheckRoundAbout = false
      var bBackRoundAbout = false
      var bForeRoundAbout = false
      var indexBackFound = 0
      var indexForeFound = 0
      var differentLanePoints = 0
      var indexMeeting = -1
      while (indexback >= 1 && indexback >= stop && indexfore < t.nodes.size) {
        tmpback = tt.nodes.get(indexback)
        tmpfore = t.nodes.get(indexfore)
        if (!bBackRoundAbout && tmpback.message != null && tmpback.message!!.isRoundabout()) {
          bBackRoundAbout = true
          indexBackFound = indexfore
        }
        if (!bForeRoundAbout &&
          tmpfore.message != null && tmpfore.message!!.isRoundabout() ||
          (tmpback.positionEquals(tmpfore) && tmpback.message!!.isRoundabout())) {
          bForeRoundAbout = true
          indexForeFound = indexfore
        }
        if (indexfore == 0) {
          tmpStart = t.nodes.get(0)
        } else {
          val dirback = CheapAngleMeter.getDirection(tmpStart!!.getILon(), tmpStart.getILat(), tmpback.getILon(), tmpback.getILat())
          val dirfore = CheapAngleMeter.getDirection(tmpStart.getILon(), tmpStart.getILat(), tmpfore.getILon(), tmpfore.getILat())
          val dirdiff = CheapAngleMeter.getDifferenceFromDirection(dirback, dirfore)
          // walking wrong direction
          if (dirdiff > 60 && !bBackRoundAbout && !bForeRoundAbout) break
        }
        // seems no roundabout, only on one end
        if (bBackRoundAbout != bForeRoundAbout && indexfore - abs(indexForeFound - indexBackFound) > 8) break
        if (!tmpback.positionEquals(tmpfore)) differentLanePoints++
        if (tmpback.positionEquals(tmpfore)) indexMeeting = indexback
        bCheckRoundAbout = bBackRoundAbout && bForeRoundAbout
        if (bCheckRoundAbout) break
        indexback--
        indexfore++
      }
      //System.out.println("snap round result " + indexback + ": " + bBackRoundAbout + " - " + indexfore + "; " + bForeRoundAbout + " pts " + differentLanePoints);
      if (bCheckRoundAbout) {

        indexback--
        tmpback = tt.nodes.get(indexback)
        while (tmpback!!.message != null && tmpback.message!!.isRoundabout()) {
          indexback--
          tmpback = tt.nodes.get(indexback)
        }

        indexfore++
        var ifore = indexfore
        var testfore = t.nodes.get(ifore)
        while (ifore < t.nodes.size && testfore.message != null && testfore.message!!.isRoundabout()) {
          testfore = t.nodes.get(ifore)
          ifore++
        }

        ifore--
        snapRoundaboutConnection(tt, t, indexback, ifore, indexMeeting, startWp)

        // remove filled arrays
        removeVoiceHintList.clear()
        removeBackList.clear()
        removeForeList.clear()
        return true
      }
      indexback = ourSize - 1
      indexfore = 0
      while (indexback >= 1 && indexback >= stop && indexfore < t.nodes.size) {
        var junctions = 0
        tmpback = tt.nodes.get(indexback)
        tmpfore = t.nodes.get(indexfore)
        if (tmpback.message != null && tmpback.message!!.isRoundabout()) {
          bCheckRoundAbout = true
        }
        if (tmpfore.message != null && tmpfore.message!!.isRoundabout()) {
          bCheckRoundAbout = true
        }
        // (bare block in Java)
        val dist = tmpback.calcDistance(tmpfore)
        val detours = tt.getFromDetourMap(tmpback.getIdFromPos())
        var h: OsmTrack.OsmPathElementHolder? = detours
        while (h != null) {
          junctions++
          lastJunctions.put(h.node!!.getIdFromPos(), h)
          h = h.nextHolder
        }

        if (dist == 1 && indexfore > 0) {
          if (indexfore == 1) {
            removeBackList.add(tt.nodes.get(tt.nodes.size - 1)) // last and first should be equal, so drop only on second also equal
            removeForeList.add(t.nodes.get(0))
            removeBackList.add(tmpback)
            removeForeList.add(tmpfore)
            removeVoiceHintList.add(tt.nodes.size - 1)
            removeVoiceHintList.add(indexback)
          } else {
            removeBackList.add(tmpback)
            removeForeList.add(tmpfore)
            removeVoiceHintList.add(indexback)
          }
          nextDist = t.nodes.get(indexfore - 1).calcDistance(tmpfore).toDouble()
          wayDistance += nextDist

        }
        if (dist > 1 || indexback == 1) {
          if (removeBackList.size != 0) {
            // recover last - should be the cross point
            removeBackList.remove(removeBackList.get(removeBackList.size - 1))
            removeForeList.remove(removeForeList.get(removeForeList.size - 1))
            break
          } else {
            return false
          }
        }
        indexback--
        indexfore++

        if (routingContext.correctMisplacedViaPointsDistance > 0 &&
          wayDistance > routingContext.correctMisplacedViaPointsDistance) {
          removeVoiceHintList.clear()
          removeBackList.clear()
          removeForeList.clear()
          return false
        }
      }


      // time hold
      var atime = 0f
      var aenergy = 0f
      var acost = 0
      if (removeForeList.size > 1) {
        atime = t.nodes.get(indexfore - 1).getTime()
        aenergy = t.nodes.get(indexfore - 1).getEnergy()
        acost = t.nodes.get(indexfore - 1).cost
      }

      for (e in removeBackList) {
        tt.nodes.remove(e)
      }
      for (e in removeForeList) {
        t.nodes.remove(e)
      }
      for (e in removeVoiceHintList) {
        tt.removeVoiceHint(e)
      }
      removeVoiceHintList.clear()
      removeBackList.clear()
      removeForeList.clear()

      if (atime > 0f) {
        for (e in t.nodes) {
          e.setTime(e.getTime() - atime)
          e.setEnergy(e.getEnergy() - aenergy)
          e.cost = e.cost - acost
        }
      }

      if (t.nodes.size < 2)
        return true
      if (tt.nodes.size < 1)
        return true
      if (tt.nodes.size == 1) {
        last = tt.nodes.get(0)
      } else {
        last = tt.nodes.get(tt.nodes.size - 2)
      }
      newJunction = t.nodes.get(0)
      newTarget = t.nodes.get(1)

      tt.cost = tt.nodes.get(tt.nodes.size - 1).cost
      t.cost = t.nodes.get(t.nodes.size - 1).cost

      // fill to correctedpoint
      startWp.correctedpoint = OsmNode(newJunction.getILon(), newJunction.getILat())

      return true
    }
    return false
  }

  private fun recalcTrack(t: OsmTrack) {
    var totaldist = 0
    var totaltime = 0
    var lasttime = 0f
    var lastenergy = 0f
    var speed_min = 9999f
    val directMap: MutableMap<Int, Int> = HashMap()
    var tmptime = 1f
    var speed = 1f
    var dist: Int
    var angle: Double

    var ascend = 0.0
    var ehb = 0.0
    val ourSize = t.nodes.size

    var ele_start: Short = Short.MIN_VALUE
    var ele_end: Short = Short.MIN_VALUE
    val eleFactor = if (routingContext.inverseRouting) 0.25 else -0.25

    for (i in 0 until ourSize) {
      val n = t.nodes.get(i)
      if (n.message == null) n.message = MessageData()
      var nLast: OsmPathElement? = null
      if (i == 0) {
        angle = 0.0
        dist = 0
      } else if (i == 1) {
        angle = 0.0
        nLast = t.nodes.get(0)
        dist = nLast.calcDistance(n)
      } else {
        val lon0 = t.nodes.get(i - 2).getILon()
        val lat0 = t.nodes.get(i - 2).getILat()
        val lon1 = t.nodes.get(i - 1).getILon()
        val lat1 = t.nodes.get(i - 1).getILat()
        val lon2 = t.nodes.get(i).getILon()
        val lat2 = t.nodes.get(i).getILat()
        angle = routingContext.anglemeter.calcAngle(lon0, lat0, lon1, lat1, lon2, lat2)
        nLast = t.nodes.get(i - 1)
        dist = nLast.calcDistance(n)
      }
      n.message!!.linkdist = dist
      n.message!!.turnangle = angle.toFloat()
      totaldist += dist
      totaltime = (totaltime + n.getTime()).toInt()
      tmptime = (n.getTime() - lasttime)
      if (dist > 0) {
        speed = dist / tmptime * 3.6f
        speed_min = minOf(speed_min, speed)
      }
      if (tmptime == 1f) { // no time used here
        directMap.put(i, dist)
      }

      lastenergy = n.getEnergy()
      lasttime = n.getTime()

      val ele = n.getSElev()
      if (ele != Short.MIN_VALUE)
        ele_end = ele
      if (ele_start == Short.MIN_VALUE)
        ele_start = ele

      if (nLast != null) {
        val ele_last = nLast.getSElev()
        if (ele_last != Short.MIN_VALUE) {
          ehb = ehb + (ele_last - ele) * eleFactor
        }
        val filter = elevationFilter(n)
        if (ehb > 0) {
          ascend += ehb
          ehb = 0.0
        } else if (ehb < filter) {
          ehb = filter
        }
      }

    }

    t.ascend = ascend.toInt()
    t.plainAscend = ((ele_start - ele_end) * eleFactor + 0.5).toInt()

    t.distance = totaldist
    //t.energy = totalenergy;

    // PORT: TreeSet of the keys -> collect and sort ascending
    val keys: MutableList<Int> = ArrayList(directMap.keys) // PORT-NOTE hash order (sorted right below)
    keys.sort()
    for (key in keys) {
      val value = directMap.get(key)!!
      val addTime = (value / (speed_min / 3.6f))

      var addEnergy = 0.0
      if (key > 0) {
        val GRAVITY = 9.81  // in meters per second^(-2)
        val incline = (if (t.nodes.get(key - 1).getSElev() == Short.MIN_VALUE || t.nodes.get(key).getSElev() == Short.MIN_VALUE) 0.0 else (t.nodes.get(key - 1).getElev() - t.nodes.get(key).getElev()) / value)
        val f_roll = routingContext.totalMass * GRAVITY * (routingContext.defaultC_r + incline)
        val spd = speed_min / 3.6
        addEnergy = value * (routingContext.S_C_x * spd * spd + f_roll)
      }
      for (j in key until ourSize) {
        val n = t.nodes.get(j)
        n.setTime(n.getTime() + addTime)
        n.setEnergy(n.getEnergy() + addEnergy.toFloat())
      }
    }
    t.energy = t.nodes.get(t.nodes.size - 1).getEnergy().toInt()

    logInfo("track-length total = " + t.distance)
    logInfo("filtered ascend = " + t.ascend)
  }

  /**
   * find the elevation type for position
   * to determine the filter value
   *
   * @param n  the point
   * @return  the filter value for 1sec / 3sec elevation source
   */
  fun elevationFilter(n: OsmPos): Double {
    if (nodesCache != null) {
      val r = nodesCache!!.getElevationType(n.getILon(), n.getILat())
      if (r == 1) return -5.0
    }
    return -10.0
  }

  // geometric position matching finding the nearest routable way-section
  private fun matchWaypointsToNodes(unmatchedWaypoints: MutableList<MatchedWaypoint>) {
    resetCache(false)
    val useDynamicDistance = routingContext.useDynamicDistance
    val bAddBeeline = routingContext.buildBeelineOnRange
    var range = routingContext.waypointCatchingRange
    var ok = nodesCache!!.matchWaypointsToNodes(unmatchedWaypoints, range, islandNodePairs)
    if (!ok && useDynamicDistance) {
      logInfo("second check for way points")
      resetCache(false)
      range = (-MAX_DYNAMIC_RANGE).toDouble()
      val tmp: MutableList<MatchedWaypoint> = ArrayList()
      for (mwp in unmatchedWaypoints) {
        if (mwp.crosspoint == null || mwp.radius >= routingContext.waypointCatchingRange)
          tmp.add(mwp)
      }
      ok = nodesCache!!.matchWaypointsToNodes(tmp, range, islandNodePairs)
    }
    if (!ok) {
      for (mwp in unmatchedWaypoints) {
        if (mwp.crosspoint == null)
          throw IllegalArgumentException(mwp.name.toString() + "-position not mapped in existing datafile")
      }
    }
    // add beeline points when not already done
    if (useDynamicDistance && !useNodePoints && bAddBeeline) {
      val waypoints: MutableList<MatchedWaypoint> = ArrayList()
      for (i in 0 until unmatchedWaypoints.size) {
        val wp = unmatchedWaypoints.get(i)
        if (wp.waypoint!!.calcDistance(wp.crosspoint!!) > routingContext.waypointCatchingRange) {

          val nmw = MatchedWaypoint()
          if (i == 0) {
            var onn = OsmNodeNamed(wp.waypoint!!)
            onn.name = "from"
            nmw.waypoint = onn
            nmw.name = onn.name
            nmw.crosspoint = OsmNode(wp.waypoint!!.ilon, wp.waypoint!!.ilat)
            nmw.wpttype = MatchedWaypoint.WAYPOINT_TYPE_DIRECT
            onn = OsmNodeNamed(wp.crosspoint!!)
            onn.name = wp.name + "_add"
            wp.waypoint = onn
            waypoints.add(nmw)
            wp.name = wp.name + "_add"
            waypoints.add(wp)
          } else {
            val onn = OsmNodeNamed(wp.crosspoint!!)
            onn.name = wp.name + "_add"
            nmw.waypoint = onn
            nmw.crosspoint = OsmNode(wp.crosspoint!!.ilon, wp.crosspoint!!.ilat)
            nmw.node1 = OsmNode(wp.node1!!.ilon, wp.node1!!.ilat)
            nmw.node2 = OsmNode(wp.node2!!.ilon, wp.node2!!.ilat)
            nmw.wpttype = MatchedWaypoint.WAYPOINT_TYPE_DIRECT

            if (wp.name != null) nmw.name = wp.name
            waypoints.add(nmw)
            wp.name = wp.name + "_add"
            waypoints.add(wp)
            if (wp.name!!.startsWith("via")) {
              wp.wpttype = MatchedWaypoint.WAYPOINT_TYPE_DIRECT
              val emw = MatchedWaypoint()
              val onn2 = OsmNodeNamed(wp.crosspoint!!)
              onn2.name = wp.name + "_2"
              emw.name = onn2.name
              emw.waypoint = onn2
              emw.crosspoint = OsmNode(nmw.crosspoint!!.ilon, nmw.crosspoint!!.ilat)
              emw.node1 = OsmNode(nmw.node1!!.ilon, nmw.node1!!.ilat)
              emw.node2 = OsmNode(nmw.node2!!.ilon, nmw.node2!!.ilat)
              emw.wpttype = MatchedWaypoint.WAYPOINT_TYPE_SHAPING
              waypoints.add(emw)
            }
            wp.crosspoint = OsmNode(wp.waypoint!!.ilon, wp.waypoint!!.ilat)
          }
        } else {
          waypoints.add(wp)
        }
      }
      unmatchedWaypoints.clear()
      unmatchedWaypoints.addAll(waypoints)
    }

  }

  private fun searchTrack(startWp: MatchedWaypoint, endWp: MatchedWaypoint, nearbyTrack: OsmTrack?, refTrack: OsmTrack?): OsmTrack? {
    // remove nogos with waypoints inside
    try {
      val calcBeeline = startWp.wpttype == MatchedWaypoint.WAYPOINT_TYPE_DIRECT

      if (!calcBeeline)
        return searchRoutedTrack(startWp, endWp, nearbyTrack, refTrack)

      // we want a beeline-segment
      var path = routingContext.createPath(OsmLink(null, startWp.crosspoint))
      path = routingContext.createPath(path, OsmLink(startWp.crosspoint, endWp.crosspoint), null, false)
      return compileTrack(path, false)
    } finally {
      routingContext.restoreNogoList()
    }
  }

  private fun searchRoutedTrack(startWp: MatchedWaypoint, endWp: MatchedWaypoint, nearbyTrack: OsmTrack?, refTrack: OsmTrack?): OsmTrack? {
    var track: OsmTrack? = null
    val airDistanceCostFactors = doubleArrayOf(
      routingContext.pass1coefficient,
      routingContext.pass2coefficient
    )
    var isDirty = false
    var dirtyMessage: IllegalArgumentException? = null

    if (nearbyTrack != null) {
      airDistanceCostFactor = 0.0
      try {
        track = findTrack("re-routing", startWp, endWp, nearbyTrack, refTrack, true)
      } catch (iae: IllegalArgumentException) {
        if (terminated) throw iae

        // fast partial recalcs: if that timed out, but we had a match,
        // build the concatenation from the partial and the nearby track
        if (matchPath != null) {
          track = mergeTrack(matchPath!!, nearbyTrack)
          isDirty = true
          dirtyMessage = iae
          logInfo("using fast partial recalc")
        }
        if (maxRunningTime > 0) {
          maxRunningTime += currentTimeMillis() - startTime // reset timeout...
        }
      }
    }

    if (track == null) {
      for (cfi in 0 until airDistanceCostFactors.size) {
        if (cfi > 0) lastAirDistanceCostFactor = airDistanceCostFactors[cfi - 1]
        airDistanceCostFactor = airDistanceCostFactors[cfi]

        if (airDistanceCostFactor < 0.0) {
          continue
        }

        var t: OsmTrack? = null
        try {
          t = findTrack(if (cfi == 0) "pass0" else "pass1", startWp, endWp, track, refTrack, false)
          if (routingContext.ai != null) return t
        } catch (iae: IllegalArgumentException) {
          if (!terminated && matchPath != null) { // timeout, but eventually prepare a dirty ref track
            logInfo("supplying dirty reference track after timeout")
            foundRawTrack = mergeTrack(matchPath!!, track!!)
            foundRawTrack!!.endPoint = endWp
            foundRawTrack!!.nogoChecksums = routingContext.getNogoChecksums()
            foundRawTrack!!.profileTimestamp = routingContext.profileTimestamp
            foundRawTrack!!.isDirty = true
          }
          throw iae
        }

        if (t == null && track != null && matchPath != null) {
          // ups, didn't find it, use a merge
          t = mergeTrack(matchPath!!, track)
          logInfo("using sloppy merge cause pass1 didn't reach destination")
        }
        if (t != null) {
          track = t
        } else {
          throw IllegalArgumentException("no track found at pass=" + cfi)
        }
      }
    }
    if (track == null) throw IllegalArgumentException("no track found")

    val lastElement: OsmPathElement? = null

    val wasClean = nearbyTrack != null && !nearbyTrack.isDirty
    if (refTrack == null && !(wasClean && isDirty)) { // do not overwrite a clean with a dirty track
      logInfo("supplying new reference track, dirty=" + isDirty)
      track.endPoint = endWp
      track.nogoChecksums = routingContext.getNogoChecksums()
      track.profileTimestamp = routingContext.profileTimestamp
      track.isDirty = isDirty
      foundRawTrack = track
    }

    if (!wasClean && isDirty) {
      throw dirtyMessage!!
    }

    // final run for verbose log info and detail nodes
    airDistanceCostFactor = 0.0
    lastAirDistanceCostFactor = 0.0
    guideTrack = track
    startTime = currentTimeMillis() // reset timeout...
    try {
      val tt = findTrack("re-tracking", startWp, endWp, null, refTrack, false)
      if (tt == null) throw IllegalArgumentException("error re-tracking track")
      return tt
    } finally {
      guideTrack = null
    }
  }


  private fun resetCache(detailed: Boolean) {
    if (hasInfo() && nodesCache != null) {
      logInfo("NodesCache status before reset=" + nodesCache!!.formatStatus())
    }
    val maxmem = routingContext.memoryclass * 1024L * 1024L // in MB

    nodesCache = NodesCache(segmentDir, routingContext.expctxWay, routingContext.forceSecondaryData, maxmem, nodesCache, detailed)
    islandNodePairs.clearTempPairs()
  }

  private fun getStartPath(n1: OsmNode, n2: OsmNode, mwp: MatchedWaypoint, endPos: OsmNodeNamed?, sameSegmentSearch: Boolean): OsmPath? {
    if (endPos != null) {
      endPos.radius = 1.5
    }
    val p = getStartPath(n1, n2, OsmNodeNamed(mwp.crosspoint!!), endPos, sameSegmentSearch)

    // special case: start+end on same segment
    if (p != null && p.cost >= 0 && sameSegmentSearch && endPos != null && endPos.radius < 1.5) {
      p.treedepth = 0 // hack: mark for the final-check
    }
    return p
  }


  private fun getStartPath(n1: OsmNode, n2: OsmNode, wp: OsmNodeNamed, endPos: OsmNodeNamed?, sameSegmentSearch: Boolean): OsmPath? {
    try {
      routingContext.setWaypoint(wp, if (sameSegmentSearch) endPos else null, false)
      var bestPath: OsmPath? = null
      var bestLink: OsmLink? = null
      val startLink = OsmLink(null, n1)
      val startPath = routingContext.createPath(startLink)
      startLink.addLinkHolder(startPath, null)
      var minradius = 1e10
      var link: OsmLink? = n1.firstlink
      while (link != null) {
        body@ do {
          val nextNode = link!!.getTarget(n1)
          if (nextNode.isHollow())
            break@body // border node?
          if (nextNode.firstlink == null)
            break@body // don't care about dead ends
          if (nextNode === n1)
            break@body // ?
          if (nextNode !== n2)
            break@body // just that link

          wp.radius = 1.5
          val testPath = routingContext.createPath(startPath, link, null, guideTrack != null)
          testPath.airdistance = if (endPos == null) 0 else nextNode.calcDistance(endPos)
          if (wp.radius < minradius) {
            bestPath = testPath
            minradius = wp.radius
            bestLink = link
          }
        } while (false)
        link = link!!.getNext(n1)
      }
      if (bestLink != null) {
        bestLink!!.addLinkHolder(bestPath!!, n1)
      }
      if (bestPath != null) bestPath!!.treedepth = 1

      return bestPath
    } finally {
      routingContext.unsetWaypoint()
    }
  }

  private fun findTrack(operationName: String, startWp: MatchedWaypoint?, endWp: MatchedWaypoint?, costCuttingTrack: OsmTrack?, refTrack: OsmTrack?, fastPartialRecalc: Boolean): OsmTrack? {
    try {
      val wpts2: MutableList<OsmNode> = ArrayList()
      if (startWp != null) wpts2.add(startWp.waypoint!!)
      if (endWp != null) wpts2.add(endWp.waypoint!!)
      routingContext.cleanNogoList(wpts2)

      val detailed = guideTrack != null
      resetCache(detailed)
      nodesCache!!.nodesMap!!.cleanupMode = if (detailed) 0 else (if (routingContext.considerTurnRestrictions) 2 else 1)
      return _findTrack(operationName, startWp!!, endWp, costCuttingTrack, refTrack, fastPartialRecalc)
    } finally {
      routingContext.restoreNogoList()
      nodesCache!!.clean(false) // clean only non-virgin caches
    }
  }


  private fun _findTrack(operationName: String, startWp: MatchedWaypoint, endWp: MatchedWaypoint?, costCuttingTrack: OsmTrack?, refTrack: OsmTrack?, fastPartialRecalc0: Boolean): OsmTrack? {
    var fastPartialRecalc = fastPartialRecalc0
    val verbose = guideTrack != null

    var maxTotalCost = if (guideTrack != null) guideTrack!!.cost + 5000 else 1000000000
    var firstMatchCost = 1000000000

    logInfo("findtrack with airDistanceCostFactor=" + airDistanceCostFactor)
    if (costCuttingTrack != null) logInfo("costCuttingTrack.cost=" + costCuttingTrack.cost)

    matchPath = null
    var nodesVisited = 0

    val startNodeId1 = startWp.node1!!.getIdFromPos()
    val startNodeId2 = startWp.node2!!.getIdFromPos()
    val endNodeId1 = if (endWp == null) -1L else endWp.node1!!.getIdFromPos()
    val endNodeId2 = if (endWp == null) -1L else endWp.node2!!.getIdFromPos()
    var end1: OsmNode? = null
    var end2: OsmNode? = null
    var endPos: OsmNodeNamed? = null

    var sameSegmentSearch = false
    val start1 = nodesCache!!.getGraphNode(startWp.node1!!)
    val start2 = nodesCache!!.getGraphNode(startWp.node2!!)
    if (endWp != null) {
      end1 = nodesCache!!.getGraphNode(endWp.node1!!)
      end2 = nodesCache!!.getGraphNode(endWp.node2!!)
      nodesCache!!.nodesMap!!.endNode1 = end1
      nodesCache!!.nodesMap!!.endNode2 = end2
      endPos = OsmNodeNamed(endWp.crosspoint!!)
      sameSegmentSearch = (start1 === end1 && start2 === end2) || (start1 === end2 && start2 === end1)
    }
    if (!nodesCache!!.obtainNonHollowNode(start1)) {
      return null
    }
    nodesCache!!.expandHollowLinkTargets(start1)
    if (!nodesCache!!.obtainNonHollowNode(start2)) {
      return null
    }
    nodesCache!!.expandHollowLinkTargets(start2)


    routingContext.startDirectionValid = routingContext.forceUseStartDirection || fastPartialRecalc
    routingContext.startDirectionValid = routingContext.startDirectionValid and (routingContext.startDirection != null && !routingContext.inverseDirection)
    if (routingContext.startDirectionValid) {
      logInfo("using start direction " + routingContext.startDirection)
    }

    val startPath1 = getStartPath(start1, start2, startWp, endPos, sameSegmentSearch)
    val startPath2 = getStartPath(start2, start1, startWp, endPos, sameSegmentSearch)

    // check for an INITIAL match with the cost-cutting-track
    if (costCuttingTrack != null) {
      val pe1 = costCuttingTrack.getLink(startNodeId1, startNodeId2)
      if (pe1 != null) {
        logInfo("initialMatch pe1.cost=" + pe1.cost)
        var c = startPath1!!.cost - pe1.cost
        if (c < 0) c = 0
        if (c < firstMatchCost) firstMatchCost = c
      }

      val pe2 = costCuttingTrack.getLink(startNodeId2, startNodeId1)
      if (pe2 != null) {
        logInfo("initialMatch pe2.cost=" + pe2.cost)
        var c = startPath2!!.cost - pe2.cost
        if (c < 0) c = 0
        if (c < firstMatchCost) firstMatchCost = c
      }

      if (firstMatchCost < 1000000000)
        logInfo("firstMatchCost from initial match=" + firstMatchCost)
    }

    if (startPath1 == null) return null
    if (startPath2 == null) return null

    // synchronized (openSet) {
    openSet.clear()
    addToOpenset(startPath1)
    addToOpenset(startPath2)
    // }
    val openBorderList: MutableList<OsmPath> = ArrayList(4096)
    var memoryPanicMode = false
    var needNonPanicProcessing = false

    while (true) {
      if (terminated) {
        throw IllegalArgumentException("operation killed by thread-priority-watchdog after " + (currentTimeMillis() - startTime) / 1000 + " seconds")
      }

      if (maxRunningTime > 0) {
        val timeout = if (matchPath == null && fastPartialRecalc) maxRunningTime / 3 else maxRunningTime
        if (currentTimeMillis() - startTime > timeout) {
          throw IllegalArgumentException(operationName + " timeout after " + (timeout / 1000) + " seconds")
        }
      }

      // synchronized (openSet) {

      val path = openSet.popLowestKeyValue()
      if (path == null) {
        if (openBorderList.isEmpty()) {
          break
        }
        for (p in openBorderList) {
          openSet.add(p.cost + (p.airdistance * airDistanceCostFactor).toInt(), p)
        }
        openBorderList.clear()
        memoryPanicMode = false
        needNonPanicProcessing = true
        continue
      }

      if (path.airdistance == -1) {
        continue
      }

      if (directWeaving && nodesCache!!.hasHollowLinkTargets(path.getTargetNode())) {
        // PORT-NOTE memory: memory-bounds control flow kept as is (nodesMap.isInMemoryBounds uses the memoryclass budget)
        if (!memoryPanicMode) {
          if (!nodesCache!!.nodesMap!!.isInMemoryBounds(openSet.getSize(), false)) {
            val nodesBefore = nodesCache!!.nodesMap!!.nodesCreated
            val pathsBefore = openSet.getSize()

            nodesCache!!.nodesMap!!.collectOutreachers()
            while (true) {
              val p3 = openSet.popLowestKeyValue()
              if (p3 == null) break
              if (p3.airdistance != -1 && nodesCache!!.nodesMap!!.canEscape(p3.getTargetNode())) {
                openBorderList.add(p3)
              }
            }
            nodesCache!!.nodesMap!!.clearTemp()
            for (p in openBorderList) {
              openSet.add(p.cost + (p.airdistance * airDistanceCostFactor).toInt(), p)
            }
            openBorderList.clear()
            logInfo("collected, nodes/paths before=" + nodesBefore + "/" + pathsBefore + " after=" + nodesCache!!.nodesMap!!.nodesCreated + "/" + openSet.getSize() + " maxTotalCost=" + maxTotalCost)
            if (!nodesCache!!.nodesMap!!.isInMemoryBounds(openSet.getSize(), true)) {
              if (maxTotalCost < 1000000000 || needNonPanicProcessing || fastPartialRecalc) {
                throw IllegalArgumentException("memory limit reached")
              }
              memoryPanicMode = true
              logInfo("************************ memory limit reached, enabled memory panic mode *************************")
            }
          }
        }
        if (memoryPanicMode) {
          openBorderList.add(path)
          continue
        }
      }
      needNonPanicProcessing = false


      if (fastPartialRecalc && matchPath != null && path.cost > 30L * firstMatchCost && !costCuttingTrack!!.isDirty) {
        logInfo("early exit: firstMatchCost=" + firstMatchCost + " path.cost=" + path.cost)

        // use an early exit, unless there's a realistc chance to complete within the timeout
        if (path.cost > maxTotalCost / 2 && currentTimeMillis() - startTime < maxRunningTime / 3) {
          logInfo("early exit supressed, running for completion, resetting timeout")
          startTime = currentTimeMillis()
          fastPartialRecalc = false
        } else {
          throw IllegalArgumentException("early exit for a close recalc")
        }
      }

      if (nodeLimit > 0) { // check node-limit for target island search
        nodeLimit--
        if (nodeLimit == 0) {
          return null
        }
      }

      nodesVisited++
      linksProcessed++

      val currentLink = path.getLink()
      val sourceNode = path.getSourceNode()
      val currentNode = path.getTargetNode()

      if (currentLink.isLinkUnused()) {
        continue
      }

      val currentNodeId = currentNode.getIdFromPos()
      val sourceNodeId = sourceNode.getIdFromPos()

      if (!path.didEnterDestinationArea()) {
        islandNodePairs.addTempPair(sourceNodeId, currentNodeId)
      }

      if (path.treedepth != 1) {
        if (path.treedepth == 0) { // hack: sameSegment Paths marked treedepth=0 to pass above check
          path.treedepth = 1
        }

        if ((sourceNodeId == endNodeId1 && currentNodeId == endNodeId2)
          || (sourceNodeId == endNodeId2 && currentNodeId == endNodeId1)) {
          // track found, compile
          logInfo("found track at cost " + path.cost + " nodesVisited = " + nodesVisited)
          val t = compileTrack(path, verbose)
          t.showspeed = routingContext.showspeed
          t.showSpeedProfile = routingContext.showSpeedProfile
          return t
        }

        // check for a match with the cost-cutting-track
        if (costCuttingTrack != null) {
          val pe = costCuttingTrack.getLink(sourceNodeId, currentNodeId)
          if (pe != null) {
            // remember first match cost for fast termination of partial recalcs
            var parentcost = if (path.originElement == null) 0 else path.originElement!!.cost

            // hitting start-element of costCuttingTrack?
            val c = path.cost - parentcost - pe.cost
            if (c > 0) parentcost += c

            if (parentcost < firstMatchCost) firstMatchCost = parentcost

            val costEstimate = (path.cost
              + path.elevationCorrection()
              + (costCuttingTrack.cost - pe.cost))
            if (costEstimate <= maxTotalCost) {
              matchPath = OsmPathElement.create(path)
            }
            if (costEstimate < maxTotalCost) {
              logInfo("maxcost " + maxTotalCost + " -> " + costEstimate)
              maxTotalCost = costEstimate
            }
          }
        }
      }

      val firstLinkHolder = currentLink.getFirstLinkHolder(sourceNode)
      var linkHolder0: OsmLinkHolder? = firstLinkHolder
      while (linkHolder0 != null) {
        (linkHolder0 as OsmPath).airdistance = -1 // invalidate the entry in the open set;
        linkHolder0 = linkHolder0.getNextForLink()
      }

      if (path.treedepth > 1) {
        val isBidir = currentLink.isBidirectional()
        sourceNode.unlinkLink(currentLink)

        // if the counterlink is alive and does not yet have a path, remove it
        if (isBidir && currentLink.getFirstLinkHolder(currentNode) == null && !routingContext.considerTurnRestrictions) {
          currentNode.unlinkLink(currentLink)
        }
      }

      // recheck cutoff before doing expensive stuff
      val addDiff = 100
      if (path.cost + path.airdistance > maxTotalCost + addDiff) {
        continue
      }

      nodesCache!!.nodesMap!!.currentMaxCost = maxTotalCost
      nodesCache!!.nodesMap!!.currentPathCost = path.cost
      nodesCache!!.nodesMap!!.destination = endPos

      routingContext.firstPrePath = null

      var link0: OsmLink? = currentNode.firstlink
      while (link0 != null) {
        body0@ do {
          val link = link0!!
          val nextNode = link.getTarget(currentNode)

          if (!nodesCache!!.obtainNonHollowNode(nextNode)) {
            break@body0 // border node?
          }
          if (nextNode.firstlink == null) {
            break@body0 // don't care about dead ends
          }
          if (nextNode === sourceNode) {
            break@body0 // border node?
          }

          val prePath = routingContext.createPrePath(path, link)
          if (prePath != null) {
            prePath.next = routingContext.firstPrePath
            routingContext.firstPrePath = prePath
          }
        } while (false)
        link0 = link0!!.getNext(currentNode)
      }

      var link1: OsmLink? = currentNode.firstlink
      while (link1 != null) {
        body1@ do {
          val link = link1!!
          val nextNode = link.getTarget(currentNode)

          if (!nodesCache!!.obtainNonHollowNode(nextNode)) {
            break@body1 // border node?
          }
          if (nextNode.firstlink == null) {
            break@body1 // don't care about dead ends
          }
          if (nextNode === sourceNode) {
            break@body1 // border node?
          }

          if (guideTrack != null) {
            val gidx = path.treedepth + 1
            if (gidx >= guideTrack!!.nodes.size) {
              break@body1
            }
            val guideNode = guideTrack!!.nodes.get(if (routingContext.inverseRouting) guideTrack!!.nodes.size - 1 - gidx else gidx)
            val nextId = nextNode.getIdFromPos()
            if (nextId != guideNode.getIdFromPos()) {
              // not along the guide-track, discard, but register for voice-hint processing
              if (routingContext.turnInstructionMode > 0) {
                val detour = routingContext.createPath(path, link, refTrack, true)
                if (detour.cost >= 0.0 && nextId != startNodeId1 && nextId != startNodeId2) {
                  guideTrack!!.registerDetourForId(currentNode.getIdFromPos(), OsmPathElement.create(detour))
                }
              }
              break@body1
            }
          }

          var bestPath: OsmPath? = null

          var isFinalLink = false
          val targetNodeId = nextNode.getIdFromPos()
          if (currentNodeId == endNodeId1 || currentNodeId == endNodeId2) {
            if (targetNodeId == endNodeId1 || targetNodeId == endNodeId2) {
              isFinalLink = true
            }
          }

          var linkHolder: OsmLinkHolder? = firstLinkHolder
          while (linkHolder != null) {
            val otherPath = linkHolder as OsmPath
            try {
              if (isFinalLink) {
                endPos!!.radius = 1.5 // 1.5 meters is the upper limit that will not change the unit-test result..
                routingContext.setWaypoint(endPos, true)
              }
              val testPath = routingContext.createPath(otherPath, link, refTrack, guideTrack != null)
              if (testPath.cost >= 0 && (bestPath == null || testPath.cost < bestPath.cost) &&
                (testPath.sourceNode!!.getIdFromPos() != testPath.targetNode!!.getIdFromPos())) {
                bestPath = testPath
              }
            } finally {
              if (isFinalLink) {
                routingContext.unsetWaypoint()
              }
            }
            linkHolder = linkHolder.getNextForLink()
          }
          if (bestPath != null) {
            bestPath.airdistance = if (isFinalLink) 0 else nextNode.calcDistance(endPos!!)

            val inRadius = boundary == null || boundary!!.isInBoundary(nextNode, bestPath.cost)

            if (inRadius && (isFinalLink || bestPath.cost + bestPath.airdistance <= (if (lastAirDistanceCostFactor != 0.0) maxTotalCost * lastAirDistanceCostFactor else maxTotalCost.toDouble()) + addDiff)) {
              // add only if this may beat an existing path for that link
              var dominator = link.getFirstLinkHolder(currentNode)
              while (dominator != null) {
                val dp = dominator as OsmPath
                if (dp.airdistance != -1 && bestPath.definitlyWorseThan(dp)) {
                  break
                }
                dominator = dominator.getNextForLink()
              }

              if (dominator == null) {
                bestPath.treedepth = path.treedepth + 1
                link.addLinkHolder(bestPath, currentNode)
                addToOpenset(bestPath)
              }
            }
          }
        } while (false)
        link1 = link1!!.getNext(currentNode)
      }
      // }
    }

    if (nodesVisited < MAXNODES_ISLAND_CHECK && islandNodePairs.getFreezeCount() < 5) {
      throw RoutingIslandException()
    }

    return null
  }

  private fun addToOpenset(path: OsmPath) {
    if (path.cost >= 0) {
      openSet.add(path.cost + (path.airdistance * airDistanceCostFactor).toInt(), path)
    }
  }

  private fun compileTrack(path: OsmPath, verbose: Boolean): OsmTrack {
    var element: OsmPathElement? = OsmPathElement.create(path)

    // for final track, cut endnode
    if (guideTrack != null && element!!.origin != null) {
      element = element.origin
    }

    val totalTime = element!!.getTime()
    val totalEnergy = element.getEnergy()

    val track = OsmTrack()
    track.cost = path.cost
    track.energy = path.getTotalEnergy().toInt()

    var distance = 0

    val eleFactor = if (routingContext.inverseRouting) -0.25 else 0.25
    while (element != null) {
      if (guideTrack != null && element.message == null) {
        element.message = MessageData()
      }
      val nextElement = element.origin
      // ignore double element
      if (nextElement != null && nextElement.positionEquals(element)) {
        element = nextElement
        continue
      }
      if (routingContext.inverseRouting) {
        element.setTime(totalTime - element.getTime())
        element.setEnergy(totalEnergy - element.getEnergy())
        track.nodes.add(element)
      } else {
        track.nodes.add(0, element)
      }

      if (nextElement != null) {
        distance += element.calcDistance(nextElement)
      }
      element = nextElement
    }
    track.distance = distance
    logInfo("track-length = " + track.distance)
    track.buildMap()

    // for final track..
    if (guideTrack != null) {
      track.copyDetours(guideTrack!!)
    }
    return track
  }

  private fun mergeTrack(match: OsmPathElement, oldTrack: OsmTrack): OsmTrack {
    logInfo("**************** merging match=" + match.cost + " with oldTrack=" + oldTrack.cost)
    var element: OsmPathElement? = match
    val track = OsmTrack()
    track.cost = oldTrack.cost

    while (element != null) {
      track.addNode(element)
      element = element.origin
    }
    var lastId: Long = 0
    val id1 = match.getIdFromPos()
    val id0 = if (match.origin == null) 0 else match.origin!!.getIdFromPos()
    var appending = false
    for (n in oldTrack.nodes) {
      if (appending) {
        track.nodes.add(n)
      }

      val id = n.getIdFromPos()
      if (id == id1 && lastId == id0) {
        appending = true
      }
      lastId = id
    }


    track.buildMap()
    return track
  }

  fun getPathPeak(): Int {
    // synchronized (openSet)
    return openSet.getPeakSize()
  }

  // PORT-CUT getOpenSet (UI progress dump, needs the extract array)

  fun isFinished(): Boolean {
    return finished
  }

  fun getLinksProcessed(): Int {
    return linksProcessed
  }

  fun getDistance(): Int {
    return foundTrack.distance
  }

  fun getAscend(): Int {
    return foundTrack.ascend
  }

  fun getPlainAscend(): Int {
    return foundTrack.plainAscend
  }

  fun getTime(): String {
    // PORT-CUT Formatter.getFormattedTime2: plain seconds
    return foundTrack.getTotalSeconds().toString()
  }

  fun getFoundTrack(): OsmTrack {
    return foundTrack
  }

  fun getFoundInfo(): String? {
    return outputMessage
  }

  fun getAlternativeIndex(): Int {
    return alternativeIndex
  }

  fun getFoundRawTrack(): OsmTrack? {
    return foundRawTrack
  }

  fun getErrorMessage(): String? {
    return errorMessage
  }

  fun terminate() {
    terminated = true
  }

  fun isTerminated(): Boolean {
    return terminated
  }

  fun getOutfile(): String? {
    return outfile
  }
}
