package com.onpath.app.routing

/**
 * A plan calculation that reaches its time limit ([RouteTimeLimit]) – owner 2026-11-02 / 03 (onpath.private#13, #21 B):
 * in Plan the user is asked in the one message box "Die Route ist lang, die Berechnung dauert. Weiter warten?" instead
 * of the whole plan turning into straight lines ("took too long"). Pure, shared with the web planner (same box there).
 *
 * - "Weiter warten": the calculation runs again with one more time-limit period ([RouteLimits.rounds] + 1) – BRouter
 *   cannot resume a search, so the new run gets the whole longer budget; routed pieces come from the engine's cache.
 *   At that limit it asks again (no maximum: the user decides).
 * - "Abbrechen": stops like Cancel while calculating (the change that started it is undone – PlanStatus.onCancel).
 * - Not asking (Navigate's runs, browser plans, plan from a track …): straight lines as before.
 */
object SlowPlan {
    enum class Answer { KEEP_WAITING, CANCEL }

    /** What happens when a run reaches its limit: [ask] = a plan run the user started in Plan. */
    enum class AtLimit { ASK, STRAIGHT }

    fun atLimit(ask: Boolean): AtLimit = if (ask) AtLimit.ASK else AtLimit.STRAIGHT

    /** The next run's periods after [answer] to a run of [rounds] periods; null = stop (cancelled). */
    fun next(rounds: Int, answer: Answer): Int? = when (answer) {
        Answer.KEEP_WAITING -> rounds.coerceAtLeast(1) + 1
        Answer.CANCEL -> null
    }

    /** Thrown by [Planner] (asking) when its run reached the limit of [rounds] periods; the caller shows the question. */
    class TimeLimit(val rounds: Int) : Exception("Route calculation reached its time limit ($rounds period(s))")
}
