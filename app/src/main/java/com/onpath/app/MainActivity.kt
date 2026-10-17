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
import com.onpath.app.ui.FinishedDialog
import com.onpath.app.ui.MapScreen
import com.onpath.app.ui.MissingDataScreen
import com.onpath.app.ui.Mode
import com.onpath.app.ui.OnPathTheme
import com.onpath.app.ui.ToursScreen
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

    private val openGpx = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importGpx(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            OnPathTheme {
                var showBatteryHint by remember { mutableStateOf(false) }
                val needData = vm.missing.isNotEmpty() && !vm.dataScreenDismissed

                Box(Modifier.fillMaxSize()) {
                    if (needData) {
                        MissingDataScreen(
                            checks = vm.dataFiles.check(),
                            dataDir = vm.dataFiles.dir.absolutePath,
                            onCopy = { copy(it) },
                            onRetry = { vm.recheckData(); if (vm.missing.isNotEmpty()) vm.message = "Still missing: ${vm.missing.joinToString { it.name }}" },
                            onContinue = { vm.dataScreenDismissed = true },
                        )
                    } else {
                        MapScreen(
                            vm = vm,
                            onRequestLocation = { requestLocation(force = true) },
                            onRecord = {
                                withLocation {
                                    vm.startRecording()
                                    if (!vm.batteryHintShown) showBatteryHint = true
                                }
                            },
                            onImport = { launchImport() },
                        )
                        if (vm.showTours) {
                            BackHandler { vm.showTours = false }
                            ToursScreen(
                                tours = vm.tours,
                                onBack = { vm.showTours = false },
                                onImport = { launchImport() },
                                onOpen = { vm.openTour(it) },
                                onExport = { vm.exportTour(it) },
                                onDelete = { vm.deleteTour(it) },
                            )
                        }
                    }
                    BackHandler(enabled = !vm.showTours && (vm.mode == Mode.PLAN || vm.shown != null)) {
                        if (vm.shown != null) vm.closeShown() else vm.closePlanning()
                    }

                    vm.message?.let { msg ->
                        LaunchedEffect(msg) { delay(if (msg.length > 80) 7000 else 3500); if (vm.message == msg) vm.message = null }
                        Snackbar(
                            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 70.dp, start = 12.dp, end = 12.dp),
                            action = { TextButton(onClick = { vm.message = null }) { Text("OK") } },
                        ) { Text(msg) }
                    }
                }

                vm.unfinished?.let { t ->
                    UnfinishedDialog(t, onContinue = { withLocation { vm.continueRecording(t) } }, onSave = { vm.saveUnfinished(t) })
                }
                vm.finishedTour?.let { t ->
                    FinishedDialog(t,
                        onExport = { vm.exportTour(t) },
                        onDelete = { vm.deleteTour(t); vm.finishedTour = null },
                        onDone = { vm.finishedTour = null })
                }
                if (showBatteryHint) {
                    AlertDialog(
                        onDismissRequest = { showBatteryHint = false; vm.batteryHintShown = true },
                        title = { Text("Recording in the background") },
                        text = {
                            Text("OnPath keeps recording with the screen off; you see a notification while it runs. " +
                                "For best reliability set OnPath's battery usage to “Unrestricted”. " +
                                "Location “Allow all the time” is optional and not needed for recording.")
                        },
                        confirmButton = { TextButton(onClick = { showBatteryHint = false; vm.batteryHintShown = true }) { Text("OK") } },
                        dismissButton = {
                            TextButton(onClick = {
                                showBatteryHint = false; vm.batteryHintShown = true
                                openAppSettings()
                            }) { Text("App settings") }
                        },
                    )
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
    }

    override fun onStop() {
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
        } catch (_: Exception) {
            vm.message = "No file picker available."
        }
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
        try { permissionLauncher.launch(perms.toTypedArray()) } catch (_: Exception) { }
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("adb", text))
        vm.message = "Copied."
    }
}
