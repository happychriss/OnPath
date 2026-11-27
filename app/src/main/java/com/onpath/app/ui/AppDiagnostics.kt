package com.onpath.app.ui

import com.onpath.app.debug.Problems

// The view model's app state for the diagnostics export, moved out of AppViewModel.kt unchanged (its size cap, FileSizeRuleTest).

/** App state for the diagnostics export (Settings → Diagnostics); never throws. */
fun AppViewModel.diagnosticsState(): org.json.JSONObject = try {
    val rec = recState.value
    val loc = tracker.location.value
    org.json.JSONObject()
        .put("mode", tourSession.mode.name).put("activity", planRoute.activity.name).put("sac", planRoute.sac)
        .put("waypoints", plan.points.size).put("routeMeters", route?.distanceM)
        .put("routeLegsStraight", route?.legs?.count { it.straight })
        .put("routeError", routeError)
        .put("followName", tourFollow.followName).put("followPoints", tourFollow.followLine?.size)
        .put("recording", rec?.let { org.json.JSONObject().put("tourId", it.tourId).put("paused", it.paused)
            .put("distanceM", it.distanceM).put("elapsedS", it.elapsedS).put("gpsLost", it.gpsLost)
            .put("offRouteM", it.offRouteM).put("saveFailed", it.saveFailed)
            .put("toGoS", it.time?.toGoS).put("arrivalMs", it.time?.arrivalMs) } ?: org.json.JSONObject.NULL)
        .put("gps", tracker.status().name)
        .put("phone", com.onpath.app.setup.PhoneCheck.describe(setup.phone))
        .put("voice", prefs.voiceDetail)
        .put("demo", com.onpath.app.recording.DemoLocation.state.value?.let { d -> org.json.JSONObject().put("kind", d.kind.name)
            .put("label", d.label).put("factor", d.factor).put("paused", d.paused).put("alongM", d.alongM.toInt())
            .put("lengthM", d.lengthM.toInt()).put("ended", d.ended) } ?: org.json.JSONObject.NULL)
        .put("location", loc?.let { org.json.JSONObject().put("lat", "%.5f".format(java.util.Locale.ROOT, it.latitude))
            .put("lon", "%.5f".format(java.util.Locale.ROOT, it.longitude)).put("accuracy", it.accuracy)
            .put("provider", it.provider).put("ageS", com.onpath.app.recording.LocationTracker.ageSeconds(it)) } ?: org.json.JSONObject.NULL)
        .put("styleVersion", styleVersion).put("missingData", missing.map { it.name })
        .put("mapDir", dataFiles.mapDir().path)
        .put("mapCells", org.json.JSONObject().put("generation", cells.generation.value)
            .put("installed", org.json.JSONArray(cells.installed.value.map { "${it.id} ${it.version} ${it.totalBytes}" }))
            .put("states", org.json.JSONObject(cells.states.value.entries.associate { (c, s) -> c.id to s.toString() } as Map<*, *>)))
} catch (e: Throwable) {
    Problems.warn("ui", "Diagnostics app state could not be collected", e)
    org.json.JSONObject().put("error", e.toString())
}
