package com.github.nyabanban.fuji.emulator.api

@ConsistentCopyVisibility
data class GraphicsMode2Tile private constructor(val source: Long, val destination: Long) {
    companion object {
        const val SETTING = "mode2Morton8x8"
        const val WIDTH = 240
        const val HEIGHT = 32
        const val BYTES = WIDTH * HEIGHT * 4
        const val GEOMETRY = (HEIGHT shl 16) or WIDTH

        fun parse(raw: Any?): GraphicsMode2Tile {
            require(raw is Map<*, *> && raw.keys == setOf("sourceBase", "destinationBase"))
            fun base(key: String): Long {
                val value = raw[key]
                require(value is Int || value is Long)
                return (value as Number).toLong().also { require(it in 0..0x100000000L-BYTES && it and 3L == 0L) }
            }
            val source = base("sourceBase")
            val destination = base("destinationBase")
            require(source + BYTES <= destination || destination + BYTES <= source)
            return GraphicsMode2Tile(source, destination)
        }
    }

    fun settings() = mapOf("sourceBase" to source, "destinationBase" to destination)
}

@ConsistentCopyVisibility
data class GraphicsMode2Workspace private constructor(val base: Long, val bytes: Long) {
    companion object {
        const val SETTING = "mode2Workspace"
        const val MAX_BYTES = 10 * 1024 * 1024

        fun parse(raw: Any?): GraphicsMode2Workspace {
            require(raw is Map<*, *> && raw.keys == setOf("base", "bytes"))
            fun integer(key: String): Long {
                val value = raw[key]
                require(value is Int || value is Long)
                return (value as Number).toLong()
            }
            val bytes = integer("bytes")
            val base = integer("base")
            require(bytes in 512..MAX_BYTES.toLong() && base in 0..0x100000000L-bytes && (base or bytes) and 3L == 0L)
            return GraphicsMode2Workspace(base, bytes)
        }
    }

    fun settings() = mapOf("base" to base, "bytes" to bytes)

    fun permits(source: Long, destination: Long, length: Int): Boolean {
        val starts = base..base + bytes - length
        return source in starts && destination in starts && (source or destination) and 3L == 0L &&
            (source + length <= destination || destination + length <= source)
    }

    fun covers(tile: GraphicsMode2Tile) = permits(tile.source, tile.destination, GraphicsMode2Tile.BYTES)
}
