/**
 * Container for a track
 *
 * @author ab
 */
package btools.router

import btools.mapaccess.MatchedWaypoint
import btools.mapaccess.OsmPos
import btools.util.CompactLongMap
import btools.util.FrozenLongMap

class OsmTrack {

  var endPoint: MatchedWaypoint? = null
  var nogoChecksums: LongArray? = null
  var profileTimestamp: Long = 0
  var isDirty: Boolean = false

  var showspeed: Boolean = false
  var showSpeedProfile: Boolean = false
  var showTime: Boolean = false

  var params: Map<String, String>? = null

  var pois: ArrayList<OsmNodeNamed> = ArrayList()

  class OsmPathElementHolder {
    var node: OsmPathElement? = null
    var nextHolder: OsmPathElementHolder? = null
  }

  var nodes: ArrayList<OsmPathElement> = ArrayList()

  private var nodesMap: CompactLongMap<OsmPathElementHolder>? = null

  private var detourMap: CompactLongMap<OsmPathElementHolder>? = null

  var voiceHints: VoiceHintList? = null

  var message: String? = null
  var messageList: List<String>? = null

  var name: String = "unset"

  var matchedWaypoints: List<MatchedWaypoint>? = null
  var exportWaypoints: Boolean = false
  var exportCorrectedWaypoints: Boolean = false

  fun addNode(node: OsmPathElement) {
    nodes.add(0, node)
  }

  fun registerDetourForId(id: Long, detour: OsmPathElement) {
    if (detourMap == null) {
      detourMap = CompactLongMap()
    }
    val nh = OsmPathElementHolder()
    nh.node = detour
    var h: OsmPathElementHolder? = detourMap!!.get(id)
    if (h != null) {
      while (h!!.nextHolder != null) {
        h = h.nextHolder
      }
      h!!.nextHolder = nh
    } else {
      detourMap!!.fastPut(id, nh)
    }
  }

  fun copyDetours(source: OsmTrack) {
    detourMap = if (source.detourMap == null) null else FrozenLongMap(source.detourMap!!)
  }

  fun addDetours(source: OsmTrack) {
    if (detourMap != null) {
      val tmpDetourMap = CompactLongMap<OsmPathElementHolder>()

      // PORT-NOTE Java also fetched getValueList() here but never used it (dropped)
      val oldidlist: LongArray = (detourMap as FrozenLongMap<OsmPathElementHolder>).getKeyArray()
      for (i in 0 until oldidlist.size) {
        val id = oldidlist[i]
        val v: OsmPathElementHolder? = detourMap!!.get(id)

        tmpDetourMap.put(id, v!!)
      }

      if (source.detourMap != null) {
        val idlist: LongArray = (source.detourMap as FrozenLongMap<OsmPathElementHolder>).getKeyArray()
        for (i in 0 until idlist.size) {
          val id = idlist[i]
          val v: OsmPathElementHolder? = source.detourMap!!.get(id)
          if (!tmpDetourMap.contains(id) && source.nodesMap!!.contains(id)) {
            tmpDetourMap.put(id, v!!)
          }
        }
      }
      detourMap = FrozenLongMap(tmpDetourMap)
    }
  }

  var lastorigin: OsmPathElement? = null

  fun appendDetours(source: OsmTrack) {
    if (detourMap == null) {
      detourMap = if (source.detourMap == null) null else CompactLongMap()
    }
    if (source.detourMap != null) {
      val pos = nodes.size - source.nodes.size + 1
      var origin: OsmPathElement? = null
      if (pos > 0)
        origin = nodes[pos]
      for (node in source.nodes) {
        val id = node.getIdFromPos()
        val nh = OsmPathElementHolder()
        if (node.origin == null && lastorigin != null)
          node.origin = lastorigin
        nh.node = node
        lastorigin = node
        var h: OsmPathElementHolder? = detourMap!!.get(id)
        if (h != null) {
          while (h!!.nextHolder != null) {
            h = h.nextHolder
          }
          h!!.nextHolder = nh
        } else {
          detourMap!!.fastPut(id, nh)
        }
      }
    }
  }

