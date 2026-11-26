plugins { kotlin("jvm") }

tasks.named<Test>("test") {
    rootProject.subprojects
        .filter { it.path.startsWith(":lenses:") }
        .forEach { dependsOn(it.tasks.named("jar")) }
}
