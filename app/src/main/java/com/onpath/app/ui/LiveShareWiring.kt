package com.onpath.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.lifecycle.viewModelScope
import com.onpath.app.debug.launchSafe
import com.onpath.app.live.EdgeLiveShareClient
import com.onpath.app.ui.live.LiveAction
import com.onpath.app.ui.live.LivePrefs
import com.onpath.app.ui.live.LiveShareHost
import com.onpath.app.ui.live.LiveShareSlice
import com.onpath.strings.Res
import com.onpath.strings.Txt
import com.onpath.strings.live_share
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * What the Settings opened from Navigate show first, above the Navigation section; null = nothing. Live sharing puts
 * its card here ([LiveShareLayer]) – so the Settings page does not know the sharing.
 */
val LocalSettingsFirst = compositionLocalOf<(@Composable () -> Unit)?> { null }

/** First in Navigate's top column, above the turn card; it is told whether a turn card shows. Live sharing's pill ([LiveSignLayer]). */
val LocalNavTop = compositionLocalOf<(@Composable (turnCard: Boolean) -> Unit)?> { null }

/** At the right edge inside Navigate's turn card. Live sharing's sign ([LiveSignLayer]). */
val LocalTurnCardEnd = compositionLocalOf<(@Composable () -> Unit)?> { null }

/**
 * Live sharing around the Settings page (docs/design/live-tracking.md §2, §14.2): its card is the first entry of the
 * on-the-way Settings through [LocalSettingsFirst], its name question is shown over [content]. Closing Settings (also by
 * the app) closes the name question and a seen "ended" line.
 */
@Composable
fun LiveShareLayer(vm: AppViewModel, content: @Composable () -> Unit) {
    val slice = LiveShares.of(vm) ?: return content()
    DisposableEffect(slice) { onDispose { slice.settingsClosed() } }
    CompositionLocalProvider(LocalSettingsFirst provides {
        LiveSettingsCard(slice.sign, slice.actions, onAction = { slice.act(it) },
            name = slice.name.takeIf { slice.nameKnown }, onName = slice::editName,
            ended = slice.ended, onEndedShown = slice::endedShown)
    }, content = content)
    if (slice.askName) LiveNameDialog(slice.name, onShare = slice::nameGiven, onCancel = slice::nameCancelled, edit = slice.editingName)
}

/**
 * Live sharing's sign on Navigate (owner 2026-11-20: "oben bei den Richtungen"): inside the turn card while one shows,
 * else a pill at the top right (a tour without a route has no turn card). A tap opens the two actions.
 */
@Composable
fun LiveSignLayer(vm: AppViewModel, content: @Composable () -> Unit) {
    val slice = LiveShares.of(vm) ?: return content()
    CompositionLocalProvider(
        LocalTurnCardEnd provides { LiveSignInTurnCard(slice.sign, slice.actions) { slice.act(it) } },
        LocalNavTop provides { turnCard -> if (!turnCard) LiveSignOnMap(slice.sign, slice.actions) { slice.act(it) } },
        content = content)
}

private fun LiveShareSlice.act(a: LiveAction) = when (a) {
    LiveAction.SHARE -> share()
    LiveAction.SHARE_AGAIN -> shareAgain()
    LiveAction.STOP -> stop()
}

/**
 * The live-sharing slice of each view model, kept by the view model itself ([androidx.lifecycle.ViewModel.addCloseable]
 * – it goes with it; the view model's file may only shrink). Made once by [attach] outside composition; the screens
 * only read it ([of]).
 */
object LiveShares {
    private const val KEY = "com.onpath.live.share"

    private class Holder(val slice: LiveShareSlice) : AutoCloseable {
        override fun close() = Unit // the collectors run in the view model's scope and end with it
    }

    /**
     * Sharing for every user, or only with the developer tools unlocked (Settings → About, 7 taps). Owner 2026-11-20:
     * on main now, in an update only after the privacy text and the hourly clean-up are merged and he shared once on
     * his Pixel – then this goes to true (one line, a line for donald).
     */
    private const val FOR_EVERYONE = false

    /** The slice of [vm]; null before [attach] or while sharing is not offered (then nothing of the sharing shows). */
    fun of(vm: AppViewModel): LiveShareSlice? =
        vm.getCloseable<Holder>(KEY)?.slice?.takeIf { FOR_EVERYONE || vm.prefs.devToolsUnlocked }

    /** Makes [vm]'s slice once (the activity's onCreate, main thread) and feeds it: the lane's snapshots, the recording, the clock. */
    fun attach(vm: AppViewModel, app: Context) {
        if (of(vm) != null) return
        val client = EdgeLiveShareClient(app)
        val slice = LiveShareSlice(client, object : LivePrefs {
            override var liveName: String?
                get() = vm.prefs.liveName
                set(v) { vm.prefs.liveName = v }
        }, object : LiveShareHost {
            override fun shareText(text: String) {
                val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
                vm.events += UiEvent.Share(Intent.createChooser(send, Txt.s(Res.string.live_share)))
            }
            override fun nowMs(): Long = System.currentTimeMillis()
        })
        vm.addCloseable(KEY, Holder(slice))
        // on the main thread, for as long as the view model lives; the clock ticks only while sharing
        vm.viewModelScope.launchSafe("live", "follow the live sharing") {
            client.live.collectLatest { s ->
                slice.onSnapshot(s)
                if (s.sharing) while (true) { delay(TICK_MS); slice.tick() }
            }
        }
        vm.viewModelScope.launchSafe("live", "follow the recording for sharing") {
            vm.recState.collect { slice.onRecording(it != null) }
        }
    }

    /** How often the sign looks at the clock while sharing (the 12-min rule needs no finer step). */
    private const val TICK_MS = 30_000L
}
