package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.MemoryRegion

internal object AdditionalRamMapping {
    fun map(regions:List<MemoryRegion>,allocate:(Long,Long,Int)->Unit,release:(Long,Long)->Unit) {
        val mapped=ArrayList<MemoryRegion>(regions.size)
        try {
            for(region in regions) {
                allocate(region.address,region.size,region.permissions)
                mapped.add(region)
            }
        } catch(failure:Throwable) {
            for(region in mapped.asReversed()) {
                try {release(region.address,region.size)}
                catch(rollback:Throwable) {failure.addSuppressed(rollback)}
            }
            throw failure
        }
    }
}
