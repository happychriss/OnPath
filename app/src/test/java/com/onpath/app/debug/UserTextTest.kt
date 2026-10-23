package com.onpath.app.debug

import com.onpath.app.backup.BackupException
import com.onpath.app.cells.CellApi
import com.onpath.app.cells.CellStore
import com.onpath.app.data.DataImporter
import com.onpath.app.gpx.GpxException
import com.onpath.app.routing.RoutingException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class UserTextTest {
    private val plain = "Export failed – the phone's storage may be full."

    @Test fun systemExceptionsGetThePlainSentence() {
        assertEquals(plain, UserText.of(IOException("write failed: ENOSPC (No space left on device)"), plain))
        assertEquals(plain, UserText.of(java.net.UnknownHostException("Unable to resolve host \"api.github.com\""), plain))
        assertEquals(plain, UserText.of(NullPointerException(), plain))
        assertEquals(plain, UserText.of(IllegalStateException("Cannot open the file."), plain))
    }

    @Test fun ourOwnExceptionsKeepTheirPlainText() {
        assertEquals("No route found near this point", UserText.of(RoutingException("No route found near this point"), plain))
        assertEquals("No track, route or waypoint found in this file.",
            UserText.of(GpxException("No track, route or waypoint found in this file."), plain))
        assertEquals("No map data found in “x.zip”.", UserText.of(DataImporter.ImportException("No map data found in “x.zip”."), plain))
        assertEquals("storage full", UserText.of(BackupException("storage full", IOException("ENOSPC")), plain))
        assertEquals("The map could not be saved.", UserText.of(CellStore.StoreException("The map could not be saved.", IOException("EIO")), plain))
        assertEquals("Map server not reachable – trying again.",
            UserText.of(CellApi.ApiException(CellApi.Failure.UNREACHABLE, "Map server not reachable – trying again.",
                cause = java.net.SocketTimeoutException("timeout")), plain))
        assertEquals("Download damaged (a) – loading again.", UserText.of(CellApi.ChecksumException("Download damaged (a) – loading again."), plain))
    }

    @Test fun anEmptyOwnTextFallsBackToThePlainSentence() {
        assertEquals(plain, UserText.of(RoutingException(""), plain))
        assertEquals(plain, UserText.of(BackupException(" "), plain))
    }
}
