plugins { kotlin("jvm"); application }
val os = System.getProperty("os.name").lowercase()
val arm = System.getProperty("os.arch") in listOf("aarch64", "arm64")
val fxPlatform = when { os.contains("win") && !arm -> "win"; os.contains("mac") -> if (arm) "mac-aarch64" else "mac"; os.contains("linux") && !arm -> "linux"; else -> error("JavaFX 25.0.1 desktop artifacts support Windows/Linux x64 and macOS x64/arm64; this platform needs a separately qualified JavaFX build") }
dependencies {
    implementation(project(":component-api")); implementation(project(":runtime")); implementation(project(":firmware")); implementation(project(":cpu-dynarmic"))
    listOf("base", "graphics", "controls", "swing").forEach { implementation("org.openjfx:javafx-$it:25.0.1:$fxPlatform") }
    implementation("org.slf4j:slf4j-api:2.0.17")
}
application {
    mainClass.set("com.github.nyabanban.fuji.emulator.desktop.MainKt")
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED",
        "-Xms512m",
        "-Xmx2g",
        "-XX:+UseG1GC",
        "-XX:MaxGCPauseMillis=15",
    )
}
val toolBootstrapDirectory = layout.buildDirectory.dir("classes/toolBootstrap")
val compileToolBootstrap = tasks.register<JavaCompile>("compileToolBootstrap") {
    description = "Compile the dependency-free Java 17 CLI bootstrap used by routing tests."
    source(rootProject.file("tools/EmulatorTools.java"))
    classpath = files()
    destinationDirectory.set(toolBootstrapDirectory)
    options.release.set(17)
    options.encoding = "UTF-8"
}
tasks.named<Test>("test") {
    dependsOn("releaseJar")
    maxHeapSize = "2g"
    dependsOn(compileToolBootstrap)
    inputs.dir(toolBootstrapDirectory).withPropertyName("toolBootstrapClasses").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("toolBootstrapClasses", toolBootstrapDirectory.get().asFile.absolutePath)
    // Native/API tests load staged independent JARs and actual CLI classes; always use this build.
    dependsOn(":stageRuntime")
    dependsOn(rootProject.subprojects.filter { it.path.startsWith(":components:") }.map { "${it.path}:jar" })
    // Reflection loads these JARs from the prepared runtime, outside the test classpath.
    // dependsOn alone does not invalidate Test when a hardware binary changes.
    inputs.dir(rootProject.layout.buildDirectory.dir("emulator/compoments")).withPropertyName("hardwareRuntime").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.layout.projectDirectory.dir("configurations/jvm")).withPropertyName("firmwareConfigurations").withPathSensitivity(PathSensitivity.RELATIVE)
    rootProject.layout.projectDirectory.dir("firmware-data/gfx100s/2.13").takeIf { it.asFile.isDirectory }?.let { inputs.dir(it).withPropertyName("firmwareBytes").withPathSensitivity(PathSensitivity.RELATIVE) }
    inputs.property("nativeHost",System.getProperty("os.name")+"/"+System.getProperty("os.arch"))
    val dynarmicLibFile = providers.environmentVariable("FUJI_DYNARMIC_LIBRARY").orNull?.let { file(it) }
        ?: listOf(
            rootProject.file(if (os.contains("win")) ".tools/dynarmic-v1/build-win64/fuji_dynarmic.dll" else ".tools/dynarmic-v1/build-linux/libfuji_dynarmic.so"),
            rootProject.file(if (os.contains("win")) ".tools/dynarmic-v1/build/fuji_dynarmic.dll" else ".tools/dynarmic-v1/build/libfuji_dynarmic.so"),
        ).firstOrNull { it.isFile }
    if (dynarmicLibFile != null && dynarmicLibFile.isFile) {
        environment("FUJI_DYNARMIC_LIBRARY", dynarmicLibFile.absolutePath)
        inputs.file(dynarmicLibFile).withPropertyName("selectedDynarmicLibrary").withPathSensitivity(PathSensitivity.NONE)
    }
    providers.gradleProperty("discoveryStudyOutput").orNull?.let { systemProperty("discoveryStudyOutput",it) }
    listOf(
        "fuji.e2e.strict",
        "fuji.e2e.strict.milestones",
        "fuji.e2e.requireDisplay",
        "fuji.e2e.requireAssets",
        "fuji.e2e.formatButtonEvents",
        "fuji.e2e.formatBudget",
        "fuji.e2e.formatDoneAttempts",
        "fuji.perf.runProfile",
        "fuji.smp.host.threads",
        "junit.jupiter.tags",
    ).forEach { propertyName ->
        providers.systemProperty(propertyName).orNull?.let { systemProperty(propertyName, it) }
    }
    providers.gradleProperty("fujiE2eTags").orNull?.let { systemProperty("junit.jupiter.tags", it) }
    val tagFilter = providers.systemProperty("junit.jupiter.tags").orNull
        ?: providers.gradleProperty("fujiE2eTags").orNull
    if (!tagFilter.isNullOrBlank()) {
        useJUnitPlatform {
            includeTags(*tagFilter.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray())
        }
    }
    systemProperty("componentProjectRoot", rootProject.projectDir.absolutePath)
}

tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        listOf(unixScript,windowsScript).forEach { file ->
            file.writeText(file.readText(Charsets.UTF_8).removePrefix("\uFEFF").replace("\r\n","\n").replace('\r','\n').trimEnd('\n') + "\n",Charsets.UTF_8)
        }
    }
}

val cameraRasterAssets = listOf("panels/gfx100s/**/*.png", "themes/body/**/*.png")
val artworkJar = tasks.register<Jar>("artworkJar") {
    group = "distribution"
    description = "Package camera body textures and artwork for the desktop application."
    archiveFileName.set("camera-artwork-${project.version}.jar")
    destinationDirectory.set(layout.buildDirectory.dir("release-libs"))
    from(tasks.processResources) { include(cameraRasterAssets) }
}

val releaseJar = tasks.register<Jar>("releaseJar") {
    group = "distribution"
    description = "Build end-user Release desktop JAR."
    val mainOutput = project.extensions.getByType<org.gradle.api.plugins.JavaPluginExtension>().sourceSets.getByName("main").output
    archiveFileName.set("desktop-${project.version}.jar")
    destinationDirectory.set(layout.buildDirectory.dir("release-libs"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(artworkJar)
    manifest.attributes("Class-Path" to artworkJar.get().archiveFileName.get())
    from(mainOutput) { exclude(cameraRasterAssets) }
}

tasks.register<Test>("quickGateHygieneTest") {
    group = "verification"
    description = "Run desktop f10T1* and catalog/text-policy sanity checks for quickGate."
    val testSourceSet = project.extensions.getByType<org.gradle.api.plugins.JavaPluginExtension>().sourceSets.getByName("test")
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    maxHeapSize = "2g"
    dependsOn(compileToolBootstrap, ":stageRuntime")
    dependsOn(rootProject.subprojects.filter { it.path.startsWith(":components:") }.map { "${it.path}:jar" })
    systemProperty("toolBootstrapClasses", toolBootstrapDirectory.get().asFile.absolutePath)
    systemProperty("componentProjectRoot", rootProject.projectDir.absolutePath)
    filter {
        includeTestsMatching("*f10T1*")
        includeTestsMatching("*f9T1_catalogCompilerVerifyAndProjectTextPolicyPass*")
    }
}
