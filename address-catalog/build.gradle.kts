plugins { kotlin("jvm") }
dependencies {
    api("com.fasterxml.jackson.core:jackson-databind:2.20.1")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.20.1")
}
val generatedCatalog = layout.buildDirectory.dir("generated/resources")
sourceSets.main { resources.srcDir(generatedCatalog) }
val compileCatalog by tasks.registering(JavaExec::class) {
    dependsOn(tasks.named("compileKotlin"))
    classpath = files(sourceSets.main.get().output.classesDirs, configurations.runtimeClasspath)
    mainClass.set("com.github.nyabanban.fuji.emulator.catalog.CatalogCompilerKt")
    args(rootProject.projectDir.absolutePath, generatedCatalog.get().asFile.absolutePath)
    inputs.files(rootProject.fileTree("catalog") { include("**/*.yaml", "schema/*.schema.json") })
    outputs.file(rootProject.file("configurations/jvm/firmware/gfx100s-213.yaml"))
    outputs.dir(generatedCatalog)
    outputs.dir(rootProject.file("catalog/compiled"))
    outputs.dir(rootProject.file("docs/generated"))
    outputs.files(rootProject.file("catalog/soc/xp4/soc.json"), rootProject.file("catalog/bodies/gfx100s/body.json"), rootProject.file("catalog/firmware/gfx100s/2.13/firmware.json"))
}
tasks.named("processResources") { dependsOn(compileCatalog) }
tasks.withType<Test>().configureEach {
    systemProperty("catalogRoot", rootProject.projectDir.absolutePath)
}
val verifyCatalog by tasks.registering(JavaExec::class) {
    dependsOn(tasks.named("compileKotlin"))
    classpath = files(sourceSets.main.get().output.classesDirs, configurations.runtimeClasspath)
    mainClass.set("com.github.nyabanban.fuji.emulator.catalog.CatalogCompilerKt")
    args(rootProject.projectDir.absolutePath, "--verify")
}
tasks.register<JavaExec>("verifyPassiveInventoryAddition") {
    dependsOn(compileCatalog)
    classpath = files(sourceSets.main.get().output.classesDirs, configurations.runtimeClasspath)
    mainClass.set("com.github.nyabanban.fuji.emulator.catalog.PassiveInventoryQualification")
    doFirst {
        args(rootProject.projectDir.absolutePath,
            providers.gradleProperty("baselineCatalogCommit").get(),
            providers.gradleProperty("addedInventoryId").get())
    }
}
