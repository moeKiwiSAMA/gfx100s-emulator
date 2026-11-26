package com.github.nyabanban.fuji.emulator.components.temperature

import com.github.nyabanban.fuji.emulator.api.TemperatureSamplePort

/** DAT field domains, explicitly hypothesized reset/latency/ready law. Unsupported IRQ unmask stays strict. */
internal class TemperatureBank(private val conversionTicks:Long,initial:LongArray) {
    companion object {
        val offsets=intArrayOf(0,4,8,16,20,24,32,36,48,52,56,60,64)
        val addresses=longArrayOf(0xfff4700cL,0xfff47028L)+offsets.map{0xfff47810L+it}
        private val SNAPSHOT_KEYS=setOf("conversionTicks","words","deadline","completedAt","conversionCount","lastTick")
        private fun snapshotLong(value:Any?,field:String,minimum:Long=Long.MIN_VALUE,maximum:Long=Long.MAX_VALUE):Long {
            require(value is Byte||value is Short||value is Int||value is Long){"Snapshot field $field requires integer"}
            val converted=(value as? Number)?.toLong()?:throw IllegalArgumentException("Snapshot field $field requires integer")
            require(converted in minimum..maximum){"Snapshot field $field out of range"}
            return converted
        }
        private fun snapshotMap(value:Any?,field:String):Map<*,*> {
            val map=value as? Map<*,*>?:throw IllegalArgumentException("Snapshot field $field requires map")
            require(map.keys.all{key->key is String}){"Snapshot field $field requires string keys"}
            return map
        }
    }
    internal class ValidatedSnapshot(
        val words:LongArray,
        val deadline:Long?,
        val completedAt:Long?,
        val count:Long,
        val lastTick:Long,
    )
    private val words=initial.copyOf()
    private var deadline:Long?=null
    private var completedAt:Long?=null
    private var count=0L
    private var closed=false
    private var lastTick=0L
    init {
        require(conversionTicks in 1..Long.MAX_VALUE/8 && words.size==15)
        words.indices.forEach{validate(it,words[it],initial=true)}
        require(words[2]==0L&&words[3]==0L) {"enabled/start reset must be explicitly idle in this profile"}
    }
    var source:TemperatureSamplePort?=null
    private fun live(tick:Long){check(!closed){"temperature controller closed"};require(tick>=lastTick){"temperature clock moved backwards"}}
    private fun validate(index:Int,value:Long,initial:Boolean=false) {
        require(value in 0..0xffffffffL)
        when(index) {
            0,2,3,8 -> require(value<=1)
            1 -> Unit // DAT read/modify/write preserves unrelated explicit bits in common word.
            4 -> require(value<=3)
            5,6 -> require(value<=15)
            7 -> require(value==15L) {"temperature comparison IRQ unmask is unqualified"}
            9 -> require(value<=4095)
            in 10..13 -> require(value<=4095||value>=0xfffff800L) {"temperature threshold must be canonical signed12 or unsigned12"}
            14 -> require(value<=255)
            else -> error("temperature register index unqualified")
        }
        if(initial&&index in 10..13)require(value<=4095)
    }
    fun nextDeadline():Long? {check(!closed){"temperature controller closed"};return deadline}
    fun advance(tick:Long) {
        live(tick);val at=deadline?:return
        if(tick<at)return
        val raw=checkNotNull(source){"temperature sample source missing"}.sampleRaw()
        require(raw in 0..4095) {"temperature conversion requires explicit raw sample"}
        check(count<Long.MAX_VALUE)
        words[8]=1;words[9]=raw.toLong();deadline=null;completedAt=at;count++;lastTick=tick
    }
    fun read(index:Int,tick:Long):Long {
        advance(tick)
        require(index in words.indices)
        // GFX100S thermal sensor runs continuously once enabled & started at boot (DAT 0x012ae8e0):
        // after initial conversion completes (`ready==1 && deadline==null`), subsequent result reads
        // at a strictly later tick (`tick > lastTick`) sample the live source so runtime thermal updates
        // (`95°C` warning / `100°C` shutdown) take effect on each 15 s OS poll while keeping same-tick
        // and in-flight strict conversion semantics unchanged.
        if(index==9&&words[2]==1L&&words[3]==1L&&words[8]==1L&&deadline==null&&tick>lastTick) {
            val activeSource=source
            if(activeSource!=null) {
                val raw=activeSource.sampleRaw()
                require(raw in 0..4095) {"temperature conversion requires explicit raw sample"}
                words[9]=raw.toLong()
                lastTick=tick
            }
        }
        return words[index]
    }
    fun write(index:Int,value:Long,tick:Long) {
        live(tick);require(index in words.indices);validate(index,value)
        require(index !in listOf(5,8,9)) {"temperature write to read-only status/ready/result"}
        if(index==4)require(deadline==null||value==words[index]) {"temperature divider change while pending is unqualified"}
        // Preflight candidate deadline before register or ready mutation, including overflow.
        val nextDeadline=if(index==2||index==3) {
            val enable=if(index==2)value else words[2];val start=if(index==3)value else words[3]
            if(enable==1L&&start==1L)Math.addExact(tick,Math.multiplyExact(conversionTicks,1L shl words[4].toInt()))else null
        }else deadline
        advance(tick)
        if(index==6)words[5]=words[5]and value.inv()else words[index]=if(index in 10..13)value and 4095 else value
        if(index==2||index==3){deadline=nextDeadline;words[8]=0;completedAt=null}
        lastTick=tick
    }
    fun close(){closed=true;source=null}
    fun state():Map<String,Any?> = mapOf("closed" to closed,"words" to addresses.indices.associate{addresses[it].toString(16) to words[it]},"deadline" to deadline,"completedAt" to completedAt,"conversionCount" to count,"lastTick" to lastTick,"conversionTicks" to conversionTicks)

