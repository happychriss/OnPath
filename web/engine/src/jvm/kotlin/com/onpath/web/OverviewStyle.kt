package com.onpath.web

import com.onpath.app.map.Overview
import java.io.File

/**
 * The app's format-2 style with the world overview merged in by the app's own [Overview.merge] (docs/design/
 * web-planner.md §16): the overview files the APK bundles (base.pmtiles, hillshade.pmtiles) and one empty cover box –
 * the page fills the cover (detail-cover) with the cells it finds, like the app with its installed cells.
 * Also `<out>-packages.json`: merged for region packages (map-cells.md §20; the detailed map from z7, the overview's
 * fills under the packages' relief, its labels from z7 `!within` the packages) with one placeholder box far from any
 * land – the page puts the boxes of the packages it finds into that `within` geometry (overview.ts followPackages).
 * Args: <style.json> <out.json>
 */
fun main(args: Array<String>) {
    val (input, output) = args
    val style = File(input).readText()
    val merged = Overview.merge(style, setOf("base.pmtiles", Overview.HILLSHADE_FILE), listOf(doubleArrayOf(0.0, 0.0, 0.0, 0.0)))
    check(merged != style && merged.contains("\"${Overview.COVER}\"")) { "overview not merged into $input" }
    File(output).apply { parentFile.mkdirs() }.writeText(merged)
    println("overview style: $output (${merged.length} bytes)")
    val withPackages = Overview.merge(style, setOf("base.pmtiles", Overview.HILLSHADE_FILE), listOf(doubleArrayOf(0.0, 0.0, 0.0, 0.0)),
        packages = listOf(PLACEHOLDER))
    check(withPackages.contains(Overview.OUTSIDE) && withPackages.contains("\"${Overview.REGION_SHADE_SOURCE}\"")) {
        "region-package overview not merged into $input"
    }
    val out2 = File(output.removeSuffix(".json") + "-packages.json")
    out2.writeText(withPackages)
    println("overview style for region packages: $out2 (${withPackages.length} bytes)")
}

/** A tiny box in the South Pacific (no land, no label): the `within` geometry until the page found a package. */
val PLACEHOLDER = doubleArrayOf(-150.0, -60.0, -149.99, -59.99)
