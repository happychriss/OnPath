package com.onpath.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.onpath.app.backup.Backups
import com.onpath.app.data.DataFiles
import com.onpath.app.data.Prefs
import com.onpath.app.data.Tour
import com.onpath.app.data.TourDb
import com.onpath.app.data.TourKind
import com.onpath.app.debug.Problems
import com.onpath.app.debug.launchSafe
import com.onpath.app.gpx.GpxParser
import com.onpath.app.gpx.GpxWriter
import com.onpath.app.gpx.SportType
import com.onpath.app.recording.RecState
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.strings.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the imports / exports / backups read from and write to the view model (phase 2, piece 7). */
interface ImportExportHost {
    val recState: StateFlow<RecState?>
    val dataFiles: DataFiles
    val prefs: Prefs
    val cells: com.onpath.app.cells.MapCells
    val sync: com.onpath.app.plansync.PlanSync
    val events: MutableList<UiEvent>
    val activity: Activity
    val showSettings: Boolean
    var dataBusy: String?
    var restoreProgress: String?
    var restoreOffer: Boolean
    var restartForData: Boolean
    var styleVersion: Int
    var busy: Busy?
    var sac: Int
    var routesOpen: Boolean
    var savedPlaces: com.onpath.app.search.SavedPlaces?
    fun recheckData()
    fun packageNote(): String
    fun say(text: String, kind: NoticeKind, place: NoticePlace)
    fun sayInfo(text: String, place: NoticePlace)
    fun sayError(text: String, place: NoticePlace)
    fun refreshTours()
    fun planFactors(a: Activity): com.onpath.app.time.Factors
    fun defaultName(prefix: String): String
    fun selectActivity(a: Activity, userChange: Boolean)
    fun showTrack(s: ShownTrack)
}

/**
 * Import and export (C7 GPX import / export, C11 backup / restore): GPX from a `Uri`, the map-data zip, the GPX / ZIP
 * share sheet, "Restore from folder", the first-start restore offer. Stays in `:app` (`Uri`, `Intent`, `FileProvider`, the
 * content resolver); its pure decisions are [ImportRules] in `:state`, the codecs and the backup files are in `:core` /
 * [Backups]. Moved out of `AppViewModel` as they were (piece 7 of the rebuild); the view model's same-name members forward
 * here, its state (`dataBusy`, `restoreProgress`, `restoreOffer`, `styleVersion`) stays there.
 */
