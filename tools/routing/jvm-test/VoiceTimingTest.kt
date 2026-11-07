// Owner field feedback 2026-11-07 afternoon (bike): "Jetzt abbiegen" came too late – it should come 2–3 s before the
// turn; a due turn must never be swallowed. Real town routes (Hilden, BRouter hints → JogRule / CueRule → VoiceGuide),
// ridden at 15 / 20 / 25 km/h with the delays of a real phone: one GPS fix a second that is FIX_AGE_S old when it
// arrives, text-to-speech that starts TTS_START_S after the call, the turn word after the words before it (~WORD_S each),
// a later sentence cutting one still being said (QUEUE_FLUSH). For every junction worth a cue: when its turn word was
// heard in its own "now" sentence ("Gleich links." / "Now turn left."), in seconds before the rider reaches it.
// Pass / fail: every junction has its own "now", heard at least MIN_HEARD_S before it – except one closer after the turn
// before than CLOSE_S of riding (physically no time): that one is named as "danach" in the sentence before.
// Run: KTTEST=.../VoiceTimingTest.kt run.sh <segmentsDir with E5_N50.rd5>   (DUMP=1 prints every sentence)
import com.onpath.app.data.Geo
import com.onpath.app.navigation.*
import com.onpath.app.routing.*
import java.io.File

class FakeCtx(private val dir: File) : android.content.Context() {
    override fun getApplicationContext(): android.content.Context = this
    override fun getFilesDir(): File = dir
    override fun getAssets(): android.content.res.AssetManager = android.content.res.AssetManager()
}

const val FIX_AGE_S = 0.8
const val TTS_START_S = 0.8
const val WORD_S = 0.4
const val MIN_HEARD_S = 2.0
/** Closer after the turn before than this many seconds of riding: named as "danach", its own "now" only if still in time. */
const val CLOSE_S = FIX_AGE_S + 1.0 + TTS_START_S + WORD_S + MIN_HEARD_S

