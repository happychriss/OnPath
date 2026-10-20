package com.onpath.app.search

import com.onpath.app.data.Geo
import com.onpath.app.debug.Problems
import java.io.Closeable
import java.io.File

/** Minimal read-only SQL access (Android SQLite on the phone, JDBC in unit tests). */
interface AddressDb : Closeable {
    /** Rows as arrays of Long / String / null. Only string arguments are bound; ids are inlined. */
    fun rows(sql: String, vararg args: String): List<Array<Any?>>
}

/** One address hit before distance ranking: [text] 0..~1.35, [tier] as in [SearchHit]. */
data class AddressHit(val place: Place, val text: Double, val tier: Int)

/**
 * Offline address search over `addresses.sqlite` (built by tools/extract_addresses.py; schema in that script).
 * One file per region or map cell; [AddressIndex] merges several. Never throws: a broken file gives no results.
 */
class AddressFile(private val db: AddressDb, val key: String) : Closeable {

    class Town(val id: Long, val kind: String, val name: String, val alt: List<String>, val cc: String?,
               val lat: Double, val lon: Double) {
        val norms: List<String> = (listOf(name) + alt).map { SearchText.normalize(it) }.filter { it.isNotEmpty() }.distinct()
        val words: Set<String> = norms.flatMap { it.split(' ') }.toSet()
    }

    private class Street(val id: Long, val name: String, val alt: List<String>, val town: Long?, val loc: Long?,
                         val pc: String?, val pseudo: Int, val lat: Double, val lon: Double)

    val towns: Map<Long, Town>
    val defaultCc: String?

    init {
        towns = db.rows("SELECT id, kind, name, alt, cc, lat, lon FROM town").associate { r ->
            val id = r[0] as Long
            id to Town(id, r[1] as String, r[2] as String, split(r[3]), r[4] as String?, e5(r[5]), e5(r[6]))
        }
        defaultCc = db.rows("SELECT v FROM meta WHERE k = 'cc'").firstOrNull()?.get(0) as String?
    }

    override fun close() = db.close()

    /** Country of the nearest town within 30 km, or null. */
    fun countryNear(lat: Double, lon: Double): String? {
        val t = towns.values.filter { it.cc != null }.minByOrNull { Geo.distance(lat, lon, it.lat, it.lon) } ?: return null
        return if (Geo.distance(lat, lon, t.lat, t.lon) < 30_000) t.cc else null
    }

    /** Towns whose full name (any language) is a run of query words: "bolzano" → Bozen. */
    fun townsNamed(words: List<String>): List<Town> {
        val runs = HashSet<String>()
        for (i in words.indices) for (j in i + 1..words.size) runs += words.subList(i, j).joinToString(" ")
        return towns.values.filter { t -> t.norms.any { it in runs } }
    }

    fun search(q: AddressQuery, max: Int = 30): List<AddressHit> {
        val words = q.words.filter { it.length >= 2 }.ifEmpty { q.words }
        if (words.isEmpty()) return emptyList()
        val streetSets = words.map { w -> streetsWithPrefix(w) }.toMutableList()
        val townSets = words.map { w -> towns.values.filter { t -> t.words.any { it.startsWith(w) } }.map { it.id }.toSet() }
        // Typo tolerance: a word that matches nothing ("garibali") is retried with up to 1 (2 for long words) wrong
        // letters; such matches rank lower (tier 1).
        val fuzzy = HashSet<Int>()
        for (i in words.indices) if (streetSets[i].isEmpty() && townSets[i].isEmpty() && words[i].length >= 5) {
            streetSets[i] = streetsFuzzy(words[i]); if (streetSets[i].isNotEmpty()) fuzzy += i
        }
        val anchor = streetSets.withIndex().filter { it.value.isNotEmpty() }.minByOrNull { it.value.size } ?: return emptyList()
        val matched = words.indices.filter { streetSets[it].isNotEmpty() || townSets[it].isNotEmpty() }
        val streets = streetRows(anchor.value).filter { s ->
            matched.all { i -> s.id in streetSets[i] || s.town in townSets[i] || s.loc in townSets[i] }
        }
        val out = ArrayList<AddressHit>()
        for (s in streets) {
            val sWords = (listOf(s.name) + s.alt).flatMap { SearchText.tokens(it) }.flatMap { listOf(it) + AddressQuery.splitSuffix(it) }.toSet()
            val byStreet = matched.filter { s.id in streetSets[it] }.map { words[it] }
            var score = 0.0
            for (i in matched) {
                val w = words[i]
                score += if (i in fuzzy) 0.7 else if (s.id in streetSets[i]) (if (w in sWords) 1.0 else 0.85) else 0.9
            }
            score = score / words.size + (if (q.postcode != null && q.postcode == s.pc) 0.1 else 0.0)
            val joined = byStreet.joinToString(" ")
            val exactName = (listOf(s.name) + s.alt).any { SearchText.normalize(it).replace(" ", "") == joined.replace(" ", "") }
            var tier = when { exactName -> 3; byStreet.all { it in sWords } -> 2; else -> 1 }
            if (matched.size < words.size || fuzzy.isNotEmpty()) tier = 1
            val house = q.number?.let { n -> houseOf(s.id, n) }
            if (house != null) out += AddressHit(place(s, house.first, house.second, house.third), score + 0.05, tier)
            out += AddressHit(place(s, null, s.lat, s.lon), score - if (q.number != null) 0.1 else 0.0,
                if (q.number != null && house == null) tier else minOf(tier, 2))
            if (out.size >= max) break
        }
        return out
    }

