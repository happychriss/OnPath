package btools.router

import btools.mapaccess.OsmNode
import btools.mapaccess.OsmPos
import btools.util.CheapRuler

/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */
class OsmPathElement : OsmPos {
  private var ilat: Int = 0 // latitude
  private var ilon: Int = 0 // longitude
  private var selev: Short = 0 // longitude

  var message: MessageData? = null // description

  var cost: Int = 0

  // interface OsmPos
  override fun getILat(): Int {
    return ilat
  }

  override fun getILon(): Int {
    return ilon
  }

  override fun getSElev(): Short {
    return selev
  }

  fun setSElev(s: Short) {
    selev = s
  }

  override fun getElev(): Double {
    return selev.toDouble() / 4.0
  }

  fun getTime(): Float {
    return if (message == null) 0.0f else message!!.time
  }

  fun setTime(t: Float) {
    if (message != null) {
      message!!.time = t
    }
  }

  fun getEnergy(): Float {
    return if (message == null) 0.0f else message!!.energy
  }

  fun setEnergy(e: Float) {
    if (message != null) {
      message!!.energy = e
    }
  }

  fun setAngle(e: Float) {
    if (message != null) {
      message!!.turnangle = e
    }
  }

  override fun getIdFromPos(): Long {
    return (ilon.toLong() shl 32) or ilat.toLong()
  }

  override fun calcDistance(p: OsmPos): Int {
    // Math.round(double) -> long, widened to double for Math.max
    return maxOf(1.0, kotlin.math.floor(CheapRuler.distance(ilon, ilat, p.getILon(), p.getILat()) + 0.5).toLong().toDouble()).toInt()
  }

  var origin: OsmPathElement? = null

  override fun toString(): String {
    return ilon.toString() + "_" + ilat
  }

  fun positionEquals(e: OsmPathElement): Boolean {
    return this.ilat == e.ilat && this.ilon == e.ilon
  }

  // PORT-CUT writeToStream / readFromStream (raw tracks)

  companion object {
    // construct a path element from a path
    fun create(path: OsmPath): OsmPathElement {
      val n: OsmNode = path.getTargetNode()!!
      val pe = create(n.getILon(), n.getILat(), n.getSElev(), path.originElement)
      pe.cost = path.cost
      pe.message = path.message
      return pe
    }

    fun create(ilon: Int, ilat: Int, selev: Short, origin: OsmPathElement?): OsmPathElement {
      val pe = OsmPathElement()
      pe.ilon = ilon
      pe.ilat = ilat
      pe.selev = selev
      pe.origin = origin
      return pe
    }
  }
}
