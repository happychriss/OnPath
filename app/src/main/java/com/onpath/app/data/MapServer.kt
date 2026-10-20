package com.onpath.app.data

import com.onpath.app.BuildConfig

/**
 * Where the map cells come from (docs/design/map-hosting.md, API: docs/design/map-cells.md §8).
 * Fixed per build type: debug → test doorman, release → production. The app key comes from a Gradle property at
 * build time (never committed); without it the server answers 401 – shown in Settings → Diagnostics.
 * No download logic yet (map-cells.md phase 2).
 */
object MapServer {
    val environment: String get() = BuildConfig.MAP_SERVER_ENV
    val baseUrl: String get() = BuildConfig.MAP_SERVER_URL.trimEnd('/')
    val apiUrl: String get() = "$baseUrl/v1"
    val appKey: String get() = BuildConfig.MAP_SERVER_KEY

    /** One line for Settings → Diagnostics; never shows the whole key. */
    fun describe(): String = describe(environment, apiUrl, appKey)

    fun describe(env: String, url: String, key: String): String = "$env · $url · key ${maskKey(key)}"

    fun maskKey(key: String): String = when {
        key.isBlank() -> "not set"
        key.length < 12 -> "set"
        else -> "set (…${key.takeLast(4)})"
    }
}
