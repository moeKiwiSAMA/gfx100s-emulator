package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.MmioDevice
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.*

class DiscoveryTest {
    private fun selectable(values: List<Long> = listOf(0L,1L)) = DiscoveryRegion(
        "choice", "test-device", 0x1000, 4, DiscoveryStrategy.FIXED, setOf(4), 4, true, selectionValues=values)

    @Test fun fixedSelectionIsFrozenExplicitOnceOnlyAndIndependentOfJournalExport() {
        for (value in listOf(0L,1L)) {
            val declaration=mutableListOf(0L,1L)
            val b=bus(selectable(declaration))
            declaration.clear()
            val initial=b.fixedInputSelection("choice")
            assertEquals(listOf(0L,1L),initial["selectionValues"])
            assertNull(initial["selectedValue"]);assertEquals(false,initial["accessAttempted"])
            assertEquals("unselected",initial["selectionState"])
            assertFails { b.selectFixedInput("choice",2) }
            assertFails { b.selectFixedInput("absent",value) }
            assertEquals(initial,b.fixedInputSelection("choice"))
            assertEquals(0,b.journal.count)
            val selected=b.selectFixedInput("choice",value)
            assertEquals(value,selected["selectedValue"]);assertEquals("selected",selected["selectionState"])
            assertFails { b.selectFixedInput("choice",value) }
            assertEquals(selected,b.fixedInputSelection("choice"))
            assertEquals(value,b.access(1,0x1000,4,false))
            assertEquals(value,b.access(2,0x1000,4,false))
            assertEquals(2L,b.fixedInputSelection("choice")["attemptedAccesses"])
            val summary=b.checkpointState()
            assertFalse(summary.containsKey("accesses"))
            assertEquals((b.snapshot()["regions"] as List<*>),summary["regions"])
            assertEquals(summary["regions"],b.boundedSnapshot(1)["regions"])
            assertFails { b.access(3,0x1000,4,true,value) }
        }
        val old=bus(region(DiscoveryStrategy.FIXED,fixed=7))
        assertEquals(7L,old.access(1,0x1000,4,false))
        assertFalse(((old.snapshot()["regions"] as List<*>).single() as Map<*,*>).containsKey("selectionValues"))
        assertFails { old.selectFixedInput("candidate",0) }
    }

    @Test fun unselectedOrInvalidAttemptsCannotBeRetrofittedWithInput() {
        val attempts:List<(DiscoveryBus)->Unit> = listOf(
            { it.access(1,0x1000,4,false);Unit },
            { it.access(1,0x1000,1,false);Unit },
            { it.access(1,0x1001,4,false);Unit },
            { it.access(1,0x1000,4,true,0);Unit },
            { it.access(1,0x0fff,4,false);Unit })
        for (attempt in attempts) {
            val b=bus(selectable())
            assertFails { attempt(b) }
            assertEquals(true,b.fixedInputSelection("choice")["accessAttempted"])
            assertEquals(1L,b.fixedInputSelection("choice")["attemptedAccesses"])
            assertNull(b.fixedInputSelection("choice")["selectedValue"])
            assertFails { b.selectFixedInput("choice",0) }
        }
        val missing=bus(selectable())
        assertFails { missing.access(1,0x1000,4,false) }
        val refusal=accesses(missing).single() as Map<*,*>
        assertEquals("finite-input-missing-or-exhausted",refusal["reason"])
        assertNull(refusal["value"]);assertEquals(false,refusal["completed"])
        val native=bus(selectable())
        native.refused(1,0x1000,4,false,reason=DiscoveryJournal.NATIVE,execute=true)
        assertFails { native.selectFixedInput("choice",1) }
        val full=bus(selectable(),capacity=1)
        full.refused(1,0x2000,4,false,reason=DiscoveryJournal.OUTSIDE)
        assertFails { full.access(2,0x1000,4,false) }
        assertEquals(true,full.fixedInputSelection("choice")["accessAttempted"])
        assertFails { full.selectFixedInput("choice",1) }
        val closed=bus(selectable());closed.close()
        assertFails { closed.selectFixedInput("choice",0) }
    }

