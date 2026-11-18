/**
 * static helper class for handling datafiles
 *
 * @author ab
 */
package btools.router

import btools.mapaccess.OsmNode

class SearchBoundary {

  private var minlon0 = 0
  private var minlat0 = 0
  private var maxlon0 = 0
  private var maxlat0 = 0

  private var minlon = 0
  private var minlat = 0
  private var maxlon = 0
  private var maxlat = 0
  private var radius = 0
  private var p: OsmNode? = null

  var direction = 0

  /**
   * @param radius Search radius in meters.
   */
  constructor(n: OsmNode, radius: Int, direction: Int) {
    this.radius = radius
    this.direction = direction

    p = OsmNode(n.ilon, n.ilat)

    val lon = (n.ilon / 5000000) * 5000000
    val lat = (n.ilat / 5000000) * 5000000

    minlon0 = lon - 5000000
    minlat0 = lat - 5000000
    maxlon0 = lon + 10000000
    maxlat0 = lat + 10000000

    minlon = lon - 1000000
    minlat = lat - 1000000
    maxlon = lon + 6000000
    maxlat = lat + 6000000
  }

  companion object {
    fun getFileName(n: OsmNode): String {
      val lon = (n.ilon / 5000000) * 5000000
      val lat = (n.ilat / 5000000) * 5000000

      val dlon = lon / 1000000 - 180
      val dlat = lat / 1000000 - 90

      val slon = if (dlon < 0) "W" + (-dlon) else "E" + dlon
      val slat = if (dlat < 0) "S" + (-dlat) else "N" + dlat
      return slon + "_" + slat + ".trf"
    }
  }

  fun isInBoundary(n: OsmNode, cost: Int): Boolean {
    if (radius > 0) {
      return n.calcDistance(p!!) < radius
    }
    if (cost == 0) {
      return n.ilon > minlon0 && n.ilon < maxlon0 && n.ilat > minlat0 && n.ilat < maxlat0
    }
    return n.ilon > minlon && n.ilon < maxlon && n.ilat > minlat && n.ilat < maxlat
  }

  fun getBoundaryDistance(n: OsmNode): Int {
    when (direction) {
      0 -> return n.calcDistance(OsmNode(n.ilon, minlat))
      1 -> return n.calcDistance(OsmNode(minlon, n.ilat))
      2 -> return n.calcDistance(OsmNode(n.ilon, maxlat))
      3 -> return n.calcDistance(OsmNode(maxlon, n.ilat))
      else -> throw IllegalArgumentException("undefined direction: " + direction)
    }
  }

}
