package com.onpath.app.data

import android.content.Context
import com.onpath.app.debug.Problems
import com.onpath.app.domain.RouteShares
import com.onpath.app.domain.StepShares
import com.onpath.app.data.ProgressMemory.Companion.SUGGEST_DEFAULT

private const val FILE = "onpath_progress"
private const val KEY_SHARES = "map_steps"
private const val KEY_ROUTE = "route_check"
private const val KEY_SUGGEST = "suggest_steps"
private const val KEY_FACTOR = "phone_factor"

@Volatile private var app: ProgressMemory? = null

/** The app's one memory (the loading window, the sign beside and the notification read the same shares). */
fun progressMemoryOf(context: Context): ProgressMemory = app ?: synchronized(ProgressMemory) {
    app ?: Stored(context.applicationContext).also { app = it }
}

/** In the preferences [FILE]; read once, then kept in memory too. */
private class Stored(private val context: Context) : ProgressMemory {
    @Volatile private var s: StepShares? = null
    @Volatile private var r: Double? = null
    @Volatile private var g: StepShares? = null
    @Volatile private var f: Double? = null

    private fun prefs() = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override var shares: StepShares
        get() = s ?: (try { StepShares.decode(prefs().getString(KEY_SHARES, null)) } catch (e: Exception) {
            Problems.warn("progress", "Learned shares of the wait window not readable; the start values are used", e)
            StepShares.DEFAULT
        }).also { s = it }
        set(v) {
            s = v
            try { prefs().edit().putString(KEY_SHARES, v.encode()).apply() } catch (e: Exception) {
                Problems.warn("progress", "Learned shares of the wait window not stored; kept for this session", e)
            }
        }

    override var routeCheck: Double
        get() = r ?: (try { RouteShares.decode(prefs().getString(KEY_ROUTE, null)) } catch (e: Exception) {
            Problems.warn("progress", "Learned route check share not readable; the start value is used", e)
            RouteShares.DEFAULT_CHECK
        }).also { r = it }
        set(v) {
            r = RouteShares.bounded(v)
            try { prefs().edit().putString(KEY_ROUTE, RouteShares.encode(v)).apply() } catch (e: Exception) {
                Problems.warn("progress", "Learned route check share not stored; kept for this session", e)
            }
        }

    override var suggestShares: StepShares
        get() = g ?: (try { prefs().getString(KEY_SUGGEST, null)?.let(StepShares::decode) ?: SUGGEST_DEFAULT } catch (e: Exception) {
            Problems.warn("progress", "Learned shares of the round-trip search not readable; the start values are used", e)
            SUGGEST_DEFAULT
        }).also { g = it }
        set(v) {
            g = v
            try { prefs().edit().putString(KEY_SUGGEST, v.encode()).apply() } catch (e: Exception) {
                Problems.warn("progress", "Learned shares of the round-trip search not stored; kept for this session", e)
            }
        }

    override var phoneFactor: Double
        get() = f ?: (try {
            com.onpath.app.routing.RouteCost.bounded(prefs().getString(KEY_FACTOR, null)?.toDoubleOrNull() ?: com.onpath.app.routing.RouteCost.DEFAULT_FACTOR)
        } catch (e: Exception) {
            Problems.warn("progress", "Learned router speed of this phone not readable; the start value is used", e)
            com.onpath.app.routing.RouteCost.DEFAULT_FACTOR
        }).also { f = it }
        set(v) {
            val b = com.onpath.app.routing.RouteCost.bounded(v)
            f = b
            try { prefs().edit().putString(KEY_FACTOR, b.toString()).apply() } catch (e: Exception) {
                Problems.warn("progress", "Learned router speed of this phone not stored; kept for this session", e)
            }
        }
}
