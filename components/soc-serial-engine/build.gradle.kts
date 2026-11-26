plugins { kotlin("jvm") }
dependencies { testImplementation(project(":runtime")) }
tasks.named<Test>("test") { dependsOn(tasks.named("jar")); systemProperty("componentProjectRoot", rootProject.projectDir.absolutePath) }
