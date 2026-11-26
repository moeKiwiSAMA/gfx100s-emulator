package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.MemoryRegion

internal class RamWriteRanges(regions:List<MemoryRegion>) {
    private val ordered=regions.sortedBy{it.address}
    private val starts=ordered.map{it.address}.toLongArray()
    private val ends=ordered.map{it.address+it.size}.toLongArray()
    private var lastIndex=0

    fun mergedRanges(): Pair<LongArray, LongArray> {
        if (starts.isEmpty()) return LongArray(0) to LongArray(0)
        val outStarts = ArrayList<Long>(starts.size)
        val outEnds = ArrayList<Long>(ends.size)
        var curStart = starts[0]
        var curEnd = ends[0]
        for (i in 1 until starts.size) {
            if (starts[i] <= curEnd) {
                if (ends[i] > curEnd) curEnd = ends[i]
            } else {
                outStarts.add(curStart)
                outEnds.add(curEnd)
                curStart = starts[i]
                curEnd = ends[i]
            }
        }
        outStarts.add(curStart)
        outEnds.add(curEnd)
        return outStarts.toLongArray() to outEnds.toLongArray()
    }

    fun contains(address:Long,size:Int):Boolean {
        if(address<0||size<=0||address>0x1_0000_0000L-size)return false
        val cached=lastIndex
        if(starts.isNotEmpty()&&address>=starts[cached]&&address+size<=ends[cached])return true
        val found=java.util.Arrays.binarySearch(starts,address)
        var index=if(found>=0)found else -found-2
        if(index<0||address>=ends[index])return false
        val end=address+size
        if(end<=ends[index]) {
            lastIndex=index
            return true
        }
        while(end>ends[index]) {
            if(index+1>=starts.size||starts[index+1]!=ends[index])return false
            index++
        }
        return true
    }
}
