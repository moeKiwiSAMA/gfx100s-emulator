package com.github.nyabanban.fuji.emulator.firmware

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** DAT control/data/request geometry. Acceptance delay/capture are functional HYP, never a physical completion or frame. */
object SerialTransmitContracts {
 const val PROFILE="gfx100s-serial-transmit-functional-experimental-v3"
 val channels=listOf(1,2,4,6)
 fun request(channel:Int)=when(channel){1->21;2->22;4->26;6->25;else->error("serial transmit channel unqualified")}
 fun controlPort(channel:Int)="soc-serial-engine.control-"+channel.also{require(it in 0..7)}
 fun dataPort(channel:Int)="soc-serial-engine.data-"+channel.also{require(it in 0..7)}
 fun peerPort(channel:Int)="soc-serial-engine.tx-"+channel.also{require(it in channels)}
 fun windows():List<DeviceWindow> = (0..7).map{DeviceWindow("soc-serial-engine",address(it,0),4,controlPort(it),true)}+(0..7).map{DeviceWindow("soc-serial-engine",address(it,4),4,dataPort(it),true)}
 private fun address(channel:Int,offset:Int)=0xfff30000L+(if(channel>4)4096 else 0)+(if(channel>4)channel-5 else channel)*32+offset
 private fun hash(bytes:ByteArray)=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
 fun observe(app:ByteArray):Map<String,Any> {
  require(hash(app)==SerialSharedContracts.SECTION_SHA){"serial transmit section differs"};val data=ByteBuffer.wrap(app).order(ByteOrder.LITTLE_ENDIAN)
  fun word(pc:Int)=data.getInt(pc-0x1021000).toLong()and 0xffffffffL
  fun immediate(pc:Int):Long {val w=word(pc);return Integer.rotateRight((w and 255).toInt(),((w ushr 8)and 15).toInt()*2).toLong()and 0xffffffffL}
  fun branch(pc:Int):Long {val w=word(pc);require(w ushr 28==14L&&w and 0x0f000000L==0x0b000000L);return(pc.toLong()+8+((w.toInt()shl 8)shr 6))and 0xffffffffL}
  val functions=listOf(Triple(0x11f9268,80,"acba6e360a05bad76d56dfb7c6439b68ee1edb28db6c77ab23ff8e5fdacbce82"),Triple(0x11f99b0,68,"73069bc32e5b9f6726ec72e23a1f9a0e70fa0c73f4af610d746605dc9fc825bc"),Triple(0x11f8fc4,264,"3db72413b3623d72f0c8cbc51dfe5130ea89bbfb1cb3e3480c10b81d5bae249e"),Triple(0x11e5b44,468,"d380f5b71358192b00ad3f00fbb7eb85f72a314b45a924ae2f07e42e2a900433"))
  functions.forEach{(entry,size,sha)->require(hash(app.copyOfRange(entry-0x1021000,entry-0x1021000+size))==sha){"serial transmit full function differs"}}
  require(word(0x11f9290)==0xe2843affL&&word(0x11f9294)==0xe2833ef3L&&word(0x11f9298)==0xe0800383L&&word(0x11f929c)==0xe3a01000L&&word(0x11f92a0)==0xe1a00280L&&word(0x11f99dc)==0xe3a03004L&&word(0x11f99e0)==0xe34f3ff3L)
  val groupShift=((word(0x11f9298)ushr 7)and 31).toInt();val strideShift=((word(0x11f92a0)ushr 7)and 31).toInt();val base=immediate(0x11f9290)+immediate(0x11f9294)
  val controls=(0..7).map{ch->(((if(ch>4)ch-5 else ch).toLong()+((base+if(ch>4)1 else 0)shl groupShift))shl strideShift)and 0xffffffffL}
  require(controls==windows().take(8).map{it.address}&&branch(0x11f92a4)==0x5af90L&&branch(0x11f907c)==0x11f9268L&&branch(0x11f90ac)==0x11e48e0L&&word(0x11f908c)==0xe3a03000L&&word(0x11f9090)==0xe5213008L)
  val indices=listOf(25,26,28,27);val records=channels.zip(indices).map{(channel,index)->val at=0x15a7fe4-0x1021000+index*12;val selector=app[at+1].toInt()and 255;require((app[at].toInt()and 255)==16&&selector==request(channel)&&(app[at+2].toInt()and 255)==2);mapOf("channel" to channel,"softwareRequestIndex" to index,"selector" to selector,"tableAddress" to 0x15a7fe4+index*12,"dataAddress" to address(channel,4))}
  return mapOf("matches" to true,"functions" to functions.map{(entry,size,sha)->mapOf("entry" to entry,"bytes" to size,"sha256" to sha)},"controlWord" to 0L,"widthBytes" to 4,"requestRecords" to records,"windows" to windows().map{mapOf("port" to it.port,"address" to it.address,"size" to it.size)},"dmaStartHelper" to 0x11e48e0L,"dmaCallbackWord" to 0L,"basis" to "DAT zero control, exact data addresses and request selectors; real driver stores a zero DMA callback word separately from its serial callback. No physical reset/start/ready/IRQ/scanout law; finite delay/lease/capture are explicit functional HYP.")
 }
 fun selected(state:Map<String,Any?>):List<Int> {
  require(state["profile"]==PROFILE);val raw=state["transmitChannels"];require(raw is List<*>&&raw.isNotEmpty()&&raw.size<=4&&raw.all{it is Int&&it in channels});val selected=raw.map{it as Int};require(selected==selected.distinct().sorted())
  val status=state["statusChannels"]as? List<*>?:error("serial transmit status selection missing");require(status.containsAll(selected))
  val plans=state["transmitPlans"];require(plans is Map<*,*>&&plans.keys==selected.map(Int::toString).toSet());for(value in plans.values){require(value is List<*>&&value.size in 1..64);for(row in value){require(row is Map<*,*>&&row.keys==setOf("byteCount","acceptDelayTicks"));val size=row["byteCount"];require(size is Byte||size is Short||size is Int||size is Long);val length=(size as Number).toLong();require(length in 4..262144L&&length%4L==0L);val delay=row["acceptDelayTicks"];require(delay==null||((delay is Byte||delay is Short||delay is Int||delay is Long)&&(delay as Number).toLong()>=0))}}
  val states=state["transmitStates"];require(states is Map<*,*>&&states.keys==plans.keys);for(channel in selected){val s=states[channel.toString()];require(s is Map<*,*>&&s["request"]==request(channel)&&s["planIndex"]==0&&s["exhausted"]==false&&s["armedAt"]==null&&s["acceptAt"]==null&&s["leaseOwner"]==null&&s["bytesReceived"]==0&&s["capture"]==null&&listOf("armCount","claimCount","cancelCount","completedCount","eventCount").all{s[it]==0L}&&s["eventCapacity"]==64&&s["eventLogTruncated"]==false&&s["events"]==emptyList<Any>()){"serial transmit startup state already consumed or differs"}}
  return selected
 }
}
