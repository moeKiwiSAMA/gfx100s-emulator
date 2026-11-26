package com.github.nyabanban.fuji.emulator.firmware

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

object LoaderDevicesContracts {
    /** FW-013: actual selected DAT address and mask tables, compared to component-port geometry. */
    fun retainedBanks(loader: ByteArray,windows: List<DeviceWindow>,masksA: List<Long>,masksB: List<Long>): Map<String,Any> {
        require(masksA.size==16 && masksB.size==15)
        val a=windows.single { it.port=="soc-system-control.a" }; val b=windows.single { it.port=="soc-system-control.b" }; val extra=windows.single { it.port=="soc-system-control.b-extra" }
        require(windows.count { it.port in listOf(a.port,b.port,extra.port) }==3 && a.size==64L && b.size==56L && extra.size==4L)
        val code=ByteBuffer.wrap(loader).order(ByteOrder.LITTLE_ENDIAN)
        fun word(at: Int)=code.getInt(at).toLong() and 0xffffffffL
        val banks=listOf(Triple("A",0xae18,0xaed0),Triple("B",0xae58,0xae94)).map { (name,addresses,masks) ->
            val expected=if(name=="A") masksA else masksB
            val rows=expected.indices.map { i ->
                val address=if(name=="A") a.address+i*4 else if(i<14) b.address+i*4 else extra.address
                require(word(addresses+i*4)==address && word(masks+i*4)==expected[i]) { "DAT retained bank $name word $i differs from component/metadata" }
                mapOf("address" to address,"mask" to expected[i])
            }
            fun hash(at: Int)=MessageDigest.getInstance("SHA-256").digest(loader.copyOfRange(at,at+expected.size*4)).joinToString("") { "%02x".format(it) }
            mapOf("bank" to name,"addressTable" to addresses,"maskTable" to masks,"words" to rows,"addressTableSha256" to hash(addresses),"maskTableSha256" to hash(masks))
        }
        return mapOf("matches" to true,"wordsChecked" to 31,"banks" to banks,"limitation" to "DAT addresses/masks only; SOURCE retention and zero reset are HYPOTHESIS, no physical clock/reset measurement")
    }
}
