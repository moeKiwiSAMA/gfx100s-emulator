import java.io.File
import java.util.Locale

plugins { kotlin("jvm") }
dependencies { implementation(project(":component-api")) }

fun isWindowsHost(): Boolean = System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")

fun Task.declareDynarmicInputs(windows: Boolean) {
    val bridge = rootProject.layout.projectDirectory.dir("tools/native/dynarmic-bridge")
    val manifest = bridge.file("bridge-inputs.txt")
    inputs.files(project.providers.fileContents(manifest).asText.map { text ->
        text.lineSequence().filter { it.isNotEmpty() }.map { bridge.file(it) }.toList()
    }).withPathSensitivity(PathSensitivity.RELATIVE)
    if (windows) inputs.file(bridge.file("mingw64-dynarmic-toolchain.cmake"))
}

tasks.register("buildDynarmicNative") {
    declareDynarmicInputs(isWindowsHost())
    group = "build setup"
    description = "Build pinned ARMv7-A Dynarmic JIT native bridge (libfuji_dynarmic.so on Linux, fuji_dynarmic.dll on Windows)"
    doLast {
        val script = rootProject.file("tools/native/dynarmic-bridge/build_dynarmic.sh")
        check(script.isFile) { "Missing Dynarmic build script: ${script.path}" }
        val target = if (isWindowsHost()) "win64" else "linux"
        val process = ProcessBuilder("bash", script.absolutePath, target)
            .directory(rootProject.projectDir)
            .inheritIO()
            .start()
        check(process.waitFor() == 0) { "Dynarmic native build failed (target=$target)" }
    }
}

tasks.register("buildDynarmicNativeWindowsCross") {
    declareDynarmicInputs(true)
    group = "build setup"
    description = "Cross-compile Windows x86_64 Dynarmic JIT bridge DLL (fuji_dynarmic.dll) on Linux using MinGW-w64"
    doLast {
        require(!isWindowsHost()) { "Use :cpu-dynarmic:buildDynarmicNative directly on Windows hosts" }
        val script = rootProject.file("tools/native/dynarmic-bridge/build_dynarmic.sh")
        check(script.isFile) { "Missing Dynarmic build script: ${script.path}" }
        val process = ProcessBuilder("bash", script.absolutePath, "win64")
            .directory(rootProject.projectDir)
            .inheritIO()
            .start()
        check(process.waitFor() == 0) { "Dynarmic Windows x86_64 cross-build failed" }
    }
}

tasks.register("buildDynarmicNativeWin64") {
    group = "build setup"
    description = "Build Windows x86_64 Dynarmic JIT bridge DLL (native on Windows, MinGW-w64 cross-compile on Linux)"
    dependsOn(if (isWindowsHost()) "buildDynarmicNative" else "buildDynarmicNativeWindowsCross")
}

tasks.named<Test>("test") {
    if (System.getenv("FUJI_DYNARMIC_LIBRARY").isNullOrBlank()) {
        val dynCandidates = listOfNotNull(
            rootProject.file(".tools/dynarmic-v1/build-linux/libfuji_dynarmic.so"),
            rootProject.file(".tools/dynarmic-v1/build-win64/fuji_dynarmic.dll"),
            rootProject.file(".tools/dynarmic-v1/build/libfuji_dynarmic.so"),
            rootProject.file(".tools/dynarmic-v1/build/fuji_dynarmic.dll"),
            rootProject.file(".tools/dynarmic-v1/build/Release/fuji_dynarmic.dll"),
            File("/opt/fuji-cache/dynarmic-v1/libfuji_dynarmic.so"),
        )
        dynCandidates.firstOrNull { it.isFile }?.let { environment("FUJI_DYNARMIC_LIBRARY", it.absolutePath) }
    }
    if (System.getenv("FUJI_WIN64_DYNARMIC_DLL").isNullOrBlank()) {
        val winDynCandidates = listOf(
            rootProject.file(".tools/dynarmic-v1/build-win64/fuji_dynarmic.dll"),
            rootProject.file(".tools/dynarmic-v1-win64/build/fuji_dynarmic.dll"),
            File("/opt/fuji-cache/dynarmic-v1-win64/fuji_dynarmic.dll"),
        )
        winDynCandidates.firstOrNull { it.isFile }?.let { environment("FUJI_WIN64_DYNARMIC_DLL", it.absolutePath) }
    }
}
