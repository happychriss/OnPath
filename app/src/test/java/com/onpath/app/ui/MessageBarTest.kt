package com.onpath.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Review 2026-10-24 B10 / C9: an error in the top bar must not look like "Copied."; progress is a spinner where it started. */
class MessageBarTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1024, screenHeight = 1200, xdpi = 428, ydpi = 428, density = Density.create(420)),
        showSystemUi = false,
    )

    @Test fun errorsAreRecognised() {
        listOf(
            "The track cannot be saved any more (phone storage full or damaged). Free up space – recording keeps trying.",
            "Export failed – the phone's storage may be full. Your tracks and routes are unchanged.",
            "Couldn't find this place. Try again when you have internet.",
            "Diagnostics could not be created – the phone's storage may be full. Please try again.",
            "Backup to Documents/OnPath failed – no space",
            "Recording did not start. Allow precise location and notifications for OnPath, then try again.",
        ).forEach { assertTrue(it, MessageKind.isError(it)) }
        listOf("Copied.", "Track saved to My routes.", "Imported “Algund” – some parts were skipped.",
            "The route already ends at the start.", "Route updated with newer map data – please check it.",
        ).forEach { assertFalse(it, MessageKind.isError(it)) }
    }

    @Test fun infoAndErrorBars() = paparazzi.snapshot {
        OnPathTheme {
            Column(Modifier.background(Color(0xFFE8EEDC)).fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MessageBar("Track saved to My routes.", onOk = {})
                MessageBar("The track cannot be saved any more (phone storage full or damaged). Free up space – recording keeps trying.", onOk = {})
                OutlinedButton(onClick = {}) { BusyContent(Busy.DIAGNOSTICS.text) }
            }
        }
    }
}
