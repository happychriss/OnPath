package com.onpath.app.ui.routes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.onpath.app.data.Tour
import com.onpath.app.domain.SaveAsk
import com.onpath.app.domain.SaveMode
import com.onpath.app.domain.routes.SaveFlow

/**
 * Save's name dialog (owner 2026-10-31, C18) as state and decisions, no coroutine: which dialog is open ([ask]), the
 * proposal that arrives later ([proposed]), Cancel, and what the dialog's Save decides ([confirm] → [SaveFlow.Write]).
 * The writes and the waiting for the name are [SaveSlice]'s. Web-ready (state/web-ready.txt).
 */
class SaveDialog {
    /** Save's name dialog while open (owner 2026-10-31: Save always asks for the name). */
    var ask by mutableStateOf<SaveAsk?>(null)
        private set

    /** What the dialog's Save decided: the write, and what runs after it succeeded ("Save first" before loading another route). */
    class Confirmed(val write: SaveFlow.Write, val then: (() -> Unit)?)

    /** Opens the dialog for a plan ([SaveFlow.ask]) with the date name; returns it (the place proposal replaces its name later, [proposed]). */
    fun request(mode: SaveMode, savedName: String?, savedId: Long, dated: String, taken: List<String>, then: (() -> Unit)?): SaveAsk {
        val ask = SaveFlow.ask(mode, savedName, savedId, proposal = null, dated, taken, then)
        this.ask = ask
        return ask
    }

    /** My routes → ⋮ → Save as copy of [t]: the same dialog with "<name> (2)" ([SaveFlow.askCopy]). */
    fun requestCopy(t: Tour, taken: List<String>) {
        ask = SaveFlow.askCopy(t.name, t.id, taken)
    }

    /** The place proposal arrived: it replaces the name in the field – unless the dialog is not [asked] any more (closed, or another one). */
    fun proposed(asked: SaveAsk, name: String) {
        if (ask === asked) ask = asked.copy(name = name)
    }

    /** Save's dialog → Cancel / Back / a tap outside: nothing is saved. */
    fun cancel() { ask = null }

    /** Save's dialog → Save with [input]: the dialog closes; null = it was not open. [savedId] = the route the plan is linked to now (-1: none). */
    fun confirm(input: String, savedId: Long): Confirmed? {
        val ask = ask ?: return null
        this.ask = null
        return Confirmed(SaveFlow.confirm(ask, input, savedId), ask.then)
    }
}
