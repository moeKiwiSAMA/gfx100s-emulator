package com.github.nyabanban.fuji.emulator.api

interface ExpandableMemoryLayout : MemoryLayout {
    fun validateAdditionalRegions(regions: List<MemoryRegion>): List<MemoryRegion>
    fun selectAdditionalRegions(regions: List<MemoryRegion>)
    fun extendAdditionalRegions(regions: List<MemoryRegion>, beforePublish: () -> Unit) {
        throw UnsupportedOperationException("Extended additional RAM is not supported by this layout")
    }
    fun additionalRegions(): List<MemoryRegion>
}

object AdditionalRamRegions {
    private const val MAX_REGIONS = 40
    private const val MAX_GENERAL_REGIONS = 36
    const val PROFILE = "finite-rw-nx-experimental-v1"
    const val EXEC_CAPABLE_PROFILE = "finite-rw-or-rwx-experimental-v2"
    val PROFILES: Set<String> = java.util.Collections.unmodifiableSet(linkedSetOf(PROFILE, EXEC_CAPABLE_PROFILE))

    @JvmOverloads
    fun validate(base: List<MemoryRegion>, additional: List<MemoryRegion>, profile: String = PROFILE): List<MemoryRegion> {
        require(profile in PROFILES) { "Unknown additional RAM profile" }
        val baseRegions = base.toList()
        val additionalRegions = additional.toList()
        require(baseRegions.isNotEmpty()) { "Additional RAM requires a nonempty base layout" }
        require(additionalRegions.size in 1..MAX_REGIONS) { "Additional RAM requires 1..$MAX_REGIONS regions" }
        validatePageAllowance(0, additionalRegions)
        val complete = (baseRegions + additionalRegions).sortedBy { it.address }
        require(complete.all { it.id.isNotBlank() } && complete.map { it.id }.distinct().size == complete.size) {
            "Additional RAM requires nonblank unique IDs distinct from the base"
        }
        require(complete.all {
            it.address >= 0 && it.size > 0 && it.size <= 0x1_0000_0000L &&
                it.address <= 0x1_0000_0000L - it.size &&
                it.address % 4096 == 0L && it.size % 4096 == 0L && it.permissions in 1..7
        }) { "Additional RAM requires valid aligned 32-bit regions" }
        require(additionalRegions.all { it.permissions == 3 || profile == EXEC_CAPABLE_PROFILE && it.permissions == 7 }) {
            if (profile == PROFILE) "Additional RAM requires exactly RW/NX permissions"
            else "Additional RAM requires exactly RW/NX or RWX permissions"
        }
        val maxTotalBytes = if (profile == EXEC_CAPABLE_PROFILE) 0xF000_0000L else 1024L * 1024 * 1024
        require(additionalRegions.sumOf { it.size } <= maxTotalBytes) {
            if (profile == EXEC_CAPABLE_PROFILE) "Additional RAM exceeds 3840MiB"
            else "Additional RAM exceeds 1024MiB"
        }
        require(complete.zipWithNext().all { (left, right) -> left.address + left.size <= right.address }) {
            "Additional RAM regions overlap"
        }
        return java.util.List.copyOf(complete)
    }

    fun validatePageAllowance(previousCount: Int, additions: List<MemoryRegion>) {
        require(previousCount in 0..MAX_REGIONS && additions.size <= MAX_REGIONS - previousCount) {
            "Additional RAM exceeds $MAX_REGIONS regions"
        }
        val generalSlots = (MAX_GENERAL_REGIONS - previousCount).coerceAtLeast(0)
        require(additions.count { it.permissions != 3 || (it.size != 4096L && it.size != 8192L) } <= generalSlots) {
            "Additional RAM beyond $MAX_GENERAL_REGIONS general regions requires one or two RW/NX pages per region"
        }
    }
}
