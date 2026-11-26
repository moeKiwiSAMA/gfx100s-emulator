package com.github.nyabanban.fuji.emulator.components.method4

import java.util.Arrays

/** Bounded 2048-byte sliding history, cloned before each transaction. */
internal class Stream(var header: List<Int>?=null,var sourceBase: Long=0,var destination: Long=0,var consumed: Int=20,
    var produced: Int=0,var page: Int=0,var literal: Int=0,var matchFirst: Int?=null,private val history: ByteArray=ByteArray(2048)) {
    fun copy()=Stream(header,sourceBase,destination,consumed,produced,page,literal,matchFirst,history.copyOf())
    fun historyBytes(): ByteArray = history.copyOf()
    fun feed(input: ByteArray): ByteArray {
        val h=checkNotNull(header)
        val totalOutput=h[0]
        val totalPacked=h[1]
        require(input.size<=totalPacked-consumed) { "method-4 input exceeds declared stream" }
        val remainingOut=totalOutput-produced
        val initialCap=if(remainingOut<=0) 0 else minOf(remainingOut,maxOf(input.size*2,4096))
        var outBuf=ByteArray(initialCap)
        var outLen=0
        val hist=history
        var prod=produced
        var lit=literal
        var mf=matchFirst ?: -1
        var cons=consumed
        for(i in input.indices) {
            val value=input[i].toInt() and 255
            if(lit>0) {
                require(prod<totalOutput) { "LZ output exceeds declared size" }
                val b=value.toByte()
                hist[prod and 2047]=b
                prod++
                if(outLen==outBuf.size) {
                    val grow=minOf(totalOutput-produced,maxOf(outBuf.size*2,outLen+256))
                    outBuf=Arrays.copyOf(outBuf,grow)
                }
                outBuf[outLen++]=b
                lit--
            } else if(mf>=0) {
                val distance=((mf and 127) shl 4) or (value ushr 4)
                val count=value and 15
                require(distance in 1..prod && count>0) { "invalid LZ back reference" }
                require(prod+count<=totalOutput) { "LZ output exceeds declared size" }
                mf=-1
                if(outLen+count>outBuf.size) {
                    val grow=minOf(totalOutput-produced,maxOf(outBuf.size*2,outLen+count+256))
                    outBuf=Arrays.copyOf(outBuf,grow)
                }
                for(k in 0 until count) {
                    val b=hist[(prod-distance) and 2047]
                    hist[prod and 2047]=b
                    prod++
                    outBuf[outLen++]=b
                }
            } else if(value==0) {
                error("zero LZ literal run")
            } else if(value<128) {
                lit=value
            } else {
                mf=value
            }
            cons++
        }
        produced=prod
        literal=lit
        matchFirst=if(mf>=0) mf else null
        consumed=cons
        require(cons!=totalPacked || (lit==0 && mf<0 && prod==totalOutput)) { "truncated LZ token or decoded size mismatch" }
        return if(outLen==outBuf.size) outBuf else Arrays.copyOf(outBuf,outLen)
    }
}
