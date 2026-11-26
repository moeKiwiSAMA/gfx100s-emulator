plugins { kotlin("jvm") }
tasks.named<ProcessResources>("processResources") {
    from(rootProject.file("catalog/compiled/gpio-buttons.properties")) { into("META-INF/fuji") }
    from(rootProject.file("catalog/compiled/gpio-buttons-v2.properties")) { into("META-INF/fuji") }
    from(rootProject.file("catalog/compiled/gpio-buttons-v3.properties")) { into("META-INF/fuji") }
    from(rootProject.file("catalog/compiled/gpio-buttons-v4.properties")) { into("META-INF/fuji") }
    from(rootProject.file("catalog/compiled/gpio-buttons-v5.properties")) { into("META-INF/fuji") }
    from(rootProject.file("catalog/compiled/gpio-buttons-v6.properties")) { into("META-INF/fuji") }
    from(rootProject.file("catalog/compiled/direction-input.properties")) { into("META-INF/fuji") }
}
