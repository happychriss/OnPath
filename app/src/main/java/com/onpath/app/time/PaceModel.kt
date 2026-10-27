package com.onpath.app.time

import com.onpath.strings.*

import com.onpath.app.routing.Activity
import com.onpath.app.routing.WayAttrs

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
 * - Way attributes (P4, [WayAttrs] per span of a routed line): hiking gets a factor for SAC grade, rough ground, steps
 *   and faint trails ([hikeWayFactor]); bikes ride on the real surface, slower on bad smoothness / MTB scale and push
 *   where bikes are walked ([ride]). Unknown way (null) = exactly the times without attributes.
 *
 * All values are for the "Average" pace; [Pace] and learned [Factors] multiply the result.
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
        // touring = a normal bike like trekking (review 2026-10-25: 120 W gave ~24 km/h, road-bike like; now ~21.7 km/h on
        // its usual asphalt, the same as trekking on the same surface)
        Activity.BIKE_TOURING -> Bike(100.0, 0.0, 95.0, 0.30, "asphalt")
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

    // ---- way attributes (P4, docs/plan-time-estimate.md "P4") ------------------------------------------------

    /**
     * Hiking time factor by SAC grade (index 0 = not tagged, 1..6 = T1..T6). Table B already has the slope; this is the
     * extra time for the ground itself: T1/T2 as the signposts (table B is anchored on them), T3 sure-footedness on
     * rocky paths, T4 hands sometimes needed, T5/T6 often pathless (Tobler's off-path ×1/0.6 = 1.67 as the upper end).
     */
    private val SAC_FACTOR = doubleArrayOf(1.0, 1.0, 1.0, 1.15, 1.35, 1.6, 1.67)
    private const val HIKE_FACTOR_MAX = 1.8

    /** Hiking: time factor for the way (1 = table B as it is). */
    fun hikeWayFactor(w: WayAttrs?): Double {
        if (w == null) return 1.0
        var f = SAC_FACTOR[w.sac.coerceIn(0, 6)]
        if (w.rough != null) f = maxOf(f, 1.15) // rock / scree underfoot
        if (w.wayType == "steps") f = maxOf(f, 1.15)
        f *= when (w.visibility) {
            "bad" -> 1.1
            "horrible" -> 1.2
            "no" -> 1.25
            else -> 1.0 // excellent, good, intermediate, not tagged
        }
        return minOf(f, HIKE_FACTOR_MAX)
    }

    /**
     * How a bike gets along one span: rolling resistance, top speed (km/h) and whether the bike is pushed
     * ([pushFactor] > 0 = walking speed × 0.85 × this; carried up / down steps = 0.5).
     */
    data class Ride(val cr: Double, val capKmh: Double, val pushFactor: Double = 0.0)

    /** [Ride] for [activity] on way [w] (null = the activity's usual surface: the model without attributes). */
    fun ride(activity: Activity, w: WayAttrs?): Ride {
        val b = bike(activity)
        val key = w?.surface?.takeIf { rollingResistance(it) != null } ?: b.surface
        var cr = rollingResistance(key)!!
        var cap = capKmh(key)!!
        if (w == null) return Ride(cr, cap)
        if (w.wayType == "steps") return Ride(cr, cap, 0.5)
        val mtb = activity == Activity.MTB || activity == Activity.EMTB
        fun limit(c: Double, k: Double) { cr = maxOf(cr, c); cap = minOf(cap, k) }
        // smoothness is defined by the vehicle that still gets through (OSM wiki Key:smoothness)
        when (w.smoothness) {
            "intermediate" -> limit(0.0, 25.0)
            "bad", "robust_wheels" -> if (mtb) limit(0.0, 20.0) else limit(0.025, 15.0)
            "very_bad", "high_clearance" -> if (mtb) limit(0.03, 15.0) else limit(0.035, 10.0)
            "horrible", "very_horrible", "off_road_wheels", "impassable" ->
                if (mtb) limit(0.045, 10.0) else return Ride(cr, cap, 1.0)
        }
        // MTB scale (singletrail-skala.de); without it the SAC grade of a footpath stands in
        val s = when {
            w.mtb >= 0 -> w.mtb
            w.sac >= 4 -> 4
            w.sac == 3 -> 2
            w.sac == 2 -> 1
            else -> 0
        }
        if (mtb) when (s) {
            1 -> limit(0.03, 15.0)
            2 -> limit(0.045, 10.0)
            3 -> limit(0.06, 6.0)
            in 4..6 -> return Ride(cr, cap, 1.0)
        } else when (s) {
            1 -> limit(0.035, 12.0)
            in 2..6 -> return Ride(cr, cap, 1.0)
        }
        return Ride(cr, cap)
    }

    /** Steady speed (m/s) on [grade] for [activity] on way [w] (null = as [bikeSpeedMs] without a surface). */
    fun bikeSpeedOn(grade: Double, activity: Activity, w: WayAttrs?): Double {
        val r = ride(activity, w)
        val g = if (grade.isNaN()) 0.0 else grade
        if (r.pushFactor > 0) return pushSpeedMs(g) * r.pushFactor
        val b = bike(activity)
        var v = solve(b.riderW, b.massKg, b.drag, r.cr, g)
        if (b.assistW > 0) v = maxOf(v, minOf(solve(b.riderW + b.assistW, b.massKg, b.drag, r.cr, g), ASSIST_LIMIT_MS))
        return v.coerceAtMost(r.capKmh / 3.6).coerceAtLeast(pushSpeedMs(g))
    }

    // ---- one segment -----------------------------------------------------------------------------------------

    /** Seconds for [lenM] horizontal metres with height change [dz] on way [way] (Average pace, no learned factors). */
    fun seconds(lenM: Double, dz: Double, activity: Activity, way: WayAttrs? = null): Double {
        if (lenM <= 0 || lenM.isNaN()) return 0.0
        val grade = if (dz.isNaN()) 0.0 else dz / lenM
        return when (activity) {
            Activity.HIKE -> hikeMinPerKm(grade) * 60 * lenM / 1000 * hikeWayFactor(way)
            else -> lenM / bikeSpeedOn(grade, activity, way)
        }
    }
}

