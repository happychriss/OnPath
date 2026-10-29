package com.onpath.app.routing

/**
 * Plan links (docs/design/web-planner.md §8): `https://plan.onpath.earth/#p=<code>`
 * (code: [PlanCode]). The web planner writes them as a link and a QR code; the phone's camera opens the link in OnPath
 * (intent filter + App Links), a link shared or pasted into the search box opens too, and "Share plan" in the plan
 * sheet writes the same link. Pure Kotlin (SharedCoreRuleTest): the intent's strings come in as plain text.
 */
object PlanLinks {
    /** The web planner's host – also the App Link host (`/.well-known/assetlinks.json` lives there). */
    const val HOST = "plan.onpath.earth"
    const val BASE = "https://$HOST/"
    /** What marks a plan link anywhere (any host: a planner copy, a test page, a link shortener's target). */
    private const val MARK = "#p="
    /** What marks a pairing link (plan sync, docs/design/web-sync.md §4) – never a plan, and a plan link is never one. */
    private const val PAIR_MARK = "#pair="

    /** How a link reached the app: opened (VIEW: camera, a tapped link) or shared as text (SEND). */
    enum class Via { OPENED, SHARED }

    /** What a link / text holds. */
    sealed interface Found {
        data class Plan(val plan: SharedPlan) : Found
        /** It is a plan link, but the plan can't be read (cut off, typed wrong, newer format, no points). */
        data object Broken : Found
        /** A pairing link of the web planner (☰ → Pair phone, its QR code): [secret] = the shared pairing secret. */
        class Pairing(val secret: ByteArray) : Found
        /** A pairing link that can't be read (cut off, a newer version). */
        data object BrokenPairing : Found
    }

    /** The link for [plan] (Share plan). */
    fun link(plan: SharedPlan): String = PlanCode.link(BASE, plan)

    /**
     * A link opened in OnPath (VIEW): a pairing link (`#pair=`) → [Found.Pairing]; a planner link or any link with
     * `#p=` → its plan or [Found.Broken]; else null.
     */
    fun fromLink(uri: String?): Found? {
        val u = uri?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (PAIR_MARK in u) return readPairing(u)
        if (!isPlannerLink(u) && MARK !in u) return null
        return read(u)
    }

    /** Text shared to OnPath or pasted into the search box: only when it holds a `#p=` / `#pair=` link; else null (a place …). */
    fun fromText(text: String?): Found? {
        val words = text?.split(Regex("""\s+"""))
        words?.firstOrNull { PAIR_MARK in it }?.let { return readPairing(it) }
        val token = words?.firstOrNull { MARK in it } ?: return null
        return read(token)
    }

    private fun readPairing(s: String): Found =
        com.onpath.app.sync.PairCode.parse(s)?.let { Found.Pairing(it) } ?: Found.BrokenPairing

    /**
     * What an intent brings (MainActivity.planLinkOf, its fields as plain strings): VIEW with a plan link, or SEND of
     * plain text (no file) holding one. Null = not a plan link – the GPX / place handling goes on.
     */
    fun fromIntent(via: Via?, data: String?, type: String?, text: String?, hasStream: Boolean): Found? = when {
        via == Via.OPENED -> fromLink(data)
        via == Via.SHARED && type?.startsWith("text/plain") == true && !hasStream -> fromText(text)
        else -> null
    }

    /** A link to the planner's host (http or https, any path). */
    fun isPlannerLink(u: String): Boolean =
        Regex("""^https?://${Regex.escape(HOST)}(?::\d+)?(?:[/?#]|$)""", RegexOption.IGNORE_CASE).containsMatchIn(u.trim())

    private fun read(s: String): Found =
        PlanCode.parse(s)?.takeIf { it.points.isNotEmpty() }?.let { Found.Plan(it) } ?: Found.Broken

    /** What a plan from a link puts into the app's plan: the points, the pinned points and the names by point. */
    data class Load(val points: List<GeoPoint>, val pinned: Set<GeoPoint>, val names: Map<GeoPoint, String>)

    fun load(plan: SharedPlan): Load {
        val pts = plan.points
        return Load(
            pts,
            plan.pinned.mapNotNull { pts.getOrNull(it) }.toSet(),
            plan.names.mapNotNull { (i, n) -> pts.getOrNull(i)?.let { it to n } }.toMap(),
        )
    }

    /** The current plan as it is shared: names and pinned points by index ([pinned] / [names] from the app's plan). */
    fun share(points: List<GeoPoint>, activity: Activity, sac: Int, pinned: Set<Int>, names: (GeoPoint) -> String?,
              title: String?): SharedPlan =
        SharedPlan(
            points.map { GeoPoint(it.lat, it.lon) }, activity, sac.coerceIn(1, 5),
            pinned.filter { it in points.indices }.toSet(),
            points.indices.mapNotNull { i -> names(points[i])?.let { i to it } }.toMap(),
            title,
        )
}
