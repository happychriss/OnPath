package com.onpath.app.domain.tour

/**
 * The hold lock's unlock window (owner 2026-11-07 09:05: "Ich gehe auf den Riegel, dann auf den zweiten Button, und der
 * ist auch noch verriegelt – macht es nicht Sinn, dass man nur einmal entriegelt?"): ONE fired hold unlocks the whole bar
 * for [OPEN_MS]. While it is open every button (Pause | Planen; paused Weiter · Beenden · ✎) acts on a plain tap and shows
 * its full colour, and every tap starts the window again. It locks again after [OPEN_MS] without a tap, when the screen
 * goes off or the app goes to the background ([LOCKED]), and there is nothing to unlock when the hold time is Aus.
 *
 * The window is one number – the moment it ends ([LOCKED] = shut); clock = any monotonic milliseconds. Pure.
 */
object UnlockWindow {
    /** How long one hold unlocks the bar (owner 2026-11-07: 20 s). */
    const val OPEN_MS = 20_000L

    /** The shut window. */
    const val LOCKED = 0L

    /** End-to-end tests only: a short window instead of [OPEN_MS] (no test waits 20 s); null = the real one. */
    @Volatile var openMsForTests: Long? = null

    /** The window's length in use. */
    val openMs: Long get() = openMsForTests ?: OPEN_MS

    /** The window ending at [until] is open at [now]. */
    fun isOpen(until: Long, now: Long): Boolean = now < until

    /** A held button fired at [now]: the bar is open until the returned moment. */
    fun afterFire(now: Long, openMs: Long = this.openMs): Long = now + openMs

    /** A plain tap at [now]: an open window starts again; a shut one stays shut (a tap never unlocks). */
    fun afterTap(until: Long, now: Long, openMs: Long = this.openMs): Long = if (isOpen(until, now)) now + openMs else until

    /**
     * The screen went off – power button, timeout, the app in the background (ON_STOP) or the proximity sensor in the
     * pocket ([PocketLogic.inPocket]; owner 2026-11-07 10:45: "Pause drücken, dann in die Tasche – danach soll wieder
     * gesperrt sein"): the window shuts at once, whatever was left of it.
     */
    fun afterScreenOff(): Long = LOCKED

    /** The bar wants held buttons: the lock is on ([lockOn] – hold time not Aus) and the window is not open. */
    fun locked(lockOn: Boolean, until: Long, now: Long): Boolean = lockOn && !isOpen(until, now)

    /** The buttons of the tour's bottom bar: running Pause | Planen, paused Weiter · Beenden · ✎ (= [PLAN]). */
    enum class BarButton { PAUSE, PLAN, RESUME, END }

    /**
     * One button wants a hold (owner 2026-11-07 10:15): with the lock on, [BarButton.RESUME] ("Weiter" out of a pause)
     * ALWAYS – also inside the window, so a stray tap never starts the recording again; every other button only while
     * the window is shut. The lock off ([lockOn] false, hold time Aus): never.
     */
    fun needsHold(button: BarButton, lockOn: Boolean, windowOpen: Boolean): Boolean =
        lockOn && (button == BarButton.RESUME || !windowOpen)
}
