package com.onpath.app.data

import com.onpath.strings.*
import org.jetbrains.compose.resources.StringResource

/**
 * Plain notice on the plan while routing data is missing (never a file name or path – those go to Problems). Pure,
 * unit-tested; shared with the web planner (`SharedCoreRuleTest.files` / `webShared`, docs/handoff-web-notice-breakdown.md).
 * The I/O (which parts are on the phone, the download) stays in [RoutingDataLoader].
 */
object RoutingDataNotice {
    /** Which notice: the part is loading now, or it loads when the phone is online again. */
    enum class Kind(val res: StringResource) {
        LOADING(Res.string.maps_routing_loading),
        NOT_YET(Res.string.maps_routing_not_yet);

        val text: String get() = Txt.s(res)
    }

    val LOADING: String get() = Kind.LOADING.text
    val NOT_YET: String get() = Kind.NOT_YET.text

    fun kind(loading: Boolean): Kind = if (loading) Kind.LOADING else Kind.NOT_YET

    fun text(loading: Boolean): String = kind(loading).text

    fun isNotice(s: String?): Boolean = s == LOADING || s == NOT_YET

    /** The router's own sentence about missing data (= `BRouterEngine.NO_DATA`), in the app's language. */
    private val NO_DATA: String get() = Txt.s(Res.string.plan_err_no_data)

    /**
     * Which notice the plan shows, or null = none ([error] as is): [missing] = a tile under the route is not on the
     * phone; a router message about missing data (any wording, may hold a path) counts the same.
     */
    fun kindForRoute(error: String?, missing: Boolean, loading: Boolean): Kind? = when {
        missing -> kind(loading)
        // the router's own sentence (in English or the app's language) or a file name
        error != null && (error.contains("No routing data") || error.contains(NO_DATA) || error.contains(".rd5")) -> kind(loading)
        else -> null
    }

    /**
     * The plan's notice: [missing] = a tile under the route is not on the phone → the plain notice (loading or not);
     * a router message about missing data (any wording, may hold a path) → the plain notice too; else [error] as is.
     */
    fun forRoute(error: String?, missing: Boolean, loading: Boolean): String? =
        kindForRoute(error, missing, loading)?.text ?: error
}
