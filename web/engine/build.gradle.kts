plugins {
    kotlin("jvm") version "2.2.10"
    id("org.teavm") version "0.16.0"
}

val root = rootDir.resolve("../..")
val app = root.resolve("app/src/main/java/com/onpath/app")
val stubs = root.resolve("tools/routing/jvm-test/stubs")

// The app files the engine compiles as they are, and the app's own unit tests for them (they run on the plain JVM
// here, so web CI checks them too): ONE list for app and web, app/web-shared-files.txt ([files] / [tests]) – the app's
// SharedFilesRuleTest guards it on every gate (review Arch 5, owner 2026-11-01). web/parity/check.mjs reads it too.
fun sharedList(section: String): List<String> {
    var current = ""
    return root.resolve("app/web-shared-files.txt").readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
        .mapNotNull { l -> if (l.startsWith("[")) { current = l; null } else l.takeIf { current == "[$section]" } }
}
val appFiles = sharedList("files")
val appTests = sharedList("tests")

sourceSets {
    main {
        java.setSrcDirs(listOf("src/main/java", stubs, root.resolve("app/src/main/java/btools/router")))
        java.include("android/**", "org/**", "btools/**", "com/google/**", "OnPathHints.java", "onpathweb/**", "com/onpath/web/**")
        // our Context / AssetManager (src/main/java) add startActivity (poi/GoogleMapsLink.kt) and list (map/Overview.kt); the JVM test stubs step aside
        java.exclude { it.relativePath.pathString in setOf("android/content/Context.java", "android/content/res/AssetManager.java") &&
            !it.file.canonicalPath.startsWith(projectDir.canonicalPath) }
    }
}
kotlin {
    jvmToolchain(21)
}
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
tasks.withType<JavaCompile>().configureEach { options.release = 17 }
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }

dependencies {
    implementation(files(root.resolve("app/libs").listFiles { f -> f.name.endsWith(".jar") }!!.sorted()))
    implementation(teavm.libs.jsoApis)
    implementation("com.google.code.gson:gson:2.11.0") // the app gets it with MapLibre; search reads GeoJSON with it
    // TeaVM maps java.time to ThreeTen backport (its classlib's substitution); gpx/GpxParser reads GPX times with it
    implementation("org.threeten:threetenbp:1.6.9")
    implementation("org.json:json:20240303") // Android ships org.json; map/Overview.kt builds the style with it
    compileOnly("org.teavm:teavm-extension-spi:0.16.0") // XmlSubstitution (onpathweb SAX stand-ins for the Wasm build)
    testImplementation("junit:junit:4.13.2")
}

teavm {
    all {
        mainClass = "com.onpath.web.WebApiKt"
        // number formats for every text language (Txt.setLanguage → data/Format: "12,5 km" in German); TeaVM keeps en only by default
        properties.put("java.util.Locale.available", "en_US, de_DE")
    }
    js { addedToWebApp = false; moduleType = org.teavm.gradle.api.JSModuleType.ES2015 }
    wasmGC { addedToWebApp = false }
}

