package com.onpath.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.runtime.Composable
import com.onpath.app.navigation.NavPrompt
import com.onpath.app.navigation.NavText
import com.onpath.strings.*

// The calm questions before the route is reached (far start, mid-route), the way back being searched and the
// notifications question – split out of NavigateScreen.kt.

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

/**
 * Off the route, the way back still being computed (owner 2026-11-01: "erst piep, piep, anbieten, dann gemeinsam
 * suchen"): the question comes at once – "Abseits der Route", how far and where the route is; "Keep original" works
 * at once, "New route" shows a spinner until the way is found (then the normal question, [rerouteMessage]).
 */
internal fun searchingMessage(o: OffRouteUi, onKeep: () -> Unit): Message =
    // "Abseits der Route" only: "New route" spins, no waiting sentence (owner 2026-11-27)
    Message(MessageKind.FRAGE, Txt.s(Res.string.nav_off_route), NavText.toRoute(o.toRoute),
        listOf(MessageButton(NavText.KEEP, onKeep), MessageButton(NavText.TAKE) {}.apply { busy = true }),
        icon = Icons.Outlined.AltRoute, navigate = true)

/**
 * A tour runs with OnPath's notifications off ([com.onpath.app.navigation.NotificationsOff]) – a question, not a warning
 * (owner 2026-11-27): „Abzweigungen auf dem Sperrbildschirm?“ / „Mit Benachrichtigungen siehst du sie, ohne das Handy zu
 * entsperren.“ · [Später] [Einschalten]. Einschalten → the phone setup step; Später → gone for this tour. Never goes by
 * itself (owner: no self-dismissing messages).
 */
@Composable
internal fun notificationsOffMessage(onSetUp: () -> Unit, onLater: () -> Unit): Message =
    Message(MessageKind.FRAGE, str(Res.string.nav_notif_off), str(Res.string.nav_notif_off_line),
        listOf(MessageButton(str(Res.string.nav_notif_later), onLater), MessageButton(str(Res.string.nav_notif_turn_on), onSetUp)),
        icon = Icons.Outlined.Notifications, navigate = true)
