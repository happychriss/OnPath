package com.onpath.app.debug

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Background work never dies silently and never takes the app down (owner 2026-11-07: "keine Fehlermeldungen
 * verschlucken – alles muss im Log erscheinen"; robust first). Before, an exception in a `launch` on viewModelScope or
 * an app-wide scope reached the global crash handler, was logged – and the process ended.
 *
 * - [problemsHandler]: a [CoroutineExceptionHandler] that reports to [Problems] – for scopes ([reported]), so every root
 *   coroutine launched on them is covered, also in helper classes that get the scope passed in.
 * - [launchSafe]: one launch with its own words ("what failed"), so the problem log says where it happened.
 *
 * Cancellation is never reported (it is how work ends). A [VirtualMachineError] (out of memory, stack overflow) is
 * reported and rethrown: the process cannot be trusted after it.
 */
fun problemsHandler(area: String, what: String = "Unexpected error in background work"): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, e ->
        if (e !is CancellationException) Problems.error(area, what, e)
    }

/** This scope (same job, same dispatcher) with [problemsHandler]: no root coroutine on it can crash the app unseen. */
fun CoroutineScope.reported(area: String, what: String = "Unexpected error in background work"): CoroutineScope =
    this + problemsHandler(area, what)

/** [launch] that reports a failure as "[what] failed" under [area] and lets the rest of the app go on. */
fun CoroutineScope.launchSafe(
    area: String, what: String,
    context: CoroutineContext = EmptyCoroutineContext,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    block: suspend CoroutineScope.() -> Unit,
): Job = launch(context, start) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: VirtualMachineError) {
        Problems.error(area, "$what failed", e)
        throw e
    } catch (e: Throwable) {
        Problems.error(area, "$what failed", e)
    }
}

/**
 * The enum constant called [name], else [default] – a stored or received value from an older / newer version or a
 * damaged file is no reason to stop, but it must be visible: warned once per (type, value) under [area].
 * `null` (nothing stored) is expected and gives [default] without a word.
 */
inline fun <reified T : Enum<T>> enumOr(name: String?, default: T, area: String): T =
    enumOrNull<T>(name, area) ?: default

/** Like [enumOr] but `null` when [name] is null or unknown (unknown is warned once). */
inline fun <reified T : Enum<T>> enumOrNull(name: String?, area: String): T? {
    if (name == null) return null
    enumValues<T>().firstOrNull { it.name == name }?.let { return it }
    EnumWarnings.once(T::class.java.simpleName, name, area)
    return null
}

/** Remembers which unknown enum values were warned already (one warning per type and value per run). */
object EnumWarnings {
    private val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun once(type: String, value: String, area: String) {
        if (seen.add("$type/$value")) Problems.warn(area, "Unknown $type value '$value' – a default is used")
    }

    /** Tests only. */
    fun reset() = seen.clear()
}
