package com.github.nyabanban.fuji.emulator.runtime

internal object Image70000ConfigWords {
    data class Read(val offset: Int, val lr: Long)

    const val INITIAL_WORD = 0L
    val reads: List<Read> = buildList {
        add(Read(0x020, 0x011b9b7c))
        add(Read(0x054, 0x011b9b98))
        add(Read(0x024, 0x011b9bac))
        add(Read(0x028, 0x011b9bc8))
        for (offset in 0x02c..0x038 step 4) add(Read(offset, 0x011b9bec))
        add(Read(0x03c, 0x011b9c20))
        add(Read(0x040, 0x011b9c34))
        add(Read(0x044, 0x011b9c58))
        add(Read(0x048, 0x011b9c7c))
        add(Read(0x04c, 0x011b9ca0))
        add(Read(0x050, 0x011b9cc4))
        add(Read(0x058, 0x011bd348))
        for (base in 0x09c..0x0ec step 16) {
            add(Read(base, 0x011ba040))
            add(Read(base + 4, 0x011ba060))
            add(Read(base + 8, 0x011ba078))
            add(Read(base + 12, 0x011ba090))
        }
        add(Read(0x0fc, 0x011ba22c))
        add(Read(0x100, 0x011ba248))
        add(Read(0x104, 0x011ba264))
    }
}
