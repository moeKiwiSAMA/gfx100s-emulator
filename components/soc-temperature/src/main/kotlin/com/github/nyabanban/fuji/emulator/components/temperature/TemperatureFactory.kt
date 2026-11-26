package com.github.nyabanban.fuji.emulator.components.temperature

import com.github.nyabanban.fuji.emulator.api.*

class TemperatureFactory:ComponentFactory {
    companion object {
        const val PROFILE="gfx100s-temperature-functional-experimental-v1"
        private val DESCRIPTOR=ComponentDescriptor("soc-temperature","0.0.1",settings=listOf(
            Setting("profile",ValueKind.TEXT,"disabled",choices=listOf("disabled",PROFILE),live=false),
            Setting("inputFile",ValueKind.TEXT,"",live=false),Setting("samplePort",ValueKind.TEXT,"",live=false)))
        private val SNAPSHOT_KEYS=setOf("schema","componentId","scalars","enabled","initialWords","registers")
        private fun snapshotLong(value:Any?,field:String,minimum:Long=Long.MIN_VALUE,maximum:Long=Long.MAX_VALUE):Long {
            require(value is Byte||value is Short||value is Int||value is Long){"Snapshot field $field requires integer"}
            val converted=(value as? Number)?.toLong()?:throw IllegalArgumentException("Snapshot field $field requires integer")
            require(converted in minimum..maximum){"Snapshot field $field out of range"}
            return converted
        }
        private fun snapshotInt(value:Any?,field:String):Int =
            snapshotLong(value,field,Int.MIN_VALUE.toLong(),Int.MAX_VALUE.toLong()).toInt()
        private fun snapshotBoolean(value:Any?,field:String):Boolean =
            value as? Boolean?:throw IllegalArgumentException("Snapshot field $field requires boolean")
        private fun snapshotMap(value:Any?,field:String):Map<*,*> {
            val map=value as? Map<*,*>?:throw IllegalArgumentException("Snapshot field $field requires map")
            require(map.keys.all{key->key is String}){"Snapshot field $field requires string keys"}
            return map
        }
    }
    override val descriptor=DESCRIPTOR
    override fun create(context:ComponentContext,config:Map<String,Any>):HardwareComponent=Controller(context,config)
    private class Controller(context:ComponentContext,config:Map<String,Any>):ScalarComponent("soc-temperature",context,config),StatefulComponent {
        private var closed=false
        private val active=config.getValue("profile")==PROFILE
        private val bank:TemperatureBank?
        private val initial:Map<String,Long>
        private val ports=listOf("soc-temperature.common-control","soc-temperature.common-gate","soc-temperature.converter")
        init {
            val file=config.getValue("inputFile")as String;val samplePort=config.getValue("samplePort")as String
            require(active==file.isNotBlank() &&(active||samplePort.isEmpty())) {"temperature profile/input/source selection mismatch"}
            if(active) {
                val data=context.readData(file);require(data.keys==setOf("schema","profile","conversionTicks","initialWords")&&data["profile"]==PROFILE)
                fun integer(v:Any?,min:Long,max:Long):Long {require(v is Byte||v is Short||v is Int||v is Long){"exact temperature input integer required"};return(v as Number).toLong().also{require(it in min..max)}}
                require(integer(data["schema"],1,1)==1L)
                val supplied=data["initialWords"];require(supplied is Map<*,*> && supplied.keys==TemperatureBank.addresses.map{it.toString(16)}.toSet()) {"complete exact temperature reset words required"}
                initial=TemperatureBank.addresses.associate{address->address.toString(16)to integer(supplied[address.toString(16)],0,0xffffffffL)}
                val current=TemperatureBank(integer(data["conversionTicks"],1,Long.MAX_VALUE/8),TemperatureBank.addresses.map{initial.getValue(it.toString(16))}.toLongArray());bank=current
                ports.forEachIndexed{part,name->context.ports.register(id,name,MmioDevice::class.java,if(part==2)object:TickAwareMmioDevice {
                    override val clockOrder=8 // Explicit HYP: timer0/SPI3/ADC4/I2C5-6/flash7 before temperature8, DMA10 after.
                    override fun nextDeadline()=current.nextDeadline()
                    override fun nextInterestedTick()=current.nextDeadline() ?: Long.MAX_VALUE
                    override fun beforeInstruction(tick:Long)=current.advance(tick)
                    override fun read(offset:Long,size:Int)=current.read(index(part,offset,size),context.clock.ticks())
                    override fun write(offset:Long,size:Int,value:Long)=current.write(index(part,offset,size),value,context.clock.ticks())
                }else object:MmioDevice {
                    override val clockOrder=80+part
                    override fun read(offset:Long,size:Int)=current.read(index(part,offset,size),context.clock.ticks())
                    override fun write(offset:Long,size:Int,value:Long)=current.write(index(part,offset,size),value,context.clock.ticks())
                })}
            }else{bank=null;initial=emptyMap()}
        }
        private fun index(part:Int,offset:Long,size:Int):Int {
            require(size==4&&offset>=0&&offset%4==0L) {"temperature requires exact aligned32 word"}
            if(part<2){require(offset==0L);return part}
            val i=TemperatureBank.offsets.indexOf(offset.toInt());require(i>=0&&offset<=64) {"temperature offset unqualified"};return i+2
        }
        override fun start(){check(!closed){"temperature controller closed"};if(active){val name=values.getValue("samplePort")as String;bank!!.source=if(name.isEmpty())null else checkNotNull(context.ports.find(name,TemperatureSamplePort::class.java)){"temperature sample port missing"}}}
        override fun close(){closed=true;bank?.close()}
        override fun additionalMmioPorts()=if(active)ports else emptyList()
        override fun state():Map<String,Any?> = super.state()+mapOf("enabled" to active,"initialWords" to initial,"registers" to bank?.state(),"basis" to "Selected DAT exact word domains; explicit reset, conversionTicks*2^divider, common-word retention, sample-at-observed-completion and signed12 threshold truncation are functional HYP. Comparison IRQ unmask and holes refuse. No calibrated physical temperature, physical clock/reset/IRQ or menu claim")

