package com.github.nyabanban.fuji.emulator.catalog

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class MenuAdcExportTest {
    @Test fun absentMenuConnectionRemovesStaleOutputAndSelectedChannelMustMatchRegisterOffset() {
        val catalog=CatalogCompiler.compile(Path.of(System.getProperty("catalogRoot")))
        val output=Files.createTempDirectory("menu-adc-export")
        fun changed(connections: Any):AddressCatalog=AddressCatalog(catalog.data+("devices" to rows(catalog.data["devices"]).map{d->
            if(d["componentId"]!="soc-adc")d else d+("ports" to rows(d["ports"]).map{p->p+("registers" to rows(p["registers"]).map{r->
                if(r["id"]=="reg.soc-adc.main.o1c")r+("connections" to connections)else r
            })})
        }))
        try{
            CatalogCompiler.generateMenuAdc(catalog,output);assertTrue(Files.exists(output.resolve("menu-adc.properties")))
            assertFails{CatalogCompiler.generateMenuAdc(changed(listOf(mapOf("profile" to "gfx100s-2.13-menu-adc-v1","channel" to 4,"buttonKey" to "menu","confidence" to "SOURCE"))),output)}
            CatalogCompiler.generateMenuAdc(changed(emptyList<Any>()),output);assertFalse(Files.exists(output.resolve("menu-adc.properties")))
        }finally{output.toFile().deleteRecursively()}
    }
}
