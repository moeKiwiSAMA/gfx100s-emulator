package com.github.nyabanban.fuji.emulator.api

/** Optional ABI1 extension for unambiguous multi-peer routing; ranges are immutable for this component lifetime.
 * These windows claim bus endpoints, not physical device capacity or writable media permission. */
interface RangedDmaDestinationPeer : DmaDestinationPeer {
    fun destinationRanges(): List<AddressWindow>
}
