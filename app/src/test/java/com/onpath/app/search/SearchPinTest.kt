package com.onpath.app.search

import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale
import kotlin.random.Random

/**
 * Pins the offline search's answers (order, tier, score, distance, near-route flag and the place's details) on a
 * synthetic map folder of ~6000 POIs, trail names and the mini base map, built the way the app builds it
 * ([SearchAreas]). The expected answers were recorded from the in-memory index (0.12.0) before the index moved to
 * SQLite; any change in ranking shows here. Re-record only for an intended ranking change: delete
 * `src/test/resources/search/pin-expected.txt`, run the test (it writes `app/build/pin-actual.txt`), copy that back.
 */
class SearchPinTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun answersStayTheSame() {
        val data = tmp.newFolder("data")
        SearchFixture.write(data)
        val files = tmp.newFolder("files")
        val idx = SearchFixture.areas(File(files, "search-areas")).build(AreaPlan.of(data))
        val actual = SearchFixture.answers { idx.search(it) }
        // after an app start (index read back from its files): the same answers
        val again = SearchFixture.areas(File(files, "search-areas")).build(AreaPlan.of(data))
        assertEquals(actual, SearchFixture.answers { again.search(it) })
        val expected = javaClass.classLoader!!.getResourceAsStream("search/pin-expected.txt")?.bufferedReader()?.readText()
        if (expected == null) {
            File("build/pin-actual.txt").writeText(actual)
            throw AssertionError("no search/pin-expected.txt – recorded to build/pin-actual.txt")
        }
        // line by line: a failure names the query
        val e = expected.lines(); val a = actual.lines()
        for (i in 0 until maxOf(e.size, a.size)) assertEquals("line ${i + 1}", e.getOrNull(i), a.getOrNull(i))
    }
}

/** A deterministic map folder with many look-alike names (umlauts, ß, accents, apostrophes, bilingual, unnamed). */
object SearchFixture {
    private val first = listOf("Meraner", "Zoggler", "Ultner", "Algunder", "St. Pankraz", "Weißkugel", "Hochgang", "Leiter",
        "Sankt Leonhard", "Pippo’s", "Mut", "Hohe", "Kofel", "Spitz", "Zons", "Café", "Gasthof", "Rifugio", "Bivacco", "Malga",
        "Ifinger", "Vöran", "Hafling", "Naturns", "Tscherms", "Lana", "Marling", "Partschins", "Schenna", "Dorf Tirol",
        "Città", "Ærø", "Łódź", "Mühl", "Schloss", "Burg", "Brunnen", "Quelle", "Wasserfall", "Bahnhof")
    private val second = listOf("Hütte", "Alm", "Spitze", "Joch", "Weg", "Waalweg", "Höhenweg", "Rast", "Stube", "Hof",
        "Kapelle", "Kirche", "Brücke", "See", "Platz", "Bühel", "Eck", "", "", "")
    private val kinds = listOf("hut", "restaurant", "cafe", "water", "spring", "viewpoint", "parking", "bus", "train", "lift",
        "bivouac", "shelter", "church", "chapel", "castle", "ruins", "museum", "waterfall", "camping", "toilets", "shop", "bar")

