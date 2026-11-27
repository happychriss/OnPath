package com.onpath.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.onpath.app.data.Format
import com.onpath.app.data.Tour
import com.onpath.strings.*

// Moved out of Screens.kt unchanged (its size cap, FileSizeRuleTest).

/**
 * A recording was interrupted (app or phone restarted without the service): Delete · Continue · Save, like End tour.
 * Delete asks once more, then the tour goes to the recycle bin. Stays until answered.
 */
@Composable
fun UnfinishedDialog(t: Tour, onContinue: () -> Unit, onSave: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(str(Res.string.routes_unfinished_title)) },
        text = { DialogText(str(Res.string.routes_unfinished_text, Format.distance(t.distanceM), Format.duration(t.durationS))) },
        confirmButton = { DeleteApartButtons(onDelete, str(Res.string.routes_unfinished_continue), onContinue,
            str(Res.string.common_save), onSave) },
    )
}