    fun saveSnapshot():Map<String,Any?> {
        check(!closed){"temperature controller closed"}
        return linkedMapOf(
            "conversionTicks" to conversionTicks,
            "words" to addresses.indices.associate{slot->addresses[slot].toString(16) to words[slot]},
            "deadline" to deadline,
            "completedAt" to completedAt,
            "conversionCount" to count,
            "lastTick" to lastTick,
        )
    }

    fun validateSnapshot(raw:Any?):ValidatedSnapshot {
        check(!closed){"temperature controller closed"}
        val section=snapshotMap(raw,"registers")
        require(section.keys==SNAPSHOT_KEYS){"Unexpected temperature registers snapshot keys"}
        require(snapshotLong(section["conversionTicks"],"registers.conversionTicks",1L,Long.MAX_VALUE/8)==conversionTicks) {
            "Temperature conversionTicks differ from live configuration"
        }
        val wordsMap=snapshotMap(section["words"],"registers.words")
        require(wordsMap.keys==addresses.map{address->address.toString(16)}.toSet()){"Temperature snapshot words keys mismatch"}
        val nextWords=LongArray(addresses.size){slot->
            val hexKey=addresses[slot].toString(16)
            val wordValue=snapshotLong(wordsMap[hexKey],"registers.words.$hexKey",0L,0xffffffffL)
            validate(slot,wordValue,initial=true)
            wordValue
        }
        val nextDeadline=section["deadline"]?.let{rawDeadline->snapshotLong(rawDeadline,"registers.deadline",1L,Long.MAX_VALUE)}
        val nextCompletedAt=section["completedAt"]?.let{rawCompleted->snapshotLong(rawCompleted,"registers.completedAt",1L,Long.MAX_VALUE)}
        val nextCount=snapshotLong(section["conversionCount"],"registers.conversionCount",0L,Long.MAX_VALUE)
        val nextLastTick=snapshotLong(section["lastTick"],"registers.lastTick",0L,Long.MAX_VALUE)
        if(nextDeadline!=null) {
            require(nextWords[2]==1L&&nextWords[3]==1L&&nextWords[8]==0L&&nextCompletedAt==null) {
                "Pending temperature conversion requires enable==1, start==1, ready==0, completedAt==null"
            }
        }
        if(nextCompletedAt!=null) {
            require(nextDeadline==null&&nextWords[8]==1L&&nextCount>=1L&&nextLastTick>=nextCompletedAt) {
                "Completed temperature conversion metadata inconsistent"
            }
        }
        return ValidatedSnapshot(nextWords,nextDeadline,nextCompletedAt,nextCount,nextLastTick)
    }

    fun applySnapshot(validated:ValidatedSnapshot) {
        check(!closed){"temperature controller closed"}
        validated.words.copyInto(words)
        deadline=validated.deadline
        completedAt=validated.completedAt
        count=validated.count
        lastTick=validated.lastTick
    }
}
