// Port of btools.mapaccess.OsmPos (BRouter 1.7.11-beta, MIT)
package btools.mapaccess

/**
 * Interface for a position (OsmNode or OsmPath)
 */
interface OsmPos {
  fun getILat(): Int

  fun getILon(): Int

  fun getSElev(): Short

  fun getElev(): Double

  fun calcDistance(p: OsmPos): Int

  fun getIdFromPos(): Long
}
