package btools.router

import btools.expressions.BExpressionContext

class AreaInfo(dir: Int) {
  var direction: Int = 0
  var numForest: Int = -1
  var numRiver: Int = -1

  var polygon: OsmNogoPolygon? = null

  var ways: Int = 0
  var greenWays: Int = 0
  var riverWays: Int = 0
  var elevStart: Double = 0.0
  var elev50: Int = 0

  init {
    direction = dir
  }

  fun checkAreaInfo(expctxWay: BExpressionContext, elev: Double, ab: ByteArray) {
    ways++

    val test = elevStart - elev
    if (kotlin.math.abs(test) < 50) elev50++

    val ld2: IntArray = expctxWay.createNewLookupData()
    expctxWay.decode(ld2, false, ab)

    if (numForest != -1 && ld2[numForest] > 1) {
      greenWays++
    }

    if (numRiver != -1 && ld2[numRiver] > 1) {
      riverWays++
    }

  }

  fun getElev50Weight(): Int {
    if (ways == 0) return 0
    return (elev50 * 100.0 / ways).toInt()
  }

  fun getGreen(): Int {
    if (ways == 0) return 0
    return (greenWays * 100.0 / ways).toInt()
  }

  fun getRiver(): Int {
    if (ways == 0) return 0
    return (riverWays * 100.0 / ways).toInt()
  }

  override fun toString(): String {
    val sb = StringBuilder()
    sb.append("Area ").append(direction).append(" ").append(elevStart).append("m ways ").append(ways)
    if (ways > 0) {
      sb.append("\nArea ways <50m  ").append(elev50).append(" ").append(getElev50Weight()).append("%")
      sb.append("\nArea ways green ").append(greenWays).append(" ").append(getGreen()).append("%")
      sb.append("\nArea ways river ").append(riverWays).append(" ").append(getRiver()).append("%")
    }
    return sb.toString()
  }

  companion object {
    const val RESULT_TYPE_NONE = 0
    const val RESULT_TYPE_ELEV50 = 1
    const val RESULT_TYPE_GREEN = 4
    const val RESULT_TYPE_RIVER = 5
  }
}
