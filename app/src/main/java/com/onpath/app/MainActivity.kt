package com.onpath.app

import android.Manifest
import com.onpath.app.cells.label
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.onpath.app.ui.AppViewModel
import com.onpath.app.ui.FinishedTourDialogs
import com.onpath.app.ui.MapScreen
import com.onpath.app.ui.MissingDataScreen
import com.onpath.app.ui.AppMode
import com.onpath.app.ui.OnPathTheme
import com.onpath.app.ui.SettingsScreen
import com.onpath.app.ui.RouteDialogs
import com.onpath.app.ui.RoutesScreen
import com.onpath.app.ui.UnfinishedDialog
import com.onpath.app.debug.ScreenLifecycle
import kotlinx.coroutines.delay
import com.onpath.strings.*

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()
    private var afterPermission: (() -> Unit)? = null
    private var askedThisSession = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.locationAsking = false
        vm.tracker.start()
        if (vm.tracker.hasPermission()) afterPermission?.invoke()
        // asked for an action (Start): it did not happen – say why; the plain start-up ask needs no message
        else if (afterPermission != null) vm.sayError(Txt.s(Res.string.msg_no_location_permission), com.onpath.app.ui.NoticePlace.MAP)
        else com.onpath.app.debug.Problems.info("gps", "Location permission not given at the start-up ask")
        afterPermission = null
    }


    /** Map data / style import from the phone itself (no adb). */
    private val openData = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importData(uri)
    }

    private val openGpx = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importGpx(uri)
    }

    /** Restore: the folder with the backup files (a reinstalled app may read it only after this picker). */
    private val pickRestoreFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.restoreFrom(uri)
    }

    /** Settings → Backup → Choose another folder (synced Drive / Nextcloud folder …). */
    private val pickBackupFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) com.onpath.app.backup.Backups.setFolder(this, uri)
    }

    /** Android 8–9 only: Documents/OnPath needs the storage permission there. */
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) com.onpath.app.backup.Backups.backupAll(this)
        else vm.sayError(Txt.s(Res.string.msg_no_storage_permission), com.onpath.app.ui.NoticePlace.SETTINGS)
    }

    private fun launchTreePicker(launcher: androidx.activity.result.ActivityResultLauncher<Uri?>) {
        try { launcher.launch(com.onpath.app.backup.BackupFolder.initialUri) } catch (e: Exception) {
            com.onpath.app.debug.Problems.warn("backup", "No folder picker available", e)
            vm.sayError(Txt.s(Res.string.msg_no_folder_picker), com.onpath.app.ui.NoticePlace.SETTINGS)
        }
    }

    private fun needsStorageAccess() = Build.VERSION.SDK_INT < 29 &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        // the app is light: dark status/nav bar icons also when the phone is in dark mode (polish audit A7)
        enableEdgeToEdge(
            androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        com.onpath.app.data.AppLanguage.apply(this) // texts in the chosen language (Developer tools override)
        super.onCreate(savedInstanceState)
        traceCreated(savedInstanceState != null)
        incoming(intent, if (savedInstanceState == null) IncomingIntent.Delivery.CREATE else IncomingIntent.Delivery.RESTORED)

        announceLastCrash()
        setContent {
            // New map files imported: restart so MapLibre opens them fresh (see AppViewModel.importData)
            LaunchedEffect(vm.restartForData) { if (vm.restartForData) restartForNewMapData() }
            // Navigate over the lock screen (owner 2026-10-28): while a tour runs in Navigate, a short press of the power
            // button shows the navigation without unlocking; Plan (routes, settings) asks to unlock first.
            val recNow by vm.recState.collectAsState()
            val overLock = com.onpath.app.ui.LockScreenNav.shows(vm.mode == com.onpath.app.ui.AppMode.NAVIGATE, recNow != null)
            LaunchedEffect(overLock) { showOverLockScreen(overLock) }
            // A new serious problem (error / crash) is said once per run – nothing stays unseen (owner).
            val problemCount by com.onpath.app.debug.Problems.count.collectAsState()
            val serious by com.onpath.app.debug.Problems.newSerious.collectAsState()
            LaunchedEffect(serious, vm.errorCard == null) {
                if (serious == null) return@LaunchedEffect
                if (vm.errorCard != null) return@LaunchedEffect // wait until the current error card is gone
                vm.sayError(Txt.s(Res.string.msg_something_wrong), com.onpath.app.ui.NoticePlace.BACKGROUND)
                com.onpath.app.debug.Problems.announcedSerious()
            }
            OnPathTheme {
                // cells world: with the overview in the APK and map downloads, a fresh install opens on the map (DataFiles.blocking)
                val needData = vm.blockingMissing.isNotEmpty() && !vm.dataScreenDismissed

                Box(Modifier.fillMaxSize()) {
                    if (needData) {
                        MissingDataScreen(
                            checks = vm.dataFiles.check(),
                            dataDir = vm.dataFiles.dir.absolutePath,
                            onCopy = { copy(it) },
                            onRetry = { vm.recheckData() }, // the list on the screen updates – no extra message
                            onContinue = { vm.dataScreenDismissed = true },
                            onImport = { openData.launch(arrayOf("*/*")) },
                            onDownloadRouting = { vm.downloadRoutingData() },
                            busy = vm.dataBusy,
                        )
                    } else {
                        MapScreen(
                            vm = vm,
                            onRequestLocation = { requestLocation(force = true) },
                            onStartNavigation = {
                                withLocation {
                                    vm.startNavigation()
                                }
                            },
                        )
                        if (vm.routesOpen) {
                            BackHandler { vm.routesOpen = false }
                            val recNow = vm.recState.collectAsState().value
                            RoutesScreen(
                                tours = vm.tours,
                                navigating = vm.navigating(recNow != null),
                                recordingId = recNow?.tourId ?: -1L,
                                onBack = { vm.routesOpen = false },
                                onImport = { launchImport() },
                                onOpen = { vm.requestOpen(it) },
                                onExport = { vm.exportTour(it) },
                                onDelete = { vm.deleteTour(it) },
                                onRename = { t, name -> vm.renameTour(t, name) },
                                onExportAll = { vm.exportAll() },
                                busy = vm.busy,
                                note = vm.routesNote,
                                onRecycleBin = { vm.openRecycleBin() },
                                onEditRides = { vm.openEditRides(it) },
                            )
                        }
                        vm.editRides?.let { (t, s) ->
                            com.onpath.app.ui.RideSummaryDialog(s, onSave = { vm.applyEditRides(t, it) }, onDelete = null,
                                onDismiss = { vm.editRides = null }, title = str(Res.string.msg_rides_title, t.name), initial = t.rides?.choice)
                        }
                        vm.recycleBin?.let { bin ->
                            BackHandler { vm.recycleBin = null }
                            com.onpath.app.ui.RecycleBinScreen(bin, onBack = { vm.recycleBin = null },
                                onRestore = { vm.restoreFromBin(it) }, onDeleteForever = { vm.deleteForever(it) })
                        }
                        // one dialog after the other on a first start: location first, then the map, then this
                        if (com.onpath.app.ui.RestoreOfferRule.show(vm.restoreOffer, vm.recState.collectAsState().value != null,
                                vm.locationAsking, vm.loadModalOpen)) {
                            com.onpath.app.ui.RestoreOfferDialog(
                                onRestore = { vm.restoreOfferAnswered(); launchTreePicker(pickRestoreFolder) },
                                onNotNow = { vm.restoreOfferAnswered() })
                        }
                    }
                    vm.route?.takeIf { vm.routeDetailsOpen }?.let { r ->
                        BackHandler { vm.routeDetailsOpen = false }
                        com.onpath.app.ui.RouteDetailsScreen(r, vm.activity, onBack = { vm.routeDetailsOpen = false })
                    }
                    if (vm.showSettings) {
                        BackHandler { vm.settingsBack() }
                        SettingsScreen(
                            page = vm.settingsPage,
                            onPage = { vm.settingsPage = it },
                            settings = vm.settings,
                            onChange = vm::updateSettings,
                            activity = vm.activity,
                            onAppSettings = ::openAppSettings,
                            checks = remember(vm.missing) { vm.dataFiles.check() },
                            mapPackage = vm.packageCheck,
                            diagnostics = true,
                            devTools = vm.devToolsShown,
                            onUnlockDevTools = vm::unlockDevTools,
                            testStyle = com.onpath.app.ui.TestStyleUi(
                                // a file check per composition: cheap, and fresh after a push or reload
                                status = com.onpath.app.map.StyleLoader.devStyleStatus(vm.dataFiles.dir, vm.devToolsShown),
                                onRemove = vm::removeTestStyle,
                                onReload = vm::reloadMapStyle,
                            ),
                            diagnosticsSeenMs = vm.diagnosticsSeenMs,
                            diagnosticsBusy = vm.busy == com.onpath.app.ui.Busy.DIAGNOSTICS,
                            onExportDiagnostics = ::exportDiagnostics,
                            problems = remember(problemCount) { com.onpath.app.debug.Problems.snapshot() },
                            onClearProblems = { com.onpath.app.debug.Problems.clear() },
                            language = com.onpath.app.ui.LanguageUi(com.onpath.app.data.AppLanguage.choice(this@MainActivity),
                                com.onpath.app.data.AppLanguage.describe(this@MainActivity), ::setLanguage),
                            mapCells = run {
                                val installed by vm.cells.installed.collectAsState()
                                val states by vm.cells.states.collectAsState()
                                val gen by vm.cells.generation.collectAsState()
                                com.onpath.app.ui.MapCellsDebug(
                                    installed = installed.map { Triple(it.id.id, it.version, it.totalBytes) },
                                    active = states.filter { it.value !is com.onpath.app.cells.CellState.Ready }
                                        .map { (c, s) -> c.id to s.label() },
                                    store = if (installed.isEmpty()) "no areas – data folder" else "store generation $gen",
                                    onRequestAtCentre = { vm.requestCellAtMapCentre() },
                                    onDelete = { id -> com.onpath.app.cells.CellId.parse(id)?.let { vm.cells.delete(listOf(it)) } },
                                )
                            },
                            offlineMaps = run {
                                val installed by vm.cells.installed.collectAsState()
                                val states by vm.cells.states.collectAsState()
                                vm.recState.collectAsState().value // Clear all is blocked while a tour runs
                                LaunchedEffect(installed) { vm.loadAreaNames() }
                                vm.offlineMapsUi(states, installed)
                            },
                            offlineMapsCb = com.onpath.app.ui.OfflineMapsCallbacks(
                                onDelete = vm::deleteMapArea,
                                onWifiOnly = { on -> vm.updateSettings(vm.settings.copy(mapsWifiOnly = on)) },
                                onUpdateNow = vm::updateMapsNow,
                                onCancelAll = vm::cancelAllDownloads,
                                onCleanUp = vm::prepareCleanup,
                                onCleanUpConfirm = vm::confirmCleanup,
                                onCleanUpCancel = { vm.cleanupPreview = null },
                                onClearAll = vm::clearAllMaps,
                            ),
                            dataDir = vm.dataFiles.dir.absolutePath,
                            version = "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                            onBack = { vm.settingsPage = null; vm.showSettings = false },
                            themes = remember(vm.styleVersion) { com.onpath.app.map.StyleLoader.themes(this@MainActivity, vm.dataFiles.dir) },
                            onImportData = { openData.launch(arrayOf("*/*")) },
                            onDownloadRouting = { vm.downloadRoutingData() },
                            onTimeFormulas = if (vm.route != null) ({ vm.openTimeDetails(technical = true) }) else null,
                            dataBusy = vm.dataBusy,
                            note = vm.settingsNote,
                            update = vm.update.takeIf { vm.updatesEnabled },
                            onCheckUpdate = { vm.checkForUpdates(manual = true) },
                            onInstallUpdate = { vm.installUpdate() },
                            backup = run {
                                val b by com.onpath.app.backup.Backups.status.collectAsState()
                                com.onpath.app.ui.BackupUi(b.folder, b.lastMs, b.count, b.lastError, b.custom, vm.restoreProgress,
                                    needsStorageAccess = remember(b) { needsStorageAccess() },
                                    backingUp = vm.busy == com.onpath.app.ui.Busy.BACKUP)
                            },
                            backupCb = com.onpath.app.ui.BackupCallbacks(
                                onBackupNow = {
                                    vm.busy = com.onpath.app.ui.Busy.BACKUP
                                    com.onpath.app.backup.Backups.backupAll(this@MainActivity) { _ ->
                                        // a failure says itself (Backups.failure); success shows in "Last backup: … · N files"
                                        runOnUiThread { if (vm.busy == com.onpath.app.ui.Busy.BACKUP) vm.busy = null }
                                    }
                                },
                                onChooseFolder = { launchTreePicker(pickBackupFolder) },
                                onUseDocuments = { com.onpath.app.backup.Backups.setFolder(this@MainActivity, null) },
                                onRestore = { launchTreePicker(pickRestoreFolder) },
                                onAllowStorage = { storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) },
                            ),
                        )
                    }
                    // over Settings too: Diagnostics → Developer tools opens the technical view
                    vm.timeDetails?.let { td ->
                        BackHandler { vm.timeDetails = null }
                        com.onpath.app.ui.TimeDetailsScreen(td, onBack = { vm.timeDetails = null })
                    }
                    // Back in Plan closes a map overlay / place card or a shown track. Navigate is left only by the slide switch.
                    BackHandler(enabled = !vm.routesOpen && !vm.showSettings && vm.mode == AppMode.PLAN &&
                        (vm.shown != null || vm.mapOverlayOpen)) {
                        if (vm.mapOverlayOpen) vm.closeMapOverlays() else vm.closeShown()
                    }

                    // The one error card (owner 2026-10-27: no dark bar; results go where the user acted – vm.say).
                    // Stays until OK is tapped (owner rule: no message disappears by itself – rain, stress).
                    vm.errorCard?.let { msg ->
                        com.onpath.app.ui.ErrorCard(msg, onOk = { vm.errorCard = null },
                            // below the blue strip while it shows – never over its buttons (UX review 2026-10-23, problem 11)
                            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(start = 12.dp, end = 12.dp,
                                top = com.onpath.app.ui.MessagePlacement.topDp(vm.backgroundStripShown && vm.mode == AppMode.PLAN,
                                    vm.backgroundStripHeightDp).dp))
                    }
                }

                RouteDialogs(vm)
                vm.restoreProgress?.let { com.onpath.app.ui.RestoreProgressDialog(it) }
                // "Allow all the time" is never asked (owner C2 2026-10-24) – only the quiet hint in Settings → Navigation
                if (vm.askUpdateDuringTour) {
                    AlertDialog(
                        onDismissRequest = { vm.askUpdateDuringTour = false },
                        title = { Text(str(Res.string.msg_update_tour_title)) },
                        text = { com.onpath.app.ui.DialogText(str(Res.string.msg_update_tour_text)) },
                        confirmButton = { TextButton(onClick = { vm.askUpdateDuringTour = false }) { Text(str(Res.string.msg_update_after_tour)) } },
                        dismissButton = { TextButton(onClick = { vm.installUpdate(duringTourOk = true) }) { Text(str(Res.string.msg_update_install_now)) } },
                    )
                }
                vm.unfinished?.let { t ->
                    UnfinishedDialog(t, onContinue = { withLocation { vm.continueRecording(t) } }, onSave = { vm.askSaveUnfinished(t) },
                        onDelete = { vm.deleteUnfinished(t) })
                }
                vm.unfinishedRides?.let { (t, s) ->
                    com.onpath.app.ui.RideSummaryDialog(s, onSave = { vm.saveUnfinished(t, it) }, onDelete = { vm.deleteUnfinished(t) },
                        onDismiss = { vm.backToUnfinished() }, title = str(Res.string.msg_unfinished_save_title))
                }
                vm.finishedTour?.let { t ->
                    FinishedTourDialogs(t,
                        times = vm.finishedTimes,
                        onExport = { vm.exportTour(t) },
                        onDelete = { vm.deleteTour(t); vm.finishedTour = null },
                        onDone = { vm.finishedTour = null })
                }
            }
        }
        requestLocation(force = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        com.onpath.app.debug.Problems.trace("app", ScreenLifecycle.newIntentLine(screenNo, describe(intent)))
        incoming(intent, IncomingIntent.Delivery.NEW_INTENT)
    }

    override fun onDestroy() {
        val others = ScreenLifecycle.destroyed(screenNo)
        com.onpath.app.debug.Problems.trace("app", ScreenLifecycle.destroyedLine(screenNo, isFinishing, isChangingConfigurations, others))
        super.onDestroy()
    }

    /** Number of this screen in the process (event log: field 0.10.1 had two at once at start – [ScreenLifecycle]). */
    private var screenNo = 0

    private fun traceCreated(restored: Boolean) {
        val (n, others) = ScreenLifecycle.created()
        screenNo = n
        val line = ScreenLifecycle.createdLine(n, others, taskId, isTaskRoot, restored, describe(intent))
        // a second screen at once is not normal: noted in Diagnostics (WARN is not announced on screen)
        if (others > 0) com.onpath.app.debug.Problems.warn("app", line) else com.onpath.app.debug.Problems.trace("app", line)
    }

    private fun describe(i: Intent?): String = if (i == null) "no intent" else ScreenLifecycle.intentText(
        i.action, i.data?.scheme, i.type, i.categories, i.flags, i.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0,
        try { referrer?.host } catch (e: Exception) { null }) // expected: referrer is best effort (event log only)

    /** Android may reset the default locale to the phone's on a configuration change: keep the chosen language. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        com.onpath.app.data.AppLanguage.apply(this)
    }

    /** Developer tools → language: store it, then build the screen again in that language. */
    private fun setLanguage(c: com.onpath.app.data.LanguageChoice) {
        com.onpath.app.data.AppLanguage.set(this, c)
        recreate()
    }

    /** Lets the activity show over the lock screen (Navigate during a tour) or not. Never throws. */
    private fun showOverLockScreen(on: Boolean) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 27) setShowWhenLocked(on)
            else @Suppress("DEPRECATION") if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
                else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            com.onpath.app.debug.Problems.trace("app", "Over the lock screen: $on")
        } catch (e: Exception) {
            com.onpath.app.debug.Problems.warn("app", "Show over the lock screen not changed ($on)", e)
        }
    }

    /**
     * Navigate → Plan while the phone is locked: unlock first (Plan shows routes and settings); unlocked → Plan at once.
     * Without the unlock (cancelled) the navigation stays.
     */
    fun leaveNavigate(toPlan: () -> Unit) {
        val km = getSystemService(android.app.KeyguardManager::class.java)
        if (km == null || !km.isKeyguardLocked || android.os.Build.VERSION.SDK_INT < 26) { toPlan(); return }
        try {
            km.requestDismissKeyguard(this, object : android.app.KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { toPlan() }
                override fun onDismissCancelled() { com.onpath.app.debug.Problems.trace("app", "Unlock for Plan cancelled – Navigate stays") }
                override fun onDismissError() { com.onpath.app.debug.Problems.info("app", "Unlock for Plan failed – Navigate stays") }
            })
        } catch (e: Exception) {
            com.onpath.app.debug.Problems.warn("app", "Unlock request failed; Plan opened anyway", e)
            toPlan()
        }
    }

    override fun onStart() {
        com.onpath.app.data.AppLanguage.apply(this)
        super.onStart()
        vm.tracker.start()
        vm.recheckData()
        vm.onAppStart() // owner: the app always opens at the current location (when nothing is running)
        com.onpath.app.debug.Health.attach(window) // janky frames + main-thread watchdog while on screen
    }

    override fun onStop() {
        vm.onAppStop()
        com.onpath.app.debug.Health.detach(window)
        vm.tracker.stop()
        super.onStop()
    }

    /**
     * An incoming link / share / GPX, handled once ([IncomingIntent]): after a reinstall or restart Android starts the
     * screen with the task's first intent again – that one is not handled a second time (field bug 2026-10-27).
     */
    private fun incoming(intent: Intent?, delivery: IncomingIntent.Delivery) {
        intent ?: return
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        val fp = try {
            IncomingIntent.fingerprint(intent.action, intent.dataString, intent.type, intent.getStringExtra(Intent.EXTRA_TEXT),
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.toString())
        } catch (e: Exception) {
            // a malformed intent from another app (bad extras): handled as before, the handlers check it themselves
            com.onpath.app.debug.Problems.warn("app", "Incoming intent could not be fingerprinted; handled without the once-check", e)
            if (delivery != IncomingIntent.Delivery.RESTORED) handleIntent(intent)
            return
        }
        if (!IncomingIntent.shouldHandle(delivery, fromHistory, fp, IncomingIntent.lastHandled(this))) {
            if (fp != null && delivery == IncomingIntent.Delivery.CREATE && !fromHistory)
                com.onpath.app.debug.Problems.trace("app", "Incoming ${intent.action} handled before (restart / reinstall) – not again")
            return
        }
        IncomingIntent.remember(this, fp!!)
        handleIntent(intent)
    }

    /** GPX from "open with" (VIEW) or "share" (SEND). */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        // Reopened from recents: that share was handled already.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        // a plan link (plan.onpath.earth/#p=…: camera / QR code, a tapped link, shared text) – A-005
        planLinkOf(intent)?.let { found ->
            vm.dataScreenDismissed = true
            vm.receivePlanLink(found, if (intent.action == Intent.ACTION_VIEW) "opened" else "shared")
            return
        }
        sharedPlaceOf(intent)?.let { place ->
            vm.dataScreenDismissed = true
            vm.receiveSharedPlace(place)
            return
        }
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri != null) {
            vm.dataScreenDismissed = true
            vm.importGpx(uri)
        }
    }

    /** A plan link opened (VIEW) or shared as text (SEND); null for everything else (PlanLinks.fromIntent). */
    private fun planLinkOf(intent: Intent): com.onpath.app.routing.PlanLinks.Found? = try {
        com.onpath.app.routing.PlanLinks.fromIntent(when (intent.action) {
            Intent.ACTION_VIEW -> com.onpath.app.routing.PlanLinks.Via.OPENED
            Intent.ACTION_SEND -> com.onpath.app.routing.PlanLinks.Via.SHARED
            else -> null
        }, intent.dataString, intent.type,
            intent.getStringExtra(Intent.EXTRA_TEXT),
            hasStream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) != null)
    } catch (e: Exception) {
        // a malformed intent from another app (bad extras): not a plan link – the other handlers decide
        com.onpath.app.debug.Problems.warn("plan", "Intent could not be checked for a plan link", e)
        null
    }

    /** A place shared as text (Google Maps "Share") or opened as a `geo:` link; null for files and everything else. */
    private fun sharedPlaceOf(intent: Intent): com.onpath.app.search.SharedPlace? = try {
        when {
            intent.action == Intent.ACTION_VIEW && intent.data?.scheme.equals("geo", ignoreCase = true) ->
                com.onpath.app.search.SharedPlaceParser.fromGeoUri(intent.data.toString())
            intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/plain") == true &&
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) == null ->
                com.onpath.app.search.SharedPlaceParser.fromText(intent.getStringExtra(Intent.EXTRA_TEXT),
                    intent.getStringExtra(Intent.EXTRA_SUBJECT)).also {
                    if (it == null) vm.sayError(Txt.s(Res.string.msg_shared_no_place), com.onpath.app.ui.NoticePlace.MAP)
                }
            else -> null
        }
    } catch (e: Exception) {
        com.onpath.app.debug.Problems.warn("search", "Shared place could not be read", e)
        vm.sayError(Txt.s(Res.string.msg_shared_unreadable), com.onpath.app.ui.NoticePlace.MAP)
        null
    }

    private fun launchImport() {
        try {
            openGpx.launch(arrayOf("application/gpx+xml", "application/octet-stream", "text/xml", "application/xml", "*/*"))
        } catch (e: Exception) {
            com.onpath.app.debug.Problems.warn("ui", "No file picker available for GPX import", e)
            vm.sayError(Txt.s(Res.string.msg_no_file_picker), com.onpath.app.ui.NoticePlace.ROUTES)
        }
    }

    /**
     * Clears MapLibre's tile cache and restarts the app process: MapLibre keeps the index of an opened .pmtiles file
     * in memory, so replaced map files would show gaps until a restart. Only called when no tour is recording.
     */
    private fun restartForNewMapData() {
        val restart = {
            val launch = packageManager.getLaunchIntentForPackage(packageName)?.component
            if (launch != null) startActivity(Intent.makeRestartActivityTask(launch))
            Runtime.getRuntime().exit(0)
        }
        try {
            org.maplibre.android.offline.OfflineManager.getInstance(this).clearAmbientCache(
                object : org.maplibre.android.offline.OfflineManager.FileSourceCallback {
                    override fun onSuccess() = restart()
                    override fun onError(message: String) {
                        com.onpath.app.debug.Problems.warn("map", "Map tile cache could not be cleared before restart: $message"); restart()
                    }
                })
        } catch (e: Exception) { com.onpath.app.debug.Problems.warn("map", "Map tile cache could not be cleared before restart", e); restart() }
    }


    /** The app crashed in an earlier run: say so once (the details are in Settings → Diagnostics). */
    private fun announceLastCrash() {
        val sp = getSharedPreferences("onpath", 0)
        val shown = sp.getLong("crashShownUntil", 0)
        val crash = com.onpath.app.debug.Problems.lastCrash(shown + 1) ?: return
        sp.edit().putLong("crashShownUntil", crash.last).apply()
        if (vm.errorCard == null) vm.sayError(Txt.s(Res.string.msg_crash_last_time), com.onpath.app.ui.NoticePlace.BACKGROUND)
    }

    /**
     * Settings → Diagnostics → Export: live map part on the main thread (MapLibre), the rest on IO, then the share
     * sheet (send by mail / chat / save to Files). Never throws.
     */
    private fun exportDiagnostics() {
        val ctrl = com.onpath.app.ui.ctrlRef.get()
        val metrics = resources.displayMetrics
        val mapPart = com.onpath.app.debug.Diagnostics.mapSnapshot(ctrl?.map, metrics.widthPixels, metrics.heightPixels)
        val styleJson = try { ctrl?.map?.style?.json } catch (e: Exception) {
            com.onpath.app.debug.Problems.warn("ui", "Diagnostics: loaded style not readable", e); null
        }
        val state = vm.diagnosticsState()
        vm.busy = com.onpath.app.ui.Busy.DIAGNOSTICS
        Thread {
            val result = runCatching { com.onpath.app.debug.Diagnostics.export(this, mapPart, styleJson, state) }
            runOnUiThread {
                vm.busy = null
                result.onSuccess { f ->
                    val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
                    val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_SUBJECT, f.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try { startActivity(Intent.createChooser(send, "Send diagnostics")) } catch (e: Exception) {
                        com.onpath.app.debug.Problems.warn("ui", "No app to share the diagnostics file", e)
                        vm.sayError("No app found to send the file with.", com.onpath.app.ui.NoticePlace.SETTINGS)
                    }
                }.onFailure {
                    com.onpath.app.debug.Problems.error("ui", "Diagnostics export failed", it)
                    vm.sayError("Diagnostics could not be created – the phone's storage may be full. Please try again.", com.onpath.app.ui.NoticePlace.SETTINGS)
                }
            }
        }.start()
    }

    private fun hasLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun withLocation(action: () -> Unit) {
        if (hasLocation()) action() else { afterPermission = action; requestLocation(force = true) }
    }

    /** Asks once per start; when the user chose "don't ask again", opens app settings instead. */
    private fun requestLocation(force: Boolean) {
        if (hasLocation() && (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)) return
        if (!force && askedThisSession) return
        val permanentlyDenied = askedThisSession && !hasLocation() &&
            !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
        askedThisSession = true
        if (permanentlyDenied) { openAppSettings(); return }
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        try { vm.locationAsking = true; permissionLauncher.launch(perms.toTypedArray()) } catch (e: Exception) {
            vm.locationAsking = false
            com.onpath.app.debug.Problems.error("gps", "Location permission request could not be shown", e)
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } catch (e: Exception) { com.onpath.app.debug.Problems.warn("ui", "Android app settings could not be opened", e) }
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("adb", text))
        // Android 13+ confirms a copy itself; below that the copied path is simply there – no message (owner 2026-10-27)
    }
}