    @Test fun fixedSelectionsRejectAmbiguousOrNonFiniteDeclarations() {
        for (values in listOf(emptyList(),listOf(0L,0L),listOf(-1L),listOf(0x1_0000_0000L))) {
            assertFails { selectable(values) }
        }
        assertFails { selectable().copy(fixedValue=0) }
        assertFails { region().copy(selectionValues=listOf(0)) }
    }

    @Test fun boundedExportKeepsTerminalRefusalAndDoesNotChangeJournalOrInputs() {
        val b = bus(region(DiscoveryStrategy.SCRIPT, samples = (1L..8L).toList()))
        repeat(8) { assertEquals(it + 1L, b.access(it + 1L, 0x1000, 4, false)) }
        assertFails { b.access(9, 0x1000, 4, false) }
        val before = b.snapshot()
        val bounded = b.boundedSnapshot(2)
        assertEquals(9, bounded["retainedCount"])
        assertEquals(2, bounded["exportedCount"])
        assertEquals(7, bounded["exportOmittedCount"])
        assertEquals(true, bounded["exportTruncated"])
        assertEquals((before["accesses"] as List<*>).takeLast(2), bounded["accesses"])
        assertEquals("finite-input-missing-or-exhausted", ((bounded["accesses"] as List<*>).last() as Map<*,*>)["reason"])
        assertEquals(before, b.snapshot())
        assertFailsWith<IllegalArgumentException> { b.boundedSnapshot(0) }
        val directory = Files.createTempDirectory("bounded-discovery")
        try {
            val file = directory.resolve("trace.json")
            val tooLarge = bounded + mapOf("large" to "x".repeat(8192))
            assertFails { b.journal.export(file, b.regions, tooLarge, 4096) }
            assertFalse(Files.exists(file))
            Files.list(directory).use { assertEquals(0L, it.count()) }
            assertEquals(before, b.snapshot())
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun checkpointSummaryPreservesCountersAndInputPositionsWithoutMaterializingAccessRows() {
        val b=bus(region(DiscoveryStrategy.SCRIPT,samples=listOf(3L,7L)))
        assertEquals(3L,b.access(1,0x1000,4,false))
        val snapshot=b.snapshot();val summary=b.checkpointState()
        assertEquals(snapshot-"accesses",summary-"accessRecordsOmitted")
        assertEquals(true,summary["accessRecordsOmitted"]);assertFalse(summary.containsKey("accesses"))
        assertEquals(snapshot,b.snapshot())
        assertEquals(7L,b.access(2,0x1000,4,false))
        assertFails {b.access(3,0x1000,4,false)}
        assertEquals(b.snapshot()-"accesses",b.checkpointState()-"accessRecordsOmitted")
    }
    private class Context : DiscoveryContext {
        var now=17L
        override fun core()=2
        override fun tick()=now
        override fun pc()=0x5afe0L
        override fun lr()=0x11f1c78L
    }
    private fun region(strategy: DiscoveryStrategy = DiscoveryStrategy.RETAINED, base: Long=0x1000, length: Int=32, fill: Int=0x55,
                       fixed: Long?=null, samples: List<Long?>?=null, holes: List<AddressInterval> = emptyList()) =
        DiscoveryRegion("candidate", "slot0", base,length,strategy,setOf(1,2,4),1,true,
            if(strategy in setOf(DiscoveryStrategy.RETAINED,DiscoveryStrategy.IMAGE))ByteArray(length){fill.toByte()}else null,fixed,samples,holes)
    private fun bus(region: DiscoveryRegion=region(), known: List<AddressInterval> = emptyList(), capacity:Int=32, disk:Long=1048576)=
        DiscoveryBus(listOf(region),known,DiscoveryJournal("run-test","1","a".repeat(64),"b".repeat(64),"c".repeat(64),capacity,disk),Context())
    private fun accesses(b: DiscoveryBus) = b.snapshot()["accesses"] as List<*>
    @Test fun f01cExtensionSurvivesRestoreWithoutRearmingTheWriteOrResettingReads() {
        val address = 0xfff4f01cL
        val region = DiscoveryRegion("diag", "f01c", address, 4, DiscoveryStrategy.RETAINED, setOf(4), 4, true,
            retainedDiagnostic = RetainedWriteDiagnostic(1L, 2))
        val original = bus(region)
        assertFails { original.extendF01cReads(region.id, 2) }
        original.access(1, address, 4, true, 1)
        original.access(2, address, 4, false)
        val legacy = original.saveSnapshot()
        assertEquals(1, legacy["schema"])
        val selected = bus(region).apply { restoreSnapshot(legacy) }
        for (invalid in listOf(0, -1, DiscoveryBus.MAX_EXTRA_F01C_READS + 1, Int.MAX_VALUE)) {
            assertFails { selected.extendF01cReads(region.id, invalid) }
        }
        assertFails { selected.extendF01cReads("other", 2) }
        val receipt = selected.extendF01cReads(region.id, 2)
        assertEquals(1, receipt["successfulReads"])
        assertEquals(4, receipt["successfulReadLimit"])
        repeat(2) { assertEquals(1L, selected.access(it.toLong(), address, 4, false)) }
        val saved = selected.saveSnapshot()
        assertEquals(2, saved["schema"])
        val restored = bus(region).apply { restoreSnapshot(saved) }
        assertEquals(saved, restored.saveSnapshot())
        assertFails { restored.extendF01cReads(region.id, 1) }
        assertEquals(1L, restored.access(1, address, 4, false))
        val exhausted = restored.saveSnapshot()
        val extended = bus(region).apply { restoreSnapshot(exhausted) }
        val second = extended.extendF01cReads(region.id, 1)
        assertEquals(4, second["previousSuccessfulReadLimit"])
        assertEquals(5, second["successfulReadLimit"])
        val resumed = bus(region).apply { restoreSnapshot(extended.saveSnapshot()) }
        assertEquals(1L, resumed.access(1, address, 4, false))
        assertContains(assertFails { resumed.access(2, address, 4, false) }.message.orEmpty(), "diagnostic-limit")
        assertContains(assertFails { restored.access(2, address, 4, false) }.message.orEmpty(), "diagnostic-limit")
        assertContains(assertFails { bus(region).apply { restoreSnapshot(saved) }.access(1, address, 4, true, 1) }
            .message.orEmpty(), "diagnostic-repeated-write")
        for ((offset, width) in listOf(0 to 2, 1 to 4, 4 to 4)) {
            assertFails { bus(region).apply { restoreSnapshot(saved) }.access(1, address + offset, width, false) }
        }
        assertFails { bus(region).restoreSnapshot(saved - "f01cExtension") }
        assertFails { bus(region).restoreSnapshot(saved + ("schema" to 1)) }
        assertFails { bus(region).restoreSnapshot(saved - "f01cExtension" + ("schema" to 1)) }
        assertEquals(1L, original.access(3, address, 4, false))
        assertContains(assertFails { original.access(4, address, 4, false) }.message.orEmpty(), "diagnostic-limit")
        assertFails { original.extendF01cReads(region.id, 1) }
    }
    @Test fun retainedLanesUseExplicitFillWithoutAllocatingArm32AndSnapshotIsReadOnly() {
        val b=bus(region(base=0xffffffe0L)); val router=MmioRouter(emptyList(),b)
        assertEquals(0x55555555L,router.read(0xfffffff0L,4))
        router.write(0xfffffff1L,1,0x12); assertEquals(0x55551255L,router.read(0xfffffff0L,4))
        val before=b.snapshot(); assertEquals(before,b.snapshot())
        assertEquals(3,(before["retainedCount"] as Number).toInt()); assertEquals(0L,before["unretainedCount"])
        assertEquals(listOf(0xfffff000L to 4096L),router.pageRanges())
        val read=accesses(b)[0] as Map<*,*>; assertEquals(0x11f1c78L,read["lr"]); assertEquals("HYPOTHESIS",read["confidence"])
        assertEquals(17L,read["tick"]); assertNull(read["addressVirtual"])
        assertEquals(before["runId"],read["runId"])
        for(key in listOf("dictionaryVersion","dictionarySha256","firmwareSha256","configurationSha256")) {
            assertNotNull(before[key]);assertFalse(read.containsKey(key))
        }
        assertEquals("compiled-payload-sha256",before["dictionaryDigestKind"])
    }
    @Test fun audioF01cCompletionPreservesReadHistoryLimitAndOneShotAcrossRestore() {
        val address = 0xfff4f01cL
        val region = DiscoveryRegion("audio", "f01c", address, 4, DiscoveryStrategy.RETAINED, setOf(4), 4, true,
            retainedDiagnostic = RetainedWriteDiagnostic(1, 2))
        val original = bus(region)
        assertFails { original.completeAudioF01c(region.id, 1, 1) }
        original.access(1, address, 4, true, 1)
        assertFails { original.completeAudioF01c(region.id, 1, 1) }
        assertEquals(1L, original.access(2, address, 4, false))
        original.extendF01cReads(region.id, 1)
        val legacy = original.saveSnapshot()
        val selected = bus(region).apply { restoreSnapshot(legacy) }
        assertFails { selected.completeAudioF01c("wrong", 2, 3) }
        val receipt = selected.completeAudioF01c(region.id, 2, 3)
        val saved = selected.saveSnapshot()
        assertEquals(4, saved["schema"])
        assertEquals(1, receipt["successfulReads"])
        assertEquals(legacy["f01cExtension"], saved["f01cExtension"])
        val restored = bus(region).apply { restoreSnapshot(saved) }
        assertEquals(saved, restored.saveSnapshot())
        assertFails { restored.completeAudioF01c(region.id, 3, 4) }
        repeat(2) { assertEquals(0L, restored.access(4, address, 4, false)) }
        assertFails { restored.access(5, address, 4, false) }
        assertFails { bus(region).apply { restoreSnapshot(saved) }.access(4, address, 4, true, 1) }
        assertFails { bus(region).restoreSnapshot(saved - "audioF01cCompletion") }
        assertFails { bus(region).restoreSnapshot(saved + ("audioF01cCompletion" to (receipt + ("successfulReads" to 2)))) }
        assertEquals(1L, original.access(3, address, 4, false))
    }
    @Test fun finiteAudioRepeatsKeepGuestTransactionDirectionAndRestorePendingRequest() {
        val address = 0xfff4f01cL
        val region = DiscoveryRegion("audio", "f01c", address, 4, DiscoveryStrategy.RETAINED, setOf(4), 4, true,
            retainedDiagnostic = RetainedWriteDiagnostic(1, 6))
        fun restore(snapshot: Map<String, Any?>) = bus(region).apply { restoreSnapshot(snapshot) }
        val original = bus(region)
        original.access(1, address, 4, true, 1)
        original.access(2, address, 4, false)
        assertFails { original.selectF01cRepeats(region.id, 1) }
        original.completeAudioF01c(region.id, 2, 2)
        val legacy = original.saveSnapshot()
        val selected = restore(legacy)
        for (limit in listOf(-1, 0, 6, 17)) assertFails { selected.selectF01cRepeats(region.id, limit) }
        selected.selectF01cRepeats(region.id, 2)
        val armed = selected.saveSnapshot()
        assertEquals(5, armed["schema"])
        assertEquals(legacy["audioF01cCompletion"], armed["audioF01cCompletion"])
        assertFails { selected.selectF01cRepeats(region.id, 2) }
        assertFails { restore(armed).access(3, address, 4, true, 0) }
        assertFails { restore(armed).access(3, address, 1, true, 1) }
        assertEquals(0L, selected.access(3, address, 4, false))
        assertEquals(1L, selected.access(4, address, 4, true, 1))
        val pending = selected.saveSnapshot()
        assertFails { restore(pending).access(5, address, 4, true, 1) }
        val resumed = restore(pending)
        assertEquals(pending, resumed.saveSnapshot())
        for (target in listOf(selected,resumed)) {
            assertEquals(0L, target.access(5, address, 4, false))
            assertEquals(1L, target.access(6, address, 4, true, 1))
            assertEquals(0L, target.access(7, address, 4, false))
        }
        val completed = resumed.saveSnapshot()
        assertEquals(selected.saveSnapshot(), completed)
        val events = accesses(resumed).map { it as Map<*,*> }
        assertEquals(listOf("read","write","read"), events.map { it["direction"] })
        assertEquals(listOf(1L,0L,1L), events.map { it["before"] })
        assertEquals(listOf(0L,1L,0L), events.map { it["after"] })
        assertFails { restore(completed).access(8, address, 4, true, 1) }
        repeat(2) { resumed.access(8L + it, address, 4, false) }
        assertFails { resumed.access(10, address, 4, false) }
        val noReadsLeft = restore(armed)
        repeat(5) { noReadsLeft.access(3L + it, address, 4, false) }
        assertFails { noReadsLeft.access(8, address, 4, true, 1) }
        assertFails { restore(pending - "audioF01cRepeats") }
        assertFails { restore(legacy + ("audioF01cRepeats" to pending["audioF01cRepeats"])) }
        val repeatState = completed.getValue("audioF01cRepeats") as Map<*,*>
        for (bad in listOf(listOf(2,2), listOf(3,2), listOf(2,4), listOf(0,3), listOf(2))) {
            assertFails { restore(completed + ("audioF01cRepeats" to repeatState + ("writeAtSuccessfulReads" to bad))) }
        }
    }

    @Test fun knownPortWrongWidthAndCrossingCannotFallbackOrMutateAnything() {
        val b=bus(known=listOf(AddressInterval(0x1008,0x100c)))
        var writes=0; var reads=0
        val port=object:MmioDevice {
            override fun read(offset:Long,size:Int):Long { require(size==4 && offset==0L); reads++; return 9 }
            override fun write(offset:Long,size:Int,value:Long) { require(size==2 && offset==0L); writes++ }
        }
        val router=MmioRouter(listOf(MmioWindow("known",0x1008,4,port)),b)
        val before=b.snapshot(); assertFails { router.write(0x1008,4,1) }; assertFails { router.read(0x100a,2) }
        assertFails { router.write(0x1006,4,1) }; assertFails { router.read(0x100a,4) }
        assertEquals(0,writes); assertEquals(0,reads); assertEquals(before["regions"],b.snapshot()["regions"])
        assertEquals(4,accesses(b).size);assertTrue(accesses(b).all { (it as Map<*,*>)["completed"]==false })
        assertEquals(9L,router.read(0x1008,4)); assertEquals(1,reads)
        assertFails { MmioRouter(emptyList(),b).read(0x1008,4) }
        val refused=accesses(b).last() as Map<*,*>; assertNull(refused["value"]); assertEquals("known-port-not-active",refused["reason"])
    }
    @Test fun everyRouterAndNativeRefusalIsRecordedExactlyOnceWithoutCallingModels() {
        val b=bus(region(DiscoveryStrategy.SCRIPT,samples=listOf(7)),known=listOf(AddressInterval(0x1008,0x100c)))
        var calls=0
        val port=object:MmioDevice {
            override fun read(offset:Long,size:Int):Long { calls++;error("known read denied") }
            override fun write(offset:Long,size:Int,value:Long) { calls++;require(value and 1L.inv()==0L);error("known write denied") }
        }
        val router=MmioRouter(listOf(MmioWindow("known",0x1008,4,port)),b)
        val before=b.snapshot()["regions"]
        assertFails {router.read(0x1006,4)}
        assertFails {router.read(0x1008,4)}
        assertFails {router.write(0x1008,4,2)}
        assertFails {router.read(0x1020,4)}
        for(kind in listOf(19,20,21,22,23,24))router.refusedNative(kind,0x9000,4,0x12)
        val rows=accesses(b).map {it as Map<*,*>}
        assertEquals(10,rows.size);assertEquals((1L..10L).toList(),rows.map {it["sequence"]})
        assertEquals(2,calls);assertEquals(before,b.snapshot()["regions"])
        assertEquals(listOf("router-preflight-refusal","known-device-refusal","known-device-refusal","outside-selected-envelope"),rows.take(4).map {it["reason"]})
        assertTrue(rows.all {it["refused"]==true && it["completed"]==false && it["accepted"]==false})
        rows.drop(4).forEach { row ->
            val kind=(row["nativeKind"] as Number).toInt()
            assertEquals(MmioRouter.nativeDirection(kind),row["direction"])
            assertEquals(if(kind in listOf(20,22))0x12L else null,row["value"])
            assertEquals(0x11f1c78L,row["lr"]);assertEquals(17L,row["tick"])
            assertEquals("native-unmapped-or-protected",row["reason"])
        }
        assertNull(rows[0]["value"]);assertNull(rows[1]["value"]);assertEquals(2L,rows[2]["value"])
        assertFails { MmioRouter.nativeDirection(16) }
        assertEquals(10,accesses(b).size)
    }
    @Test fun holesCrossRegionOverflowAndWrongWidthRefuseBeforeRetainedBytesChange() {
        val b=bus(region(holes=listOf(AddressInterval(0x1010,0x1014))))
        val before=(b.snapshot()["regions"] as List<*>)[0]
        assertFails { b.access(1,0x1010,4,true,0) }; assertFails { b.access(2,0x101e,4,true,0) }
        assertFails { b.access(3,0x1000,8,false) }; assertFails { b.access(4,0xffffffff,4,false) }
        assertEquals(before,(b.snapshot()["regions"] as List<*>)[0])
        assertTrue(accesses(b).all { (it as Map<*,*>)["completed"]==false })
        assertFails { DiscoveryRegion("bad","slot",0xffffffffL,2,DiscoveryStrategy.RETAINED,setOf(1),1,true,byteArrayOf(0,0)) }
    }
    @Test fun finiteScriptDoesNotConsumeOnMissingWrongWidthOrLogCapacityAndNeverRepeatsLast() {
        val b=bus(region(DiscoveryStrategy.SCRIPT,samples=listOf(0x1234,null,7)))
        assertFails { b.access(1,0x1000,1,false) }; assertEquals(0,(b.snapshot()["regions"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } })[0]["sampleIndex"])
        assertEquals(0x1234L,b.access(2,0x1000,2,false)); assertFails { b.access(3,0x1000,2,false) }
        assertEquals(1,(b.snapshot()["regions"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } })[0]["sampleIndex"])
        val finite=bus(region(DiscoveryStrategy.SCRIPT,samples=listOf(1)),capacity=1)
        assertEquals(1L,finite.access(1,0x1000,4,false)); val model=(finite.snapshot()["regions"] as List<*>)[0]
        assertFails { finite.access(2,0x1000,4,false) }
        assertEquals(model,(finite.snapshot()["regions"] as List<*>)[0]); assertEquals(2,finite.journal.count)
        assertEquals("log-capacity",(accesses(finite).last() as Map<*,*>)["reason"])
        assertNull((accesses(finite).last() as Map<*,*>)["value"])
        val exhausted=bus(region(DiscoveryStrategy.SCRIPT,samples=listOf(5)))
        exhausted.access(1,0x1000,4,false); assertFails { exhausted.access(2,0x1000,4,false) }
        assertEquals("finite-input-missing-or-exhausted",(accesses(exhausted).last() as Map<*,*>)["reason"])
    }
    @Test fun fixedImageAndRefuseStrategiesRemainExplicitAndReadOnly() {
        val fixed=bus(region(DiscoveryStrategy.FIXED,fixed=0x1234)); assertEquals(0x1234L,fixed.access(1,0x1000,2,false))
        assertFails { fixed.access(2,0x1000,1,false) }; assertFails { fixed.access(3,0x1000,2,true,7) }
        val image=bus(region(DiscoveryStrategy.IMAGE,fill=0x80)); assertEquals(0x80808080L,image.access(1,0x1000,4,false))
        assertFails { image.access(2,0x1000,1,true,0) }
        assertFails { bus(region(DiscoveryStrategy.REFUSE)).access(1,0x1000,4,false) }
        assertFails { DiscoveryRegion("a","b",0x1000,4,DiscoveryStrategy.RETAINED,setOf(4),4,true) }
        assertFails { DiscoveryBus(listOf(region(),region(base=0x1010)),emptyList(),fixed.journal,Context()) }
    }
    @Test fun explicitExportBudgetKeepsDefaultAndChecksFourAndHalfGiBBoundary() {
        fun journal(disk: Long, capacity: Int = 1) = DiscoveryJournal("run-test", "1", "a".repeat(64), "b".repeat(64), "c".repeat(64),
            capacity = capacity, diskBudgetBytes = disk)
        val defaults = DiscoveryJournal("run-test", "1", "a".repeat(64), "b".repeat(64), "c".repeat(64), capacity = 1)
        assertEquals(32L * 1024 * 1024, defaults.diskBudgetBytes)
        for (disk in listOf(4096L, 536870912L, 1073741824L, 1610612736L, 1610612737L, 2684354560L, 2684354561L, 4831838208L)) {
            val accepted = journal(disk)
            assertEquals(disk, accepted.diskBudgetBytes)
            assertEquals(1, accepted.capacity)
            assertEquals(0, accepted.count)
            assertEquals(0L, accepted.attempts)
        }
        val selected = journal(4831838208L, 2228224)
        assertEquals(2228224, selected.capacity)
        assertEquals(4831838208L, selected.diskBudgetBytes)
        assertEquals(0, selected.count)
        assertEquals(0L, selected.attempts)
        assertFailsWith<IllegalArgumentException>{journal(4096L,2228225)}
        for (disk in listOf(4095L, 4831838209L)) {
            assertFailsWith<IllegalArgumentException> { journal(disk) }
        }
    }
    @Test fun diskBudgetAndClosedModelFailuresAreExplicitAndDumpDoesNotConsume() {
        val b=bus(disk=4096)
        repeat(6) { b.access(it.toLong(),0x1000,4,false) }
        val before=b.snapshot(); val root=Files.createTempDirectory("discovery-export")
        try {
            assertFails { b.journal.export(root.resolve("oversized.json"),b.regions) }
            assertFalse(Files.exists(root.resolve("oversized.json"))); assertEquals(before,b.snapshot())
            val tiny=bus(); tiny.access(1,0x1000,4,false)
            val prior=tiny.snapshot(); tiny.journal.export(root.resolve("view.json"),tiny.regions)
            val bytes=Files.readAllBytes(root.resolve("view.json")); assertFalse(bytes.contains(13)); assertEquals(10,bytes.last().toInt())
            assertEquals(prior,tiny.snapshot()); tiny.close(); assertFails { tiny.access(2,0x1000,4,false) }
            assertEquals("closed",(accesses(tiny).last() as Map<*,*>)["reason"])
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun streamingExportPreservesBytesAndRejectsOneByteOverBudgetWithoutPublishing() {
        val value=mapOf<String,Any?>("sample" to "中".repeat(1500),"missing" to null,"values" to listOf(1,2,3))
        val expected=Formats.jsonBytes(value)
        fun journal(limit:Long)=DiscoveryJournal("run-test","1","a".repeat(64),"b".repeat(64),"c".repeat(64),capacity=1,diskBudgetBytes=limit)
        val root=Files.createTempDirectory("bounded-stream-export")
        try {
            val result=journal(expected.size.toLong()).export(root.resolve("exact.json"),emptyList(),value)
            assertContentEquals(expected,Files.readAllBytes(root.resolve("exact.json")))
            assertEquals(expected.size.toLong(),result["bytes"])
            assertFails{journal(expected.size.toLong()-1).export(root.resolve("too-large.json"),emptyList(),value)}
            assertFalse(Files.exists(root.resolve("too-large.json")))
            Files.list(root).use { assertEquals(listOf("exact.json"),it.map{file->file.fileName.toString()}.toList()) }
        } finally { root.toFile().deleteRecursively() }
    }
}
