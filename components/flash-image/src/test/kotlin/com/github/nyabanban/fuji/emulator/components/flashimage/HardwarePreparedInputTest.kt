package com.github.nyabanban.fuji.emulator.components.flashimage

import com.github.nyabanban.fuji.emulator.api.*
import com.github.nyabanban.fuji.emulator.runtime.Formats
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/** FILE input through the real component with a finite TEST DAT prefix; no native/guest qualification. */
class HardwarePreparedInputTest {
    @TempDir lateinit var temporary:Path
    private fun sha(bytes:ByteArray)=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private inner class Fixture {
        val input:Path
        val declarations:Map<String,Any?>
        val rows:List<Map<*,*>>
        val component:HardwareComponent
        val claims=linkedMapOf<String,Any>()
        val read:ReadOnlyImagePort
        val writable:WritableImageSetPort
        val program:ProgrammableImagePort
        init {
            val prepared=System.getProperty("hardwareFlashPrepared")
            assumeTrue(!prepared.isNullOrBlank(),"Formal hardware file input task provides hardwareFlashPrepared; generic COW tests use their own TEST layouts")
            input=Path.of(checkNotNull(prepared)).resolve("compoments/flash-image")
            declarations=Formats.readData(input.resolve("regions.json"))
            rows=(declarations["regions"] as List<*>).map {it as Map<*,*>}
            val immutable=rows.map {it["file"] as String}.toSet()
            val raw=ByteArray(0x274+8193)
            ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putInt(0,6).putInt(0x24c,8193)
            repeat(8193){raw[0x274+it]=(it and 255).toByte()}
            claims["test.update"]=object:UpdateDataPort {
                override val size=raw.size.toLong();override val sha256=sha(raw)
                override fun read(offset:Long,destination:ByteBuffer) {require(offset>=0 && destination.remaining()<=raw.size-offset);destination.put(raw,offset.toInt(),destination.remaining())}
            }
            val context=object:ComponentContext {
                override val directory=temporary
                override val clock=EmulationClock {0L}
                override fun dataPath(relative:String):Path {
                    require(relative.isNotBlank() && Path.of(relative).nameCount==1 && !relative.contains(':') && relative !in setOf(".",".."))
                    return if(relative in immutable)input.resolve(relative) else temporary.resolve(relative)
                }
                override fun readData(relative:String)=Formats.readData(input.resolve(relative))
                override val ports=object:Ports {
                    override fun<T:Any>register(owner:String,name:String,type:Class<T>,port:T){require(claims.putIfAbsent(name,port)==null)}
                    override fun<T:Any>find(name:String,type:Class<T>):T?=claims[name]?.let(type::cast)
                    override fun removeOwner(owner:String){}
                }
                override val events=object:EventChannel {override fun publish(event:ComponentEvent){};override fun subscribe(listener:(ComponentEvent)->Unit)=AutoCloseable{}}
            }
            val factory=FlashImageFactory()
            val config=factory.descriptor.settings.associate {it.key to it.default}+Formats.read(input.resolve("flash-image.yaml"))+("sourcePort" to "test.update")
            component=factory.create(context,config)
            read=claims.getValue("flash.image") as ReadOnlyImagePort
            writable=claims.getValue("flash.image.mutable") as WritableImageSetPort
            program=claims.getValue("flash.image.program") as ProgrammableImagePort
        }
    }
    @Test fun everySelectedFileByteAndAdjacentDomainTransitionUseActualFrozenData() {
        val fixture=Fixture()
        fixture.component.use {
            assertEquals(8193L,fixture.read.length)
            val prefix=ByteBuffer.allocate(8193);fixture.read.read(0,prefix)
            assertContentEquals(ByteArray(8193){(it and 255).inv().toByte()},prefix.array())
            val tail=ByteBuffer.allocate(4095);fixture.read.read(8193,tail);assertTrue(tail.array().all {it==(-1).toByte()})
            for(row in fixture.rows) {
                val bytes=Files.readAllBytes(fixture.input.resolve(row["file"] as String))
                val destination=ByteBuffer.allocate(bytes.size);fixture.read.read((row["offset"] as Number).toLong(),destination)
                assertContentEquals(bytes,destination.array());assertEquals(bytes.size,destination.position())
            }
            val first=Files.readAllBytes(fixture.input.resolve(fixture.rows[0]["file"] as String))
            val second=Files.readAllBytes(fixture.input.resolve(fixture.rows[1]["file"] as String))
            val transition=ByteBuffer.allocate(8);fixture.read.read(0x3d3fffc,transition)
            assertContentEquals(first.takeLast(4).toByteArray()+second.take(4).toByteArray(),transition.array())
        }
    }
    @Test fun suppliedFileLengthNeverGrantsHolesFullChipOrCrossBoundaryPartialReads() {
        val fixture=Fixture()
        fixture.component.use {
            val before=fixture.writable.regionSnapshots()
            for((offset,count) in listOf(12288L to 1,0x34fffffL to 2,0x3e3ffffL to 2,0x3e40000L to 1,0x3efffffL to 2,0x3f80000L to 1,0x3ffffffL to 1,Long.MAX_VALUE to 4)) {
                val destination=ByteBuffer.allocate(8);destination.put(ByteArray(8){0x5a});destination.position(2);destination.limit(2+count)
                val bytes=destination.array().copyOf();assertFailsWith<IllegalArgumentException> {fixture.read.read(offset,destination)}
                assertEquals(2,destination.position());assertContentEquals(bytes,destination.array());assertEquals(before,fixture.writable.regionSnapshots())
            }
            for((offset,count) in listOf(0x3500000L to 4096L,0x3d40000L to 4096L,0x3dbffffL to 2L,0x3e40000L to 4096L,0x3f3ffffL to 2L,0x3f40000L to 4096L))
                assertFailsWith<IllegalArgumentException> {fixture.writable.regionForWrite(offset,count)}
            assertEquals(before,fixture.writable.regionSnapshots())
        }
    }
    @Test fun onlyTwoExistingNarrowCowDomainsCanChangeAndAllImmutableSeedsStayUnchanged() {
        val fixture=Fixture()
        val before=fixture.rows.associate {(it["file"] as String) to Files.readAllBytes(fixture.input.resolve(it["file"] as String))}
        fixture.component.use {
            val initial=fixture.writable.regionSnapshots()
            assertEquals(listOf(0x3dc0000L,0x3f00000L),initial.map {it.writableOffset})
            assertEquals(listOf(0x80000L,0x40000L),initial.map {it.writableLength})
            for((i,offset) in listOf(0x3dc0000L,0x3f00000L).withIndex()) {
                fixture.writable.prepareErase(offset,4096).commit()
                fixture.program.prepareProgram(offset,ByteBuffer.wrap(byteArrayOf((0x50+i).toByte()))).commit()
                val observed=ByteBuffer.allocate(1);fixture.read.read(offset,observed);assertEquals((0x50+i).toByte(),observed.get(0))
            }
            assertEquals(listOf(2L,2L),fixture.writable.regionSnapshots().map {it.generation})
            for((name,bytes) in before)assertContentEquals(bytes,Files.readAllBytes(fixture.input.resolve(name)))
        }
        assertFalse(Files.exists(fixture.input.resolve("copy-domain.img")))
        assertFalse(Files.exists(fixture.input.resolve("copy-domain504-505.img")))
    }
}
