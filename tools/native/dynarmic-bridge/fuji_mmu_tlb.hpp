#ifndef FUJI_DYNARMIC_MMU_TLB_HPP
#define FUJI_DYNARMIC_MMU_TLB_HPP

#include <algorithm>
#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <vector>

#include <dynarmic/a32_sys/cp15.hpp>

namespace Dynarmic::A32Sys {

/* Generic physical memory region permission bitflags */
inline constexpr std::uint32_t PERM_READ  = 1u << 0;
inline constexpr std::uint32_t PERM_WRITE = 1u << 1;
inline constexpr std::uint32_t PERM_EXEC  = 1u << 2;
inline constexpr std::uint32_t PERM_ALL   = PERM_READ | PERM_WRITE | PERM_EXEC;

/* ARMv7-A Short-Descriptor 40-bit physical address limit (Supersection PA[39:0]) */
inline constexpr std::uint64_t PHYS_ADDR_LIMIT = 1ull << 40;
inline constexpr std::uint64_t PHYS_ADDR_32BIT = 0x100000000ull;

/* ARMv7-A Short-Descriptor Fault Status codes (DFSR/IFSR[3:0]) */
inline constexpr std::uint32_t FAULT_NONE                 = 0x00u;
inline constexpr std::uint32_t FAULT_ALIGNMENT            = 0x01u;
inline constexpr std::uint32_t FAULT_SECTION_ACCESS_FLAG  = 0x03u;
inline constexpr std::uint32_t FAULT_SECTION_TRANSLATION  = 0x05u;
inline constexpr std::uint32_t FAULT_PAGE_ACCESS_FLAG     = 0x06u;
inline constexpr std::uint32_t FAULT_PAGE_TRANSLATION     = 0x07u;
inline constexpr std::uint32_t FAULT_SYNC_EXTERNAL_ABORT  = 0x08u;
inline constexpr std::uint32_t FAULT_SECTION_DOMAIN       = 0x09u;
inline constexpr std::uint32_t FAULT_PAGE_DOMAIN          = 0x0Bu;
inline constexpr std::uint32_t FAULT_L1_EXTERNAL_ABORT    = 0x0Cu;
inline constexpr std::uint32_t FAULT_SECTION_PERMISSION   = 0x0Du;
inline constexpr std::uint32_t FAULT_L2_EXTERNAL_ABORT    = 0x0Eu;
inline constexpr std::uint32_t FAULT_PAGE_PERMISSION      = 0x0Fu;

struct RamRegion {
    std::uint64_t base = 0;
    std::uint64_t size = 0;
    std::uint64_t end = 0;
    std::uint8_t* ptr = nullptr;
    std::uint32_t perms = PERM_ALL;
    std::unique_ptr<std::uint8_t[]> owned;
};

struct MmioRegion {
    std::uint32_t base = 0;
    std::uint32_t size = 0;
    std::uint64_t end = 0;
};

class PhysMemoryBus {
public:
    static constexpr std::size_t INVALID_IDX = std::numeric_limits<std::size_t>::max();

    std::int32_t map_ram(std::uint64_t phys_base, std::uint64_t size, void* host_ptr, std::uint32_t perms) {
        if (size == 0 || phys_base >= PHYS_ADDR_LIMIT || size > (PHYS_ADDR_LIMIT - phys_base)) [[unlikely]] {
            return -1;
        }
        const std::uint64_t end = phys_base + size;
        for (const auto& r : ram_regions_) {
            if (phys_base < r.end && end > r.base) [[unlikely]] {
                return -2; /* overlap */
            }
        }
        RamRegion reg;
        reg.base = phys_base;
        reg.size = size;
        reg.end = end;
        reg.perms = perms & PERM_ALL;
        if (host_ptr != nullptr) {
            reg.ptr = static_cast<std::uint8_t*>(host_ptr);
        } else {
            if (size > static_cast<std::uint64_t>(std::numeric_limits<std::size_t>::max())) [[unlikely]] {
                return -1;
            }
            reg.owned = std::make_unique<std::uint8_t[]>(static_cast<std::size_t>(size));
            std::memset(reg.owned.get(), 0, static_cast<std::size_t>(size));
            reg.ptr = reg.owned.get();
        }
        ram_regions_.push_back(std::move(reg));
        std::sort(ram_regions_.begin(), ram_regions_.end(),
                  [](const RamRegion& a, const RamRegion& b) { return a.base < b.base; });
        last_ram_idx_.store(INVALID_IDX, std::memory_order_relaxed);
        refresh_primary_ram_cache();
        return 0;
    }

    std::int32_t unmap_ram(std::uint64_t phys_base, std::uint64_t size) {
        if (size == 0 || phys_base >= PHYS_ADDR_LIMIT || size > (PHYS_ADDR_LIMIT - phys_base)) [[unlikely]] {
            return -1;
        }
        const std::uint64_t end = phys_base + size;
        auto it = std::remove_if(ram_regions_.begin(), ram_regions_.end(), [&](const RamRegion& r) {
            return r.base == phys_base && r.end == end;
        });
        if (it == ram_regions_.end()) [[unlikely]] return -2;
        ram_regions_.erase(it, ram_regions_.end());
        last_ram_idx_.store(INVALID_IDX, std::memory_order_relaxed);
        refresh_primary_ram_cache();
        return 0;
    }

    std::uint64_t primary_ram_base() const { return primary_ram_base_; }
    std::uint64_t primary_ram_end() const { return primary_ram_end_; }
    std::uint8_t* primary_ram_host() const { return primary_ram_host_; }

