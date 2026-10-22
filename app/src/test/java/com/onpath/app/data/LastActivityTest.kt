package com.onpath.app.data

import android.content.SharedPreferences
import com.onpath.app.routing.Activity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Owner 2026-10-22: no "Default activity" in Settings; fresh install = Hike T2, then always the last chosen activity
 * and T level (picker), across sessions. Older installs keep what they had stored.
 */
class LastActivityTest {
    private val dir = File(System.getProperty("java.io.tmpdir"))

    @Test fun freshInstallIsHikeT2() {
        val p = Prefs(MapPrefs(), dir)
        assertEquals(Activity.HIKE, p.activity)
        assertEquals(2, p.sac)
    }

    @Test fun lastChosenActivityAndLevelSurviveARestart() {
        val store = MapPrefs()
        Prefs(store, dir).apply { activity = Activity.MTB; sac = 4 }
        val again = Prefs(store, dir) // next session
        assertEquals(Activity.MTB, again.activity)
        assertEquals(4, again.sac)
        again.activity = Activity.HIKE
        assertEquals(Activity.HIKE, Prefs(store, dir).activity)
    }

    @Test fun migrationKeepsStoredValues() {
        // before: Settings wrote defaultActivity / defaultSac (T3 was the default) on every settings change
        val old = MapPrefs(mutableMapOf("defaultActivity" to "BIKE_TREKKING", "defaultSac" to 3))
        assertEquals(Activity.BIKE_TREKKING, Prefs(old, dir).activity)
        assertEquals(3, Prefs(old, dir).sac)
        // a level / activity chosen later (key sac / activity) wins over the old Settings default
        val both = MapPrefs(mutableMapOf("defaultActivity" to "BIKE_TREKKING", "activity" to "HIKE", "defaultSac" to 3, "sac" to 5))
        assertEquals(Activity.HIKE, Prefs(both, dir).activity)
        assertEquals(5, Prefs(both, dir).sac)
    }

    @Test fun brokenValuesFallBackToDefaults() {
        assertEquals(5, LastActivity.sac(9, null))
        assertEquals(1, LastActivity.sac(0, 3))
        assertEquals(Activity.HIKE, LastActivity.activity(false, Activity.MTB, false, Activity.EBIKE))
    }

    @Test fun chipText() {
        assertEquals("Hike · T2", LastActivity.chipText(Activity.HIKE, 2))
        assertEquals("Hike · T5", LastActivity.chipText(Activity.HIKE, 7))
        assertEquals("MTB", LastActivity.chipText(Activity.MTB, 2))
        assertEquals("Bike – trekking", LastActivity.chipText(Activity.BIKE_TREKKING, 4))
    }
}

/** In-memory SharedPreferences (JVM tests). */
internal class MapPrefs(private val m: MutableMap<String, Any?> = mutableMapOf()) : SharedPreferences {
    override fun getAll(): MutableMap<String, *> = m
    override fun getString(key: String, defValue: String?) = m[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = m[key] as MutableSet<String>? ?: defValues
    override fun getInt(key: String, defValue: Int) = m[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long) = m[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float) = m[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = m[key] as Boolean? ?: defValue
    override fun contains(key: String) = m.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val put = mutableMapOf<String, Any?>()
        private val del = mutableSetOf<String>()
        override fun putString(k: String, v: String?) = apply { put[k] = v }
        override fun putStringSet(k: String, v: MutableSet<String>?) = apply { put[k] = v }
        override fun putInt(k: String, v: Int) = apply { put[k] = v }
        override fun putLong(k: String, v: Long) = apply { put[k] = v }
        override fun putFloat(k: String, v: Float) = apply { put[k] = v }
        override fun putBoolean(k: String, v: Boolean) = apply { put[k] = v }
        override fun remove(k: String) = apply { del += k }
        override fun clear() = apply { del += m.keys }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() { del.forEach { m.remove(it) }; m.putAll(put) }
    }
}
