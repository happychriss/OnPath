package com.onpath.app

import android.app.Application
import com.onpath.app.recording.RecordingService
import org.maplibre.android.MapLibre

class OnPathApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Must happen before any MapView is created. No API key / network needed.
        MapLibre.getInstance(this)
        RecordingService.createChannels(this)
    }
}
