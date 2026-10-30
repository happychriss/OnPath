package com.onpath.app.search

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.sql.DriverManager
import java.util.Locale
import kotlin.random.Random

/**
 * Pins the offline address search's answers (order, tier, text score, name, point, ids) on three synthetic map cells
 * of ~2500 streets each (look-alike German and Italian names, compounds, "Am …", villages, house numbers), written in
 * the schema of tools/extract_addresses.py. Recorded before the address search was made faster for short queries
 * (0.13.0): queries of 3+ letters must answer exactly the same. Re-record only for an intended change: delete
 * `src/test/resources/search/address-pin-expected.txt`, run the test (it writes `app/build/address-pin-actual.txt`),
 * copy that back.
 */
class AddressPinTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun answersStayTheSame() {
        val data = tmp.newFolder("data")
        AddressFixture.write(data)
        val actual = AddressIndex.open(data) { JdbcAddressDb(it) }.use { idx -> AddressFixture.answers(idx, AddressFixture.queries) }
        val expected = javaClass.classLoader!!.getResourceAsStream("search/address-pin-expected.txt")?.bufferedReader()?.readText()
        if (expected == null) {
            File("build/address-pin-actual.txt").writeText(actual)
            throw AssertionError("no search/address-pin-expected.txt – recorded to build/address-pin-actual.txt")
        }
        val e = expected.lines(); val a = actual.lines()
        for (i in 0 until maxOf(e.size, a.size)) assertEquals("line ${i + 1}", e.getOrNull(i), a.getOrNull(i))
    }
}

/** Deterministic address cells (`addresses/<cell>.sqlite`) for the address search tests. */
object AddressFixture {
    private val stems = listOf("Haupt", "Kirch", "Schul", "Linden", "Goethe", "Schiller", "Mozart", "Garten", "Wald", "Markt",
        "Bahnhof", "Rosen", "Berg", "Mühlen", "Kölner", "Düsseldorfer", "Hildener", "Kloster", "Schloss", "Bach", "Wiesen",
        "Eichen", "Birken", "Buchen", "Tannen", "Kastanien", "Ahorn", "Feld", "Dorf", "Hof", "Sonnen", "Stern", "Friedhof",
        "Industrie", "Brunnen", "Quellen", "Weiher", "Teich", "Kapellen", "Laurin", "Garibaldi", "Rom", "Meraner", "Bozner",
        "Zoggler", "Ultner", "Algunder", "St.-Georg", "Sankt-Anna", "Kaiser-Wilhelm", "Yorck", "Xanten", "Quirin", "Zeppelin")
    private val suffixes = listOf("straße", "weg", "gasse", "platz", "allee", "ring", "steig", "-Straße", " Weg", "damm")
    private val prefixes = listOf("Am", "Im", "An der", "Auf dem", "In der", "Zum", "Zur")
    private val italian = listOf("Via", "Vicolo", "Piazza", "Viale")
    private val towns = listOf("Hilden", "Haan", "Solingen", "Langenfeld", "Monheim", "Erkrath", "Meran", "Bozen", "Lana",
        "Algund", "Kaltern", "Eppan")
    private val villages = listOf("Völlan", "Gratsch", "Meide", "Hellerhof", "Rheindorf", "Derendorf", "Pawigl", "Unterbach",
        "Benrath", "Garath", "Holthausen", "Itter", "Gruiten", "Ohligs", "Wald", "Aufderhöhe", "Kloster", "Oberbozen")
    private val suffixWords = listOf("strasse", "gasse", "weg", "platz", "allee", "steig", "promenade", "ring", "damm", "ufer", "stieg")

    const val LAT = 51.17
    const val LON = 6.93

    /** Three cells side by side (a street name may repeat across them, like a street crossing a cell edge). */
    fun write(dataDir: File, cells: Int = 3, streetsPerCell: Int = 2500) {
        val dir = File(dataDir, AddressIndex.DIR).apply { mkdirs() }
        for (c in 0 until cells) writeCell(File(dir, "10-530-34$c.sqlite"), c, streetsPerCell)
    }

