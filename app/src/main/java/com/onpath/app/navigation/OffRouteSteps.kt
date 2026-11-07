package com.onpath.app.navigation

/**
 * Leaving the route is two steps, each shown and said (owner 2026-11-07, cycling: "zuerst 'du hast die Route verlassen',
 * dann 'Route neu planen' – beides sind Schritte"):
 * 1. **Off the route** – the amber off-route card on Navigate (ANZEIGE: "Abseits der Route", arrow and distance to the
 *    route), held while the state holds, also while the question below is open; the tour notification's status line
 *    "Abseits der Route"; the voice "Abseits der Route. Bitte aufs Handy schauen." when it is confirmed.
 * 2. **Re-plan?** – the question box (FRAGE): the way back being found, then "Du bist 120 m von der Route weg" · New route /
 *    Keep original; on the lock screen the heads-up question. When it asks again (the first reminder, a way found after
 *    none, asked again after Keep) the voice says it, as the phone would vibrate.
 * Back on the route: the card goes; "Wieder auf der Route." ([VoiceGuide]). Pure; `OffRouteStepsTest`.
 */
object OffRouteSteps {
    /** Step 1: the amber off-route card shows (way back searched, question open, no way found, original kept). */
    fun card(p: NavPrompt?): Boolean = p != null && p.offRoute

    /** Step 2: the off-route question box (the way back being found, or found) – not the activity switch. */
    fun question(p: NavPrompt?): Boolean = p == NavPrompt.Searching || (p is NavPrompt.Question && p.switch == null)

    /** The card's small state line ("Kein Rückweg gefunden", "Du folgst der ursprünglichen Route") – only when no box says it. */
    fun cardState(p: NavPrompt?): Boolean = card(p) && !question(p)
}
