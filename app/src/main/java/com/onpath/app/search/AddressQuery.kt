package com.onpath.app.search

/**
 * A search text read as an address: "Laurinstraße 5, Meran", "Meran Laurinstraße", "via Roma 3 Bolzano Italia",
 * "Laurin Str. 7a 39012". Pure (unit-tested).
 *
 * [words]: normalised name words (street and/or town, any order); [number]: house number ("7a");
 * [postcode]: 4–5 digits; [country]: ISO code of a country word ("Italia" → IT), which is not searched as a name.
 */
data class AddressQuery(val words: List<String>, val number: String?, val postcode: String?, val country: String?) {

    val isEmpty: Boolean get() = words.isEmpty()

    companion object {
        private val NUMBER = Regex("^[0-9]+[a-z]?$")
        private val DIGITS = Regex("^[0-9]{4,5}$")

        /** Country names in German, Italian, English and the local language. */
        val COUNTRIES: Map<String, String> = buildMap {
            fun add(cc: String, vararg names: String) { for (n in names) put(SearchText.normalize(n), cc) }
            add("IT", "italia", "italien", "italy", "italie")
            add("AT", "österreich", "oesterreich", "austria", "autriche")
            add("DE", "deutschland", "germania", "germany", "allemagne")
            add("CH", "schweiz", "svizzera", "suisse", "switzerland", "svizra")
            add("FR", "frankreich", "francia", "france")
            add("SI", "slowenien", "slovenia", "slovenija")
            add("LI", "liechtenstein")
        }

        fun parse(text: String): AddressQuery {
            val t = SearchText.tokens(text)
            val words = ArrayList<String>()
            var number: String? = null
            var postcode: String? = null
            var country: String? = null
            var i = 0
            while (i < t.size) {
                val w = t[i]
                when {
                    DIGITS.matches(w) && postcode == null -> postcode = w
                    NUMBER.matches(w) && number == null -> {
                        // "7 a" and "12/b" (→ "12", "b") are one house number
                        val next = t.getOrNull(i + 1)
                        number = if (w.last().isDigit() && next != null && next.length == 1 && next[0] in 'a'..'z') {
                            i++; w + next
                        } else w
                    }
                    w in COUNTRIES && country == null && t.size > 1 -> country = COUNTRIES[w]
                    else -> words += w
                }
                i++
            }
            return AddressQuery(words, number, postcode, country)
        }

        /** House numbers compare without case, spaces and separators: "7 A" = "7a", "12/b" = "12b". */
        fun normNumber(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }
    }
}
