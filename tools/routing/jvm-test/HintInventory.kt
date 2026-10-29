import com.onpath.app.routing.*
import java.io.File
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sqrt

/*
 * Inventory of "needs checking" tags on real routes (route hints): random point pairs per box and
 * activity, BRouter's full way AND node tags of every found track (RAW_TAP), counted per candidate hint – how many
 * routes would show it (noise) and how much of the route it covers.
 *   RAW_TAP=1 KTTEST=tools/routing/jvm-test/HintInventory.kt JAVA_XMX=1g run.sh <segmentsDir> <workDir>
 * Env: HI_N (pairs per box, 30), HI_SEED (7), HI_ACTS (BIKE_TOURING,BIKE_TREKKING,MTB,HIKE), HI_SAMPLES=1 prints one
 * example (box, activity, place) per candidate, HI_BYBOX=<regex> counts the candidates matching it per box.
 */

class FakeCtx(private val dir: File) : android.content.Context() {
    override fun getApplicationContext(): android.content.Context = this
    override fun getFilesDir(): File = dir
    override fun getAssets(): android.content.res.AssetManager = android.content.res.AssetManager()
}

fun dist(a: GeoPoint, b: GeoPoint): Double {
    val x = Math.toRadians(b.lon - a.lon) * cos(Math.toRadians((a.lat + b.lat) / 2))
    val y = Math.toRadians(b.lat - a.lat)
    return sqrt(x * x + y * y) * 6_371_000.0
}

/** Candidate hints from one run of way tags (and the tags of the node ending it). */
fun candidates(way: Map<String, String>, node: Map<String, String>): List<String> {
    val out = ArrayList<String>()
    if (way["route"] == "ferry") out += "ferry"
    if (way["ford"] != null) out += "ford (way ford=${way["ford"]})"
    if (node["ford"] != null || node["highway"] == "ford") out += "ford (node)"
    node["barrier"]?.let { out += "barrier=$it" }
    if (node["access"] in setOf("no", "private")) out += "node access=${node["access"]}"
    if (node["bicycle"] in setOf("no", "dismount")) out += "node bicycle=${node["bicycle"]}"
    way["access"]?.takeIf { it in setOf("private", "no", "destination", "agricultural", "forestry", "customers", "delivery", "permit", "military") }
        ?.let { out += "way access=$it" }
    way["bicycle"]?.takeIf { it in setOf("no", "private") }?.let { out += "way bicycle=$it" }
    way["foot"]?.takeIf { it in setOf("no", "private") }?.let { out += "way foot=$it" }
    if (way["toll"] != null) out += "toll"
    if (way["highway"] == "construction" || way["construction"] != null) out += "construction"
    if (way["conveying"] != null) out += "conveying (escalator)"
    if (way["motorroad"] == "yes") out += "motorroad"
    way["obstacle"]?.let { out += "obstacle=$it" }
    if (way["highway"] == "via_ferrata") out += "via_ferrata"
    way["trail_visibility"]?.takeIf { it in setOf("bad", "horrible", "no") }?.let { out += "trail_visibility=$it" }
    way["smoothness"]?.takeIf { it in setOf("very_horrible", "impassable") }?.let { out += "smoothness=$it" }
    if (way["highway"] == "steps") out += "steps"
    if (way["railway"] != null && way["highway"] == null) out += "railway=${way["railway"]}"
    if (way["noexit"] == "yes") out += "noexit"
    if (way["piste:type"] != null) out += "piste"
    return out
}

