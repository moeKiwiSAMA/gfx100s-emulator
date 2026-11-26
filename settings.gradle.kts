pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { mavenCentral() } }
rootProject.name = "fuji-emulator-gfx100s"
include("component-api", "runtime", "cpu-dynarmic", "firmware", "desktop")
listOf("battery", "buttons", "display", "shoulder-display", "sdcard-reader", "lens", "mount", "cmos", "optical-filter", "shutter", "soc-gpio", "soc-timer", "soc-dma", "soc-configuration", "flash-image", "soc-flash", "soc-gic", "system-memory", "soc-keyed-io", "soc-system-control", "soc-method4", "soc-spi").forEach {
    include(":components:$it")
}
include(":components:power-peer")
include(":components:soc-adc")
include(":components:soc-i2c",":components:i2c-bus",":components:tmd2772",":components:mma8452q",":components:max17048",":components:wm8962",":components:rear-touch-ic")
include(":components:spi-input")
include(":components:soc-selection")

include(":components:rtc")

include(":components:temperature-sensor",":components:soc-temperature")

include(":components:soc-dram")

include(":components:soc-serial-engine")

include(":components:soc-sd-host")
include(":components:soc-sdio", ":components:bcm43438")


include("address-catalog")

include(":components:soc-vglib-init")
include(":components:soc-hdmi", ":components:soc-panel", ":components:soc-uart")
include(":components:soc-isp-cmd", ":components:soc-isp-setup", ":components:soc-isp")
include(":components:soc-jpeg")
listOf(
    "lens-gf45-100",
    "lens-gf20-35",
    "lens-gf32-64",
    "lens-gf63",
    "lens-gf110",
    "lens-gf50",
    "lens-canon-ef85-f14l",
    "lens-takumar-50-f14",
    "lens-zeiss-planar-80-f28",
    "adapter-fringer-ef-gfx",
    "adapter-m42-gfx",
    "adapter-hasselblad-v-gfx",
).forEach {
    include(":lenses:$it")
}