class ImportExport(
    private val app: Application,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val db: TourDb,
    private val host: ImportExportHost,
) {
    /** Imports the onpath-data zip or a single data/style file picked by the user. */
    fun importData(uri: Uri) {
        if (host.dataBusy != null) return
        host.dataBusy = Txt.s(Res.string.msg_import_busy)
        scope.launchSafe("app", "importData", io) {
            val msg: Pair<String, Boolean> = try {
                val name = displayName(uri) ?: "import.zip"
                val input = app.contentResolver.openInputStream(uri) ?: error("Cannot open the file.")
                val r = com.onpath.app.data.DataImporter.import(input, name, host.dataFiles.dir) { f ->
                    host.dataBusy = Txt.s(Res.string.msg_import_busy_file, f)
                }
                // MapLibre keeps an opened .pmtiles file's index in memory: a replaced file would be read with the
                // old index (gaps, wrong tiles) until the process restarts. So after new .pmtiles: restart the app
                // (not during a tour – then the user is asked to restart after it).
                val newTiles = r.imported.any { it.endsWith(".pmtiles") }
                // downloaded map cells are joined with the region package: join the new one
                if (newTiles || r.imported.contains("pois.geojson")) host.cells.regionChanged()
                withContext(Dispatchers.Main) {
                    host.recheckData()
                    if (newTiles && host.recState.value == null) host.restartForData = true else host.styleVersion++
                }
                ImportRules.dataImportMessage(r.summary, { host.packageNote() }, newTiles, { host.recState.value != null },
                    { Txt.s(Res.string.msg_import_restart_after_tour) }) to false
            } catch (e: com.onpath.app.data.DataImporter.ImportException) {
                Problems.warn("data", "Map data import rejected", e)
                com.onpath.app.debug.UserText.of(e, Txt.s(Res.string.msg_import_failed_unchanged)) to true
            } catch (e: Exception) {
                Problems.error("data", "Map data import failed; existing data kept", e)
                Txt.s(Res.string.msg_import_failed_damaged) to true
            }
            withContext(Dispatchers.Main) {
                host.dataBusy = null
                host.say(msg.first, if (msg.second) NoticeKind.ERROR else NoticeKind.INFO, NoticePlace.SETTINGS)
            }
        }
    }

    private fun displayName(uri: Uri): String? = try {
        app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) {
        Problems.warn("data", "File name of the picked file unreadable; using the URI name", e)
        null
    } ?: uri.lastPathSegment?.substringAfterLast('/')

    /** "Export all": one ZIP with every tour and plan as GPX, handed to the share sheet (Files, Drive, mail …). */
    fun exportAll() {
        host.busy = Busy.EXPORT
        scope.launchSafe("app", "exportAll") {
            try {
                val uri = withContext(io) {
                    val f = Backups.exportZip(app, File(app.cacheDir, "export"), SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date()))
                    FileProvider.getUriForFile(app, app.packageName + ".files", f) to f.name
                }
                host.busy = null
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri.first)
                    putExtra(Intent.EXTRA_SUBJECT, uri.second)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                host.events += UiEvent.Share(Intent.createChooser(send, Txt.s(Res.string.msg_export_all_chooser)))
            } catch (e: com.onpath.app.backup.BackupException) {
                Problems.info("backup", "Export all: ${e.reason}", e); host.busy = null; host.sayError(Txt.s(Res.string.msg_export_reason, e.reason), NoticePlace.ROUTES)
            } catch (e: Exception) {
                Problems.error("backup", "Export all failed", e)
                host.busy = null
                host.sayError(Txt.s(Res.string.msg_export_failed), NoticePlace.ROUTES)
            }
        }
    }

    fun checkRestoreOffer() {
        scope.launchSafe("app", "checkRestoreOffer", io) {
            val offer = try {
                RestoreOfferRule.offer(Backups.restoreOffered(app), db.countAll(), com.onpath.app.backup.BackupFolder.documentsOnPathExists())
            } catch (e: Exception) {
                Problems.warn("backup", "Restore offer not checked", e); false
            }
            if (offer) withContext(Dispatchers.Main) { host.restoreOffer = true }
        }
    }

    /** The folder the user confirmed in the picker: import every OnPath backup in it (off the main thread). */
    fun restoreFrom(tree: Uri) {
        if (host.restoreProgress != null) return
        host.restoreProgress = Txt.s(Res.string.msg_restore_looking)
        scope.launchSafe("app", "restoreFrom") {
            val r = withContext(io) {
                try { Backups.restore(app, tree) { line -> scope.launchSafe("app", "restoreFrom", Dispatchers.Main) { if (host.restoreProgress != null) host.restoreProgress = line } } }
                catch (e: Exception) { Problems.error("backup", "Restore from the backup folder failed", e); null }
            }
            host.restoreProgress = null
            host.savedPlaces = null // a restore may have brought kept places (Zuhause, favourites): read them again
            if (r != null) host.sync.changed("restored from the backup folder")
            // the result where it was started (Settings → Backup), else where the tracks are now: My routes (restore
            // offered at the first start); a failure there → the error box
            if (r != null) host.sayInfo(com.onpath.app.backup.BackupRestore.summary(r),
                if (host.showSettings) NoticePlace.SETTINGS else { host.routesOpen = true; NoticePlace.ROUTES })
            else host.sayError(Txt.s(Res.string.msg_restore_folder_unreadable), if (host.showSettings) NoticePlace.SETTINGS else NoticePlace.MAP)
            host.refreshTours()
        }
    }

    fun exportTour(t: Tour) {
        scope.launchSafe("app", "exportTour") {
            // as chosen at End tour: rides not counted are their own segments, a dropped ride is not exported
            val segs = withContext(io) { com.onpath.app.rides.RideView.layout(db.loadSegments(t.id), t.rides).trk.map { it.first } }
            share(t.name) {
                // a planned route: the one plan export shared with the web planner (gpx/PlanGpx) – its route points
                // as <wpt> (start, stops, goal – with their names): other apps show them, a re-import rebuilds the plan
                if (t.kind == TourKind.PLANNED) com.onpath.app.gpx.PlanGpx.export(t.name, segs,
                    com.onpath.app.gpx.PlanGpx.withPins(t.waypoints, t.pinned), t.activity, t.sac)
                // the sport as `<trk><type>` (Komoot's string, so Komoot and OnPath read the activity back);
                // the file name the same in every language (owner): "Hilden - rec.gpx"
                else com.onpath.app.gpx.GpxFile(GpxWriter.fileName(t.name, GpxWriter.kindSuffix(false, t.kind == TourKind.RECORDED)),
                    GpxWriter.write(t.name, segs, t.waypoints, type = SportType.typeOf(t.activity, t.sac)))
            }
        }
    }

    /** Writes the GPX [gpx] builds (off the main thread) into the export folder and opens the share sheet. */
    suspend fun share(name: String, gpx: () -> com.onpath.app.gpx.GpxFile) {
        try {
            val uri = withContext(io) {
                val dir = File(app.cacheDir, "export").apply { mkdirs() }
                val g = gpx()
                val file = File(dir, g.fileName)
                file.writeText(g.text)
                FileProvider.getUriForFile(app, app.packageName + ".files", file)
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            host.events += UiEvent.Share(Intent.createChooser(send, Txt.s(Res.string.common_export_gpx)))
        } catch (e: Exception) {
            Problems.error("gpx", "GPX export failed", e)
            host.sayError(Txt.s(Res.string.msg_export_failed), NoticePlace.ROUTES)
        }
    }

    // ---------------- GPX import ----------------

    fun importGpx(uri: Uri) {
        host.busy = Busy.IMPORT
        scope.launchSafe("app", "importGpx") {
            try {
                val (data, fileName) = withContext(io) {
                    val name = runCatching {
                        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst()) c.getString(0) else null
                        }
                    }.onFailure { Problems.warn("gpx", "GPX import: file name unreadable; using the GPX name", it) }.getOrNull()
                    val stream = app.contentResolver.openInputStream(uri) ?: error("Cannot open the file.")
                    stream.use { GpxParser.parse(it) } to name
                }
                val segs = data.allSegments
                // Komoot's sport in <trk><type> preselects the activity (owner); unknown → the current one;
                // the user's T level stays when it fits the type's band, and a change is said
                val choice = SportType.importChoice(data.type, host.activity, host.sac, touring = host.recState.value != null)
                val pick = choice.pick
                val name = ImportRules.gpxName(data.name, fileName) { host.defaultName(Txt.s(Res.string.msg_name_import)) }
                val geo = segs.map { s -> s.map { it.toGeo() } }
                // the file's own moving time, else the one time model with the pace level – the one import stats (app,
                // sync, web: TimeEstimate.trackStats)
                val st = com.onpath.app.time.TimeEstimate.trackStats(segs, pick.activity, host.planFactors(pick.activity))
                val id = withContext(io) {
                    db.insertTour(TourKind.IMPORTED, name, pick.activity.name, pick.sac, st.distanceM, st.ascentM, st.descentM,
                        st.durationS, segs, data.waypoints)
                }
                Backups.tourChanged(app, id)
                host.sync.changed("GPX imported")
                val pts = geo.flatten().ifEmpty { data.waypoints.map { GeoPoint(it.lat, it.lon) } }
                host.routesOpen = false // show the imported track on the map
                host.refreshTours()
                // like opening a recorded tour: its activity is chosen (not during a tour – the followed route keeps its own);
                // before the track is shown: an activity change clears the panel's line, this one sets it
                if (choice.switch) {
                    if (pick.activity == Activity.HIKE) { host.sac = pick.sac.coerceIn(1, 5); host.prefs.sac = host.sac }
                    host.selectActivity(pick.activity, userChange = false)
                }
                // something odd in the file: a grey line in the track panel, never a message (owner)
                host.showTrack(ShownTrack(id, name, geo, data.waypoints, st.distanceM, st.ascentM, st.descentM, st.durationS, note = choice.note,
                    skippedNote = data.skippedNote))
                if (geo.isEmpty()) host.events += UiEvent.Fit(pts)
                // Diagnostics: the type is the file's own text – cut short, and say when it was not one we know
                data.type?.let { t ->
                    val to = if (SportType.activityOf(t) == null) "unknown, kept ${pick.activity.name}"
                        else pick.activity.name + if (pick.activity == Activity.HIKE) " T${pick.sac}" else ""
                    Problems.info("gpx", "GPX import “$name”: type “${t.take(40)}” → $to")
                }
                // The track panel with its name is the feedback (+ its "parts skipped" line); the details go to Diagnostics
                host.busy = null
                if (data.warnings.isNotEmpty()) {
                    Problems.info("gpx", "GPX import “$name”: ${data.pointCount} points, ${data.waypoints.size} waypoints; " +
                        data.warnings.joinToString("; "))
                }
            } catch (e: Exception) {
                host.busy = null
                Problems.error("gpx", "GPX import failed", e)
                // GpxException: our own plain reason ("No track, route or waypoint found in this file.")
                val own = com.onpath.app.debug.UserText.of(e, "")
                // My routes when the import was started there; a file opened from another app → the error box
                host.sayError(ImportRules.gpxFailureText(own, { Txt.s(Res.string.msg_gpx_import_failed_reason, it) },
                    { Txt.s(Res.string.msg_gpx_import_failed) }), NoticePlace.ROUTES)
            }
        }
    }
}
