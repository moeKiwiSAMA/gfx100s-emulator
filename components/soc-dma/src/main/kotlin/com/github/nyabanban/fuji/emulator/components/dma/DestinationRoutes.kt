package com.github.nyabanban.fuji.emulator.components.dma

import com.github.nyabanban.fuji.emulator.api.*
import java.util.IdentityHashMap

/** Named owner-thread routes, finite immutable endpoint claims. No hardware/JAR-order priority. */
internal class DestinationRoutes(private val context:ComponentContext,legacy:String,raw:Map<String,Any?>) {
 companion object {const val PROFILE="named-destination-ports-experimental-v1"}
 data class Binding(val name:String,val peer:DmaDestinationPeer)
 private val names:List<String>
 private var resolved:List<Pair<Binding,List<AddressWindow>>>?=null
 init {
  require(raw.keys==setOf("schema","profile","ports")&&(raw["schema"] is Byte||raw["schema"] is Short||raw["schema"] is Int||raw["schema"] is Long)&&(raw["schema"] as Number).toLong()==1L&&raw["profile"]==PROFILE){"DMA destination route schema/profile differs"}
  val ports=raw["ports"];require(ports is List<*>&&ports.size in 1..16&&ports.all{it is String&&it.matches(Regex("[a-z][a-z0-9._-]{0,127}"))}){"DMA destination names must be1..16 portable port identifiers"}
  names=(if(legacy.isEmpty())emptyList()else listOf(legacy))+ports.map{it as String};require(names.size<=16&&names.distinct().size==names.size){"duplicate or excessive DMA destination route"}
 }
 private fun bindings():List<Pair<Binding,List<AddressWindow>>> {
  resolved?.let{return it}
  val instances=IdentityHashMap<DmaDestinationPeer,Boolean>();val ranges=mutableListOf<AddressWindow>()
  val all=names.map{name->val peer=context.ports.find(name,DmaDestinationPeer::class.java)?:error("DMA destination peer missing: "+name);require(instances.put(peer,true)==null){"DMA destination aliases same peer"};require(peer is RangedDmaDestinationPeer){"multiple DMA destinations require finite immutable endpoint claims"};val windows=peer.destinationRanges().toList();require(windows.size in 1..8)
   for(window in windows){require(window.base in 0..0xffffffffL&&window.size in 1..0x1_0000_0000L&&window.base<=0x1_0000_0000L-window.size){"invalid DMA destination endpoint range"};require(ranges.none{it.base<window.base+window.size&&window.base<it.base+it.size}){"overlapping DMA destination endpoint claims"};ranges.add(window)}
   Binding(name,peer) to windows
  }
  resolved=all;return all
 }
 fun select(address:Long):Binding? {
  require(address in 0..0xffffffffL)
  var selected:Binding?=null
  for((binding,ranges)in bindings()) {
   val covered=ranges.any{address>=it.base&&address-it.base<it.size};val matches=binding.peer.matches(address)
   require(matches==covered){"DMA destination match differs from immutable range claim"}
   if(matches){check(selected==null){"ambiguous DMA destination"};selected=binding}
  }
  return selected
 }
}
