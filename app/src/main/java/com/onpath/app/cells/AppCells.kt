package com.onpath.app.cells

import android.content.Context
import com.onpath.app.BuildConfig
import com.onpath.app.data.DataFiles
import com.onpath.app.debug.Problems
import com.onpath.app.data.MapServer
import com.onpath.app.routing.RouterClassHash
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The one [MapCells] of the app process (UI, and later the recording service for T5), started on first use.
 * Server URL and key come from the build ([MapServer]); without a key requests fail with a plain reason.
 */
object AppCells {
    @Volatile private var instance: MapCells? = null

    /** The instance when it was started already (health line), else null – never starts it. */
    fun peek(): MapCells? = instance

    fun get(context: Context): MapCells = instance ?: synchronized(this) {
        instance ?: create(context.applicationContext).also { instance = it }
    }

    private fun create(app: Context): MapCells {
        val net = AndroidNetworkStatus(app)
        val api = MapServer.appKey.takeIf { it.isNotBlank() }?.let { CellApi(MapServer.apiUrl, it, BuildConfig.VERSION_NAME) }
        val cells = MapCells(CellStore(DataFiles(app).dir), api, net, CoroutineScope(SupervisorJob() + Dispatchers.IO), BuildConfig.VERSION_NAME,
            routerClassHash = { profileHash(app) })
        net.listen { cells.networkChanged() }
        cells.start()
        return cells
    }

    /** Hash of the routing profiles this app ships (assets/brouter, the files BRouterEngine installs); null when unreadable. */
    private fun profileHash(app: Context): String? = try {
        RouterClassHash.of { name -> app.assets.open("brouter/$name").use { it.readBytes() } }
    } catch (e: Exception) {
        Problems.warn("routing", "Routing profile hash unknown; map router classes not compared", e)
        null
    }
}
