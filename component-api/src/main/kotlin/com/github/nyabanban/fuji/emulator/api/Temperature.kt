package com.github.nyabanban.fuji.emulator.api

/** Owner-only unsigned12 sample. -1 means missing explicit input, never a zero or ready value. */
fun interface TemperatureSamplePort { fun sampleRaw(): Int }