    /** Streets with a word that starts like [w] give or take one or two wrong letters (first two letters exact). */
    private fun streetsFuzzy(w: String): Set<Long> {
        val head = w.take(2)
        val maxD = if (w.length >= 8) 2 else 1
        val cands = db.rows("SELECT DISTINCT w FROM word WHERE w >= ? AND w < ? LIMIT 20000", head, upper(head))
            .mapNotNull { it[0] as? String }
            .filter { c -> (-1..1).any { d -> val n = w.length + d; n in 1..c.length && editDistance(w, c.take(n)) <= maxD } }
            .take(200)
        if (cands.isEmpty()) return emptySet()
        return cands.chunked(100).flatMap { chunk ->
            db.rows("SELECT street FROM word WHERE w IN (${chunk.joinToString(",") { "?" }}) LIMIT 5000", *chunk.toTypedArray())
                .map { it[0] as Long }
        }.toSet()
    }

    private fun streetsWithPrefix(w: String): Set<Long> =
        db.rows("SELECT street FROM word WHERE w >= ? AND w < ? LIMIT 5000", w, upper(w)).map { it[0] as Long }.toSet()

    private fun streetRows(ids: Set<Long>): List<Street> = ids.chunked(500).flatMap { chunk ->
        db.rows("SELECT id, name, alt, town, loc, pc, p, lat, lon FROM street WHERE id IN (${chunk.joinToString(",")})").map { r ->
            Street(r[0] as Long, r[1] as String, split(r[2]), r[3] as Long?, r[4] as Long?, r[5] as String?,
                (r[6] as Long).toInt(), e5(r[7]), e5(r[8]))
        }
    }

    /** Exact house number, else the first one starting with it ("7" → "7a"). */
    private fun houseOf(street: Long, n: String): Triple<String, Double, Double>? {
        val want = AddressQuery.normNumber(n)
        val rows = db.rows("SELECT num, lat, lon FROM house WHERE street = $street")
        val r = rows.firstOrNull { AddressQuery.normNumber(it[0] as String) == want }
            ?: rows.firstOrNull { AddressQuery.normNumber(it[0] as String).let { x -> x.startsWith(want) && !x[want.length].isDigit() } }
            ?: return null
        return Triple(r[0] as String, e5(r[1]), e5(r[2]))
    }

    private fun place(s: Street, num: String?, lat: Double, lon: Double): Place {
        val town = s.town?.let { towns[it] }
        val loc = s.loc?.let { towns[it] }
        val label = listOfNotNull(s.name, num).joinToString(" ")
        val townName = town?.name ?: loc?.name
        val other = s.alt.firstOrNull()?.let { listOfNotNull(it, num).joinToString(" ") }
        return Place(
            id = "a:$key:${s.id}" + (num?.let { ":$it" } ?: ""),
            name = listOfNotNull(label, townName).joinToString(" · "),
            kind = if (num != null || s.pseudo == 1) "address" else "street",
            lat = lat, lon = lon,
            address = listOfNotNull(other, loc?.name?.takeIf { it != townName }, s.pc).joinToString(" · ").ifEmpty { null },
            source = Place.Source.MAP,
            alt = listOfNotNull(label) + s.alt.map { listOfNotNull(it, num).joinToString(" ") },
            cc = town?.cc ?: loc?.cc ?: defaultCc,
            town = townName,
        )
    }

