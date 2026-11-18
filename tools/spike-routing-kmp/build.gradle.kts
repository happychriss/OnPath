// BRouter's routing core as pure Kotlin (commonMain), built for the JVM and for linuxX64 (Kotlin/Native).
plugins { kotlin("multiplatform") version "2.2.10" }

kotlin {
    jvmToolchain(21)
    jvm()
    linuxX64 {
        binaries { executable { entryPoint = "spike.main" } }
    }
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
}

// JVM run of the same main: ./gradlew -p tools/spike-routing-kmp runJvm --args="<segmentsDir> <profilesDir> <routes.txt> <outDir>"
tasks.register<JavaExec>("runJvm") {
    val main = kotlin.jvm().compilations["main"]
    dependsOn(main.compileTaskProvider)
    classpath(main.output.allOutputs, main.runtimeDependencyFiles)
    mainClass.set("spike.MainKt")
    jvmArgs("-Xmx192m")
}
// one fat-free jar dir for timing runs outside Gradle: build/jvmcp.txt holds the class path
tasks.register("jvmClasspath") {
    val main = kotlin.jvm().compilations["main"]
    dependsOn(main.compileTaskProvider)
    val out = layout.buildDirectory.file("jvmcp.txt")
    val cp = files(main.output.allOutputs, main.runtimeDependencyFiles)
    outputs.file(out)
    doLast { out.get().asFile.writeText(cp.asPath) }
}
