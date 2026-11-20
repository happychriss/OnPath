package com.onpath.app.ui

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.onpath.app.live.EdgeLiveShareClient
import com.onpath.app.live.LiveShareClient
import com.onpath.app.ui.live.LivePrefs
import com.onpath.app.ui.live.LiveAction
import com.onpath.app.ui.live.LiveShareHost
import com.onpath.app.ui.live.LiveShareSlice
import com.onpath.strings.Res
import com.onpath.strings.Txt
import com.onpath.strings.live_share
import java.util.WeakHashMap

/**
 * Extra content under the route numbers of Navigate's status card (its `actions` slot); null = none. Live sharing puts
 * its row here ([LiveShareLayer]) – so the card and Navigate's files do not know the sharing.
 */
val LocalNavCardExtra = compositionLocalOf<(@Composable () -> Unit)?> { null }

/**
 * Live sharing's wiring on Navigate (docs/design/live-tracking.md §2, §14.2): one [LiveShareSlice] per view model –
 * kept beside it (the view model's file may only shrink) – fed with the live lane's snapshots; its row goes into the
 * status card through [LocalNavCardExtra], its one-time name question is shown over [content].
 */
@Composable
fun LiveShareLayer(vm: AppViewModel, content: @Composable () -> Unit) {
    val app = LocalContext.current.applicationContext
    val client = remember(app) { EdgeLiveShareClient(app) }
    val slice = remember(vm) { LiveShares.of(vm, client) }
    LaunchedEffect(slice, client) { client.live.collect(slice::onSnapshot) }
    CompositionLocalProvider(LocalNavCardExtra provides {
        LiveShareRow(slice.sign, slice.actions, onAction = {
            when (it) {
                LiveAction.SHARE -> slice.share()
                LiveAction.SHARE_AGAIN -> slice.shareAgain()
                LiveAction.STOP -> slice.stop()
            }
        })
    }, content = content)
    if (slice.askName) LiveNameDialog(slice.name, onShare = slice::nameGiven, onCancel = slice::nameCancelled)
}

/** The live-sharing slice of each view model (weak: it goes with its view model). */
private object LiveShares {
    private val slices = WeakHashMap<AppViewModel, LiveShareSlice>()

    fun of(vm: AppViewModel, client: LiveShareClient): LiveShareSlice = synchronized(slices) {
        slices.getOrPut(vm) {
            LiveShareSlice(client, object : LivePrefs {
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
            }, vm.notices)
        }
    }
}
