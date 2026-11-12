package com.onpath.app.ui.routes

import com.onpath.app.data.Tour

/**
 * The order of My routes (owner 2026-11-12, variant C "chip row", like Google Maps' filter chips): Neueste zuerst
 * (the default), Älteste zuerst, Länge, Dauer, Anstieg. A tap on the chosen chip turns Länge / Dauer / Anstieg round
 * (biggest first at the first tap); the date has its two chips. One choice for both tabs, kept across app starts
 * ([encode] / [decode] in Prefs). It orders the saved routes / done tours; "Zuletzt" stays newest first. Ties: the newest
 * first, then the higher id – the same list always comes in the same order. Difficulty comes later (owner: "erst ohne,
 * später dazu"; the stored `tours.difficulty` waits for it). Pure.
 */
object RouteSort {
    enum class Key(val hasDirection: Boolean) { NEWEST(false), OLDEST(false), LENGTH(true), DURATION(true), ASCENT(true) }

    /** [biggestFirst] = longest / most first; only read for a key with a direction. */
    data class Choice(val key: Key = Key.NEWEST, val biggestFirst: Boolean = true)

    val DEFAULT = Choice()

    /** A tap on [key]'s chip: the chosen one with a direction turns round, any other is chosen biggest first. */
    fun tap(current: Choice, key: Key): Choice = when {
        key != current.key -> Choice(key)
        key.hasDirection -> current.copy(biggestFirst = !current.biggestFirst)
        else -> current
    }

    /** [tours] in the order of [c] (a new list; the input stays as it is). */
    fun sort(tours: List<Tour>, c: Choice): List<Tour> {
        val newest = compareByDescending<Tour> { it.createdMs }.thenByDescending { it.id }
        fun by(value: (Tour) -> Double): Comparator<Tour> =
            (if (c.biggestFirst) compareByDescending(value) else compareBy(value)).then(newest)
        return tours.sortedWith(when (c.key) {
            Key.NEWEST -> newest
            Key.OLDEST -> compareBy<Tour> { it.createdMs }.thenBy { it.id }
            Key.LENGTH -> by { it.distanceM }
            Key.DURATION -> by { it.durationS }
            Key.ASCENT -> by { it.ascentM }
        })
    }

    /** The stored form: "LENGTH:down" (biggest first) / "LENGTH:up". */
    fun encode(c: Choice): String = "${c.key.name}:${if (c.biggestFirst) "down" else "up"}"

    /** Back from [encode]; nothing stored or unknown (an older or newer build) → [DEFAULT]. */
    fun decode(s: String?): Choice {
        val parts = s?.split(':') ?: return DEFAULT
        val key = Key.entries.firstOrNull { it.name == parts[0] } ?: return DEFAULT
        return Choice(key, parts.getOrNull(1) != "up")
    }
}
