package com.onpath.app.ui

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
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
import java.util.WeakHashMap

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
 * on-the-way Settings through [LocalSettingsFirst], its name question is shown over [content]. The one
 * [LiveShareSlice] per view model is kept beside it ([LiveShares] – the view model's file may only shrink).
 */
@Composable
fun LiveShareLayer(vm: AppViewModel, content: @Composable () -> Unit) {
    val slice = liveSlice(vm)
    CompositionLocalProvider(LocalSettingsFirst provides {
        LiveSettingsCard(slice.sign, slice.actions, onAction = { slice.act(it) },
            name = slice.name.takeIf { slice.nameKnown }, onName = slice::editName)
    }, content = content)
    if (slice.askName) LiveNameDialog(slice.name, onShare = slice::nameGiven, onCancel = slice::nameCancelled, edit = slice.editingName)
}

/**
 * Live sharing's sign on Navigate (owner 2026-11-20: "oben bei den Richtungen"): inside the turn card while one shows,
 * else a pill at the top right (a tour without a route has no turn card). A tap opens the two actions.
 */
@Composable
fun LiveSignLayer(vm: AppViewModel, content: @Composable () -> Unit) {
    val slice = liveSlice(vm)
    CompositionLocalProvider(
        LocalTurnCardEnd provides { LiveSignInTurnCard(slice.sign, slice.actions) { slice.act(it) } },
        LocalNavTop provides { turnCard -> if (!turnCard) LiveSignOnMap(slice.sign, LiveSignLook.PILL, slice.actions) { slice.act(it) } },
        content = content)
}

@Composable
private fun liveSlice(vm: AppViewModel): LiveShareSlice {
    val app = LocalContext.current.applicationContext
    return remember(vm) { LiveShares.of(vm) { EdgeLiveShareClient(app) } }
}

private fun LiveShareSlice.act(a: LiveAction) = when (a) {
    LiveAction.SHARE -> share()
    LiveAction.SHARE_AGAIN -> shareAgain()
    LiveAction.STOP -> stop()
}

/** The live-sharing slice of each view model (weak: it goes with its view model), fed with the lane's snapshots once. */
private object LiveShares {
    private val slices = WeakHashMap<AppViewModel, LiveShareSlice>()

    fun of(vm: AppViewModel, client: () -> EdgeLiveShareClient): LiveShareSlice = synchronized(slices) {
        slices.getOrPut(vm) {
            val c = client()
            LiveShareSlice(c, object : LivePrefs {
                override var liveName: String?
                    get() = vm.prefs.liveName
                    set(v) { vm.prefs.liveName = v }
            }, object : LiveShareHost {
                override fun recording(): Boolean = vm.recState.value != null
                override fun shareText(text: String) {
                    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
                    vm.events += UiEvent.Share(Intent.createChooser(send, Txt.s(Res.string.live_share)))
                }
                override fun nowMs(): Long = System.currentTimeMillis()
            }, vm.notices).also { slice ->
                // one collector per slice, on the main thread, for as long as the view model lives
                vm.viewModelScope.launchSafe("live", "follow the live sharing") { c.live.collect { slice.onSnapshot(it) } }
            }
        }
    }
}
