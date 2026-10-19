package com.onpath.app

import android.Manifest
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
import androidx.compose.material3.Snackbar
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
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()
    private var afterPermission: (() -> Unit)? = null
    private var askedThisSession = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)

        diagnosticsOn = com.onpath.app.debug.Diagnostics.enabled(this)
        announceLastCrash()
        setContent {
            // New map files imported: restart so MapLibre opens them fresh (see AppViewModel.importData)
            LaunchedEffect(vm.restartForData) { if (vm.restartForData) restartForNewMapData() }
            // A new serious problem (error / crash) is said once per run – nothing stays unseen (owner).
            val problemCount by com.onpath.app.debug.Problems.count.collectAsState()
            val serious by com.onpath.app.debug.Problems.newSerious.collectAsState()
            LaunchedEffect(serious, vm.message == null) {
                val p = serious ?: return@LaunchedEffect
                if (vm.message != null) return@LaunchedEffect // wait until the current message is gone
                vm.message = "Problem (${p.area}): ${p.message.take(160)}. Details: Settings → Diagnostics."
                com.onpath.app.debug.Problems.announcedSerious()
            }
            OnPathTheme {
                val needData = vm.missing.isNotEmpty() && !vm.dataScreenDismissed

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
                                    val newTour = vm.recState.value == null
                                    vm.startNavigation()
                                }
                            },
                        )
                        if (vm.routesOpen) {
                            BackHandler { vm.routesOpen = false }
                            RoutesScreen(
                                tours = vm.tours,
                                navigating = vm.navigating(vm.recState.collectAsState().value != null),
                                onBack = { vm.routesOpen = false },
                                onImport = { launchImport() },
                                onOpen = { vm.requestOpen(it) },
                                onExport = { vm.exportTour(it) },
                                onDelete = { vm.deleteTour(it) },
                                onRename = { t, name -> vm.renameTour(t, name) },
                            )
                        }
                    }
                    vm.timeDetails?.let { td ->
                        BackHandler { vm.timeDetails = null }
                        com.onpath.app.ui.TimeDetailsScreen(td, onBack = { vm.timeDetails = null })
                    }
                    if (vm.showSettings) {
                        BackHandler { vm.showSettings = false }
                        SettingsScreen(
                            settings = vm.settings,
                            onChange = vm::updateSettings,
                            onAppSettings = ::openAppSettings,
                            checks = remember(vm.missing) { vm.dataFiles.check() },
                            mapPackage = vm.packageCheck,
                            diagnosticsOn = diagnosticsOn,
                            onDiagnostics = { on -> diagnosticsOn = on; com.onpath.app.debug.Diagnostics.setEnabled(this@MainActivity, on) },
                            onExportDiagnostics = ::exportDiagnostics,
                            problems = remember(problemCount) { com.onpath.app.debug.Problems.snapshot() },
                            onClearProblems = { com.onpath.app.debug.Problems.clear() },
                            dataDir = vm.dataFiles.dir.absolutePath,
                            version = "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                            onBack = { vm.showSettings = false },
                            themes = remember(vm.styleVersion) { com.onpath.app.map.StyleLoader.themes(this@MainActivity, vm.dataFiles.dir) },
                            onImportData = { openData.launch(arrayOf("*/*")) },
                            onDownloadRouting = { vm.downloadRoutingData() },
                            dataBusy = vm.dataBusy,
                            update = vm.update.takeIf { vm.updatesEnabled },
                            onCheckUpdate = { vm.checkForUpdates(manual = true) },
                            onInstallUpdate = { vm.installUpdate() },
                            learnedPace = vm.learnedPace,
                            onResetPace = vm::resetLearnedPace,
                        )
                    }
                    // Back in Plan closes a map overlay / place card or a shown track. Navigate is left only by the slide switch.
                    BackHandler(enabled = !vm.routesOpen && !vm.showSettings && vm.mode == AppMode.PLAN &&
                        (vm.shown != null || vm.mapOverlayOpen)) {
                        if (vm.mapOverlayOpen) vm.closeMapOverlays() else vm.closeShown()
                    }

                    // Stays until OK is tapped (owner rule: no message disappears by itself – rain, stress).
                    vm.message?.let { msg ->
                        Snackbar(
                            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 70.dp, start = 12.dp, end = 12.dp),
                            action = { TextButton(onClick = { vm.message = null }) { Text("OK") } },
                        ) { Text(msg) }
                    }
                }

                RouteDialogs(vm)
                vm.unfinished?.let { t ->
                    UnfinishedDialog(t, onContinue = { withLocation { vm.continueRecording(t) } }, onSave = { vm.saveUnfinished(t) })
                }
                vm.finishedTour?.let { t ->
                    FinishedTourDialogs(t,
                        times = vm.finishedTimes,
                        onExport = { vm.exportTour(t) },
                        onDelete = { vm.deleteTour(t); vm.finishedTour = null },
                        onDone = { vm.finishedTour = null },
                        // a saved plan already left the map (0.6.9); an unsaved one can go here with one tap (Undo brings it back)
                        onClearRoute = if (vm.waypoints.size >= 2) ({ vm.clearPlan() }) else null)
                }
            }
        }
        requestLocation(force = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        vm.tracker.start()
        vm.recheckData()
        vm.onAppStart() // owner: the app always opens at the current location (when nothing is running)
    }

    override fun onStop() {
        vm.onAppStop()
        vm.tracker.stop()
        super.onStop()
    }

    /** GPX from "open with" (VIEW) or "share" (SEND). */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
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

    private var diagnosticsOn by mutableStateOf(true)

    /** The app crashed in an earlier run: say so once (the details are in Settings → Diagnostics). */
    private fun announceLastCrash() {
        val sp = getSharedPreferences("onpath", 0)
        val shown = sp.getLong("crashShownUntil", 0)
        val crash = com.onpath.app.debug.Problems.lastCrash(shown + 1) ?: return
        sp.edit().putLong("crashShownUntil", crash.last).apply()
        if (vm.message == null) vm.message = "OnPath stopped unexpectedly last time (${crash.message}). " +
            "Details: Settings → Diagnostics → Export diagnostics."
    }

    /**
     * Settings → Diagnostics → Export: live map part on the main thread (MapLibre), the rest on IO, then the share
     * sheet (send by mail / chat / save to Files). Never throws.
     */
    private fun exportDiagnostics() {
        val ctrl = com.onpath.app.ui.ctrlRef[0]
        val metrics = resources.displayMetrics
        val mapPart = com.onpath.app.debug.Diagnostics.mapSnapshot(ctrl?.map, metrics.widthPixels, metrics.heightPixels)
        val styleJson = try { ctrl?.map?.style?.json } catch (e: Exception) {
            com.onpath.app.debug.Problems.warn("ui", "Diagnostics: loaded style not readable", e); null
        }
        val state = vm.diagnosticsState()
        vm.message = "Creating diagnostics…"
        Thread {
            val result = runCatching { com.onpath.app.debug.Diagnostics.export(this, mapPart, styleJson, state) }
            runOnUiThread {
                result.onSuccess { f ->
                    vm.message = null
                    val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
                    val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_SUBJECT, f.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try { startActivity(Intent.createChooser(send, "Send diagnostics")) } catch (e: Exception) {
                        com.onpath.app.debug.Problems.warn("ui", "No app to share the diagnostics file", e)
                        vm.message = "No app to share with. The file is in the app's cache: ${f.name}"
                    }
                }.onFailure {
                    com.onpath.app.debug.Problems.error("ui", "Diagnostics export failed", it)
                    vm.message = "Diagnostics could not be created: ${it.message ?: it.javaClass.simpleName}"
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
        try { permissionLauncher.launch(perms.toTypedArray()) } catch (e: Exception) {
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