    std::int32_t map_mmio(std::uint32_t phys_base, std::uint32_t size) {
        if (size == 0 || (static_cast<std::uint64_t>(phys_base) + size) > PHYS_ADDR_32BIT) [[unlikely]] {
            return -1;
        }
        const std::uint64_t end = static_cast<std::uint64_t>(phys_base) + size;
        MmioRegion reg{phys_base, size, end};
        mmio_regions_.push_back(reg);
        std::sort(mmio_regions_.begin(), mmio_regions_.end(),
                  [](const MmioRegion& a, const MmioRegion& b) { return a.base < b.base; });
        refresh_mmio_bounds();
        return 0;
    }

    std::int32_t unmap_mmio(std::uint32_t phys_base, std::uint32_t size) {
        if (size == 0) [[unlikely]] return -1;
        const std::uint64_t end = static_cast<std::uint64_t>(phys_base) + size;
        auto it = std::remove_if(mmio_regions_.begin(), mmio_regions_.end(), [&](const MmioRegion& r) {
            return r.base == phys_base && r.end == end;
        });
        if (it == mmio_regions_.end()) [[unlikely]] return -2;
        mmio_regions_.erase(it, mmio_regions_.end());
        refresh_mmio_bounds();
        return 0;
    }

    inline const RamRegion* find_ram(std::uint64_t pa, std::uint64_t size) const {
        if (size == 0 || pa >= PHYS_ADDR_LIMIT || size > (PHYS_ADDR_LIMIT - pa)) [[unlikely]] return nullptr;
        const std::uint64_t end = pa + size;
        if (primary_ram_host_ != nullptr && pa >= primary_ram_base_ && end <= primary_ram_end_) [[likely]] {
            return &ram_regions_[primary_ram_idx_];
        }
        const std::size_t cached_ram = last_ram_idx_.load(std::memory_order_relaxed);
        if (cached_ram < ram_regions_.size()) {
            const auto& r = ram_regions_[cached_ram];
            if (pa >= r.base && end <= r.end) [[likely]] return &r;
        }
        std::size_t lo = 0;
        std::size_t hi = ram_regions_.size();
        while (lo < hi) {
            const std::size_t mid = lo + ((hi - lo) >> 1);
            const auto& r = ram_regions_[mid];
            if (pa < r.base) {
                hi = mid;
            } else if (pa >= r.end) {
                lo = mid + 1;
            } else {
                if (end <= r.end) [[likely]] {
                    last_ram_idx_.store(mid, std::memory_order_relaxed);
                    return &r;
                }
                return nullptr;
            }
        }
        return nullptr;
    }

    inline bool is_mmio(std::uint64_t pa) const {
        if (pa >= PHYS_ADDR_32BIT) [[unlikely]] return false;
        const std::uint32_t pa32 = static_cast<std::uint32_t>(pa);
        if (pa32 < mmio_min_base_ || pa >= mmio_max_end_) [[likely]] {
            return false;
        }
        const std::size_t cached_mmio = last_mmio_idx_.load(std::memory_order_relaxed);
        if (cached_mmio < mmio_regions_.size()) {
            const auto& r = mmio_regions_[cached_mmio];
            if (pa32 >= r.base && pa < r.end) [[likely]] return true;
        }
        std::size_t lo = 0;
        std::size_t hi = mmio_regions_.size();
        while (lo < hi) {
            const std::size_t mid = lo + ((hi - lo) >> 1);
            const auto& r = mmio_regions_[mid];
            if (pa32 < r.base) {
                hi = mid;
            } else if (pa >= r.end) {
                lo = mid + 1;
            } else {
                last_mmio_idx_.store(mid, std::memory_order_relaxed);
                return true;
            }
        }
        return false;
    }

    inline bool read_phys_u32(std::uint64_t pa, std::uint32_t& out) const {
        if (pa > (PHYS_ADDR_LIMIT - 4ull)) [[unlikely]] return false;
        const std::uint64_t end = pa + 4ull;
        if (primary_ram_host_ != nullptr && pa >= primary_ram_base_ && end <= primary_ram_end_) [[likely]] {
            std::memcpy(&out, primary_ram_host_ + (pa - primary_ram_base_), 4u);
            return true;
        }
        const auto* r = find_ram(pa, 4u);
        if (!r) [[unlikely]] return false;
        std::memcpy(&out, r->ptr + (pa - r->base), 4u);
        return true;
    }

    inline std::int32_t mem_read(std::uint64_t pa, void* dst, std::uint32_t size) const {
        if (size == 0) [[unlikely]] return 0;
        if (pa >= PHYS_ADDR_LIMIT || size > (PHYS_ADDR_LIMIT - pa)) [[unlikely]] return -1;
        const std::uint64_t end = pa + size;
        if (primary_ram_host_ != nullptr && pa >= primary_ram_base_ && end <= primary_ram_end_) [[likely]] {
            std::memcpy(dst, primary_ram_host_ + (pa - primary_ram_base_), size);
            return 0;
        }
        const auto* r = find_ram(pa, size);
        if (!r) [[unlikely]] return -1;
        std::memcpy(dst, r->ptr + (pa - r->base), size);
        return 0;
    }

