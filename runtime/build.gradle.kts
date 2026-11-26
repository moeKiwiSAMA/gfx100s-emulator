plugins { kotlin("jvm") }
dependencies {
    api(project(":component-api"))
    api(project(":address-catalog"))
    api("com.fasterxml.jackson.core:jackson-databind:2.20.1")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.20.1")
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("com.google.inject:guice:7.0.0")
}

tasks.register<JavaExec>("generateHttpApiDocs") {
    group = "documentation"
    description = "Generate deterministic OpenAPI 3.1.0, Markdown, and offline interactive HTML docs for ControlServer HTTP API under docs/api/."
    val mainSourceSet = project.extensions.getByType<org.gradle.api.plugins.JavaPluginExtension>().sourceSets.getByName("main")
    val outDir = rootProject.layout.projectDirectory.dir("docs/api")
    inputs.files(
        file("src/main/kotlin/com/github/nyabanban/fuji/emulator/runtime/ControlApiSpec.kt"),
        file("src/main/kotlin/com/github/nyabanban/fuji/emulator/runtime/ControlApiDocGenerator.kt"),
        file("src/main/kotlin/com/github/nyabanban/fuji/emulator/runtime/ControlServer.kt"),
    )
    outputs.files(
        outDir.file("http-openapi.json"),
        outDir.file("http-control-api.md"),
        outDir.file("http-control-api.html"),
    )
    classpath = mainSourceSet.runtimeClasspath
    mainClass.set("com.github.nyabanban.fuji.emulator.runtime.ControlApiDocGeneratorKt")
    doFirst {
        args(outDir.asFile.absolutePath)
    }
}

tasks.named<Test>("test") {
    dependsOn(":components:shoulder-display:jar", ":components:soc-serial-engine:jar")
    mustRunAfter("generateHttpApiDocs")
    maxHeapSize = "4g"
    systemProperty("componentProjectRoot", rootProject.projectDir.absolutePath)
}

tasks.register<Test>("quickGateRuntimeTest") {
    group = "verification"
    description = "Run core runtime quick-gate checks (RelaxedPeripheralFallbackTest & RuntimeTest)."
    val testSourceSet = project.extensions.getByType<org.gradle.api.plugins.JavaPluginExtension>().sourceSets.getByName("test")
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    dependsOn(":components:shoulder-display:jar", ":components:soc-serial-engine:jar")
    systemProperty("componentProjectRoot", rootProject.projectDir.absolutePath)
    filter {
        includeTestsMatching("com.github.nyabanban.fuji.emulator.runtime.RelaxedPeripheralFallbackTest")
        includeTestsMatching("com.github.nyabanban.fuji.emulator.runtime.UserActionLoggerTest")
        includeTestsMatching("com.github.nyabanban.fuji.emulator.runtime.FirmwareWatchdogTest")
        includeTestsMatching("com.github.nyabanban.fuji.emulator.runtime.AsyncPerfProfilerTest")
        includeTestsMatching("com.github.nyabanban.fuji.emulator.runtime.TelemetryHubClientTest")
        includeTestsMatching("com.github.nyabanban.fuji.emulator.runtime.RealTimePacerTest")
        includeTestsMatching("com.github.nyabanban.fuji.emulator.runtime.ControlApiDocGeneratorTest")
        includeTestsMatching("com.github.nyabanban.fuji.emulator.runtime.RuntimeTest")
    }
}