fun main() {
    android.content.res.AssetManager.root = File("/home/user/onpath/app/src/main/assets")
    val files = File("/home/user/build/routing-test/kt-files").apply { deleteRecursively(); mkdirs() }
    val eng = BRouterEngine(FakeCtx(files), File("/home/user/build/routing-test/segments"))
    val boxes = linkedMapOf(
        "Hilden/Duesseldorf" to listOf(51.10, 6.75, 51.25, 7.10),
        "Rhine Dormagen-Kaiserswerth" to listOf(51.08, 6.68, 51.33, 6.90),
        "Meran" to listOf(46.58, 11.02, 46.78, 11.30),
    )
    val n = (System.getenv("HI_N") ?: "30").toInt()
    val acts = (System.getenv("HI_ACTS") ?: "BIKE_TOURING,BIKE_TREKKING,MTB,HIKE").split(',').map { Activity.valueOf(it.trim()) }
    val samples = System.getenv("HI_SAMPLES") != null
    val byBox = System.getenv("HI_BYBOX")?.let { Regex(it) }
    var msgs: List<String?> = emptyList()
    RawTap.sink = { _, _, m -> msgs = m }
    // candidate -> activity -> (routes, metres or count)
    val routes = HashMap<String, HashMap<Activity, Int>>()
    val metres = HashMap<String, HashMap<Activity, Double>>()
    val total = HashMap<Activity, Int>()
    // what the app shows: routes per activity with an amber line, and with each kind of spot in it
    val shown = HashMap<String, HashMap<Activity, Int>>()
    val sample = HashMap<String, String>()
    for ((box, bb) in boxes) {
        val rnd = java.util.Random((System.getenv("HI_SEED") ?: "7").toLong())
        val pairs = ArrayList<Pair<GeoPoint, GeoPoint>>()
        while (pairs.size < n) {
            val a = GeoPoint(bb[0] + rnd.nextDouble() * (bb[2] - bb[0]), bb[1] + rnd.nextDouble() * (bb[3] - bb[1]))
            val b = GeoPoint(bb[0] + rnd.nextDouble() * (bb[2] - bb[0]), bb[1] + rnd.nextDouble() * (bb[3] - bb[1]))
            if (dist(a, b) in 2000.0..8000.0) pairs += a to b
        }
        for (act in acts) for ((a, b) in pairs) {
            msgs = emptyList()
            val r = try { eng.route(listOf(a, b), act, 3) } catch (e: RoutingException) { continue }
            val spots = com.onpath.app.ui.RouteWarnings.detect(r.points, act, 3)
            for (k in spots.map { it.kind.name }.toSet() + (if (spots.isEmpty()) emptySet() else setOf("(any line)")))
                shown.getOrPut(k) { HashMap() }.merge(act, 1, Int::plus)
            total.merge(act, 1, Int::plus)
            val seen = HashSet<String>()
            for (line in msgs) {
                val c = line?.split('\t') ?: continue
                if (c.size < 11) continue
                val d = c[3].trim().toDoubleOrNull() ?: 0.0
                val way = BRouterWayStats.parseTags(c[9]); val node = BRouterWayStats.parseTags(c[10])
                for (k0 in candidates(way, node)) {
                    val k = if (byBox != null && byBox.containsMatchIn(k0)) "$k0 @${box.take(6)}" else k0
                    seen += k
                    metres.getOrPut(k) { HashMap() }.merge(act, if (k.startsWith("barrier") || k.contains("node")) 1.0 else d, Double::plus)
                    if (samples && k !in sample) sample[k] = "$box $act at ${c[1].trim().toInt() / 1e6},${c[0].trim().toInt() / 1e6}  way: ${c[9].trim().take(120)}  node: ${c[10].trim()}"
                }
            }
            for (k in seen) routes.getOrPut(k) { HashMap() }.merge(act, 1, Int::plus)
        }
    }
    println("routes per activity: " + acts.joinToString { "$it ${total[it] ?: 0}" })
    println(String.format("%-34s", "candidate") + acts.joinToString("") { String.format("%22s", it.name.take(13)) })
    for (k in routes.keys.sorted()) {
        println(String.format("%-34s", k.take(34)) + acts.joinToString("") { a ->
            val r = routes[k]?.get(a) ?: 0; val m = metres[k]?.get(a) ?: 0.0
            val unit = if (k.startsWith("barrier") || k.contains("node")) String.format(Locale.ROOT, "%5.0f×", m) else String.format(Locale.ROOT, "%5.0fm", m)
            String.format(Locale.ROOT, "%9s %3d%% %6s", "$r/${total[a] ?: 0}", if ((total[a] ?: 0) == 0) 0 else 100 * r / total[a]!!, unit)
        })
    }
    println("\nAmber line as the app shows it (RouteWarnings.detect, T3): routes with each kind")
    for (k in shown.keys.sorted()) println(String.format("%-34s", k) + acts.joinToString("") { a ->
        val r = shown[k]?.get(a) ?: 0
        String.format(Locale.ROOT, "%15s %4d%%", "$r/${total[a] ?: 0}", if ((total[a] ?: 0) == 0) 0 else 100 * r / total[a]!!)
    })
    if (samples) sample.toSortedMap().forEach { (k, v) -> println("SAMPLE $k: $v") }
}
