package com.github.nyabanban.fuji.emulator.firmware

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Selected-DAT status address/read/masked-write contract. Physical W1C/IRQ/reset laws remain unknown. */
object SerialStatusContracts {
 const val PROFILE="gfx100s-serial-status-functional-experimental-v2"
 fun port(channel:Int):String {require(channel in 0..7);return "soc-serial-engine.status-"+channel}
 fun windows():List<DeviceWindow> =(0..7).map{ch->DeviceWindow("soc-serial-engine",0xfff3000cL+(if(ch>4)4096 else 0)+(if(ch>4)ch-5 else ch)*32,4,port(ch),true)}
 private fun hash(bytes:ByteArray)=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
 fun observe(app:ByteArray):Map<String,Any> {
  require(hash(app)==SerialSharedContracts.SECTION_SHA){"serial status selected section identity differs"}
  val data=ByteBuffer.wrap(app).order(ByteOrder.LITTLE_ENDIAN)
  fun word(pc:Int)=data.getInt(pc-0x1021000).toLong()and 0xffffffffL
  fun immediate(pc:Int):Long {val w=word(pc);return Integer.rotateRight((w and 255).toInt(),((w ushr 8)and 15).toInt()*2).toLong()and 0xffffffffL}
  fun branch(pc:Int):Long {val w=word(pc);require(w ushr 28==14L&&w and 0x0f000000L==0x0b000000L);return(pc.toLong()+8+((w.toInt()shl 8)shr 6))and 0xffffffffL}
  val extents=listOf(Triple(0x11f989c,104,"b0f5f6b2493cd454ef9ba8f49eba59691c6df34bb1c7d8c9da1dd5aa5da89fa3"),Triple(0x11f9904,140,"d0e2e3ee334bd0ca7eb88773e44e44c9d3ae42487a7c0a1f17f779b04af8989d"),Triple(0x11f9990,32,"cb140a8f411b25d3bb9a11503691ec61e19ba9a0e716eac99551846096febeda"),Triple(0x11f91f4,52,"fd8a58e23fad3e78422db69717de289b7bf180bdd40640bb237ed9584a399a2c"))
  for((entry,size,sha)in extents)require(hash(app.copyOfRange(entry-0x1021000,entry-0x1021000+size))==sha){"serial status full function differs"}
  require(word(0x11f98b4)==0xe3500007L&&word(0x11f98c0)==0xe3500004L&&word(0x11f98c4)==0x93a05000L&&word(0x11f98c8)==0x83a05001L&&word(0x11f98d4)==0xe0800385L&&word(0x11f98d8)==0xe3a0300cL&&word(0x11f98dc)==0xe34f3ff3L&&word(0x11f98e0)==0xe0830280L)
  require(word(0x11f91fc)==0xe3500001L&&word(0x11f9208)==0xe3510004L&&word(0x11f9214)==0x12410005L&&word(0x11f9950)==0xe7df3813L&&word(0x11f9958)==0xe0055003L)
  val maximum=immediate(0x11f98b4).toInt();val threshold=immediate(0x11f98c0).toInt();val groupShift=((word(0x11f98d4)ushr 7)and 31).toInt();val localShift=((word(0x11f98e0)ushr 7)and 31).toInt()
  val movt=word(0x11f98dc);val high=((movt ushr 4)and 0xf000)or(movt and 0xfff);val base=(high shl 16)or immediate(0x11f98d8)
  val addresses=(0..maximum).map{channel->base+(if(channel>threshold)1L shl(groupShift+localShift)else 0L)+(if(channel>threshold)channel-5 else channel)*(1L shl localShift)}
  val read=branch(0x11f98e4);val write=branch(0x11f9980);require(maximum==7&&threshold==4&&groupShift==7&&localShift==5&&read==0x5afd8L&&write==0x5af90L&&addresses==windows().map{it.address})
  return mapOf("matches" to true,"section4Sha256" to hash(app),"functions" to extents.map{(entry,size,sha)->mapOf("entry" to entry,"bytes" to size,"sha256" to sha)},"channelCount" to 8,"firstGroupMaximumChannel" to threshold,"statusBase" to base,"groupStride" to (1L shl(groupShift+localShift)),"localStride" to (1L shl localShift),"maskedWriteDomain" to 0x10001L,"readHelper" to read,"writeHelper" to write,"windows" to windows().map{mapOf("port" to it.port,"address" to it.address,"size" to it.size)},"basis" to "DAT complete getter/cleanup/wrapper/local-index functions and actual helper/address fields. Finite supplied samples are explicit HYP, not physical W1C, reset, post-write zero, FIFO, ready, IRQ or DMA completion.")
 }
 fun channels(state:Map<String,Any?>):List<Int> {
  require(state["profile"]in listOf(PROFILE,SerialTransmitContracts.PROFILE)){"serial status profile differs"}
  val raw=state["statusChannels"];require(raw is List<*>&&raw.isNotEmpty()&&raw.size<=8&&raw.all{it is Int&&it in 0..7}){"serial status selected channel state differs"};val indices=raw.map{it as Int};require(indices==indices.distinct().sorted())
  val plans=state["statusPlans"];require(plans is Map<*,*>&&plans.keys==indices.map(Int::toString).toSet()){"serial status plan keys differ"}
  for(value in plans.values){require(value is List<*>&&value.size in 1..64);for(transaction in value){require(transaction is List<*>&&transaction.size in 1..2&&transaction.all{it==null||((it is Byte||it is Short||it is Int||it is Long)&&(it as Number).toLong()in 0..0xffffffffL)}){"serial status sample domain differs"}}}
  val states=state["statusStates"];require(states is Map<*,*>&&states.keys==plans.keys)
  for(value in states.values){require(value is Map<*,*>&&value["transactionIndex"]==0&&value["sampleIndex"]==0&&value["exhausted"]==false&&value["pendingWrite"]==false&&value["readCount"]==0L&&value["writeCount"]==0L&&value["accessCount"]==0L&&value["accessCapacity"]==64&&value["accessLogTruncated"]==false&&value["accesses"]==emptyList<Any>()){"serial status requires unconsumed startup state"}}
  return indices
 }
}
