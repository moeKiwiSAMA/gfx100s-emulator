package com.github.nyabanban.fuji.emulator.api

object GraphicsSuccessors {
    const val CAPACITY = 512
    const val SUBMISSION_LIMIT = CAPACITY + 1
    const val ACCESS_LIMIT = 16384
    private val repeated = Regex("""either\*([1-9][0-9]{0,2})""")

    fun expand(selection: String): List<String> = buildList {
        require(selection.length <= CAPACITY * "either,".length)
        for (token in selection.split(',')) {
            if (token in listOf("fill", "copy", "either")) {
                require(size < CAPACITY)
                add(token)
            } else {
                val count = requireNotNull(repeated.matchEntire(token)) { "Invalid IRQ92 successor: $token" }
                    .groupValues[1].toInt()
                require(count <= CAPACITY - size) { "IRQ92 successor capacity exceeded" }
                repeat(count) { add("either") }
            }
        }
    }

    fun checkedOrder(selection: String, fillLimit: Long, copyLimit: Long): List<String> {
        require(fillLimit in 1..SUBMISSION_LIMIT && copyLimit in 1..SUBMISSION_LIMIT)
        return expand(selection).also { order ->
            require(order.size <= fillLimit + copyLimit - 2 && order.count { it == "fill" } < fillLimit &&
                order.count { it == "copy" } < copyLimit) { "IRQ92 sequence exceeds execution capacity" }
        }
    }
}
