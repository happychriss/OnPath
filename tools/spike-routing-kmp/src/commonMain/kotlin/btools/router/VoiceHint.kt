/**
 * Container for a voice hint
 * (both input- and result data for voice hint processing)
 *
 * @author ab
 */
package btools.router

class VoiceHint {
  var ilon: Int = 0
  var ilat: Int = 0
  var selev: Short = 0
  var cmd: Int = 0
  var oldWay: MessageData? = null
  var goodWay: MessageData? = null
  var badWays: ArrayList<MessageData>? = null
  var distanceToNext: Double = 0.0
  var indexInTrack: Int = 0

  fun getTime(): Float {
    return if (oldWay == null) 0.0f else oldWay!!.time
  }

  var angle: Float = Float.MAX_VALUE
  var lowerBadWayAngle: Float = -181f
  var higherBadWayAngle: Float = 181f

  var turnAngleConsumed: Boolean = false
  var needsRealTurn: Boolean = false
  var maxBadPrio: Int = -1

  var roundaboutExit: Int = 0

  fun isRoundabout(): Boolean {
    return roundaboutExit != 0
  }

  fun addBadWay(badWay: MessageData?) {
    if (badWay == null) {
      return
    }
    if (badWays == null) {
      badWays = ArrayList()
    }
    badWays!!.add(badWay)
  }

  fun getExitNumber(): Int {
    return roundaboutExit
  }

  fun calcCommand() {
    if (badWays != null) {
      for (badWay in badWays!!) {
        if (badWay.isBadOneway()) {
          continue
        }
        if (lowerBadWayAngle < badWay.turnangle && badWay.turnangle < goodWay!!.turnangle) {
          lowerBadWayAngle = badWay.turnangle
        }
        if (higherBadWayAngle > badWay.turnangle && badWay.turnangle > goodWay!!.turnangle) {
          higherBadWayAngle = badWay.turnangle
        }
      }
    }

    var cmdAngle: Float = angle

    // fall back to local angle if otherwise inconsistent
    //if ( lowerBadWayAngle > angle || higherBadWayAngle < angle )
    //{
    //cmdAngle = goodWay.turnangle;
    //}
    if (angle == Float.MAX_VALUE) {
      cmdAngle = goodWay!!.turnangle
    }
    if (cmd == BL) return

    if (roundaboutExit > 0) {
      cmd = RNDB
    } else if (roundaboutExit < 0) {
      cmd = RNLB
    } else if (is180DegAngle(cmdAngle) && cmdAngle <= -179.0f && higherBadWayAngle == 181.0f && lowerBadWayAngle == -181.0f) {
      cmd = TU
    } else if (cmdAngle < -159.0f) {
      cmd = TLU
    } else if (cmdAngle < -135.0f) {
      cmd = TSHL
    } else if (cmdAngle < -45.0f) {
      // a TL can be pushed in either direction by a close-by alternative
      if (cmdAngle < -95.0f && higherBadWayAngle < -30.0f && lowerBadWayAngle < -180.0f) {
        cmd = TSHL
      } else if (cmdAngle > -85.0f && lowerBadWayAngle > -180.0f && higherBadWayAngle > -10.0f) {
        cmd = TSLL
      } else {
        if (cmdAngle < -110.0f) {
          cmd = TSHL
        } else if (cmdAngle > -60.0f) {
          cmd = TSLL
        } else {
          cmd = TL
        }
      }
    } else if (cmdAngle < -21.0f) {
      if (cmd != KR) { // don't overwrite KR with TSLL
        cmd = TSLL
      }
    } else if (cmdAngle < -5.0f) {
      if (lowerBadWayAngle < -100.0f && higherBadWayAngle < 45.0f) {
        cmd = TSLL
      } else if (lowerBadWayAngle >= -100.0f && higherBadWayAngle < 45.0f) {
        cmd = KL
      } else {
        if (lowerBadWayAngle > -35.0f && higherBadWayAngle > 55.0f) {
          cmd = KR
        } else {
          cmd = C
        }
      }
    } else if (cmdAngle < 5.0f) {
      if (lowerBadWayAngle > -30.0f) {
        cmd = KR
      } else if (higherBadWayAngle < 30.0f) {
        cmd = KL
      } else {
        cmd = C
      }
    } else if (cmdAngle < 21.0f) {
      // a TR can be pushed in either direction by a close-by alternative
      if (lowerBadWayAngle > -45.0f && higherBadWayAngle > 100.0f) {
        cmd = TSLR
      } else if (lowerBadWayAngle > -45.0f && higherBadWayAngle <= 100.0f) {
        cmd = KR
      } else {
        if (lowerBadWayAngle < -55.0f && higherBadWayAngle < 35.0f) {
          cmd = KL
        } else {
          cmd = C
        }
      }
    } else if (cmdAngle < 45.0f) {
      cmd = TSLR
    } else if (cmdAngle < 135.0f) {
      if (cmdAngle < 85.0f && higherBadWayAngle < 180.0f && lowerBadWayAngle < 10.0f) {
        cmd = TSLR
      } else if (cmdAngle > 95.0f && lowerBadWayAngle > 30.0f && higherBadWayAngle > 180.0f) {
        cmd = TSHR
      } else {
        if (cmdAngle.toDouble() > 110.0) {
          cmd = TSHR
        } else if (cmdAngle.toDouble() < 60.0) {
          cmd = TSLR
        } else {
          cmd = TR
        }
      }
    } else if (cmdAngle < 159.0f) {
      cmd = TSHR
    } else if (is180DegAngle(cmdAngle) && cmdAngle >= 179.0f && higherBadWayAngle == 181.0f && lowerBadWayAngle == -181.0f) {
      cmd = TU
    } else {
      cmd = TRU
    }
  }