    fun write(dir: File) {
        val r = Random(7)
        val sb = StringBuilder("""{"type":"FeatureCollection","features":[""")
        for (i in 0 until 6000) {
            if (i > 0) sb.append(',')
            val lat = 46.55 + r.nextDouble() * 0.3; val lon = 10.95 + r.nextDouble() * 0.4
            val kind = kinds[r.nextInt(kinds.size)]
            val named = r.nextInt(10) != 0
            val name = if (!named) null else listOf(first[r.nextInt(first.size)], second[r.nextInt(second.size)],
                if (r.nextInt(6) == 0) "${r.nextInt(30)}" else "").filter { it.isNotEmpty() }.joinToString(" ")
            val props = StringBuilder("\"id\":\"n$i\",\"kind\":\"$kind\"")
            if (name != null) props.append(",\"name\":\"${name.replace("\"", "")}\"")
            if (r.nextInt(3) == 0) props.append(",\"ele\":\"${1000 + r.nextInt(2000)}\"")
            if (r.nextInt(4) == 0) props.append(",\"opening_hours\":\"Mo-Su 08:00-18:00; Tu off\"")
            if (r.nextInt(5) == 0) props.append(",\"phone\":\"+39 0473 ${r.nextInt(99999)}\"")
            if (r.nextInt(6) == 0) props.append(",\"website\":\"https://example.org/$i\"")
            if (r.nextInt(9) == 0) props.append(",\"wikipedia\":\"de:Ort $i\"")
            if (kind == "restaurant" && r.nextInt(2) == 0) props.append(",\"cuisine\":\"regional;pizza\"")
            sb.append("""{"type":"Feature","geometry":{"type":"Point","coordinates":[$lon,$lat]},"properties":{$props}}""")
        }
        sb.append("]}")
        File(dir, OfflineIndex.POIS).writeText(sb.toString())
        val t = StringBuilder("""{"type":"FeatureCollection","features":[""")
        for (i in 0 until 400) {
            if (i > 0) t.append(',')
            val name = "${first[r.nextInt(12)]} ${listOf("Waalweg", "Höhenweg", "Steig", "Weg")[r.nextInt(4)]}"
            val lat = 46.55 + r.nextDouble() * 0.3; val lon = 10.95 + r.nextDouble() * 0.4
            val tt = if (r.nextInt(8) == 0) "r" else "n"
            t.append("""{"type":"Feature","geometry":{"type":"LineString","coordinates":[[$lon,$lat],[${lon + 0.004},${lat + 0.002}],[${lon + 0.008},$lat]]},""" +
                """"properties":{"t":"$tt","name":"$name","len":${100 + r.nextInt(5000)}}}""")
        }
        t.append("]}")
        File(dir, OfflineIndex.TRAILS).writeText(t.toString())
        SearchFixture::class.java.classLoader!!.getResourceAsStream("search/mini.pmtiles")!!.use { File(dir, OfflineIndex.BASE).outputStream().use(it::copyTo) }
    }

    fun areas(dir: File) = SearchAreas(dir, opener = JdbcSearchDb.opener)

    val route = listOf(GeoPoint(46.60, 11.00), GeoPoint(46.65, 11.10), GeoPoint(46.70, 11.15), GeoPoint(46.75, 11.30))

    /** (text, category, with route) – single letters, prefixes, typos, compounds, kind words, several words. */
    val queries: List<Triple<String, SearchKinds.Category?, Boolean>> = listOf(
        "m", "me", "mer", "meran", "merano", "meraner hutte", "meraner hütte", "Meraner Hütte 12", "hutte", "hütte", "huette",
        "rifugio", "joch", "hochgang", "hochgangg", "hcohgang", "weisskugel", "weißkugel", "pippos", "pippo's", "st pank",
        "sankt leon", "zons", "zonser", "algunder waal", "waalweg", "höhenweg", "a", "z", "x", "brunnen", "quelle", "wasser",
        "bahnhof", "station", "gipfel", "dorf", "lodz", "aero", "citta", "cafe", "café", "schloss", "kirche", "12", "alm 3",
        "leiter alm", "ultner hof", "mut spitze", "hohe weisse", "   ", "-", "ultnr", "kofel see", "parkplatz", "wc",
    ).map { Triple(it, null, false) } + listOf(
        Triple("", SearchKinds.Category.FOOD, false), Triple("", SearchKinds.Category.SIGHTS, false),
        Triple("", SearchKinds.Category.WATER, true), Triple("zog", SearchKinds.Category.FOOD, false),
        Triple("alm", SearchKinds.Category.TRANSIT, true), Triple("", SearchKinds.Category.PLACES, false),
        Triple("", SearchKinds.Category.PARKING, false),
        Triple("mer", null, true), Triple("hütte", null, true), Triple("weg", null, true), Triple("a", null, true),
    )

    private fun f(d: Double) = String.format(Locale.ROOT, "%.6f", d)

    fun place(p: Place) = "${p.id}|${p.name}|${p.kind}|${p.sub}|${p.lat}|${p.lon}|${p.ele}|${p.alt}|${p.source}|${p.poi}"

    /** Every query's answer as text lines (all hits; the first 5 with the place's details). */
    fun answers(search: (SearchQuery) -> List<SearchHit>): String = buildString {
        for ((text, cat, withRoute) in queries) {
            val q = SearchQuery(text, cat, 46.67, 11.16, if (withRoute) route else null)
            val hits = search(q)
            append("Q '").append(text).append("' ").append(cat).append(if (withRoute) " route" else "").append(" → ").append(hits.size).append('\n')
            hits.forEachIndexed { i, h ->
                append("  ").append(h.place.id).append(' ').append(h.tier).append(' ').append(f(h.score)).append(' ')
                    .append(f(h.distanceM)).append(if (h.nearRoute) " R" else "")
                if (i < 5) append("  ").append(place(h.place))
                append('\n')
            }
        }
    }
}
