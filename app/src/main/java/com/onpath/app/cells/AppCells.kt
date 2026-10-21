package com.onpath.app.cells

import android.content.Context
import com.onpath.app.BuildConfig
import com.onpath.app.data.DataFiles
import com.onpath.app.data.MapServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The one [MapCells] of the app process (UI, and later the recording service for T5), started on first use.
 * Server URL and key come from the build ([MapServer]); without a key requests fail with a plain reason.
 */
object AppCells {
    @Volatile private var instance: MapCells? = null

    fun get(context: Context): MapCells = instance ?: synchronized(this) {
        instance ?: create(context.applicationContext).also { instance = it }
    }

    private fun create(app: Context): MapCells {
        val net = AndroidNetworkStatus(app)
        val api = MapServer.appKey.takeIf { it.isNotBlank() }?.let { CellApi(MapServer.apiUrl, it, BuildConfig.VERSION_NAME) }
        val cells = MapCells(CellStore(DataFiles(app).dir), api, net, CoroutineScope(SupervisorJob() + Dispatchers.IO), BuildConfig.VERSION_NAME)
        net.listen { cells.networkChanged() }
        cells.start()
        return cells
    }
}
