package com.onpath.app.debug

import com.onpath.app.routing.RoutingException

/**
 * Web stand-in for the app's UserText (the real one also knows the phone-only exceptions: backup, cells, import).
 * Same rule: our own RoutingException texts are shown, anything else gets the plain sentence.
 */
object UserText {
    fun of(e: Throwable, plain: String): String =
        (if (e is RoutingException) e.message else null)?.takeIf { it.isNotBlank() } ?: plain
}
