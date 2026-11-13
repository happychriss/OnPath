package com.onpath.app.ui.routes

/**
 * SKETCH (owner 2026-11-13 „Sollte man in der Routenliste nicht auch anzeigen, wenn es eine Rundtour ist?“), not for
 * main as it is: set only by RoundTripSketchTest. NONE = the rows as they are. A = "· ↻ Rundtour" at the end of the
 * activity line; B = a small ↻ badge on the map tile's corner (where the ⤢ badge was) + "Rundtour" in the row's TalkBack
 * label. [ids] = the tours drawn as round trips (in the app: a planned route's points end at its start –
 * PlanEdits.isRoundTrip; a track / recorded tour: its line ends near its start – RouteNamer's loop rule).
 */
object RoundTripSketch {
    enum class Mode { NONE, A, B }
    var mode: Mode = Mode.NONE
    var ids: Set<Long> = emptySet()
    fun isLoop(id: Long) = mode != Mode.NONE && id in ids
}
