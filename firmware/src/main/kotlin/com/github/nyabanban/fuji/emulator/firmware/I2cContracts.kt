package com.github.nyabanban.fuji.emulator.firmware

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** FW-018: target STRB/LDRB/indexed producers. Two windows/extent are bounded SOURCE/HYP, not silicon count. */
object I2cContracts {
    const val CONTROLLER2_PORT = "soc-i2c.controller2"

    /** P610: selected application descriptor/index/byte-access facts, not peer or timing qualification. */
    fun observeController2(section4: ByteArray, kernel: ByteArray, windows: List<DeviceWindow>): Map<String,Any> {
        fun word(bytes: ByteArray, base: Int, address: Int): Long {
            val offset = address - base
            require(offset >= 0 && offset <= bytes.size - 4) { "I2C2 source word outside selected image" }
            return ByteBuffer.wrap(bytes,offset,4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        }
        val expected = mapOf(
            0x11e052c to 0xe30d3018L, 0x11e0530 to 0xe34f3ff3L,
            0x11e0538 to 0xe0830600L, 0x11e053c to 0xebb9ea9fL,
            0x11e05a0 to 0xe30d3008L, 0x11e05a4 to 0xe34f3ff3L,
            0x11e05a8 to 0xe0830600L, 0x11e05ac to 0xebb9ea95L,
            0x11e06d8 to 0xe5d21002L, 0x11e06dc to 0xe3510002L,
            0x11e06e0 to 0x83a00000L, 0x11e06e4 to 0x8a000002L)
        expected.forEach { (address,value) -> require(word(section4,0x1021000,address)==value) {
            "DAT I2C2 producer differs at ${address.toString(16)}"
        } }
        require(word(kernel,0x40000,0x5afc8)==0xe5c01000L && word(kernel,0x40000,0x5b010)==0xe5d00000L) {
            "DAT I2C2 kernel access must remain byte-wide"
        }
        val descriptors=listOf(14 to byteArrayOf(0x55,0,2),15 to byteArrayOf(0x1d,0,2)).map { (index,bytes) ->
            val address=0x15a7f50+3*index
            val offset=address-0x1021000
            require(offset+3<=section4.size && section4.copyOfRange(offset,offset+3).contentEquals(bytes)) {
                "DAT I2C2 descriptor $index differs"
            }
            mapOf("index" to index,"address" to address,"bytes" to bytes.map {it.toInt() and 255})
        }
        val irqTableAddress=0x15a89ac
        val irqSelectorOffset=0x15a7f34-0x1021000
        require(irqSelectorOffset+3<=section4.size && section4.copyOfRange(irqSelectorOffset,irqSelectorOffset+3).contentEquals(byteArrayOf(3,4,5))) {
            "DAT I2C IRQ selectors differ"
        }
        val irqRoutes=(0..2).map { index ->
            val selector=section4[irqSelectorOffset+index].toInt() and 255
            val address=irqTableAddress+16*selector
            val values=listOf(65L+index,0xc0L,1L,if(index==0)1L else 8L)
            values.forEachIndexed { field,value -> require(word(section4,0x1021000,address+4*field)==value) {
                "DAT I2C IRQ entry $index differs at ${(address+4*field).toString(16)}"
            } }
            mapOf("controllerIndex" to index,"selector" to selector,"address" to address,
                "irq" to values[0],"priority" to values[1],"trigger" to values[2],"targets" to values[3])
        }
        val selected=windows.single {it.port==CONTROLLER2_PORT}
        require(selected.id=="soc-i2c" && selected.optional && selected.address==0xfff3f000L && selected.size==0x20L) {
            "selected I2C2 window differs from SOURCE byte layout"
        }
        return mapOf("matches" to true,"port" to CONTROLLER2_PORT,"base" to selected.address,"size" to selected.size,
            "accessBytes" to 1,"controllerIndex" to 2,"descriptorAddress" to 0x15a7f7a,
            "descriptors" to descriptors,"irqTableAddress" to irqTableAddress,"irqRoutes" to irqRoutes,
            "checkedInstructionAddresses" to expected.keys.toList(),
            "limitation" to "DAT descriptors, firmware IRQ directory and SOURCE byte layout only; peer ACK, reset, byte ticks and physical IRQ behavior remain unmeasured explicit inputs")
    }

    fun observe(loader: ByteArray,windows: List<DeviceWindow>,section4: ByteArray): Map<String,Any> {
        val b=ByteBuffer.wrap(loader).order(ByteOrder.LITTLE_ENDIAN)
        fun word(at: Int)=b.getInt(at).toLong() and 0xffffffffL
        val expected=mapOf(0x52c8 to 0xe5c01000L,0x5310 to 0xe5d00000L,0xa074 to 0xe30d3018L,0xa078 to 0xe34f3ff3L,0xa080 to 0xe0830600L,0xa084 to 0xebffec8dL,
            0xa0c8 to 0xe30d3008L,0xa0cc to 0xe34f3ff3L,0xa0d0 to 0xe6ef1071L,0xa0e8 to 0xe30d3008L,0xa0ec to 0xe34f3ff3L,0xa0f0 to 0xe0830600L,0xa0f4 to 0xebffec83L)
        expected.forEach { (at,value)->require(word(at)==value) { "DAT I2C byte/indexed producer differs at ${at.toString(16)}" } }
        fun imm16(w: Long)=(w and 0xfff) or ((w ushr 4) and 0xf000)
        fun address(at: Int)=imm16(word(at)) or (imm16(word(at+4)) shl 16)
        val fsr0=address(0xa074);val ccr0=address(0xa0c8);val base=ccr0-8
        val stride=1L shl ((word(0xa080) ushr 7) and 31).toInt()
        require(fsr0-base==0x18L && address(0xa0e8)==ccr0 && stride==4096L)
        val selected=List(2) { i->windows.single {it.port=="soc-i2c.controller$i"}.also {require(it.address==base+i*stride && it.size==0x20L) { "selected I2C layout differs from DAT producer/SOURCE extent" }} }
        val prefixes=listOf(1187036,7591019).associate { size->require(section4.size>=size);size.toString() to mapOf("address" to 0x1021000,"size" to size,"sha256" to MessageDigest.getInstance("SHA-256").digest(section4.copyOfRange(0,size)).joinToString("") {"%02x".format(it)}) }
        return mapOf("matches" to true,"base0" to base,"stride" to stride,"fsr0" to fsr0,"ccr0" to ccr0,"accessBytes" to 1,"selectedWindows" to selected.map {mapOf("port" to it.port,"address" to it.address,"size" to it.size)},
            "checkedInstructionAddresses" to expected.keys.toList(),"section4Prefixes" to prefixes,"limitation" to "DAT byte/indexed consumers only. Two windows, SOURCE extent, reset/ticks/bus/IRQ profiles unmeasured. Prefix extents follow accepted bounded outputs, never preloaded RAM")
    }
}
