package com.onpath.app.ui.plan

import com.onpath.app.data.Prefs
import com.onpath.app.routing.GeoPoint

/** [PlanStore] in the app's preferences (`plan`, `planPinned`, `planSavedName` / `Id` / `Points` – [Prefs]). */
class PrefsPlanStore(private val prefs: Prefs) : PlanStore {
    override var points: List<GeoPoint>
        get() = prefs.planWaypoints
        set(v) { prefs.planWaypoints = v }
    override var pinned: Set<GeoPoint>
        get() = prefs.planPinned
        set(v) { prefs.planPinned = v }
    override var savedName: String?
        get() = prefs.planSavedName
        set(v) { prefs.planSavedName = v }
    override var savedId: Long
        get() = prefs.planSavedId
        set(v) { prefs.planSavedId = v }
    override var savedPoints: List<GeoPoint>?
        get() = prefs.planSavedPoints
        set(v) { prefs.planSavedPoints = v }
    override var anchorsJson: String?
        get() = prefs.planAnchors
        set(v) { prefs.planAnchors = v }
    override var savedAnchorsJson: String?
        get() = prefs.planSavedAnchors
        set(v) { prefs.planSavedAnchors = v }
}
