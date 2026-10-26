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

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()
    private var afterPermission: (() -> Unit)? = null
    private var askedThisSession = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.locationAsking = false
        vm.tracker.start()
        if (vm.tracker.hasPermission()) afterPermission?.invoke()
        else vm.message = "Without location permission the map works, but position and recording do not."
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
        else vm.message = "Without storage access OnPath cannot copy tracks and routes to Documents/OnPath. Choose another folder in Settings → Backup."
    }

    private fun launchTreePicker(launcher: androidx.activity.result.ActivityResultLauncher<Uri?>) {
        try { launcher.launch(com.onpath.app.backup.BackupFolder.initialUri) } catch (e: Exception) {
            com.onpath.app.debug.Problems.warn("backup", "No folder picker available", e)
            vm.message = "This phone has no folder picker – restore and folder choice are not possible."
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
        if (savedInstanceState == null) handleIntent(intent)

        announceLastCrash()
        setContent {
            // New map files imported: restart so MapLibre opens them fresh (see AppViewModel.importData)
            LaunchedEffect(vm.restartForData) { if (vm.restartForData) restartForNewMapData() }
            // A new serious problem (error / crash) is said once per run – nothing stays unseen (owner).
            val problemCount by com.onpath.app.debug.Problems.count.collectAsState()
            val serious by com.onpath.app.debug.Problems.newSerious.collectAsState()
            LaunchedEffect(serious, vm.message == null) {
                if (serious == null) return@LaunchedEffect
                if (vm.message != null) return@LaunchedEffect // wait until the current message is gone
                vm.message = "Something went wrong. It was noted in Settings → Diagnostics."
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
                                onRecycleBin = { vm.openRecycleBin() },
                                onEditRides = { vm.openEditRides(it) },
                            )
                        }
                        vm.editRides?.let { (t, s) ->
                            com.onpath.app.ui.RideSummaryDialog(s, onSave = { vm.applyEditRides(t, it) }, onDelete = null,
                                onDismiss = { vm.editRides = null }, title = "Rides in “${t.name}”", initial = t.rides?.choice)
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

                    // Stays until OK is tapped (owner rule: no message disappears by itself – rain, stress).
                    vm.message?.let { msg ->
                        // errors get ⚠ and a red edge (review 2026-10-24 B10)
                        com.onpath.app.ui.MessageBar(msg, onOk = { vm.message = null },
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
                        title = { Text("A tour is being recorded") },
                        text = { com.onpath.app.ui.DialogText("Installing the update closes OnPath and recording stops. Your track so far is kept – " +
                            "open OnPath afterwards and tap Continue. Better: install after the tour.") },
                        confirmButton = { TextButton(onClick = { vm.askUpdateDuringTour = false }) { Text("After the tour") } },
                        dismissButton = { TextButton(onClick = { vm.installUpdate(duringTourOk = true) }) { Text("Install now") } },
                    )
                }
                vm.unfinished?.let { t ->
                    UnfinishedDialog(t, onContinue = { withLocation { vm.continueRecording(t) } }, onSave = { vm.askSaveUnfinished(t) },
                        onDelete = { vm.deleteUnfinished(t) })
                }
                vm.unfinishedRides?.let { (t, s) ->
                    com.onpath.app.ui.RideSummaryDialog(s, onSave = { vm.saveUnfinished(t, it) }, onDelete = { vm.deleteUnfinished(t) },
                        onDismiss = { vm.backToUnfinished() }, title = "Save the unfinished tour?")
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
        handleIntent(intent)
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

    /** GPX from "open with" (VIEW) or "share" (SEND). */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        // Reopened from recents: that share was handled already.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
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

    /** A place shared as text (Google Maps "Share") or opened as a `geo:` link; null for files and everything else. */
    private fun sharedPlaceOf(intent: Intent): com.onpath.app.search.SharedPlace? = try {
        when {
            intent.action == Intent.ACTION_VIEW && intent.data?.scheme.equals("geo", ignoreCase = true) ->
                com.onpath.app.search.SharedPlaceParser.fromGeoUri(intent.data.toString())
            intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/plain") == true &&
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) == null ->
                com.onpath.app.search.SharedPlaceParser.fromText(intent.getStringExtra(Intent.EXTRA_TEXT),
                    intent.getStringExtra(Intent.EXTRA_SUBJECT)).also {
                    if (it == null) vm.message = "OnPath found no place in the shared text."
                }
            else -> null
        }
    } catch (e: Exception) {
        com.onpath.app.debug.Problems.warn("search", "Shared place could not be read", e)
        vm.message = "OnPath could not read the shared place."
        null
    }

    private fun launchImport() {
        try {
            openGpx.launch(arrayOf("application/gpx+xml", "application/octet-stream", "text/xml", "application/xml", "*/*"))
        } catch (e: Exception) {
            com.onpath.app.debug.Problems.warn("ui", "No file picker available for GPX import", e)
            vm.message = "No file picker available."
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
        if (vm.message == null) vm.message = "OnPath stopped unexpectedly last time. " +
            "Details: Settings → Diagnostics."
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
                        vm.message = "No app found to send the file with."
                    }
                }.onFailure {
                    com.onpath.app.debug.Problems.error("ui", "Diagnostics export failed", it)
                    vm.message = "Diagnostics could not be created – the phone's storage may be full. Please try again."
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
        // Android 13+ confirms a copy itself; an extra message would only need another tap.
        if (Build.VERSION.SDK_INT < 33) vm.message = "Copied."
    }
}
