package com.onpath.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.onpath.app.data.AppSettings
import com.onpath.app.debug.FrameStats
import com.onpath.app.debug.Problems
import com.onpath.app.debug.launchSafe
import com.onpath.app.map.ReliefAuto
import com.onpath.strings.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext

/**
 * What [SettingsSlice] reads and writes outside the screens (the phone's preferences and files; blocking, may throw –
 * [rd5Files] / [fingerprint] only inside `withContext(io)`). The app implements it over `Prefs`, `DataFiles` and the
 * assets; each call is one preference or one read with the arguments the view model had.
 */
interface SettingsStore {
    /** The stored settings; read once at start, written at once on every change. */
    var settings: AppSettings
    /** Relief switched off by the app once / set by hand ([ReliefAuto]). */
    var reliefAuto: ReliefAuto.State
    /** The 7 taps on the version were done once (remembered). */
    var devToolsUnlocked: Boolean
    /** Last visit to Diagnostics, ms. */
    var diagnosticsSeenMs: Long
    /** The routing data's `.rd5` files (blocking). */
    fun rd5Files(): List<java.io.File>
    /** The routing fingerprint ([com.onpath.app.routing.RoutingFingerprint.of] over the shipped brouter assets; read once). */
    fun fingerprint(): String
}

/** What [SettingsSlice] asks of the rest of the app (main thread). The view model implements it. */
interface SettingsHost {
    /** The Plan map is what the user looks at (Plan mode, no search over it). */
    val planMapShown: Boolean
    /** The Offline-maps result line goes (the page was left). */
    fun clearOfflineResult()
    /** "Ended in the browser" was seen: the ☰ item is no longer amber. */
    fun syncEndedSeen()
    /** Map downloads over wifi only, or not (the cells' download rule). */
    fun mapsWifiOnly(on: Boolean)
    /** The pace changed: the saved routes' and the plan's times are worked out again (PlanRoute – reached through the host). */
    fun retime()
    /** A tour is recording. */
    val tourRecording: Boolean
    /** The recording service reads the settings again (voice, units). */
    fun recordingSettingsChanged()
    /** "Prefer official routes" changed: the router takes the new strength, the plan is routed again (PlanRoute). */
    fun officialRoutesChanged(strength: Int)
}

/**
 * Settings (cut out of `AppViewModel`, rebuild phase 2 piece 4): open / topic page / back and the on-the-way list, the
 * settings themselves and what a change reaches, the relief that switches itself off on a slow phone, the developer tools
 * unlock and the last visit to Diagnostics, the routing fingerprint. The line at the top of a page is the [notices]'.
 * The store is [store], the rest of the app [host]; background work runs on [scope] with the blocking calls on [io].
 */
