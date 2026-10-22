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
        // still older installs (no Settings default yet) started with the key activity / sac
        val older = MapPrefs(mutableMapOf("activity" to "MTB", "sac" to 4))
        assertEquals(Activity.MTB, Prefs(older, dir).activity)
        assertEquals(4, Prefs(older, dir).sac)
    }

    /**
     * Field bug 2026-10-22 (owner's phone, 0.9.5): the old app wrote the key "activity" on every picker choice but
     * started each session with the Settings default (defaultActivity). The owner hiked (default Hike, all tours Hike);
     * a stale "activity" = BIKE_TREKKING from an earlier try then won after the update → the app came up with Bike
     * trekking. The activity the old app started with must win; the new app remembers under its own keys.
     */
    @Test fun upgradeKeepsTheActivityTheOldAppStartedWith() {
        val phone = MapPrefs(mutableMapOf("activity" to "BIKE_TREKKING", "defaultActivity" to "HIKE", "sac" to 4, "defaultSac" to 2))
        assertEquals(Activity.HIKE, Prefs(phone, dir).activity)
        assertEquals(2, Prefs(phone, dir).sac)
        // after the update: a choice in the picker is remembered and wins over the old Settings default
        Prefs(phone, dir).apply { activity = Activity.MTB; sac = 5 }
        assertEquals(Activity.MTB, Prefs(phone, dir).activity)
        assertEquals(5, Prefs(phone, dir).sac)
        Prefs(phone, dir).activity = Activity.HIKE
        assertEquals(Activity.HIKE, Prefs(phone, dir).activity)
    }

    @Test fun brokenValuesFallBackToDefaults() {
        assertEquals(5, LastActivity.sac(9, null))
        assertEquals(1, LastActivity.sac(0, 3))
        assertEquals(2, LastActivity.sac(null, null))
        assertEquals(Activity.HIKE, LastActivity.activity(null, null))
        assertEquals(Activity.EBIKE, LastActivity.activity(null, Activity.EBIKE, Activity.MTB))
    }

    @Test fun chipText() {
        assertEquals("Hike · T2", LastActivity.chipText(Activity.HIKE, 2))
        assertEquals("Hike · T5", LastActivity.chipText(Activity.HIKE, 7))
        assertEquals("MTB", LastActivity.chipText(Activity.MTB, 2))
        assertEquals("Trekking", LastActivity.chipText(Activity.BIKE_TREKKING, 4))
        assertEquals("Touring", LastActivity.chipText(Activity.BIKE_TOURING, 4))
        assertEquals("E-bike", LastActivity.chipText(Activity.EBIKE, 4))
        assertEquals("E-MTB", LastActivity.chipText(Activity.EMTB, 4))
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
