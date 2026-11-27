package com.onpath.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Flag
import com.onpath.app.navigation.NavPrompt
import com.onpath.app.navigation.NavText
import com.onpath.strings.*

// The calm questions before the route is reached (far start, mid-route), split out of NavigateScreen.kt.

/**
 * Never on the route yet and away from it (owner 2026-10-31): "Route starts 31 km away" · "south-west (direct) ·
 * recording" · Just record / Guide me (FRAGE). Calm: no vibration, no sound; stays until answered or the route is
 * reached. No "the search can take long" line (owner 2026-11-27). After "Guide me" the same box stays while the way
 * there is computed – only "Guide me" spins, no waiting sentence (owner 2026-11-27); "Cancel" in place of "Just record"
 * stops the search and asks the same question again.
 */
internal fun farStartMessage(p: NavPrompt.FarStart, onLead: () -> Unit, onJustRecord: () -> Unit, onCancel: () -> Unit = {}): Message {
    val buttons = if (p.searching) listOf(MessageButton(Txt.s(Res.string.common_cancel), onCancel),
            MessageButton(NavText.LEAD) {}.apply { busy = true })
        else listOf(MessageButton(NavText.JUST_RECORD, onJustRecord), MessageButton(NavText.LEAD, onLead))
    return Message(MessageKind.FRAGE, NavText.title(p), NavText.state(p.copy(searching = false)), buttons, icon = Icons.Outlined.Flag,
        navigate = true)
}

/**
 * The route reached beside a later part of it, not at point 1 (owner 2026-11-03: "every tour starts at point 1 and skips
 * nothing"): "Du stehst mitten auf der Route." · "Die Tour beginnt bei ①." · Hier einsteigen / **Zu ① führen** (the
 * primary). Calm (no vibration); stays until answered or the user is at the start. "Hier einsteigen" = the points before
 * count as passed (grey, not deleted). While the way to ① is computed „Zu ① führen“ spins, no waiting sentence (owner
 * 2026-11-27); "Hier einsteigen" still works.
 */
internal fun midStartMessage(p: NavPrompt.MidStart, onLead: () -> Unit, onJoin: () -> Unit): Message {
    val buttons = if (p.searching) listOf(MessageButton(NavText.MID_JOIN, onJoin), MessageButton(NavText.MID_LEAD) {}.apply { busy = true })
        else listOf(MessageButton(NavText.MID_JOIN, onJoin), MessageButton(NavText.MID_LEAD, onLead))
    return Message(MessageKind.FRAGE, NavText.title(p), NavText.state(p.copy(searching = false)), buttons, icon = Icons.Outlined.Flag,
        navigate = true)
}
