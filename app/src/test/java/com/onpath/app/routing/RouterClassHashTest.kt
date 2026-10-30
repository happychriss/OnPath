package com.onpath.app.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URLClassLoader

/**
 * The app's profile hash must be the one the cell builder writes into the manifest (`wayclass`, from
 * tools/routing/wayclass/WayClass.java) – otherwise every cell looks built with other profiles.
 */
class RouterClassHashTest {
    @get:Rule val tmp = TemporaryFolder()
    private val assets = File("src/main/assets/brouter")
    private val classifier = File("../tools/routing/wayclass/WayClass.java")

    private fun appHash() = RouterClassHash.of { File(assets, it).readBytes() }

    /** VERSION, GOOD and the hashed files follow WayClass.java – change them together with the classifier. */
    @Test fun constantsFollowTheClassifier() {
        val src = classifier.readText()
        val version = Regex("""static final String VERSION = "([^"]+)";""").find(src)!!.groupValues[1]
        assertEquals("WayClass.VERSION changed: update RouterClassHash.VERSION", version, RouterClassHash.VERSION)
        val good = Regex("""static final float\[] GOOD = \{([^}]+)};""").find(src)!!.groupValues[1]
            .split(",").map { it.trim().removeSuffix("f").toFloat() }.toFloatArray()
        assertEquals("WayClass.GOOD changed: update RouterClassHash.GOOD", good.contentToString(), RouterClassHash.GOOD)
        val hashBody = src.substringAfter("static String hash(").substringBefore("return ")
        val files = Regex("""new String\[]\{([^}]+)}""").find(hashBody)!!.groupValues[1].split(",").map { it.trim().trim('"') }
        assertEquals("WayClass hashes other files: update RouterClassHash.FILES", files, RouterClassHash.FILES)
        // the files the router installs are the files the classes are made from
        assertEquals(BRouterEngine.PROFILE_ASSETS.toSet(), RouterClassHash.FILES.toSet())
    }

    /** A copy of the shipped profiles in a temp folder, [edit] applied to one file's text (ISO-8859-1, byte for byte). */
    private fun copy(file: String? = null, edit: (String) -> String = { it }): File {
        val dir = tmp.newFolder()
        for (n in RouterClassHash.FILES) {
            val b = File(assets, n).readBytes()
            File(dir, n).writeBytes(if (n == file) edit(String(b, Charsets.ISO_8859_1)).toByteArray(Charsets.ISO_8859_1) else b)
        }
        return dir
    }

    private fun hashOf(dir: File) = RouterClassHash.of { File(dir, it).readBytes() }

    /** Comment-only and blank-line edits (owner: those must not ask for new maps). */
    private val commentOnly = listOf<Pair<String, (String) -> String>>(
        "mtb.brf" to { t -> "# a new header comment\n\n" + t.replace("\n", "   \r\n") },
        "touring.brf" to { t -> t.replaceFirst(Regex("#[^\n]*"), "# reworded comment") },
        "trekking.brf" to { t -> t.replaceFirst("\n", "   # trailing comment\n\n\n") },
        "lookups.dat" to { t -> "# lookups comment\n\t\n" + t },
    )
    /** Real changes: a value, a token joined with a '#' (not a comment for BRouter), a lookups line. */
    private val real = listOf<Pair<String, (String) -> String>>(
        "mtb.brf" to { t -> t + "\nassign extra = 1\n" },
        "hiking-mountain.brf" to { t -> t.replaceFirst(Regex("consider_noise( +)= false"), "consider_noise$1= true") },
        "touring.brf" to { t -> t + "\nassign x#y = 1\n" },
        "lookups.dat" to { t -> t + "\nhighway;0000000001 newvalue\n" },
    )

    @Test fun hashOfTheShippedProfiles() {
        val h = appHash()
        assertTrue(h, Regex("^2-[0-9a-f]{16}$").matches(h))
        assertTrue(RouterClassHash.isComparable(h))
        assertEquals(h, appHash())
        for ((f, e) in commentOnly) assertEquals("comment-only edit of $f", h, hashOf(copy(f, e)))
        for ((f, e) in real) assertNotEquals("real edit of $f", h, hashOf(copy(f, e)))
        // format 1 (16 hex digits) and garbage are not comparable
        assertTrue(!RouterClassHash.isComparable("704c9982b445dbe2") && !RouterClassHash.isComparable(null) &&
            !RouterClassHash.isComparable("2-xyz"))
    }

    @Test fun normalizeKeepsWhatBRouterReads() {
        fun n(name: String, text: String) =
            String(RouterClassHash.normalize(name, text.toByteArray(Charsets.ISO_8859_1)), Charsets.ISO_8859_1)
        assertEquals("assign a = 1\nassign b=#x\n", n("p.brf", "# head\n\n  assign   a\t= 1   # why\r\nassign b=#x # c\n"))
        // a noStartWay comment is read by BRouter (check_start_way): kept
        assertEquals("assign a = 1 # noStartWay | x\n", n("p.brf", "assign a = 1 # noStartWay | x\n"))
        // lookups.dat: only whole-line comments
        assertEquals("highway;0001 path #x\n", n("lookups.dat", "# c\n  highway;0001   path #x\n\n"))
    }

    /**
     * The real classifier's hash() over the same files: WayClass.java compiled here with the JDK that runs the tests
     * (javax.tools by reflection – the unit tests compile against android.jar); skipped without a JDK compiler. Also
     * over the edited copies: both sides agree on what counts.
     */
    @Test fun sameHashAsTheClassifier() {
        val javac = try {
            Class.forName("javax.tools.ToolProvider").getMethod("getSystemJavaCompiler").invoke(null)
        } catch (e: ClassNotFoundException) { null } // expected: a JRE without javax.tools
        assumeNotNull(javac)
        // the app part may be merged before the classifier's format-2 hash (WayClass.normalize): until then the app
        // compares nothing (every cell's hash is format 1 = unknown), so there is nothing to agree on yet
        assumeTrue("WayClass.java still writes format-1 hashes", classifier.readText().contains("static byte[] normalize("))
        val out = tmp.newFolder("classes")
        val cp = System.getProperty("java.class.path")
        val run = Class.forName("javax.tools.Tool").getMethod("run", java.io.InputStream::class.java,
            java.io.OutputStream::class.java, java.io.OutputStream::class.java, Array<String>::class.java)
        val err = java.io.ByteArrayOutputStream()
        val args = arrayOf("-nowarn", "-proc:none", "-cp", cp, "-d", out.path, classifier.path)
        val rc = run.invoke(javac, null, null, err, args) as Int
        assertEquals("WayClass.java does not compile on the test classpath: $err", 0, rc)
        URLClassLoader(arrayOf(out.toURI().toURL()), javaClass.classLoader).use { cl ->
            val m = cl.loadClass("WayClass").getDeclaredMethod("hash", File::class.java).apply { isAccessible = true }
            assertEquals(m.invoke(null, assets) as String, appHash())
            for ((f, e) in commentOnly + real) {
                val dir = copy(f, e)
                assertEquals("WayClass vs app on an edited $f", m.invoke(null, dir) as String, hashOf(dir))
            }
        }
    }
}
