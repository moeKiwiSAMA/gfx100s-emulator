package com.github.nyabanban.fuji.emulator.desktop

/** Host query operands keep physical byte widths and protocol word bits distinct. */
internal fun catalogQueryParameters(space:String,raw:String,size:Int):Map<String,String> {
    val value=raw.trim();require(value.isNotEmpty() && space.isNotBlank())
    val numeric=value.startsWith("0x",true) || value.all {it.isDigit()}
    if(!numeric) {
        require(value.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._:/-]*"))) {"Invalid stable dictionary ID"}
        return mapOf("id" to value)
    }
    return if(space=="cpu-physical") {
        require(size in listOf(1,2,4,8))
        mapOf("space" to space,"address" to value,"width" to size.toString())
    } else {
        require(size in 1..32)
        mapOf("space" to space,"selector" to value,"wordBits" to size.toString())
    }
}
