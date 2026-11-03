package com.onpath.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.onpath.app.data.Prefs
import com.onpath.app.debug.Problems
import com.onpath.app.ui.AppViewModel.UpdateState
import com.onpath.strings.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App updates from GitHub Releases (F-OFF-06) – out of AppViewModel (review Arch 3, owner 2026-11-01), the same code:
 * the automatic / manual check, the download and the hand-over to the Android installer. The ViewModel owns it and
 * forwards its members (the UI and the emulator tests use them there); [UpdateState] stays in the ViewModel.
 * [recording] = a tour records (installing closes OnPath), [say] = a line where the user acted (updatePlace()).
 */
class AppUpdates(
    private val app: Application,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val recording: () -> Boolean,
    private val say: (String, NoticeKind) -> Unit,
) {
    var update by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set
    /** Release the user dismissed on the home screen (no nagging; still visible in Settings). */
    var updateDismissed by mutableStateOf<String?>(null)

    /**
     * Off in the Google Play build (BuildConfig.SELF_UPDATE, A-003) and hidden when Google Play installed the app –
     * Play handles updates then: no check, no download, no Settings → Updates.
     */
    val updatesEnabled: Boolean by lazy {
        com.onpath.app.BuildConfig.SELF_UPDATE && !com.onpath.app.update.AppUpdater.installedFromPlay(app)
    }

    fun check(manual: Boolean) {
        if (!updatesEnabled || update is UpdateState.Checking || update is UpdateState.Downloading) return
        val now = System.currentTimeMillis()
        if (!manual && !com.onpath.app.update.AppUpdater.dueForAutoCheck(prefs.updateCheckMs, now)) return
        if (manual) update = UpdateState.Checking
        scope.launch(Dispatchers.IO) {
            val result = try {
                val r = com.onpath.app.update.AppUpdater.fetchLatest(com.onpath.app.BuildConfig.UPDATE_REPO)
                prefs.updateCheckMs = now
                if (r != null && com.onpath.app.update.AppUpdater.isNewer(r.version, com.onpath.app.BuildConfig.VERSION_NAME))
                    UpdateState.Available(r) else UpdateState.UpToDate
            } catch (e: Exception) {
                // Offline or GitHub unreachable: no user message for the automatic check, only the problem log.
                Problems.info("update", "Update check failed (offline or GitHub unreachable)", e)
                if (manual) UpdateState.Failed(Txt.s(Res.string.msg_update_check_failed)) else UpdateState.Idle
            }
            withContext(Dispatchers.Main) { update = result }
        }
    }

    /** A tour is recording and the user tapped Install: ask first (installing closes OnPath, recording stops). */
    var askUpdateDuringTour by mutableStateOf(false)

    /** Downloads the update and opens the Android installer (the user confirms there). */
    fun install(duringTourOk: Boolean = false) {
        if (!duringTourOk && recording()) { askUpdateDuringTour = true; return }
        askUpdateDuringTour = false
                val rel = (update as? UpdateState.Available)?.release ?: return
        if (!com.onpath.app.update.AppUpdater.canInstall(app)) {
            say(Txt.s(Res.string.msg_update_allow_install), NoticeKind.INFO)
            try { app.startActivity(com.onpath.app.update.AppUpdater.permissionIntent(app)) } catch (e: Exception) {
                Problems.warn("update", "Install-permission settings screen could not be opened", e)
            }
            return
        }
        update = UpdateState.Downloading(rel, 0)
        scope.launch(Dispatchers.IO) {
            try {
                val apk = com.onpath.app.update.AppUpdater.download(app, rel) { p ->
                    scope.launch(Dispatchers.Main) { if (update is UpdateState.Downloading) update = UpdateState.Downloading(rel, p) }
                }
                withContext(Dispatchers.Main) {
                    update = UpdateState.Available(rel)
                    app.startActivity(com.onpath.app.update.AppUpdater.installIntent(app, apk))
                }
            } catch (e: Exception) {
                Problems.warn("update", "Update download or installer start failed; current version kept", e)
                withContext(Dispatchers.Main) {
                    update = UpdateState.Available(rel)
                    say(Txt.s(Res.string.msg_update_download_failed), NoticeKind.ERROR)
                }
            }
        }
    }
}