  fun buildMap() {
    nodesMap = CompactLongMap()
    for (node in nodes) {
      val id = node.getIdFromPos()
      val nh = OsmPathElementHolder()
      nh.node = node
      var h: OsmPathElementHolder? = nodesMap!!.get(id)
      if (h != null) {
        while (h!!.nextHolder != null) {
          h = h.nextHolder
        }
        h!!.nextHolder = nh
      } else {
        nodesMap!!.fastPut(id, nh)
      }
    }
    nodesMap = FrozenLongMap(nodesMap!!)
  }

  fun aggregateMessages(): List<String> {
    val res = ArrayList<String>()
    var current: MessageData? = null
    for (n in nodes) {
      if (n.message != null && n.message!!.wayKeyValues != null) {
        val md: MessageData = n.message!!.copy()
        if (current != null) {
          if (current.nodeKeyValues != null || current.wayKeyValues!! != md.wayKeyValues) {
            res.add(current.toMessage()!!)
          } else {
            md.add(current)
          }
        }
        current = md
      }
    }
    if (current != null) {
      res.add(current.toMessage()!!)
    }
    return res
  }

  fun aggregateSpeedProfile(): List<String> {
    val res = ArrayList<String>()
    var vmax = -1
    var vmaxe = -1
    var vmin = -1
    var extraTime = 0
    var i = nodes.size - 1
    while (i > 0) {
      val n = nodes[i]
      val m: MessageData? = n.message
      val vnode = getVNode(i)
      if (m != null && (vmax != m.vmax || vmin != m.vmin || vmaxe != m.vmaxExplicit || vnode < m.vmax || extraTime != m.extraTime)) {
        vmax = m.vmax
        vmin = m.vmin
        vmaxe = m.vmaxExplicit
        extraTime = m.extraTime
        res.add(i.toString() + "," + vmaxe + "," + vmax + "," + vmin + "," + vnode + "," + extraTime)
      }
      i--
    }
    return res
  }

  // PORT-CUT writeBinary / readBinary / formatLongs (raw tracks, rawTrackPath is always null)

  fun addNodes(t: OsmTrack) {
    for (n in t.nodes)
      addNode(n)
    buildMap()
  }

  fun containsNode(node: OsmPos): Boolean {
    return nodesMap!!.contains(node.getIdFromPos())
  }

  fun getLink(n1: Long, n2: Long): OsmPathElement? {
    var h: OsmPathElementHolder? = nodesMap!!.get(n2)
    while (h != null) {
      val e1: OsmPathElement? = h.node!!.origin
      if (e1 != null && e1.getIdFromPos() == n1) {
        return h.node
      }
      h = h.nextHolder
    }
    return null
  }

  fun appendTrack(t: OsmTrack) {
    var i = 0

    val ourSize = nodes.size
    if (ourSize > 0 && t.nodes.size > 1) {
      val olde = nodes[ourSize - 1]
      t.nodes[1].origin = olde
    }
    val t0: Float = if (ourSize > 0) nodes[ourSize - 1].getTime() else 0f
    val e0: Float = if (ourSize > 0) nodes[ourSize - 1].getEnergy() else 0f
    val c0: Int = if (ourSize > 0) nodes[ourSize - 1].cost else 0
    i = 0
    while (i < t.nodes.size) {
      val e = t.nodes[i]
      if (i == 0 && ourSize > 0 && nodes[ourSize - 1].getSElev() == Short.MIN_VALUE)
        nodes[ourSize - 1].setSElev(e.getSElev())
      if (i > 0 || ourSize == 0) {
        e.setTime(e.getTime() + t0)
        e.setEnergy(e.getEnergy() + e0)
        e.cost = e.cost + c0
        if (e.message != null) {
          if (!(e.message!!.lon == e.getILon() && e.message!!.lat == e.getILat())) {
            e.message!!.lon = e.getILon()
            e.message!!.lat = e.getILat()
          }
        }
        nodes.add(e)
      }
      i++
    }

    if (t.voiceHints != null) {
      if (ourSize > 0) {
        for (hint in t.voiceHints!!.list) {
          hint.indexInTrack = hint.indexInTrack + ourSize - 1
        }
      }
      if (voiceHints == null) {
        voiceHints = t.voiceHints
      } else {
        voiceHints!!.list.addAll(t.voiceHints!!.list)
      }
    } else {
      if (detourMap == null) {
        //copyDetours( t );
        detourMap = t.detourMap
      } else {
        addDetours(t)
      }
    }

    distance += t.distance
    ascend += t.ascend
    plainAscend += t.plainAscend
    cost += t.cost
    energy = nodes[nodes.size - 1].getEnergy().toInt()

    showspeed = showspeed or t.showspeed
    showSpeedProfile = showSpeedProfile or t.showSpeedProfile
  }

