package com.onpath.app.ui

/**
 * Plan in front (owner 2026-11-06: "Ich bin in Hilden und habe eine Route in Köln geplant – im Planmodus steht mein
 * Standort, wo ist mein Plan? Mit einem Plan sollte immer der Plan im Vordergrund stehen; der Standort nur ohne Plan.").
 *
 * At these moments Plan shows the WHOLE plan (the fit of "Show whole route": [WholeRoute.bounds], north up, the
 * [FitPaddingRule] padding); one point: centred at [ONE_POINT_ZOOM]. Without a plan nothing changes (the position, as
 * [StartCamera] does). Never against the user: a finger on the map since the moment was asked keeps the map where it is.
 */
object PlanCamera {
    const val ONE_POINT_ZOOM = 14.0

    enum class Moment {
        /** The app starts in Plan ([StartCamera.Step.SHOW_PLAN]). */
        APP_START,
        /** Back in the app after a longer time away (the same 10 min as [AppViewModel.onAppStart]). */
        BACK_IN_APP,
        /** Navigate → Plan, also during a tour (the slider's "Plan"). */
        FROM_NAVIGATE,
        /** The tour ended: the plan stays on the map (plan and tour are two things) and shows. */
        TOUR_END,
    }

    enum class Show { WHOLE, ONE_POINT, NOTHING }

    /** What the camera does at a [Moment] with [planPoints] plan points; [touchedSince] = a finger on the map meanwhile. */
    fun show(planPoints: Int, touchedSince: Boolean, trackShown: Boolean): Show = when {
        touchedSince || trackShown -> Show.NOTHING
        planPoints >= 2 -> Show.WHOLE
        planPoints == 1 -> Show.ONE_POINT
        else -> Show.NOTHING
    }
}