  fun formatGeometry(): String {
    val oldPrio: Float = if (oldWay == null) 0.0f else oldWay!!.priorityclassifier.toFloat()
    val sb = StringBuilder(30)
    sb.append(' ').append(oldPrio.toInt())
    appendTurnGeometry(sb, goodWay!!)
    if (badWays != null) {
      for (badWay in badWays!!) {
        sb.append(" ")
        appendTurnGeometry(sb, badWay)
      }
    }
    return sb.toString()
  }

  private fun appendTurnGeometry(sb: StringBuilder, msg: MessageData) {
    sb.append("(").append((msg.turnangle + 0.5).toInt()).append(")").append(msg.priorityclassifier)
  }

  fun hasGiveWay(): Boolean {
    if (oldWay != null && oldWay!!.nodeKeyValues != null) {
      if (oldWay!!.wayKeyValues!!.contains("reversedirection=yes")) {
        return (oldWay!!.nodeKeyValues!!.contains("highway=give_way") || oldWay!!.nodeKeyValues!!.contains("highway=stop")) && oldWay!!.nodeKeyValues!!.contains("direction=backward")
      } else {
        return (oldWay!!.nodeKeyValues!!.contains("highway=give_way") || oldWay!!.nodeKeyValues!!.contains("highway=stop")) && !oldWay!!.nodeKeyValues!!.contains("direction=backward")
      }
    }
    return false

  }

  companion object {
    const val C = 1 // continue (go straight)
    const val TL = 2 // turn left
    const val TSLL = 3 // turn slightly left
    const val TSHL = 4 // turn sharply left
    const val TR = 5 // turn right
    const val TSLR = 6 // turn slightly right
    const val TSHR = 7 // turn sharply right
    const val KL = 8 // keep left
    const val KR = 9 // keep right
    const val TLU = 10 // U-turn
    const val TRU = 11 // Right U-turn
    const val OFFR = 12 // Off route
    const val RNDB = 13 // Roundabout
    const val RNLB = 14 // Roundabout left
    const val TU = 15 // 180 degree u-turn
    const val BL = 16 // Beeline routing
    const val EL = 17 // exit left
    const val ER = 18 // exit right

    const val END = 100 // end point

    fun is180DegAngle(angle: Float): Boolean {
      return (kotlin.math.abs(angle) <= 180.0f && kotlin.math.abs(angle) >= 179.0f)
    }
  }
}
