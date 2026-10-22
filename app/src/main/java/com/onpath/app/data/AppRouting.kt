package com.onpath.app.data

import android.content.Context
import com.onpath.app.cells.AndroidNetworkStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The one [RoutingDataLoader] of the app process – the UI and the recording service (tour in the background, T5) load
 * routing parts through it, so a part is never fetched twice at the same time. Listeners get its events on any thread.
 */
object AppRouting {
    interface Listener {
        fun installed(names: List<String>) {}
        fun changed() {}
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var instance: RoutingDataLoader? = null

    fun get(context: Context): RoutingDataLoader = instance ?: synchronized(this) {
        instance ?: create(context.applicationContext).also { instance = it }
    }

    fun listen(l: Listener) { listeners += l }
    fun unlisten(l: Listener) { listeners -= l }

    private fun create(app: Context): RoutingDataLoader {
        val net = AndroidNetworkStatus(app)
        val prefs = Prefs(app)
        return RoutingDataLoader(DataFiles(app).segmentsDir, net, { prefs.settings.mapsWifiOnly },
            launch = { block -> scope.launch { block() } },
            onInstalled = { names -> listeners.forEach { it.installed(names) } },
            onChange = { listeners.forEach { it.changed() } },
        ).also { loader -> net.listen { loader.retry() } }
    }
}
