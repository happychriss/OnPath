package com.onpath.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Flag
import com.onpath.app.navigation.NavPrompt
import com.onpath.app.navigation.NavText
import com.onpath.app.navigation.Navigator
import com.onpath.strings.*

// The calm questions before the route is reached (far start, mid-route), split out of NavigateScreen.kt.

/**
 * Never on the route yet and away from it (owner 2026-10-31): "Route starts 31 km away" · "south-west (direct) ·
 * recording" · Just record / Guide me (FRAGE). Calm: no vibration, no sound; stays until answered or the route is
 * reached. From [Navigator.FAR_SEARCH_WARN_M] away a second line warns "The search can take a long time." (owner
 * 2026-11-21). After "Guide me" only "Cancel" stays while the way there is computed ("Finding a way to the start…"): it
 * stops the search and asks the same question again.
 */
internal fun farStartMessage(p: NavPrompt.FarStart, onLead: () -> Unit, onJustRecord: () -> Unit, onCancel: () -> Unit = {}): Message {
    val buttons = if (p.searching) listOf(MessageButton(Txt.s(Res.string.common_cancel), onCancel))
        else listOf(MessageButton(NavText.JUST_RECORD, onJustRecord), MessageButton(NavText.LEAD, onLead))
    val warn = !p.searching && Navigator.searchMayTakeLong(p.distanceM)
    val detail = if (warn) NavText.state(p) + "\n" + Txt.s(Res.string.nav_far_long) else NavText.state(p)
    return Message(MessageKind.FRAGE, NavText.title(p), detail, buttons, icon = Icons.Outlined.Flag, navigate = true)
}

/**
 * The route reached beside a later part of it, not at point 1 (owner 2026-11-03: "every tour starts at point 1 and skips
 * nothing"): "Du stehst mitten auf der Route." · "Die Tour beginnt bei ①." · Hier einsteigen / **Zu ① führen** (the
 * primary). Calm (no vibration); stays until answered or the user is at the start. "Hier einsteigen" = the points before
 * count as passed (grey, not deleted). While the way to ① is computed only "Hier einsteigen" stays.
 */
internal fun midStartMessage(p: NavPrompt.MidStart, onLead: () -> Unit, onJoin: () -> Unit): Message {
    val buttons = if (p.searching) listOf(MessageButton(NavText.MID_JOIN, onJoin))
        else listOf(MessageButton(NavText.MID_JOIN, onJoin), MessageButton(NavText.MID_LEAD, onLead))
    return Message(MessageKind.FRAGE, NavText.title(p), NavText.state(p), buttons, icon = Icons.Outlined.Flag, navigate = true)
}
