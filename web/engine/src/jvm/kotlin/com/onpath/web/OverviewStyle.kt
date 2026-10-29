package com.onpath.web

import com.onpath.app.map.Overview
import java.io.File

/**
 * The app's format-2 style with the world overview merged in by the app's own [Overview.merge] (docs/design/
 * web-planner.md §16): the overview files the APK bundles (base.pmtiles, hillshade.pmtiles) and one empty cover box –
 * the page fills the cover (detail-cover) with the cells it finds, like the app with its installed cells.
 * Args: <style.json> <out.json>
 */
fun main(args: Array<String>) {
    val (input, output) = args
    val style = File(input).readText()
    val merged = Overview.merge(style, setOf("base.pmtiles", Overview.HILLSHADE_FILE), listOf(doubleArrayOf(0.0, 0.0, 0.0, 0.0)))
    check(merged != style && merged.contains("\"${Overview.COVER}\"")) { "overview not merged into $input" }
    File(output).apply { parentFile.mkdirs() }.writeText(merged)
    println("overview style: $output (${merged.length} bytes)")
}
