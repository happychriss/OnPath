package com.onpath.app.debug

import com.onpath.app.backup.BackupException
import com.onpath.app.cells.CellApi
import com.onpath.app.cells.CellStore
import com.onpath.app.data.DataImporter
import com.onpath.app.gpx.GpxException
import com.onpath.app.routing.RoutingException

/**
 * The sentence a user sees for an exception (owner 2026-10-23: no technical error texts in the app).
 *
 * Our own exception types carry plain texts we wrote ourselves – those are shown. Any other exception (system,
 * library, "ENOSPC", "Unable to resolve host…", class names) gets [plain]. The technical details go to
 * `Problems.warn/error(area, message, e)` at the call site, never into the UI. UserTextRuleTest guards the rule.
 */
object UserText {
    fun of(e: Throwable, plain: String): String {
        val own = when (e) {
            is RoutingException, is GpxException, is DataImporter.ImportException,
            is CellStore.StoreException, is CellApi.ApiException, is CellApi.ChecksumException -> e.message
            is BackupException -> e.reason
            else -> null
        }
        return own?.takeIf { it.isNotBlank() } ?: plain
    }
}
