package com.onpath.app.time

import com.onpath.app.routing.Activity

/**
 * The one time model of OnPath (docs/plan-time-estimate.md §3): seconds for a short piece of route from its length and
 * its height change (DEM elevation of the route, never GPS altitude). Pure Kotlin, JVM-tested.
 *
 * - Hiking: gradient table "B" (minutes per horizontal km, linear in between): 4 km/h on the flat, gentle descents a bit
 *   faster, steady climbs ~300–340 m/h, steep descents ~500–550 m/h and slower again beyond −30 % (knees, steps). Anchored
 *   on the DAV / AVS / SAC signpost rules.
 * - Bikes: a power model (like BRouter's, but computed here so Plan and Navigate agree): rider power by activity, e-bike
 *   assist up to 25 km/h, rolling resistance by surface, speed caps by surface (downhill), pushing (walking speed
 *   × 0.85) as the floor on very steep climbs.
 *
 * All values are for the "Normal" pace; [Pace] and learned [Factors] multiply the result.
 */
object PaceModel {
    /** Length of one model segment (m); gradients are taken over it after light smoothing (see [RouteTimes]). */
    const val SEGMENT_M = 50.0

    /** Learning groups (docs §4.2): down (< −5 %), flat (−5 … +5 %), up (> +5 %). */
    const val DOWN = 0
    const val FLAT = 1
    const val UP = 2
    const val GROUPS = 3
    const val GROUP_LIMIT = 0.05

    fun group(grade: Double): Int = when {
        grade < -GROUP_LIMIT -> DOWN
        grade > GROUP_LIMIT -> UP
        else -> FLAT
    }

    // ---- hiking ----------------------------------------------------------------------------------------------

    private val HIKE_GRADE = doubleArrayOf(-0.50, -0.40, -0.30, -0.20, -0.10, -0.05, 0.0, 0.05, 0.10, 0.15, 0.20, 0.30, 0.40, 0.50)
    private val HIKE_MIN_PER_KM = doubleArrayOf(62.0, 46.0, 32.0, 22.0, 13.5, 13.5, 15.0, 18.5, 24.0, 30.0, 37.0, 54.0, 70.0, 88.0)

    /** Table B: minutes per horizontal km at gradient [grade] (−0.2 = 20 % down). Beyond ±50 % it grows linearly. */
    fun hikeMinPerKm(grade: Double): Double {
        if (grade.isNaN()) return HIKE_MIN_PER_KM[6]
        if (grade <= HIKE_GRADE.first()) return HIKE_MIN_PER_KM.first() * grade / HIKE_GRADE.first()
        if (grade >= HIKE_GRADE.last()) return HIKE_MIN_PER_KM.last() * grade / HIKE_GRADE.last()
        var i = 0
        while (HIKE_GRADE[i + 1] < grade) i++
        val t = (grade - HIKE_GRADE[i]) / (HIKE_GRADE[i + 1] - HIKE_GRADE[i])
        return HIKE_MIN_PER_KM[i] + (HIKE_MIN_PER_KM[i + 1] - HIKE_MIN_PER_KM[i]) * t
    }

    // ---- bikes -----------------------------------------------------------------------------------------------

    /** Rider + bike for one activity: power (W), extra assist power below 25 km/h (W), mass (kg), drag 0.5·ρ·CdA. */
    data class Bike(val riderW: Double, val assistW: Double, val massKg: Double, val drag: Double, val surface: String)

    fun bike(activity: Activity): Bike = when (activity) {
        Activity.BIKE_TOURING -> Bike(120.0, 0.0, 92.0, 0.27, "asphalt")
        Activity.BIKE_TREKKING -> Bike(100.0, 0.0, 95.0, 0.30, "paved")
        Activity.EBIKE -> Bike(100.0, 200.0, 115.0, 0.30, "paved")
        Activity.MTB -> Bike(110.0, 0.0, 92.0, 0.32, "unpaved")
        Activity.EMTB -> Bike(100.0, 200.0, 115.0, 0.32, "unpaved")
        Activity.HIKE -> Bike(100.0, 0.0, 95.0, 0.30, "paved") // not used
    }

