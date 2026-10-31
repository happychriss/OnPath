package com.onpath.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Settings → About → Licences (A-020): every library the build bundles has a licence on the page, and every licence
 * text the page offers is in the APK's assets. The list is the one the build writes (Gradle task licenceDepsDebug,
 * passed in as -Donpath.licenceDeps) – a new dependency without a rule in [Licences.RULES] fails here.
 */
class LicencesTest {

    private val assets = File("src/main/assets")

    private fun resolved(): List<String> {
        val path = System.getProperty("onpath.licenceDeps")
        assertNotNull("run through Gradle (testDebugUnitTest): the build's library list is missing", path)
        val f = File(path!!)
        assertTrue("library list not written: $f", f.isFile)
        return Licences.parse(f.readText())
    }

    @Test fun everyBundledLibraryHasALicence() {
        val libs = resolved()
        assertTrue("suspiciously few libraries: $libs", libs.size > 20)
        val missing = libs.filter { Licences.projectOf(it) == null }
        assertEquals("libraries without a licence rule in data/Licences.kt – check their licence, add a rule", emptyList<String>(), missing)
        // the main ones are really there
        for (p in listOf(Licences.MAPLIBRE, Licences.ANDROIDX, Licences.KOTLIN))
            assertTrue("${p.name} not in the build", libs.any { Licences.projectOf(it) == p })
    }

    @Test fun everyLicenceTextIsInTheAssets() {
        val projects = Licences.RULES.map { it.second } + Licences.BUNDLED.map { it.project }
        for (p in projects) {
            val t = p.text ?: continue
            assertTrue("${p.name}: licence text $t missing in app/src/main/assets", File(assets, t).isFile)
        }
        // MIT / BSD need their own copyright lines: those projects bring their own file
        for (p in projects.filter { it.licence == Licences.Licence.MIT || it.licence == Licences.Licence.BSD_2 })
            assertNotNull("${p.name}: MIT / BSD need the project's own licence file", p.textAsset)
    }

    @Test fun brouterVersionMatchesTheJars() {
        val jars = File("libs").listFiles().orEmpty().map { it.name }.filter { it.startsWith("brouter-") && it.endsWith(".jar") }
        assertEquals(5, jars.size)
        assertTrue("app/libs: $jars – update Licences.BROUTER_VERSION", jars.all { it.endsWith("-${Licences.BROUTER_VERSION}.jar") })
    }

    @Test fun pageOrderMapLibreFirstThenBundledThenLibraries() {
        val e = Licences.entries(listOf("androidx.core:core:1.15.0", "org.maplibre.gl:android-sdk:13.7.1", "foo.bar:baz:1.0"))
        assertEquals(Licences.MAPLIBRE, e[0].project)
        assertEquals(listOf("android-sdk 13.7.1"), e[0].libraries)
        assertEquals(Licences.BROUTER, e[1].project)
        assertTrue(e.any { it.project == Licences.ANDROIDX && it.libraries == listOf("core 1.15.0") })
        // a library without a rule is still listed, by its group, marked "not checked"
        assertEquals("foo.bar", e.last().project.name)
        assertEquals("Licence not checked yet", e.last().project.note)
    }
}
