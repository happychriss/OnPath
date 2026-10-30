package com.onpath.app.routing

import com.onpath.strings.*

/**
 * What to do at a junction of a routed line – from BRouter's turn instructions, which it computes only where other
 * ways branch off (a bend of a mountain path is no junction). Stored on the route point of the junction
 * ([GeoPoint.turn]); navigation vibrates before it and the lock screen names it.
 */
enum class JunctionCue(val code: String, private val res: org.jetbrains.compose.resources.StringResource, val side: Side) {
    LEFT("TL", Res.string.nav_dir_left, Side.LEFT),
    SLIGHT_LEFT("TSLL", Res.string.nav_dir_slight_left, Side.LEFT),
    SHARP_LEFT("TSHL", Res.string.nav_dir_sharp_left, Side.LEFT),
    KEEP_LEFT("KL", Res.string.nav_dir_keep_left, Side.LEFT),
    RIGHT("TR", Res.string.nav_dir_right, Side.RIGHT),
    SLIGHT_RIGHT("TSLR", Res.string.nav_dir_slight_right, Side.RIGHT),
    SHARP_RIGHT("TSHR", Res.string.nav_dir_sharp_right, Side.RIGHT),
    KEEP_RIGHT("KR", Res.string.nav_dir_keep_right, Side.RIGHT),
    U_TURN_LEFT("TLU", Res.string.nav_dir_turn_around, Side.LEFT),
    U_TURN_RIGHT("TRU", Res.string.nav_dir_turn_around, Side.RIGHT),
    ROUNDABOUT("RNDB", Res.string.nav_dir_roundabout, Side.RIGHT);

    /** "Keep left" in the app's language (lock screen). */
    val text: String get() = Txt.s(res)

    /** With the roundabout exit: "Roundabout · exit 2" (owner); else [text]. */
    fun text(exit: Int): String =
        if (this == ROUNDABOUT && exit > 0) Txt.s(Res.string.nav_roundabout_exit, exit) else text

    enum class Side { LEFT, RIGHT }

    companion object {
        fun ofCode(code: String?): JunctionCue? = entries.firstOrNull { it.code == code }

        /**
         * BRouter's VoiceHint command → cue; null for "straight on", "off route", "end", beeline (no junction to name).
         * Constants of btools.router.VoiceHint (1.7.11): C 1, TL 2, TSLL 3, TSHL 4, TR 5, TSLR 6, TSHR 7, KL 8, KR 9,
         * TLU 10, TRU 11, OFFR 12, RNDB 13, RNLB 14, TU 15, BL 16, EL 17, ER 18, END 100.
         */
        fun ofBRouter(cmd: Int): JunctionCue? = when (cmd) {
            2 -> LEFT; 3 -> SLIGHT_LEFT; 4 -> SHARP_LEFT; 8, 17 -> KEEP_LEFT
            5 -> RIGHT; 6 -> SLIGHT_RIGHT; 7 -> SHARP_RIGHT; 9, 18 -> KEEP_RIGHT
            10, 15 -> U_TURN_LEFT; 11 -> U_TURN_RIGHT
            13, 14 -> ROUNDABOUT
            else -> null
        }

        /**
         * Puts BRouter's hints ({index, cmd, angle, exit, branches…} rows, btools.router.OnPathHints) on the route
         * points; unknown indices are skipped.
         */
        fun apply(points: MutableList<GeoPoint>, hints: Array<DoubleArray>) {
            for (h in hints) {
                if (h.size < 2) continue
                val i = h[0].toInt()
                val cue = ofBRouter(h[1].toInt()) ?: continue
                val exit = if (cue == ROUNDABOUT && h.size >= 4) kotlin.math.abs(h[3].toInt()) else 0
                val angle = if (h.size >= 3) h[2].toFloat() else 0f
                if (i in points.indices) points[i] = points[i].copy(turn = cue, exit = exit, turnAngle = angle,
                    shape = JunctionShape.ofRow(h))
            }
        }
    }
}

/**
 * A junction as the route meets it, from BRouter's hint (btools.router.OnPathHints): [angle] = the way the route takes
 * (degrees, > 0 right, 0 = straight on, relative to the way it comes on), [inPrio] / [outPrio] = road class of the way it
 * comes on / goes on (the profile's priorityclassifier: 30 motorway … 26 primary … 22 tertiary, 20 unclassified, 6
 * residential / service / cycleway, 4 path / footway / track, 2 steps / pedestrian), [others] = the other ways one could
 * take there (oneways against the direction and ways without access left out). The bike's "could one go wrong here"
 * check ([com.onpath.app.navigation.CueRule]) reads it.
 */
data class JunctionShape(val angle: Float, val inPrio: Int, val outPrio: Int, val others: List<Branch>) {
    data class Branch(val angle: Float, val prio: Int)

    /** Compact text for the navigation session file: "angle,in,out" then ";angle,prio" per other way. */
    fun encode(): String = buildString {
        append(Math.round(angle)).append(',').append(inPrio).append(',').append(outPrio)
        others.forEach { append(';').append(Math.round(it.angle)).append(',').append(it.prio) }
    }

    companion object {
        /** BRouter's cost factor of a way without access (10000; a little below for float rounding). */
        const val NO_ACCESS_COST = 9_999.0

        /** From an OnPathHints row; null when it has no branch data (old row, hint without ways). */
        fun ofRow(h: DoubleArray): JunctionShape? {
            if (h.size < 8) return null
            val n = h[7].toInt()
            if (n < 0 || h.size < 8 + 4 * n) return null
            val others = (0 until n).mapNotNull { k ->
                val o = 8 + 4 * k
                if (h[o + 2] != 0.0 || h[o + 3] >= NO_ACCESS_COST) null else Branch(h[o].toFloat(), h[o + 1].toInt())
            }
            return JunctionShape(h[4].toFloat(), h[5].toInt(), h[6].toInt(), others)
        }

        /** Reads [encode]; null when unreadable (the cue then falls back to the plain rule). */
        fun decode(s: String?): JunctionShape? = try {
            if (s.isNullOrEmpty()) null else {
                val parts = s.split(';')
                val head = parts[0].split(',').map { it.trim().toInt() }
                JunctionShape(head[0].toFloat(), head[1], head[2], parts.drop(1).map { b ->
                    val v = b.split(',').map { it.trim().toInt() }
                    Branch(v[0].toFloat(), v[1])
                })
            }
        } catch (e: RuntimeException) {
            // expected: an odd value = no shape; the junction is then cued as before (never fewer cues by mistake)
            null
        }
    }
}