class SettingsSlice(
    private val notices: NoticeBoard,
    private val store: SettingsStore,
    private val host: SettingsHost,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    // --- settings (Settings page) ---
    var settings by mutableStateOf(store.settings)
        private set

    private var settingsShown by mutableStateOf(false)
    var showSettings: Boolean
        get() = settingsShown
        set(v) { if (!v) { notices.settingsNote = null; settingsOnTheWay = false }; settingsShown = v } // the line lasts until Settings is left
    /**
     * Settings opened from Navigate's ⚙ (owner 2026-11-07): the on-the-way list (Navigation page + the topics for the
     * way + "Alle Einstellungen"); the tour and the mode are not touched. False after "Alle Einstellungen" or closing.
     */
    var settingsOnTheWay by mutableStateOf(false)
        private set
    /** Open topic of Settings (list of topics → pages); null = the list. */
    private var page by mutableStateOf<SettingsPage?>(null)
    var settingsPage: SettingsPage?
        get() = page
        set(v) {
            if (v != SettingsPage.OFFLINE_MAPS) host.clearOfflineResult() // the result line lasts until the page is left
            if (v != page) notices.settingsNote = null
            if (v == SettingsPage.DIAGNOSTICS || page == SettingsPage.DIAGNOSTICS) markDiagnosticsSeen()
            if (v == SettingsPage.SYNC) host.syncEndedSeen() // "ended in the browser" seen: the ☰ item is no longer amber
            if (v == SettingsPage.DIAGNOSTICS && v != page) loadRoutingInfo()
            page = v
        }

    /** Settings → Diagnostics: the routing fingerprint and the routing data's dates (RoutingFingerprint); null = not read yet. */
    var routingInfo by mutableStateOf<String?>(null)
        private set

    private fun loadRoutingInfo() {
        scope.launchSafe("app", "loadRoutingInfo") {
            routingInfo = withContext(io) {
                try {
                    val rd5 = store.rd5Files().map { f ->
                        f.name to try {
                            com.onpath.app.routing.Rd5Parts.readIndex(f)?.let { com.onpath.app.routing.Rd5Parts.creationTime(com.onpath.app.routing.Rd5Parts.readTail(f, it)) }
                        } catch (e: java.io.IOException) {
                            Problems.info("routing", "Diagnostics: date of routing data ${f.name} not read", e); null
                        }
                    }
                    com.onpath.app.routing.RoutingFingerprint.describe(store.fingerprint(), rd5)
                } catch (e: Exception) {
                    Problems.warn("routing", "Diagnostics: routing fingerprint not computed", e); null
                }
            }
        }
    }

    /** Developer tools in Diagnostics (owner): after 7 taps on the version (remembered) – also in debug builds (Arch H1). */
    var devToolsShown by mutableStateOf(DevToolsUnlock.shown(store.devToolsUnlocked))
        private set

    fun unlockDevTools() {
        store.devToolsUnlocked = true
        devToolsShown = true
        Problems.info("ui", "Developer tools unlocked (7 taps on the version)")
    }

    /** Last visit to Diagnostics: a serious problem after it shows red on the Settings list (owner). */
    var diagnosticsSeenMs by mutableStateOf(store.diagnosticsSeenMs)
        private set

    private fun markDiagnosticsSeen() {
        diagnosticsSeenMs = nowMs()
        store.diagnosticsSeenMs = diagnosticsSeenMs
    }

    /** Opens Settings, at [page] if given (map credits → About). */
    fun openSettings(page: SettingsPage? = null) { settingsPage = page; showSettings = true }

    /** Navigate → ⚙ (owner 2026-11-07): Settings in the on-the-way list; back returns to Navigate, the tour runs on. */
    fun openSettingsOnTheWay() {
        Problems.trace("ui", "Navigate ⚙: settings on the way")
        settingsPage = null; showSettings = true; settingsOnTheWay = true
    }

    /** On-the-way list → "Alle Einstellungen": the whole topic list (back from it closes Settings). */
    fun showAllSettings() { settingsOnTheWay = false; settingsPage = null }

    /** Back in Settings: a topic page returns to the list, the list closes Settings. */
    fun settingsBack() { if (settingsPage != null) settingsPage = null else showSettings = false }

    /** Relief switched off by the app once / set by hand ([com.onpath.app.map.ReliefAuto]). */
    private var reliefAuto = store.reliefAuto

    /**
     * One map gesture is over (finger down → map at rest, fling included) with its frame times (MapHost, FrameJankMeter):
     * into the events log and the problem report's tally; on a phone that stutters with relief, relief goes off once,
     * by itself, with a short line on the map (owner 2026-11-07, [com.onpath.app.map.ReliefAuto]). Decided only in Plan
     * (where the line can show); Navigate's gestures count towards it.
     */
    fun mapGestureMeasured(kind: com.onpath.app.map.ReliefAuto.Kind, stats: FrameStats) {
        val relief = settings.hillshade
        Problems.trace("map", stats.line("Map ${kind.label}") + ", relief ${if (relief) "on" else "off"}")
        val tally = com.onpath.app.debug.MapJank.tally
        tally.add(com.onpath.app.map.ReliefAuto.Gesture(kind, stats.frames, stats.over16, relief))
        if (!host.planMapShown) return
        val v = com.onpath.app.map.ReliefAuto.decide(tally.recent(), relief, reliefAuto) ?: return
        reliefAuto = com.onpath.app.map.ReliefAuto.switchedOff(reliefAuto); store.reliefAuto = reliefAuto
        put(settings.copy(hillshade = false)) // not by hand: updateSettings would mark it
        Problems.trace("map", "Relief switched off by the app: ${v.janky} of ${v.frames} frames > 16 ms (${v.percent} %) " +
            "over ${v.gestures} map gestures with relief on (limit ${(com.onpath.app.map.ReliefAuto.JANK_SHARE * 100).toInt()} %); " +
            "Settings → Map switches it back on")
        notices.sayInfo(Txt.s(Res.string.maps_relief_auto_off), NoticePlace.MAP)
    }

    /** Stores [s] as the settings and nothing else (no relief mark, no re-timing): the app's own change (relief off, heading-up). */
    fun put(s: AppSettings) { settings = s; store.settings = s }

    fun updateSettings(s: AppSettings) {
        val paceChanged = s.hikePace != settings.hikePace || s.bikePace != settings.bikePace
        val officialChanged = s.officialRouteStrength != settings.officialRouteStrength
        // Relief set by hand (Settings → Map): the user's choice wins, the app never switches it off again (ReliefAuto)
        if (s.hillshade != settings.hillshade) {
            reliefAuto = com.onpath.app.map.ReliefAuto.userSet(reliefAuto); store.reliefAuto = reliefAuto
            Problems.trace("map", "Relief ${if (s.hillshade) "on" else "off"} by hand – the app no longer switches it by itself")
        }
        settings = s; store.settings = s
        host.mapsWifiOnly(s.mapsWifiOnly)
        if (paceChanged) host.retime()
        if (officialChanged) {
            Problems.trace("routing", "Prefer official routes: ${s.officialRouteStrength} (0 off, 1 normal, 2 strong) – routed again")
            host.officialRoutesChanged(s.officialRouteStrength)
        }
        if (host.tourRecording) host.recordingSettingsChanged()
    }
}