fun main() {
    android.content.res.AssetManager.root = File("/home/user/onpath/app/src/main/assets")
    val files = File("/home/user/build/routing-test/kt-files").apply { deleteRecursively(); mkdirs() }
    val eng = BRouterEngine(FakeCtx(files), File("/home/user/build/routing-test/segments"))
    val dump = System.getenv("DUMP") != null
    val only = System.getenv("CASE")
    val cases = listOf(
        "Hilden plan 2026-11-07" to listOf(GeoPoint(51.16928899999999, 6.925429000000008), GeoPoint(51.16915399999999, 6.929327999999998),
            GeoPoint(51.16826, 6.93204), GeoPoint(51.16830975821202, 6.932520568370819)),
        "Hilden Stadtpark" to listOf(GeoPoint(51.167810, 6.925827), GeoPoint(51.166511, 6.927717), GeoPoint(51.165892, 6.928305)),
        "Hilden -> Schloss Benrath" to listOf(GeoPoint(51.16923, 6.92560), GeoPoint(51.16136, 6.87100)),
        "Hilden -> Haan" to listOf(GeoPoint(51.16923, 6.92560), GeoPoint(51.19180, 7.01290)),
        "Hilden -> Langenfeld" to listOf(GeoPoint(51.16923, 6.92560), GeoPoint(51.10990, 6.94820)),
    )
    val fails = ArrayList<String>()
    var all = 0; var late = 0; var missing = 0
    for ((name, wps) in cases.filter { only == null || it.first.startsWith(only) }) {
        val pts = try { Planner(eng).plan(wps, Activity.BIKE_TOURING, 1, (1 until wps.size).toSet()).legs.flatMap { it.points } }
            catch (e: Exception) { println("$name: ${e.message}"); continue }
        if (pts.size < 2) { println("$name: no route"); continue }
        val line = JogRule.approach(pts, hike = false)
        val cum = Geo.cumulative(line)
        val idx = line.indices.filter { line[it].turn != null }
        val needed = JogRule.needed(line, cum, idx, hike = false)
        val at = idx.indices.filter { needed[it] }.map { cum[idx[it]] }
        val total = cum.last()
        for (kmh in (System.getenv("SPEEDS")?.split(",")?.map { it.toDouble() } ?: listOf(15.0, 20.0, 25.0))) {
            val v = kmh / 3.6
            val g = VoiceGuide()
            // the heard turn words: junction along → seconds before reaching it (own "now" sentence only)
            val heardNow = HashMap<Double, Double>()
            data class Said(val tS: Double, val text: String, val words: List<Pair<Double, Double>>) // (junction, turn word at s)
            var playing: Said? = null
            val heard = ArrayList<Pair<Double, Double>>() // (junction, time heard)
            val named = HashSet<Double>()                  // junctions named as "danach" in the sentence of the one before
            fun flush(untilS: Double) {
                playing?.let { p -> p.words.filter { it.second <= untilS }.forEach { heard += it } }
                playing = null
            }
            var tS = 0.0
            while (v * (tS - FIX_AGE_S) <= total + 30) {
                val fixAlong = (v * (tS - FIX_AGE_S)).coerceAtLeast(0.0)
                val ph = g.update(VoiceGuide.Input((tS * 1000).toLong(), pts, 1, fixAlong, true, v, true, fixAgeMs = (FIX_AGE_S * 1000).toLong(),
                    speechStartMs = (TTS_START_S * 1000).toLong()))
                if (ph != null) {
                    val start = tS + TTS_START_S
                    flush(start)
                    val text = VoiceText.of(ph)
                    val words = ArrayList<Pair<Double, Double>>()
                    if (ph is Phrase.Junction && ph.inM < VoiceGuide.SOON_M) {
                        // the turn word comes after "Now turn" / "Gleich": the first junction ahead not yet given its own now
                        val j = ph.atM ?: at.firstOrNull { it >= fixAlong - JunctionCues.PASSED_M && it !in heardNow.keys && heard.none { h -> h.first == it } }
                        if (j != null) words += j to (start + WORD_S)
                        if (j != null && ph.then != null) at.firstOrNull { it > j }?.let { named += it }
                    }
                    if (dump) println("  %5.1f s  %6.0f m (fix %6.0f)  %s".format(tS, v * tS, fixAlong, text))
                    playing = Said(start, text, words)
                }
                tS += 1.0
            }
            flush(Double.MAX_VALUE)
            for ((j, t) in heard) if (j !in heardNow) heardNow[j] = j / v - t
            val leads = at.map { heardNow[it] }
            // a turn closer after the one before than the delays and one second of riding (CLOSE_S) can have no "now" of
            // its own heard in time – it must be named as "danach" with the one before (and, if said itself, before it)
            val close = at.indices.filter { k -> k > 0 && at[k] - at[k - 1] < v * CLOSE_S && at[k] in named }.map { at[it] }.toSet()
            val miss = at.indices.count { leads[it] == null && at[it] !in close }
            val bad = at.indices.count { k -> leads[k] != null && leads[k]!! < (if (at[k] in close) 0.5 else MIN_HEARD_S) }
            all += at.size; late += bad; missing += miss
            val ok = leads.filterNotNull().sorted()
            println("%-26s %2.0f km/h  %3d turns  own now: min %5.1f s  median %5.1f s  under %.0f s: %2d  none: %2d".format(
                name, kmh, at.size, ok.firstOrNull() ?: Double.NaN, ok.getOrNull(ok.size / 2) ?: Double.NaN, MIN_HEARD_S, bad, miss))
            if (dump) at.forEachIndexed { k, a -> println("    %6.0f m  %s%s".format(a, heardNow[a]?.let { "heard %.1f s before".format(it) } ?: "NO NOW",
                if (a in close) "  (${(a - at[k - 1]).toInt()} m after the one before: named as \"danach\")" else "")) }
            if (bad > 0 || miss > 0) fails += "$name @ ${kmh.toInt()} km/h: $bad late, $miss without a now"
        }
    }
    println("turns %d, heard under %.0f s: %d, no own now: %d".format(all, MIN_HEARD_S, late, missing))
    println(if (fails.isEmpty()) "PASS" else "FAIL – " + fails.joinToString("; "))
    if (fails.isNotEmpty()) System.exit(1)
}
