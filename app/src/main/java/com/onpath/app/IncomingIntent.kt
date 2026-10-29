package com.onpath.app

import android.content.Context

/**
 * An incoming link / share / GPX (VIEW or SEND) is handled once: after a reinstall or a process restart Android starts
 * the screen again with the task's FIRST intent, so a link handled long ago would come back. The last handled one is
 * remembered (a fingerprint in the app's preferences); a new one is always handled.
 */
object IncomingIntent {
    enum class Delivery {
        /** onCreate without saved state: a new start – or the task's first intent again after a restart. */
        CREATE,
        /** onCreate with saved state (rotation, language, process restored): handled before. */
        RESTORED,
        /** onNewIntent (singleTask): always a new delivery. */
        NEW_INTENT,
    }

    /** null = not an incoming item (launcher MAIN, nothing). Content only: action, data, type, shared text, shared file. */
    fun fingerprint(action: String?, data: String?, type: String?, text: String?, stream: String?): String? {
        if (action != "android.intent.action.VIEW" && action != "android.intent.action.SEND") return null
        val raw = listOf(action, data, type, text, stream).joinToString("\u0001") { it ?: "" }
        return java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
    }

    fun shouldHandle(delivery: Delivery, fromHistory: Boolean, fingerprint: String?, lastHandled: String?): Boolean = when {
        fingerprint == null -> false
        fromHistory -> false // reopened from recents: that share was handled already
        delivery == Delivery.RESTORED -> false
        delivery == Delivery.NEW_INTENT -> true // even the same link tapped again while the app runs
        else -> fingerprint != lastHandled // the task's first intent once more after a reinstall / restart
    }

    private const val PREFS = "onpath"
    private const val KEY = "lastIncomingIntent"

    fun lastHandled(c: Context): String? = c.getSharedPreferences(PREFS, 0).getString(KEY, null)

    fun remember(c: Context, fingerprint: String) { c.getSharedPreferences(PREFS, 0).edit().putString(KEY, fingerprint).apply() }
}
