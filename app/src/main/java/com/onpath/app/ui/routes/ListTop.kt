package com.onpath.app.ui.routes

/**
 * My routes' list at its top stays at its top when a row comes before its first one – a route just saved or synced, or
 * the fresh read after the list opened with the one read before (E2E 2026-11-07: "E2E saved route" in the list but above
 * the screen). A LazyColumn holds its first visible row by key, so without this the new row lay above the screen and
 * nothing said so. A list scrolled down keeps its place (nothing jumps under the finger).
 */
object ListTop {
    /** The first row is shown whole at the top. */
    fun atTop(firstVisibleIndex: Int, firstVisibleOffset: Int): Boolean = firstVisibleIndex == 0 && firstVisibleOffset == 0

    /**
     * The index to scroll to when the list's first row changed from [firstKeyBefore] to [firstKeyNow] – 0 when the list
     * [wasAtTop] before – else null (nothing to do; also on the first composition, [firstKeyBefore] null).
     */
    fun scrollTo(firstKeyBefore: Any?, firstKeyNow: Any?, wasAtTop: Boolean): Int? =
        if (firstKeyBefore != null && firstKeyBefore != firstKeyNow && wasAtTop) 0 else null
}
