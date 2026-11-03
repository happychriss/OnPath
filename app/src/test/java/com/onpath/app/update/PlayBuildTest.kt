package com.onpath.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A-003 – the Google Play build (build type "play"): no own updater (Play forbids an app that updates itself past the
 * store), no REQUEST_INSTALL_PACKAGES, no GitHub update row in Settings → Privacy. The GitHub build (debug, what
 * release.yml publishes as the APK) stays as it is. release.yml checks the merged manifest of the play build too.
 */
class PlayBuildTest {
    private fun src(p: String) = File(p).readText()
    private val gradle = src("build.gradle.kts")

    @Test fun theGithubBuildKeepsItsUpdater() {
        // the unit tests run the debug variant = the GitHub APK
        assertEquals(true, com.onpath.app.BuildConfig.SELF_UPDATE)
        assertTrue(gradle.contains("buildConfigField(\"boolean\", \"SELF_UPDATE\", \"true\")"))
        assertTrue(src("src/main/AndroidManifest.xml").contains("android.permission.REQUEST_INSTALL_PACKAGES"))
    }

    @Test fun thePlayBuildHasNoUpdaterAndNoInstallPermission() {
        val play = gradle.substringAfter("create(\"play\") {").substringBefore("\n        }\n")
        assertTrue(play, play.contains("buildConfigField(\"boolean\", \"SELF_UPDATE\", \"false\")"))
        assertTrue(play, play.contains("isDebuggable = false"))
        val manifest = src("src/play/AndroidManifest.xml")
        assertTrue(manifest, Regex("""android\.permission\.REQUEST_INSTALL_PACKAGES"\s+tools:node="remove"""").containsMatchIn(manifest))
    }

    @Test fun everyUpdaterPathAsksSelfUpdate() {
        // check, download, install and Settings → Updates all hang on updatesEnabled
        val updates = src("src/main/java/com/onpath/app/ui/AppUpdates.kt")
        assertTrue(updates.contains("com.onpath.app.BuildConfig.SELF_UPDATE && !com.onpath.app.update.AppUpdater.installedFromPlay(app)"))
        assertTrue(updates.contains("if (!updatesEnabled ||"))
        assertTrue(src("src/main/java/com/onpath/app/MainActivity.kt").contains("update = vm.update.takeIf { vm.updatesEnabled }"))
        // the privacy list names GitHub only where the updater exists
        val privacy = src("src/main/java/com/onpath/app/ui/SettingsScreen.kt").substringAfter("val PRIVACY_FLOWS")
        assertTrue(privacy.contains("\"GitHub\", Txt.s(Res.string.settings_privacy_update_when))\n        .takeIf { com.onpath.app.BuildConfig.SELF_UPDATE }"))
    }

    @Test fun theUploadKeyNeverLivesInTheRepository() {
        assertTrue(gradle.contains("\"ONPATH_UPLOAD_KEYSTORE\""))
        val tracked = File("..").walkTopDown().onEnter { it.name !in setOf("build", ".git", ".gradle", ".claude", "snapshots", "node_modules") }
            .filter { it.isFile && (it.extension == "jks" || it.extension == "keystore") }.map { it.name }.toList()
        assertEquals("only the test key", listOf("onpath-test.keystore"), tracked.distinct())
    }
}
