plugins { kotlin("jvm") }
tasks.named<ProcessResources>("processResources") {
    from(rootProject.file("catalog/compiled/menu-adc.properties")) { into("META-INF/fuji") }
    from(rootProject.file("catalog/compiled/direction-input.properties")) { into("META-INF/fuji") }
}
