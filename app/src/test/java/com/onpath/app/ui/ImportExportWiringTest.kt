package com.onpath.app.ui

import com.onpath.app.testkit.sourceFile
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 piece 7: GPX import / export and backup / restore moved from AppViewModel to the plain class [ImportExport] in
 * :app. What covers them today: the codecs and rules by GpxParserTest, PlanGpxTest, GpxWriterNameTest, SportTypeTest (:core),
 * BackupTest (backup files), DataImporterTest (map-data zip), ImportRulesTest (:state, the three decisions cut out), and the
 * restore path end to end by CoreBackupE2ETest. NO behaviour test exists for: importGpx's effects (insert, backup, sync,
 * activity switch, show), exportAll / exportTour / share, importData's restart-or-reload rule, the checkRestoreOffer condition
 * - these are held by the TEXT pins below only (what the view model hands in and forwards, each host adapter member, the
 * guards, stored places, thread hops and the order of effects).
 */
class ImportExportWiringTest {
    private val vm = sourceFile("ui/AppViewModel.kt").readText()
    private val ie = sourceFile("ui/ImportExport.kt").readText()
    private fun has(src: String, what: String, regex: Boolean = false) =
        assertTrue("not found: $what", if (regex) Regex(what).containsMatchIn(src) else src.contains(what))
    private fun body(src: String, start: String): String {
        val a = src.indexOf(start); assertTrue("not found: $start", a >= 0)
        return src.substring(a).substringBefore("\n    }\n")
    }

    @Test fun theViewModelBuildsTheHelperLazilyWithItsScopeIoAndStore() {
        has(vm, """val importExport: ImportExport by lazy \{\s+ImportExport\(app, viewModelScope, Dispatchers\.IO, db, object : ImportExportHost""", true)
    }

    @Test fun everyMemberIsForwardedUnderItsOldName() {
        // (piece 21: the one-line forwards `importData`, `checkRestoreOffer`, `exportAll`, `exportTour`, `importGpx`, `restoreFrom` are gone: the
        // screens call `vm.importExport.x`; what stays is the one that is not a plain forward, `share`, which `exportFollowedPlan` still calls)
        for (f in listOf(
            "private suspend fun share(name: String, gpx: () -> com.onpath.app.gpx.GpxFile) = importExport.share(name, gpx)")) has(vm, f)
    }

    @Test fun theHostAdapterForwardsEachMemberToItsOwn() {
        val a = vm.indexOf("object : ImportExportHost {"); assertTrue("adapter not found", a >= 0)
        val b = vm.substring(a, vm.indexOf("        })\n", a))
        for (l in listOf(
            "override val recState: StateFlow<RecState?> get() = this@AppViewModel.recState",
            "override val dataFiles: DataFiles get() = this@AppViewModel.dataFiles",
            "override val prefs: Prefs get() = this@AppViewModel.prefs",
            "override val cells: com.onpath.app.cells.MapCells get() = this@AppViewModel.cells",
            "override val sync: com.onpath.app.plansync.PlanSync get() = this@AppViewModel.sync",
            "override val events: MutableList<UiEvent> get() = this@AppViewModel.events",
            "override val activity: Activity get() = this@AppViewModel.planRoute.activity",
            "override val showSettings: Boolean get() = this@AppViewModel.settingsSlice.showSettings",
            "override fun recheckData() = this@AppViewModel.recheckData()",
            "override fun packageNote() = this@AppViewModel.packageNote()",
            "override fun say(text: String, kind: NoticeKind, place: NoticePlace) = this@AppViewModel.notices.say(text, kind, place)",
            "override fun sayInfo(text: String, place: NoticePlace) = this@AppViewModel.notices.sayInfo(text, place)",
            "override fun sayError(text: String, place: NoticePlace) = this@AppViewModel.notices.sayError(text, place)",
            "override fun refreshTours() = this@AppViewModel.routesFlow.refreshTours()",
            "override fun planFactors(a: Activity) = this@AppViewModel.planRoute.planFactors(a)",
            "override fun defaultName(prefix: String) = this@AppViewModel.defaultName(prefix)",
            "override fun selectActivity(a: Activity, userChange: Boolean) = this@AppViewModel.planRoute.selectActivity(a, userChange)",
            "override fun showTrack(s: ShownTrack) = this@AppViewModel.routesFlow.showTrack(s)")) has(b, l)
        for (v in listOf("dataBusy: String?", "restoreProgress: String?", "restoreOffer: Boolean", "restartForData: Boolean",
            "styleVersion: Int", "busy: Busy?", "sac: Int", "routesOpen: Boolean")) {
            val name = v.substringBefore(":")
            val target = mapOf("busy" to "notices.busy", "sac" to "planRoute.sac", "routesOpen" to "routes.open")[name] ?: name // the slice each one is read from (piece 21)
            has(b, "override var $v\n                get() = this@AppViewModel.$target\n                set(v) { this@AppViewModel.$target = v }")
        }
        // the kept places live in their slice (piece 12): a restore sets its cache to null through the host
        has(b, "override var savedPlaces: com.onpath.app.search.SavedPlaces?\n                get() = keptPlaces.savedPlaces\n                set(v) { keptPlaces.savedPlaces = v }")
    }

