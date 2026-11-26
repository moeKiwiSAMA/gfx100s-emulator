package com.github.nyabanban.fuji.emulator.desktop.panels

import com.github.nyabanban.fuji.emulator.desktop.theme.BodyThemeLayout

/** Native window layouts for the built-in GFX100S. Dimensions are logical screen units. */
object StudioLayout {
    const val WIDTH=1560.0
    const val HEIGHT=1110.0
    const val ENTRY_MIN_WIDTH=340.0
    data class Box(val x:Double,val y:Double,val width:Double,val height:Double)
    data class Face(val slot:Box,val crop:Box,val rotationDegrees:Double=0.0)
    val faces=linkedMapOf(
        "rear" to Face(Box(320.0,320.0,720.0,490.0),Box(128.0,104.0,958.0,638.0)),
        "top" to Face(Box(320.0,10.0,720.0,300.0),Box(128.0,234.0,970.0,463.0)),
        "front" to Face(Box(10.0,290.0,300.0,530.0),Box(120.0,164.0,972.0,576.0),90.0),
        "ports-left" to Face(Box(10.0,10.0,300.0,270.0),Box(338.0,166.0,480.0,573.0)),
        "storage-right" to Face(Box(10.0,830.0,300.0,270.0),Box(338.0,174.0,518.0,566.0)),
        "battery-bottom" to Face(Box(320.0,820.0,720.0,280.0),Box(128.0,260.0,1000.0,438.0))
    )
    val configuration=Box(1050.0,10.0,500.0,1090.0)
    // The primary column groups the shoulder LCD with the larger rear display.
    // The configuration window occupies the adjacent full-height column.
    val shootingWorkspace by lazy {
        BodyThemeLayout(
            width = 1600.0,
            height = 1040.0,
            configuration = Box(892.0, 10.0, 698.0, 1020.0),
            faces = mapOf(
                "top" to faces.getValue("top").copy(slot = Box(10.0, 10.0, 872.0, 426.0)),
                "rear" to faces.getValue("rear").copy(slot = Box(10.0, 446.0, 872.0, 584.0)),
            ),
        )
    }
    fun overlap(a:Box,b:Box)=minOf(a.x+a.width,b.x+b.width)>maxOf(a.x,b.x)&&minOf(a.y+a.height,b.y+b.height)>maxOf(a.y,b.y)
}