    inline std::int32_t mem_write(std::uint64_t pa, const void* src, std::uint32_t size) {
        if (size == 0) [[unlikely]] return 0;
        if (pa >= PHYS_ADDR_LIMIT || size > (PHYS_ADDR_LIMIT - pa)) [[unlikely]] return -1;
        const std::uint64_t end = pa + size;
        if (primary_ram_host_ != nullptr && pa >= primary_ram_base_ && end <= primary_ram_end_) [[likely]] {
            std::memcpy(primary_ram_host_ + (pa - primary_ram_base_), src, size);
            return 0;
        }
        const auto* r = find_ram(pa, size);
        if (!r) [[unlikely]] return -1;
        std::memcpy(r->ptr + (pa - r->base), src, size);
        return 0;
    }

private:
    void refresh_primary_ram_cache() {
        primary_ram_base_ = 0u;
        primary_ram_end_ = 0u;
        primary_ram_host_ = nullptr;
        primary_ram_idx_ = INVALID_IDX;
        std::uint64_t best_size = 0u;
        /* Prefer the largest low-32-bit RAM region so hot page-table walks stay O(1) */
        for (std::size_t i = 0; i < ram_regions_.size(); ++i) {
            const auto& r = ram_regions_[i];
            if (r.base < PHYS_ADDR_32BIT && r.size > best_size) {
                best_size = r.size;
                primary_ram_base_ = r.base;
                primary_ram_end_ = r.end;
                primary_ram_host_ = r.ptr;
                primary_ram_idx_ = i;
            }
        }
        if (primary_ram_host_ == nullptr) {
            for (std::size_t i = 0; i < ram_regions_.size(); ++i) {
                const auto& r = ram_regions_[i];
                if (r.size > best_size) {
                    best_size = r.size;
                    primary_ram_base_ = r.base;
                    primary_ram_end_ = r.end;
                    primary_ram_host_ = r.ptr;
                    primary_ram_idx_ = i;
                }
            }
        }
    }

    void refresh_mmio_bounds() {
        last_mmio_idx_.store(INVALID_IDX, std::memory_order_relaxed);
        if (mmio_regions_.empty()) {
            mmio_min_base_ = 0xFFFFFFFFu;
            mmio_max_end_ = 0u;
            return;
        }
        mmio_min_base_ = mmio_regions_.front().base;
        std::uint64_t max_end = 0u;
        for (const auto& r : mmio_regions_) {
            if (r.end > max_end) max_end = r.end;
        }
        mmio_max_end_ = max_end;
    }

    std::uint64_t primary_ram_base_ = 0u;
    std::uint64_t primary_ram_end_ = 0u;
    std::uint8_t* primary_ram_host_ = nullptr;
    std::size_t primary_ram_idx_ = INVALID_IDX;
    std::uint32_t mmio_min_base_ = 0xFFFFFFFFu;
    std::uint64_t mmio_max_end_ = 0u;
    std::vector<RamRegion> ram_regions_;
    std::vector<MmioRegion> mmio_regions_;
    mutable std::atomic<std::size_t> last_ram_idx_{INVALID_IDX};
    mutable std::atomic<std::size_t> last_mmio_idx_{INVALID_IDX};
};

struct TranslationResult {
    bool ok = false;
    bool is_mmio = false;
    bool is_ram = false;
    bool can_read = false;
    bool can_write = false;
    bool can_exec = false;
    bool device_or_strongly_ordered = false;
    bool is_global = true;
    std::uint8_t asid = 0;
    std::uint64_t phys_addr = 0;
    std::uint32_t fault_status = 0;
    std::uint32_t domain = 0;
    std::uint8_t* host_page_ptr = nullptr; /* non-null when 4 KiB aligned RAM page */
};

/*
 * 4096-entry direct-mapped software TLB with full ARMv7-A Short-Descriptor
 * 40-bit Supersection decoding (PA[31:24]=L1[31:24], PA[35:32]=L1[23:20],
 * PA[39:36]=L1[8:5]), ASID/global/domain/AP/XN checks, and Dynarmic's
 * 1M-entry direct host-RAM page_table fastpath.
 */
class ArmMmuTlb {
public:
    static constexpr std::size_t TLB_ENTRIES = 4096;
    static constexpr std::uint32_t TLB_MASK = static_cast<std::uint32_t>(TLB_ENTRIES - 1);
    static constexpr std::size_t PAGE_TABLE_SIZE = 1u << 20;
    static constexpr std::uint32_t LOW_32BIT_PPAGE_LIMIT = 1u << 20;

    static constexpr std::uint8_t FLAG_READ   = 1u << 0;
    static constexpr std::uint8_t FLAG_WRITE  = 1u << 1;
    static constexpr std::uint8_t FLAG_EXEC   = 1u << 2;
    static constexpr std::uint8_t FLAG_RAM    = 1u << 3;
    static constexpr std::uint8_t FLAG_MMIO      = 1u << 4;
    static constexpr std::uint8_t FLAG_DEVICE_SO = 1u << 5;
    static constexpr std::uint8_t FLAG_GLOBAL    = 1u << 6;

    static_assert(PERM_READ == FLAG_READ && PERM_WRITE == FLAG_WRITE && PERM_EXEC == FLAG_EXEC,
                  "PERM_* constants must match TLB FLAG_* permission bits");

    struct TlbEntry {
        std::uint32_t vpage_tag = 0xFFFFFFFFu; /* vpage | (user_mode << 20) */
        std::uint32_t ppage = 0;               /* 28-bit physical 4 KiB page index (pa >> 12) for 40-bit PA */
        std::uint8_t* host_page_ptr = nullptr;
        std::uint8_t flags = 0;
        std::uint8_t asid = 0;
    };

    explicit ArmMmuTlb(bool absolute_offset_pt = false)
        : page_table_(std::make_unique<std::array<std::uint8_t*, PAGE_TABLE_SIZE>>()),
          read_page_table_(std::make_unique<std::array<std::uint8_t*, PAGE_TABLE_SIZE>>()),
          absolute_offset_page_table_(absolute_offset_pt) {
        page_table_->fill(nullptr);
        read_page_table_->fill(nullptr);
        dirty_pages_.reserve(1024);
        watched_l2_pages_.reserve(64);
    }

    std::array<std::uint8_t*, PAGE_TABLE_SIZE>* page_table_ptr() { return page_table_.get(); }
    std::array<std::uint8_t*, PAGE_TABLE_SIZE>* read_page_table_ptr() { return read_page_table_.get(); }

    bool absolute_offset_page_table() const { return absolute_offset_page_table_; }

    void set_absolute_offset_page_table(bool enable) {
        if (absolute_offset_page_table_ != enable) {
            flush();
            absolute_offset_page_table_ = enable;
        }
    }

