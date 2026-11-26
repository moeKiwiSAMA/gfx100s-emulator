plugins { kotlin("jvm") }

tasks.test {
    dependsOn(tasks.jar)
    systemProperty("fuji.sdcard.testJar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
}
