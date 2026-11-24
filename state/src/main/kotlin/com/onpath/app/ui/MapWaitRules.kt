package com.onpath.app.ui

import com.onpath.app.cells.CellId
import com.onpath.app.cells.CellState
import com.onpath.app.domain.LoadStep
import com.onpath.app.domain.TimeLeft
import com.onpath.app.domain.WaitStep
import com.onpath.app.domain.WaitSteps
import com.onpath.app.ui.progress.WaitSign
import com.onpath.app.ui.progress.WaitWindowUi
import com.onpath.strings.*

/**
 * The wait window while the user waits for the map of a place (form A, owner 2026-11-19 „ich muss warten“): the first map
 * of their area, a point set / searched / tapped where there is no detailed map, a route computed while the visible map
 * has none ([MapLoadWait]), routing data. One frame: "Karte für deine Gegend", ONE bar 0–100 over all steps with its
 * number – each step its learned share ([StepShares]), never back, never standing ([OneBar]) –, at most 3 step rows
 * „vorbereitet · lädt Karte + Routendaten · einfügen“ (missing routing data is part of the loading step, never a 4th
 * row), the time left once it can be estimated, Cancel. Closes by itself only when everything is there; Cancel stops it
 * ([MapLoadCancel]). Background loads (the map for here when the app opens, route corridor, on tour, newer versions)
 * show the sign beside the map instead (form B).
 */
data class MapLoadUi(
    /** What happens: the running step (PREPARING / LOADING / FINISHING) or why it waits / failed. */
    val kind: Kind,
    /** 0..100 over all steps ([OneBar]): never back, never standing, 100 only when everything is there. */
    val percent: Int,
    /** Estimated seconds left; null = not estimable yet (no line). */
    val secondsLeft: Int?,
    /** Failure: plain reason. */
    val reason: String? = null,
    val retryable: Boolean = false,
    /** How many regions (map cells – never called that on screen) the window waits for; 0 = only routing data. */
    val regions: Int = 1,
    val bytes: Long? = null,
    /** Routing data loads too: part of the loading step („lädt Karte + Routendaten“). */
    val routing: Boolean = false,
    /** Of [regions]: already on the phone. */
    val regionsDone: Int = 0,
    /** The step the wait is in when it pauses (offline, Wi-Fi) – its rows stay where they were; null = by [kind]. */
    val at: LoadStep? = null,
    /** The running step is past its own estimate ([OneBar.late]): "dauert etwas länger…" instead of a frozen number. */
    val late: Boolean = false,
) {
    /** FINISHING: everything is downloaded and is joined into the map / written into the routing file. */
    enum class Kind { PREPARING, LOADING, FINISHING, OFFLINE, WIFI, FAILED }

    val title: String get() = if (kind == Kind.FAILED) Txt.s(Res.string.maps_load_failed) else Txt.s(Res.string.wait_title_map)

    /** Offline / waiting for Wi-Fi: the bar stands grey and the line says why. */
    val paused: Boolean get() = kind == Kind.OFFLINE || kind == Kind.WIFI

    /** The running step (the step rows' ● and where the bar's part ends). */
    val step: LoadStep get() = at ?: when (kind) {
        Kind.PREPARING -> LoadStep.PREPARE
        Kind.FINISHING -> LoadStep.JOIN
        else -> LoadStep.DOWNLOAD
    }

    /** The line under the bar: time left, or why it waits / what failed. */
    val line: String? get() = when (kind) {
        Kind.OFFLINE -> OFFLINE_TEXT
        Kind.WIFI -> WIFI_TEXT
        Kind.FAILED -> reason
        else -> if (late) TimeLeft.LATE else secondsLeft?.let(TimeLeft::text)
    }

    /**
     * The step rows: „vorbereitet · lädt Karte + Routendaten · einfügen“ (routing data only: „lädt Routendaten ·
     * einfügen“) – never more than 3 ([WaitSteps]).
     */
    val steps: List<WaitStep> get() {
        val load = when {
            regions == 0 -> Txt.s(Res.string.wait_step_loading_routing)
            routing -> Txt.s(Res.string.wait_step_loading_map_routing)
            else -> Txt.s(Res.string.wait_step_loading_map)
        }
        val now = step
        fun label(s: LoadStep): Pair<String, String?> = when (s) {
            LoadStep.PREPARE -> (if (now == s) LoadPhase.PREPARING.text else if (now > s) Txt.s(Res.string.wait_step_prepared)
                else Txt.s(Res.string.wait_step_prepare)) to null
            LoadStep.DOWNLOAD -> if (now == s) load to LoadPhase.LOADING.text
                else (if (now > s) Txt.s(Res.string.wait_step_loaded) else Txt.s(Res.string.wait_step_load)) to null
            LoadStep.JOIN -> (if (now == s) LoadPhase.FINISHING.text else Txt.s(Res.string.wait_step_join)) to null
        }
        val all = if (regions == 0) listOf(LoadStep.DOWNLOAD, LoadStep.JOIN) else LoadStep.entries
        return WaitSteps.of(all.map(::label), all.indexOf(now))
    }

    /** The window's look ([WaitWindowView]). */
    val window: WaitWindowUi get() = WaitWindowUi(
        when (kind) { Kind.FAILED -> WaitSign.FAILED; Kind.OFFLINE -> WaitSign.OFFLINE; else -> WaitSign.MAP },
        title, percent, if (kind == Kind.FAILED) emptyList() else steps, line, paused = paused, bar = kind != Kind.FAILED)

    companion object {
        val OFFLINE_TEXT: String get() = Txt.s(Res.string.maps_offline_text)
        val WIFI_TEXT: String get() = Txt.s(Res.string.maps_wifi_text)
        /** One-time load over mobile data while "Maps only on Wi-Fi" waits (window and question box; setting unchanged). */
        val LOAD_ON_MOBILE: String get() = Txt.s(Res.string.maps_load_on_mobile)
    }
}