    /** Rolling resistance by surface key (RoutingContract [com.onpath.app.routing.Share] keys). */
    fun rollingResistance(surface: String?): Double? = when (surface) {
        "asphalt" -> 0.006
        "paved" -> 0.008
        "gravel" -> 0.012
        "unpaved", "ground" -> 0.020
        "natural_trail" -> 0.030
        "alpine" -> 0.040
        else -> null
    }

    /** Highest speed we assume on a surface (km/h): downhill caps (nobody rides a trail at 40 km/h). */
    fun capKmh(surface: String?): Double? = when (surface) {
        "asphalt" -> 40.0
        "paved" -> 35.0
        "gravel" -> 30.0
        "unpaved" -> 25.0
        "ground" -> 22.0
        "natural_trail" -> 15.0
        "alpine" -> 10.0
        else -> null
    }

    const val ASSIST_LIMIT_MS = 25 / 3.6
    private const val G = 9.81

    /** Steady speed (m/s) on [grade] for [activity] on [surface] (null = the activity's usual surface). */
    fun bikeSpeedMs(grade: Double, activity: Activity, surface: String? = null): Double {
        val b = bike(activity)
        val cr = rollingResistance(surface) ?: rollingResistance(b.surface)!!
        val cap = (capKmh(surface) ?: capKmh(b.surface)!!) / 3.6
        val g = if (grade.isNaN()) 0.0 else grade
        var v = solve(b.riderW, b.massKg, b.drag, cr, g)
        if (b.assistW > 0) v = maxOf(v, minOf(solve(b.riderW + b.assistW, b.massKg, b.drag, cr, g), ASSIST_LIMIT_MS))
        return v.coerceAtMost(cap).coerceAtLeast(pushSpeedMs(g))
    }

    /** Pushing the bike: a bit slower than walking the same slope (table B × 1/0.85). */
    fun pushSpeedMs(grade: Double): Double = 1000 / (hikeMinPerKm(grade) * 60) * PUSH_FACTOR

    private const val PUSH_FACTOR = 0.85

    /** Solves drag·v³ + m·g·(cr + grade)·v = power for v > 0 (exactly one positive root; bisection). */
    private fun solve(power: Double, mass: Double, drag: Double, cr: Double, grade: Double): Double {
        val k = mass * G * (cr + grade)
        var lo = 0.0; var hi = 40.0
        repeat(48) {
            val m = (lo + hi) / 2
            if (drag * m * m * m + k * m - power > 0) hi = m else lo = m
        }
        return (lo + hi) / 2
    }

    // ---- one segment -----------------------------------------------------------------------------------------

    /** Seconds for [lenM] horizontal metres with height change [dz] (Normal pace, no learned factors). */
    fun seconds(lenM: Double, dz: Double, activity: Activity, surface: String? = null): Double {
        if (lenM <= 0 || lenM.isNaN()) return 0.0
        val grade = if (dz.isNaN()) 0.0 else dz / lenM
        return when (activity) {
            Activity.HIKE -> hikeMinPerKm(grade) * 60 * lenM / 1000
            else -> lenM / bikeSpeedMs(grade, activity, surface)
        }
    }
}

/** Pace choice in Settings (F-ROUTE-03): a factor on time only, the route stays the same. */
enum class Pace(val label: String, val factor: Double) {
    RELAXED("Relaxed", 1.25),
    NORMAL("Normal", 1.0),
    FIT("Fit", 0.8);

    companion object {
        fun of(name: String?): Pace = entries.firstOrNull { it.name == name } ?: NORMAL
    }
}

/** Time factors per learning group (down / flat / up); 1 = the model as it is. */
data class Factors(val down: Double = 1.0, val flat: Double = 1.0, val up: Double = 1.0) {
    operator fun get(g: Int): Double = when (g) { PaceModel.DOWN -> down; PaceModel.FLAT -> flat; else -> up }
    operator fun times(k: Double) = Factors(down * k, flat * k, up * k)
    operator fun times(o: Factors) = Factors(down * o.down, flat * o.flat, up * o.up)
    fun toArray() = doubleArrayOf(down, flat, up)

    companion object {
        val ONE = Factors()
        fun of(a: DoubleArray) = Factors(a[0], a[1], a[2])
    }
}