/**
 * Alpine standard hiking time rule DIN 33466 (DAV / AVS / SAC signposts): 4 km/h on the flat, 300 m up and 500 m down
 * per hour; time = the larger of horizontal and vertical time + half of the smaller. It defines the "Average" pace level
 * (shown in Settings). Times themselves – planned routes, imported tracks, straight lines – all come from [PaceModel]
 * (one time model, captain 2026-10-26; the separate quick estimate `Geo.estimateSeconds` is gone).
 * The per-segment model ([PaceModel.hikeMinPerKm], table B) is anchored on the same rule (docs/plan-time-estimate.md §3):
 * 4 km/h on the flat, ~300–340 m/h on steady climbs, but not as slow on descents as the whole-route formula.
 */
object Din33466 {
    const val FLAT_KMH = 4.0
    const val UP_M_PER_H = 300.0
    const val DOWN_M_PER_H = 500.0

    /** Walking time (s) for [distanceM] with [ascentM] up and [descentM] down. */
    fun seconds(distanceM: Double, ascentM: Double, descentM: Double): Double {
        val h = distanceM / (FLAT_KMH * 1000)
        val v = ascentM / UP_M_PER_H + descentM / DOWN_M_PER_H
        return (maxOf(h, v) + minOf(h, v) / 2) * 3600
    }
}

/**
 * Pace level in Settings (F-ROUTE-03, owner 2026-10-22: five levels): a factor on time only, the route stays the same.
 * [speed] is relative to "Average" (= [Din33466] for hiking, the power model for bikes), steps about 15 % apart;
 * [factor] = 1 / [speed] multiplies the model's times. Every plan and every tour ([TripPace]) starts from it.
 */
