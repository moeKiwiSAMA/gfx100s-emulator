package com.github.nyabanban.fuji.emulator.components.flashimage

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

class WritableRegionTest {
    @TempDir lateinit var home:Path
    private val offset=0x3de0000L
    private fun hash(b:ByteArray)=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b))
    private class Context(override val directory:Path,val data:Map<String,Map<String,Any?>> = emptyMap()):ComponentContext {
        val claims=linkedMapOf<String,Any>()
        override fun readData(relative:String)=data.getValue(relative)
        override val clock=EmulationClock{0}
        override val ports=object:Ports{
            override fun <T:Any> register(owner:String,name:String,type:Class<T>,port:T){check(claims.putIfAbsent(name,port)==null)}
            override fun <T:Any> find(name:String,type:Class<T>):T?=claims[name]?.let{type.cast(it)}
            override fun removeOwner(owner:String){}
        }
        override val events=object:EventChannel{override fun publish(event:ComponentEvent){};override fun subscribe(listener:(ComponentEvent)->Unit)=AutoCloseable{}}
        override fun dataPath(relative:String):Path=directory.resolve(relative).normalize().also{require(it.startsWith(directory))}
    }
    private fun seed(fill:Int=0xa5):ByteArray {
        val b=ByteArray(131072){fill.toByte()};Files.write(home.resolve("seed.img"),b);return b
    }
    private fun identity(b:ByteArray)=ImageRegionIdentity("seed",offset,b.size.toLong(),hash(b),"seed.img")
    private fun spec(b:ByteArray,mode:String="create",file:String="copy.img")=mapOf<String,Any?>("schema" to 1,"profile" to WritableRegion.PROFILE,"regionId" to "seed","file" to file,"mode" to mode,"sha256" to hash(b),"writableOffset" to offset,"writableLength" to b.size.toLong())
    private fun open(b:ByteArray,settings:Map<String,Any?> = spec(b))=WritableRegion(Context(home),settings,listOf(identity(b))){at,dst->
        require(at>=offset&&dst.remaining()<=b.size-(at-offset));dst.put(b,(at-offset).toInt(),dst.remaining())
    }
    @Test fun actualAtomicEraseReadbackResumeAndImmutableSeedCanaries() {
        val initial=seed();val expected=initial.clone().also{it.fill(0xff.toByte(),0,65536)}
        val image=open(initial)
        val transaction=image.prepareErase(offset,65536)
        assertContentEquals(initial,Files.readAllBytes(home.resolve("copy.img")));assertTrue(image.snapshot().pending)
        val receipt=transaction.commit()
        assertEquals(65536L,receipt.changedBytes);assertEquals(hash(initial),receipt.beforeSha256);assertEquals(hash(expected),receipt.afterSha256)
        assertContentEquals(expected,Files.readAllBytes(home.resolve("copy.img")));assertContentEquals(initial,Files.readAllBytes(home.resolve("seed.img")))
        val view=ByteBuffer.allocateDirect(65544);repeat(view.capacity()){view.put(it,0x59)};view.position(4);view.limit(65540)
        image.overlay(offset,view,view.position(),view.remaining());assertEquals(4,view.position())
        repeat(65536){assertEquals(0xff.toByte(),view.get(it+4))};view.limit(view.capacity());repeat(4){assertEquals(0x59.toByte(),view.get(it));assertEquals(0x59.toByte(),view.get(it+65540))}
        image.close()
        open(initial,spec(expected,"resume")).use{resumed->assertEquals(hash(expected),resumed.snapshot().currentSha256);val raw=ByteBuffer.allocate(131072);resumed.overlay(offset,raw,0,raw.capacity());assertContentEquals(expected,raw.array())}
    }
    @Test fun boundsOwnershipCancellationAndStaleCommitNeverMutateStorage() {
        val b=seed();open(b).use{image->
            for((at,size)in listOf(-1L to 65536L,offset-65536 to 65536L,offset+1 to 65536L,offset+131072 to 4096L,offset to 256L,Long.MAX_VALUE to 65536L)) {
                val before=image.snapshot();assertFails{image.prepareErase(at,size)};assertEquals(before,image.snapshot());assertContentEquals(b,Files.readAllBytes(home.resolve("copy.img")))
            }
            val t=image.prepareErase(offset,4096);val before=image.snapshot();assertFails{image.prepareErase(offset,4096)};assertEquals(before,image.snapshot())
            t.close();assertFalse(image.snapshot().pending);assertFails{t.commit()};assertContentEquals(b,Files.readAllBytes(home.resolve("copy.img")))
            val next=image.prepareErase(offset,4096);val r=next.commit();assertEquals(1L,r.generation);assertFails{next.commit()};next.close()
        }
    }
    @Test fun closingPendingLeaseReleasesFileLockAndRejectsStaleReadWithoutDestinationChanges() {
        val b=seed();val image=open(b);val t=image.prepareErase(offset,65536);image.close();image.close()
        assertTrue(image.snapshot().closed);assertFalse(image.snapshot().pending);assertFails{t.commit()};assertFails{image.prepareErase(offset,4096)}
        val dst=ByteBuffer.wrap(ByteArray(16){0x58});assertFails{image.overlay(offset,dst,0,16)};assertEquals(0,dst.position());assertContentEquals(ByteArray(16){0x58},dst.array())
        open(b,spec(b,"resume")).close();assertContentEquals(b,Files.readAllBytes(home.resolve("seed.img")));assertContentEquals(b,Files.readAllBytes(home.resolve("copy.img")))
    }
    @Test fun externalEditDuringLeaseFailsWithoutHidingOrOverwritingIt() {
        val b=seed();open(b).use{image->
            val t=image.prepareErase(offset,65536);val external=b.clone().also{it[70000]=3};Files.write(home.resolve("copy.img"),external)
            assertFails{t.commit()};assertNotNull(image.snapshot().failure);assertTrue(image.snapshot().pending);assertEquals(0L,image.snapshot().generation)
            assertContentEquals(external,Files.readAllBytes(home.resolve("copy.img")));assertFails{image.prepareErase(offset,4096)}
        }
    }
    @Test fun temporaryOutputCollisionPreservesOriginalAndFailedTransaction() {
        val b=seed();open(b).use{image->
            val t=image.prepareErase(offset,65536);val canary=byteArrayOf(1,2,3);Files.write(home.resolve("copy.img.txn-1.tmp"),canary)
            assertFails{t.commit()};assertNotNull(image.snapshot().failure);assertEquals(hash(b),image.snapshot().currentSha256);assertEquals(0L,image.snapshot().generation)
            assertContentEquals(b,Files.readAllBytes(home.resolve("copy.img")));assertContentEquals(canary,Files.readAllBytes(home.resolve("copy.img.txn-1.tmp")))
        }
    }
    @Test fun exclusiveLeaseAndCreateExistingFileRefusalsPreserveCurrentBytes() {
        val b=seed();open(b).use{first->
            assertFails{open(b,spec(b,"resume"))};assertFails{open(b)}
            assertContentEquals(b,Files.readAllBytes(home.resolve("copy.img")));assertEquals(0L,first.snapshot().generation)
        }
        open(b,spec(b,"resume")).close()
    }
    @Test fun sourceAliasAndHardLinkCannotBecomeMutableCopy() {
        val b=seed();assertFails{open(b,spec(b,"resume","seed.img"))}
        Files.createLink(home.resolve("linked.img"),home.resolve("seed.img"))
        assertFails{open(b,spec(b,"resume","linked.img"))};assertContentEquals(b,Files.readAllBytes(home.resolve("seed.img")))
    }
    @Test fun strictSpecificationErrorsRefuseBeforeCreatingCopyOrLock() {
        val b=seed();val valid=spec(b)
        val invalid=listOf(valid+("unknown" to 1),valid-("regionId"),valid+("schema" to 1.0),valid+("profile" to "unknown"),valid+("regionId" to "missing"),
            valid+("file" to "../escape.img"),valid+("file" to "CON.img"),valid+("file" to "copy.bin"),valid+("mode" to "unknown"),valid+("sha256" to "f".repeat(64)),valid+("sha256" to 5),valid+("writableOffset" to (offset+1)),valid+("writableLength" to 0),valid+("writableLength" to 131073),valid+("writableOffset" to Long.MAX_VALUE))
        for(settings in invalid){assertFails{open(b,settings)};assertFalse(Files.exists(home.resolve("copy.img")));assertFalse(Files.exists(home.resolve("copy.img.lock")))}
    }
    @Test fun resumeRequiresExplicitCurrentHashAndExactExistingLength() {
        val b=seed();Files.write(home.resolve("copy.img"),b.clone().also{it[0]=0})
        assertFails{open(b,spec(b,"resume"))};assertContentEquals(b,Files.readAllBytes(home.resolve("seed.img")))
        Files.write(home.resolve("copy.img"),ByteArray(1));assertFails{open(b,spec(b,"resume"))}
    }
    @Test fun explicitSubrangeKeepsOtherSeedBytesReadonlyAcrossResume() {
        val b=seed();val settings=spec(b)+mapOf("writableOffset" to(offset+4096),"writableLength" to 4096L)
        open(b,settings).use{image->val before=image.snapshot();assertFails{image.prepareErase(offset,4096)};assertFails{image.prepareErase(offset+8192,4096)};assertEquals(before,image.snapshot());image.prepareErase(offset+4096,4096).commit()}
        val expected=b.clone().also{it.fill(0xff.toByte(),4096,8192)}
        open(b,spec(expected,"resume")+mapOf("writableOffset" to(offset+4096),"writableLength" to 4096L)).close()
        val tampered=expected.clone().also{it[0]=0};Files.write(home.resolve("copy.img"),tampered)
        assertFails{open(b,spec(tampered,"resume")+mapOf("writableOffset" to(offset+4096),"writableLength" to 4096L))};assertContentEquals(tampered,Files.readAllBytes(home.resolve("copy.img")));assertContentEquals(b,Files.readAllBytes(home.resolve("seed.img")))
    }
    private fun factoryFixture(b:ByteArray,mutable:Boolean):Pair<Context,HardwareComponent> {
        val row=mapOf("id" to "seed","offset" to offset,"length" to b.size.toLong(),"file" to "seed.img","sha256" to hash(b))
        val context=Context(home,mapOf("regions.json" to mapOf("schema" to 1,"profile" to SupplementalRegions.PROFILE,"regions" to listOf(row)),"mutable.json" to spec(b)))
        val source=ByteArray(0x274+4096){0xdd.toByte()};java.nio.ByteBuffer.wrap(source).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(0,6).putInt(0x24c,4096)
        context.claims["test.update"]=object:UpdateDataPort {override val size=source.size.toLong();override val sha256=hash(source);override fun read(offset:Long,destination:ByteBuffer){require(offset>=0&&offset+destination.remaining()<=source.size);destination.put(source,offset.toInt(),destination.remaining())}}
        val settings=FlashImageFactory().descriptor.settings.associate{it.key to it.default}+mapOf("sourcePort" to "test.update","regionsProfile" to SupplementalRegions.PROFILE,"regionsFile" to "regions.json")+if(mutable)mapOf("mutationProfile" to WritableRegion.PROFILE,"mutationFile" to "mutable.json")else emptyMap()
        return context to FlashImageFactory().create(context,settings)
    }
    @Test fun actualFactoryPublishesSeparateTypedMutablePortAndArrayReadsSeeOnlyCommittedOverlay() {
        val b=seed();val(context,component)=factoryFixture(b,true);component.use{
            assertNull(context.ports.find("flash.image.program",ProgrammableImagePort::class.java));val port=context.ports.find("flash.image",ReadOnlyImagePort::class.java)!!;val mutable=context.ports.find("flash.image.mutable",WritableImagePort::class.java)!!
            assertEquals(4096L,port.length);assertEquals(hash(ByteArray(4096){0x22}),port.sha256);assertEquals(true,component.state()["writable"])
            val unchanged=ByteBuffer.allocate(8);port.read(offset,unchanged);assertContentEquals(ByteArray(8){0xa5.toByte()},unchanged.array())
            val lease=mutable.prepareErase(offset,65536);val during=ByteBuffer.allocate(8);port.read(offset,during);assertContentEquals(unchanged.array(),during.array());lease.commit()
            val crossing=ByteBuffer.wrap(ByteArray(22){0x58});crossing.position(3);crossing.limit(19);port.read(offset+65532,crossing);assertEquals(19,crossing.position());crossing.limit(22)
            repeat(4){assertEquals(0xff.toByte(),crossing.get(3+it))};repeat(12){assertEquals(0xa5.toByte(),crossing.get(7+it))};for(index in listOf(0,1,2,19,20,21))assertEquals(0x58.toByte(),crossing.get(index))
            val gap=ByteBuffer.wrap(ByteArray(8){0x59});assertFails{port.read(4094,gap)};assertEquals(0,gap.position());assertContentEquals(ByteArray(8){0x59},gap.array());assertContentEquals(b,Files.readAllBytes(home.resolve("seed.img")))
        }
        val after=ByteBuffer.wrap(ByteArray(8){0x58});assertFails{context.ports.find("flash.image",ReadOnlyImagePort::class.java)!!.read(offset,after)};assertEquals(0,after.position())
    }
    @Test fun defaultFactoryKeepsOldReadonlyPortAndWireStateShape() {
        val b=seed();val(context,component)=factoryFixture(b,false);component.use{
            assertNull(context.ports.find("flash.image.program",ProgrammableImagePort::class.java));assertNull(context.ports.find("flash.image.mutable",WritableImagePort::class.java));val state=component.state();assertEquals(false,state["writable"]);assertFalse("mutationProfile" in state);assertFalse("mutationFile" in state);assertFalse("writableOverlay" in state);assertFalse("eraseHistory" in state)
            assertFalse(Files.exists(home.resolve("copy.img")));assertEquals(4096L,context.ports.find("flash.image",ReadOnlyImagePort::class.java)!!.length)
        }
    }
    @Test fun repeatedNoChangeErasesRetainBoundedHistoryAndExactGeneration() {
        val b=seed(255);open(b).use{image->
            repeat(65){index->val r=image.prepareErase(offset,65536).commit();assertEquals(0L,r.changedBytes);assertEquals((index+1).toLong(),r.generation);assertEquals(hash(b),r.afterSha256)}
            assertEquals(64,image.history().size);assertEquals(2L,image.history().first().generation);assertEquals(65L,image.snapshot().generation)
            assertContentEquals(b,Files.readAllBytes(home.resolve("copy.img")));assertFalse(Files.exists(home.resolve("copy.img.txn-1.tmp")))
        }
    }
}
