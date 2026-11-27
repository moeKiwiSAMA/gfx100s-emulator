package com.github.nyabanban.fuji.emulator.desktop

/** Qualified GFX100S 2.13 screen sequence observed after moving the physical movie lever to STILL. */
internal class StillModeTransition {
    var active = false
        private set

    fun leftMovie(previousScreen: Int?) {
        active = previousScreen == 0x0208
    }

    fun observe(screen: Int?) {
        active = active && (screen == 0x0208 || screen == 0x0000)
    }

    fun clear() {
        active = false
    }
}
