package com.onpath.app.ui

/**
 * Navigate over the lock screen (owner 2026-10-28, bike: "with the screen off, a short press shows the navigation"):
 * only while a tour runs and the app is in Navigate – never Plan, My routes or Settings without unlocking. Pure.
 */
object LockScreenNav {
    fun shows(navigate: Boolean, touring: Boolean): Boolean = navigate && touring
}