/** Sizes of map regions for the question before a tap's download, and for the windows. Pure. */
object MapLoads {
    /** "2 regions · ≈ 18 MB" (the tap question). */
    fun askText(regions: Int, bytes: Long): String = Txt.p(Res.plurals.maps_ask_text, regions, regions, mb(bytes))

    /** Size of a region when the server has not said it yet. */
    const val ESTIMATED_REGION_BYTES = 9L * 1_048_576

    /** Known size of a region (installed, downloading, waiting for Wi-Fi), else null. */
    fun knownBytes(s: CellState?, installed: Long?): Long? = installed
        ?: (s as? CellState.Downloading)?.totalBytes?.takeIf { it > 0 }
        ?: (s as? CellState.WaitingForWifi)?.bytes

    /** Total size: known sizes, [ESTIMATED_REGION_BYTES] for the rest. */
    fun estimate(cells: Collection<CellId>, states: Map<CellId, CellState>, sizes: Map<CellId, Long>): Long =
        cells.sumOf { knownBytes(states[it], sizes[it]) ?: ESTIMATED_REGION_BYTES }
}

/**
 * When the user waits for a map job in the modal. Pure, unit-tested.
 *
 * Field bug 2026-11-08 (owner, fresh install): the location granted in the phone setup gave the first fix, the map for
 * here started loading (T3) and – the empty world map counting as "the view has no detailed map" – the modal opened
 * over the setup guide: the user had to wait inside the onboarding. The map for here (opening the app, the first start
 * too) loads in the background only; and no modal ever covers the phone setup.
 */
object MapLoadWait {
    /**
     * [job] opens / joins the modal: the user's own point / search / tap ([CellJob.own]), or a job loaded while the
     * visible map has no detailed map – but never the map for here ([CellWhy.HERE]: the user only opened the app).
     */
    fun waits(job: com.onpath.app.cells.CellJob, viewWithoutDetail: Boolean): Boolean =
        job.own != null || (viewWithoutDetail && job.why != com.onpath.app.cells.CellWhy.HERE)

    /** The modal to show: none while the phone setup (welcome page / guide) is open – the map loads on behind it. */
    fun shown(ui: MapLoadUi?, setupOpen: Boolean): MapLoadUi? = if (setupOpen) null else ui
}

/**
 * Modal → Cancel: **Cancel = stop** (owner). The regions the modal waited for stop loading (installed ones stay; the
 * server may finish its build – the next request is then quick), no card follows for that job, the searched place's
 * ring goes when a search / shared place started it and its map is still missing (map there = the ring stays), a point
 * the user set stays in the plan, the map stays where it is.
 * Setting a point or searching there again loads anew. Pure, unit-tested.
 */
object MapLoadCancel {
    data class Outcome(
        /** Regions to drop from the queue (not installed). */
        val stop: List<CellId>,
        /** Remove the candidate marker (searched / shared place). */
        val clearSearchMark: Boolean,
        /** The plan sheet's card for this job goes too (no "waiting for Wi-Fi" afterwards). */
        val closeCard: Boolean,
    )

    /**
     * [waitedFor]: the modal's regions; [job] / [jobWaited]: the card's job and whether the modal waited for it;
     * [installed]: regions on the phone; [bySearch]: a search / shared place opened (or joined) the modal.
     */
    fun decide(
        waitedFor: Set<CellId>, job: com.onpath.app.cells.CellJob?, jobWaited: Boolean, installed: Set<CellId>,
        bySearch: Boolean,
    ): Outcome {
        val closeCard = job != null && jobWaited
        val all = LinkedHashSet<CellId>(waitedFor)
        if (closeCard) all += job!!.cells
        // the ring goes only when Cancel really stops the searched place's map (field 2026-11-07, S10: the map for the
        // place was there, the window still showed – Cancel then took the destination too). The map is there / only
        // routing data was waited for: the found place stays until the user acts on it.
        val stopsItsMap = waitedFor.any { it !in installed }
        return Outcome(all.filter { it !in installed }, bySearch && stopsItsMap, closeCard)
    }
}
