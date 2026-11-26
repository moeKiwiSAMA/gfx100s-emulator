plugins { kotlin("jvm") }

dependencies {
    testImplementation(project(":components:power-peer"))
    testImplementation(project(":components:soc-spi"))
}