    private fun words(names: List<String>): Set<String> {
        val out = HashSet<String>()
        for (n in names) for (t in SearchText.tokens(n)) {
            out += t
            suffixWords.firstOrNull { t.endsWith(it) && t.length > it.length + 2 }?.let { out += t.dropLast(it.length); out += it }
        }
        return out
    }

    private fun writeCell(f: File, cell: Int, n: Int) {
        val r = Random(11 + cell)
        val lat0 = LAT - 0.1 + cell * 0.08; val lon0 = LON - 0.15 + cell * 0.1
        fun e5(d: Double) = Math.round(d * 1e5)
        DriverManager.getConnection("jdbc:sqlite:${f.path}").use { c ->
            c.createStatement().use { st ->
                st.executeUpdate("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT) WITHOUT ROWID")
                st.executeUpdate("CREATE TABLE town(id INTEGER PRIMARY KEY, kind TEXT, name TEXT, alt TEXT, cc TEXT, lat INTEGER, lon INTEGER)")
                st.executeUpdate("CREATE TABLE street(id INTEGER PRIMARY KEY, name TEXT, alt TEXT, town INTEGER, loc INTEGER, pc TEXT, p INTEGER, lat INTEGER, lon INTEGER)")
                st.executeUpdate("CREATE TABLE house(street INTEGER, num TEXT, lat INTEGER, lon INTEGER, PRIMARY KEY(street, num)) WITHOUT ROWID")
                st.executeUpdate("CREATE TABLE word(w TEXT, street INTEGER, PRIMARY KEY(w, street)) WITHOUT ROWID")
            }
            c.autoCommit = false
            c.prepareStatement("INSERT INTO meta VALUES (?,?)").use { st ->
                for ((k, v) in listOf("cc" to "DE", "format" to "1")) { st.setString(1, k); st.setString(2, v); st.executeUpdate() }
            }
            val townPts = ArrayList<Triple<Long, Double, Double>>()
            c.prepareStatement("INSERT INTO town VALUES (?,?,?,?,?,?,?)").use { st ->
                var id = 1L
                for ((i, t) in towns.withIndex()) {
                    if (i % 3 != cell % 3 && i >= 6) continue
                    val la = lat0 + r.nextDouble() * 0.07; val lo = lon0 + r.nextDouble() * 0.09
                    st.setLong(1, id); st.setString(2, if (i % 4 == 0) "city" else "town"); st.setString(3, t)
                    st.setString(4, if (i >= 6) "${t}o|${t}a" else null); st.setString(5, if (i >= 6) "IT" else "DE")
                    st.setLong(6, e5(la)); st.setLong(7, e5(lo)); st.executeUpdate()
                    townPts += Triple(id, la, lo); id++
                }
                for ((i, v) in villages.withIndex()) {
                    val la = lat0 + r.nextDouble() * 0.07; val lo = lon0 + r.nextDouble() * 0.09
                    st.setLong(1, id); st.setString(2, if (i % 3 == 0) "hamlet" else if (i % 3 == 1) "village" else "suburb"); st.setString(3, v)
                    st.setString(4, null); st.setString(5, null); st.setLong(6, e5(la)); st.setLong(7, e5(lo)); st.executeUpdate()
                    townPts += Triple(id, la, lo); id++
                }
            }
            val ws = c.prepareStatement("INSERT OR IGNORE INTO word VALUES (?,?)")
            val hs = c.prepareStatement("INSERT OR IGNORE INTO house VALUES (?,?,?,?)")
            c.prepareStatement("INSERT INTO street VALUES (?,?,?,?,?,?,?,?,?)").use { st ->
                for (id in 1L..n.toLong()) {
                    val stem = stems[r.nextInt(stems.size)]
                    val name = when (r.nextInt(10)) {
                        0, 1 -> "${prefixes[r.nextInt(prefixes.size)]} $stem${listOf("", "feld", "bach", "berg")[r.nextInt(4)]}"
                        2 -> "${italian[r.nextInt(italian.size)]} $stem"
                        else -> stem + suffixes[r.nextInt(suffixes.size)]
                    }
                    val alt = if (r.nextInt(5) == 0) "Via ${stem.lowercase().replaceFirstChar { it.uppercase() }}" else null
                    val la = lat0 + r.nextDouble() * 0.07; val lo = lon0 + r.nextDouble() * 0.09
                    val town = townPts[r.nextInt(minOf(6, townPts.size))].first
                    val loc = if (r.nextInt(3) == 0) townPts[r.nextInt(townPts.size)].first else null
                    st.setLong(1, id); st.setString(2, name); st.setString(3, alt); st.setLong(4, town)
                    if (loc == null) st.setNull(5, java.sql.Types.INTEGER) else st.setLong(5, loc)
                    st.setString(6, "40${700 + r.nextInt(30)}"); st.setLong(7, if (r.nextInt(15) == 0) 1 else 0)
                    st.setLong(8, e5(la)); st.setLong(9, e5(lo)); st.executeUpdate()
                    for (w in words(listOfNotNull(name, alt))) { ws.setString(1, w); ws.setLong(2, id); ws.executeUpdate() }
                    if (r.nextInt(2) == 0) for (k in 0 until 1 + r.nextInt(12)) {
                        hs.setLong(1, id); hs.setString(2, "${1 + r.nextInt(40)}${if (r.nextInt(8) == 0) "a" else ""}")
                        hs.setLong(3, e5(la + r.nextDouble() * 0.002)); hs.setLong(4, e5(lo + r.nextDouble() * 0.002)); hs.executeUpdate()
                    }
                }
            }
            ws.close(); hs.close()
            c.commit()
        }
    }