        override fun saveSnapshot():Map<String,Any?> {
            check(!closed){"temperature controller closed"}
            return linkedMapOf(
                "schema" to snapshotSchemaVersion,
                "componentId" to id,
                "scalars" to saveScalarValues(),
                "enabled" to active,
                "initialWords" to initial,
                "registers" to bank?.saveSnapshot(),
            )
        }

        override fun restoreSnapshot(data:Map<String,Any?>) {
            check(!closed){"temperature controller closed"}
            require(data.keys==SNAPSHOT_KEYS){"Unexpected snapshot keys for $id"}
            require(snapshotInt(data["schema"],"schema")==snapshotSchemaVersion){"Unsupported snapshot schema for $id"}
            require(data["componentId"]==id){"Snapshot componentId mismatch for $id"}
            val scalarsRaw=snapshotMap(data["scalars"],"scalars")
            require(scalarsRaw.keys==DESCRIPTOR.settings.map{setting->setting.key}.toSet()){"Scalar setting keys differ in snapshot for $id"}
            val previousValues=values
            val validatedBank:TemperatureBank.ValidatedSnapshot?
            try {
                restoreScalarValues(scalarsRaw,DESCRIPTOR)
                require(snapshotBoolean(data["enabled"],"enabled")==active){"Snapshot enabled flag differs for $id"}
                val initialMap=snapshotMap(data["initialWords"],"initialWords")
                require(initialMap.keys==initial.keys){"Snapshot initialWords keys differ for $id"}
                for((addressKey,expectedWord)in initial) {
                    require(snapshotLong(initialMap[addressKey],"initialWords.$addressKey",0L,0xffffffffL)==expectedWord) {
                        "Snapshot initialWords.$addressKey differs from live configuration"
                    }
                }
                require((bank!=null)==(data["registers"]!=null)){"Snapshot registers presence mismatch for $id"}
                validatedBank=bank?.validateSnapshot(data["registers"])
            } catch(failure:Throwable) {
                values=previousValues
                throw failure
            }
            if(bank!=null&&validatedBank!=null)bank.applySnapshot(validatedBank)
        }
    }
}