    @Test fun theBackupAndRestoreGuardsAndWritesAreAsTheyWere() {
        // a second restore / import while one runs is ignored; the progress line only while it runs
        has(body(ie, "fun restoreFrom("), "if (host.restoreProgress != null) return")
        has(body(ie, "fun importData("), "if (host.dataBusy != null) return")
        has(ie, "if (host.restoreProgress != null) host.restoreProgress = line")
        // a restore may have brought kept places (Zuhause, favourites): read again; sync told only on a result
        has(ie, "host.savedPlaces = null")
        has(ie, "if (r != null) host.sync.changed(\"restored from the backup folder\")")
        // the restore offer: once, only with an empty database
        has(ie, "!Backups.restoreOffered(app) && db.countAll() == 0")
        // Export all / GPX share: the cache folder, the file provider authority, the mime types
        has(ie, "File(app.cacheDir, \"export\")")
        has(ie, "app.packageName + \".files\"")
        has(ie, "type = \"application/zip\"")
        has(ie, "type = \"application/gpx+xml\"")
        // the busy marker is cleared on every way out of Export all and GPX import
        assertTrue(body(ie, "fun exportAll(").split("host.busy = null").size - 1 == 3)
        assertTrue(body(ie, "fun importGpx(").split("host.busy = null").size - 1 == 2)
        // an imported tour is backed up and synced; the file's own name needs no rename (owner: its track panel is the feedback)
        has(ie, "Backups.tourChanged(app, id)")
        has(ie, "host.sync.changed(\"GPX imported\")")
        has(ie, "// something odd in the file: a grey line in the track panel, never a message (owner)")
        // new .pmtiles: restart (never during a tour), else the style reloads
        has(ie, "if (newTiles && host.recState.value == null) host.restartForData = true else host.styleVersion++")
    }

    private fun inOrder(src: String, vararg parts: String) {
        var at = -1
        for (p in parts) {
            val i = src.indexOf(p, at + 1)
            assertTrue("not found after the previous step: $p", i > at)
            at = i
        }
    }

    @Test fun theStateWritesStayOnTheMainThreadAndTheShareGrantsRead() {
        has(body(ie, "fun importData("), "withContext(Dispatchers.Main) {\n                    host.recheckData()")
        has(body(ie, "fun importData("), "withContext(Dispatchers.Main) {\n                host.dataBusy = null")
        has(body(ie, "fun checkRestoreOffer("), "if (offer) withContext(Dispatchers.Main) { host.restoreOffer = true }")
        has(body(ie, "fun restoreFrom("), "scope.launchSafe(\"app\", \"restoreFrom\", Dispatchers.Main) { if (host.restoreProgress != null)")
        has(ie.substringAfter("suspend fun share("), "addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)")
        has(body(ie, "fun exportAll("), "addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)")
    }

    @Test fun theOrderOfEffectsIsAsItWas() {
        inOrder(body(ie, "fun importGpx("), "host.busy = Busy.IMPORT", "db.insertTour(", "Backups.tourChanged(app, id)",
            "host.sync.changed(\"GPX imported\")", "host.routesOpen = false", "host.refreshTours()", "host.selectActivity(",
            "host.showTrack(ShownTrack(", "host.busy = null")
        inOrder(body(ie, "fun restoreFrom("), "host.restoreProgress = Txt.s(Res.string.msg_restore_looking)", "Backups.restore(app, tree)",
            "host.restoreProgress = null", "host.savedPlaces = null", "host.sync.changed(\"restored from the backup folder\")",
            "host.sayInfo(", "host.sayError(", "host.refreshTours()")
        // the restore's change is told to the sync only with a result
        has(body(ie, "fun restoreFrom("), "if (r != null) host.sync.changed(")
    }
}