    /** 3+ letters: street words, compounds, abbreviations, house numbers, towns, villages, Italian, typos, no match. */
    val queries = listOf(
        "kol", "koln", "koelner", "kölner str", "kölner straße 12", "hil", "hild", "hildener", "dus", "dussel",
        "duesseldorfer", "sch", "schul", "schulstrasse", "schulstraße 5", "schul str 7a", "haupt", "hauptstr", "hauptstraße 3",
        "hauptstrasse hilden", "hilden hauptstraße", "am", "am mühlen", "im weiher", "an der kirch", "auf dem berg", "zum",
        "berg", "bergfeld", "kirch", "kirchweg", "garten", "wald", "markt", "bahnhof", "linden", "rosen", "mozart",
        "goethe", "goethestr 12", "goethe straße 12 meran", "via roma", "via rom 3", "via roma bozen", "via garibaldi",
        "garibaldistrasse", "garibali", "garibaldy", "laurin", "laurinstr 7", "laurin str. 7a", "rom", "piazza", "vicolo",
        "vollan", "völlan", "gratsch", "lindenstraße gratsch", "hellerhof", "meide", "st georg", "sankt anna", "st.-georg",
        "kaiser wilhelm", "yorck", "xanten", "quirin", "zeppelin", "zeppelinstrasse 1", "strasse", "weg", "gasse",
        "platz", "allee", "40712", "40712 haupt", "lindenstraße 40705", "zzz", "qqq", "mühl", "muhl", "muehl",
        "schillerweg 9", "schiller weg", "birkenallee", "tannensteig", "industrie", "industriering", "friedhof", "brunnen",
        "quellen", "weiher", "teich", "kapellen", "eichen", "buchen", "kastanien", "ahorn", "feld", "dorf", "hof",
        "sonnen", "stern", "meraner", "bozner", "zoggler", "ultner", "algunder", "italia via roma", "haupt italia",
    )

    private fun f(d: Double) = String.format(Locale.ROOT, "%.6f", d)

    /** Every query's address hits as text lines in the order the search returns them (the first 5 with details). */
    fun answers(idx: AddressIndex, qs: List<String>): String = buildString {
        for (t in qs) {
            val hits = idx.search(AddressQuery.parse(t))
            append("Q '").append(t).append("' → ").append(hits.size).append('\n')
            hits.forEachIndexed { i, h ->
                val p = h.place
                append("  ").append(p.id.removePrefix("a:10-530-")).append(' ').append(h.tier).append(' ').append(f(h.text))
                if (i < 5) append("  ").append(p.name).append('|').append(p.kind).append('|').append(f(p.lat)).append(',').append(f(p.lon))
                    .append('|').append(p.address).append('|').append(p.alt).append('|').append(p.cc).append('|').append(p.town)
                    .append('|').append(p.village)
                append('\n')
            }
        }
    }
}
