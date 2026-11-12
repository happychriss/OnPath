package com.onpath.app.debug

import android.content.Context
import com.onpath.app.BuildConfig
import java.io.File

/**
 * Debug builds only: a frame-rate limit for the map, set from the computer for a measuring session (smooth-map
 * work 2026-11-12). The shipped app has no limit (owner: the map runs at the display's full rate) – a release build
 * never reads the file.
 *
 *   adb shell run-as com.onpath.app sh -c 'echo 60 > files/debug-map-fps'   # then reopen the app
 *   adb shell run-as com.onpath.app rm files/debug-map-fps                    # back to no limit
 */
object MapFpsTrial {
    const val FILE = "debug-map-fps"

    /** The limit in the file's text, or null for none / not a number / outside 10..240. Pure, unit-tested. */
    fun parse(text: String?): Int? = text?.trim()?.toIntOrNull()?.takeIf { it in 10..240 }

    fun limit(context: Context): Int? {
        if (!BuildConfig.DEBUG) return null
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return null
        return try {
            parse(f.readText())
        } catch (e: Exception) {
            Problems.warn("map", "Debug map frame limit not readable", e)
            null
        }
    }
}
