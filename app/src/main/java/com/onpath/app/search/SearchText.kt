package com.onpath.app.search

import java.text.Normalizer
import java.util.Locale

/**
 * Text normalisation and word matching for the offline search. Pure Kotlin (unit-tested).
 *
 * "Meraner Hütte" → "meraner hutte", "Weißkugel" → "weisskugel", "Pippo’s" → "pippos".
 * German and Italian names both work because accents are stripped, not transliterated
 * (ä → a, so "Hutte" and "Hütte" match; "Huette" is one typo away and also matches).
 */
object SearchText {

    private val MARKS = Regex("\\p{M}+")
    private val APOSTROPHES = Regex("['’`´]")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    fun normalize(s: String?): String {
        if (s.isNullOrBlank()) return ""
        val lower = s.lowercase(Locale.ROOT).replace("ß", "ss").replace("æ", "ae").replace("ø", "o").replace("ł", "l")
        val noMarks = MARKS.replace(Normalizer.normalize(lower, Normalizer.Form.NFD), "")
        return NON_WORD.replace(APOSTROPHES.replace(noMarks, ""), " ").trim()
    }

    fun tokens(s: String?): List<String> = normalize(s).split(' ').filter { it.isNotEmpty() }

    /** How well one query word matches one name word. Higher is better, 0 = no match. */
    enum class Match(val score: Double) { NONE(0.0), TYPO(0.45), INFIX(0.6), PREFIX(0.85), EXACT(1.0) }

    /**
     * Rules: exact word; prefix ("mer" → "meraner"); inside a compound word for 3+ letters
     * ("joch" → "hochjoch"); one typo (edit, swap, missing/extra letter) for words of 5+ letters,
     * also against the start of a longer word ("hochgang" → "hochganghaus" with one typo).
     */
    fun matchWord(q: String, t: String): Match {
        if (q.isEmpty() || t.isEmpty()) return Match.NONE
        if (q == t) return Match.EXACT
        if (t.startsWith(q)) return Match.PREFIX
        if (q.length >= 3 && t.contains(q)) return Match.INFIX
        if (q.length >= 5 && withinOneEdit(q, t)) return Match.TYPO
        return Match.NONE
    }

    private fun withinOneEdit(q: String, t: String): Boolean {
        if (kotlin.math.abs(q.length - t.length) <= 1 && osaAtMostOne(q, t)) return true
        // Typo inside a prefix of a longer word: compare with the start of t (same length ±1).
        if (t.length > q.length) {
            for (len in (q.length - 1)..(q.length + 1)) {
                if (len in 1 until t.length && osaAtMostOne(q, t, len)) return true
            }
        }
        return false
    }

    /**
     * True when [a] and the first [lb] chars of [b] differ by at most one substitution, insertion,
     * deletion or adjacent swap. No allocations (runs for every word of the index).
     */
    fun osaAtMostOne(a: String, b: String, lb: Int = b.length): Boolean {
        val la = a.length
        if (kotlin.math.abs(la - lb) > 1) return false
        var i = 0
        while (i < la && i < lb && a[i] == b[i]) i++
        if (i == la && i == lb) return true
        if (la == lb) {
            // substitution or adjacent swap at i
            if (a.regionMatches(i + 1, b, i + 1, la - i - 1)) return true
            return i + 1 < la && a[i] == b[i + 1] && a[i + 1] == b[i] && a.regionMatches(i + 2, b, i + 2, la - i - 2)
        }
        // one insertion/deletion at i
        return if (la > lb) a.regionMatches(i + 1, b, i, lb - i) else b.regionMatches(i + 1, a, i, la - i)
    }

    /** Words that name a kind ("hütte", "rifugio", "bahnhof" …): the query may use them instead of a name. */
    val kindWords: Map<String, Set<String>> = buildKindWords()

    private fun buildKindWords(): Map<String, Set<String>> {
        val m = HashMap<String, Set<String>>()
        fun add(kinds: Set<String>, vararg words: String) {
            for (w in words) { val k = normalize(w); m[k] = (m[k] ?: emptySet()) + kinds }
        }
        val huts = setOf("hut", "bivouac", "shelter")
        add(huts, "hütte", "hütten", "hut", "huts", "schutzhütte", "berghütte", "rifugio", "baita", "refuge")
        add(setOf("bivouac"), "biwak", "bivacco", "bivouac")
        add(setOf("shelter"), "unterstand", "shelter")
        add(setOf("peak"), "gipfel", "peak", "berg", "cima", "monte", "summit")
        add(setOf("saddle"), "pass", "passo", "joch", "sattel", "saddle", "forcella")
        add(setOf("viewpoint"), "aussicht", "aussichtspunkt", "viewpoint", "view", "belvedere", "panorama")
        add(setOf("water"), "wasser", "trinkwasser", "brunnen", "water", "fontana", "fountain")
        add(setOf("spring"), "quelle", "spring", "sorgente")
        add(setOf("waterfall"), "wasserfall", "waterfall", "cascata")
        add(setOf("train"), "bahnhof", "station", "train", "stazione", "zug", "bahn")
        add(setOf("bus"), "bus", "bushaltestelle", "haltestelle", "fermata", "stop")
        add(setOf("lift"), "seilbahn", "lift", "gondel", "funivia", "bergbahn", "cable", "sessellift", "station")
        add(setOf("parking"), "parkplatz", "parking", "parcheggio")
        add(setOf("restaurant"), "restaurant", "gasthaus", "gasthof", "ristorante", "essen", "food", "trattoria", "pizzeria")
        add(setOf("cafe"), "cafe", "café", "kaffee", "coffee")
        add(setOf("bar"), "bar", "pub", "kneipe")
        add(setOf("camping"), "camping", "campingplatz", "campsite", "zelt")
        add(setOf("castle"), "burg", "schloss", "castello", "castle")
        add(setOf("ruins"), "ruine", "ruins", "rovine")
        add(setOf("church", "chapel"), "kirche", "kapelle", "church", "chapel", "chiesa")
        add(setOf("toilets"), "wc", "toilette", "toiletten", "toilets", "bagno")
        add(setOf("museum"), "museum", "museo")
        add(setOf("place"), "dorf", "ort", "stadt", "village", "town", "paese", "citta")
        add(setOf("trail"), "weg", "steig", "trail", "sentiero", "waalweg", "höhenweg")
        add(setOf("shop"), "laden", "geschäft", "supermarkt", "shop", "negozio")
        add(setOf("bike_shop"), "fahrrad", "radwerkstatt", "bike", "bici")
        add(setOf("charging"), "ladestation", "laden", "charging")
        add(setOf("info"), "info", "tourismusbüro", "information")
        return m
    }

    /** Kinds named by query word [q] (exact, or prefix for 4+ letters). */
    fun kindsFor(q: String): Set<String> {
        kindWords[q]?.let { return it }
        if (q.length < 4) return emptySet()
        var out: Set<String> = emptySet()
        for ((w, kinds) in kindWords) if (w.startsWith(q)) out = out + kinds
        return out
    }
}
