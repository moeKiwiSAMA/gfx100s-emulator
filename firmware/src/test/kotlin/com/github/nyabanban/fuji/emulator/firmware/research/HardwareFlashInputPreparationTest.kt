package com.github.nyabanban.fuji.emulator.firmware.research

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class HardwareFlashInputPreparationTest {
    private val p=HardwareFlashInputPreparation
    private fun config():Map<String,Any> = mapOf("tailProfile" to "dat-end-block-ff-experimental-v1","regionsProfile" to "explicit-readonly-regions-experimental-v1",
        "regionsFile" to "regions.json","mutationProfile" to "explicit-disjoint-copy-on-write-program-experimental-v3","mutationFile" to "mutable.json")
    private fun regions():Map<String,Any> = mapOf("schema" to 1,"profile" to "explicit-readonly-regions-experimental-v1",
        "regions" to p.domains.map {mapOf("id" to it.id,"offset" to it.offset,"length" to it.length,"file" to it.file,"sha256" to "0".repeat(64))})
    private fun mutable():Map<String,Any> = mapOf("schema" to 1,"profile" to "explicit-disjoint-copy-on-write-program-experimental-v3","primaryRegionId" to p.domains[1].id,
        "regions" to listOf(Triple(p.domains[1].id,0x3dc0000L,0x80000L),Triple(p.domains[2].id,0x3f00000L,0x40000L)).mapIndexed {i,d->
            mapOf("schema" to 1,"profile" to "explicit-copy-on-write-program-experimental-v2","regionId" to d.first,"file" to "copy-"+i+".img","mode" to "create",
                "sha256" to "0".repeat(64),"writableOffset" to d.second,"writableLength" to d.third,"programLaw" to "strict-one-to-zero-no-page-wrap-experimental-v1","programPageBytes" to 256)})
    @Test fun selectedDomainsKeepSeparateReadAndWriteGeometryAndNoCapacityInference() {
        p.verifyBaseline(config(),regions(),mutable())
        assertEquals(listOf(0x3500000L,0x3d40000L,0x3f00000L,0x3f40000L),p.domains.map {it.offset})
        assertEquals(listOf(0x3d40000L,0x3e40000L,0x3f40000L,0x3f80000L),p.domains.map {it.offset+it.length})
        assertEquals(10223616L,p.domains.sumOf {it.length})
    }
    @Test fun expandedMovedDuplicatedOrWrongWidthReadDomainsCannotBecomeInputs() {
        val data=regions();val rows=data["regions"] as List<*>;val first=rows[0] as Map<*,*>
        for(change in listOf(first+mapOf("offset" to 0x34fffff),first+mapOf("length" to 0x840001),first+mapOf("length" to 8650752.0),first+mapOf("extra" to 0))) {
            assertFailsWith<IllegalArgumentException> {p.verifyBaseline(config(),data+("regions" to (listOf(change)+rows.drop(1))),mutable())}
        }
        assertFailsWith<IllegalArgumentException> {p.verifyBaseline(config(),data+("regions" to (rows+first)),mutable())}
        assertFailsWith<IllegalArgumentException> {p.verifyBaseline(config()+("tailProfile" to "strict"),data,mutable())}
    }
    @Test fun writableExpansionModeChangeAndLaterDomainErrorsRefuseBeforeOutput(@TempDir root:Path) {
        val old=mutable();val rows=old["regions"] as List<*>;val second=rows[1] as Map<*,*>
        for(change in listOf(second+mapOf("writableLength" to 0x80000),second+mapOf("writableOffset" to 0x3e40000),second+mapOf("mode" to "resume"),second+mapOf("programPageBytes" to 512))) {
            val output=root.resolve("must-not-exist")
            assertFailsWith<IllegalArgumentException> {p.prepare(config(),regions(),old+("regions" to listOf(rows[0],change)),byteArrayOf(),output)}
            assertFalse(Files.exists(output))
        }
    }
    @Test fun wrongImageSizeOrExistingOutputCannotProducePartialFiles(@TempDir root:Path) {
        val absent=root.resolve("absent")
        assertFailsWith<IllegalArgumentException> {p.prepare(config(),regions(),mutable(),ByteArray(1024),absent)}
        assertFalse(Files.exists(absent))
        val present=Files.createDirectory(root.resolve("present"));Files.writeString(present.resolve("sentinel"),"keep\n")
        assertFailsWith<IllegalArgumentException> {p.prepare(config(),regions(),mutable(),ByteArray(0x4000000),present)}
        assertEquals(listOf("sentinel"),Files.list(present).use {it.map {p->p.fileName.toString()}.toList()})
    }
    @Test fun rawValuesAreNotTruncatedAndNullableGeometryDoesNotBecomeZero() {
        for(value in listOf(null,1.0,"1",Long.MAX_VALUE.toDouble()))assertFailsWith<IllegalArgumentException> {p.exact(value)}
        assertEquals(0L,p.exact(0));assertEquals(Long.MAX_VALUE,p.exact(Long.MAX_VALUE))
    }
}