enum class Pace(private val res: org.jetbrains.compose.resources.StringResource, val speed: Double) {
    RELAXED(Res.string.nav_pace_relaxed, 0.75),
    EASY(Res.string.nav_pace_easy, 0.875),
    AVERAGE(Res.string.nav_pace_average, 1.0),
    FIT(Res.string.nav_pace_fit, 1.15),
    VERY_FIT(Res.string.nav_pace_very_fit, 1.3);

    /** "Relaxed" in the app's language (Settings → Pace). */
    val label: String get() = Txt.s(res)

    /** Factor on time (1 = the model as it is). */
    val factor: Double get() = 1.0 / speed

    /**
     * Time factors (down / flat / up) of this level for [activity]: [factor] everywhere, except e-bikes on the flat
     * (owner 2026-10-22): the motor stops at 25 km/h, so a fitter rider is not faster there – the flat speed stays at
     * most about [E_FLAT_CAP_KMH] at every level (climbs still get faster with the level).
     */
    fun factors(activity: com.onpath.app.routing.Activity): Factors {
        if (!isEBike(activity)) return Factors.ONE * factor
        val flatKmh = PaceModel.bikeSpeedMs(0.0, activity) * 3.6
        return Factors(factor, maxOf(factor, flatKmh / E_FLAT_CAP_KMH), factor)
    }

    /**
     * What the level means for [activity], in plain words for Settings: hiking "about 4 km/h · 300 m up per hour"
     * ([Din33466] × [speed]); bikes the same from the power model: speed on the flat and height gained per hour on a
     * steady [BIKE_CLIMB] climb, both on the activity's usual surface, × [speed] (e-bikes on the flat: capped, see [factors]).
     */
    fun meaning(activity: com.onpath.app.routing.Activity): String {
        val (kmh, upMh) = if (activity == com.onpath.app.routing.Activity.HIKE) {
            Din33466.FLAT_KMH * speed to Din33466.UP_M_PER_H * speed
        } else {
            PaceModel.bikeSpeedMs(0.0, activity) * 3.6 / factors(activity).flat to
                PaceModel.bikeSpeedMs(BIKE_CLIMB, activity) * BIKE_CLIMB * 3600 * speed
        }
        val speedText = if (kmh < 10) {
            val tenths = Math.round(kmh * 10)
            if (tenths % 10 == 0L) "${tenths / 10}" else com.onpath.app.data.Format.number(tenths / 10.0, 1)
        } else "${Math.round(kmh)}"
        return Txt.s(Res.string.nav_pace_meaning, speedText, (Math.round(upMh / 10) * 10).toInt())
    }

    companion object {
        val DEFAULT = AVERAGE

        /** Highest flat speed of e-bikes at any level (km/h): the assist limit. */
        const val E_FLAT_CAP_KMH = 25.0

        fun isEBike(a: com.onpath.app.routing.Activity) =
            a == com.onpath.app.routing.Activity.EBIKE || a == com.onpath.app.routing.Activity.EMTB

        /** Gradient of the "steady climb" in a bike level's [meaning] (6 %: a typical pass road). */
        const val BIKE_CLIMB = 0.06

        /** A stored level name ([name]); unknown or missing = [DEFAULT]. "NORMAL" (old middle level) = Average. */
        fun of(name: String?): Pace = entries.firstOrNull { it.name == name } ?: if (name == "NORMAL") AVERAGE else DEFAULT

        /**
         * The old three levels (stored until 2026-10-22 under "hikePace" / "bikePace"): Relaxed ×1.25 time = 0.8 speed
         * → Relaxed (0.75, the nearest), Normal → Average, Fit ×0.8 time = 1.25 speed → Very fit (1.3, the nearest).
         * So existing users keep (almost) the same times.
         */
        fun fromOld(name: String?): Pace = when (name) {
            "RELAXED" -> RELAXED
            "FIT" -> VERY_FIT
            else -> AVERAGE
        }
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