    void set_code_write_watch(bool enable) {
        if (enable && !watched_code_pages_) {
            watched_code_pages_ = std::make_unique<std::array<std::uint64_t, 16384>>();
            watched_code_pages_->fill(0ull);
        }
        code_write_watch_enabled_ = enable;
        if (!enable && watched_code_pages_) {
            watched_code_pages_->fill(0ull);
        }
    }

    inline bool is_watched_code_page(std::uint32_t ppage) const {
        if (!code_write_watch_enabled_ || !watched_code_pages_ || ppage >= LOW_32BIT_PPAGE_LIMIT) [[likely]] {
            return false;
        }
        return ((*watched_code_pages_)[ppage >> 6] & (1ull << (ppage & 63u))) != 0ull;
    }

    void watch_code_page(std::uint32_t ppage) {
        if (!code_write_watch_enabled_ || !watched_code_pages_ || ppage >= LOW_32BIT_PPAGE_LIMIT) return;
        const std::uint64_t bit = 1ull << (ppage & 63u);
        std::uint64_t& word = (*watched_code_pages_)[ppage >> 6];
        if ((word & bit) == 0ull) {
            word |= bit;
            unpublish_phys_page(ppage);
        }
    }

    void unwatch_code_page(std::uint32_t ppage) {
        if (!watched_code_pages_ || ppage >= LOW_32BIT_PPAGE_LIMIT) return;
        (*watched_code_pages_)[ppage >> 6] &= ~(1ull << (ppage & 63u));
    }

    void clear_watched_code_pages() {
        if (watched_code_pages_) {
            watched_code_pages_->fill(0ull);
        }
    }

    inline void invalidate_mru() {
        mru_entry_.vpage_tag = 0xFFFFFFFFu;
        mmio_mru_entry_.vpage_tag = 0xFFFFFFFFu;
    }

    void flush_all() {
        flush();
    }

    void flush() {
        invalidate_mru();
        for (std::uint32_t vpage : dirty_pages_) {
            (*page_table_)[vpage] = nullptr;
            (*read_page_table_)[vpage] = nullptr;
        }
        dirty_pages_.clear();
        for (auto& e : tlb_) {
            e.vpage_tag = 0xFFFFFFFFu;
        }
        watched_l1_page_ = 0xFFFFFFFFu;
        watched_l2_pages_.clear();
    }

    void flush_va(std::uint32_t va) {
        const std::uint32_t vpage = va >> 12;
        if ((mru_entry_.vpage_tag & 0xFFFFFu) == vpage) {
            mru_entry_.vpage_tag = 0xFFFFFFFFu;
        }
        if ((mmio_mru_entry_.vpage_tag & 0xFFFFFu) == vpage) {
            mmio_mru_entry_.vpage_tag = 0xFFFFFFFFu;
        }
        (*page_table_)[vpage] = nullptr;
        (*read_page_table_)[vpage] = nullptr;
        TlbEntry& e = tlb_[vpage & TLB_MASK];
        if ((e.vpage_tag & 0xFFFFFu) == vpage) {
            e.vpage_tag = 0xFFFFFFFFu;
        }
    }

    void flush_asid(std::uint8_t asid) {
        if (mru_entry_.vpage_tag != 0xFFFFFFFFu &&
            (mru_entry_.flags & FLAG_GLOBAL) == 0u && mru_entry_.asid == asid) {
            mru_entry_.vpage_tag = 0xFFFFFFFFu;
        }
        if (mmio_mru_entry_.vpage_tag != 0xFFFFFFFFu &&
            (mmio_mru_entry_.flags & FLAG_GLOBAL) == 0u && mmio_mru_entry_.asid == asid) {
            mmio_mru_entry_.vpage_tag = 0xFFFFFFFFu;
        }
        for (std::uint32_t vpage : dirty_pages_) {
            const TlbEntry& e = tlb_[vpage & TLB_MASK];
            if ((e.vpage_tag & 0xFFFFFu) != vpage ||
                ((e.flags & FLAG_GLOBAL) == 0u && e.asid == asid)) {
                (*page_table_)[vpage] = nullptr;
                (*read_page_table_)[vpage] = nullptr;
            }
        }
        for (auto& e : tlb_) {
            if (e.vpage_tag != 0xFFFFFFFFu && (e.flags & FLAG_GLOBAL) == 0u && e.asid == asid) {
                e.vpage_tag = 0xFFFFFFFFu;
            }
        }
    }

    void sync_control_state(const Cp15State& cp15) {
        const std::uint32_t mmu_ctrl = cp15.sctlr & ((1u << 29) | (1u << 28) | 1u);
        if (!ctrl_synced_ || mmu_ctrl != synced_mmu_ctrl_ ||
            cp15.ttbr0 != synced_ttbr0_ || cp15.ttbr1 != synced_ttbr1_ ||
            cp15.ttbcr != synced_ttbcr_ || cp15.dacr != synced_dacr_ ||
            cp15.prrr != synced_prrr_ || cp15.nmrr != synced_nmrr_) {
            if (ctrl_synced_) {
                flush();
            }
            synced_mmu_ctrl_ = mmu_ctrl;
            synced_ttbr0_ = cp15.ttbr0;
            synced_ttbr1_ = cp15.ttbr1;
            synced_ttbcr_ = cp15.ttbcr;
            synced_dacr_ = cp15.dacr;
            synced_prrr_ = cp15.prrr;
            synced_nmrr_ = cp15.nmrr;
            synced_asid_ = cp15.current_asid();
            ctrl_synced_ = true;
            return;
        }
        const std::uint8_t asid = cp15.current_asid();
        if (asid != synced_asid_) {
            invalidate_mru();
            synced_asid_ = asid;
        }
    }

