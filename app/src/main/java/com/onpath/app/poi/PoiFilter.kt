package com.onpath.app.poi

/**
 * Builds the MapLibre filter JSON for POI layers (see docs/architecture-contract.md):
 * `["all", <original filter>, ["in", ["get","kind"], ["literal", [...enabled kinds]]]]`.
 * Pure string building so it can be unit-tested on the JVM.
 */
object PoiFilter {

    /**
     * Filter JSON for one layer. Returns [originalJson] unchanged (may be null = no filter)
     * when no known kind is hidden, so the style stays exactly as designed.
     */
    fun layerFilter(originalJson: String?, allKnown: Collection<String>, hidden: Set<String>): String? {
        if (allKnown.none { it in hidden }) return originalJson
        val enabled = allKnown.distinct().filter { it !in hidden }
        val kindTest = """["in",["get","kind"],["literal",[${enabled.joinToString(",") { quote(it) }}]]]"""
        return if (originalJson.isNullOrBlank()) kindTest else """["all",$originalJson,$kindTest]"""
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