    companion object {
        private fun e5(v: Any?) = ((v as? Long) ?: 0L) / 1e5
        private fun split(v: Any?) = (v as? String)?.split('|')?.filter { it.isNotEmpty() } ?: emptyList()

        /** Levenshtein distance (insert, delete, replace = 1). */
        fun editDistance(a: String, b: String): Int {
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1); cur[0] = i
                for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = cur
            }
            return prev[b.length]
        }

        /** Smallest string greater than every string starting with [w]. */
        fun upper(w: String): String = w.dropLast(1) + (w.last() + 1)
    }
}

/** All address files of the data folder: `addresses.sqlite` plus `addresses/` (one file per map cell). */
class AddressIndex(val files: List<AddressFile>, val signature: String = "") : Closeable {

    fun search(q: AddressQuery): List<AddressHit> {
        val all = files.flatMap { f -> safe { f.search(q) } ?: emptyList() }
        // the same street from two cells: keep one per name within 2 km
        val out = ArrayList<AddressHit>()
        for (h in all) if (out.none { it.place.name == h.place.name &&
                Geo.distance(it.place.lat, it.place.lon, h.place.lat, h.place.lon) < 2000 }) out += h
        return out
    }

    fun countryNear(lat: Double, lon: Double): String? = files.firstNotNullOfOrNull { safe { it.countryNear(lat, lon) } }

    /** Normalised names (all languages) of the towns named in the query, and one point to centre an online search on. */
    fun townsNamed(words: List<String>): List<AddressFile.Town> = files.flatMap { f -> safe { f.townsNamed(words) } ?: emptyList() }

    override fun close() { files.forEach { safe { it.close() } } }

    private inline fun <T> safe(block: () -> T): T? = try { block() } catch (e: Exception) {
        Problems.warn("search", "Offline address file unreadable; no offline addresses from it", e); null
    }

    companion object {
        const val FILE = "addresses.sqlite"
        const val DIR = "addresses"

        fun sources(dataDir: File): List<File> =
            (listOf(File(dataDir, FILE)) + (File(dataDir, DIR).listFiles { f -> f.name.endsWith(".sqlite") }?.sortedBy { it.name } ?: emptyList()))
                .filter { it.isFile }

        fun signature(dataDir: File): String = sources(dataDir).joinToString(";") { "${it.name}:${it.length()}:${it.lastModified()}" }

        /** Opens every address file; a broken one is skipped and logged. */
        fun open(dataDir: File, opener: (File) -> AddressDb): AddressIndex {
            val files = sources(dataDir).mapNotNull { f ->
                try { AddressFile(opener(f), f.nameWithoutExtension) } catch (e: Exception) {
                    Problems.warn("search", "Offline address file ${f.name} unreadable; skipped", e); null
                }
            }
            return AddressIndex(files, signature(dataDir))
        }
    }
}

/** [AddressDb] on Android's SQLite, read-only. */
class AndroidAddressDb(file: File) : AddressDb {
    // Own error handler: Android's default one DELETES a database file it finds corrupt – a map data file must stay
    // (the query fails, AddressIndex skips the file and reports it).
    private val db = android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null,
        android.database.sqlite.SQLiteDatabase.OPEN_READONLY or android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        { Problems.warn("search", "Offline address file ${file.name} is corrupt; not deleted, no addresses from it") })

    override fun rows(sql: String, vararg args: String): List<Array<Any?>> = db.rawQuery(sql, args).use { c ->
        val out = ArrayList<Array<Any?>>()
        while (c.moveToNext()) out += Array(c.columnCount) { i ->
            when (c.getType(i)) {
                android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                android.database.Cursor.FIELD_TYPE_NULL -> null
                android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                else -> c.getString(i)
            }
        }
        out
    }

    override fun close() = db.close()
}