    bool is_watched_pt_page(std::uint32_t ppage) const {
        if (ppage >= LOW_32BIT_PPAGE_LIMIT) return false;
        if (watched_l1_page_ != 0xFFFFFFFFu && ppage >= watched_l1_page_ && ppage < watched_l1_page_ + 4u) {
            return true;
        }
        for (std::uint32_t lp : watched_l2_pages_) {
            if (lp == ppage) return true;
        }
        return false;
    }

    void watch_pt_pages(std::uint32_t l1_ppage, std::uint32_t l2_ppage) {
        if (watched_l1_page_ != l1_ppage) {
            watched_l1_page_ = l1_ppage;
            /* Ensure L1 page-table pages cannot be bypassed via direct fastmem page_table */
            for (std::uint32_t i = 0; i < 4u; ++i) {
                unpublish_phys_page(l1_ppage + i);
            }
        }
        if (l2_ppage != 0xFFFFFFFFu) {
            for (std::uint32_t lp : watched_l2_pages_) {
                if (lp == l2_ppage) return;
            }
            if (watched_l2_pages_.size() < 256) {
                watched_l2_pages_.push_back(l2_ppage);
                unpublish_phys_page(l2_ppage);
            }
        }
    }

    /*
     * Fast-path MMIO translation check avoiding full TranslationResult construction:
     *   - When MMU is disabled (SCTLR.M == 0), checks bus.is_mmio(va) in O(1) for identity PA = VA.
     *   - When MMU is enabled, checks dedicated 1-entry mmio_mru_entry_ (isolated from code/RAM MRU).
     * Returns true and sets out_pa only on a verified, permitted 32-bit MMIO hit.
     */
    inline bool try_translate_identity_mmio(const PhysMemoryBus& bus, const Cp15State& cp15,
                                            std::uint32_t va, std::uint32_t access, bool user_mode,
                                            std::uint32_t& out_pa) const {
        if ((cp15.sctlr & 1u) == 0u) {
            if (access != 0u && bus.is_mmio(va)) {
                out_pa = va;
                return true;
            }
            return false;
        }
        const std::uint32_t vpage = va >> 12;
        const std::uint32_t tag = vpage | (user_mode ? (1u << 20) : 0u);
        if (mmio_mru_entry_.vpage_tag == tag &&
            ((mmio_mru_entry_.flags & FLAG_GLOBAL) != 0u || mmio_mru_entry_.asid == cp15.current_asid()) &&
            access != 0u && (mmio_mru_entry_.flags & (FLAG_MMIO | access)) == (FLAG_MMIO | access)) [[likely]] {
            out_pa = (mmio_mru_entry_.ppage << 12) | (va & 0xFFFu);
            return true;
        }
        return false;
    }

    inline TranslationResult translate(const PhysMemoryBus& bus, const Cp15State& cp15,
                                       std::uint32_t va, std::uint32_t access, bool user_mode,
                                       bool populate_fast_pt) {
        const std::uint32_t vpage = va >> 12;
        const std::uint32_t tag = vpage | (user_mode ? (1u << 20) : 0u);
        const std::uint8_t cur_asid = cp15.current_asid();

        /* 1-entry MRU micro-TLB fast-check for strong spatial locality */
        if (mru_entry_.vpage_tag == tag &&
            ((mru_entry_.flags & FLAG_GLOBAL) != 0u || mru_entry_.asid == cur_asid)) [[likely]] {
            if (access != 0u && (mru_entry_.flags & access) == access) [[likely]] {
                return build_hit_result(mru_entry_, va);
            }
        }

        /* Dedicated 1-entry MMIO MRU check (prevents code/RAM fetches from evicting hot MMIO pages) */
        if (mmio_mru_entry_.vpage_tag == tag &&
            ((mmio_mru_entry_.flags & FLAG_GLOBAL) != 0u || mmio_mru_entry_.asid == cur_asid)) {
            if (access != 0u && (mmio_mru_entry_.flags & access) == access) {
                return build_hit_result(mmio_mru_entry_, va);
            }
        }

        const std::uint32_t idx = vpage & TLB_MASK;
        const TlbEntry& cached = tlb_[idx];
        if (cached.vpage_tag == tag &&
            ((cached.flags & FLAG_GLOBAL) != 0u || cached.asid == cur_asid)) [[likely]] {
            if (access != 0u && (cached.flags & access) == access) [[likely]] {
                if ((cached.flags & FLAG_MMIO) != 0u) {
                    mmio_mru_entry_ = cached;
                } else {
                    mru_entry_ = cached;
                }
                return build_hit_result(cached, va);
            }
        }
        return translate_slow(bus, cp15, va, access, user_mode, populate_fast_pt);
    }