  var distance: Int = 0
  var ascend: Int = 0
  var plainAscend: Int = 0
  var cost: Int = 0
  var energy: Int = 0
  var iternity: List<String>? = null

  fun getVoiceHint(i: Int): VoiceHint? {
    if (voiceHints == null) return null
    for (hint in voiceHints!!.list) {
      if (hint.indexInTrack == i) {
        return hint
      }
    }
    return null
  }

  fun getMatchedWaypoint(idx: Int): MatchedWaypoint? {
    if (matchedWaypoints == null) return null
    for (wp in matchedWaypoints!!) {
      if (idx == wp.indexInTrack) {
        return wp
      }
    }
    return null
  }

  private fun getVNode(i: Int): Int {
    val m1: MessageData? = if (i + 1 < nodes.size) nodes[i + 1].message else null
    val m0: MessageData? = if (i < nodes.size) nodes[i].message else null
    val vnode0 = if (m1 == null) 999 else m1.vnode0
    val vnode1 = if (m0 == null) 999 else m0.vnode1
    return if (vnode0 < vnode1) vnode0 else vnode1
  }

  fun getTotalSeconds(): Int {
    val s: Float = if (nodes.size < 2) 0f else nodes[nodes.size - 1].getTime() - nodes[0].getTime()
    return (s + 0.5).toInt()
  }

  fun equalsTrack(t: OsmTrack): Boolean {
    if (nodes.size != t.nodes.size)
      return false
    for (i in 0 until nodes.size) {
      val e1 = nodes[i]
      val e2 = t.nodes[i]
      if (e1.getILon() != e2.getILon() || e1.getILat() != e2.getILat())
        return false
    }
    return true
  }

  fun getFromDetourMap(id: Long): OsmPathElementHolder? {
    if (detourMap == null)
      return null
    return detourMap!!.get(id)
  }

  fun prepareSpeedProfile(rc: RoutingContext) {
    // sendSpeedProfile = rc.keyValues != null && rc.keyValues.containsKey( "vmax" );
  }

