package com.github.nyabanban.fuji.emulator.firmware

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Selected update-byte geometry; functional responses/reset/clock/capacity remain unmeasured. */
object DramContracts {
 const val FIRMWARE_ID="gfx100s-213"
 const val FIRMWARE_SHA="ea997915ac5accc79699392b47909bb29506f66ec8d571e5804dd1fb0408fd12"
 const val SECTION_SHA="eb5372dfda41009b969ccf148f031b3976230a6a9d249d0ea3d0588e6feab157"
 const val PROFILE="gfx100s-dram-functional-experimental-v1"
 const val COMMAND_PROFILE="gfx100s-dram-command-functional-experimental-v2"
 const val PROGRAMMING_SHA="c98dcc492448021370679b5bfab4d49194d6669069f93c29eacd4d2d2140da0d"
 const val CONFIGURATION_SHA="53408ae8622ba49a18ee44ddc04caaa1b2f112982712d1870c56777c58c48481"
 const val COMMAND_SHA="b4e3892f978b34ab7f70c4a5df7d719b3e80edfbfd7d76239cb0205259a9a30d"
 private const val BASE=0x1021000L
 val profiles=setOf(PROFILE,COMMAND_PROFILE,DramSnapshotContracts.PROFILE)
 fun ports(bank:Int):List<String> {require(bank in 0..3);return listOf("soc-dram.controller-$bank","soc-dram.phy-$bank")}
 private fun hash(bytes:ByteArray)=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
 fun observe(app:ByteArray):Map<String,Any> {
  require(hash(app)==SECTION_SHA){"DRAM selected section4 identity differs"}
  val bytes=ByteBuffer.wrap(app).order(ByteOrder.LITTLE_ENDIAN)
  fun word(pc:Long):Long {require(pc%4L==0L&&pc>=BASE&&pc-BASE<=app.size-4L);return bytes.getInt((pc-BASE).toInt()).toLong()and 0xffffffffL}
  fun wide(w:Long)=((w ushr 4)and 0xf000L)or(w and 0xfffL)
  fun address(pc:Long,register:Int):Long {val low=word(pc);val high=word(pc+4);require(low ushr 28==14L&&high ushr 28==14L&&low and 0x0ff00000L==0x03000000L&&high and 0x0ff00000L==0x03400000L&&((low ushr 12)and 15)==register.toLong()&&((high ushr 12)and 15)==register.toLong());return wide(low)or(wide(high)shl 16)}
  fun branch(pc:Long):Long {val w=word(pc);require(w ushr 28==14L&&w and 0x0f000000L==0x0b000000L);return (pc+8+((w.toInt()shl 8)shr 6))and 0xffffffffL}
  val countAddress=address(0x11fa70c,3);val programmingAddress=address(0x11fa720,5);val phyAddress=address(0x11fa730,3);val controllerAddress=address(0x11fa764,2);val pollAddress=address(0x11fa78c,4)
  require(countAddress==0x15ae84cL&&programmingAddress==0x15ad2dcL&&phyAddress==0x15b1838L&&controllerAddress==0x15b1848L&&pollAddress==0x15ae864L)
  require(word(0x11fa6f8)==0xe3500003L&&word(0x11fa738)==0xe7939100L&&word(0x11fa73c)==0xe7960184L&&word(0x11fa750)==0xe2855008L&&branch(0x11fa748)==0x5af90L&&branch(0x11fa77c)==0x5af90L)
  require(listOf(0x11fa7a0L,0x11fa7ccL,0x11fa7f8L).all{branch(it)==0x5afd8L})
  val phy=(0..3).map{word(phyAddress+it*4)};val controller=(0..3).map{word(controllerAddress+it*4)}
  require(phy==listOf(0xff600000L,0xff610000L,0xff620000L,0xff630000L)&&controller==listOf(0xff500000L,0xff510000L,0xff520000L,0xff530000L))
  fun pairs(pc:Long,count:Int):List<Pair<Long,Long>> {require(count in 1..1024);return (0 until count).map{word(pc+it*8)to word(pc+it*8+4)}}
  fun pairHash(rows:List<Pair<Long,Long>>):String {val raw=ByteBuffer.allocate(rows.size*8).order(ByteOrder.LITTLE_ENDIAN);rows.forEach{(o,v)->raw.putInt(o.toInt());raw.putInt(v.toInt())};return hash(raw.array())}
  require(word(countAddress)==686L)
  val programming=pairs(programmingAddress,686);require(programming.map{it.first}.distinct().size==686&&programming.all{it.first in 0..0xfffc&&it.first%4L==0L}&&pairHash(programming)==PROGRAMMING_SHA)
  val configuration=pairs(0x15b16cc,word(0x15b1704).toInt())+pairs(0x15b1640,word(0x15b16c8).toInt())+pairs(0x15b1708,4)
  require(configuration.size==28&&pairHash(configuration)==CONFIGURATION_SHA)
  val polls=(0..2).map{index->val at=pollAddress+index*12;mapOf("offset" to word(at),"mask" to word(at+4),"expected" to word(at+8))}
  require(polls==listOf(mapOf("offset" to 0x4670L,"mask" to 0x30000L,"expected" to 0x30000L),mapOf("offset" to 0x4674L,"mask" to 0x30003L,"expected" to 0x30003L),mapOf("offset" to 0x4678L,"mask" to 0x30003L,"expected" to 0x30003L)))
  val tableAddresses=listOf(0x11fb538L,0x11fb5acL,0x11fb5c4L,0x11fb5dcL,0x11fb5f4L).map{address(it,3)}
  require(tableAddresses==listOf(0x15b0cbcL,0x15afabcL,0x15ae8bcL,0x15af1bcL,0x15b03bcL))
  require(word(0x11fb508)==0xe3500003L&&word(0x11fb518)==0xe3510004L&&word(0x11fb51c)==0x979ff101L&&branch(0x11fb570)==0x5af90L&&branch(0x11fb594)==0x5af90L&&branch(0x11fb59c)==0x5afd8L&&word(0x11fb5a0)==0xe3500000L&&word(0x11fb5a4)==0x1afffffbL)
  val commands=ByteBuffer.allocate(11360).order(ByteOrder.LITTLE_ENDIAN)
  val commandDomains=linkedSetOf<Long>()
  val descriptors=(0..3).flatMap{bank->(0..4).map{mode->val descriptor=tableAddresses[mode]+bank*8;require(word(descriptor)==71L);val pointer=word(descriptor+4);val absolute=pairs(pointer,71);val relative=absolute.map{(a,v)->val o=a-controller[bank];require(o in 0..0xfffc&&o%4L==0L);commandDomains.add(o);commands.putInt(o.toInt());commands.putInt(v.toInt());o to v};require(relative.last()==(0x5384L to 1L));mapOf("bank" to bank,"mode" to mode,"descriptorAddress" to descriptor,"pairsAddress" to pointer,"pairCount" to 71,"relativePairsSha256" to pairHash(relative))}}
  require(hash(commands.array())==COMMAND_SHA&&commandDomains.size==71&&commandDomains.intersect(configuration.map{it.first}.toSet()+setOf(0x7c18L,0x7040L,0x7044L,0x7360L,0x7364L)).isEmpty())
  val windows=(0..3).flatMap{bank->listOf(mapOf("port" to ports(bank)[0],"address" to controller[bank],"size" to 0x10000L),mapOf("port" to ports(bank)[1],"address" to phy[bank],"size" to 0x10000L))}
  return linkedMapOf("matches" to true,"section4Sha256" to hash(app),"phyBases" to phy,"controllerBases" to controller,"windows" to windows,
   "programmingTable" to mapOf("address" to programmingAddress,"count" to 686,"stride" to 8,"minimumOffset" to programming.minOf{it.first},"maximumOffset" to programming.maxOf{it.first},"sha256" to pairHash(programming)),
   "configurationTable" to mapOf("count" to 28,"sha256" to pairHash(configuration)),"initialPolls" to polls,"commandDescriptors" to descriptors,"commandContractSha256" to hash(commands.array()),"commandWordCount" to 71,
   "basis" to "Selected GFX100S2.13 DAT address producers, bounded tables, masks and command-zero consumer. Supplied reset words, five masked response deadlines, finite command plans, whole-word command responses/counter inputs and admission remain functional HYP. No physical training, capacity, clock, reset, IRQ, ROM or menu claim.")
 }
 fun verify(meta:FirmwareMetadata,state:Map<String,Any?>,claims:List<String>,active:List<DeviceWindow>,application:()->ByteArray):Map<String,Any> {
  require(meta.id==FIRMWARE_ID&&meta.sha256==FIRMWARE_SHA){"DRAM profile is not qualified for selected firmware identity"}
  require(state["enabled"]==true&&state["profile"] in profiles){"DRAM enabled profile differs"}
  val banks=state["banks"];require(banks is Map<*,*>&&banks.isNotEmpty()&&banks.size<=4&&banks.keys.all{it is String&&it in setOf("0","1","2","3")}){"DRAM explicit selected bank state required"}
  val indices=banks.keys.map{it.toString().toInt()}.sorted()
  val snapshotProfile=state["profile"]==DramSnapshotContracts.PROFILE
  val selectedSnapshots=indices.filter{bank->
   val bankState=banks[bank.toString()];require(bankState is Map<*,*>){"DRAM selected bank state must be an object"}
   if("snapshot" !in bankState)false else {
    require(snapshotProfile&&bank in 1..3){"DRAM snapshot bank/profile prerequisite differs"}
    val snapshot=bankState["snapshot"];require(snapshot is Map<*,*>&&snapshot["bank"]==bank&&snapshot["closed"]==false){"DRAM snapshot live bank state differs"}
    true
   }
  }
  require(!snapshotProfile||selectedSnapshots.isNotEmpty()){"DRAM snapshot profile requires explicit post-initialization bank claims"}
  val expectedClaims=indices.flatMap{ports(it)+if(it in selectedSnapshots)listOf(DramSnapshotContracts.port(it))else emptyList()}
  require(claims==expectedClaims){"DRAM claims differ from selected banks"}
  val app=application();val report=observe(app)
  fun windows(items:Any?)=(items as List<*>).map{val w=it as Map<*,*>;DeviceWindow("soc-dram",w["address"]as Long,w["size"]as Long,w["port"]as String,true)}
  val original=windows(report.getValue("windows"))
  val declared=meta.devices.filter{it.id=="soc-dram"}
  // Legacy eight-window metadata remains qualified. New metadata must declare all four
  // exact optional external words, even if only a post-initialization subset is selected.
  val snapshotReport=if(snapshotProfile||declared.size!=8)DramSnapshotContracts.observe(app)else null
  val external=snapshotReport?.let{windows(it.getValue("finalExternalWrites"))}.orEmpty()
  val allExpected=if(snapshotProfile||declared.size!=8)original+external else original
  require(declared.size==allExpected.size&&declared.toSet()==allExpected.toSet()){"DRAM metadata differs from selected DAT windows"}
  val selected=allExpected.filter{it.port in expectedClaims};val actual=active.filter{it.id=="soc-dram"}
  require(actual.size==selected.size&&actual.toSet()==selected.toSet()){"DRAM activated windows differ from selected bank claims"}
  val result=report+mapOf("selectedBanks" to indices,"selectedPorts" to claims.toList(),"profile" to state.getValue("profile")!!)
  // Do not change historical v1/v2 report values merely because optional metadata grew.
  return if(snapshotProfile)result+mapOf("snapshot" to (checkNotNull(snapshotReport)+mapOf("selectedBanks" to selectedSnapshots)))else result
 }
}
