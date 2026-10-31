package com.onpath.app.routing

import com.onpath.app.sync.Sha256

/**
 * A short fingerprint of what turns a plan into a route – besides the map's routing data (the rd5 files, shown with
 * their own dates): the BRouter version, the profiles and lookups.dat the app routes with, and [PLANNER_VERSION] for
 * our own code around BRouter (Planner, WaypointSnap, the profile parameters BRouterEngine sets). The same plan with
 * the same fingerprint and the same rd5 dates gives the same route – in the app and in the web planner (owner: routes
 * identical in both). Shown in Settings → Diagnostics and carried in synced plan items (fields 7 / 8,
 * docs/design/routing-fingerprint.md "Sync fields").
 *
 * Pure, no Android and no java.security (the web planner compiles it: [Sha256] is the sync's own, TeaVM-safe). Style
 * as [RouterClassHash]: "r1-" + the first 12 hex digits of sha256 over a header ([BROUTER_VERSION], [PLANNER_VERSION])
 * and, per file of [FILES] in that order, its name, its length and its bytes without '\r' (a checkout with CRLF line
 * ends must not look like other profiles). Comments count: a profile whose comments changed is a new profile version.
 * `RoutingFingerprintTest` checks [BROUTER_VERSION] against the jars in app/libs and [FILES] against assets/brouter.
 */
object RoutingFingerprint {
    /** The BRouter jars in app/libs (tools/routing/build_brouter_jars.sh). */
    const val BROUTER_VERSION = "1.7.11-beta"

    /**
     * Our routing code's version: raise it with every change that can give another route for the same points,
     * activity and data (Planner passes and splits, WaypointSnap rules, BRouterEngine's profile parameters / via
     * handling). Not raised for refactors that keep every route the same.
     */
    const val PLANNER_VERSION = 1

    /** The files BRouter routes with (assets/brouter), in hash order. */
    val FILES = listOf("lookups.dat", "hiking-mountain.brf", "mtb.brf", "touring.brf", "trekking.brf")

    private val FORMAT_1 = Regex("^r1-[0-9a-f]{12}$")

    /** A fingerprint of the format this code computes; anything else (a newer format, garbage) is not comparable. */
    fun isComparable(fp: String?): Boolean = fp != null && FORMAT_1.matches(fp)

    /** The fingerprint of the files [read] gives by name (assets/brouter/<name> in the app, the served files in the web). */
    fun of(read: (String) -> ByteArray, brouter: String = BROUTER_VERSION, planner: Int = PLANNER_VERSION): String {
        val out = java.io.ByteArrayOutputStream(1 shl 17)
        fun add(b: ByteArray) = out.write(b, 0, b.size)
        add("onpath-routing\nbrouter=$brouter\nplanner=$planner\n".encodeToByteArray())
        for (f in FILES) {
            val bytes = read(f).filter { it != '\r'.code.toByte() }.toByteArray()
            add("$f\n${bytes.size}\n".encodeToByteArray())
            add(bytes)
        }
        return "r1-" + Sha256.hash(out.toByteArray()).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }.take(12)
    }

    /**
     * The Diagnostics line: "r1-… · BRouter 1.7.11-beta · planner 1 · data E10_N45 2026-10-20, E5_N45 –" – [rd5] = the
     * routing files on the phone (name → BRouter's creation time in ms, null = unknown / old file without a tail).
     */
    fun describe(fingerprint: String, rd5: List<Pair<String, Long?>>): String {
        val data = if (rd5.isEmpty()) "no routing data"
            else "data " + rd5.sortedBy { it.first }.joinToString(", ") { (n, t) -> n.removeSuffix(".rd5") + " " + (t?.let(::day) ?: "–") }
        return "$fingerprint · BRouter $BROUTER_VERSION · planner $PLANNER_VERSION · $data"
    }

    /** UTC day of [ms] as yyyy-mm-dd (pure: no java.time, the web compiles this file). */
    internal fun day(ms: Long): String {
        // days since 1970-01-01 → civil date (Howard Hinnant's days_from_civil inverse)
        val z = Math.floorDiv(ms, 86_400_000L) + 719_468
        val era = Math.floorDiv(z, 146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + if (m <= 2) 1 else 0
        return "$y-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
    }
}