  fun processVoiceHints(rc: RoutingContext) {
    voiceHints = VoiceHintList()
    voiceHints!!.setTransportMode(rc.carMode, rc.bikeMode)
    voiceHints!!.turnInstructionMode = rc.turnInstructionMode

    if (detourMap == null && !rc.hasDirectRouting) {
      // only when no direct way points
      return
    }
    var nodeNr = nodes.size - 1
    var i = nodeNr
    var node: OsmPathElement? = nodes[nodeNr]
    while (node != null) {
      node = node.origin
    }

    i = 0

    node = nodes[nodeNr]
    val inputs = ArrayList<VoiceHint>()
    while (node != null) {
      if (node.origin != null) {
        if (nodeNr == nodes.size - 1) {
          val input = VoiceHint()
          inputs.add(0, input)
          input.ilat = node.getILat()
          input.ilon = node.getILon()
          input.selev = node.getSElev()
          input.goodWay = node.message
          input.oldWay = node.message
          input.indexInTrack = nodes.size - 1
          input.cmd = VoiceHint.END
        }
        val input = VoiceHint()
        inputs.add(input)
        input.ilat = node.origin!!.getILat()
        input.ilon = node.origin!!.getILon()
        input.selev = node.origin!!.getSElev()
        --nodeNr
        input.indexInTrack = nodeNr
        input.goodWay = node.message
        input.oldWay = if (node.origin!!.message == null) node.message else node.origin!!.message
        if (rc.turnInstructionMode == 8 ||
          rc.turnInstructionMode == 4 ||
          rc.turnInstructionMode == 2 ||
          rc.turnInstructionMode == 9) {
          val mwpt: MatchedWaypoint? = getMatchedWaypoint(nodeNr)
          if (mwpt != null && mwpt.wpttype == MatchedWaypoint.WAYPOINT_TYPE_DIRECT) {
            input.cmd = VoiceHint.BL
            input.angle = (if (nodeNr == 0) node.origin!!.message!!.turnangle else node.message!!.turnangle)
            input.distanceToNext = node.calcDistance(node.origin!!).toDouble()
          }
        }
        if (detourMap != null) {
          val detours: OsmPathElementHolder? = detourMap!!.get(node.origin!!.getIdFromPos())
          if (nodeNr >= 0 && detours != null) {
            var h: OsmPathElementHolder? = detours
            while (h != null) {
              val e: OsmPathElement = h.node!!
              input.addBadWay(startSection(e, node.origin!!))
              h = h.nextHolder
            }
          }
        }
        /* else if (nodeNr == 0 && detours != null) {
          OsmPathElementHolder h = detours;
          OsmPathElement e = h.node;
          input.addBadWay(startSection(e, e));
        } */
      }
      node = node.origin
    }

    val transportMode = voiceHints!!.transportMode()
    val vproc = VoiceHintProcessor(rc.turnInstructionCatchingRange, rc.turnInstructionRoundabouts, transportMode)
    val results: List<VoiceHint> = vproc.process(inputs)

    val minDistance: Double = getMinDistance().toDouble()
    val resultsLast: List<VoiceHint> = vproc.postProcess(results, rc.turnInstructionCatchingRange, minDistance)
    for (hint in resultsLast) {
      voiceHints!!.list.add(hint)
    }

  }

  fun getMinDistance(): Int {
    if (voiceHints != null) {
      when (voiceHints!!.transportMode()) {
        VoiceHintList.TRANS_MODE_CAR -> return 20
        VoiceHintList.TRANS_MODE_FOOT -> return 3
        VoiceHintList.TRANS_MODE_BIKE -> return 5
        else -> return 5
      }
    }
    return 2
  }

  fun getVoiceHintTime(i: Int): Float {
    if (!voiceHints!!.list.isEmpty() && i < voiceHints!!.list.size) {
      return voiceHints!!.list[i].getTime()
    }
    if (nodes.isEmpty()) {
      return 0f
    }
    return nodes[nodes.size - 1].getTime()
  }

  fun removeVoiceHint(i: Int) {
    if (voiceHints != null) {
      var remove: VoiceHint? = null
      for (vh in voiceHints!!.list) {
        if (vh.indexInTrack == i)
          remove = vh
      }
      if (remove != null)
        voiceHints!!.list.remove(remove)
    }
  }

  private fun startSection(element: OsmPathElement, root: OsmPathElement): MessageData? {
    var e: OsmPathElement? = element
    var cnt = 0
    while (e != null && e.origin != null) {
      if (e.origin!!.getILat() == root.getILat() && e.origin!!.getILon() == root.getILon()) {
        return e.message
      }
      e = e.origin
      if (cnt++ == 1000000) {
        throw IllegalArgumentException("ups: " + root + "->" + element)
      }
    }
    return null
  }

  companion object {
    // PORT-NOTE version came from the jar manifest (Implementation-Version); constant here
    val version: String = "1.7.11-beta-onpath1"

    // csv-header-line
    private const val MESSAGES_HEADER = "Longitude\tLatitude\tElevation\tDistance\tCostPerKm\tElevCost\tTurnCost\tNodeCost\tInitialCost\tWayTags\tNodeTags\tTime\tEnergy"
  }
}
