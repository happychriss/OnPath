package com.onpath.app.domain.routes

import com.onpath.app.ui.RouteRules
import com.onpath.app.ui.SaveAsk
import com.onpath.app.ui.SaveMode

/**
 * Save's dialog (owner 2026-10-31, core feature C18): every Save opens it – prefilled with the proposal (else the date
 * name) or the saved route's name. A new plan becomes a new route; a saved route is overwritten and renamed when the
 * name changed – still ONE route; My routes → ⋮ → Save as copy writes a copy of the stored route. A blank name keeps the
 * proposal / old name. Pure: AppViewModel asks it, then does the write.
 */
object SaveFlow {
    /** What Save's dialog opens with. [proposal] null = the date name until the proposal arrives. */
    fun ask(mode: SaveMode, savedName: String?, savedId: Long, proposal: String?, dated: String, taken: List<String>,
            then: (() -> Unit)?): SaveAsk =
        if (mode == SaveMode.OVERWRITE && savedName != null) SaveAsk(SaveAsk.Kind.OVERWRITE, savedName, savedId, then)
        else SaveAsk(SaveAsk.Kind.NEW, RouteRules.newRouteName(proposal, dated, taken), -1L, then)

    /** My routes → ⋮ → Save as copy of [routeName] (id [routeId]). */
    fun askCopy(routeName: String, routeId: Long, taken: List<String>): SaveAsk =
        SaveAsk(SaveAsk.Kind.COPY, RouteRules.copyName(routeName, taken), routeId, null)

    sealed interface Write {
        val name: String
        data class Copy(val routeId: Long, override val name: String) : Write
        data class Overwrite(val routeId: Long, override val name: String) : Write
        data class New(override val name: String) : Write
    }

    /** The dialog's Save with what the field holds; [savedId] = the route the plan is linked to now (-1: none). */
    fun confirm(ask: SaveAsk, input: String, savedId: Long): Write {
        val name = RouteRules.cleanName(input, ask.name)
        return when {
            ask.kind == SaveAsk.Kind.COPY -> Write.Copy(ask.routeId, name)
            ask.kind == SaveAsk.Kind.OVERWRITE && ask.routeId == savedId -> Write.Overwrite(ask.routeId, name)
            else -> Write.New(name)
        }
    }
}
