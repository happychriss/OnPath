package com.onpath.app.navigation

import com.onpath.app.data.Format
import com.onpath.strings.*
import kotlin.math.roundToInt

/**
 * A [Phrase] as a sentence in the app's language, for the phone's text-to-speech (`docs/design/voice-guidance.md`).
 * Every word comes from `strings_voice.xml` – whole phrases with placeholders, so a language orders them its own way;
 * this file only rounds numbers and picks phrases. A new language = a new `values-xx/strings_voice.xml` (+ the app's
 * other texts), nothing here.
 */
object VoiceText {
    fun of(p: Phrase): String = when (p) {
        is Phrase.Start -> {
            val dist = distance(p.distanceM)
            val time = p.durationS?.let { duration(it) }
            when {
                p.destination != null && time != null -> Txt.s(Res.string.voice_start_dest_time, dist, p.destination, time)
                p.destination != null -> Txt.s(Res.string.voice_start_dest, dist, p.destination)
                time != null -> Txt.s(Res.string.voice_start_time, dist, time)
                else -> Txt.s(Res.string.voice_start, dist)
            }
        }
        is Phrase.Junction -> junction(p)
        Phrase.OffRoute -> Txt.s(Res.string.voice_off_route)
        Phrase.BackOnRoute -> Txt.s(Res.string.voice_back_on_route)
        is Phrase.NewRoute -> listOfNotNull(Txt.s(Res.string.voice_new_route),
            if (p.turnRound) Txt.s(Res.string.voice_turn_round) else p.next?.let { junction(it) }).joinToString(" ")
        is Phrase.StopReached -> {
            val reached = p.name?.takeIf { it.isNotBlank() }?.let { Txt.s(Res.string.voice_stop_named, it) }
                ?: Txt.s(Res.string.voice_stop_number, p.number)
            val next = if (p.nextName != null && p.nextM != null) Txt.s(Res.string.voice_next, p.nextName, distance(p.nextM)) else null
            listOfNotNull(reached, next).joinToString(" ")
        }
        Phrase.Arrived -> Txt.s(Res.string.voice_arrived)
        Phrase.NoGps -> Txt.s(Res.string.voice_no_gps)
        Phrase.NoDestination -> Txt.s(Res.string.voice_no_destination)
    }

    /** The test sentence of the phone setup. */
    fun test(): String = Txt.s(Res.string.voice_test)

    private fun junction(p: Phrase.Junction): String {
        var action = action(p.turn, p.exit)
        p.onto?.let { action = Txt.s(Res.string.voice_onto, action, way(it)) }
        var s = if (p.inM < VoiceGuide.SOON_M) Txt.s(Res.string.voice_soon, action)
            else Txt.s(Res.string.voice_in, inDistance(p.inM), action)
        p.then?.let { s = Txt.s(Res.string.voice_then, s, action(it, p.thenExit)) }
        p.straightM?.let { s = Txt.s(Res.string.voice_then_straight, s, distance(it)) }
        return Txt.s(Res.string.voice_sentence, s)
    }

    private fun action(t: Turn, exit: Int): String = when (t) {
        Turn.LEFT -> Txt.s(Res.string.voice_turn_left)
        Turn.SLIGHT_LEFT -> Txt.s(Res.string.voice_turn_slight_left)
        Turn.SHARP_LEFT -> Txt.s(Res.string.voice_turn_sharp_left)
        Turn.KEEP_LEFT -> Txt.s(Res.string.voice_keep_left)
        Turn.RIGHT -> Txt.s(Res.string.voice_turn_right)
        Turn.SLIGHT_RIGHT -> Txt.s(Res.string.voice_turn_slight_right)
        Turn.SHARP_RIGHT -> Txt.s(Res.string.voice_turn_sharp_right)
        Turn.KEEP_RIGHT -> Txt.s(Res.string.voice_keep_right)
        Turn.U_TURN -> Txt.s(Res.string.voice_u_turn)
        Turn.ROUNDABOUT -> ordinal(exit)?.let { Txt.s(Res.string.voice_roundabout_exit, it) } ?: Txt.s(Res.string.voice_roundabout)
    }

    private fun ordinal(exit: Int): String? = when (exit) {
        1 -> Txt.s(Res.string.voice_exit_1); 2 -> Txt.s(Res.string.voice_exit_2); 3 -> Txt.s(Res.string.voice_exit_3)
        4 -> Txt.s(Res.string.voice_exit_4); 5 -> Txt.s(Res.string.voice_exit_5); 6 -> Txt.s(Res.string.voice_exit_6)
        else -> null
    }

    private fun way(w: WayKind): String = when (w) {
        WayKind.PATH -> Txt.s(Res.string.voice_way_path)
        WayKind.TRACK -> Txt.s(Res.string.voice_way_track)
        WayKind.ROAD -> Txt.s(Res.string.voice_way_road)
        WayKind.CYCLEWAY -> Txt.s(Res.string.voice_way_cycleway)
        WayKind.WAY -> Txt.s(Res.string.voice_way_way)
    }

    /** "In 150 metres" – a junction's distance, in the steps one can picture (50, 100, 150, 200, 300, 500 m, then km). */
    fun inDistance(m: Double): String {
        val r = roundAhead(m)
        return if (r < 1_000) Txt.s(Res.string.voice_in_m, r.toString())
        else km(r / 1000.0).let { (count, text) -> Txt.p(Res.plurals.voice_in_km, count, text) }
    }

    /** "3 kilometres" / "400 metres" – a length (start, quiet stretch, on to the next stop). */
    fun distance(m: Double): String =
        if (m < 950) Txt.p(Res.plurals.voice_m, 2, (((m / 100).roundToInt()).coerceAtLeast(1) * 100).toString())
        else km(m / 1000.0).let { (count, text) -> Txt.p(Res.plurals.voice_km, count, text) }

    /** "about 50 minutes" / "one hour 20 minutes": under an hour to 5 min, above to 10 min. */
    fun duration(s: Double): String {
        val min = (s / 60).roundToInt()
        if (min < 60) {
            val m = maxOf(5, (min / 5.0).roundToInt() * 5)
            return if (m >= 60) Txt.p(Res.plurals.voice_hours, 1, "1") else Txt.p(Res.plurals.voice_minutes, m, m.toString())
        }
        val total = (min / 10.0).roundToInt() * 10
        val h = total / 60; val rest = total % 60
        val hours = Txt.p(Res.plurals.voice_hours, h, h.toString())
        return if (rest == 0) hours else Txt.s(Res.string.voice_hours_minutes, hours, Txt.p(Res.plurals.voice_minutes, rest, rest.toString()))
    }

    /** 50 / 100 / 150 / 200 / 300 / 500 m, then whole hundreds of metres as km. */
    fun roundAhead(m: Double): Int = when {
        m <= 75 -> 50
        m <= 125 -> 100
        m <= 175 -> 150
        m <= 250 -> 200
        m <= 400 -> 300
        m <= 750 -> 500
        else -> ((m / 100).roundToInt() * 100)
    }

    /** km for speech: one decimal under 10 km (none when whole), whole km above; the count for the plural form. */
    private fun km(km: Double): Pair<Int, String> {
        if (km >= 10) { val k = km.roundToInt(); return k to k.toString() }
        val tenth = (km * 10).roundToInt()
        return if (tenth % 10 == 0) (tenth / 10).let { it to it.toString() } else 2 to Format.number(tenth / 10.0, 1)
    }
}
