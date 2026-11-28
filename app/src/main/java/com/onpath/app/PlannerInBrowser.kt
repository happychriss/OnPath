package com.onpath.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.onpath.app.debug.Problems

/**
 * A planner link OnPath can't use (the planner page itself, a code it can't read – routing/PlanLinks.toBrowser) opened in
 * the phone's browser, never in OnPath again (no loop: the browser's package is set, OnPath is never asked). No browser:
 * nothing happens, a line in Problems (owner's Pixel 2026-11-28: the planner page opened OnPath with an error).
 */
object PlannerInBrowser {
    /** True when [found] for the opened ([viewed]) [uri] goes to the browser ([PlanLinks.toBrowser]) – then it is sent there. */
    fun takes(context: Context, viewed: Boolean, uri: Uri?, found: com.onpath.app.routing.PlanLinks.Found?): Boolean {
        val via = if (viewed) com.onpath.app.routing.PlanLinks.Via.OPENED else null
        if (!com.onpath.app.routing.PlanLinks.toBrowser(via, uri?.toString(), found)) return false
        open(context, uri)
        return true
    }

    fun open(context: Context, uri: Uri?) {
        if (uri == null) return
        try {
            val pm = context.packageManager
            // the phone's browser: who opens a plain web page (example.org) – not a page this app claims
            val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/")).addCategory(Intent.CATEGORY_BROWSABLE)
            val browser = pm.queryIntentActivities(probe, 0).map { it.activityInfo.packageName }
                .firstOrNull { it != context.packageName }
            if (browser == null) { Problems.warn("plan", "Planner link not opened: no browser on the phone"); return }
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(browser)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Problems.trace("plan", "Planner link without a plan: opened in the browser ($browser)")
        } catch (e: Exception) {
            Problems.warn("plan", "Planner link not opened in the browser", e)
        }
    }
}