    std::uint32_t translate_for_ats(const PhysMemoryBus& bus, const Cp15State& cp15,
                                    std::uint32_t va, std::uint32_t access, bool user_mode) {
        auto res = translate_slow(bus, cp15, va, access, user_mode, false);
        if (!res.ok) {
            return 1u | ((res.fault_status & 0x3Fu) << 1);
        }
        /* ARMv7-A Short-Descriptor PAR[31:12] = PA[31:12]; Supersection PAR[23:16] = PA[39:32] when SS=1 (bit 1). */
        if (res.phys_addr >= PHYS_ADDR_32BIT) {
            const std::uint32_t pa_hi = static_cast<std::uint32_t>((res.phys_addr >> 32) & 0xFFu);
            return (static_cast<std::uint32_t>(res.phys_addr) & 0xFF000000u) | (pa_hi << 16) | 2u;
        }
        return static_cast<std::uint32_t>(res.phys_addr) & 0xFFFFF000u;
    }

private:
    static bool is_device_or_strongly_ordered(const Cp15State& cp15, std::uint32_t tex, bool c, bool b) {
        if ((cp15.sctlr & (1u << 28)) != 0u) {
            /* SCTLR.TRE == 1: PRRR[2*idx+1 : 2*idx]: 0b00 = Strongly-ordered, 0b01 = Device,
             * 0b10 = Normal memory (whether NMRR marks it Cacheable or Non-cacheable). */
            const std::uint32_t idx = ((tex & 1u) << 2) | (c ? 2u : 0u) | (b ? 1u : 0u);
            const std::uint32_t tr = (cp15.prrr >> (idx * 2u)) & 3u;
            return tr == 0u || tr == 1u;
        }
        /* SCTLR.TRE == 0 (ARMv7-A DDI 0406C Table B3-10):
         *   TEX[2] == 1                : Normal memory (outer=TEX[1:0], inner=C:B)
         *   TEX == 0, C == 0, B == 0/1 : Strongly-ordered (B=0) / Shareable Device (B=1)
         *   TEX == 1, C == 0, B == 0   : Normal Outer & Inner Non-cacheable (NOT Device)
         *   TEX == 2, C == 0, B == 0   : Non-shareable Device */
        if ((tex & 4u) != 0u) {
            return false;
        }
        switch (tex & 3u) {
        case 0u: return !c;
        case 2u: return !c && !b;
        default: return false;
        }
    }

    static bool check_ap(std::uint32_t ap2, std::uint32_t ap10, bool afe, bool user_mode, bool is_write) {
        if (afe) {
            if ((ap10 & 1u) == 0u) return false;
            const bool allow_user = (ap10 & 2u) != 0u;
            if (user_mode && !allow_user) return false;
            if (is_write && ap2 != 0u) return false;
            return true;
        }
        const std::uint32_t ap = (ap2 << 2) | (ap10 & 3u);
        switch (ap) {
        case 0u: return false;
        case 1u: return !user_mode;
        case 2u: return !user_mode || !is_write;
        case 3u: return true;
        case 5u: return !user_mode && !is_write;
        case 6u:
        case 7u: return !is_write;
        default: return false;
        }
    }

    std::uint8_t* compute_pt_entry(std::uint32_t vpage, std::uint8_t* host_page_ptr) const {
        if (!absolute_offset_page_table_) {
            return host_page_ptr;
        }
        const std::uintptr_t raw =
            reinterpret_cast<std::uintptr_t>(host_page_ptr) - (static_cast<std::uintptr_t>(vpage) << 12);
        if (raw == 0u) [[unlikely]] {
            return nullptr; /* Guard against accidental nullptr sentinel collision */
        }
        return reinterpret_cast<std::uint8_t*>(raw);
    }

    void publish_fast_pt(std::uint32_t vpage, std::uint8_t* host_page_ptr, bool writable) {
        std::uint8_t* entry = compute_pt_entry(vpage, host_page_ptr);
        if (entry == nullptr) [[unlikely]] return;
        const bool was_clean = ((*page_table_)[vpage] == nullptr) && ((*read_page_table_)[vpage] == nullptr);
        if (was_clean) {
            dirty_pages_.push_back(vpage);
        }
        (*read_page_table_)[vpage] = entry;
        if (writable) {
            (*page_table_)[vpage] = entry;
        }
    }

    void unpublish_phys_page(std::uint32_t ppage) {
        for (std::uint32_t vpage : dirty_pages_) {
            const auto& e = tlb_[vpage & TLB_MASK];
            if ((e.vpage_tag & 0xFFFFFu) != vpage || e.ppage == ppage) {
                (*page_table_)[vpage] = nullptr;
            }
        }
    }

