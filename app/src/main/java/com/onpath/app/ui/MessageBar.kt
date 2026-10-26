package com.onpath.app.ui

import com.onpath.strings.*
import org.jetbrains.compose.resources.StringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Work the user started that takes a moment (review 2026-10-24 C9): it shows as a spinner where it was started (the
 * button / the My routes row), not in the message bar. The bar only gets the result when it is not visible, or an error.
 */
enum class Busy(private val res: StringResource?, private val english: String = "") {
    IMPORT(Res.string.plan_busy_import),
    EXPORT(Res.string.plan_busy_export),
    /** Diagnostics stays English (owner 2026-10-26: not translated). */
    DIAGNOSTICS(null, "Creating the report…"),
    BACKUP(Res.string.plan_busy_backup);

    val text: String get() = res?.let { Txt.s(it) } ?: english
}

/** Which look a message of the top bar gets (review 2026-10-24 B10): an error must not look like "Copied.". Pure. */
object MessageKind {
    private val error = Regex(
        "\\b(failed|could not|couldn't|cannot|can't|not possible|went wrong|stopped unexpectedly|did not start|" +
            "no app found|no file picker|not be saved|not be read|not be deleted|not be changed|not be removed|" +
            // German (EN + DE, owner 2026-10-26): the same failures in the words the German texts use
            "fehlgeschlagen|konnte nicht|konnten nicht|kann nicht|können nicht|nicht möglich|schiefgegangen|" +
            "unerwartet beendet|nicht gestartet|keine App gefunden|keine Dateiauswahl|nicht gespeichert|nicht gelesen|" +
            "nicht gelöscht|nicht geändert|nicht entfernt)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun isError(text: String): Boolean = error.containsMatchIn(text)
}

/**
 * The general message bar at the top: stays until OK (owner rule: nothing disappears by itself). Info keeps the dark
 * bar; an error gets the ⚠ icon and a red left edge (ERROR look).
 */
@Composable
fun MessageBar(text: String, onOk: () -> Unit, modifier: Modifier = Modifier, error: Boolean = MessageKind.isError(text)) {
    val err = error // EN + DE: the view model says whether it is an error (AppViewModel.showError)
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface, shadowElevation = 6.dp, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            if (err) Box(Modifier.width(6.dp).fillMaxHeight().background(OnPathColors.Error))
            Row(Modifier.weight(1f).heightIn(min = 48.dp).padding(start = if (err) 10.dp else 16.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (err) {
                    Icon(Icons.Outlined.Warning, str(Res.string.plan_error), tint = ERROR_ON_DARK, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Text(text, style = MaterialTheme.typography.bodyLarge)
            }
            TextButton(onClick = onOk, modifier = Modifier.heightIn(min = 48.dp).padding(end = 4.dp)) {
                Text(str(Res.string.common_ok), color = MaterialTheme.colorScheme.inversePrimary)
            }
        }
    }
}

/** The error red, light enough to read on the dark bar. */
private val ERROR_ON_DARK = Color(0xFFFF8A80)

/** A spinner + the busy text, for a button or row that started the work. */
@Composable
fun BusyContent(text: String) {
    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    Spacer(Modifier.width(10.dp))
    Text(text)
}

/** The body of a dialog: Body size (16 sp) – Material uses 14 there (type scale, review 2026-10-24 A). */
@Composable
fun DialogText(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge)
