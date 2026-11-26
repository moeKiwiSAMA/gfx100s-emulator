package com.github.nyabanban.fuji.emulator.catalog

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.*

class DirectionInputExportTest {
    private val profile="gfx100s-2.13-direction-input-v1"
    private fun change(catalog:AddressCatalog,component:String,edit:(Map<String,Any?>)->Map<String,Any?>?)=AddressCatalog(catalog.data+
        ("devices" to rows(catalog.data["devices"]).map { d -> if(d["componentId"]!=component)d else d+("ports" to rows(d["ports"]).map { p ->
            p+("registers" to rows(p["registers"]).map { r -> r+("connections" to rows(r["connections"]).mapNotNull { b -> if(b["profile"]==profile)edit(b)else b }) })
        }) }))
    @Test fun generatedPairAndBoundsComeFromTheCatalogWhileLegacyExportsStayIndependent() {
        val catalog=CatalogCompiler.compile(Path.of(System.getProperty("catalogRoot")))
        val out=Files.createTempDirectory("direction-export")
        try {
            CatalogCompiler.generateDirectionInput(catalog,out)
            val p=Properties().apply { Files.newInputStream(out.resolve("direction-input.properties")).use { load(it) } }
            assertEquals(14,p.size);assertEquals("9",p.getProperty("adcChannel"));assertEquals("7",p.getProperty("gpioBank"));assertEquals("9",p.getProperty("gpioPin"))
            assertEquals("1,1930,2219",p.getProperty("up"));assertEquals("8,3042,3719",p.getProperty("left"))
            CatalogCompiler.generateGpioButtons(catalog,out)
            assertEquals("profile=gfx100s-2.13-gpio-buttons-v2\nback=9,5,true\nplay=9,6,true\nshutterFull=0,1,true\nshutterHalf=0,0,true\n",Files.readString(out.resolve("gpio-buttons-v2.properties")))
            val relocated=change(catalog,"soc-gpio") { it+mapOf("bank" to 6,"pin" to 8) }
            CatalogCompiler.generateDirectionInput(relocated,out)
            val moved=Properties().apply { Files.newInputStream(out.resolve("direction-input.properties")).use { load(it) } }
            assertEquals("6",moved.getProperty("gpioBank"));assertEquals("8",moved.getProperty("gpioPin"))
            val absent=change(change(catalog,"soc-adc"){null},"soc-gpio"){null}
            CatalogCompiler.generateDirectionInput(absent,out);assertFalse(Files.exists(out.resolve("direction-input.properties")))
        } finally {out.toFile().deleteRecursively()}
    }
    @Test fun partialBindingsWrongChannelAndOverlappingOrIncompleteDirectionsCannotPublish() {
        val catalog=CatalogCompiler.compile(Path.of(System.getProperty("catalogRoot")))
        val out=Files.createTempDirectory("direction-export-invalid")
        try {
            val bad=listOf(
                change(catalog,"soc-adc"){null},change(catalog,"soc-gpio"){null},
                change(catalog,"soc-adc"){it+("channel" to 8)},change(catalog,"soc-gpio"){it+("bank" to 16)},
                change(catalog,"soc-adc"){it+("directions" to rows(it["directions"]).dropLast(1))},
                change(catalog,"soc-adc"){it+("directions" to rows(it["directions"]).map { d->if(d["key"]=="up")d+("maximum" to 2800)else d })},
                change(catalog,"soc-gpio"){it+("confidence" to "UNKNOWN")}
            )
            for(input in bad){assertFailsWith<IllegalArgumentException>{CatalogCompiler.generateDirectionInput(input,out)};assertFalse(Files.exists(out.resolve("direction-input.properties")))}
        } finally {out.toFile().deleteRecursively()}
    }
}