    TranslationResult translate_slow(const PhysMemoryBus& bus, const Cp15State& cp15,
                                     std::uint32_t va, std::uint32_t access, bool user_mode,
                                     bool populate_fast_pt) {
        TranslationResult res;
        const bool is_write = (access == PERM_WRITE);
        const bool is_exec = (access == PERM_EXEC);
        const std::uint32_t wnr_bit = is_write ? (1u << 11) : 0u;

        bool can_r = true;
        bool can_w = true;
        bool can_x = true;
        std::uint64_t pa = static_cast<std::uint64_t>(va);
        bool device_or_so = false;
        bool is_global = true;
        const std::uint8_t cur_asid = cp15.current_asid();

        if ((cp15.sctlr & 1u) != 0u) {
            const std::uint32_t ttbcr = cp15.ttbcr;
            const std::uint32_t n = ttbcr & 7u;
            const bool use_ttbr1 = (n > 0u) && ((va >> (32u - n)) != 0u);
            if ((!use_ttbr1 && (ttbcr & (1u << 4)) != 0u) ||
                (use_ttbr1 && (ttbcr & (1u << 5)) != 0u)) {
                res.fault_status = wnr_bit | FAULT_SECTION_TRANSLATION;
                return res;
            }
            const std::uint32_t ttbr = use_ttbr1 ? cp15.ttbr1 : cp15.ttbr0;
            const std::uint32_t ttbr_mask = use_ttbr1 ? 0xFFFFC000u : ((0xFFFFC000u >> n) & 0xFFFFFF80u);
            const std::uint32_t l1_base = ttbr & ttbr_mask;
            const std::uint32_t l1_idx = use_ttbr1 ? (va >> 20) : ((va >> 20) & ((0x1000u >> n) - 1u));
            const std::uint32_t l1_addr = l1_base + (l1_idx << 2);

            std::uint32_t l1 = 0;
            if (!bus.read_phys_u32(l1_addr, l1)) {
                res.fault_status = wnr_bit | FAULT_L1_EXTERNAL_ABORT;
                return res;
            }

            const bool afe = (cp15.sctlr & (1u << 29)) != 0u;
            const std::uint32_t l1_type = l1 & 3u;

            if (l1_type == 0u || l1_type == 3u) {
                res.fault_status = wnr_bit | FAULT_SECTION_TRANSLATION;
                return res;
            }

            if (l1_type == 2u) {
                const bool is_super = (l1 & (1u << 18)) != 0u;
                const std::uint32_t dom = is_super ? 0u : ((l1 >> 5) & 0xFu);
                const std::uint32_t dom_acc = (cp15.dacr >> (dom * 2u)) & 3u;
                res.domain = dom;
                if (dom_acc == 0u || dom_acc == 2u) {
                    res.fault_status = wnr_bit | (dom << 4) | FAULT_SECTION_DOMAIN;
                    return res;
                }
                if (is_super) {
                    /* ARMv7-A DDI 0406C B3.5.1 16 MiB Supersection 40-bit PA:
                     * PA[31:24] = L1[31:24], PA[35:32] = L1[23:20], PA[39:36] = L1[8:5], PA[23:0] = VA[23:0]. */
                    pa = static_cast<std::uint64_t>(l1 & 0xFF000000u)
                       | (static_cast<std::uint64_t>((l1 >> 20) & 0xFu) << 32)
                       | (static_cast<std::uint64_t>((l1 >> 5) & 0xFu) << 36)
                       | static_cast<std::uint64_t>(va & 0x00FFFFFFu);
                } else {
                    pa = static_cast<std::uint64_t>((l1 & 0xFFF00000u) | (va & 0x000FFFFFu));
                }
                is_global = ((l1 & (1u << 17)) == 0u);
                const bool xn = (l1 & (1u << 4)) != 0u;
                const bool pxn = (l1 & 1u) != 0u;
                const std::uint32_t ap2 = (l1 >> 15) & 1u;
                const std::uint32_t ap10 = (l1 >> 10) & 3u;
                const std::uint32_t tex = (l1 >> 12) & 7u;
                const bool c = (l1 & (1u << 3)) != 0u;
                const bool b = (l1 & (1u << 2)) != 0u;
                device_or_so = is_device_or_strongly_ordered(cp15, tex, c, b);

                if (dom_acc == 3u) {
                    can_r = can_w = can_x = true;
                } else {
                    if (afe && (ap10 & 1u) == 0u) {
                        res.fault_status = wnr_bit | (dom << 4) | FAULT_SECTION_ACCESS_FLAG;
                        return res;
                    }
                    can_r = check_ap(ap2, ap10, afe, user_mode, false);
                    can_w = check_ap(ap2, ap10, afe, user_mode, true);
                    can_x = can_r && !(xn || (!user_mode && pxn));
                }
                watch_pt_pages(l1_base >> 12, 0xFFFFFFFFu);

                if ((is_exec && !can_x) || (is_write && !can_w) || (!is_exec && !is_write && !can_r)) {
                    res.fault_status = wnr_bit | (dom << 4) | FAULT_SECTION_PERMISSION;
                    return res;
                }
            } else {
                /* l1_type == 1: Page table */
                const std::uint32_t dom = (l1 >> 5) & 0xFu;
                const std::uint32_t dom_acc = (cp15.dacr >> (dom * 2u)) & 3u;
                res.domain = dom;
                if (dom_acc == 0u || dom_acc == 2u) {
                    res.fault_status = wnr_bit | (dom << 4) | FAULT_PAGE_DOMAIN;
                    return res;
                }
                const bool pxn = (l1 & (1u << 2)) != 0u;
                const std::uint32_t l2_base = l1 & 0xFFFFFC00u;
                const std::uint32_t l2_addr = l2_base + (((va >> 12) & 0xFFu) << 2);
                std::uint32_t l2 = 0;
                if (!bus.read_phys_u32(l2_addr, l2)) {
                    res.fault_status = wnr_bit | (dom << 4) | FAULT_L2_EXTERNAL_ABORT;
                    return res;
                }
                const std::uint32_t l2_type = l2 & 3u;
                if (l2_type == 0u) {
                    res.fault_status = wnr_bit | (dom << 4) | FAULT_PAGE_TRANSLATION;
                    return res;
                }
                const bool is_large = (l2_type == 1u);
                pa = static_cast<std::uint64_t>(
                    is_large ? ((l2 & 0xFFFF0000u) | (va & 0xFFFFu))
                             : ((l2 & 0xFFFFF000u) | (va & 0xFFFu)));
                is_global = ((l2 & (1u << 11)) == 0u);
                const bool xn = is_large ? ((l2 & (1u << 15)) != 0u) : ((l2 & 1u) != 0u);
                const std::uint32_t ap2 = (l2 >> 9) & 1u;
                const std::uint32_t ap10 = (l2 >> 4) & 3u;
                const std::uint32_t tex = is_large ? ((l2 >> 12) & 7u) : ((l2 >> 6) & 7u);
                const bool c = (l2 & (1u << 3)) != 0u;
                const bool b = (l2 & (1u << 2)) != 0u;
                device_or_so = is_device_or_strongly_ordered(cp15, tex, c, b);

                if (dom_acc == 3u) {
                    can_r = can_w = can_x = true;
                } else {
                    if (afe && (ap10 & 1u) == 0u) {
                        res.fault_status = wnr_bit | (dom << 4) | FAULT_PAGE_ACCESS_FLAG;
                        return res;
                    }
                    can_r = check_ap(ap2, ap10, afe, user_mode, false);
                    can_w = check_ap(ap2, ap10, afe, user_mode, true);
                    can_x = can_r && !(xn || (!user_mode && pxn));
                }
                watch_pt_pages(l1_base >> 12, l2_base >> 12);

                if ((is_exec && !can_x) || (is_write && !can_w) || (!is_exec && !is_write && !can_r)) {
                    res.fault_status = wnr_bit | (dom << 4) | FAULT_PAGE_PERMISSION;
                    return res;
                }
            }
        }

        /* Classify physical page (RAM vs MMIO vs unmapped) */
        const std::uint64_t ppage_base = pa & ~0xFFFull;
        const RamRegion* ram = bus.find_ram(ppage_base, 4096u);
        std::uint8_t* host_page_ptr = nullptr;
        bool is_ram = false;
        bool is_mmio = false;
        if (ram != nullptr) {
            is_ram = true;
            host_page_ptr = ram->ptr + (ppage_base - ram->base);
            can_r = can_r && ((ram->perms & PERM_READ) != 0u);
            can_w = can_w && ((ram->perms & PERM_WRITE) != 0u);
            can_x = can_x && ((ram->perms & PERM_EXEC) != 0u);
            if ((is_exec && !can_x) || (is_write && !can_w) || (!is_exec && !is_write && !can_r)) {
                res.fault_status = wnr_bit | FAULT_PAGE_PERMISSION;
                return res;
            }
        } else if (bus.is_mmio(pa)) {
            is_mmio = true;
        } else {
            const RamRegion* sub_ram = bus.find_ram(pa, 1u);
            if (sub_ram != nullptr) {
                is_ram = true;
            } else {
                res.fault_status = wnr_bit | FAULT_SYNC_EXTERNAL_ABORT;
                return res;
            }
        }

        res.ok = true;
        res.phys_addr = pa;
        res.is_ram = is_ram;
        res.is_mmio = is_mmio;
        res.can_read = can_r;
        res.can_write = can_w;
        res.can_exec = can_x;
        res.device_or_strongly_ordered = device_or_so;
        res.is_global = is_global;
        res.asid = cur_asid;
        res.host_page_ptr = host_page_ptr;

        /* Cache into 4096-entry direct-mapped software TLB and 1-entry MRU micro-TLB */
        const std::uint32_t vpage = va >> 12;
        const std::uint32_t ppage = static_cast<std::uint32_t>(pa >> 12);
        if (is_ram || is_mmio) [[likely]] {
            TlbEntry& ent = tlb_[vpage & TLB_MASK];
            ent.vpage_tag = vpage | (user_mode ? (1u << 20) : 0u);
            ent.ppage = ppage;
            ent.host_page_ptr = host_page_ptr;
            ent.flags = static_cast<std::uint8_t>(
                (can_r ? FLAG_READ : 0u) |
                (can_w ? FLAG_WRITE : 0u) |
                (can_x ? FLAG_EXEC : 0u) |
                (is_ram ? FLAG_RAM : 0u) |
                (is_mmio ? FLAG_MMIO : 0u) |
                (device_or_so ? FLAG_DEVICE_SO : 0u) |
                (is_global ? FLAG_GLOBAL : 0u));
            ent.asid = cur_asid;
            if (is_mmio) {
                mmio_mru_entry_ = ent;
            } else {
                mru_entry_ = ent;
            }
        }

        /* Publish into Dynarmic's 1M-entry fastmem tables:
         *   - read_page_table_ : all readable 4 KiB-aligned RAM pages (including RO .text/.rodata and >4 GiB high DRAM)
         *   - page_table_      : strictly RW non-PT, non-code-watched RAM pages */
        if (populate_fast_pt && is_ram && host_page_ptr != nullptr && can_r) {
            const bool allow_write = can_w && !is_watched_pt_page(ppage) && !is_watched_code_page(ppage);
            publish_fast_pt(vpage, host_page_ptr, allow_write);
        }
        return res;
    }

