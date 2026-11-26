plugins { kotlin("jvm") }
tasks.named<ProcessResources>("processResources") {
    from(rootProject.file("catalog/compiled/power-button.properties")) { into("META-INF/fuji") }
}