// The app's texts (main: `strings/` = Compose Multiplatform resources, docs/i18n.md) for the web: a generated stand-in
// for `com.onpath.strings` (Res.string.<key>, Res.plurals.<key>; Txt in src/main/kotlin) read from the same English
// XML files with XmlStrings' rules (text as written, only \n \t \uXXXX \\ special). Each accessor carries its text, so
// TeaVM keeps only the texts the shared code really uses. Every language: values/ = English, values-<lang>/ (a missing
// text falls back to English); Txt picks the language at run time (docs/design/web-planner.md §18).
val stringsRes =root.resolve("strings/src/commonMain/composeResources")
val stringsGen = layout.buildDirectory.dir("generated/strings")
val generateStrings by tasks.registering {
    inputs.dir(stringsRes)
    outputs.dir(stringsGen)
    doLast {
        val bs = '\\'
        // XmlStrings.unescape (Compose's handleSpecialCharacters), written with chars to keep the escaping readable
        fun unescape(s: String): String {
            val b = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == bs && i + 1 < s.length) {
                    val n = s[i + 1]
                    when {
                        n == bs -> { b.append(bs); i += 2; continue }
                        n == 'n' -> { b.append('\n'); i += 2; continue }
                        n == 't' -> { b.append('\t'); i += 2; continue }
                        n == 'u' && i + 6 <= s.length && s.substring(i + 2, i + 6).all { it.isLetterOrDigit() && it.lowercaseChar() in "0123456789abcdef" } -> {
                            b.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 6; continue
                        }
                    }
                }
                b.append(c); i++
            }
            return b.toString()
        }
        fun kt(s: String): String {
            val b = StringBuilder("\"")
            for (c in s) when (c) {
                bs -> b.append(bs).append(bs)
                '"' -> b.append(bs).append('"')
                '$' -> b.append(bs).append('$')
                '\n' -> b.append(bs).append('n')
                '\t' -> b.append(bs).append('t')
                else -> b.append(c)
            }
            return b.append('"').toString()
        }
        // languages: values/ = "en" first, then values-<lang>/ sorted (adding a language = adding its folder)
        val dirs = listOf("en" to stringsRes.resolve("values")) + stringsRes.listFiles { f -> f.isDirectory && f.name.startsWith("values-") }!!
            .sortedBy { it.name }.map { it.name.removePrefix("values-") to it }
        val tables = dirs.map { (_, dir) ->
            val strings = HashMap<String, String>()
            val plurals = HashMap<String, Pair<String?, String>>()
            dir.listFiles { f -> f.extension == "xml" }!!.sortedBy { it.name }.forEach { f ->
                val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f).documentElement
                val nodes = doc.childNodes
                for (i in 0 until nodes.length) {
                    val e = nodes.item(i) as? org.w3c.dom.Element ?: continue
                    val name = e.getAttribute("name")
                    when (e.tagName) {
                        "string" -> require(strings.put(name, unescape(e.textContent)) == null) { "duplicate '$name' in $f" }
                        "plurals" -> {
                            val items = e.getElementsByTagName("item")
                            val forms = (0 until items.length).map { items.item(it) as org.w3c.dom.Element }
                                .associate { it.getAttribute("quantity") to unescape(it.textContent) }
                            val other = forms["other"] ?: error("plural '$name' has no 'other' in $f")
                            require(plurals.put(name, forms["one"] to other) == null) { "duplicate '$name' in $f" }
                        }
                    }
                }
            }
            strings to plurals
        }
        fun arr(xs: List<String?>) = xs.joinToString(", ", "arrayOf(", ")") { it?.let { t -> kt(t) } ?: "null" }
        val out = StringBuilder("// generated by web/engine/build.gradle.kts (generateStrings) from strings/…/values* – do not edit\n")
        out.append("@file:Suppress(\"unused\")\npackage com.onpath.strings\n\n")
        out.append("import org.jetbrains.compose.resources.PluralStringResource\nimport org.jetbrains.compose.resources.StringResource\n\n")
        out.append("/** The text languages, in the order of every accessor's array (English first = the fallback). */\n")
        out.append("val LANGUAGES: Array<String> = ").append(arr(dirs.map { it.first })).append("\n\n")
        val (en, enPlurals) = tables[0]
        for (name in en.keys.sorted())
            out.append("val Res.string.$name: StringResource get() = StringResource(\"$name\", ${arr(tables.map { it.first[name] })})\n")
        for (name in enPlurals.keys.sorted())
            out.append("val Res.plurals.$name: PluralStringResource get() = PluralStringResource(\"$name\", " +
                "${arr(tables.map { it.second[name]?.first })}, ${arr(tables.map { it.second[name]?.second })})\n")
        val dir = stringsGen.get().asFile.resolve("com/onpath/strings").apply { mkdirs() }
        dir.resolve("ResStrings.kt").writeText(out.toString())
    }
}
kotlin.sourceSets["main"].kotlin.srcDir(generateStrings)

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") { source(appFiles.map { app.resolve(it) }) }
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    source(appTests.map { root.resolve("app/src/test/java/com/onpath/app").resolve(it) })
}
// app tests read the BRouter profiles from the app module's path (RouteWarningsTest: src/main/assets/brouter/*.brf);
// the engine's tests run in web/engine, so the profiles are copied there first (git-ignored, the app's files as they are)
val appProfiles = tasks.register<Sync>("appProfiles") {
    from(root.resolve("app/src/main/assets/brouter")) { include("*.brf") }
    into(layout.projectDirectory.dir("src/main/assets/brouter"))
}
tasks.named<Test>("test") { dependsOn(appProfiles) }

// Our few web replacements (src/main/java: btools.util.StackSampler, TeaVM's java.lang.Package, ReentrantLock) must
// win over the BRouter jars and TeaVM's class library: project classes first on the TeaVM classpath.
tasks.withType<org.teavm.gradle.tasks.TeaVMTask>().configureEach {
    val rest = files(classpath.from.toList()) // keeps the task dependencies (resolving to files would drop them)
    classpath.setFrom(sourceSets["main"].output, rest)
}

// JVM side of the browser comparison (web/test/compare.mjs): same Engine code, plain JVM.
val jvm by sourceSets.creating {
    compileClasspath += sourceSets["main"].output + configurations["runtimeClasspath"]
    runtimeClasspath += output + compileClasspath
}
tasks.register<JavaExec>("runJvm") {
    classpath = jvm.runtimeClasspath
    mainClass = "com.onpath.web.JvmRunKt"
    systemProperty("onpath.root", root.canonicalPath)
    args((project.findProperty("cases") as String?) ?: "../test/cases.json",
        (project.findProperty("segments") as String?) ?: "/home/user/build/routing-test/segments",
        (project.findProperty("out") as String?) ?: "build/jvm-results.json")
}
// App side of the plan parity test (web/test/plans.json → build/parity-app.json; web/app/test/plan-parity.mjs runs the
// same cases in the Wasm engine and compares): the app's PlanResult / PlanPoints / BRouterEngine only, no web code.
tasks.register<JavaExec>("parityJvm") {
    classpath = jvm.runtimeClasspath
    mainClass = "com.onpath.web.ParityRunKt"
    systemProperty("onpath.root", root.canonicalPath)
    args((project.findProperty("plans") as String?) ?: "../test/plans.json",
        (project.findProperty("segments") as String?) ?: "/home/user/build/routing-test/segments",
        (project.findProperty("out") as String?) ?: "build/parity-app.json")
}
// The app's style with the world overview merged in (map/Overview.kt, the app's own code, run here at build time;
// docs/design/web-planner.md §16): the page loads it when the overview files are deployed next to it.
tasks.register<JavaExec>("overviewStyle") {
    classpath = jvm.runtimeClasspath
    mainClass = "com.onpath.web.OverviewStyleKt"
    args(root.resolve("app/src/main/assets/style/f2/onpath.json").path, layout.buildDirectory.file("style/onpath-overview.json").get().asFile.path)
}
// A small rd5 with only some parts (previews / tests): -Ptile=E5_N50.rd5 -Pdir=<head/tail/part files> -Pout=<file>
tasks.register<JavaExec>("partialRd5") {
    classpath = jvm.runtimeClasspath
    mainClass = "com.onpath.web.PartialRd5Kt"
    args(project.findProperty("tile") as String? ?: "", project.findProperty("dir") as String? ?: "",
        project.findProperty("out") as String? ?: "")
}