    static inline TranslationResult build_hit_result(const TlbEntry& entry, std::uint32_t va) {
        TranslationResult res;
        res.ok = true;
        res.phys_addr = (static_cast<std::uint64_t>(entry.ppage) << 12) | static_cast<std::uint64_t>(va & 0xFFFu);
        res.can_read = (entry.flags & FLAG_READ) != 0u;
        res.can_write = (entry.flags & FLAG_WRITE) != 0u;
        res.can_exec = (entry.flags & FLAG_EXEC) != 0u;
        res.is_ram = (entry.flags & FLAG_RAM) != 0u;
        res.is_mmio = (entry.flags & FLAG_MMIO) != 0u;
        res.device_or_strongly_ordered = (entry.flags & FLAG_DEVICE_SO) != 0u;
        res.is_global = (entry.flags & FLAG_GLOBAL) != 0u;
        res.asid = entry.asid;
        res.host_page_ptr = entry.host_page_ptr;
        return res;
    }

    TlbEntry mru_entry_{};
    TlbEntry mmio_mru_entry_{};
    std::array<TlbEntry, TLB_ENTRIES> tlb_{};
    std::unique_ptr<std::array<std::uint8_t*, PAGE_TABLE_SIZE>> page_table_;
    std::unique_ptr<std::array<std::uint8_t*, PAGE_TABLE_SIZE>> read_page_table_;
    bool absolute_offset_page_table_ = false;
    bool code_write_watch_enabled_ = false;
    std::unique_ptr<std::array<std::uint64_t, 16384>> watched_code_pages_;
    std::vector<std::uint32_t> dirty_pages_;
    std::uint32_t watched_l1_page_ = 0xFFFFFFFFu;
    std::vector<std::uint32_t> watched_l2_pages_;
    bool ctrl_synced_ = false;
    std::uint32_t synced_mmu_ctrl_ = 0u;
    std::uint32_t synced_ttbr0_ = 0u;
    std::uint32_t synced_ttbr1_ = 0u;
    std::uint32_t synced_ttbcr_ = 0u;
    std::uint32_t synced_dacr_ = 0u;
    std::uint32_t synced_prrr_ = 0u;
    std::uint32_t synced_nmrr_ = 0u;
    std::uint8_t synced_asid_ = 0u;
};

}  // namespace Dynarmic::A32Sys

#endif  /* FUJI_DYNARMIC_MMU_TLB_HPP */
