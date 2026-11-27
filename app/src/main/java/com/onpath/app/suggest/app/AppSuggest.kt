package com.onpath.app.suggest.app

import android.content.Context
import com.onpath.app.BuildConfig
import com.onpath.app.cells.AppCells
import com.onpath.app.cells.CellApi
import com.onpath.app.cells.CellId
import com.onpath.app.data.AppRouting
import com.onpath.app.data.DataFiles
import com.onpath.app.data.MapServer
import com.onpath.app.debug.Problems
import java.io.File

/**
 * The app's tour suggestions wired to its data (stage 4, nothing calls it yet – the UI step does): one [SuggestRunner]
 * per process over a [SuggestService] on the app's routing data (DataFiles.segmentsDir, RoutingDataLoader.missing), the
 * cell store's suggest files (CellStore.suggestFile) and the method's numbers (assets/suggest/settings.json).
 *
 * Profiles: the method reads the router's .brf + lookups.dat as files (RouterProfile → NetReader); it keeps its own copy
 * of the shipped ones in `filesDir/suggest/profiles` (BRouterEngine's folder and its copy step stay untouched).
 */
object AppSuggest {
    const val SETTINGS_ASSET = "suggest/settings.json"
    private const val PROFILES = "brouter"
    /** The shipped profiles the method may read (= BRouterEngine.PROFILE_ASSETS, SuggestServiceTest). */
    internal val PROFILE_FILES = listOf("lookups.dat", "touring.brf", "trekking.brf", "mtb.brf", "hiking-mountain.brf")

    @Volatile private var runner: SuggestRunner? = null
    @Volatile private var service: SuggestService? = null

    fun runner(context: Context): SuggestRunner = runner ?: synchronized(this) {
        runner ?: SuggestRunner(service(context)).also { runner = it }
    }

    fun service(context: Context): SuggestService = service ?: synchronized(this) {
        service ?: create(context.applicationContext).also { service = it }
    }

    /** Fetches the suggest files of [cells] (SuggestCards.cellsToFetch); null when the app has no map server key. */
    fun fetch(context: Context): SuggestFetch? {
        val key = MapServer.appKey.takeIf { it.isNotBlank() } ?: return null
        return SuggestFetch(AppCells.get(context).store, CellApi(MapServer.apiUrl, key, BuildConfig.VERSION_NAME))
    }

    private fun create(app: Context): SuggestService {
        val store = AppCells.get(app).store
        val profiles = File(app.filesDir, "suggest/profiles")
        val settingsText = app.assets.open(SETTINGS_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        return SuggestService(
            segmentsDir = DataFiles(app).segmentsDir,
            profileDir = profiles.also { installProfiles(app, it) },
            suggestFileOf = { x, y -> store.suggestFile(CellId(x, y)) },
            settingsText = { settingsText },
            routingMissing = { AppRouting.get(app).missing(it) },
            markedTrails = com.onpath.app.routing.EngineProvider.markedTrails(app),
        )
    }

    /** The shipped profiles into [dir], rewritten only when they differ (an app update with new profiles). */
    private fun installProfiles(app: Context, dir: File) {
        dir.mkdirs()
        for (name in PROFILE_FILES) {
            val bytes = app.assets.open("$PROFILES/$name").use { it.readBytes() }
            val target = File(dir, name)
            if (target.isFile && target.length() == bytes.size.toLong() && target.readBytes().contentEquals(bytes)) continue
            val tmp = File(dir, "$name.tmp")
            try {
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) throw java.io.IOException("rename failed: ${tmp.path}")
            } catch (e: java.io.IOException) {
                tmp.delete()
                // the run then fails on the missing profile and SuggestService reports it; nothing else depends on it
                Problems.error("suggest", "Routing profile for the tour suggestions could not be installed: $name", e)
            }
        }
    }
}
