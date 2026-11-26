package com.github.nyabanban.fuji.emulator.desktop.panels

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** Original nonperiodic microrelief; no source pixels, broad lighting or per-frame generation. */
object ContinuousMaterial {
    const val WIDTH = 1200
    const val HEIGHT = 800
    data class Spec(val kind: String, val seed: Long) {
        init { require(kind in setOf("paint", "rubber", "brushed-metal", "anodized")); require(seed in 0..0xffffffffL) }
    }
    private fun hash(x: Int, y: Int, seed: Int): Double {
        var h = x * 0x1f1f1f1f xor (y * 0x5f356495) xor seed
        h = (h xor (h ushr 16)) * -2048144789
        h = (h xor (h ushr 13)) * -1028477387
        return ((h xor (h ushr 16)) ushr 8) / 8388607.5 - 1.0
    }
    private fun field(x: Double, y: Double, seed: Int): Double {
        val gx=floor(x).toInt();val gy=floor(y).toInt()
        val fx=x-gx;val fy=y-gy
        val sx=fx*fx*(3-2*fx);val sy=fy*fy*(3-2*fy)
        val a=hash(gx,gy,seed);val b=hash(gx+1,gy,seed)
        val c=hash(gx,gy+1,seed);val d=hash(gx+1,gy+1,seed)
        return (a+(b-a)*sx)*(1-sy)+(c+(d-c)*sx)*sy
    }
    private fun scattered(x:Double,y:Double,spacing:Double,seed:Int):Double {
        val u=x/spacing;val v=y/spacing;val gx=floor(u).toInt();val gy=floor(v).toInt()
        var relief=0.0
        for(oy in -1..1)for(ox in -1..1) {
            val cx=gx+ox;val cy=gy+oy
            val dx=u-(cx+.5+.45*hash(cx,cy,seed))
            val dy=v-(cy+.5+.45*hash(cx,cy,seed xor 0x27183))
            val axis=.20*hash(cx,cy,seed xor 0x61abc)
            val turn=.28*hash(cx,cy,seed xor 0x17091)
            // Positive elliptical compact bumps have random local normals and zero slope at the support edge.
            val q=dx*dx*(1+axis)+dy*dy*(1-axis)+turn*dx*dy
            val t=(1-q/.60).coerceAtLeast(0.0)
            relief+=(.72+.22*hash(cx,cy,seed xor 0x59cab))*t*t*t
        }
        return relief
    }
    private fun height(x: Double,y: Double,spec:Spec):Float {
        val seed=spec.seed.toInt()
        return when (spec.kind) {
            "paint" -> (.94*scattered(x,y,1.70,seed)+.06*field(x*.43,y*.47,seed xor 0x39ab1)).toFloat()
            "brushed-metal" -> (.74*field(x*.042,y*1.65,seed xor 0x19d35)+.26*scattered(x,y,2.10,seed xor 0x51a7)).toFloat()
            "anodized" -> (.88*scattered(x,y,1.35,seed xor 0x2b1e)+.12*field(x*.55,y*.55,seed xor 0x71c4)).toFloat()
            else -> (.84*scattered(x,y,2.85,seed xor 0x47bc2)+.16*field(x*.23,y*.27,seed xor 0x39ab1)).toFloat()
        }
    }
    fun pixels(spec:Spec):IntArray {
        // A single bounded 1-pixel halo makes central differences continuous across every former tile boundary.
        val stride=WIDTH+2
        val relief=FloatArray(stride*(HEIGHT+2)) { i -> height((i%stride-1).toDouble(),(i/stride-1).toDouble(),spec) }
        return IntArray(WIDTH*HEIGHT) { i ->
            val x=i%WIDTH;val y=i/WIDTH;val at=(y+1)*stride+x+1
            val dx=(relief[at+1]-relief[at-1]).toDouble()
            val dy=(relief[at+stride]-relief[at-stride]).toDouble()
            // Fixed upper-left light, independent of a slowly varying local surface tilt.
            val turn=field(x/37.0,y/37.0,spec.seed.toInt() xor 0x62acd)
            val n=(-.72*dx-(.66+.16*turn)*dy).coerceIn(-1.0,1.0)
            val gain=when (spec.kind) {
                "paint" -> if(n>0).090 else .220
                "brushed-metal" -> if(n>0).085 else .145
                "anodized" -> if(n>0).072 else .165
                else -> if(n>0).090 else .210
            }
            val alpha=(abs(n)*gain*255).roundToInt().coerceIn(0,80)
            (alpha shl 24) or (if(n>=0)0xffffff else 0)
        }
    }
}
