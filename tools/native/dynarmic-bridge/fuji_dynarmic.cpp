#include "fuji_dynarmic.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <bit>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <optional>
#include <thread>
#include <unordered_set>
#include <vector>
#if defined(__x86_64__) || defined(_M_X64)
#include <immintrin.h>
#endif

#include <dynarmic/frontend/A32/a32_ir_emitter.h>
#include <dynarmic/interface/A32/a32.h>
#include <dynarmic/interface/A32/arch_version.h>
#include <dynarmic/interface/A32/config.h>
#include <dynarmic/interface/exclusive_monitor.h>
#include <dynarmic/interface/halt_reason.h>
#include <dynarmic/ir/terminal.h>

#include <dynarmic/a32_sys/cp15.hpp>
#include <dynarmic/a32_sys/exceptions.hpp>
#include <dynarmic/a32_sys/gic_v2_fastpath.hpp>
#include "fuji_mmu_tlb.hpp"
#include <dynarmic/a32_sys/mmio_fastpath.hpp>

#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#undef near
#undef far
#elif defined(__linux__)
#include <fcntl.h>
#include <signal.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>
#ifndef FALLOC_FL_KEEP_SIZE
#define FALLOC_FL_KEEP_SIZE 0x01
#endif
#ifndef FALLOC_FL_PUNCH_HOLE
#define FALLOC_FL_PUNCH_HOLE 0x02
#endif
#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif
#endif

using namespace Dynarmic::A32Sys;

static_assert(FUJI_DYN_PERM_READ == PERM_READ, "PERM_READ ABI mismatch");
static_assert(FUJI_DYN_PERM_WRITE == PERM_WRITE, "PERM_WRITE ABI mismatch");
static_assert(FUJI_DYN_PERM_EXEC == PERM_EXEC, "PERM_EXEC ABI mismatch");
static_assert(FUJI_DYN_MODE_USR == MODE_USR && FUJI_DYN_MODE_SVC == MODE_SVC &&
              FUJI_DYN_MODE_IRQ == MODE_IRQ && FUJI_DYN_MODE_FIQ == MODE_FIQ &&
              FUJI_DYN_MODE_ABT == MODE_ABT && FUJI_DYN_MODE_UND == MODE_UND &&
              FUJI_DYN_MODE_SYS == MODE_SYS, "ARM mode ABI mismatch");
static_assert(FUJI_DYN_BANKED_SPSR == BANKED_SPSR, "BANKED_SPSR ABI mismatch");
static_assert(FUJI_DYN_TIMER_CHANNELS == TimerShadowBank<FUJI_DYN_TIMER_CHANNELS>::MAX_CHANNELS, "Timer channels mismatch");
static_assert(FUJI_DYN_RO_MMIO_SLOTS == RoMmioShadowTable<FUJI_DYN_RO_MMIO_SLOTS>::CAPACITY, "RO-MMIO slots mismatch");
static_assert(sizeof(FujiDynGicCoreShadow) == sizeof(GicV2CoreShadow), "FujiDynGicCoreShadow size mismatch");
static_assert(sizeof(FujiDynGicShadow) == sizeof(GicV2ShadowState), "FujiDynGicShadow size mismatch");
static_assert(offsetof(FujiDynGicShadow, cores) == offsetof(GicV2ShadowState, cores), "FujiDynGicShadow::cores offset mismatch");

/* ============================================================================
 * DYN-V3 Hardware 4 GiB Fastmem Arena + Async-Signal-Safe JVM SIGSEGV Wrapper
 * ============================================================================ */

namespace {

constexpr uint64_t FASTMEM_ARENA_BYTES = 0x100010000ull; /* 4 GiB + 64 KiB guard (64 KiB gran for Win32 placeholders) */
#ifdef MADV_HUGEPAGE
constexpr uint32_t THP_MIN_BYTES = 2u * 1024u * 1024u;
#endif

static inline void fuji_cpu_pause() {
#if defined(__x86_64__) || defined(_M_X64)
    _mm_pause();
#else
    std::this_thread::yield();
#endif
}

#ifdef _WIN32
#ifndef MEM_RESERVE_PLACEHOLDER
#define MEM_RESERVE_PLACEHOLDER 0x40000000UL
#endif
#ifndef MEM_REPLACE_PLACEHOLDER
#define MEM_REPLACE_PLACEHOLDER 0x00004000UL
#endif
#ifndef MEM_PRESERVE_PLACEHOLDER
#define MEM_PRESERVE_PLACEHOLDER 0x00000002UL
#endif

struct WinFastmemApis {
    using PFN_VirtualAlloc2 = PVOID (WINAPI*)(HANDLE, PVOID, SIZE_T, ULONG, ULONG, void*, ULONG);
    using PFN_MapViewOfFile3 = PVOID (WINAPI*)(HANDLE, HANDLE, PVOID, ULONG64, SIZE_T, ULONG, ULONG, void*, ULONG);
    using PFN_UnmapViewOfFile2 = BOOL (WINAPI*)(HANDLE, PVOID, ULONG);
    PFN_VirtualAlloc2 virtual_alloc2 = nullptr;
    PFN_MapViewOfFile3 map_view_of_file3 = nullptr;
    PFN_UnmapViewOfFile2 unmap_view_of_file2 = nullptr;
    bool resolved = false;

    bool init() {
        if (resolved) return virtual_alloc2 && map_view_of_file3 && unmap_view_of_file2;
        resolved = true;
        HMODULE kb = GetModuleHandleA("kernelbase.dll");
        if (!kb) kb = LoadLibraryA("kernelbase.dll");
        HMODULE k32 = GetModuleHandleA("kernel32.dll");
        auto load_fn = [&](const char* name) -> FARPROC {
            FARPROC p = kb ? GetProcAddress(kb, name) : nullptr;
            if (!p && k32) p = GetProcAddress(k32, name);
            return p;
        };
        virtual_alloc2 = reinterpret_cast<PFN_VirtualAlloc2>(reinterpret_cast<void*>(load_fn("VirtualAlloc2")));
        map_view_of_file3 = reinterpret_cast<PFN_MapViewOfFile3>(reinterpret_cast<void*>(load_fn("MapViewOfFile3")));
        unmap_view_of_file2 = reinterpret_cast<PFN_UnmapViewOfFile2>(reinterpret_cast<void*>(load_fn("UnmapViewOfFile2")));
        return virtual_alloc2 && map_view_of_file3 && unmap_view_of_file2;
    }
};

WinFastmemApis g_win_fm_apis;
#endif

enum FastmemProt : uint8_t {
    FM_NONE = 0,
    FM_RO = 1,
    FM_RW = 2,
    FM_MIXED = 3,
};

#if defined(__linux__)
constexpr size_t MAX_FASTMEM_SLOTS = 64;
std::atomic<uintptr_t> g_fastmem_bases[MAX_FASTMEM_SLOTS]{};
std::mutex g_sig_mutex;
bool g_sig_wrapped = false;
bool g_sig_jvm_captured = false;
uint32_t g_live_sig_engines = 0u;
struct sigaction g_jvm_segv{};
struct sigaction g_dynarmic_segv{};

void fuji_segv_action(int sig, siginfo_t* info, void* uctx) {
    const uintptr_t fault = info ? reinterpret_cast<uintptr_t>(info->si_addr) : 0u;
    if (fault != 0u) {
        for (size_t i = 0; i < MAX_FASTMEM_SLOTS; ++i) {
            const uintptr_t base = g_fastmem_bases[i].load(std::memory_order_acquire);
            if (base != 0u && fault >= base && (fault - base) < FASTMEM_ARENA_BYTES) {
                if ((g_dynarmic_segv.sa_flags & SA_SIGINFO) != 0 && g_dynarmic_segv.sa_sigaction != nullptr) {
                    g_dynarmic_segv.sa_sigaction(sig, info, uctx);
                    return;
                }
                break;
            }
        }
    }
    /* Non-fastmem fault (e.g. HotSpot JVM implicit null-check, safepoint poll, GC barrier):
     * chain silently & lock-free directly to the saved prior handler. */
    if ((g_jvm_segv.sa_flags & SA_SIGINFO) != 0 && g_jvm_segv.sa_sigaction != nullptr) {
        g_jvm_segv.sa_sigaction(sig, info, uctx);
        return;
    }
    if (g_jvm_segv.sa_handler == SIG_IGN) {
        return;
    }
    if (g_jvm_segv.sa_handler != SIG_DFL && g_jvm_segv.sa_handler != nullptr) {
        g_jvm_segv.sa_handler(sig);
        return;
    }
    signal(sig, SIG_DFL);
    raise(sig);
}

void fuji_sig_before_jit_create() {
    std::lock_guard<std::mutex> lock(g_sig_mutex);
    if (!g_sig_jvm_captured) {
        if (sigaction(SIGSEGV, nullptr, &g_jvm_segv) == 0) {
            g_sig_jvm_captured = true;
        }
    }
}

void fuji_sig_after_jit_create() {
    std::lock_guard<std::mutex> lock(g_sig_mutex);
    ++g_live_sig_engines;
    if (g_sig_wrapped) return;
    struct sigaction cur{};
    if (sigaction(SIGSEGV, nullptr, &cur) != 0) return;
    if ((cur.sa_flags & SA_SIGINFO) != 0 && cur.sa_sigaction != nullptr &&
        cur.sa_sigaction != g_jvm_segv.sa_sigaction && cur.sa_sigaction != &fuji_segv_action) {
        g_dynarmic_segv = cur;
    }
    if ((g_dynarmic_segv.sa_flags & SA_SIGINFO) != 0 && g_dynarmic_segv.sa_sigaction != nullptr) {
        struct sigaction wrapper{};
        wrapper.sa_sigaction = &fuji_segv_action;
        wrapper.sa_flags = SA_SIGINFO | SA_ONSTACK | SA_RESTART;
        sigemptyset(&wrapper.sa_mask);
        if (sigaction(SIGSEGV, &wrapper, nullptr) == 0) {
            g_sig_wrapped = true;
        }
    }
}

void fuji_sig_on_engine_destroy() {
    std::lock_guard<std::mutex> lock(g_sig_mutex);
    if (g_live_sig_engines > 0u) --g_live_sig_engines;
    if (g_live_sig_engines == 0u && g_sig_wrapped && g_sig_jvm_captured) {
        if (sigaction(SIGSEGV, &g_jvm_segv, nullptr) == 0) {
            g_sig_wrapped = false;
        }
    }
}

int fuji_register_fastmem_slot(uintptr_t base) {
    for (size_t i = 0; i < MAX_FASTMEM_SLOTS; ++i) {
        uintptr_t expected = 0u;
        if (g_fastmem_bases[i].compare_exchange_strong(expected, base, std::memory_order_release)) {
            return static_cast<int>(i);
        }
    }
    return -1;
}

void fuji_unregister_fastmem_slot(int slot) {
    if (slot >= 0 && static_cast<size_t>(slot) < MAX_FASTMEM_SLOTS) {
        g_fastmem_bases[slot].store(0u, std::memory_order_release);
    }
}

int fuji_create_memfd() {
#if defined(SYS_memfd_create)
    return static_cast<int>(syscall(SYS_memfd_create, "fuji_lpddr4", MFD_CLOEXEC));
#else
    return -1;
#endif
}

void fuji_apply_thp_to_jit_code_caches() {
#ifdef MADV_HUGEPAGE
    FILE* fp = std::fopen("/proc/self/maps", "r");
    if (!fp) return;
    char line[512];
    while (std::fgets(line, sizeof(line), fp) != nullptr) {
        unsigned long long start = 0, end = 0;
        char perms[8]{};
        int consumed = 0;
        if (std::sscanf(line, "%llx-%llx %7s %*x %*x:%*x %*u %n", &start, &end, perms, &consumed) >= 3) {
            const uint64_t len = (end > start) ? (end - start) : 0ull;
            if (len >= 32ull * 1024ull * 1024ull && perms[2] == 'x') {
                const char* tail = line + consumed;
                while (*tail == ' ' || *tail == '\t') ++tail;
                if (*tail == '\0' || *tail == '\n') {
                    (void)madvise(reinterpret_cast<void*>(static_cast<uintptr_t>(start)),
                                  static_cast<size_t>(len), MADV_HUGEPAGE);
                }
            }
        }
    }
    std::fclose(fp);
#endif
}
#endif

struct FastmemRegion {
    uint32_t base = 0u;
    uint32_t size = 0u;
    uint64_t end = 0u;
    uint8_t* host_rw = nullptr;
    uint32_t perms = PERM_ALL;
#ifdef _WIN32
    HANDLE map_handle = nullptr;
#endif
};

struct FastmemSyncSig {
    alignas(4) uint32_t valid = 0u;
    bool mmu_on = false;
    bool user_mode = false;
    bool strict_align = false;
    uint32_t sctlr_bits = 0u;
    uint32_t ttbr0 = 0u;
    uint32_t ttbr1 = 0u;
    uint32_t ttbcr = 0u;
    uint32_t dacr = 0u;
    uint32_t prrr = 0u;
    uint32_t nmrr = 0u;
    uint32_t bus_gen = 0u;
};

struct FastmemCoreView {
    uint8_t* base = nullptr;
#if defined(__linux__)
    int sig_slot = -1;
#endif
    std::array<uint8_t, 4096> sec_base_prot{};
    std::array<uint64_t, 16384> protected_4k_pages{};
    std::vector<uint32_t> ro_4k_pages;
    uint32_t last_l1_page = 0xFFFFFFFFu;
    std::vector<uint32_t> last_l2_pages;
    std::vector<uint32_t> last_l2_secs;
    bool needs_ro_reset = false;
    alignas(4) uint32_t pt_dirty = 0u;
    FastmemSyncSig sig{};

    inline bool is_page_protected(uint32_t page) const {
        const uint32_t p = page & 0xFFFFFu;
        return (protected_4k_pages[p >> 6] & (1ull << (p & 63u))) != 0ull;
    }

    inline void mark_page_protected(uint32_t page) {
        const uint32_t p = page & 0xFFFFFu;
        const uint64_t bit = 1ull << (p & 63u);
        uint64_t& w = protected_4k_pages[p >> 6];
        if ((w & bit) == 0ull) {
            w |= bit;
            ro_4k_pages.push_back(p);
        }
    }

    inline void clear_protected_pages_fast() {
        for (uint32_t p : ro_4k_pages) {
            protected_4k_pages[p >> 6] &= ~(1ull << (p & 63u));
        }
        ro_4k_pages.clear();
    }
};

struct FujiFastmemArena {
    uint32_t num_views = 0u;
    bool dual_view = false;
    uint32_t bus_gen = 0u;
#if defined(__linux__)
    int ram_fd = -1;
#endif
    std::array<FastmemCoreView, FUJI_DYN_MAX_CORES> views{};
    std::vector<FastmemRegion> regions;

    bool init(uint32_t core_count) {
        const uint32_t clamped = std::max<uint32_t>(1u, std::min<uint32_t>(core_count, FUJI_DYN_MAX_CORES));
        for (auto& v : views) {
            v.base = nullptr;
#if defined(__linux__)
            v.sig_slot = -1;
#endif
            v.sec_base_prot.fill(FM_NONE);
            v.protected_4k_pages.fill(0ull);
            v.ro_4k_pages.clear();
            v.ro_4k_pages.reserve(1024);
            v.last_l1_page = 0xFFFFFFFFu;
            v.last_l2_pages.clear();
            v.last_l2_secs.clear();
            v.needs_ro_reset = false;
            v.pt_dirty = false;
            v.sig = FastmemSyncSig{};
        }
#if defined(__linux__)
        ram_fd = fuji_create_memfd();
        if (ram_fd < 0) return false;
        if (ftruncate(ram_fd, static_cast<off_t>(0x100000000LL)) != 0) {
            close(ram_fd);
            ram_fd = -1;
            return false;
        }
        for (uint32_t i = 0u; i < clamped; ++i) {
            void* p = mmap(nullptr, static_cast<size_t>(FASTMEM_ARENA_BYTES), PROT_NONE,
                           MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE, -1, 0);
            if (p == MAP_FAILED) {
                destroy();
                return false;
            }
            views[i].base = static_cast<uint8_t*>(p);
            views[i].sig_slot = fuji_register_fastmem_slot(reinterpret_cast<uintptr_t>(p));
            if (views[i].sig_slot < 0) {
                destroy();
                return false;
            }
            num_views = i + 1u;
        }
        dual_view = true;
        return true;
#elif defined(_WIN32)
        if (!g_win_fm_apis.init()) return false;
        for (uint32_t i = 0u; i < clamped; ++i) {
            void* p = g_win_fm_apis.virtual_alloc2(
                GetCurrentProcess(), nullptr, static_cast<SIZE_T>(FASTMEM_ARENA_BYTES),
                MEM_RESERVE | MEM_RESERVE_PLACEHOLDER, PAGE_NOACCESS, nullptr, 0u);
            if (p == nullptr) {
                destroy();
                return false;
            }
            views[i].base = static_cast<uint8_t*>(p);
            num_views = i + 1u;
        }
        dual_view = true;
        return true;
#else
        (void)clamped;
        return false;
#endif
    }

    ~FujiFastmemArena() {
        destroy();
    }

    void destroy() {
#if defined(__linux__)
        for (const auto& r : regions) {
            if (r.host_rw != nullptr) {
                munmap(r.host_rw, r.size);
            }
        }
        regions.clear();
        for (auto& v : views) {
            if (v.sig_slot >= 0) {
                fuji_unregister_fastmem_slot(v.sig_slot);
                v.sig_slot = -1;
            }
            if (v.base != nullptr) {
                munmap(v.base, static_cast<size_t>(FASTMEM_ARENA_BYTES));
                v.base = nullptr;
            }
            v.sig.valid = false;
        }
        if (ram_fd >= 0) {
            close(ram_fd);
            ram_fd = -1;
        }
#elif defined(_WIN32)
        for (const auto& r : regions) {
            for (uint32_t i = 0u; i < num_views; ++i) {
                if (views[i].base != nullptr && g_win_fm_apis.unmap_view_of_file2 != nullptr) {
                    (void)g_win_fm_apis.unmap_view_of_file2(
                        GetCurrentProcess(), views[i].base + r.base, MEM_PRESERVE_PLACEHOLDER);
                }
            }
            if (r.host_rw != nullptr) {
                UnmapViewOfFile(r.host_rw);
            }
            if (r.map_handle != nullptr) {
                CloseHandle(r.map_handle);
            }
        }
        regions.clear();
        for (uint32_t i = 0u; i < num_views; ++i) {
            if (views[i].base != nullptr) {
                uint8_t* cur = views[i].base;
                uint8_t* const limit = views[i].base + FASTMEM_ARENA_BYTES;
                while (cur < limit) {
                    MEMORY_BASIC_INFORMATION mbi{};
                    if (VirtualQuery(cur, &mbi, sizeof(mbi)) == 0 || mbi.RegionSize == 0) break;
                    if (mbi.State == MEM_RESERVE) {
                        (void)VirtualFree(mbi.BaseAddress, 0, MEM_RELEASE);
                    }
                    cur = static_cast<uint8_t*>(mbi.BaseAddress) + mbi.RegionSize;
                }
                views[i].base = nullptr;
            }
            views[i].sig.valid = false;
        }
#endif
        num_views = 0u;
        dual_view = false;
    }

    inline bool active() const { return num_views > 0u && views[0].base != nullptr; }

    inline uint32_t view_index(uint32_t core) const {
        return (dual_view && core < num_views) ? core : 0u;
    }

    inline uint8_t* core_base(uint32_t core) const {
        if (!active()) return nullptr;
        return views[view_index(core)].base;
    }

    inline void invalidate_core(uint32_t core, bool reset_ro = false) {
        if (!active()) return;
        FastmemCoreView& v = views[view_index(core)];
        std::atomic_ref<uint32_t>(v.sig.valid).store(0u, std::memory_order_release);
        if (reset_ro) v.needs_ro_reset = true;
    }

    inline void invalidate_all(bool reset_ro = false) {
        for (uint32_t i = 0u; i < num_views; ++i) {
            std::atomic_ref<uint32_t>(views[i].sig.valid).store(0u, std::memory_order_release);
            if (reset_ro) views[i].needs_ro_reset = true;
        }
    }

    inline bool is_core_synced_fast(uint32_t core, const Cp15State& cp15, bool user_mode) const {
        if (!active()) return true;
        const FastmemCoreView& v = views[view_index(core)];
        if (std::atomic_ref<uint32_t>(const_cast<uint32_t&>(v.sig.valid)).load(std::memory_order_acquire) == 0u) {
            return false;
        }
        const bool mmu_on = (cp15.sctlr & 1u) != 0u;
        const bool strict_align = (cp15.sctlr & 2u) != 0u;
        const uint32_t sctlr_bits = cp15.sctlr & ((1u << 29) | (1u << 28) | 3u);
        return v.sig.mmu_on == mmu_on && v.sig.user_mode == user_mode &&
               v.sig.strict_align == strict_align && v.sig.sctlr_bits == sctlr_bits &&
               v.sig.ttbr0 == cp15.ttbr0 && v.sig.ttbr1 == cp15.ttbr1 &&
               v.sig.ttbcr == cp15.ttbcr && v.sig.dacr == cp15.dacr &&
               v.sig.prrr == cp15.prrr && v.sig.nmrr == cp15.nmrr &&
               v.sig.bus_gen == bus_gen;
    }

    inline bool is_page_protected(uint32_t core, uint32_t page) const {
        if (!active()) return false;
        return views[view_index(core)].is_page_protected(page);
    }

    inline bool has_watched_pt_in_range(uint32_t core, uint32_t first_p, uint32_t last_p) const {
        if (!active()) return false;
        const FastmemCoreView& v = views[view_index(core)];
        if (v.last_l1_page != 0xFFFFFFFFu && last_p >= v.last_l1_page && first_p <= v.last_l1_page + 3u) {
            return true;
        }
        for (uint32_t l2p : v.last_l2_pages) {
            if (l2p >= first_p && l2p <= last_p) return true;
        }
        return false;
    }

    static inline void os_protect_raw(uint8_t* ptr, size_t len, uint8_t prot) {
        if (len == 0u) return;
#if defined(__linux__)
        int p = PROT_NONE;
        if (prot == FM_RO) p = PROT_READ;
        else if (prot == FM_RW) p = PROT_READ | PROT_WRITE;
        (void)mprotect(ptr, len, p);
#elif defined(_WIN32)
        DWORD p = PAGE_NOACCESS;
        if (prot == FM_RO) p = PAGE_READONLY;
        else if (prot == FM_RW) p = PAGE_READWRITE;
        DWORD old_p = 0;
        (void)VirtualProtect(ptr, len, p, &old_p);
#else
        (void)ptr; (void)len; (void)prot;
#endif
    }

    uint8_t* map_backing(uint32_t phys_base, uint32_t size, uint32_t perms) {
        if (!active() || size == 0u || (phys_base & 0xFFFu) != 0u || (size & 0xFFFu) != 0u) return nullptr;
        const uint64_t end = static_cast<uint64_t>(phys_base) + size;
        if (end > 0x100000000ull) return nullptr;
        for (const auto& r : regions) {
            if (static_cast<uint64_t>(phys_base) < r.end && end > r.base) return nullptr;
        }
#if defined(__linux__)
        void* host_rw = mmap(nullptr, size, PROT_READ | PROT_WRITE, MAP_SHARED, ram_fd, static_cast<off_t>(phys_base));
        if (host_rw == MAP_FAILED) return nullptr;
        const int fm_prot = ((perms & PERM_WRITE) != 0u) ? (PROT_READ | PROT_WRITE)
                          : (((perms & PERM_READ) != 0u) ? PROT_READ : PROT_NONE);
        for (uint32_t i = 0u; i < num_views; ++i) {
            void* fm = mmap(views[i].base + phys_base, size, fm_prot, MAP_SHARED | MAP_FIXED, ram_fd, static_cast<off_t>(phys_base));
            if (fm == MAP_FAILED) {
                for (uint32_t j = 0u; j < i; ++j) {
                    (void)mmap(views[j].base + phys_base, size, PROT_NONE,
                               MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE | MAP_FIXED, -1, 0);
                }
                munmap(host_rw, size);
                return nullptr;
            }
#ifdef MADV_HUGEPAGE
            if (size >= THP_MIN_BYTES) {
                (void)madvise(fm, size, MADV_HUGEPAGE);
            }
#endif
        }
#ifdef MADV_HUGEPAGE
        if (size >= THP_MIN_BYTES) {
            (void)madvise(host_rw, size, MADV_HUGEPAGE);
        }
#endif
        FastmemRegion reg{phys_base, size, end, static_cast<uint8_t*>(host_rw), perms & PERM_ALL};
        regions.push_back(reg);
        std::sort(regions.begin(), regions.end(), [](const FastmemRegion& a, const FastmemRegion& b) { return a.base < b.base; });
        mark_sections_mixed_all(phys_base, size);
        ++bus_gen;
        invalidate_all(true);
        return reg.host_rw;
#elif defined(_WIN32)
        /* Windows placeholder splitting requires 64 KiB alignment/size */
        if ((phys_base & 0xFFFFu) != 0u || (size & 0xFFFFu) != 0u) return nullptr;
        HANDLE hmap = CreateFileMappingA(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE, 0u, size, nullptr);
        if (hmap == nullptr) return nullptr;
        void* host_rw = MapViewOfFile(hmap, FILE_MAP_ALL_ACCESS, 0u, 0u, size);
        if (host_rw == nullptr) {
            CloseHandle(hmap);
            return nullptr;
        }
        const DWORD fm_prot = ((perms & PERM_WRITE) != 0u) ? PAGE_READWRITE
                            : (((perms & PERM_READ) != 0u) ? PAGE_READONLY : PAGE_NOACCESS);
        uint32_t mapped_views = 0u;
        for (uint32_t i = 0u; i < num_views; ++i) {
            /* Split placeholder [phys_base, phys_base + size) from enclosing placeholder if needed */
            (void)VirtualFree(views[i].base + phys_base, size, MEM_RELEASE | MEM_PRESERVE_PLACEHOLDER);
            void* fm = g_win_fm_apis.map_view_of_file3(
                hmap, GetCurrentProcess(), views[i].base + phys_base, 0ull, size,
                MEM_REPLACE_PLACEHOLDER, fm_prot, nullptr, 0u);
            if (fm == nullptr) {
                for (uint32_t j = 0u; j < mapped_views; ++j) {
                    (void)g_win_fm_apis.unmap_view_of_file2(
                        GetCurrentProcess(), views[j].base + phys_base, MEM_PRESERVE_PLACEHOLDER);
                }
                UnmapViewOfFile(host_rw);
                CloseHandle(hmap);
                return nullptr;
            }
            ++mapped_views;
        }
        FastmemRegion reg{phys_base, size, end, static_cast<uint8_t*>(host_rw), perms & PERM_ALL, hmap};
        regions.push_back(reg);
        std::sort(regions.begin(), regions.end(), [](const FastmemRegion& a, const FastmemRegion& b) { return a.base < b.base; });
        mark_sections_mixed_all(phys_base, size);
        ++bus_gen;
        invalidate_all(true);
        return reg.host_rw;
#else
        (void)perms;
        return nullptr;
#endif
    }

    bool unmap_backing(uint32_t phys_base, uint32_t size) {
        if (!active() || size == 0u) return false;
        const uint64_t end = static_cast<uint64_t>(phys_base) + size;
        auto it = std::find_if(regions.begin(), regions.end(), [&](const FastmemRegion& r) {
            return r.base == phys_base && r.end == end;
        });
        if (it == regions.end()) return false;
#if defined(__linux__)
        for (uint32_t i = 0u; i < num_views; ++i) {
            (void)mmap(views[i].base + phys_base, size, PROT_NONE,
                       MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE | MAP_FIXED, -1, 0);
        }
        if (it->host_rw != nullptr) {
            munmap(it->host_rw, size);
        }
        (void)fallocate(ram_fd, FALLOC_FL_PUNCH_HOLE | FALLOC_FL_KEEP_SIZE,
                        static_cast<off_t>(phys_base), static_cast<off_t>(size));
#elif defined(_WIN32)
        for (uint32_t i = 0u; i < num_views; ++i) {
            (void)g_win_fm_apis.unmap_view_of_file2(
                GetCurrentProcess(), views[i].base + phys_base, MEM_PRESERVE_PLACEHOLDER);
        }
        if (it->host_rw != nullptr) {
            UnmapViewOfFile(it->host_rw);
        }
        if (it->map_handle != nullptr) {
            CloseHandle(it->map_handle);
        }
#endif
        regions.erase(it);
        mark_sections_mixed_all(phys_base, size);
        ++bus_gen;
        invalidate_all(true);
        return true;
    }

    uint8_t* find_host_rw(uint32_t phys_base) const {
        for (const auto& r : regions) {
            if (r.base == phys_base) return r.host_rw;
        }
        return nullptr;
    }

    void mark_sections_mixed_all(uint32_t start, uint32_t size) {
        if (size == 0u) return;
        const uint32_t s0 = start >> 20;
        const uint32_t s1 = static_cast<uint32_t>((static_cast<uint64_t>(start) + size - 1ull) >> 20);
        for (uint32_t i = 0u; i < num_views; ++i) {
            for (uint32_t s = s0; s <= s1 && s < 4096u; ++s) {
                views[i].sec_base_prot[s] = FM_MIXED;
            }
        }
    }

    void protect_mapped_span_view(const FastmemCoreView& v, uint32_t start, uint32_t len, uint8_t desired_prot) const {
        if (!v.base || len == 0u) return;
        const uint64_t span_s = start;
        const uint64_t span_e = span_s + len;
        for (const auto& r : regions) {
            if (r.end <= span_s) continue;
            if (r.base >= span_e) break;
            const uint64_t s = std::max<uint64_t>(span_s, r.base);
            const uint64_t e = std::min<uint64_t>(span_e, r.end);
            uint8_t eff = desired_prot;
            if (eff == FM_RW && (r.perms & PERM_WRITE) == 0u) {
                eff = ((r.perms & PERM_READ) != 0u) ? FM_RO : FM_NONE;
            } else if (eff == FM_RO && (r.perms & PERM_READ) == 0u) {
                eff = FM_NONE;
            }
            os_protect_raw(v.base + s, static_cast<size_t>(e - s), eff);
        }
    }

    inline void protect_page_ro_all(uint32_t ppage, bool is_identity_va) {
        if (!active()) return;
        const uint32_t va = ppage << 12;
        const uint32_t sec = ppage >> 8;
        for (uint32_t i = 0u; i < num_views; ++i) {
            FastmemCoreView& v = views[i];
            if ((v.sec_base_prot[sec] == FM_RW || (is_identity_va && v.sec_base_prot[sec] == FM_MIXED)) &&
                !v.is_page_protected(ppage)) {
                protect_mapped_span_view(v, va, 4096u, FM_RO);
                v.mark_page_protected(ppage);
            }
        }
    }

    inline bool is_pt_dirty(uint32_t core) const {
        if (!active()) return false;
        return std::atomic_ref<uint32_t>(const_cast<uint32_t&>(views[view_index(core)].pt_dirty)).load(std::memory_order_acquire) != 0u;
    }

    inline bool any_pt_dirty() const {
        for (uint32_t i = 0u; i < num_views; ++i) {
            if (std::atomic_ref<uint32_t>(const_cast<uint32_t&>(views[i].pt_dirty)).load(std::memory_order_acquire) != 0u) return true;
        }
        return false;
    }

    /* O(1) architectural fastpath when guest modifies an active L1/L2 page-table entry:
     * demote ONLY the affected 1 MiB section(s) to FM_NONE and defer full resync until TLBI/CP15.
     * Returns the affected 1 MiB section index (or 0xFFFFFFFFu if unknown/multiple). */
    uint32_t on_pt_write_o1(uint32_t writer_core, uint32_t phys_addr, const Cp15State& cp15) {
        (void)writer_core;
        (void)cp15;
        if (!active()) return 0xFFFFFFFFu;
        const uint32_t ppage = phys_addr >> 12;
        uint32_t affected_sec = 0xFFFFFFFFu;
        uint32_t affected_count = 0u;
        for (uint32_t i = 0u; i < num_views; ++i) {
            FastmemCoreView& v = views[i];
            if (std::atomic_ref<uint32_t>(v.sig.valid).load(std::memory_order_acquire) == 0u || !v.sig.mmu_on) continue;
            if (v.last_l1_page != 0xFFFFFFFFu && ppage >= v.last_l1_page && ppage < v.last_l1_page + 4u) {
                std::atomic_ref<uint32_t>(v.pt_dirty).store(1u, std::memory_order_release);
                const uint32_t l1_base = v.last_l1_page << 12;
                const uint32_t s = (phys_addr - l1_base) >> 2;
                if (s < 4096u) {
                    if (affected_sec != s) {
                        affected_sec = s;
                        ++affected_count;
                    }
                    if (v.sec_base_prot[s] != FM_NONE) {
                        protect_mapped_span_view(v, s << 20, 1u << 20, FM_NONE);
                        v.sec_base_prot[s] = FM_NONE;
                    }
                }
            } else {
                for (size_t k = 0; k < v.last_l2_pages.size(); ++k) {
                    if (v.last_l2_pages[k] == ppage) {
                        std::atomic_ref<uint32_t>(v.pt_dirty).store(1u, std::memory_order_release);
                        if (k < v.last_l2_secs.size()) {
                            const uint32_t s = v.last_l2_secs[k];
                            if (s < 4096u) {
                                if (affected_sec != s) {
                                    affected_sec = s;
                                    ++affected_count;
                                }
                                if (v.sec_base_prot[s] != FM_NONE) {
                                    protect_mapped_span_view(v, s << 20, 1u << 20, FM_NONE);
                                    v.sec_base_prot[s] = FM_NONE;
                                }
                            }
                        }
                    }
                }
            }
        }
        return (affected_count == 1u) ? affected_sec : 0xFFFFFFFFu;
    }

    inline bool has_ram_in_section(uint32_t sec) const {
        const uint64_t s0 = static_cast<uint64_t>(sec) << 20;
        const uint64_t s1 = s0 + (1ull << 20);
        for (const auto& r : regions) {
            if (r.end <= s0) continue;
            if (r.base >= s1) break;
            return true;
        }
        return false;
    }

    void ensure_host_rw_range(uint32_t phys_addr, uint32_t size) {
        if (!active() || dual_view || size == 0u) return;
        /* Single-view (Windows): temporarily restore PAGE_READWRITE on touched pages before host memcpy */
        const uint32_t p0 = phys_addr & ~0xFFFu;
        const uint64_t end_aligned = (static_cast<uint64_t>(phys_addr) + size + 0xFFFu) & ~0xFFFfull;
        const uint32_t len = static_cast<uint32_t>(std::min<uint64_t>(end_aligned, 0x100000000ull) - p0);
        protect_mapped_span_view(views[0], p0, len, FM_RW);
        mark_sections_mixed_all(p0, len);
        invalidate_all(true);
    }

    static bool check_ap_l1(uint32_t ap2, uint32_t ap10, bool afe, bool user_mode, bool is_write) {
        if (afe) {
            if ((ap10 & 1u) == 0u) return false;
            const bool allow_user = (ap10 & 2u) != 0u;
            if (user_mode && !allow_user) return false;
            if (is_write && ap2 != 0u) return false;
            return true;
        }
        const uint32_t ap = (ap2 << 2) | (ap10 & 3u);
        switch (ap) {
        case 1u: return !user_mode;
        case 2u: return !user_mode || !is_write;
        case 3u: return true;
        case 5u: return !user_mode && !is_write;
        case 6u:
        case 7u: return !is_write;
        default: return false;
        }
    }

    void sync_for_core(uint32_t core, const PhysMemoryBus& bus, const Cp15State& cp15, bool user_mode,
                       const std::vector<uint32_t>& watched_code_pages, bool force_resync = false) {
        if (!active()) return;
        FastmemCoreView& v = views[view_index(core)];
        const bool mmu_on = (cp15.sctlr & 1u) != 0u;
        const bool strict_align = (cp15.sctlr & 2u) != 0u;
        const uint32_t sctlr_bits = cp15.sctlr & ((1u << 29) | (1u << 28) | 3u);

        if (!force_resync && std::atomic_ref<uint32_t>(v.sig.valid).load(std::memory_order_acquire) != 0u &&
            v.sig.mmu_on == mmu_on && v.sig.user_mode == user_mode &&
            v.sig.strict_align == strict_align && v.sig.sctlr_bits == sctlr_bits &&
            v.sig.ttbr0 == cp15.ttbr0 && v.sig.ttbr1 == cp15.ttbr1 &&
            v.sig.ttbcr == cp15.ttbcr && v.sig.dacr == cp15.dacr &&
            v.sig.prrr == cp15.prrr && v.sig.nmrr == cp15.nmrr &&
            v.sig.bus_gen == bus_gen) [[likely]] {
            return;
        }

        std::array<uint8_t, 4096> desired{};
        desired.fill(FM_NONE);
        uint32_t watched_l1_page = 0xFFFFFFFFu;
        std::vector<uint32_t> watched_l2_pages;
        std::vector<uint32_t> watched_l2_secs;
        struct MixedL2Section {
            uint32_t sec;
            std::array<uint8_t, 256> page_prot;
        };
        std::vector<MixedL2Section> mixed_l2_sections;

        if (!strict_align) {
            if (!mmu_on) {
                for (const auto& r : regions) {
                    const uint32_t s0 = r.base >> 20;
                    const uint32_t s1 = static_cast<uint32_t>((r.end - 1ull) >> 20);
                    const uint8_t rp = ((r.perms & PERM_WRITE) != 0u) ? FM_RW
                                     : (((r.perms & PERM_READ) != 0u) ? FM_RO : FM_NONE);
                    for (uint32_t s = s0; s <= s1 && s < 4096u; ++s) {
                        desired[s] = std::max(desired[s], rp);
                    }
                }
            } else {
                const uint32_t ttbcr = cp15.ttbcr;
                const uint32_t n = ttbcr & 7u;
                const bool afe = (cp15.sctlr & (1u << 29)) != 0u;
                const uint32_t ttbr0_mask = (0xFFFFC000u >> n) & 0xFFFFFF80u;
                watched_l1_page = (cp15.ttbr0 & ttbr0_mask) >> 12;

                /* Walk ONLY sections overlapping mapped RAM (plus record all L2 table pages in RAM) */
                for (const auto& r : regions) {
                    const uint32_t s0 = r.base >> 20;
                    const uint32_t s1 = static_cast<uint32_t>((r.end - 1ull) >> 20);
                    for (uint32_t s = s0; s <= s1 && s < 4096u; ++s) {
                        const uint32_t va = s << 20;
                        const bool use_ttbr1 = (n > 0u) && ((va >> (32u - n)) != 0u);
                        if ((!use_ttbr1 && (ttbcr & (1u << 4)) != 0u) ||
                            (use_ttbr1 && (ttbcr & (1u << 5)) != 0u)) {
                            continue;
                        }
                        const uint32_t ttbr = use_ttbr1 ? cp15.ttbr1 : cp15.ttbr0;
                        const uint32_t ttbr_mask = use_ttbr1 ? 0xFFFFC000u : ttbr0_mask;
                        const uint32_t l1_base = ttbr & ttbr_mask;
                        const uint32_t l1_idx = use_ttbr1 ? s : (s & ((0x1000u >> n) - 1u));
                        uint32_t l1 = 0u;
                        if (!bus.read_phys_u32(l1_base + (l1_idx << 2), l1)) continue;
                        const uint32_t l1_type = l1 & 3u;
                        if (l1_type == 1u) {
                            const uint32_t l2_base = l1 & 0xFFFFFC00u;
                            const uint32_t l2_pp = l2_base >> 12;
                            if (watched_l2_pages.size() < 256u) {
                                watched_l2_pages.push_back(l2_pp);
                                watched_l2_secs.push_back(s);
                            }
                            const uint32_t dom = (l1 >> 5) & 0xFu;
                            const uint32_t dom_acc = (cp15.dacr >> (dom * 2u)) & 3u;
                            if (dom_acc == 0u || dom_acc == 2u) continue;
                            std::array<uint8_t, 256> pprot{};
                            pprot.fill(FM_NONE);
                            uint8_t first_p = FM_NONE;
                            bool uniform = true;
                            for (uint32_t idx = 0u; idx < 256u; ++idx) {
                                uint32_t l2 = 0u;
                                if (!bus.read_phys_u32(l2_base + (idx << 2), l2)) {
                                    if (idx == 0u) first_p = FM_NONE; else if (FM_NONE != first_p) uniform = false;
                                    continue;
                                }
                                const uint32_t l2_type = l2 & 3u;
                                const uint32_t page_va = va + (idx << 12);
                                uint8_t ep = FM_NONE;
                                if (l2_type >= 2u) {
                                    /* 4 KiB Small Page */
                                    const uint32_t tex = (l2 >> 6) & 7u;
                                    const bool c = (l2 & (1u << 3)) != 0u;
                                    const bool b = (l2 & (1u << 2)) != 0u;
                                    if ((l2 & 0xFFFFF000u) == page_va &&
                                        !ArmMmuTlb::is_device_or_strongly_ordered(cp15, tex, c, b)) {
                                        if (dom_acc == 3u) {
                                            ep = FM_RW;
                                        } else {
                                            const uint32_t ap2 = (l2 >> 9) & 1u;
                                            const uint32_t ap10 = (l2 >> 4) & 3u;
                                            if (!afe || (ap10 & 1u) != 0u) {
                                                const bool cr = check_ap_l1(ap2, ap10, afe, user_mode, false);
                                                const bool cw = check_ap_l1(ap2, ap10, afe, user_mode, true);
                                                if (cr) ep = cw ? FM_RW : FM_RO;
                                            }
                                        }
                                    }
                                } else if (l2_type == 1u) {
                                    /* 64 KiB Large Page */
                                    const uint32_t tex = (l2 >> 12) & 7u;
                                    const bool c = (l2 & (1u << 3)) != 0u;
                                    const bool b = (l2 & (1u << 2)) != 0u;
                                    if ((l2 & 0xFFFF0000u) == (page_va & 0xFFFF0000u) &&
                                        !ArmMmuTlb::is_device_or_strongly_ordered(cp15, tex, c, b)) {
                                        if (dom_acc == 3u) {
                                            ep = FM_RW;
                                        } else {
                                            const uint32_t ap2 = (l2 >> 9) & 1u;
                                            const uint32_t ap10 = (l2 >> 4) & 3u;
                                            if (!afe || (ap10 & 1u) != 0u) {
                                                const bool cr = check_ap_l1(ap2, ap10, afe, user_mode, false);
                                                const bool cw = check_ap_l1(ap2, ap10, afe, user_mode, true);
                                                if (cr) ep = cw ? FM_RW : FM_RO;
                                            }
                                        }
                                    }
                                }
                                pprot[idx] = ep;
                                if (idx == 0u) first_p = ep;
                                else if (ep != first_p) uniform = false;
                            }
                            if (uniform) {
                                desired[s] = first_p;
                            } else {
                                desired[s] = FM_MIXED;
                                mixed_l2_sections.push_back(MixedL2Section{s, pprot});
                            }
                            continue;
                        }
                        if (l1_type != 2u) continue;
                        const bool is_super = (l1 & (1u << 18)) != 0u;
                        const uint64_t pa_sec = is_super
                            ? (static_cast<uint64_t>(l1 & 0xFF000000u)
                               | (static_cast<uint64_t>((l1 >> 20) & 0xFu) << 32)
                               | (static_cast<uint64_t>((l1 >> 5) & 0xFu) << 36)
                               | static_cast<uint64_t>(va & 0x00F00000u))
                            : static_cast<uint64_t>(l1 & 0xFFF00000u);
                        if (pa_sec != static_cast<uint64_t>(va)) {
                            /* Non-identity or >4 GiB 40-bit Supersection virtual mapping (e.g. 4K/capture pool):
                             * keep FM_NONE so Dynarmic falls back cleanly to mmu.page_table! */
                            continue;
                        }
                        const uint32_t sec_tex = (l1 >> 12) & 7u;
                        const bool sec_c = (l1 & (1u << 3)) != 0u;
                        const bool sec_b = (l1 & (1u << 2)) != 0u;
                        if (ArmMmuTlb::is_device_or_strongly_ordered(cp15, sec_tex, sec_c, sec_b)) {
                            continue;
                        }
                        const uint32_t dom = is_super ? 0u : ((l1 >> 5) & 0xFu);
                        const uint32_t dom_acc = (cp15.dacr >> (dom * 2u)) & 3u;
                        if (dom_acc == 0u || dom_acc == 2u) continue;
                        bool can_r = false;
                        bool can_w = false;
                        if (dom_acc == 3u) {
                            can_r = true;
                            can_w = true;
                        } else {
                            const uint32_t ap2 = (l1 >> 15) & 1u;
                            const uint32_t ap10 = (l1 >> 10) & 3u;
                            if (afe && (ap10 & 1u) == 0u) continue;
                            can_r = check_ap_l1(ap2, ap10, afe, user_mode, false);
                            can_w = check_ap_l1(ap2, ap10, afe, user_mode, true);
                        }
                        if (!can_r) continue;
                        desired[s] = can_w ? FM_RW : FM_RO;
                    }
                }
            }
        }

        const bool pt_changed = (v.last_l1_page != watched_l1_page) || (v.last_l2_pages != watched_l2_pages);
        const bool reset_ro = v.needs_ro_reset || pt_changed;
        if (reset_ro) {
            for (uint32_t ppage : v.ro_4k_pages) {
                const uint32_t sec = ppage >> 8;
                if (v.sec_base_prot[sec] == FM_RW && desired[sec] == FM_RW) {
                    protect_mapped_span_view(v, ppage << 12, 4096u, FM_RW);
                }
            }
            v.clear_protected_pages_fast();
            v.needs_ro_reset = false;
        }

        /* Coalesce ONLY changed uniform 1 MiB sections into minimal mprotect/VirtualProtect calls */
        std::array<uint8_t, 4096> sec_changed{};
        sec_changed.fill(0u);
        uint32_t s = 0u;
        while (s < 4096u) {
            if (desired[s] == FM_MIXED || v.sec_base_prot[s] == desired[s]) {
                ++s;
                continue;
            }
            const uint8_t target = desired[s];
            uint32_t e = s + 1u;
            while (e < 4096u && desired[e] != FM_MIXED && v.sec_base_prot[e] != desired[e] && desired[e] == target) {
                ++e;
            }
            protect_mapped_span_view(v, s << 20, (e - s) << 20, target);
            for (uint32_t k = s; k < e; ++k) {
                v.sec_base_prot[k] = target;
                sec_changed[k] = 1u;
            }
            s = e;
        }

        /* Apply mixed L2 4 KiB sections with coalesced sub-runs */
        for (const auto& ml2 : mixed_l2_sections) {
            const uint32_t sec = ml2.sec;
            uint32_t idx = 0u;
            while (idx < 256u) {
                const uint8_t p = ml2.page_prot[idx];
                uint32_t end_idx = idx + 1u;
                while (end_idx < 256u && ml2.page_prot[end_idx] == p) {
                    ++end_idx;
                }
                protect_mapped_span_view(v, (sec << 20) + (idx << 12), (end_idx - idx) << 12, p);
                idx = end_idx;
            }
            v.sec_base_prot[sec] = FM_MIXED;
            sec_changed[sec] = 1u;
        }

        if (!strict_align) {
            auto ensure_ro_page = [&](uint32_t ppage) {
                if (ppage >= (1u << 20)) return;
                const uint32_t sec = ppage >> 8;
                if (desired[sec] == FM_RW) {
                    if (sec_changed[sec] != 0u || !v.is_page_protected(ppage)) {
                        protect_mapped_span_view(v, ppage << 12, 4096u, FM_RO);
                        v.mark_page_protected(ppage);
                    }
                } else if (desired[sec] == FM_MIXED) {
                    for (const auto& ml2 : mixed_l2_sections) {
                        if (ml2.sec == sec && ml2.page_prot[ppage & 0xFFu] == FM_RW) {
                            protect_mapped_span_view(v, ppage << 12, 4096u, FM_RO);
                            v.mark_page_protected(ppage);
                            break;
                        }
                    }
                }
            };
            for (uint32_t ppage : watched_code_pages) {
                ensure_ro_page(ppage);
            }
            if (mmu_on && watched_l1_page != 0xFFFFFFFFu) {
                for (uint32_t i = 0u; i < 4u; ++i) {
                    ensure_ro_page(watched_l1_page + i);
                }
                for (uint32_t ppage : watched_l2_pages) {
                    ensure_ro_page(ppage);
                }
            }
        }

        v.last_l1_page = watched_l1_page;
        v.last_l2_pages = std::move(watched_l2_pages);
        v.last_l2_secs = std::move(watched_l2_secs);
        std::atomic_ref<uint32_t>(v.pt_dirty).store(0u, std::memory_order_release);
        v.sig.mmu_on = mmu_on;
        v.sig.user_mode = user_mode;
        v.sig.strict_align = strict_align;
        v.sig.sctlr_bits = sctlr_bits;
        v.sig.ttbr0 = cp15.ttbr0;
        v.sig.ttbr1 = cp15.ttbr1;
        v.sig.ttbcr = cp15.ttbcr;
        v.sig.dacr = cp15.dacr;
        v.sig.prrr = cp15.prrr;
        v.sig.nmrr = cp15.nmrr;
        v.sig.bus_gen = bus_gen;
        std::atomic_ref<uint32_t>(v.sig.valid).store(1u, std::memory_order_release);
    }
};

}  // namespace

struct CoreState;

struct RoMmioRange {
    uint32_t phys_base = 0u;
    uint32_t size = 0u;
    const uint8_t* host_ptr = nullptr; /* nullptr => constant zero range */
};

struct HighRamBacking {
    uint64_t phys_base = 0u;
    uint64_t size = 0u;
    uint8_t* ptr = nullptr;
};

static uint8_t* alloc_demand_zero_ram(uint64_t size) {
    if (size == 0u) return nullptr;
#if defined(__linux__)
    void* p = mmap(nullptr, static_cast<size_t>(size), PROT_READ | PROT_WRITE,
                   MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE, -1, 0);
    if (p == MAP_FAILED) return nullptr;
#ifdef MADV_HUGEPAGE
    if (size >= THP_MIN_BYTES) {
        (void)madvise(p, static_cast<size_t>(size), MADV_HUGEPAGE);
    }
#endif
    return static_cast<uint8_t*>(p);
#elif defined(_WIN32)
    void* p = VirtualAlloc(nullptr, static_cast<SIZE_T>(size), MEM_RESERVE | MEM_COMMIT, PAGE_READWRITE);
    return static_cast<uint8_t*>(p);
#else
    return nullptr;
#endif
}

static void free_demand_zero_ram(uint8_t* ptr, uint64_t size) {
    if (!ptr || size == 0u) return;
#if defined(__linux__)
    munmap(ptr, static_cast<size_t>(size));
#elif defined(_WIN32)
    (void)size;
    VirtualFree(ptr, 0, MEM_RELEASE);
#else
    (void)ptr;
    (void)size;
#endif
}

struct FujiDynEngine {
    uint32_t num_cores = 1;
    uint32_t cbar_base = 0xFFFF0000u;
    PhysMemoryBus bus;
    FujiFastmemArena fastmem;
    std::vector<HighRamBacking> high_ram_backings;
    std::unique_ptr<Dynarmic::ExclusiveMonitor> exclusive_monitor;

    ~FujiDynEngine();

    FujiDynMmioReadFn mmio_read_cb = nullptr;
    FujiDynMmioWriteFn mmio_write_cb = nullptr;
    void* mmio_user = nullptr;

    uint32_t auto_excp_mask = 0u;
    std::atomic<uint32_t> wfe_event_mask{0u};
    std::atomic<uint32_t> active_core{0u};
    std::atomic<uint64_t> current_vtick{0u};
    uint32_t vtick_divider = 1u;   /* CPU instructions per 1 virtual 54 MHz timer tick (>= 1) */
    uint32_t vtick_shift = 0u;     /* Non-zero when vtick_divider is a power of 2 (> 1) */
    uint32_t vtick_mask = 0u;      /* (vtick_divider - 1) when vtick_shift > 0 */
    uint32_t vtick_subticks = 0u;  /* Fractional instruction carry in [0, vtick_divider - 1] */
    std::atomic<bool> in_cluster_fast{false};
    std::atomic<bool> host_halt_requested{false};
    std::atomic<uint32_t> cluster_runnable_mask{0u};
    std::atomic<uint32_t> cluster_wfe_waiting_mask{0u};
    std::atomic<uint32_t> cluster_wfi_waiting_mask{0u};
    std::atomic<uint32_t> cluster_sev_seen_mask{0u};
    std::atomic<uint32_t> cluster_irq_woken_mask{0u};

    /* Multi-core host-threaded SMP synchronization & persistent worker pool */
    mutable std::mutex gic_lock;
    mutable std::mutex ro_mmio_lock;
    mutable std::mutex meta_lock;
    std::atomic<bool> smp_host_threads{false};
    std::atomic<bool> mt_epoch_active{false};
    std::atomic<bool> mt_epoch_stop_requested{false};
    std::atomic<uint32_t> mt_halt_core{0xFFFFFFFFu};

    struct MtMmioRequest {
        std::atomic<uint32_t> state{0u}; /* 0=idle, 1=pending, 2=done */
        bool is_write = false;
        uint32_t phys_addr = 0u;
        uint32_t size = 0u;
        uint64_t write_val = 0u;
        uint64_t read_result = 0u;
        uint32_t pc = 0u;
        uint32_t cpsr = 0u;
    };
    std::array<MtMmioRequest, FUJI_DYN_MAX_CORES> mt_mmio_reqs{};
    std::atomic<uint32_t> mt_mmio_pending_mask{0u};
    std::mutex mt_pool_mutex;
    std::array<std::condition_variable, FUJI_DYN_MAX_CORES> mt_worker_wake_cv;
    std::condition_variable mt_owner_cv;
    std::array<std::condition_variable, FUJI_DYN_MAX_CORES> mt_mmio_done_cv;
    std::array<std::atomic<uint64_t>, FUJI_DYN_MAX_CORES> mt_worker_gen{};
    std::atomic<uint32_t> mt_epoch_mask{0u};
    uint64_t mt_epoch_budget = 0u;
    uint64_t mt_epoch_total_cap = 0u;
    std::atomic<uint64_t> mt_epoch_total_exec{0u};
    uint64_t mt_epoch_base_vtick = 0u;
    uint32_t mt_epoch_base_subticks = 0u;
    std::atomic<uint32_t> mt_workers_remaining{0u};
    /* SMP-MT start barrier: number of epoch workers that have actually entered execute_core_mt_epoch.
     * Core 0 (owner thread) waits for all of them before running so it never races ahead of
     * still-parked worker cores by a host futex wake-up latency (tens of thousands of guest insns). */
    std::atomic<uint32_t> mt_epoch_workers_entered{0u};
    std::atomic<uint32_t> mt_workers_parked_mask{0u};
    std::atomic<uint32_t> mt_owner_parked{0u};
    std::atomic<bool> mt_shutdown{false};
    std::array<std::thread, FUJI_DYN_MAX_CORES> mt_workers{};
    bool mt_workers_started = false;
    bool mt_owner_in_mmio_cb = false;

    inline bool mt_should_stop_epoch() const {
        return host_halt_requested.load(std::memory_order_acquire) ||
               mt_epoch_stop_requested.load(std::memory_order_acquire);
    }

    inline void bump_stat(uint64_t& field) {
        if (mt_epoch_active.load(std::memory_order_relaxed)) {
            std::atomic_ref<uint64_t>(field).fetch_add(1u, std::memory_order_relaxed);
        } else {
            ++field;
        }
    }

    inline void advance_vtick_max(uint64_t cand) {
        uint64_t cur = current_vtick.load(std::memory_order_relaxed);
        while (cand > cur && !current_vtick.compare_exchange_weak(
                                 cur, cand, std::memory_order_release, std::memory_order_relaxed)) {}
    }

    void ensure_mt_workers_started();
    void shutdown_mt_workers();
    void mt_worker_loop(uint32_t worker_core_id);
    uint64_t live_vtick();
    void drain_worker_mmio_requests();
    uint64_t submit_worker_mmio_request(uint32_t worker_core_id, bool is_write, uint32_t phys_addr,
                                        uint32_t size, uint64_t write_val, uint32_t pc, uint32_t cpsr);
    void halt_other_active_cores_mt(uint32_t except_core, Dynarmic::HaltReason hr);
    void request_mt_epoch_stop(uint32_t triggering_core);
    void broadcast_xcore_op_mt(uint32_t sender_core, uint32_t op_mask);

    /* Shared off-heap live-state buffer written before MMIO upcalls & on run/CPSR updates */
    FujiDynLiveState* live_state = nullptr;

    /* Native C++ GICv2 fastpath operating in-place on shared FujiDynGicShadow */
    GicV2Fastpath gic_fastpath;

    /* Native C++ soc-timer read shadow (0xFFF3C000..0xFFF3C04F) + V3 inline comparator IRQ auto-raise */
    TimerShadowBank<FUJI_DYN_TIMER_CHANNELS> timer_bank;
    uint32_t shared_timer_base = 0u;
    const FujiDynTimerShadow* shared_timer_shadow = nullptr;
    FujiDynTimerShadow* rw_timer_shadow = nullptr;
    FujiDynTimerIrqShadow* timer_irq_shadow = nullptr;

    inline bool try_gic_v3_write(uint32_t core, uint32_t phys_addr, uint32_t sz, uint32_t val) {
        if (!gic_fastpath.is_active()) return false;
        if (gic_fastpath.try_write(core, phys_addr, sz, val)) return true;

        /* DYN-V3 inline GICD_SGIR (0xF00) and GICD_CPENDSGIR (0xF10..0xF1C) fastpath */
        GicV2ShadowState* sp = gic_fastpath.shadow();
        if (!sp || core >= sp->core_count || sp->core_count <= 1u || sz != 4u) return false;
        const uint32_t dist_off = phys_addr - gic_fastpath.gicd_base();
        if (dist_off == 0xF00u) {
            const uint32_t filter = (val >> 24) & 3u;
            if ((val & ~0x03FF000Fu) != 0u || filter == 3u) return false;
            const uint32_t line = val & 0xFu;
            const uint32_t all_cores = (1u << sp->core_count) - 1u;
            uint32_t target_mask = 0u;
            if (filter == 0u) {
                target_mask = (val >> 16) & all_cores;
            } else if (filter == 1u) {
                target_mask = all_cores & ~(1u << core);
            } else {
                target_mask = 1u << core;
            }
            if (target_mask != 0u) {
                for (uint32_t tc = 0u; tc < sp->core_count; ++tc) {
                    if ((target_mask & (1u << tc)) != 0u) {
                        sp->cores[tc].sgi_senders[line] |= static_cast<uint8_t>(1u << core);
                        sp->cores[tc].local_pending |= (1u << line);
                        sp->cores[tc].dirty_flag = 1u;
                    }
                }
                ++sp->native_seq;
                GicV2Fastpath::refresh_mask_pending(*sp, target_mask);
            }
            ++sp->fast_hits;
            return true;
        }
        if (dist_off >= 0xF10u && dist_off <= 0xF1Cu && (dist_off & 3u) == 0u) {
            const uint32_t base_sgi = ((dist_off - 0xF10u) >> 2) * 4u;
            GicV2CoreShadow& c = sp->cores[core];
            bool changed = false;
            for (uint32_t b = 0u; b < 4u; ++b) {
                const uint32_t clear_mask = (val >> (b * 8u)) & 0xFFu;
                if (clear_mask != 0u) {
                    const uint32_t sgi = base_sgi + b;
                    const uint8_t next_senders = static_cast<uint8_t>(c.sgi_senders[sgi] & ~clear_mask);
                    if (next_senders != c.sgi_senders[sgi]) {
                        c.sgi_senders[sgi] = next_senders;
                        if (next_senders == 0u) {
                            c.local_pending &= ~(1u << sgi);
                        }
                        changed = true;
                    }
                }
            }
            if (changed) {
                c.dirty_flag = 1u;
                ++sp->native_seq;
                GicV2Fastpath::refresh_core_pending(*sp, core);
            }
            ++sp->fast_hits;
            return true;
        }
        return false;
    }

    inline bool inline_timer_irq_active() const {
        return rw_timer_shadow != nullptr && rw_timer_shadow->active != 0u &&
               timer_irq_shadow != nullptr && (timer_irq_shadow->active & 1u) != 0u &&
               gic_fastpath.is_active();
    }

    inline uint64_t earliest_inline_timer_deadline() const {
        if (!inline_timer_irq_active()) return std::numeric_limits<uint64_t>::max();
        uint64_t earliest = std::numeric_limits<uint64_t>::max();
        const FujiDynTimerShadow* ts = rw_timer_shadow;
        for (uint32_t i = 0u; i < FUJI_DYN_TIMER_CHANNELS; ++i) {
            const FujiDynTimerChannel& ch = ts->channels[i];
            if (ch.has_deadline != 0u && ch.deadline < earliest) {
                earliest = ch.deadline;
            }
        }
        return earliest;
    }

    /* Fires all due soc-timer channels directly into GicV2Fastpath (zero Java upcalls).
     * Returns: >0 count of fired channels, 0 if none due, -1 if an unrouted/unqualified channel needs Java. */
    inline int32_t try_fire_inline_timers(uint64_t now_tick) {
        if (!inline_timer_irq_active()) return 0;
        FujiDynTimerShadow* ts = rw_timer_shadow;
        FujiDynTimerIrqShadow* is = timer_irq_shadow;
        GicV2ShadowState* gic = gic_fastpath.shadow();
        if (!gic) return 0;

        /* Preflight all due channels before mutating any state (bit-exact to TimerFactory.beforeInstruction) */
        uint32_t due_mask = 0u;
        for (uint32_t i = 0u; i < FUJI_DYN_TIMER_CHANNELS; ++i) {
            const FujiDynTimerChannel& ch = ts->channels[i];
            if (ch.has_deadline == 0u || now_tick < ch.deadline) continue;
            const int32_t irq = is->irq_routes[i];
            if (irq < 32 || irq >= static_cast<int32_t>(GIC_V2_MAX_IRQS)) return -1;
            const uint32_t w = static_cast<uint32_t>(irq) >> 6;
            const uint64_t bit = 1ull << (static_cast<uint32_t>(irq) & 63u);
            if ((gic->shared_edge[w] & bit) == 0ull || (gic->shared_levels[w] & bit) != 0ull) {
                return -1;
            }
            due_mask |= (1u << i);
        }
        if (due_mask == 0u) return 0;

        uint32_t refresh_cores = 0u;
        int32_t fired = 0;
        for (uint32_t i = 0u; i < FUJI_DYN_TIMER_CHANNELS; ++i) {
            if ((due_mask & (1u << i)) == 0u) continue;
            FujiDynTimerChannel& ch = ts->channels[i];
            const uint64_t period = (ch.reload & 0xFFFFFFFFull) + 1ull;
            const uint64_t count = ((now_tick - ch.deadline) / period) + 1ull;
            ch.deadline += count * period;
            is->fired_periods[i] += count;

            const uint32_t irq = static_cast<uint32_t>(is->irq_routes[i]);
            const uint32_t w = irq >> 6;
            const uint64_t bit = 1ull << (irq & 63u);
            gic->shared_pending[w] |= bit;
            gic->dirty_spi_mask |= GIC_V2_DIRTY_SPI_PEND_ACT;
            refresh_cores |= static_cast<uint32_t>(gic->shared_targets[irq - 32u]);
            ++fired;
        }
        is->dirty = 1u;
        is->last_fire_tick = now_tick;
        ++gic->native_seq;
        if (refresh_cores != 0u) {
            GicV2Fastpath::refresh_mask_pending(*gic, refresh_cores);
        }
        return fired;
    }

    /* Native C++ open-addressed RO/retained 32-bit MMIO shadow cache via dynarmic::a32_sys */
    RoMmioShadowTable<FUJI_DYN_RO_MMIO_SLOTS> ro_mmio;

    /* Contiguous read-only MMIO range fastpaths (e.g. soc-isp 56 KiB stats/result surfaces) */
    std::array<RoMmioRange, FUJI_DYN_RO_MMIO_RANGES> ro_ranges{};
    uint32_t ro_range_count = 0u;
    uint32_t ro_range_min_addr = 0xFFFFFFFFu;
    uint64_t ro_range_max_end = 0u;

    /* Generic declarative live MMIO read-mirror banks (component-declared, zero peripheral logic in C++) */
    struct MmioMirrorBank {
        uint32_t phys_base = 0u;
        uint32_t window_size = 0u;
        FujiDynMmioMirrorHeader* header = nullptr;
        FujiDynMmioMirrorSlot* slots = nullptr;
        uint32_t slot_count = 0u;
    };
    struct FlatMmioMirrorEntry {
        uint32_t phys_addr = 0u;
        uint32_t width_bytes = 0u; /* 0 == empty */
        uint32_t* value_ptr = nullptr;
        uint64_t* valid_mask_ptr = nullptr;
        uint64_t valid_bit = 0ull;
    };
    static constexpr uint32_t MMIO_MIRROR_TABLE_SIZE = 2048u;
    static constexpr uint32_t MMIO_MIRROR_TABLE_MASK = MMIO_MIRROR_TABLE_SIZE - 1u;
    std::array<MmioMirrorBank, FUJI_DYN_MMIO_MIRROR_MAX_BANKS> mmio_mirror_banks{};
    uint32_t mmio_mirror_bank_count = 0u;
    std::array<FlatMmioMirrorEntry, MMIO_MIRROR_TABLE_SIZE> mmio_mirror_table{};
    uint32_t mmio_mirror_slot_total = 0u;
    uint32_t mmio_mirror_min_addr = 0xFFFFFFFFu;
    uint64_t mmio_mirror_max_end = 0u;

    static inline uint32_t hash_mmio_mirror_addr(uint32_t pa) {
        return ((pa ^ (pa >> 12)) * 2654435761u) >> 21;
    }

    void rebuild_mmio_mirror_table() {
        mmio_mirror_table.fill(FlatMmioMirrorEntry{});
        mmio_mirror_slot_total = 0u;
        uint32_t mn = 0xFFFFFFFFu;
        uint64_t mx = 0u;
        for (uint32_t b = 0u; b < mmio_mirror_bank_count; ++b) {
            const MmioMirrorBank& bank = mmio_mirror_banks[b];
            if (!bank.header || !bank.slots) continue;
            for (uint32_t s = 0u; s < bank.slot_count; ++s) {
                const FujiDynMmioMirrorSlot& slot = bank.slots[s];
                if (slot.width_bytes != 1u && slot.width_bytes != 2u && slot.width_bytes != 4u) continue;
                if (static_cast<uint64_t>(slot.offset) + slot.width_bytes > bank.window_size) continue;
                const uint64_t pa64 = static_cast<uint64_t>(bank.phys_base) + slot.offset;
                if (pa64 >= 0x100000000ull) continue;
                const uint32_t pa = static_cast<uint32_t>(pa64);
                uint32_t idx = hash_mmio_mirror_addr(pa) & MMIO_MIRROR_TABLE_MASK;
                for (uint32_t probe = 0u; probe < MMIO_MIRROR_TABLE_SIZE; ++probe) {
                    FlatMmioMirrorEntry& e = mmio_mirror_table[idx];
                    if (e.width_bytes == 0u || e.phys_addr == pa) {
                        const bool is_new = (e.width_bytes == 0u);
                        e.phys_addr = pa;
                        e.width_bytes = slot.width_bytes;
                        e.value_ptr = &bank.slots[s].value;
                        e.valid_mask_ptr = &bank.header->valid_mask;
                        e.valid_bit = 1ull << s;
                        if (is_new) ++mmio_mirror_slot_total;
                        if (pa < mn) mn = pa;
                        if (pa64 + slot.width_bytes > mx) mx = pa64 + slot.width_bytes;
                        break;
                    }
                    idx = (idx + 1u) & MMIO_MIRROR_TABLE_MASK;
                }
            }
        }
        mmio_mirror_min_addr = mn;
        mmio_mirror_max_end = mx;
    }

    inline bool try_read_mmio_mirror(uint32_t phys_addr, uint32_t sz, uint32_t& out_val) const {
        if (mmio_mirror_slot_total == 0u ||
            phys_addr < mmio_mirror_min_addr ||
            static_cast<uint64_t>(phys_addr) >= mmio_mirror_max_end) [[likely]] {
            return false;
        }
        uint32_t idx = hash_mmio_mirror_addr(phys_addr) & MMIO_MIRROR_TABLE_MASK;
        for (uint32_t probe = 0u; probe < MMIO_MIRROR_TABLE_SIZE; ++probe) {
            const FlatMmioMirrorEntry& e = mmio_mirror_table[idx];
            if (e.width_bytes == 0u) return false;
            if (e.phys_addr == phys_addr) {
                if (e.width_bytes != sz) return false;
                const uint64_t mask = std::atomic_ref<uint64_t>(*e.valid_mask_ptr).load(std::memory_order_acquire);
                if ((mask & e.valid_bit) == 0ull) return false;
                out_val = std::atomic_ref<uint32_t>(*e.value_ptr).load(std::memory_order_acquire);
                return true;
            }
            idx = (idx + 1u) & MMIO_MIRROR_TABLE_MASK;
        }
        return false;
    }

    inline void recompute_ro_range_bounds() {
        uint32_t mn = 0xFFFFFFFFu;
        uint64_t mx = 0u;
        for (uint32_t i = 0; i < ro_range_count; ++i) {
            if (ro_ranges[i].phys_base < mn) mn = ro_ranges[i].phys_base;
            uint64_t e = static_cast<uint64_t>(ro_ranges[i].phys_base) + ro_ranges[i].size;
            if (e > mx) mx = e;
        }
        ro_range_min_addr = mn;
        ro_range_max_end = mx;
    }

    /* Cumulative MMIO fastpath vs upcall stats */
    MmioFastpathStats mmio_stats;

    bool has_tracepoints = false;
    std::unordered_set<uint32_t> tracepoints;

    // Mixed code/data pages are common in the firmware (e.g. ThreadX globals beside
    // the exception vectors). Keep the page-level write trap, but dirty the JIT only
    // when a store overlaps a word the translator has actually fetched.
    struct FetchedWords {
        using Section = std::array<std::atomic<uint64_t>, 4096>; // 1 MiB, one bit per ARM word
        std::array<std::atomic<Section*>, 4096> sections{};

        ~FetchedWords() {
            for (auto& slot : sections) delete slot.load(std::memory_order_relaxed);
        }

        void record(uint32_t physical) {
            auto& slot = sections[physical >> 20];
            auto* section = slot.load(std::memory_order_acquire);
            if (!section) {
                auto candidate = std::make_unique<Section>();
                if (slot.compare_exchange_strong(section, candidate.get(), std::memory_order_acq_rel)) {
                    section = candidate.release();
                }
            }
            const uint32_t word = (physical & 0xfffffu) >> 2;
            const uint64_t bit = uint64_t{1} << (word & 63u);
            auto& bits = (*section)[word >> 6];
            if (!(bits.load(std::memory_order_relaxed) & bit)) bits.fetch_or(bit, std::memory_order_release);
        }

        bool overlaps(uint32_t physical, uint32_t size) const {
            const uint64_t end = static_cast<uint64_t>(physical) + size;
            for (uint64_t address = physical & ~uint32_t{3}; address < end && address < PHYS_ADDR_32BIT; address += 4) {
                const auto* section = sections[address >> 20].load(std::memory_order_acquire);
                if (!section) continue;
                const uint32_t word = static_cast<uint32_t>((address & 0xfffffu) >> 2);
                if ((*section)[word >> 6].load(std::memory_order_acquire) & (uint64_t{1} << (word & 63u))) return true;
            }
            return false;
        }
    } fetched_words;
    /* 128 KiB 4 KiB-page code-translated bitmap + compact watched list */
    alignas(8) std::array<uint64_t, 16384> code_pages{};
    std::vector<uint32_t> watched_code_pages;
    std::atomic<bool> code_pages_dirty{false};

    inline bool mark_code_page(uint32_t addr) {
        const uint32_t page = addr >> 12;
        const uint64_t bit = 1ull << (page & 63u);
        std::atomic_ref<uint64_t> word(code_pages[page >> 6]);
        const uint64_t prev = word.fetch_or(bit, std::memory_order_acq_rel);
        if ((prev & bit) == 0ull) {
            watched_code_pages.push_back(page);
            return true;
        }
        return false;
    }

    inline bool is_code_page(uint32_t page) const {
        const uint32_t p = page & 0xFFFFFu;
        return (std::atomic_ref<uint64_t>(const_cast<uint64_t&>(code_pages[p >> 6]))
                    .load(std::memory_order_relaxed) & (1ull << (p & 63u))) != 0ull;
    }

    inline bool has_code_pages(uint32_t base, uint32_t size) const {
        if (size == 0u) return false;
        const uint32_t first_p = base >> 12;
        const uint64_t end_addr = static_cast<uint64_t>(base) + size - 1ull;
        const uint32_t last_p = static_cast<uint32_t>(end_addr > 0xFFFFFFFFull ? 0xFFFFFu : (end_addr >> 12));
        const uint32_t w0 = first_p >> 6;
        const uint32_t w1 = last_p >> 6;
        for (uint32_t w = w0; w <= w1; ++w) {
            uint64_t mask = ~0ull;
            if (w == w0) mask &= (~0ull << (first_p & 63u));
            if (w == w1) {
                const uint32_t hi = last_p & 63u;
                if (hi < 63u) mask &= ((1ull << (hi + 1u)) - 1ull);
            }
            const uint64_t val = std::atomic_ref<uint64_t>(const_cast<uint64_t&>(code_pages[w]))
                                     .load(std::memory_order_relaxed);
            if ((val & mask) != 0ull) return true;
        }
        return false;
    }

    bool write_overlaps_code(uint32_t address, uint32_t size) const {
        if (!has_code_pages(address, size)) return false;
        // On a single shared host/guest view, a host write can make the whole page writable.
        if (fastmem.active() && !fastmem.dual_view) return true;
        return fetched_words.overlaps(address, size);
    }

    inline void clear_code_pages() {
        for (uint32_t page : watched_code_pages) {
            std::atomic_ref<uint64_t>(code_pages[page >> 6])
                .fetch_and(~(1ull << (page & 63u)), std::memory_order_release);
        }
        watched_code_pages.clear();
        code_pages_dirty.store(false, std::memory_order_release);
    }

    inline bool has_and_clear_code_pages(uint32_t base, uint32_t size) {
        if (size == 0u) return false;
        const uint32_t first_p = base >> 12;
        const uint64_t end_addr = static_cast<uint64_t>(base) + size - 1ull;
        const uint32_t last_p = static_cast<uint32_t>(end_addr > 0xFFFFFFFFull ? 0xFFFFFu : (end_addr >> 12));
        const uint32_t w0 = first_p >> 6;
        const uint32_t w1 = last_p >> 6;
        bool any = false;
        for (uint32_t w = w0; w <= w1; ++w) {
            uint64_t mask = ~0ull;
            if (w == w0) mask &= (~0ull << (first_p & 63u));
            if (w == w1) {
                const uint32_t hi = last_p & 63u;
                if (hi < 63u) mask &= ((1ull << (hi + 1u)) - 1ull);
            }
            std::atomic_ref<uint64_t> word(code_pages[w]);
            const uint64_t cur = word.fetch_and(~mask, std::memory_order_acq_rel);
            if ((cur & mask) != 0ull) {
                any = true;
            }
        }
        if (any && !watched_code_pages.empty()) {
            watched_code_pages.erase(
                std::remove_if(watched_code_pages.begin(), watched_code_pages.end(),
                               [&](uint32_t p) { return p >= first_p && p <= last_p; }),
                watched_code_pages.end());
        }
        return any;
    }

    std::vector<std::unique_ptr<CoreState>> cores;

    inline bool try_read_shared_timer32(uint32_t phys_addr, uint64_t now_tick,
                                        uint32_t& out_val, bool& needs_tick_flush) const {
        const FujiDynTimerShadow* sh = shared_timer_shadow;
        if (!sh || sh->active == 0u) [[unlikely]] return false;
        const uint32_t offset = phys_addr - shared_timer_base;
        if (offset >= 0x50u || (offset & 3u) != 0u) [[unlikely]] return false;
        if (offset == 0u) {
            uint32_t bits = 0u;
            for (uint32_t i = 0; i < FUJI_DYN_TIMER_CHANNELS; ++i) {
                if (sh->channels[i].enabled != 0u) bits |= (1u << i);
            }
            out_val = bits;
            needs_tick_flush = false;
            return true;
        }
        if (offset >= 0x10u) [[likely]] {
            const uint32_t rel = offset - 0x10u;
            const uint32_t ch_idx = rel >> 3;
            const uint32_t reg_off = rel & 7u;
            if (ch_idx < FUJI_DYN_TIMER_CHANNELS) [[likely]] {
                const FujiDynTimerChannel& ch = sh->channels[ch_idx];
                if (reg_off == 0u) [[likely]] {
                    if (ch.has_deadline == 0u) [[unlikely]] {
                        out_val = static_cast<uint32_t>(ch.stored);
                        needs_tick_flush = false;
                    } else if (now_tick < ch.deadline) [[likely]] {
                        out_val = static_cast<uint32_t>(ch.deadline - now_tick - 1ull);
                        needs_tick_flush = true;
                    } else {
                        const uint64_t relv = ch.reload & 0xFFFFFFFFull;
                        const uint64_t period = relv + 1ull;
                        out_val = static_cast<uint32_t>(relv - ((now_tick - ch.deadline) % period));
                        needs_tick_flush = true;
                    }
                    return true;
                } else if (reg_off == 4u) {
                    out_val = static_cast<uint32_t>(ch.reload);
                    needs_tick_flush = false;
                    return true;
                }
            }
        }
        return false;
    }

    template <typename T>
    inline bool try_read_ro_range(uint32_t phys_addr, T& out_val) const {
        if (phys_addr < ro_range_min_addr || phys_addr >= ro_range_max_end) [[likely]] return false;
        const uint32_t cnt = ro_range_count;
        constexpr uint32_t sz = sizeof(T);
        for (uint32_t i = 0; i < cnt; ++i) {
            const RoMmioRange& r = ro_ranges[i];
            const uint32_t off = phys_addr - r.phys_base;
            if (phys_addr >= r.phys_base && off < r.size && sz <= (r.size - off)) {
                if (r.host_ptr == nullptr) {
                    out_val = 0;
                } else {
                    std::memcpy(&out_val, r.host_ptr + off, sz);
                }
                return true;
            }
        }
        return false;
    }

    inline void invalidate_ro_ranges_covering(uint32_t phys_addr, uint32_t sz) {
        const uint64_t w_end = static_cast<uint64_t>(phys_addr) + sz;
        if (phys_addr >= ro_range_max_end || w_end <= ro_range_min_addr) [[likely]] return;
        bool changed = false;
        for (uint32_t i = 0; i < ro_range_count; ) {
            const RoMmioRange& r = ro_ranges[i];
            const uint64_t r_end = static_cast<uint64_t>(r.phys_base) + r.size;
            if (phys_addr < r_end && w_end > r.phys_base) {
                ro_ranges[i] = ro_ranges[--ro_range_count];
                changed = true;
            } else {
                ++i;
            }
        }
        if (changed) recompute_ro_range_bounds();
    }
};

struct CoreState final : public Dynarmic::A32::UserCallbacks {
    FujiDynEngine* engine = nullptr;
    uint32_t core_id = 0;

    Cp15State cp15;
    BankedRegs banked;
    ArmMmuTlb mmu;

    struct FujiCp15Coprocessor final : public Dynarmic::A32::Coprocessor {
        ArmCp15Coprocessor inner;
        CoreState* owner = nullptr;
        uint32_t nop_sink = 0u;
        uint32_t mpidr_val = 0u;

        FujiCp15Coprocessor(CoreState* core, Cp15State& st, Cp15HostHooks hooks)
            : inner(st, std::move(hooks)), owner(core), mpidr_val(st.mpidr()) {}

        static std::uint64_t tlb_va_local_cb(void* arg, std::uint32_t val, std::uint32_t) {
            auto* c = static_cast<CoreState*>(arg);
            const uint32_t va = val & ~0xFFFu;
            c->mmu.flush_va(va);
            c->invalidate_fastmem_local();
            if (c->has_non_global_pages) {
                c->request_jit_inval(va, 4096u);
            }
            return 0;
        }
        static std::uint64_t tlb_va_all_cb(void* arg, std::uint32_t val, std::uint32_t) {
            auto* c = static_cast<CoreState*>(arg);
            const uint32_t va = val & ~0xFFFu;
            if (c->engine->mt_epoch_active.load(std::memory_order_acquire)) {
                c->mmu.flush_va(va);
                c->invalidate_fastmem_local();
                if (c->has_non_global_pages) c->request_jit_inval(va, 4096u);
                c->engine->broadcast_xcore_op_mt(c->core_id, XCORE_TLB_FLUSH_ALL | XCORE_FASTMEM_RESYNC | XCORE_ICACHE_CLEAR);
                return 0;
            }
            for (auto& cc : c->engine->cores) {
                if (cc) {
                    cc->mmu.flush_va(va);
                    cc->invalidate_fastmem_local();
                    if (cc->has_non_global_pages) cc->request_jit_inval(va, 4096u);
                }
            }
            return 0;
        }
        static std::uint64_t tlb_asid_local_cb(void* arg, std::uint32_t val, std::uint32_t) {
            auto* c = static_cast<CoreState*>(arg);
            c->mmu.flush_asid(static_cast<uint8_t>(val & 0xFFu));
            c->invalidate_fastmem_local();
            if (c->has_non_global_pages) c->request_jit_clear();
            return 0;
        }
        static std::uint64_t tlb_asid_all_cb(void* arg, std::uint32_t val, std::uint32_t) {
            auto* c = static_cast<CoreState*>(arg);
            const uint8_t asid = static_cast<uint8_t>(val & 0xFFu);
            if (c->engine->mt_epoch_active.load(std::memory_order_acquire)) {
                c->mmu.flush_asid(asid);
                c->invalidate_fastmem_local();
                if (c->has_non_global_pages) c->request_jit_clear();
                c->engine->broadcast_xcore_op_mt(c->core_id, XCORE_TLB_FLUSH_ALL | XCORE_FASTMEM_RESYNC | XCORE_ICACHE_CLEAR);
                return 0;
            }
            for (auto& cc : c->engine->cores) {
                if (cc) {
                    cc->mmu.flush_asid(asid);
                    cc->invalidate_fastmem_local();
                    if (cc->has_non_global_pages) cc->request_jit_clear();
                }
            }
            return 0;
        }
        static std::uint64_t ttbr0_write_cb(void* arg, std::uint32_t val, std::uint32_t) {
            auto* c = static_cast<CoreState*>(arg);
            if (c->cp15.ttbr0 == val) return 0;
            c->cp15.ttbr0 = val;
            c->mmu.flush();
            c->invalidate_fastmem_local();
            if (c->has_non_global_pages) {
                c->engine->code_pages_dirty.store(true, std::memory_order_release);
                c->request_jit_clear();
            }
            return 0;
        }
        static std::uint64_t contextidr_cb(void* arg, std::uint32_t val, std::uint32_t) {
            auto* c = static_cast<CoreState*>(arg);
            const uint8_t old_asid = c->cp15.current_asid();
            c->cp15.contextidr = val;
            const uint8_t new_asid = c->cp15.current_asid();
            if (old_asid != new_asid) {
                c->mmu.invalidate_mru();
                if (c->has_non_global_pages) {
                    c->mmu.flush_asid(old_asid);
                    c->invalidate_fastmem_local();
                    c->engine->code_pages_dirty.store(true, std::memory_order_release);
                    c->request_jit_clear();
                }
            }
            return 0;
        }
        static std::uint64_t sctlr_write_cb(void* arg, std::uint32_t val, std::uint32_t) {
            auto* c = static_cast<CoreState*>(arg);
            const uint32_t prev = c->cp15.sctlr;
            c->cp15.sctlr = val;
            if (prev == val) return 0;
            constexpr uint32_t MMU_BITS = (1u << 29) | (1u << 28) | 3u; /* AFE, TRE, A, M */
            if (((prev ^ val) & MMU_BITS) != 0u) {
                c->mmu.flush();
                c->has_non_global_pages = false;
                c->mmu_ctrl_changed = true;
                c->engine->code_pages_dirty.store(true, std::memory_order_release);
                if (((prev ^ val) & 1u) != 0u && c->jit) {
                    c->jit->ClearCache();
                    c->mmu.clear_watched_code_pages();
                    if (c->engine->num_cores == 1u) {
                        c->engine->clear_code_pages();
                        c->engine->fastmem.invalidate_all(true);
                    }
                }
                c->sync_fastmem_safe(false);
            }
            return 0;
        }

        std::optional<Callback> CompileInternalOperation(
            bool two, unsigned opc1, Dynarmic::A32::CoprocReg crd, Dynarmic::A32::CoprocReg crn,
            Dynarmic::A32::CoprocReg crm, unsigned opc2) override {
            return inner.CompileInternalOperation(two, opc1, crd, crn, crm, opc2);
        }

        CallbackOrAccessOneWord CompileSendOneWord(
            bool two, unsigned opc1, Dynarmic::A32::CoprocReg crn,
            Dynarmic::A32::CoprocReg crm, unsigned opc2) override {
            const uint32_t n = static_cast<uint32_t>(crn);
            const uint32_t m = static_cast<uint32_t>(crm);
            if (opc1 == 0u && n == 1u && m == 0u && opc2 == 0u) {
                /* SCTLR: only flush TLB/Fastmem when MMU-affecting bits (M/A/TRE/AFE) actually change */
                return Callback{&sctlr_write_cb, owner};
            }
            if (opc1 == 0u && n == 2u && m == 0u && opc2 == 0u) {
                /* TTBR0: switch user page table, invalidate fastmem and (for non-global OS) JIT cache */
                return Callback{&ttbr0_write_cb, owner};
            }
            if (opc1 == 0u && n == 7u) {
                /* Coherent-JIT architectural NOPs: D-cache maintenance (c6/c10/c14), CP15 ISB/BPIALL* */
                if (m == 6u || m == 10u || m == 14u ||
                    (m == 5u && (opc2 == 4u || opc2 == 6u || opc2 == 7u)) ||
                    (m == 1u && opc2 == 6u)) {
                    return &nop_sink;
                }
            } else if (opc1 == 0u && n == 8u) {
                const bool all_cores = (m == 3u);
                if (opc2 == 1u || opc2 == 3u) {
                    return Callback{all_cores ? &tlb_va_all_cb : &tlb_va_local_cb, owner};
                }
                if (opc2 == 2u) {
                    return Callback{all_cores ? &tlb_asid_all_cb : &tlb_asid_local_cb, owner};
                }
            } else if (opc1 == 0u && n == 13u && m == 0u && opc2 == 1u) {
                /* CONTEXTIDR: ARMv7-A ASID switch does NOT flush global TLB entries */
                return Callback{&contextidr_cb, owner};
            }
            return inner.CompileSendOneWord(two, opc1, crn, crm, opc2);
        }

        CallbackOrAccessTwoWords CompileSendTwoWords(
            bool two, unsigned opc, Dynarmic::A32::CoprocReg crm) override {
            return inner.CompileSendTwoWords(two, opc, crm);
        }

        CallbackOrAccessOneWord CompileGetOneWord(
            bool two, unsigned opc1, Dynarmic::A32::CoprocReg crn,
            Dynarmic::A32::CoprocReg crm, unsigned opc2) override {
            const uint32_t n = static_cast<uint32_t>(crn);
            const uint32_t m = static_cast<uint32_t>(crm);
            if (opc1 == 0u) {
                if (n == 0u && m == 0u) {
                    if (opc2 == 0u) return &owner->cp15.midr;
                    if (opc2 == 5u) return &mpidr_val;
                } else if (n == 1u && m == 0u && opc2 == 0u) {
                    return &owner->cp15.sctlr;
                } else if (n == 2u && m == 0u) {
                    if (opc2 == 0u) return &owner->cp15.ttbr0;
                    if (opc2 == 1u) return &owner->cp15.ttbr1;
                    if (opc2 == 2u) return &owner->cp15.ttbcr;
                } else if (n == 3u && m == 0u && opc2 == 0u) {
                    return &owner->cp15.dacr;
                } else if (n == 12u && m == 0u && opc2 == 0u) {
                    return &owner->cp15.vbar;
                } else if (n == 13u && m == 0u && opc2 == 1u) {
                    return &owner->cp15.contextidr;
                }
            }
            return inner.CompileGetOneWord(two, opc1, crn, crm, opc2);
        }

        CallbackOrAccessTwoWords CompileGetTwoWords(
            bool two, unsigned opc, Dynarmic::A32::CoprocReg crm) override {
            return inner.CompileGetTwoWords(two, opc, crm);
        }

        std::optional<Callback> CompileLoadWords(
            bool two, bool long_transfer, Dynarmic::A32::CoprocReg crd, std::optional<std::uint8_t> option) override {
            return inner.CompileLoadWords(two, long_transfer, crd, option);
        }

        std::optional<Callback> CompileStoreWords(
            bool two, bool long_transfer, Dynarmic::A32::CoprocReg crd, std::optional<std::uint8_t> option) override {
            return inner.CompileStoreWords(two, long_transfer, crd, option);
        }

        std::uint64_t read_64(std::uint32_t opc1, std::uint32_t crm) const { return inner.read_64(opc1, crm); }
        void write_64(std::uint32_t opc1, std::uint32_t crm, std::uint64_t val) { inner.write_64(opc1, crm, val); }
    };

    /* CP14 (ThumbEE TEECR/TEEHBR + Debug RAZ/WI): Cortex-A7 ID_PFR0 advertises State3=1 (ThumbEE),
     * so standard ARMv7 Linux kernels execute mcr/mrc p14, 6, ... in __errata_finish (proc-v7.S). */
    class FujiCp14Coprocessor final : public Dynarmic::A32::Coprocessor {
    public:
        uint32_t teecr = 0u;
        uint32_t teehbr = 0u;

        std::optional<Callback> CompileInternalOperation(
            bool, unsigned, Dynarmic::A32::CoprocReg, Dynarmic::A32::CoprocReg,
            Dynarmic::A32::CoprocReg, unsigned) override {
            return Callback{&nop_cb, nullptr};
        }

        CallbackOrAccessOneWord CompileSendOneWord(
            bool two, unsigned opc1, Dynarmic::A32::CoprocReg crn,
            Dynarmic::A32::CoprocReg crm, unsigned opc2) override {
            if (!two && opc1 == 6u && static_cast<uint32_t>(crm) == 0u && opc2 == 0u) {
                if (static_cast<uint32_t>(crn) == 0u) return &teecr;
                if (static_cast<uint32_t>(crn) == 1u) return &teehbr;
            }
            return Callback{&nop_cb, nullptr};
        }

        CallbackOrAccessTwoWords CompileSendTwoWords(bool, unsigned, Dynarmic::A32::CoprocReg) override {
            return Callback{&nop_cb, nullptr};
        }

        CallbackOrAccessOneWord CompileGetOneWord(
            bool two, unsigned opc1, Dynarmic::A32::CoprocReg crn,
            Dynarmic::A32::CoprocReg crm, unsigned opc2) override {
            if (!two && opc1 == 6u && static_cast<uint32_t>(crm) == 0u && opc2 == 0u) {
                if (static_cast<uint32_t>(crn) == 0u) return &teecr;
                if (static_cast<uint32_t>(crn) == 1u) return &teehbr;
            }
            return Callback{&zero_cb, nullptr};
        }

        CallbackOrAccessTwoWords CompileGetTwoWords(bool, unsigned, Dynarmic::A32::CoprocReg) override {
            return Callback{&zero_cb, nullptr};
        }

        std::optional<Callback> CompileLoadWords(
            bool, bool, Dynarmic::A32::CoprocReg, std::optional<std::uint8_t>) override {
            return std::nullopt;
        }

        std::optional<Callback> CompileStoreWords(
            bool, bool, Dynarmic::A32::CoprocReg, std::optional<std::uint8_t>) override {
            return std::nullopt;
        }

    private:
        static std::uint64_t zero_cb(void*, std::uint32_t, std::uint32_t) { return 0; }
        static std::uint64_t nop_cb(void*, std::uint32_t, std::uint32_t) { return 0; }
    };

    std::shared_ptr<FujiCp15Coprocessor> cp15_coproc;
    std::shared_ptr<FujiCp14Coprocessor> cp14_coproc;
    std::shared_ptr<ArmVfpSystemCoprocessor> cp10_coproc;
    std::unique_ptr<Dynarmic::A32::Jit> jit;
    PrivMemCallbacks priv_mem_cbs;
    bool has_non_global_pages = false;

    /* Per-run execution state */
    static constexpr uint32_t XCORE_TLB_FLUSH_ALL   = 1u << 0;
    static constexpr uint32_t XCORE_ICACHE_CLEAR    = 1u << 1;
    static constexpr uint32_t XCORE_FASTMEM_RESYNC  = 1u << 2;
    static constexpr uint32_t XCORE_CODE_WATCH_SYNC = 1u << 3;
    static constexpr uint32_t XCORE_IRQ_CHECK       = 1u << 4;

    uint64_t run_budget = 0;
    uint64_t run_executed = 0;
    uint64_t run_base_vtick = 0;
    uint32_t run_base_subticks = 0;
    uint64_t run_translated_fetches = 0;
    uint64_t run_fast_mmio_hits = 0;
    alignas(4) uint32_t irq_pending = 0;
    std::atomic<uint32_t> pending_xcore_ops{0u};
    std::atomic<uint32_t> mt_core_active{0u};
    alignas(64) std::atomic<uint64_t> mt_exec_atomic{0u};
    std::vector<uint32_t> pending_watch_pages;

    bool stop_latched = false;
    bool dabt_latched = false;
    bool deferred_stop_pending = false;
    uint32_t stop_reason = FUJI_DYN_STOP_QUANTUM;
    uint32_t stop_insn = 0;
    uint32_t fault_addr = 0;
    uint32_t fault_status = 0;
    uint32_t svc_imm = 0;
    uint32_t last_pabt_fsr = 0;
    bool resume_from_tracepoint = false;
    uint32_t resume_tp_pc = 0;
    bool bypass_tp_once = false;
    uint32_t bypass_tp_pc = 0;
    bool in_mmio_callback = false;
    bool mmu_ctrl_changed = false;
    bool pending_jit_clear = false;
    uint32_t pending_jit_inval_va = 0;
    uint32_t pending_jit_inval_sz = 0;

    inline void invalidate_fastmem_local() {
        if (!engine->fastmem.active()) return;
        if (engine->mt_epoch_active.load(std::memory_order_acquire)) {
            std::lock_guard<std::mutex> lk(engine->meta_lock);
            engine->fastmem.invalidate_core(core_id);
        } else {
            engine->fastmem.invalidate_core(core_id);
        }
    }

    inline void request_jit_clear() {
        pending_jit_clear = true;
        if (jit && jit->IsExecuting()) {
            jit->HaltExecution(Dynarmic::HaltReason::CacheInvalidation);
        }
    }

    inline void request_jit_inval(uint32_t va, uint32_t sz) {
        if (pending_jit_inval_sz != 0u) {
            pending_jit_clear = true;
        } else {
            pending_jit_inval_va = va;
            pending_jit_inval_sz = sz;
        }
        if (jit && jit->IsExecuting()) {
            jit->HaltExecution(Dynarmic::HaltReason::CacheInvalidation);
        }
    }

    inline void drain_pending_jit_inval() {
        if (pending_jit_clear) {
            pending_jit_clear = false;
            pending_jit_inval_sz = 0;
            if (jit) {
                jit->ClearCache();
                jit->ClearHalt(Dynarmic::HaltReason::CacheInvalidation);
            }
        } else if (pending_jit_inval_sz != 0u) {
            const uint32_t va = pending_jit_inval_va;
            const uint32_t sz = pending_jit_inval_sz;
            pending_jit_inval_sz = 0;
            if (jit) {
                jit->InvalidateCacheRange(va, sz);
                jit->ClearHalt(Dynarmic::HaltReason::CacheInvalidation);
            }
        }
    }

    inline uint32_t load_irq_pending() const {
        return std::atomic_ref<uint32_t>(const_cast<uint32_t&>(irq_pending)).load(std::memory_order_acquire);
    }

    inline void store_irq_pending(uint32_t v) {
        std::atomic_ref<uint32_t>(irq_pending).store(v, std::memory_order_release);
    }

    inline void sync_fastmem_safe(bool force_resync) {
        if (!engine->fastmem.active()) return;
        const bool usr = is_user_mode();
        if (!force_resync && engine->fastmem.is_core_synced_fast(core_id, cp15, usr)) [[likely]] {
            return;
        }
        if (engine->mt_epoch_active.load(std::memory_order_acquire)) {
            std::lock_guard<std::mutex> lk(engine->meta_lock);
            engine->fastmem.sync_for_core(core_id, engine->bus, cp15, usr, engine->watched_code_pages, force_resync);
        } else {
            engine->fastmem.sync_for_core(core_id, engine->bus, cp15, usr, engine->watched_code_pages, force_resync);
        }
    }

    void process_pending_xcore_ops() {
        const uint32_t ops = pending_xcore_ops.load(std::memory_order_acquire);
        if (ops == 0u) [[likely]] return;
        if ((ops & XCORE_TLB_FLUSH_ALL) != 0u) {
            mmu.flush();
            has_non_global_pages = false;
        }
        if ((ops & XCORE_ICACHE_CLEAR) != 0u) {
            if (jit) jit->ClearCache();
            mmu.clear_watched_code_pages();
        }
        if ((ops & XCORE_CODE_WATCH_SYNC) != 0u) {
            std::vector<uint32_t> pages;
            {
                std::lock_guard<std::mutex> lk(engine->meta_lock);
                pages.swap(pending_watch_pages);
            }
            for (uint32_t ppage : pages) {
                if (engine->is_code_page(ppage)) {
                    mmu.watch_code_page(ppage);
                } else {
                    mmu.unwatch_code_page(ppage);
                }
            }
        }
        if ((ops & XCORE_FASTMEM_RESYNC) != 0u) {
            sync_fastmem_safe(true);
        }
        if ((ops & XCORE_IRQ_CHECK) != 0u) {
            if (load_irq_pending() != 0u && (jit->Cpsr() & CPSR_I_BIT) == 0u) {
                latch_stop(FUJI_DYN_STOP_IRQ);
            }
        }
        /* Clear handled bits ONLY AFTER invalidations have fully completed so synchronous
         * cross-core shootdown senders never proceed before target caches/TLBs are flushed! */
        pending_xcore_ops.fetch_and(~ops, std::memory_order_acq_rel);
    }

    inline void publish_live_state() const {
        if (engine->mt_epoch_active.load(std::memory_order_relaxed) && core_id != 0u) {
            /* In MT mode only the owner thread writes live_state (worker MMIO proxies write it on owner thread). */
            return;
        }
        FujiDynLiveState* ls = engine->live_state;
        if (!ls) return;
        const uint32_t cpsr = jit->Cpsr();
        ls->current_vtick = engine->live_vtick();
        ls->current_pc = jit->Regs()[15];
        ls->current_cpsr = cpsr;
        ls->active_core = core_id;
        ls->cpsr_by_core[core_id] = cpsr;
        ls->valid_mask |= (1u << core_id);
    }

    static void on_mode_switch_cb(void* user, uint32_t old_mode, uint32_t new_mode) {
        auto* c = static_cast<CoreState*>(user);
        c->banked.switch_mode(old_mode, new_mode, c->jit->Regs());
        if ((old_mode == FUJI_DYN_MODE_USR) != (new_mode == FUJI_DYN_MODE_USR)) {
            c->mmu.flush();
            if (c->engine->mt_epoch_active.load(std::memory_order_acquire)) {
                std::lock_guard<std::mutex> lk(c->engine->meta_lock);
                c->engine->fastmem.invalidate_core(c->core_id);
                c->engine->fastmem.sync_for_core(
                    c->core_id, c->engine->bus, c->cp15, new_mode == FUJI_DYN_MODE_USR,
                    c->engine->watched_code_pages);
            } else {
                c->engine->fastmem.invalidate_core(c->core_id);
                c->engine->fastmem.sync_for_core(
                    c->core_id, c->engine->bus, c->cp15, new_mode == FUJI_DYN_MODE_USR,
                    c->engine->watched_code_pages);
            }
        }
    }

    static uint32_t on_get_spsr_cb(void* user, uint32_t mode) {
        auto* c = static_cast<CoreState*>(user);
        return c->banked.get_spsr(mode);
    }

    static void on_set_spsr_cb(void* user, uint32_t mode, uint32_t value) {
        auto* c = static_cast<CoreState*>(user);
        c->banked.set_spsr(mode, value);
    }

    static void on_irq_unmasked_cb(void* user) {
        auto* c = static_cast<CoreState*>(user);
        c->latch_stop(FUJI_DYN_STOP_IRQ);
    }

    inline bool sync_gic_irq_pending_all_locked(uint32_t& out_woken_mask) const {
        out_woken_mask = 0u;
        if (!engine->gic_fastpath.is_active()) return false;
        bool other_core_woken = false;
        const bool in_cluster = engine->in_cluster_fast.load(std::memory_order_acquire);
        const uint32_t runnable_snap = engine->cluster_runnable_mask.load(std::memory_order_acquire);
        for (uint32_t i = 0; i < engine->num_cores; ++i) {
            if (engine->cores[i]) {
                const uint32_t prev = engine->cores[i]->load_irq_pending();
                const uint32_t next = engine->gic_fastpath.core_has_pending(i) ? 1u : 0u;
                engine->cores[i]->store_irq_pending(next);
                if (in_cluster && i != core_id && next != 0u) {
                    if ((runnable_snap & (1u << i)) == 0u || prev == 0u) {
                        engine->cluster_irq_woken_mask.fetch_or(1u << i, std::memory_order_acq_rel);
                        out_woken_mask |= (1u << i);
                        other_core_woken = true;
                    }
                }
            }
        }
        return other_core_woken;
    }

    inline bool sync_gic_irq_pending_all() const {
        uint32_t woken_mask = 0u;
        if (engine->mt_epoch_active.load(std::memory_order_acquire)) {
            bool any = false;
            {
                std::lock_guard<std::mutex> lk(engine->gic_lock);
                any = sync_gic_irq_pending_all_locked(woken_mask);
            }
            if (any) {
                notify_mt_irq_targets(woken_mask);
            }
            return any;
        }
        return sync_gic_irq_pending_all_locked(woken_mask);
    }

    inline void notify_mt_irq_targets(uint32_t woken_mask) const {
        const uint32_t active_mask = engine->mt_epoch_mask.load(std::memory_order_acquire);
        bool woke_sleeping = false;
        for (uint32_t i = 0; i < engine->num_cores; ++i) {
            const uint32_t bit = 1u << i;
            if ((woken_mask & bit) == 0u || i == core_id) continue;
            if ((active_mask & bit) != 0u) {
                if (engine->cores[i]) {
                    engine->cores[i]->pending_xcore_ops.fetch_or(XCORE_IRQ_CHECK, std::memory_order_acq_rel);
                    if (engine->cores[i]->jit) {
                        engine->cores[i]->jit->HaltExecution(Dynarmic::HaltReason::UserDefined2);
                    }
                }
            } else {
                woke_sleeping = true;
            }
        }
        if (woke_sleeping) {
            /* Sleeping WFI/WFE core received an IRQ/SGI: end current epoch promptly so it wakes immediately */
            engine->request_mt_epoch_stop(core_id);
        }
    }

    CoreState(FujiDynEngine* eng, uint32_t id) : engine(eng), core_id(id) {
        cp15.reset(id, eng->cbar_base);
        banked.reset();
        mmu.set_absolute_offset_page_table(true);
        mmu.set_code_write_watch(true);

        Cp15HostHooks hooks;
        hooks.on_tlb_flush = [this](bool all_cores) {
            if (engine->mt_epoch_active.load(std::memory_order_acquire)) {
                bool pt_was_dirty = false;
                {
                    std::lock_guard<std::mutex> lk(engine->meta_lock);
                    pt_was_dirty = all_cores ? engine->fastmem.any_pt_dirty() : engine->fastmem.is_pt_dirty(core_id);
                    mmu.flush();
                    has_non_global_pages = false;
                    if (all_cores && pt_was_dirty) {
                        engine->fastmem.invalidate_all();
                    }
                    engine->fastmem.sync_for_core(core_id, engine->bus, cp15, is_user_mode(), engine->watched_code_pages, pt_was_dirty);
                }
                if (all_cores) {
                    engine->broadcast_xcore_op_mt(core_id, XCORE_TLB_FLUSH_ALL | XCORE_FASTMEM_RESYNC);
                }
                return;
            }
            const bool pt_was_dirty = all_cores
                ? engine->fastmem.any_pt_dirty()
                : engine->fastmem.is_pt_dirty(core_id);
            if (all_cores) {
                for (auto& c : engine->cores) {
                    if (c) {
                        c->mmu.flush();
                        c->has_non_global_pages = false;
                    }
                }
                if (pt_was_dirty) {
                    engine->fastmem.invalidate_all();
                }
            } else {
                mmu.flush();
                has_non_global_pages = false;
            }
            /* Only resync Fastmem if CP15 MMU controls actually changed or PT pages were modified */
            engine->fastmem.sync_for_core(core_id, engine->bus, cp15, is_user_mode(), engine->watched_code_pages, pt_was_dirty);
        };
        hooks.on_icache_invalidate = [this](bool all_cores) {
            /* Smart ICIALLU/ICIALLUIS retention: skip wiping 4x 64 MiB JIT caches when
             * zero translated code pages were modified and no MMU remapping occurred. */
            if (!engine->code_pages_dirty.load(std::memory_order_acquire)) {
                return;
            }
            if (engine->mt_epoch_active.load(std::memory_order_acquire)) {
                if (all_cores) {
                    {
                        std::lock_guard<std::mutex> lk(engine->meta_lock);
                        if (!engine->code_pages_dirty.load(std::memory_order_acquire)) return;
                        engine->clear_code_pages();
                        engine->fastmem.invalidate_all(true);
                    }
                    if (jit) jit->ClearCache();
                    mmu.clear_watched_code_pages();
                    engine->broadcast_xcore_op_mt(core_id, XCORE_ICACHE_CLEAR | XCORE_FASTMEM_RESYNC);
                } else if (jit) {
                    jit->ClearCache();
                    mmu.clear_watched_code_pages();
                }
                return;
            }
            if (all_cores) {
                for (auto& c : engine->cores) {
                    if (c) {
                        if (c->jit) c->jit->ClearCache();
                        c->mmu.clear_watched_code_pages();
                    }
                }
                engine->clear_code_pages();
                engine->fastmem.invalidate_all(true);
            } else if (jit) {
                jit->ClearCache();
                mmu.clear_watched_code_pages();
                if (engine->num_cores == 1u) {
                    engine->clear_code_pages();
                    engine->fastmem.invalidate_all(true);
                }
            }
        };
        hooks.on_icache_invalidate_range = [this](uint32_t mva, uint32_t size) {
            if (jit && size > 0u) {
                jit->InvalidateCacheRange(mva, size);
            }
        };
        hooks.on_mmu_control_changed = [this]() {
            mmu_ctrl_changed = true;
            if ((cp15.sctlr & 2u) != 0u) {
                mmu.flush();
            }
            /* Fast-path no-op when only non-MMU SCTLR bits (e.g. C/I/Z cache toggles) changed */
            sync_fastmem_safe(false);
        };
        hooks.get_virtual_tick = [this]() -> uint64_t {
            return engine->live_vtick();
        };
        hooks.translate_for_ats = [this](uint32_t va, uint32_t access, bool user_mode) -> uint32_t {
            return mmu.translate_for_ats(engine->bus, cp15, va, access, user_mode);
        };

        cp15_coproc = std::make_shared<FujiCp15Coprocessor>(this, cp15, std::move(hooks));
        cp14_coproc = std::make_shared<FujiCp14Coprocessor>();
        cp10_coproc = std::make_shared<ArmVfpSystemCoprocessor>(cp15);
        priv_mem_cbs.read32 = [this](uint32_t va, bool& faulted) -> uint32_t {
            uint32_t v = mem_read_impl<uint32_t>(va);
            faulted = dabt_latched;
            return v;
        };
        priv_mem_cbs.write32 = [this](uint32_t va, uint32_t val, bool& faulted) {
            mem_write_impl<uint32_t>(va, val);
            faulted = dabt_latched;
        };

        Dynarmic::A32::UserConfig conf;
        conf.callbacks = this;
        conf.processor_id = id;
        conf.global_monitor = eng->exclusive_monitor.get();
        conf.arch_version = Dynarmic::A32::ArchVersion::v7;
        /* Full safe optimizations including BlockLinking (moeKiwiSAMA/dynarmic a32-soc-jit natively
         * fuses halt_reason == 0 && cycles_remaining > 0 at every LinkBlock/RSB/FastDispatch boundary). */
        conf.optimizations = Dynarmic::all_safe_optimizations;
        conf.page_table = mmu.page_table_ptr();
        conf.absolute_offset_page_table = true;
        /* DYN-V3: Hardware 4 GiB Fastmem (single-instruction direct RAM access; MMIO/non-identity/watched
         * pages fault once via PROT_NONE/PROT_READ and recompile cleanly to page_table).
         * When Fastmem occupies r13, keep read_page_table null so LDM/POP uses r14 directly in 1 instruction
         * without spilling a scratch GPR to load a 64-bit read_page_table immediate. */
        if (eng->fastmem.active()) {
            conf.fastmem_pointer = reinterpret_cast<uintptr_t>(eng->fastmem.core_base(id));
            conf.read_page_table = nullptr;
            conf.recompile_on_fastmem_failure = true;
        } else {
            conf.read_page_table = mmu.read_page_table_ptr();
        }
        // Unaligned doublewords depend on guest memory attributes and SCTLR.A. Route
        // them through the memory callback before the host performs an unaligned load/store.
        conf.detect_misaligned_access_via_page_table = 64;
        conf.only_detect_misalignment_via_page_table_on_page_boundary = false;
        conf.coprocessors[10] = cp10_coproc;
        conf.coprocessors[14] = cp14_coproc;
        conf.coprocessors[15] = cp15_coproc;
        conf.hook_hint_instructions = true;
        conf.hook_isb = false;
        conf.define_unpredictable_behaviour = true;
        // Preserve the faulting guest PC and register state when a memory callback takes
        // a synchronous abort in the middle of a translated block.
        conf.check_halt_on_memory_access = true;
        conf.enable_cycle_counting = true;
        conf.always_little_endian = true;
        conf.enable_system_mode = true;
        conf.irq_pending_ptr = &irq_pending;
        conf.system_callbacks_user = this;
        conf.on_mode_switch = &CoreState::on_mode_switch_cb;
        conf.on_get_spsr = &CoreState::on_get_spsr_cb;
        conf.on_set_spsr = &CoreState::on_set_spsr_cb;
        conf.on_irq_unmasked = &CoreState::on_irq_unmasked_cb;
        conf.code_cache_size = 64 * 1024 * 1024;

        jit = std::make_unique<Dynarmic::A32::Jit>(conf);
        reset_arch_state();
    }

    void reset_arch_state() {
        cp15.reset(core_id, engine->cbar_base);
        if (cp15_coproc) cp15_coproc->mpidr_val = cp15.mpidr();
        if (cp14_coproc) { cp14_coproc->teecr = 0u; cp14_coproc->teehbr = 0u; }
        banked.reset();
        mmu.flush();
        has_non_global_pages = false;
        jit->Reset();
        jit->Regs().fill(0);
        jit->ExtRegs().fill(0);
        jit->SetCpsr(FUJI_DYN_MODE_SVC | CPSR_I_BIT | CPSR_F_BIT);
        jit->SetFpscr(0);
        jit->ClearExclusiveState();
        resume_from_tracepoint = false;
        resume_tp_pc = 0u;
        bypass_tp_once = false;
        bypass_tp_pc = 0u;
        deferred_stop_pending = false;
        mt_exec_atomic.store(0u, std::memory_order_release);
        publish_live_state();
    }

    inline bool is_user_mode() const {
        return (jit->Cpsr() & CPSR_M_MASK) == FUJI_DYN_MODE_USR;
    }

    inline void latch_stop(uint32_t reason, Dynarmic::HaltReason hr = Dynarmic::HaltReason::UserDefined1) {
        if (!stop_latched) {
            stop_latched = true;
            stop_reason = reason;
        }
        if (jit->IsExecuting()) {
            jit->HaltExecution(hr);
        }
    }

    inline void latch_dabt(uint32_t va, uint32_t fsr) {
        dabt_latched = true;
        if (!stop_latched) {
            stop_latched = true;
            stop_reason = FUJI_DYN_STOP_FAULT_DABT;
            fault_addr = va;
            fault_status = fsr;
        }
        if (jit->IsExecuting()) {
            jit->HaltExecution(Dynarmic::HaltReason::MemoryAbort);
        }
    }

    /* ---- Translation-time hooks ---- */

    bool PreCodeReadHook(bool is_thumb, Dynarmic::A32::VAddr pc, Dynarmic::A32::IREmitter& ir) override {
        if (engine->has_tracepoints && !(bypass_tp_once && pc == bypass_tp_pc) && engine->tracepoints.count(pc) != 0) {
            ir.SetTerm(Dynarmic::IR::Term::Interpret(ir.current_location));
            return false;
        }
        /* With conf.enable_system_mode = true, A32 privileged instructions (CPS, MSR/MRS CPSR/SPSR,
         * S=1 Rd=15 eret, LDM_eret, VMSR/VMRS CP10) are compiled natively in x64 JIT, while SRS/RFE/
         * LDM_usr/STM_usr automatically emit InterpretThisInstruction() in Dynarmic's A32 frontend.
         * Only Thumb privileged instructions still require translation-time interception. */
        if (is_thumb) {
            auto maybe_hw1 = peek_code_u16(pc);
            if (maybe_hw1.has_value()) {
                uint16_t hw1 = *maybe_hw1;
                uint16_t hw2 = 0;
                if ((hw1 >> 11) >= 0x1Du) {
                    auto maybe_hw2 = peek_code_u16(pc + 2u);
                    if (maybe_hw2.has_value()) hw2 = *maybe_hw2;
                }
                if (thumb_needs_privileged_fallback(hw1, hw2)) {
                    ir.SetTerm(Dynarmic::IR::Term::Interpret(ir.current_location));
                    return false;
                }
            }
        }
        return true;
    }

    std::optional<std::uint32_t> peek_code_u32(uint32_t va) {
        auto tr = mmu.translate(engine->bus, cp15, va, FUJI_DYN_PERM_EXEC, is_user_mode(), true);
        if (!tr.ok || !tr.is_ram) return std::nullopt;
        if (!tr.is_global) has_non_global_pages = true;
        uint32_t word = 0;
        if (tr.host_page_ptr != nullptr && (va & 0xFFFu) <= 4092u) {
            std::memcpy(&word, tr.host_page_ptr + (va & 0xFFFu), 4u);
            return word;
        }
        if (engine->bus.read_phys_u32(tr.phys_addr, word)) return word;
        return std::nullopt;
    }

    std::optional<std::uint16_t> peek_code_u16(uint32_t va) {
        auto tr = mmu.translate(engine->bus, cp15, va, FUJI_DYN_PERM_EXEC, is_user_mode(), true);
        if (!tr.ok || !tr.is_ram) return std::nullopt;
        if (!tr.is_global) has_non_global_pages = true;
        uint16_t hw = 0;
        if (tr.host_page_ptr != nullptr && (va & 0xFFFu) <= 4094u) {
            std::memcpy(&hw, tr.host_page_ptr + (va & 0xFFFu), 2u);
            return hw;
        }
        if (engine->bus.mem_read(tr.phys_addr, &hw, 2u) == 0) return hw;
        return std::nullopt;
    }

    std::optional<std::uint32_t> MemoryReadCode(Dynarmic::A32::VAddr vaddr) override {
        auto tr = mmu.translate(engine->bus, cp15, vaddr, FUJI_DYN_PERM_EXEC, is_user_mode(), true);
        if (!tr.ok || !tr.is_ram) {
            last_pabt_fsr = tr.ok ? FAULT_SYNC_EXTERNAL_ABORT : tr.fault_status;
            return std::nullopt;
        }
        if (!tr.is_global) has_non_global_pages = true;
        if (tr.phys_addr < 0x100000000ull) {
            const uint32_t pa32 = static_cast<uint32_t>(tr.phys_addr);
            const uint32_t ppage = pa32 >> 12;
            engine->fetched_words.record(pa32);
            if (!engine->is_code_page(ppage)) {
                if (engine->mt_epoch_active.load(std::memory_order_acquire)) {
                    bool newly_marked = false;
                    {
                        std::lock_guard<std::mutex> lk(engine->meta_lock);
                        newly_marked = engine->mark_code_page(pa32);
                        if (newly_marked) {
                            engine->fastmem.protect_page_ro_all(ppage, pa32 == vaddr);
                            for (auto& cc : engine->cores) {
                                if (cc && cc.get() != this) {
                                    cc->pending_watch_pages.push_back(ppage);
                                    cc->pending_xcore_ops.fetch_or(XCORE_CODE_WATCH_SYNC, std::memory_order_release);
                                }
                            }
                        }
                    }
                    if (newly_marked) {
                        mmu.watch_code_page(ppage);
                        if (!engine->fastmem.active() || pa32 != vaddr) {
                            engine->broadcast_xcore_op_mt(core_id, XCORE_CODE_WATCH_SYNC);
                        }
                    }
                } else if (engine->mark_code_page(pa32)) {
                    for (auto& cc : engine->cores) {
                        if (cc) cc->mmu.watch_code_page(ppage);
                    }
                    engine->fastmem.protect_page_ro_all(ppage, pa32 == vaddr);
                }
            }
        }
        if (tr.phys_addr != static_cast<uint64_t>(vaddr) && !engine->is_code_page(vaddr >> 12)) {
            if (engine->mt_epoch_active.load(std::memory_order_acquire)) {
                std::lock_guard<std::mutex> lk(engine->meta_lock);
                engine->mark_code_page(vaddr);
            } else {
                engine->mark_code_page(vaddr);
            }
        }
        if ((cp15.sctlr & 1u) != 0u) {
            ++run_translated_fetches;
        }
        uint32_t word = 0;
        if (tr.host_page_ptr != nullptr && (vaddr & 0xFFFu) <= 4092u) {
            std::memcpy(&word, tr.host_page_ptr + (vaddr & 0xFFFu), 4u);
            return word;
        }
        if (engine->bus.read_phys_u32(tr.phys_addr, word)) return word;
        last_pabt_fsr = FAULT_SYNC_EXTERNAL_ABORT;
        return std::nullopt;
    }

    /* ---- Data memory callbacks ---- */

    struct MtLockInactiveGuard {
        CoreState* self;
        uint32_t prev_active;
        explicit MtLockInactiveGuard(CoreState* c, bool active_mt)
            : self(active_mt ? c : nullptr),
              prev_active(active_mt ? c->mt_core_active.exchange(0u, std::memory_order_acq_rel) : 0u) {}
        ~MtLockInactiveGuard() {
            if (self) {
                const uint32_t ops = self->pending_xcore_ops.load(std::memory_order_acquire);
                if (ops != 0u) {
                    self->process_pending_xcore_ops();
                    if (((ops & CoreState::XCORE_ICACHE_CLEAR) != 0u || self->stop_latched) && self->jit && self->jit->IsExecuting()) {
                        self->jit->HaltExecution(Dynarmic::HaltReason::UserDefined2);
                    }
                }
                self->mt_core_active.store(prev_active, std::memory_order_release);
            }
        }
    };

    template <typename T>
    inline T dispatch_mmio_read(uint32_t va, uint32_t phys_addr) {
        constexpr uint32_t sz = sizeof(T);
        const bool mt = engine->mt_epoch_active.load(std::memory_order_acquire);
        /* 1. Native C++ GICv2 fastpath (GICC_IAR/HPPIR/CTLR/PMR/BPR, GICD_IPRIORITYR/TYPER/bitmaps):
         *    ZERO JVM upcalls and ZERO unnecessary jit->Run() halts! */
        if constexpr (sz <= 4u) {
            if (engine->gic_fastpath.is_active()) {
                uint32_t gic_val = 0u;
                bool gic_hit = false;
                bool other_woken = false;
                uint32_t woken_mask = 0u;
                if (mt) {
                    MtLockInactiveGuard inact(this, true);
                    std::lock_guard<std::mutex> lk(engine->gic_lock);
                    gic_hit = engine->gic_fastpath.try_read(core_id, phys_addr, sz, gic_val);
                    if (gic_hit) {
                        other_woken = sync_gic_irq_pending_all_locked(woken_mask);
                    }
                } else {
                    gic_hit = engine->gic_fastpath.try_read(core_id, phys_addr, sz, gic_val);
                    if (gic_hit) {
                        other_woken = sync_gic_irq_pending_all_locked(woken_mask);
                    }
                }
                if (gic_hit) {
                    engine->bump_stat(engine->mmio_stats.ro_mmio_fast_hits);
                    ++run_fast_mmio_hits;
                    if (mt && other_woken) {
                        notify_mt_irq_targets(woken_mask);
                    }
                    if (load_irq_pending() != 0u && (jit->Cpsr() & CPSR_I_BIT) == 0u) {
                        latch_stop(FUJI_DYN_STOP_IRQ);
                    } else if (!mt && other_woken) {
                        latch_stop(FUJI_DYN_STOP_MMIO_HALT);
                    }
                    return static_cast<T>(gic_val);
                }
            }
        }
        /* 2. Fast contiguous read-only MMIO range hit (supports 1/2/4/8-byte reads, ZERO upcalls / ZERO halts) */
        if (engine->ro_range_count != 0u) {
            T range_val = 0;
            bool range_hit = false;
            if (mt) {
                std::lock_guard<std::mutex> lk(engine->ro_mmio_lock);
                range_hit = engine->try_read_ro_range<T>(phys_addr, range_val);
            } else {
                range_hit = engine->try_read_ro_range<T>(phys_addr, range_val);
            }
            if (range_hit) {
                engine->bump_stat(engine->mmio_stats.ro_mmio_fast_hits);
                ++run_fast_mmio_hits;
                return range_val;
            }
        }
        /* 2.5 Generic declarative live MMIO read-mirror bank (1/2/4-byte exact width, 100% lock-free,
         *     ZERO JVM upcalls, ZERO owner-thread proxy round-trips under 4-core SMP-MT!) */
        if constexpr (sz <= 4u) {
            uint32_t mirror_val = 0u;
            if (engine->try_read_mmio_mirror(phys_addr, sz, mirror_val)) {
                engine->bump_stat(engine->mmio_stats.ro_mmio_fast_hits);
                ++run_fast_mmio_hits;
                return static_cast<T>(mirror_val);
            }
        }
        /* 3. Split 64-bit LDRD/VLDM MMIO reads into two aligned 32-bit accesses via dynarmic::a32_sys */
        if constexpr (sz == 8u) {
            return static_cast<T>(MmioWidthSplitter::read64(
                va,
                [this](uint32_t a) { return mem_read_impl<uint32_t>(a); },
                dabt_latched,
                mt ? nullptr : &engine->mmio_stats));
        }
        if constexpr (sz == 4u) {
            uint32_t fast_val = 0u;
            bool needs_tick_flush = false;
            const uint64_t now_vtick = engine->live_vtick();
            bool timer_hit = false;
            if (mt) {
                MtLockInactiveGuard inact(this, true);
                std::lock_guard<std::mutex> lk(engine->gic_lock);
                timer_hit = engine->shared_timer_shadow != nullptr
                    ? engine->try_read_shared_timer32(phys_addr, now_vtick, fast_val, needs_tick_flush)
                    : engine->timer_bank.try_read32(phys_addr, now_vtick, fast_val, needs_tick_flush);
            } else {
                timer_hit = engine->shared_timer_shadow != nullptr
                    ? engine->try_read_shared_timer32(phys_addr, now_vtick, fast_val, needs_tick_flush)
                    : engine->timer_bank.try_read32(phys_addr, now_vtick, fast_val, needs_tick_flush);
            }
            if (timer_hit) {
                engine->bump_stat(engine->mmio_stats.timer_fast_hits);
                ++run_fast_mmio_hits;
                if (needs_tick_flush && !stop_latched && jit->IsExecuting()) {
                    jit->HaltExecution(Dynarmic::HaltReason::UserDefined2);
                }
                return static_cast<T>(fast_val);
            }
            bool ro_hit = false;
            if (mt) {
                std::lock_guard<std::mutex> lk(engine->ro_mmio_lock);
                ro_hit = engine->ro_mmio.lookup(phys_addr, fast_val);
            } else {
                ro_hit = engine->ro_mmio.lookup(phys_addr, fast_val);
            }
            if (ro_hit) {
                engine->bump_stat(engine->mmio_stats.ro_mmio_fast_hits);
                ++run_fast_mmio_hits;
                return static_cast<T>(fast_val);
            }
        }
        T result = 0;
        if (engine->mmio_read_cb) {
            if (mt && core_id != 0u) {
                /* Proxy non-fastpath MMIO read strictly onto owner thread (emulation-owner) */
                const uint64_t r = engine->submit_worker_mmio_request(
                    core_id, false, phys_addr, sz, 0u, jit->Regs()[15], jit->Cpsr());
                result = static_cast<T>(r);
                if (engine->host_halt_requested.load(std::memory_order_acquire)) {
                    latch_stop(FUJI_DYN_STOP_MMIO_HALT);
                } else if (load_irq_pending() != 0u && (jit->Cpsr() & CPSR_I_BIT) == 0u) {
                    latch_stop(FUJI_DYN_STOP_IRQ);
                }
            } else if (mt) {
                engine->drain_worker_mmio_requests();
                engine->active_core.store(core_id, std::memory_order_release);
                engine->bump_stat(engine->mmio_stats.upcall_reads);
                publish_live_state();
                uint32_t woken_mask = 0u;
                bool any_woken = false;
                {
                    MtLockInactiveGuard inact(this, true);
                    std::lock_guard<std::mutex> gic_lk(engine->gic_lock);
                    engine->mt_owner_in_mmio_cb = true;
                    in_mmio_callback = true;
                    uint64_t r = engine->mmio_read_cb(engine->mmio_user, phys_addr, sz);
                    in_mmio_callback = false;
                    engine->mt_owner_in_mmio_cb = false;
                    result = static_cast<T>(r);
                    any_woken = sync_gic_irq_pending_all_locked(woken_mask);
                }
                if (any_woken) {
                    notify_mt_irq_targets(woken_mask);
                }
                if (engine->host_halt_requested.load(std::memory_order_acquire)) {
                    latch_stop(FUJI_DYN_STOP_MMIO_HALT);
                }
            } else {
                engine->bump_stat(engine->mmio_stats.upcall_reads);
                publish_live_state();
                in_mmio_callback = true;
                uint64_t r = engine->mmio_read_cb(engine->mmio_user, phys_addr, sz);
                in_mmio_callback = false;
                result = static_cast<T>(r);
                if (sync_gic_irq_pending_all()) {
                    latch_stop(FUJI_DYN_STOP_MMIO_HALT);
                }
            }
        }
        if (!stop_latched && jit->IsExecuting()) {
            jit->HaltExecution(Dynarmic::HaltReason::UserDefined2);
        }
        return result;
    }

    template <typename T>
    inline void dispatch_mmio_write(uint32_t va, uint32_t phys_addr, T val) {
        constexpr uint32_t sz = sizeof(T);
        const bool mt = engine->mt_epoch_active.load(std::memory_order_acquire);
        /* 1. Native C++ GICv2 V3 write fastpath (GICC_EOIR/CTLR/PMR/BPR, GICD_IPRIORITYR,
         *    GICD_SGIR 0xF00, GICD_CPENDSGIR 0xF10..0xF1C): ZERO JVM upcalls! */
        if constexpr (sz <= 4u) {
            bool gic_hit = false;
            bool other_woken = false;
            uint32_t woken_mask = 0u;
            if (engine->gic_fastpath.is_active()) {
                if (mt) {
                    MtLockInactiveGuard inact(this, true);
                    std::lock_guard<std::mutex> lk(engine->gic_lock);
                    gic_hit = engine->try_gic_v3_write(core_id, phys_addr, sz, static_cast<uint32_t>(val));
                    if (gic_hit) {
                        other_woken = sync_gic_irq_pending_all_locked(woken_mask);
                    }
                } else {
                    gic_hit = engine->try_gic_v3_write(core_id, phys_addr, sz, static_cast<uint32_t>(val));
                    if (gic_hit) {
                        other_woken = sync_gic_irq_pending_all_locked(woken_mask);
                    }
                }
            }
            if (gic_hit) {
                engine->bump_stat(engine->mmio_stats.ro_mmio_fast_hits);
                ++run_fast_mmio_hits;
                if (mt) {
                    std::lock_guard<std::mutex> lk(engine->ro_mmio_lock);
                    if (!engine->ro_mmio.empty()) engine->ro_mmio.remove(phys_addr & ~3u);
                } else if (!engine->ro_mmio.empty()) {
                    engine->ro_mmio.remove(phys_addr & ~3u);
                }
                if (mt && other_woken) {
                    notify_mt_irq_targets(woken_mask);
                }
                if (load_irq_pending() != 0u && (jit->Cpsr() & CPSR_I_BIT) == 0u) {
                    latch_stop(FUJI_DYN_STOP_IRQ);
                } else if (!mt && other_woken) {
                    latch_stop(FUJI_DYN_STOP_MMIO_HALT);
                }
                return;
            }
        }
        /* 2. Split 64-bit STRD/VSTM MMIO writes into two aligned 32-bit accesses via dynarmic::a32_sys */
        if constexpr (sz == 8u) {
            MmioWidthSplitter::write64(
                va,
                static_cast<uint64_t>(val),
                [this](uint32_t a, uint32_t v) { mem_write_impl<uint32_t>(a, v); },
                dabt_latched,
                mt ? nullptr : &engine->mmio_stats);
            return;
        }
        if (mt && core_id != 0u) {
            if (engine->mmio_write_cb) {
                /* Owner thread invalidates ro_mmio/ro_ranges and runs mmio_write_cb strictly on owner thread */
                (void)engine->submit_worker_mmio_request(
                    core_id, true, phys_addr, sz, static_cast<uint64_t>(val), jit->Regs()[15], jit->Cpsr());
                if (engine->host_halt_requested.load(std::memory_order_acquire)) {
                    latch_stop(FUJI_DYN_STOP_MMIO_HALT);
                } else if (load_irq_pending() != 0u && (jit->Cpsr() & CPSR_I_BIT) == 0u) {
                    latch_stop(FUJI_DYN_STOP_IRQ);
                }
            } else {
                std::lock_guard<std::mutex> lk(engine->ro_mmio_lock);
                if (!engine->ro_mmio.empty()) engine->ro_mmio.remove(phys_addr & ~3u);
                if (engine->ro_range_count != 0u) engine->invalidate_ro_ranges_covering(phys_addr, sz);
            }
            if (!stop_latched && jit->IsExecuting()) {
                jit->HaltExecution(Dynarmic::HaltReason::UserDefined2);
            }
            return;
        }
        /* Any MMIO write invalidates any cached RO-MMIO slot or RO range covering this word */
        if (mt) {
            std::lock_guard<std::mutex> lk(engine->ro_mmio_lock);
            if (!engine->ro_mmio.empty()) engine->ro_mmio.remove(phys_addr & ~3u);
            if (engine->ro_range_count != 0u) engine->invalidate_ro_ranges_covering(phys_addr, sz);
        } else {
            if (!engine->ro_mmio.empty()) {
                engine->ro_mmio.remove(phys_addr & ~3u);
            }
            if (engine->ro_range_count != 0u) {
                engine->invalidate_ro_ranges_covering(phys_addr, sz);
            }
        }
        if (engine->mmio_write_cb) {
            if (mt) {
                engine->drain_worker_mmio_requests();
                engine->active_core.store(core_id, std::memory_order_release);
                engine->bump_stat(engine->mmio_stats.upcall_writes);
                publish_live_state();
                uint32_t woken_mask = 0u;
                bool any_woken = false;
                {
                    MtLockInactiveGuard inact(this, true);
                    std::lock_guard<std::mutex> gic_lk(engine->gic_lock);
                    engine->mt_owner_in_mmio_cb = true;
                    in_mmio_callback = true;
                    engine->mmio_write_cb(engine->mmio_user, phys_addr, sz, static_cast<uint64_t>(val));
                    in_mmio_callback = false;
                    engine->mt_owner_in_mmio_cb = false;
                    any_woken = sync_gic_irq_pending_all_locked(woken_mask);
                }
                if (any_woken) {
                    notify_mt_irq_targets(woken_mask);
                }
                if (engine->host_halt_requested.load(std::memory_order_acquire)) {
                    latch_stop(FUJI_DYN_STOP_MMIO_HALT);
                }
            } else {
                engine->bump_stat(engine->mmio_stats.upcall_writes);
                publish_live_state();
                in_mmio_callback = true;
                engine->mmio_write_cb(engine->mmio_user, phys_addr, sz, static_cast<uint64_t>(val));
                in_mmio_callback = false;
                if (sync_gic_irq_pending_all()) {
                    latch_stop(FUJI_DYN_STOP_MMIO_HALT);
                }
            }
        }
        if (!stop_latched && jit->IsExecuting()) {
            jit->HaltExecution(Dynarmic::HaltReason::UserDefined2);
        }
    }

    template <typename T>
    bool data_alignment_fault(uint32_t va, bool strict_align, uint32_t access) {
        if constexpr (sizeof(T) == 1) return false;
        if ((va & (sizeof(T) - 1u)) == 0u) return false;
        if (strict_align) return true;
        if constexpr (sizeof(T) == 8) {
            if ((va & 3u) != 0u) return true;
            // ARMv7-A A3.2.1: With SCTLR.A == 0, word-aligned (va & 7 == 4) 64-bit accesses
            // are allowed on Normal memory (both Cacheable and Non-Cacheable) and fault only
            // on Device or Strongly-Ordered memory.
            const auto tr = mmu.translate(engine->bus, cp15, va, access, is_user_mode(), false);
            if (tr.ok && (tr.device_or_strongly_ordered || tr.is_mmio)) {
                return true;
            }
            if (((va & 0xFFFu) + sizeof(T)) > 4096u) {
                const auto tr2 = mmu.translate(engine->bus, cp15, va + 4u, access, is_user_mode(), false);
                if (tr2.ok && (tr2.device_or_strongly_ordered || tr2.is_mmio)) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    template <typename T>
    T mem_read_impl(uint32_t va, bool force_user = false) {
        if (dabt_latched) return 0;
        constexpr uint32_t sz = sizeof(T);
        const bool strict_align = (cp15.sctlr & 2u) != 0u;
        if (data_alignment_fault<T>(va, strict_align, FUJI_DYN_PERM_READ)) [[unlikely]] {
            latch_dabt(va, FAULT_ALIGNMENT);
            return 0;
        }
        if (sz > 1u && ((va & 0xFFFu) + sz) > 4096u) {
            uint8_t buf[8]{};
            for (uint32_t i = 0; i < sz; ++i) {
                buf[i] = mem_read_impl<uint8_t>(va + i, force_user);
                if (dabt_latched) return 0;
            }
            T val = 0;
            std::memcpy(&val, buf, sz);
            return val;
        }

        const bool usr = force_user || is_user_mode();
        uint32_t fast_mmio_pa = 0u;
        if (mmu.try_translate_identity_mmio(engine->bus, cp15, va, FUJI_DYN_PERM_READ, usr, fast_mmio_pa)) {
            return dispatch_mmio_read<T>(va, fast_mmio_pa);
        }

        auto tr = mmu.translate(engine->bus, cp15, va, FUJI_DYN_PERM_READ, usr, !strict_align);
        if (!tr.ok) {
            latch_dabt(va, tr.fault_status);
            return 0;
        }
        if (!tr.is_global) has_non_global_pages = true;
        if (tr.is_ram) {
            T val = 0;
            if (tr.host_page_ptr != nullptr) {
                std::memcpy(&val, tr.host_page_ptr + (va & 0xFFFu), sz);
                return val;
            }
            if (engine->bus.mem_read(tr.phys_addr, &val, sz) == 0) return val;
            latch_dabt(va, FAULT_SYNC_EXTERNAL_ABORT);
            return 0;
        }
        if (tr.is_mmio) {
            return dispatch_mmio_read<T>(va, static_cast<uint32_t>(tr.phys_addr));
        }
        latch_dabt(va, FAULT_SYNC_EXTERNAL_ABORT);
        return 0;
    }

    template <typename T>
    void mem_write_impl(uint32_t va, T val, bool force_user = false) {
        if (dabt_latched) return;
        constexpr uint32_t sz = sizeof(T);
        const bool strict_align = (cp15.sctlr & 2u) != 0u;
        if (data_alignment_fault<T>(va, strict_align, FUJI_DYN_PERM_WRITE)) [[unlikely]] {
            latch_dabt(va, (1u << 11) | FAULT_ALIGNMENT);
            return;
        }
        if (sz > 1u && ((va & 0xFFFu) + sz) > 4096u) {
            uint8_t buf[8]{};
            std::memcpy(buf, &val, sz);
            for (uint32_t i = 0; i < sz; ++i) {
                mem_write_impl<uint8_t>(va + i, buf[i], force_user);
                if (dabt_latched) return;
            }
            return;
        }

        const bool usr = force_user || is_user_mode();
        uint32_t fast_mmio_pa = 0u;
        if (mmu.try_translate_identity_mmio(engine->bus, cp15, va, FUJI_DYN_PERM_WRITE, usr, fast_mmio_pa)) {
            dispatch_mmio_write<T>(va, fast_mmio_pa, val);
            return;
        }

        auto tr = mmu.translate(engine->bus, cp15, va, FUJI_DYN_PERM_WRITE, usr, !strict_align);
        if (!tr.ok) {
            latch_dabt(va, tr.fault_status);
            return;
        }
        if (!tr.is_global) has_non_global_pages = true;
        if (tr.is_ram) {
            const bool is_low32 = tr.phys_addr < 0x100000000ull;
            if (tr.host_page_ptr != nullptr) {
                if (is_low32 && !engine->fastmem.dual_view &&
                    engine->fastmem.is_page_protected(core_id, static_cast<uint32_t>(tr.phys_addr >> 12))) {
                    engine->fastmem.ensure_host_rw_range(static_cast<uint32_t>(tr.phys_addr), sz);
                }
                auto* ptr = tr.host_page_ptr + (va & 0xFFFu);
                bool stored = false;
                if constexpr (sizeof(T) == sizeof(uint32_t)) {
                    if (is_low32 && engine->fastmem.dual_view &&
                        (reinterpret_cast<uintptr_t>(ptr) % std::atomic_ref<uint32_t>::required_alignment) == 0 &&
                        mmu.is_watched_pt_page(static_cast<uint32_t>(tr.phys_addr >> 12))) {
                        // Firmware frequently reinstalls the same short descriptor. Use atomic_ref
                        // consistently for both the unchanged-PTE check and the modified-PTE store
                        // so concurrent MT cores never mix atomic_ref with non-atomic memcpy (UB).
                        std::atomic_ref<uint32_t> word(*reinterpret_cast<uint32_t*>(ptr));
                        uint32_t expected = static_cast<uint32_t>(val);
                        if (word.compare_exchange_strong(
                                expected, static_cast<uint32_t>(val),
                                std::memory_order_acq_rel, std::memory_order_acquire)) {
                            return;
                        }
                        word.store(static_cast<uint32_t>(val), std::memory_order_release);
                        stored = true;
                    }
                }
                if (!stored) {
                    std::memcpy(ptr, &val, sz);
                }
            } else if (engine->bus.mem_write(tr.phys_addr, &val, sz) != 0) {
                latch_dabt(va, (1u << 11) | FAULT_SYNC_EXTERNAL_ABORT);
                return;
            }
            if (is_low32) {
                const uint32_t pa32 = static_cast<uint32_t>(tr.phys_addr);
                const uint32_t ppage = pa32 >> 12;
                if (mmu.is_watched_pt_page(ppage)) {
                    mmu.flush();
                    uint32_t sec = 0xFFFFFFFFu;
                    if (engine->mt_epoch_active.load(std::memory_order_acquire)) {
                        std::lock_guard<std::mutex> lk(engine->meta_lock);
                        sec = engine->fastmem.on_pt_write_o1(core_id, pa32, cp15);
                    } else {
                        sec = engine->fastmem.on_pt_write_o1(core_id, pa32, cp15);
                    }
                    if (sec == 0xFFFFFFFFu || engine->has_code_pages(sec << 20, 1u << 20)) {
                        engine->code_pages_dirty.store(true, std::memory_order_release);
                    }
                }
                if (engine->write_overlaps_code(pa32, sz)) {
                    engine->code_pages_dirty.store(true, std::memory_order_release);
                    mmu.unwatch_code_page(ppage);
                    if (!engine->mt_epoch_active.load(std::memory_order_acquire)) {
                        for (auto& cc : engine->cores) {
                            if (cc && cc.get() != this) cc->mmu.unwatch_code_page(ppage);
                        }
                    }
                }
            }
            return;
        }
        if (tr.is_mmio) {
            dispatch_mmio_write<T>(va, static_cast<uint32_t>(tr.phys_addr), val);
            return;
        }
        latch_dabt(va, (1u << 11) | FAULT_SYNC_EXTERNAL_ABORT);
    }

    template <typename T>
    bool mem_write_exclusive_impl(uint32_t va, T val, T expected) {
        if (dabt_latched) return false;
        constexpr uint32_t sz = sizeof(T);
        const bool strict_align = (cp15.sctlr & 2u) != 0u;
        if constexpr (sz == 8u) {
            /* ARMv7-A A3.2.1: 64-bit exclusive store (STREXD) unconditionally requires
             * 8-byte alignment even when SCTLR.A == 0. */
            if ((va & 7u) != 0u) [[unlikely]] {
                latch_dabt(va, (1u << 11) | FAULT_ALIGNMENT);
                return false;
            }
        } else if constexpr (sz > 1u) {
            if (strict_align && (va & (sz - 1u)) != 0u) [[unlikely]] {
                latch_dabt(va, (1u << 11) | FAULT_ALIGNMENT);
                return false;
            }
        }
        auto tr = mmu.translate(engine->bus, cp15, va, FUJI_DYN_PERM_WRITE, is_user_mode(), !strict_align);
        if (!tr.ok || !tr.is_ram) {
            if (!tr.ok) latch_dabt(va, tr.fault_status);
            return false;
        }
        uint8_t* ptr = nullptr;
        if (tr.host_page_ptr != nullptr && ((va & 0xFFFu) + sz) <= 4096u) {
            ptr = tr.host_page_ptr + (va & 0xFFFu);
        } else {
            const auto* ram = engine->bus.find_ram(tr.phys_addr, sz);
            if (!ram) return false;
            ptr = ram->ptr + (tr.phys_addr - ram->base);
        }
        const bool is_low32 = tr.phys_addr < 0x100000000ull;
        if (is_low32 && !engine->fastmem.dual_view &&
            engine->fastmem.is_page_protected(core_id, static_cast<uint32_t>(tr.phys_addr >> 12))) {
            engine->fastmem.ensure_host_rw_range(static_cast<uint32_t>(tr.phys_addr), sz);
        }
        if ((reinterpret_cast<uintptr_t>(ptr) & (alignof(T) - 1u)) == 0u) [[likely]] {
            T cur = expected;
            if (!std::atomic_ref<T>(*reinterpret_cast<T*>(ptr))
                     .compare_exchange_strong(cur, val, std::memory_order_acq_rel, std::memory_order_acquire)) {
                return false;
            }
        } else {
            T current = 0;
            std::memcpy(&current, ptr, sz);
            if (current != expected) {
                return false;
            }
            std::memcpy(ptr, &val, sz);
        }
        if (is_low32) {
            const uint32_t pa32 = static_cast<uint32_t>(tr.phys_addr);
            const uint32_t ppage = pa32 >> 12;
            if (mmu.is_watched_pt_page(ppage)) {
                mmu.flush();
                uint32_t sec = 0xFFFFFFFFu;
                if (engine->mt_epoch_active.load(std::memory_order_acquire)) {
                    std::lock_guard<std::mutex> lk(engine->meta_lock);
                    sec = engine->fastmem.on_pt_write_o1(core_id, pa32, cp15);
                } else {
                    sec = engine->fastmem.on_pt_write_o1(core_id, pa32, cp15);
                }
                if (sec == 0xFFFFFFFFu || engine->has_code_pages(sec << 20, 1u << 20)) {
                    engine->code_pages_dirty.store(true, std::memory_order_release);
                }
            }
            if (engine->write_overlaps_code(pa32, sz)) {
                engine->code_pages_dirty.store(true, std::memory_order_release);
                mmu.unwatch_code_page(ppage);
                if (!engine->mt_epoch_active.load(std::memory_order_acquire)) {
                    for (auto& cc : engine->cores) {
                        if (cc && cc.get() != this) cc->mmu.unwatch_code_page(ppage);
                    }
                }
            }
        }
        return true;
    }

    std::uint8_t  MemoryRead8(Dynarmic::A32::VAddr vaddr) override  { return mem_read_impl<uint8_t>(vaddr); }
    std::uint16_t MemoryRead16(Dynarmic::A32::VAddr vaddr) override { return mem_read_impl<uint16_t>(vaddr); }
    std::uint32_t MemoryRead32(Dynarmic::A32::VAddr vaddr) override { return mem_read_impl<uint32_t>(vaddr); }
    std::uint64_t MemoryRead64(Dynarmic::A32::VAddr vaddr) override { return mem_read_impl<uint64_t>(vaddr); }

    void MemoryWrite8(Dynarmic::A32::VAddr vaddr, std::uint8_t value) override   { mem_write_impl<uint8_t>(vaddr, value); }
    void MemoryWrite16(Dynarmic::A32::VAddr vaddr, std::uint16_t value) override { mem_write_impl<uint16_t>(vaddr, value); }
    void MemoryWrite32(Dynarmic::A32::VAddr vaddr, std::uint32_t value) override { mem_write_impl<uint32_t>(vaddr, value); }
    void MemoryWrite64(Dynarmic::A32::VAddr vaddr, std::uint64_t value) override { mem_write_impl<uint64_t>(vaddr, value); }

    bool MemoryWriteExclusive8(Dynarmic::A32::VAddr vaddr, std::uint8_t value, std::uint8_t expected) override {
        return mem_write_exclusive_impl<uint8_t>(vaddr, value, expected);
    }
    bool MemoryWriteExclusive16(Dynarmic::A32::VAddr vaddr, std::uint16_t value, std::uint16_t expected) override {
        return mem_write_exclusive_impl<uint16_t>(vaddr, value, expected);
    }
    bool MemoryWriteExclusive32(Dynarmic::A32::VAddr vaddr, std::uint32_t value, std::uint32_t expected) override {
        return mem_write_exclusive_impl<uint32_t>(vaddr, value, expected);
    }
    bool MemoryWriteExclusive64(Dynarmic::A32::VAddr vaddr, std::uint64_t value, std::uint64_t expected) override {
        return mem_write_exclusive_impl<uint64_t>(vaddr, value, expected);
    }

    /* ---- Privileged instruction & tracepoint fallback ---- */

    void InterpreterFallback(Dynarmic::A32::VAddr pc, size_t num_instructions) override {
        for (size_t step = 0; step < num_instructions && !stop_latched; ++step) {
            const uint32_t cur_pc = (step == 0) ? pc : jit->Regs()[15];
            if (engine->has_tracepoints && !(bypass_tp_once && cur_pc == bypass_tp_pc) && engine->tracepoints.count(cur_pc) != 0) {
                jit->Regs()[15] = cur_pc;
                resume_from_tracepoint = true;
                resume_tp_pc = cur_pc;
                latch_stop(FUJI_DYN_STOP_TRACEPOINT);
                return;
            }

            uint32_t cpsr = jit->Cpsr();
            const bool was_irq_masked = (cpsr & CPSR_I_BIT) != 0u;
            const bool was_user = (cpsr & CPSR_M_MASK) == FUJI_DYN_MODE_USR;
            const bool is_thumb = (cpsr & CPSR_T_BIT) != 0u;

            bool handled = false;
            bool mode_changed = false;
            bool faulted = false;
            uint32_t raw_insn = 0;

            if (!is_thumb) {
                auto maybe_w = peek_code_u32(cur_pc);
                if (!maybe_w.has_value()) {
                    fault_addr = cur_pc;
                    fault_status = last_pabt_fsr ? last_pabt_fsr : FAULT_SECTION_TRANSLATION;
                    jit->Regs()[15] = cur_pc;
                    latch_stop(FUJI_DYN_STOP_FAULT_PABT, Dynarmic::HaltReason::MemoryAbort);
                    return;
                }
                raw_insn = *maybe_w;
                handled = execute_privileged_a32(cur_pc, raw_insn, jit->Regs(), cpsr, banked, cp15, priv_mem_cbs, mode_changed, faulted);
            } else {
                auto maybe_h1 = peek_code_u16(cur_pc);
                if (!maybe_h1.has_value()) {
                    fault_addr = cur_pc;
                    fault_status = last_pabt_fsr ? last_pabt_fsr : FAULT_SECTION_TRANSLATION;
                    jit->Regs()[15] = cur_pc;
                    latch_stop(FUJI_DYN_STOP_FAULT_PABT, Dynarmic::HaltReason::MemoryAbort);
                    return;
                }
                uint16_t hw1 = *maybe_h1;
                uint16_t hw2 = 0;
                if ((hw1 >> 11) >= 0x1Du) {
                    auto maybe_h2 = peek_code_u16(cur_pc + 2u);
                    if (maybe_h2.has_value()) hw2 = *maybe_h2;
                }
                raw_insn = (static_cast<uint32_t>(hw1) << 16) | hw2;
                handled = execute_privileged_thumb(cur_pc, hw1, hw2, jit->Regs(), cpsr, banked, cp15, priv_mem_cbs, mode_changed, faulted);
            }

            if (faulted) {
                jit->Regs()[15] = cur_pc;
                return;
            }
            if (!handled) {
                stop_insn = raw_insn;
                jit->Regs()[15] = cur_pc;
                latch_stop(FUJI_DYN_STOP_FAULT_UNDEF);
                return;
            }

            jit->SetCpsr(cpsr);
            const bool is_user_now = (cpsr & CPSR_M_MASK) == FUJI_DYN_MODE_USR;
            if (is_user_now != was_user) {
                mmu.flush();
                if (engine->mt_epoch_active.load(std::memory_order_acquire)) {
                    std::lock_guard<std::mutex> lk(engine->meta_lock);
                    engine->fastmem.invalidate_core(core_id);
                    engine->fastmem.sync_for_core(core_id, engine->bus, cp15, is_user_now, engine->watched_code_pages);
                } else {
                    engine->fastmem.invalidate_core(core_id);
                    engine->fastmem.sync_for_core(core_id, engine->bus, cp15, is_user_now, engine->watched_code_pages);
                }
            }

            AddTicks(1);

            /* If IRQ just became unmasked while irq_pending != 0, stop immediately so IRQ delivers cleanly */
            if (load_irq_pending() != 0u && was_irq_masked && (cpsr & CPSR_I_BIT) == 0u) {
                latch_stop(FUJI_DYN_STOP_IRQ);
                return;
            }
        }
    }

    /* ---- SVC & Exception callbacks ---- */

    void CallSVC(std::uint32_t swi) override {
        svc_imm = swi;
        if ((engine->auto_excp_mask & FUJI_DYN_AUTO_EXCP_SVC) != 0u) {
            uint32_t cpsr = jit->Cpsr();
            bool was_user = (cpsr & CPSR_M_MASK) == FUJI_DYN_MODE_USR;
            uint32_t lr_val = jit->Regs()[15]; /* Dynarmic already advanced PC past SVC */
            enter_exception_mode(jit->Regs(), cpsr, banked, cp15,
                                 FUJI_DYN_MODE_SVC, CPSR_I_BIT, lr_val, 0x08u);
            jit->SetCpsr(cpsr);
            jit->ClearExclusiveState();
            if (was_user) {
                mmu.flush();
                if (engine->mt_epoch_active.load(std::memory_order_acquire)) {
                    std::lock_guard<std::mutex> lk(engine->meta_lock);
                    engine->fastmem.invalidate_core(core_id);
                    engine->fastmem.sync_for_core(core_id, engine->bus, cp15, false, engine->watched_code_pages);
                } else {
                    engine->fastmem.invalidate_core(core_id);
                    engine->fastmem.sync_for_core(core_id, engine->bus, cp15, false, engine->watched_code_pages);
                }
            }
            return;
        }
        latch_stop(FUJI_DYN_STOP_SVC);
    }

    void ExceptionRaised(Dynarmic::A32::VAddr pc, Dynarmic::A32::Exception exception) override {
        using Exc = Dynarmic::A32::Exception;
        switch (exception) {
        case Exc::WaitForInterrupt:
            if (load_irq_pending() == 0u) {
                if (stop_latched && stop_reason == FUJI_DYN_STOP_MMIO_HALT &&
                    !engine->host_halt_requested.load(std::memory_order_acquire)) {
                    stop_reason = FUJI_DYN_STOP_WFI;
                } else {
                    latch_stop(FUJI_DYN_STOP_WFI);
                }
            }
            break;

        case Exc::WaitForEvent: {
            const uint32_t bit = 1u << core_id;
            const uint32_t prev = engine->wfe_event_mask.fetch_and(~bit, std::memory_order_acq_rel);
            if ((prev & bit) != 0u) {
                /* Event consumed */
            } else if (load_irq_pending() == 0u) {
                if (stop_latched && stop_reason == FUJI_DYN_STOP_MMIO_HALT &&
                    !engine->host_halt_requested.load(std::memory_order_acquire)) {
                    stop_reason = FUJI_DYN_STOP_WFE;
                } else {
                    latch_stop(FUJI_DYN_STOP_WFE);
                }
            }
            break;
        }

        case Exc::SendEvent:
            engine->wfe_event_mask.fetch_or((1u << engine->num_cores) - 1u, std::memory_order_release);
            if (engine->in_cluster_fast.load(std::memory_order_acquire)) {
                engine->cluster_sev_seen_mask.fetch_or(1u << core_id, std::memory_order_acq_rel);
                if ((engine->cluster_wfe_waiting_mask.load(std::memory_order_acquire) & ~(1u << core_id)) != 0u) {
                    if (engine->mt_epoch_active.load(std::memory_order_acquire)) {
                        engine->request_mt_epoch_stop(core_id);
                    } else {
                        latch_stop(FUJI_DYN_STOP_SEV);
                    }
                }
            } else {
                latch_stop(FUJI_DYN_STOP_SEV);
            }
            break;

        case Exc::SendEventLocal:
            engine->wfe_event_mask.fetch_or(1u << core_id, std::memory_order_release);
            break;

        case Exc::Yield:
        case Exc::PreloadData:
        case Exc::PreloadDataWithIntentToWrite:
        case Exc::PreloadInstruction:
            break;

        case Exc::NoExecuteFault: {
            jit->Regs()[15] = pc;
            fault_addr = pc;
            fault_status = last_pabt_fsr ? last_pabt_fsr : FAULT_SECTION_TRANSLATION;
            /* Evict the cached NoExecuteFault stub block at pc so once the guest OS demand-pages
             * the code page and returns to pc, Dynarmic re-fetches the real instruction. */
            engine->code_pages_dirty.store(true, std::memory_order_release);
            request_jit_inval(pc & ~3u, 4u);
            if ((engine->auto_excp_mask & FUJI_DYN_AUTO_EXCP_PABT) != 0u) {
                deliver_pabt_internal(fault_addr, fault_status);
                return;
            }
            latch_stop(FUJI_DYN_STOP_FAULT_PABT, Dynarmic::HaltReason::MemoryAbort);
            break;
        }

        case Exc::UndefinedInstruction:
        case Exc::UnpredictableInstruction:
        case Exc::DecodeError:
        case Exc::Breakpoint: {
            auto maybe_w = peek_code_u32(pc);
            stop_insn = maybe_w.value_or(0u);
            if (exception == Exc::UndefinedInstruction && maybe_w.has_value() &&
                try_emulate_arm_unpriv_ldst(pc, *maybe_w)) {
                return;
            }
            jit->Regs()[15] = pc;
            if ((engine->auto_excp_mask & FUJI_DYN_AUTO_EXCP_UNDEF) != 0u) {
                deliver_undef_internal();
                return;
            }
            latch_stop(FUJI_DYN_STOP_FAULT_UNDEF);
            break;
        }
        }
    }

    static bool arm_cond_passed(uint32_t cond, uint32_t cpsr) {
        const bool N = ((cpsr >> 31) & 1u) != 0u;
        const bool Z = ((cpsr >> 30) & 1u) != 0u;
        const bool C = ((cpsr >> 29) & 1u) != 0u;
        const bool V = ((cpsr >> 28) & 1u) != 0u;
        switch (cond & 0xFu) {
        case 0x0: return Z;
        case 0x1: return !Z;
        case 0x2: return C;
        case 0x3: return !C;
        case 0x4: return N;
        case 0x5: return !N;
        case 0x6: return V;
        case 0x7: return !V;
        case 0x8: return C && !Z;
        case 0x9: return !C || Z;
        case 0xA: return N == V;
        case 0xB: return N != V;
        case 0xC: return !Z && (N == V);
        case 0xD: return Z || (N != V);
        case 0xE: return true;
        default:  return false;
        }
    }

    /* Emulate ARMv7-A unprivileged load/store instructions (LDRT/STRT/LDRBT/STRBT/
     * LDRHT/STRHT/LDRSBT/LDRSHT), which Dynarmic A32 frontend stubs as UndefinedInstruction.
     * Used by Linux ARM __clear_user_std (strbtne) and uaccess helpers. */
    bool try_emulate_arm_unpriv_ldst(uint32_t pc, uint32_t w) {
        const uint32_t cpsr = jit->Cpsr();
        if ((cpsr & (1u << 5)) != 0u) return false; /* Thumb state */
        const uint32_t cond = (w >> 28) & 0xFu;
        if (cond == 0xFu) return false;

        const bool is_std_imm = (w & 0x0F200000u) == 0x04200000u; /* LDRT/STRT/LDRBT/STRBT A1 */
        const bool is_std_reg = (w & 0x0F200010u) == 0x06200000u; /* LDRT/STRT/LDRBT/STRBT A2 */
        const bool is_extra   = (w & 0x0F200090u) == 0x00200090u && ((w >> 5) & 3u) != 0u; /* LDRHT/STRHT/LDRSBT/LDRSHT */
        if (!is_std_imm && !is_std_reg && !is_extra) return false;

        if (!arm_cond_passed(cond, cpsr)) {
            jit->Regs()[15] = pc + 4u;
            return true;
        }

        const uint32_t rn = (w >> 16) & 0xFu;
        const uint32_t rt = (w >> 12) & 0xFu;
        if (rn == 15u || rt == 15u) return false;
        const bool U = ((w >> 23) & 1u) != 0u;
        const bool L = ((w >> 20) & 1u) != 0u;

        uint32_t offset = 0u;
        if (is_std_imm) {
            offset = w & 0xFFFu;
        } else if (is_std_reg) {
            const uint32_t rm = w & 0xFu;
            if (rm == 15u) return false;
            const uint32_t val = jit->Regs()[rm];
            const uint32_t stype = (w >> 5) & 3u;
            const uint32_t shift_n = (w >> 7) & 0x1Fu;
            switch (stype) {
            case 0u: offset = val << shift_n; break;
            case 1u: offset = (shift_n == 0u) ? 0u : (val >> shift_n); break;
            case 2u: offset = (shift_n == 0u) ? static_cast<uint32_t>(static_cast<int32_t>(val) >> 31)
                                              : static_cast<uint32_t>(static_cast<int32_t>(val) >> shift_n); break;
            case 3u:
                if (shift_n == 0u) {
                    const uint32_t carry = (cpsr >> 29) & 1u;
                    offset = (carry << 31) | (val >> 1);
                } else {
                    offset = (val >> shift_n) | (val << (32u - shift_n));
                }
                break;
            }
        } else {
            /* Extra unprivileged halfword/signed-byte: bit22=1 imm8, bit22=0 reg Rm */
            if (((w >> 22) & 1u) != 0u) {
                offset = ((w >> 4) & 0xF0u) | (w & 0xFu);
            } else {
                const uint32_t rm = w & 0xFu;
                if (rm == 15u) return false;
                offset = jit->Regs()[rm];
            }
        }

        const uint32_t addr = jit->Regs()[rn];
        const uint32_t new_rn = U ? (addr + offset) : (addr - offset);
        /* Point PC at the faulting instruction in case mem_*_impl latches a Data Abort. */
        jit->Regs()[15] = pc;

        uint32_t loaded = 0u;
        if (is_std_imm || is_std_reg) {
            const bool B = ((w >> 22) & 1u) != 0u;
            if (L) {
                loaded = B ? static_cast<uint32_t>(mem_read_impl<uint8_t>(addr, true))
                           : mem_read_impl<uint32_t>(addr, true);
            } else {
                const uint32_t val = jit->Regs()[rt];
                if (B) mem_write_impl<uint8_t>(addr, static_cast<uint8_t>(val & 0xFFu), true);
                else   mem_write_impl<uint32_t>(addr, val, true);
            }
        } else {
            const uint32_t op2 = (w >> 5) & 3u; /* 1=H, 2=SB, 3=SH */
            if (!L) {
                if (op2 != 1u) return false; /* Only STRHT exists for L=0 */
                mem_write_impl<uint16_t>(addr, static_cast<uint16_t>(jit->Regs()[rt] & 0xFFFFu), true);
            } else if (op2 == 1u) {
                loaded = static_cast<uint32_t>(mem_read_impl<uint16_t>(addr, true));
            } else if (op2 == 2u) {
                loaded = static_cast<uint32_t>(static_cast<int32_t>(static_cast<int8_t>(mem_read_impl<uint8_t>(addr, true))));
            } else {
                loaded = static_cast<uint32_t>(static_cast<int32_t>(static_cast<int16_t>(mem_read_impl<uint16_t>(addr, true))));
            }
        }

        if (dabt_latched || stop_latched) {
            return true; /* Data Abort latched at PC=pc */
        }
        jit->Regs()[rn] = new_rn;
        if (L && rt != rn) {
            jit->Regs()[rt] = loaded;
        }
        jit->Regs()[15] = pc + 4u;
        return true;
    }

    /* ---- Timing callbacks ---- */

    void AddTicks(std::uint64_t ticks) override {
        run_executed += ticks;
        if (engine->mt_epoch_active.load(std::memory_order_relaxed)) {
            mt_exec_atomic.store(run_executed, std::memory_order_relaxed);
            const uint64_t tot = engine->mt_epoch_total_exec.fetch_add(ticks, std::memory_order_relaxed) + ticks;
            if (tot >= engine->mt_epoch_total_cap) {
                engine->request_mt_epoch_stop(core_id);
            }
            return;
        }
        const uint32_t div = engine->vtick_divider;
        if (div <= 1u) {
            engine->current_vtick.store(run_base_vtick + run_executed, std::memory_order_release);
        } else if (engine->vtick_shift != 0u) {
            const uint64_t total = static_cast<uint64_t>(run_base_subticks) + run_executed;
            engine->current_vtick.store(run_base_vtick + (total >> engine->vtick_shift), std::memory_order_release);
            engine->vtick_subticks = static_cast<uint32_t>(total & engine->vtick_mask);
        } else {
            const uint64_t total = static_cast<uint64_t>(run_base_subticks) + run_executed;
            engine->current_vtick.store(run_base_vtick + (total / div), std::memory_order_release);
            engine->vtick_subticks = static_cast<uint32_t>(total % div);
        }
    }

    std::uint64_t GetTicksRemaining() override {
        if (stop_latched || run_executed >= run_budget) return 0;
        return run_budget - run_executed;
    }

    /* ---- Architectural exception delivery helpers ---- */

    int32_t deliver_irq_internal() {
        uint32_t cpsr = jit->Cpsr();
        bool was_user = (cpsr & CPSR_M_MASK) == FUJI_DYN_MODE_USR;
        if (!deliver_irq(jit->Regs(), cpsr, banked, cp15)) return -1; /* IRQ masked */
        jit->SetCpsr(cpsr);
        jit->ClearExclusiveState();
        if (was_user) { mmu.flush(); engine->fastmem.invalidate_core(core_id); }
        publish_live_state();
        return 0;
    }

    int32_t deliver_fiq_internal() {
        uint32_t cpsr = jit->Cpsr();
        bool was_user = (cpsr & CPSR_M_MASK) == FUJI_DYN_MODE_USR;
        if (!deliver_fiq(jit->Regs(), cpsr, banked, cp15)) return -1;
        jit->SetCpsr(cpsr);
        jit->ClearExclusiveState();
        if (was_user) { mmu.flush(); engine->fastmem.invalidate_core(core_id); }
        publish_live_state();
        return 0;
    }

    int32_t deliver_svc_internal() {
        uint32_t cpsr = jit->Cpsr();
        bool was_user = (cpsr & CPSR_M_MASK) == FUJI_DYN_MODE_USR;
        uint32_t lr_val = jit->Regs()[15];
        deliver_svc(jit->Regs(), cpsr, banked, cp15, lr_val);
        jit->SetCpsr(cpsr);
        jit->ClearExclusiveState();
        if (was_user) { mmu.flush(); engine->fastmem.invalidate_core(core_id); }
        publish_live_state();
        return 0;
    }

    int32_t deliver_undef_internal() {
        uint32_t cpsr = jit->Cpsr();
        bool was_user = (cpsr & CPSR_M_MASK) == FUJI_DYN_MODE_USR;
        deliver_undef(jit->Regs(), cpsr, banked, cp15);
        jit->SetCpsr(cpsr);
        jit->ClearExclusiveState();
        if (was_user) { mmu.flush(); engine->fastmem.invalidate_core(core_id); }
        publish_live_state();
        return 0;
    }

    int32_t deliver_pabt_internal(uint32_t far, uint32_t fsr) {
        uint32_t cpsr = jit->Cpsr();
        bool was_user = (cpsr & CPSR_M_MASK) == FUJI_DYN_MODE_USR;
        deliver_pabt(jit->Regs(), cpsr, banked, cp15, far, fsr);
        jit->SetCpsr(cpsr);
        jit->ClearExclusiveState();
        if (was_user) { mmu.flush(); engine->fastmem.invalidate_core(core_id); }
        publish_live_state();
        return 0;
    }

    /* ARMv7-A Base Restored Data Abort Model (ARM DDI 0406C §B1.9.7):
     * Dynarmic's A32 GetAddress() updates Rn before calling MemoryRead/MemoryWrite.
     * When a writeback load/store faults (e.g. Linux demand paging in __clear_user_std),
     * restore Rn to its pre-instruction value before delivering DABT so retrying the
     * faulting instruction does not double-increment Rn. */
    void rollback_base_reg_on_dabt(uint32_t fault_pc, uint32_t cpsr) {
        if ((cpsr & CPSR_T_BIT) != 0u) return;
        auto maybe_w = peek_code_u32(fault_pc);
        if (!maybe_w.has_value()) return;
        const uint32_t w = *maybe_w;
        const uint32_t cond = (w >> 28) & 0xFu;
        if (cond == 0xFu) return;

        const bool P = ((w >> 24) & 1u) != 0u;
        const bool U = ((w >> 23) & 1u) != 0u;
        const bool W = ((w >> 21) & 1u) != 0u;
        /* Only post-indexed (P=0, W=0) and pre-indexed writeback (P=1, W=1) go through
         * Dynarmic GetAddress() writeback; (P=0, W=1) unprivileged *T is handled by
         * try_emulate_arm_unpriv_ldst which defers Rn update until after memory access. */
        if (P != W) return;

        const uint32_t rn = (w >> 16) & 0xFu;
        if (rn == 15u) return;

        uint32_t offset = 0u;
        if ((w & 0x0E000000u) == 0x04000000u) {
            offset = w & 0xFFFu;
        } else if ((w & 0x0E000010u) == 0x06000000u) {
            const uint32_t rm = w & 0xFu;
            if (rm == 15u || rm == rn) return;
            const uint32_t val = jit->Regs()[rm];
            const uint32_t stype = (w >> 5) & 3u;
            const uint32_t shift_n = (w >> 7) & 0x1Fu;
            switch (stype) {
            case 0u: offset = val << shift_n; break;
            case 1u: offset = (shift_n == 0u) ? 0u : (val >> shift_n); break;
            case 2u: offset = (shift_n == 0u) ? static_cast<uint32_t>(static_cast<int32_t>(val) >> 31)
                                              : static_cast<uint32_t>(static_cast<int32_t>(val) >> shift_n); break;
            case 3u:
                if (shift_n == 0u) {
                    const uint32_t carry = (cpsr >> 29) & 1u;
                    offset = (carry << 31) | (val >> 1);
                } else {
                    offset = (val >> shift_n) | (val << (32u - shift_n));
                }
                break;
            }
        } else if ((w & 0x0E000090u) == 0x00000090u && ((w >> 5) & 3u) != 0u) {
            if (((w >> 22) & 1u) != 0u) {
                offset = ((w >> 4) & 0xF0u) | (w & 0xFu);
            } else {
                const uint32_t rm = w & 0xFu;
                if (rm == 15u || rm == rn) return;
                offset = jit->Regs()[rm];
            }
        } else {
            return;
        }

        auto& regs = jit->Regs();
        regs[rn] = U ? (regs[rn] - offset) : (regs[rn] + offset);
    }

    int32_t deliver_dabt_internal(uint32_t far, uint32_t fsr) {
        uint32_t cpsr = jit->Cpsr();
        bool was_user = (cpsr & CPSR_M_MASK) == FUJI_DYN_MODE_USR;
        rollback_base_reg_on_dabt(jit->Regs()[15], cpsr);
        deliver_dabt(jit->Regs(), cpsr, banked, cp15, far, fsr);
        jit->SetCpsr(cpsr);
        jit->ClearExclusiveState();
        if (was_user) { mmu.flush(); engine->fastmem.invalidate_core(core_id); }
        publish_live_state();
        return 0;
    }
};

static inline CoreState* get_core(FujiDynEngine* engine, uint32_t core) {
    if (!engine || core >= engine->num_cores) return nullptr;
    return engine->cores[core].get();
}

static void execute_core_mt_epoch(FujiDynEngine* engine, CoreState* c,
                                  uint64_t budget, uint64_t base_vtick, uint32_t base_subticks) {
    struct ActiveGuard {
        CoreState* core;
        ~ActiveGuard() {
            core->mt_core_active.store(0u, std::memory_order_release);
        }
    } active_guard{c};
    c->mt_core_active.store(1u, std::memory_order_release);

    c->run_budget = budget;
    c->run_executed = 0u;
    c->mt_exec_atomic.store(0u, std::memory_order_release);
    c->run_base_vtick = base_vtick;
    c->run_base_subticks = base_subticks;
    c->run_translated_fetches = 0u;
    c->run_fast_mmio_hits = 0u;
    if (c->deferred_stop_pending) {
        c->deferred_stop_pending = false;
        c->stop_latched = true;
        c->dabt_latched = (c->stop_reason == FUJI_DYN_STOP_FAULT_DABT);
        engine->request_mt_epoch_stop(c->core_id);
        return;
    }
    c->stop_latched = false;
    c->dabt_latched = false;
    c->stop_reason = FUJI_DYN_STOP_QUANTUM;
    c->stop_insn = 0u;
    c->fault_addr = 0u;
    c->fault_status = 0u;
    c->svc_imm = 0u;
    c->mmu_ctrl_changed = false;

    c->process_pending_xcore_ops();
    if (c->stop_latched) {
        return;
    }

    if (c->load_irq_pending() != 0u && (c->jit->Cpsr() & CPSR_I_BIT) == 0u) {
        c->stop_latched = true;
        c->stop_reason = FUJI_DYN_STOP_IRQ;
        return;
    }

    if (budget == 0u) {
        return;
    }

    if (c->resume_from_tracepoint) {
        const uint32_t cur_pc = c->jit->Regs()[15];
        c->resume_from_tracepoint = false;
        if (engine->has_tracepoints && cur_pc == c->resume_tp_pc && engine->tracepoints.count(cur_pc) != 0) {
            c->jit->ClearHalt(Dynarmic::HaltReason::UserDefined1 |
                              Dynarmic::HaltReason::UserDefined2 |
                              Dynarmic::HaltReason::MemoryAbort);
            c->bypass_tp_once = true;
            c->bypass_tp_pc = cur_pc;
            c->jit->InvalidateCacheRange(cur_pc, 4u);
            c->jit->Step();
            c->bypass_tp_once = false;
            c->jit->InvalidateCacheRange(cur_pc, 4u);
            if (c->stop_latched && c->stop_reason == FUJI_DYN_STOP_FAULT_DABT &&
                (engine->auto_excp_mask & FUJI_DYN_AUTO_EXCP_DABT) != 0u) {
                c->deliver_dabt_internal(c->fault_addr, c->fault_status);
                c->stop_latched = false;
                c->dabt_latched = false;
                c->stop_reason = FUJI_DYN_STOP_QUANTUM;
            }
        }
    }

    while (!c->stop_latched && c->run_executed < c->run_budget && !engine->mt_should_stop_epoch()) {
        c->process_pending_xcore_ops();
        if (c->stop_latched) break;
        c->sync_fastmem_safe(false);
        c->jit->ClearHalt(Dynarmic::HaltReason::UserDefined1 |
                          Dynarmic::HaltReason::UserDefined2 |
                          Dynarmic::HaltReason::MemoryAbort);
        if (c->core_id == 0u) {
            if (engine->mt_mmio_pending_mask.load(std::memory_order_seq_cst) != 0u) {
                engine->drain_worker_mmio_requests();
            }
            if (c->pending_xcore_ops.load(std::memory_order_seq_cst) != 0u) {
                c->process_pending_xcore_ops();
            }
            if (c->stop_latched || c->run_executed >= c->run_budget || engine->mt_should_stop_epoch()) {
                break;
            }
        } else {
            if (c->pending_xcore_ops.load(std::memory_order_seq_cst) != 0u) {
                c->process_pending_xcore_ops();
            }
            if (c->stop_latched || engine->mt_should_stop_epoch()) {
                break;
            }
        }

        Dynarmic::HaltReason hr = c->jit->Run();
        c->drain_pending_jit_inval();

        if (c->core_id == 0u && engine->mt_mmio_pending_mask.load(std::memory_order_acquire) != 0u) {
            engine->drain_worker_mmio_requests();
        }
        c->process_pending_xcore_ops();

        if (c->stop_latched) {
            if (c->stop_reason == FUJI_DYN_STOP_FAULT_DABT &&
                (engine->auto_excp_mask & FUJI_DYN_AUTO_EXCP_DABT) != 0u) {
                c->deliver_dabt_internal(c->fault_addr, c->fault_status);
                c->stop_latched = false;
                c->dabt_latched = false;
                c->stop_reason = FUJI_DYN_STOP_QUANTUM;
                continue;
            }
            if (c->stop_reason != FUJI_DYN_STOP_WFI && c->stop_reason != FUJI_DYN_STOP_WFE) {
                /* Host-visible stop (IRQ, fault, SVC, tracepoint, MMIO halt): wrap up epoch promptly */
                engine->request_mt_epoch_stop(c->core_id);
            }
            break;
        }
        if (Dynarmic::Has(hr, Dynarmic::HaltReason::CacheInvalidation) ||
            Dynarmic::Has(hr, Dynarmic::HaltReason::UserDefined2)) {
            continue;
        }
        if (c->run_executed >= c->run_budget || engine->mt_should_stop_epoch()) {
            c->stop_reason = FUJI_DYN_STOP_QUANTUM;
            break;
        }
        if (hr != Dynarmic::HaltReason{}) {
            c->stop_reason = FUJI_DYN_STOP_HALT;
            break;
        }
    }
}

FujiDynEngine::~FujiDynEngine() {
    shutdown_mt_workers();
    for (const auto& hb : high_ram_backings) {
        free_demand_zero_ram(hb.ptr, hb.size);
    }
    high_ram_backings.clear();
}

void FujiDynEngine::ensure_mt_workers_started() {
    if (mt_workers_started || num_cores <= 1u) return;
    mt_shutdown.store(false, std::memory_order_release);
    for (uint32_t i = 0u; i < FUJI_DYN_MAX_CORES; ++i) {
        mt_worker_gen[i].store(0u, std::memory_order_release);
    }
    for (uint32_t i = 1u; i < num_cores; ++i) {
        mt_workers[i] = std::thread(&FujiDynEngine::mt_worker_loop, this, i);
    }
    mt_workers_started = true;
}

void FujiDynEngine::shutdown_mt_workers() {
    if (!mt_workers_started) return;
    {
        std::lock_guard<std::mutex> lk(mt_pool_mutex);
        mt_shutdown.store(true, std::memory_order_release);
    }
    for (uint32_t i = 1u; i < num_cores; ++i) {
        mt_worker_wake_cv[i].notify_one();
    }
    for (uint32_t i = 1u; i < num_cores; ++i) {
        if (mt_workers[i].joinable()) {
            mt_workers[i].join();
        }
    }
    mt_workers_started = false;
    mt_shutdown.store(false, std::memory_order_release);
}

void FujiDynEngine::mt_worker_loop(uint32_t worker_core_id) {
    uint64_t seen_gen = 0u;
    const uint32_t my_bit = 1u << worker_core_id;
    constexpr uint32_t BUSY_SPIN_ITERS = 2000u;
    constexpr uint32_t IDLE_SPIN_ITERS = 128u;
    uint32_t spin_iters = IDLE_SPIN_ITERS;
    for (;;) {
        uint64_t cur_gen = mt_worker_gen[worker_core_id].load(std::memory_order_acquire);
        if (cur_gen == seen_gen && !mt_shutdown.load(std::memory_order_acquire)) {
            for (uint32_t spin = 0u; spin < spin_iters; ++spin) {
                fuji_cpu_pause();
                cur_gen = mt_worker_gen[worker_core_id].load(std::memory_order_acquire);
                if (cur_gen != seen_gen || mt_shutdown.load(std::memory_order_acquire)) break;
            }
        }
        if (cur_gen == seen_gen && !mt_shutdown.load(std::memory_order_acquire)) {
            std::unique_lock<std::mutex> lk(mt_pool_mutex);
            mt_workers_parked_mask.fetch_or(my_bit, std::memory_order_seq_cst);
            mt_worker_wake_cv[worker_core_id].wait(lk, [&]() {
                return mt_worker_gen[worker_core_id].load(std::memory_order_seq_cst) != seen_gen ||
                       mt_shutdown.load(std::memory_order_acquire);
            });
            mt_workers_parked_mask.fetch_and(~my_bit, std::memory_order_relaxed);
            cur_gen = mt_worker_gen[worker_core_id].load(std::memory_order_acquire);
        }
        if (mt_shutdown.load(std::memory_order_acquire)) {
            return;
        }
        seen_gen = cur_gen;
        mt_epoch_workers_entered.fetch_add(1u, std::memory_order_acq_rel);
        if (auto* c = get_core(this, worker_core_id)) {
            execute_core_mt_epoch(this, c, mt_epoch_budget, mt_epoch_base_vtick, mt_epoch_base_subticks);
            const bool short_or_idle = (c->run_executed < 256u) ||
                (c->stop_latched && (c->stop_reason == FUJI_DYN_STOP_WFI || c->stop_reason == FUJI_DYN_STOP_WFE));
            spin_iters = short_or_idle ? IDLE_SPIN_ITERS : BUSY_SPIN_ITERS;
        }
        const uint32_t prev = mt_workers_remaining.fetch_sub(1u, std::memory_order_seq_cst);
        if (prev == 1u && mt_owner_parked.load(std::memory_order_seq_cst) != 0u) {
            std::lock_guard<std::mutex> lk(mt_pool_mutex);
            mt_owner_cv.notify_one();
        }
    }
}

void FujiDynEngine::halt_other_active_cores_mt(uint32_t except_core, Dynarmic::HaltReason hr) {
    const uint32_t mask = mt_epoch_mask.load(std::memory_order_acquire);
    for (uint32_t i = 0u; i < num_cores; ++i) {
        if (i != except_core && (mask & (1u << i)) != 0u && cores[i] && cores[i]->jit) {
            cores[i]->jit->HaltExecution(hr);
        }
    }
}

void FujiDynEngine::request_mt_epoch_stop(uint32_t triggering_core) {
    mt_epoch_stop_requested.store(true, std::memory_order_release);
    halt_other_active_cores_mt(triggering_core, Dynarmic::HaltReason::UserDefined2);
}

uint64_t FujiDynEngine::live_vtick() {
    if (!mt_epoch_active.load(std::memory_order_acquire)) {
        return current_vtick.load(std::memory_order_acquire);
    }
    const uint32_t mask = mt_epoch_mask.load(std::memory_order_acquire);
    uint64_t sum = 0u;
    for (uint32_t i = 0u; i < num_cores; ++i) {
        if ((mask & (1u << i)) != 0u && cores[i]) {
            sum += cores[i]->mt_exec_atomic.load(std::memory_order_relaxed);
        }
    }
    uint64_t cand = mt_epoch_base_vtick;
    if (vtick_divider <= 1u) {
        cand = mt_epoch_base_vtick + sum;
    } else if (vtick_shift != 0u) {
        cand = mt_epoch_base_vtick + ((static_cast<uint64_t>(mt_epoch_base_subticks) + sum) >> vtick_shift);
    } else {
        cand = mt_epoch_base_vtick + ((static_cast<uint64_t>(mt_epoch_base_subticks) + sum) / vtick_divider);
    }
    advance_vtick_max(cand);
    return current_vtick.load(std::memory_order_acquire);
}

void FujiDynEngine::broadcast_xcore_op_mt(uint32_t sender_core, uint32_t op_mask) {
    CoreState* self = get_core(this, sender_core);
    const uint32_t prev_self_active = self ? self->mt_core_active.exchange(0u, std::memory_order_acq_rel) : 0u;
    const uint32_t epoch_mask = mt_epoch_mask.load(std::memory_order_acquire);
    for (uint32_t i = 0u; i < num_cores; ++i) {
        if (i == sender_core || !cores[i]) continue;
        if ((op_mask & CoreState::XCORE_ICACHE_CLEAR) != 0u && cores[i]->jit) {
            cores[i]->jit->ClearCache();
        }
        cores[i]->pending_xcore_ops.fetch_or(op_mask, std::memory_order_seq_cst);
        if ((epoch_mask & (1u << i)) != 0u && cores[i]->jit) {
            cores[i]->jit->HaltExecution(Dynarmic::HaltReason::UserDefined2);
        }
    }
    /* Synchronously wait for all active peer cores to exit JIT and finish flushing their TLB/ICache
     * BEFORE the sender can publish visibility flags in subsequent instructions (DSB ISH semantics). */
    for (;;) {
        if (sender_core == 0u && !mt_owner_in_mmio_cb &&
            mt_mmio_pending_mask.load(std::memory_order_acquire) != 0u) {
            drain_worker_mmio_requests();
        }
        bool all_flushed = true;
        for (uint32_t i = 0u; i < num_cores; ++i) {
            if (i == sender_core || (epoch_mask & (1u << i)) == 0u || !cores[i]) continue;
            if (cores[i]->mt_core_active.load(std::memory_order_acquire) != 0u &&
                (cores[i]->pending_xcore_ops.load(std::memory_order_acquire) & op_mask) != 0u) {
                if (cores[i]->jit) {
                    cores[i]->jit->HaltExecution(Dynarmic::HaltReason::UserDefined2);
                }
                all_flushed = false;
            }
        }
        if (all_flushed) break;
        fuji_cpu_pause();
    }
    if (self) {
        while (self->pending_xcore_ops.load(std::memory_order_acquire) != 0u) {
            self->process_pending_xcore_ops();
        }
        self->mt_core_active.store(prev_self_active, std::memory_order_release);
    }
}

uint64_t FujiDynEngine::submit_worker_mmio_request(uint32_t worker_core_id, bool is_write,
                                                   uint32_t phys_addr, uint32_t size,
                                                   uint64_t write_val, uint32_t pc, uint32_t cpsr) {
    CoreState* wc = get_core(this, worker_core_id);
    if (wc) {
        wc->mt_core_active.store(0u, std::memory_order_release);
    }
    MtMmioRequest& req = mt_mmio_reqs[worker_core_id];
    req.is_write = is_write;
    req.phys_addr = phys_addr;
    req.size = size;
    req.write_val = write_val;
    req.read_result = 0u;
    req.pc = pc;
    req.cpsr = cpsr;
    req.state.store(1u, std::memory_order_release);
    /* seq_cst fetch_or pairs with Core 0's ClearHalt + seq_cst load(mt_mmio_pending_mask) */
    mt_mmio_pending_mask.fetch_or(1u << worker_core_id, std::memory_order_seq_cst);
    if (cores[0] && cores[0]->jit) {
        cores[0]->jit->HaltExecution(Dynarmic::HaltReason::UserDefined2);
    }
    if (mt_owner_parked.load(std::memory_order_seq_cst) != 0u) {
        std::lock_guard<std::mutex> lk(mt_pool_mutex);
        mt_owner_cv.notify_one();
    }
    /* Hybrid spin-wait before parking for low-latency owner MMIO handoff */
    for (uint32_t spin = 0u; spin < 2000u; ++spin) {
        if (req.state.load(std::memory_order_acquire) == 2u) break;
        fuji_cpu_pause();
    }
    if (req.state.load(std::memory_order_acquire) != 2u) {
        std::unique_lock<std::mutex> lk(mt_pool_mutex);
        mt_mmio_done_cv[worker_core_id].wait(lk, [&]() {
            return req.state.load(std::memory_order_acquire) == 2u;
        });
    }
    const uint64_t result = req.read_result;
    req.state.store(0u, std::memory_order_release);
    if (wc) {
        wc->mt_core_active.store(1u, std::memory_order_release);
    }
    return result;
}

void FujiDynEngine::drain_worker_mmio_requests() {
    CoreState* c0 = get_core(this, 0u);
    const uint32_t prev_c0_active = c0 ? c0->mt_core_active.exchange(0u, std::memory_order_acq_rel) : 0u;
    struct DrainGuard {
        CoreState* c0;
        uint32_t prev;
        ~DrainGuard() {
            if (c0) {
                if (prev != 0u && c0->pending_xcore_ops.load(std::memory_order_acquire) != 0u) {
                    c0->process_pending_xcore_ops();
                }
                c0->mt_core_active.store(prev, std::memory_order_release);
            }
        }
    } drain_guard{c0, prev_c0_active};
    for (;;) {
        uint32_t pending = mt_mmio_pending_mask.exchange(0u, std::memory_order_acq_rel);
        if (pending == 0u) return;
        for (uint32_t k = 1u; k < num_cores; ++k) {
            if ((pending & (1u << k)) == 0u) continue;
            MtMmioRequest& req = mt_mmio_reqs[k];
            if (req.state.load(std::memory_order_acquire) != 1u) continue;
            CoreState* wc = get_core(this, k);
            active_core.store(k, std::memory_order_release);
            if (live_state) {
                live_state->current_vtick = live_vtick();
                live_state->current_pc = req.pc;
                live_state->current_cpsr = req.cpsr;
                live_state->active_core = k;
                live_state->cpsr_by_core[k] = req.cpsr;
                live_state->valid_mask |= (1u << k);
            }
            uint32_t woken_mask = 0u;
            bool any_woken = false;
            if (req.is_write) {
                {
                    std::lock_guard<std::mutex> lk(ro_mmio_lock);
                    if (!ro_mmio.empty()) ro_mmio.remove(req.phys_addr & ~3u);
                    if (ro_range_count != 0u) invalidate_ro_ranges_covering(req.phys_addr, req.size);
                }
                if (mmio_write_cb) {
                    bump_stat(mmio_stats.upcall_writes);
                    std::lock_guard<std::mutex> gic_lk(gic_lock);
                    mt_owner_in_mmio_cb = true;
                    if (wc) wc->in_mmio_callback = true;
                    mmio_write_cb(mmio_user, req.phys_addr, req.size, req.write_val);
                    if (wc) wc->in_mmio_callback = false;
                    mt_owner_in_mmio_cb = false;
                    if (wc) any_woken = wc->sync_gic_irq_pending_all_locked(woken_mask);
                }
            } else {
                if (mmio_read_cb) {
                    bump_stat(mmio_stats.upcall_reads);
                    std::lock_guard<std::mutex> gic_lk(gic_lock);
                    mt_owner_in_mmio_cb = true;
                    if (wc) wc->in_mmio_callback = true;
                    req.read_result = mmio_read_cb(mmio_user, req.phys_addr, req.size);
                    if (wc) wc->in_mmio_callback = false;
                    mt_owner_in_mmio_cb = false;
                    if (wc) any_woken = wc->sync_gic_irq_pending_all_locked(woken_mask);
                }
            }
            if (wc && any_woken) {
                wc->notify_mt_irq_targets(woken_mask);
            }
            active_core.store(0u, std::memory_order_release);
            {
                std::lock_guard<std::mutex> lk(mt_pool_mutex);
                req.state.store(2u, std::memory_order_release);
                mt_mmio_done_cv[k].notify_one();
            }
        }
    }
}

/* ============================================================================
 * C ABI Implementation
 * ============================================================================ */

extern "C" {

FUJI_DYN_EXPORT uint32_t fuji_dyn_abi_version(void) {
    return FUJI_DYN_ABI_VERSION;
}

FUJI_DYN_EXPORT FujiDynEngine* fuji_dyn_create(uint32_t num_cores, uint32_t cbar_base) {
    if (num_cores == 0u || num_cores > FUJI_DYN_MAX_CORES) return nullptr;
    auto engine = std::make_unique<FujiDynEngine>();
    engine->num_cores = num_cores;
    engine->cbar_base = cbar_base;
    engine->exclusive_monitor = std::make_unique<Dynarmic::ExclusiveMonitor>(num_cores);
    engine->fastmem.init(num_cores);
#if defined(__linux__)
    fuji_sig_before_jit_create();
#endif
    engine->cores.resize(num_cores);
    for (uint32_t i = 0; i < num_cores; ++i) {
        engine->cores[i] = std::make_unique<CoreState>(engine.get(), i);
    }
#if defined(__linux__)
    fuji_sig_after_jit_create();
    fuji_apply_thp_to_jit_code_caches();
#endif
    return engine.release();
}

FUJI_DYN_EXPORT void fuji_dyn_destroy(FujiDynEngine* engine) {
    if (!engine) return;
    delete engine;
#if defined(__linux__)
    fuji_sig_on_engine_destroy();
#endif
}

FUJI_DYN_EXPORT void fuji_dyn_reset_core(FujiDynEngine* engine, uint32_t core) {
    if (auto* c = get_core(engine, core)) {
        c->reset_arch_state();
        engine->fastmem.invalidate_core(core);
    }
}

FUJI_DYN_EXPORT int32_t fuji_dyn_map_ram(FujiDynEngine* engine, uint32_t phys_base, uint32_t size, void* host_ptr, uint32_t perms) {
    if (!engine) return -1;
    void* effective_ptr = host_ptr;
    bool fastmem_backed = false;
    if (effective_ptr == nullptr) {
        if (!engine->fastmem.active()) return -3;
        uint8_t* fm_rw = engine->fastmem.map_backing(phys_base, size, perms);
        if (fm_rw == nullptr) return -3;
        effective_ptr = fm_rw;
        fastmem_backed = true;
    }
    int32_t rc = engine->bus.map_ram(phys_base, size, effective_ptr, perms);
    if (rc == 0) {
        for (auto& c : engine->cores) {
            if (c) c->mmu.flush();
        }
        engine->fastmem.invalidate_all();
    } else if (fastmem_backed) {
        engine->fastmem.unmap_backing(phys_base, size);
    }
    return rc;
}

FUJI_DYN_EXPORT int32_t fuji_dyn_unmap_ram(FujiDynEngine* engine, uint32_t phys_base, uint32_t size) {
    if (!engine) return -1;
    int32_t rc = engine->bus.unmap_ram(phys_base, size);
    if (rc == 0) {
        engine->fastmem.unmap_backing(phys_base, size);
        for (auto& c : engine->cores) {
            if (c) {
                c->mmu.flush();
                c->jit->ClearCache();
            }
        }
        engine->fastmem.invalidate_all(true);
    }
    return rc;
}

FUJI_DYN_EXPORT int32_t fuji_dyn_map_ram64(FujiDynEngine* engine, uint64_t phys_base, uint64_t size, void* host_ptr, uint32_t perms) {
    if (!engine || size == 0u || phys_base >= PHYS_ADDR_LIMIT || size > (PHYS_ADDR_LIMIT - phys_base)) return -1;
    if (phys_base < PHYS_ADDR_32BIT && (phys_base + size) <= PHYS_ADDR_32BIT) {
        return fuji_dyn_map_ram(engine, static_cast<uint32_t>(phys_base), static_cast<uint32_t>(size), host_ptr, perms);
    }
    if (phys_base < PHYS_ADDR_32BIT) return -1; /* Do not straddle the 4 GiB Fastmem boundary */
    void* effective_ptr = host_ptr;
    uint8_t* owned_ptr = nullptr;
    if (effective_ptr == nullptr) {
        owned_ptr = alloc_demand_zero_ram(size);
        if (owned_ptr == nullptr) return -3;
        effective_ptr = owned_ptr;
    }
    const int32_t rc = engine->bus.map_ram(phys_base, size, effective_ptr, perms);
    if (rc == 0) {
        if (owned_ptr != nullptr) {
            engine->high_ram_backings.push_back(HighRamBacking{phys_base, size, owned_ptr});
        }
        for (auto& c : engine->cores) {
            if (c) c->mmu.flush();
        }
    } else if (owned_ptr != nullptr) {
        free_demand_zero_ram(owned_ptr, size);
    }
    return rc;
}

FUJI_DYN_EXPORT int32_t fuji_dyn_unmap_ram64(FujiDynEngine* engine, uint64_t phys_base, uint64_t size) {
    if (!engine || size == 0u || phys_base >= PHYS_ADDR_LIMIT || size > (PHYS_ADDR_LIMIT - phys_base)) return -1;
    if (phys_base < PHYS_ADDR_32BIT && (phys_base + size) <= PHYS_ADDR_32BIT) {
        return fuji_dyn_unmap_ram(engine, static_cast<uint32_t>(phys_base), static_cast<uint32_t>(size));
    }
    const int32_t rc = engine->bus.unmap_ram(phys_base, size);
    if (rc == 0) {
        for (auto it = engine->high_ram_backings.begin(); it != engine->high_ram_backings.end(); ++it) {
            if (it->phys_base == phys_base && it->size == size) {
                free_demand_zero_ram(it->ptr, it->size);
                engine->high_ram_backings.erase(it);
                break;
            }
        }
        for (auto& c : engine->cores) {
            if (c) {
                c->mmu.flush();
                c->jit->ClearCache();
            }
        }
    }
    return rc;
}

FUJI_DYN_EXPORT int32_t fuji_dyn_map_mmio(FujiDynEngine* engine, uint32_t phys_base, uint32_t size) {
    if (!engine) return -1;
    int32_t rc = engine->bus.map_mmio(phys_base, size);
    if (rc == 0) {
        for (auto& c : engine->cores) {
            if (c) c->mmu.flush();
        }
        engine->fastmem.invalidate_all();
    }
    return rc;
}

FUJI_DYN_EXPORT int32_t fuji_dyn_unmap_mmio(FujiDynEngine* engine, uint32_t phys_base, uint32_t size) {
    if (!engine) return -1;
    int32_t rc = engine->bus.unmap_mmio(phys_base, size);
    if (rc == 0) {
        for (auto& c : engine->cores) {
            if (c) c->mmu.flush();
        }
        engine->fastmem.invalidate_all();
    }
    return rc;
}

FUJI_DYN_EXPORT void fuji_dyn_set_mmio_callbacks(FujiDynEngine* engine, FujiDynMmioReadFn read_cb, FujiDynMmioWriteFn write_cb, void* user) {
    if (!engine) return;
    engine->mmio_read_cb = read_cb;
    engine->mmio_write_cb = write_cb;
    engine->mmio_user = user;
}

FUJI_DYN_EXPORT int32_t fuji_dyn_mem_read(FujiDynEngine* engine, uint32_t phys_addr, void* dst, uint32_t size) {
    if (!engine || (!dst && size > 0)) return -1;
    if (!engine->fastmem.dual_view && engine->fastmem.active() && size > 0u) {
        engine->fastmem.ensure_host_rw_range(phys_addr, size);
    }
    return engine->bus.mem_read(phys_addr, dst, size);
}

FUJI_DYN_EXPORT int32_t fuji_dyn_mem_write(FujiDynEngine* engine, uint32_t phys_addr, const void* src, uint32_t size) {
    if (!engine || (!src && size > 0)) return -1;
    if (!engine->fastmem.dual_view && engine->fastmem.active() && size > 0u) {
        engine->fastmem.ensure_host_rw_range(phys_addr, size);
    }
    int32_t rc = engine->bus.mem_write(phys_addr, src, size);
    if (rc == 0 && size > 0) {
        const bool mt = engine->mt_epoch_active.load(std::memory_order_acquire);
        if (engine->has_code_pages(phys_addr, size)) {
            engine->code_pages_dirty.store(true, std::memory_order_release);
        }
        const uint32_t caller_core = engine->active_core.load(std::memory_order_acquire);
        for (auto& c : engine->cores) {
            if (!c) continue;
            if ((c->cp15.sctlr & 1u) != 0u) {
                /* Check if host write touched active page tables */
                uint32_t first_p = phys_addr >> 12;
                uint32_t last_p = (phys_addr + size - 1u) >> 12;
                bool touched_pt = false;
                if (mt && c->core_id != caller_core) {
                    std::lock_guard<std::mutex> lk(engine->meta_lock);
                    const uint32_t n = c->cp15.ttbcr & 7u;
                    const uint32_t ttbr0_mask = (0xFFFFC000u >> n) & 0xFFFFFF80u;
                    const uint32_t l1_p = (c->cp15.ttbr0 & ttbr0_mask) >> 12;
                    if (last_p >= l1_p && first_p <= l1_p + 3u) {
                        touched_pt = true;
                    } else if (engine->fastmem.has_watched_pt_in_range(c->core_id, first_p, last_p)) {
                        touched_pt = true;
                    } else if (!engine->fastmem.active()) {
                        touched_pt = true;
                    }
                    if (touched_pt) {
                        engine->fastmem.invalidate_core(c->core_id);
                        c->pending_xcore_ops.fetch_or(CoreState::XCORE_TLB_FLUSH_ALL | CoreState::XCORE_FASTMEM_RESYNC,
                                                      std::memory_order_acq_rel);
                        if (c->jit) c->jit->HaltExecution(Dynarmic::HaltReason::CacheInvalidation);
                    }
                    continue;
                }
                if (last_p - first_p <= 64u) {
                    for (uint32_t p = first_p; p <= last_p; ++p) {
                        if (c->mmu.is_watched_pt_page(p)) { touched_pt = true; break; }
                    }
                } else {
                    const uint32_t n = c->cp15.ttbcr & 7u;
                    const uint32_t ttbr0_mask = (0xFFFFC000u >> n) & 0xFFFFFF80u;
                    const uint32_t l1_p = (c->cp15.ttbr0 & ttbr0_mask) >> 12;
                    if (last_p >= l1_p && first_p <= l1_p + 3u) {
                        touched_pt = true;
                    } else if (engine->fastmem.has_watched_pt_in_range(c->core_id, first_p, last_p)) {
                        touched_pt = true;
                    } else if (!engine->fastmem.active()) {
                        touched_pt = true;
                    }
                }
                if (touched_pt) {
                    c->mmu.flush();
                    if (mt) {
                        std::lock_guard<std::mutex> lk(engine->meta_lock);
                        engine->fastmem.invalidate_core(c->core_id);
                    } else {
                        engine->fastmem.invalidate_core(c->core_id);
                    }
                }
            }
        }
    }
    return rc;
}

FUJI_DYN_EXPORT int32_t fuji_dyn_mem_read64(FujiDynEngine* engine, uint64_t phys_addr, void* dst, uint32_t size) {
    if (!engine || (!dst && size > 0)) return -1;
    if (phys_addr < PHYS_ADDR_32BIT && (phys_addr + size) <= PHYS_ADDR_32BIT) {
        return fuji_dyn_mem_read(engine, static_cast<uint32_t>(phys_addr), dst, size);
    }
    return engine->bus.mem_read(phys_addr, dst, size);
}

FUJI_DYN_EXPORT int32_t fuji_dyn_mem_write64(FujiDynEngine* engine, uint64_t phys_addr, const void* src, uint32_t size) {
    if (!engine || (!src && size > 0)) return -1;
    if (phys_addr < PHYS_ADDR_32BIT && (phys_addr + size) <= PHYS_ADDR_32BIT) {
        return fuji_dyn_mem_write(engine, static_cast<uint32_t>(phys_addr), src, size);
    }
    return engine->bus.mem_write(phys_addr, src, size);
}

FUJI_DYN_EXPORT void fuji_dyn_invalidate_range(FujiDynEngine* engine, uint32_t phys_base, uint32_t size) {
    if (!engine || size == 0) return;
    if (engine->mt_epoch_active.load(std::memory_order_acquire)) {
        {
            std::lock_guard<std::mutex> lk(engine->meta_lock);
            if (!engine->has_and_clear_code_pages(phys_base, size)) return;
            engine->fastmem.invalidate_all(true);
        }
        const uint32_t caller = engine->active_core.load(std::memory_order_acquire);
        if (auto* cc = get_core(engine, caller)) {
            if (cc->jit) cc->jit->ClearCache();
            cc->pending_xcore_ops.fetch_or(CoreState::XCORE_ICACHE_CLEAR | CoreState::XCORE_FASTMEM_RESYNC,
                                           std::memory_order_acq_rel);
        }
        engine->broadcast_xcore_op_mt(caller, CoreState::XCORE_ICACHE_CLEAR | CoreState::XCORE_FASTMEM_RESYNC);
        return;
    }
    if (!engine->has_and_clear_code_pages(phys_base, size)) return;
    engine->fastmem.invalidate_all(true);
    for (auto& c : engine->cores) {
        if (!c || !c->jit) continue;
        c->jit->InvalidateCacheRange(phys_base, size);
    }
}

FUJI_DYN_EXPORT void fuji_dyn_clear_cache(FujiDynEngine* engine) {
    if (!engine) return;
    if (engine->mt_epoch_active.load(std::memory_order_acquire)) {
        {
            std::lock_guard<std::mutex> lk(engine->meta_lock);
            engine->clear_code_pages();
            engine->fastmem.invalidate_all(true);
        }
        const uint32_t caller = engine->active_core.load(std::memory_order_acquire);
        if (auto* cc = get_core(engine, caller)) {
            if (cc->jit) cc->jit->ClearCache();
            cc->pending_xcore_ops.fetch_or(CoreState::XCORE_ICACHE_CLEAR | CoreState::XCORE_FASTMEM_RESYNC,
                                           std::memory_order_acq_rel);
        }
        engine->broadcast_xcore_op_mt(caller, CoreState::XCORE_ICACHE_CLEAR | CoreState::XCORE_FASTMEM_RESYNC);
        return;
    }
    engine->clear_code_pages();
    engine->fastmem.invalidate_all(true);
    for (auto& c : engine->cores) {
        if (c) {
            if (c->jit) c->jit->ClearCache();
            c->mmu.clear_watched_code_pages();
        }
    }
}

FUJI_DYN_EXPORT void fuji_dyn_flush_tlb(FujiDynEngine* engine, uint32_t core) {
    if (!engine) return;
    if (engine->mt_epoch_active.load(std::memory_order_acquire)) {
        const uint32_t caller = engine->active_core.load(std::memory_order_acquire);
        if (core == 0xFFFFFFFFu) {
            if (auto* cc = get_core(engine, caller)) {
                cc->pending_xcore_ops.fetch_or(CoreState::XCORE_TLB_FLUSH_ALL, std::memory_order_acq_rel);
            }
            engine->broadcast_xcore_op_mt(caller, CoreState::XCORE_TLB_FLUSH_ALL);
        } else if (auto* c = get_core(engine, core)) {
            c->pending_xcore_ops.fetch_or(CoreState::XCORE_TLB_FLUSH_ALL, std::memory_order_acq_rel);
            if (c->jit) c->jit->HaltExecution(Dynarmic::HaltReason::UserDefined2);
        }
        return;
    }
    if (core == 0xFFFFFFFFu) {
        for (auto& c : engine->cores) {
            if (c) c->mmu.flush();
        }
    } else if (auto* c = get_core(engine, core)) {
        c->mmu.flush();
    }
}

FUJI_DYN_EXPORT int32_t fuji_dyn_translate(FujiDynEngine* engine, uint32_t core, uint32_t va, uint32_t access, uint32_t* out_pa) {
    auto* c = get_core(engine, core);
    if (!c || !out_pa) return -1;
    auto tr = c->mmu.translate(engine->bus, c->cp15, va, access, c->is_user_mode(), false);
    if (!tr.ok) {
        *out_pa = tr.fault_status;
        return -static_cast<int32_t>(tr.fault_status ? tr.fault_status : 1u);
    }
    *out_pa = static_cast<uint32_t>(tr.phys_addr);
    return 0;
}

FUJI_DYN_EXPORT int32_t fuji_dyn_translate64(FujiDynEngine* engine, uint32_t core, uint32_t va, uint32_t access, uint64_t* out_pa) {
    auto* c = get_core(engine, core);
    if (!c || !out_pa) return -1;
    auto tr = c->mmu.translate(engine->bus, c->cp15, va, access, c->is_user_mode(), false);
    if (!tr.ok) {
        *out_pa = tr.fault_status;
        return -static_cast<int32_t>(tr.fault_status ? tr.fault_status : 1u);
    }
    *out_pa = tr.phys_addr;
    return 0;
}

FUJI_DYN_EXPORT int32_t fuji_dyn_run_fast(FujiDynEngine* engine, uint32_t core,
                                          uint64_t max_ticks, uint64_t current_vtick,
                                          uint32_t irq_pending, FujiDynRunResult* out) {
    auto* c = get_core(engine, core);
    if (!c || !out) return -1;

    std::memset(out, 0, sizeof(FujiDynRunResult));
    engine->active_core.store(core, std::memory_order_release);
    if (current_vtick != engine->current_vtick.load(std::memory_order_acquire)) {
        engine->vtick_subticks = 0u;
    }
    engine->current_vtick.store(current_vtick, std::memory_order_release);

    c->run_budget = max_ticks;
    c->run_executed = 0;
    c->run_base_vtick = current_vtick;
    c->run_base_subticks = engine->vtick_subticks;
    c->run_translated_fetches = 0;
    c->run_fast_mmio_hits = 0;
    const uint32_t effective_irq = engine->gic_fastpath.is_active()
        ? (engine->gic_fastpath.core_has_pending(core) ? 1u : 0u)
        : irq_pending;
    c->store_irq_pending(effective_irq);
    if (c->deferred_stop_pending) {
        c->deferred_stop_pending = false;
        c->publish_live_state();
        out->executed_ticks = 0u;
        out->virtual_tick = current_vtick;
        out->stop_pc = c->jit->Regs()[15];
        out->stop_cpsr = c->jit->Cpsr();
        out->stop_reason = c->stop_reason;
        out->stop_insn = c->stop_insn;
        out->fault_addr = c->fault_addr;
        out->fault_status = c->fault_status;
        out->svc_imm = c->svc_imm;
        return 0;
    }
    c->stop_latched = false;
    c->dabt_latched = false;
    c->stop_reason = FUJI_DYN_STOP_QUANTUM;
    c->stop_insn = 0;
    c->fault_addr = 0;
    c->fault_status = 0;
    c->svc_imm = 0;
    c->mmu_ctrl_changed = false;
    c->process_pending_xcore_ops();

    /* Immediate unmasked IRQ check before executing any instruction */
    if (effective_irq != 0u && (c->jit->Cpsr() & CPSR_I_BIT) == 0u) {
        out->executed_ticks = 0;
        out->virtual_tick = current_vtick;
        out->stop_pc = c->jit->Regs()[15];
        out->stop_cpsr = c->jit->Cpsr();
        out->stop_reason = FUJI_DYN_STOP_IRQ;
        return 0;
    }

    if (max_ticks == 0u) {
        out->executed_ticks = 0;
        out->virtual_tick = current_vtick;
        out->stop_pc = c->jit->Regs()[15];
        out->stop_cpsr = c->jit->Cpsr();
        out->stop_reason = FUJI_DYN_STOP_QUANTUM;
        return 0;
    }

    /* Resume past a previously hit tracepoint by JIT single-stepping 1 instruction cleanly (#769) */
    if (c->resume_from_tracepoint) {
        const uint32_t cur_pc = c->jit->Regs()[15];
        c->resume_from_tracepoint = false;
        if (engine->has_tracepoints && cur_pc == c->resume_tp_pc && engine->tracepoints.count(cur_pc) != 0) {
            c->jit->ClearHalt(Dynarmic::HaltReason::UserDefined1 |
                              Dynarmic::HaltReason::UserDefined2 |
                              Dynarmic::HaltReason::MemoryAbort);
            c->bypass_tp_once = true;
            c->bypass_tp_pc = cur_pc;
            c->jit->InvalidateCacheRange(cur_pc, 4u);
            c->jit->Step();
            c->bypass_tp_once = false;
            c->jit->InvalidateCacheRange(cur_pc, 4u);
            if (c->stop_latched && c->stop_reason == FUJI_DYN_STOP_FAULT_DABT &&
                (engine->auto_excp_mask & FUJI_DYN_AUTO_EXCP_DABT) != 0u) {
                c->deliver_dabt_internal(c->fault_addr, c->fault_status);
                c->stop_latched = false;
                c->dabt_latched = false;
                c->stop_reason = FUJI_DYN_STOP_QUANTUM;
            }
        }
    }

    while (!c->stop_latched && c->run_executed < c->run_budget) {
        engine->fastmem.sync_for_core(core, engine->bus, c->cp15, c->is_user_mode(), engine->watched_code_pages);
        c->jit->ClearHalt(Dynarmic::HaltReason::UserDefined1 |
                          Dynarmic::HaltReason::UserDefined2 |
                          Dynarmic::HaltReason::MemoryAbort);
        Dynarmic::HaltReason hr = c->jit->Run();
        c->drain_pending_jit_inval();

        if (c->stop_latched) {
            if (c->stop_reason == FUJI_DYN_STOP_FAULT_DABT &&
                (engine->auto_excp_mask & FUJI_DYN_AUTO_EXCP_DABT) != 0u) {
                c->deliver_dabt_internal(c->fault_addr, c->fault_status);
                c->stop_latched = false;
                c->dabt_latched = false;
                c->stop_reason = FUJI_DYN_STOP_QUANTUM;
                continue;
            }
            break;
        }
        if (Dynarmic::Has(hr, Dynarmic::HaltReason::CacheInvalidation) ||
            Dynarmic::Has(hr, Dynarmic::HaltReason::UserDefined2)) {
            /* Cache invalidation (MMU/ICIALLU) or MMIO tick-flush yield: seamlessly resume */
            continue;
        }
        if (c->run_executed >= c->run_budget) {
            c->stop_reason = FUJI_DYN_STOP_QUANTUM;
            break;
        }
        if (hr != Dynarmic::HaltReason{}) {
            c->stop_reason = FUJI_DYN_STOP_HALT;
            break;
        }
    }

    c->publish_live_state();
    out->executed_ticks = c->run_executed;
    out->virtual_tick = engine->current_vtick.load(std::memory_order_acquire);
    out->stop_pc = c->jit->Regs()[15];
    out->stop_cpsr = c->jit->Cpsr();
    out->stop_reason = c->stop_latched ? c->stop_reason : static_cast<uint32_t>(FUJI_DYN_STOP_QUANTUM);
    out->stop_insn = c->stop_insn;
    out->fault_addr = c->fault_addr;
    out->fault_status = c->fault_status;
    out->svc_imm = c->svc_imm;
    out->translated_fetches = c->run_translated_fetches;
    out->fast_mmio_hits = c->run_fast_mmio_hits;
    return 0;
}

FUJI_DYN_EXPORT void fuji_dyn_halt_run(FujiDynEngine* engine) {
    if (!engine) return;
    engine->host_halt_requested.store(true, std::memory_order_release);
    const uint32_t cur = engine->active_core.load(std::memory_order_acquire);
    if (engine->mt_epoch_active.load(std::memory_order_acquire)) {
        engine->mt_halt_core.store(cur, std::memory_order_release);
        engine->mt_epoch_stop_requested.store(true, std::memory_order_release);
        for (uint32_t i = 0u; i < engine->num_cores; ++i) {
            if (engine->cores[i] && engine->cores[i]->jit) {
                engine->cores[i]->jit->HaltExecution(Dynarmic::HaltReason::UserDefined1);
            }
        }
        return;
    }
    if (auto* c = get_core(engine, cur)) {
        c->latch_stop(c->in_mmio_callback ? FUJI_DYN_STOP_MMIO_HALT : FUJI_DYN_STOP_HALT);
    }
}

FUJI_DYN_EXPORT void fuji_dyn_set_irq_pending(FujiDynEngine* engine, uint32_t core, uint32_t pending) {
    auto* c = get_core(engine, core);
    if (!c) return;
    c->store_irq_pending(pending);
    if (engine->mt_epoch_active.load(std::memory_order_acquire)) {
        if (pending != 0u) {
            c->pending_xcore_ops.fetch_or(CoreState::XCORE_IRQ_CHECK, std::memory_order_acq_rel);
            if (c->jit) c->jit->HaltExecution(Dynarmic::HaltReason::UserDefined2);
        }
        return;
    }
    if (pending != 0u && c->jit->IsExecuting() && (c->jit->Cpsr() & CPSR_I_BIT) == 0u) {
        c->latch_stop(FUJI_DYN_STOP_IRQ);
    }
}

FUJI_DYN_EXPORT int32_t fuji_dyn_deliver_irq(FujiDynEngine* engine, uint32_t core) {
    auto* c = get_core(engine, core);
    return c ? c->deliver_irq_internal() : -1;
}

FUJI_DYN_EXPORT int32_t fuji_dyn_deliver_fiq(FujiDynEngine* engine, uint32_t core) {
    auto* c = get_core(engine, core);
    return c ? c->deliver_fiq_internal() : -1;
}

FUJI_DYN_EXPORT int32_t fuji_dyn_deliver_svc(FujiDynEngine* engine, uint32_t core) {
    auto* c = get_core(engine, core);
    return c ? c->deliver_svc_internal() : -1;
}

FUJI_DYN_EXPORT int32_t fuji_dyn_deliver_undef(FujiDynEngine* engine, uint32_t core) {
    auto* c = get_core(engine, core);
    return c ? c->deliver_undef_internal() : -1;
}

FUJI_DYN_EXPORT int32_t fuji_dyn_deliver_pabt(FujiDynEngine* engine, uint32_t core, uint32_t far, uint32_t fsr) {
    auto* c = get_core(engine, core);
    return c ? c->deliver_pabt_internal(far, fsr) : -1;
}

FUJI_DYN_EXPORT int32_t fuji_dyn_deliver_dabt(FujiDynEngine* engine, uint32_t core, uint32_t far, uint32_t fsr) {
    auto* c = get_core(engine, core);
    return c ? c->deliver_dabt_internal(far, fsr) : -1;
}

FUJI_DYN_EXPORT void fuji_dyn_set_auto_exceptions(FujiDynEngine* engine, uint32_t enable_mask) {
    if (engine) engine->auto_excp_mask = enable_mask;
}

FUJI_DYN_EXPORT void fuji_dyn_set_wfe_event_mask(FujiDynEngine* engine, uint32_t mask) {
    if (engine) engine->wfe_event_mask.store(mask & 0xFu, std::memory_order_release);
}

FUJI_DYN_EXPORT uint32_t fuji_dyn_get_wfe_event_mask(FujiDynEngine* engine) {
    return engine ? (engine->wfe_event_mask.load(std::memory_order_acquire) & 0xFu) : 0u;
}

FUJI_DYN_EXPORT void fuji_dyn_set_tracepoints(FujiDynEngine* engine, const uint32_t* pcs, uint32_t count) {
    if (!engine) return;
    engine->tracepoints.clear();
    if (pcs && count > 0) {
        for (uint32_t i = 0; i < count; ++i) {
            engine->tracepoints.insert(pcs[i]);
        }
    }
    engine->has_tracepoints = !engine->tracepoints.empty();
    for (auto& c : engine->cores) {
        if (c && c->jit) c->jit->ClearCache();
    }
}

FUJI_DYN_EXPORT void fuji_dyn_set_cntfrq(FujiDynEngine* engine, uint32_t freq_hz) {
    if (!engine) return;
    for (auto& c : engine->cores) {
        if (c) c->cp15.cntfrq = freq_hz;
    }
}

FUJI_DYN_EXPORT void fuji_dyn_set_vtick_divider(FujiDynEngine* engine, uint32_t divider) {
    if (!engine) return;
    const uint32_t d = (divider < 1u) ? 1u : divider;
    engine->vtick_divider = d;
    engine->vtick_subticks = 0u;
    if (d > 1u && (d & (d - 1u)) == 0u) {
        uint32_t sh = 0u;
        uint32_t tmp = d;
        while (tmp > 1u) { tmp >>= 1u; ++sh; }
        engine->vtick_shift = sh;
        engine->vtick_mask = d - 1u;
    } else {
        engine->vtick_shift = 0u;
        engine->vtick_mask = 0u;
    }
}

FUJI_DYN_EXPORT void fuji_dyn_clear_exclusive(FujiDynEngine* engine, uint32_t core) {
    if (!engine) return;
    if (core == 0xFFFFFFFFu) {
        engine->exclusive_monitor->Clear();
        for (auto& c : engine->cores) {
            if (c && c->jit) c->jit->ClearExclusiveState();
        }
    } else if (auto* c = get_core(engine, core)) {
        engine->exclusive_monitor->ClearProcessor(core);
        c->jit->ClearExclusiveState();
    }
}

/* ---- Register & CP15 accessors ---- */

FUJI_DYN_EXPORT uint32_t fuji_dyn_get_reg(FujiDynEngine* engine, uint32_t core, uint32_t reg) {
    auto* c = get_core(engine, core);
    if (!c || reg >= 16u) return 0u;
    return c->jit->Regs()[reg];
}

FUJI_DYN_EXPORT void fuji_dyn_set_reg(FujiDynEngine* engine, uint32_t core, uint32_t reg, uint32_t value) {
    auto* c = get_core(engine, core);
    if (!c || reg >= 16u) return;
    c->jit->Regs()[reg] = value;
}

FUJI_DYN_EXPORT void fuji_dyn_get_regs(FujiDynEngine* engine, uint32_t core, uint32_t out_regs[16]) {
    auto* c = get_core(engine, core);
    if (!c || !out_regs) return;
    const auto& r = c->jit->Regs();
    std::memcpy(out_regs, r.data(), 16u * sizeof(uint32_t));
}

FUJI_DYN_EXPORT void fuji_dyn_set_regs(FujiDynEngine* engine, uint32_t core, const uint32_t in_regs[16]) {
    auto* c = get_core(engine, core);
    if (!c || !in_regs) return;
    auto& r = c->jit->Regs();
    std::memcpy(r.data(), in_regs, 16u * sizeof(uint32_t));
}

FUJI_DYN_EXPORT uint32_t fuji_dyn_get_cpsr(FujiDynEngine* engine, uint32_t core) {
    auto* c = get_core(engine, core);
    return c ? c->jit->Cpsr() : 0u;
}

FUJI_DYN_EXPORT void fuji_dyn_set_cpsr(FujiDynEngine* engine, uint32_t core, uint32_t value) {
    auto* c = get_core(engine, core);
    if (!c) return;
    uint32_t old_cpsr = c->jit->Cpsr();
    uint32_t old_mode = old_cpsr & CPSR_M_MASK;
    uint32_t new_mode = value & CPSR_M_MASK;
    if (is_valid_arm_mode(new_mode) && new_mode != old_mode) {
        c->banked.switch_mode(old_mode, new_mode, c->jit->Regs());
        if ((old_mode == FUJI_DYN_MODE_USR) != (new_mode == FUJI_DYN_MODE_USR)) {
            c->mmu.flush();
            engine->fastmem.invalidate_core(core);
        }
    }
    c->jit->SetCpsr(value);
    c->publish_live_state();
}

FUJI_DYN_EXPORT uint32_t fuji_dyn_get_spsr(FujiDynEngine* engine, uint32_t core) {
    auto* c = get_core(engine, core);
    if (!c) return 0u;
    return c->banked.get_spsr(c->jit->Cpsr() & CPSR_M_MASK);
}

FUJI_DYN_EXPORT void fuji_dyn_set_spsr(FujiDynEngine* engine, uint32_t core, uint32_t value) {
    auto* c = get_core(engine, core);
    if (!c) return;
    c->banked.set_spsr(c->jit->Cpsr() & CPSR_M_MASK, value);
}

FUJI_DYN_EXPORT uint32_t fuji_dyn_get_banked_reg(FujiDynEngine* engine, uint32_t core, uint32_t mode, uint32_t reg) {
    auto* c = get_core(engine, core);
    if (!c) return 0u;
    return c->banked.get_banked(c->jit->Cpsr() & CPSR_M_MASK, c->jit->Regs(), mode, reg);
}

FUJI_DYN_EXPORT void fuji_dyn_set_banked_reg(FujiDynEngine* engine, uint32_t core, uint32_t mode, uint32_t reg, uint32_t value) {
    auto* c = get_core(engine, core);
    if (!c) return;
    c->banked.set_banked(c->jit->Cpsr() & CPSR_M_MASK, c->jit->Regs(), mode, reg, value);
}

FUJI_DYN_EXPORT uint32_t fuji_dyn_get_cp15(FujiDynEngine* engine, uint32_t core, uint32_t opc1, uint32_t crn, uint32_t crm, uint32_t opc2) {
    auto* c = get_core(engine, core);
    if (!c) return 0u;
    Cp15HostHooks hooks;
    hooks.get_virtual_tick = [engine]() -> uint64_t { return engine->current_vtick.load(std::memory_order_acquire); };
    return cp15_read_reg(c->cp15, hooks, opc1, crn, crm, opc2);
}

FUJI_DYN_EXPORT void fuji_dyn_set_cp15(FujiDynEngine* engine, uint32_t core, uint32_t opc1, uint32_t crn, uint32_t crm, uint32_t opc2, uint32_t value) {
    auto* c = get_core(engine, core);
    if (!c) return;
    Cp15HostHooks hooks;
    hooks.on_tlb_flush = [engine, c](bool all_cores) {
        if (all_cores) {
            for (auto& cc : engine->cores) if (cc) cc->mmu.flush();
        } else {
            c->mmu.flush();
        }
    };
    hooks.on_icache_invalidate = [engine, c](bool all_cores) {
        if (all_cores) {
            for (auto& cc : engine->cores) if (cc && cc->jit) cc->jit->ClearCache();
        } else if (c->jit) {
            c->jit->ClearCache();
        }
    };
    hooks.get_virtual_tick = [engine]() -> uint64_t { return engine->current_vtick.load(std::memory_order_acquire); };
    cp15_write_reg(c->cp15, hooks, opc1, crn, crm, opc2, value, false);
    engine->fastmem.invalidate_core(core);
}

FUJI_DYN_EXPORT uint64_t fuji_dyn_get_cp15_64(FujiDynEngine* engine, uint32_t core, uint32_t opc1, uint32_t crm) {
    auto* c = get_core(engine, core);
    return c ? c->cp15_coproc->read_64(opc1, crm) : 0u;
}

FUJI_DYN_EXPORT void fuji_dyn_set_cp15_64(FujiDynEngine* engine, uint32_t core, uint32_t opc1, uint32_t crm, uint64_t value) {
    if (auto* c = get_core(engine, core)) {
        c->cp15_coproc->write_64(opc1, crm, value);
    }
}

FUJI_DYN_EXPORT uint32_t fuji_dyn_get_ext_reg(FujiDynEngine* engine, uint32_t core, uint32_t index) {
    auto* c = get_core(engine, core);
    if (!c) return 0u;
    if (index < 64u) return c->jit->ExtRegs()[index];
    if (index == FUJI_DYN_EXT_FPSCR) return c->jit->Fpscr();
    if (index == FUJI_DYN_EXT_FPEXC) return c->cp15.fpexc;
    return 0u;
}

FUJI_DYN_EXPORT void fuji_dyn_set_ext_reg(FujiDynEngine* engine, uint32_t core, uint32_t index, uint32_t value) {
    auto* c = get_core(engine, core);
    if (!c) return;
    if (index < 64u) {
        c->jit->ExtRegs()[index] = value;
    } else if (index == FUJI_DYN_EXT_FPSCR) {
        c->jit->SetFpscr(value);
    } else if (index == FUJI_DYN_EXT_FPEXC) {
        c->cp15.fpexc = value;
    }
}

FUJI_DYN_EXPORT uint64_t fuji_dyn_get_dreg(FujiDynEngine* engine, uint32_t core, uint32_t d_index) {
    auto* c = get_core(engine, core);
    if (!c || d_index >= 32u) return 0u;
    const auto& ext = c->jit->ExtRegs();
    return static_cast<uint64_t>(ext[d_index * 2u]) | (static_cast<uint64_t>(ext[d_index * 2u + 1u]) << 32);
}

FUJI_DYN_EXPORT void fuji_dyn_set_dreg(FujiDynEngine* engine, uint32_t core, uint32_t d_index, uint64_t value) {
    auto* c = get_core(engine, core);
    if (!c || d_index >= 32u) return;
    auto& ext = c->jit->ExtRegs();
    ext[d_index * 2u] = static_cast<uint32_t>(value);
    ext[d_index * 2u + 1u] = static_cast<uint32_t>(value >> 32);
}

FUJI_DYN_EXPORT uint32_t fuji_dyn_get_fpscr(FujiDynEngine* engine, uint32_t core) {
    auto* c = get_core(engine, core);
    return c ? c->jit->Fpscr() : 0u;
}

FUJI_DYN_EXPORT void fuji_dyn_set_fpscr(FujiDynEngine* engine, uint32_t core, uint32_t value) {
    if (auto* c = get_core(engine, core)) c->jit->SetFpscr(value);
}

FUJI_DYN_EXPORT uint32_t fuji_dyn_get_fpexc(FujiDynEngine* engine, uint32_t core) {
    auto* c = get_core(engine, core);
    return c ? c->cp15.fpexc : 0u;
}

FUJI_DYN_EXPORT void fuji_dyn_set_fpexc(FujiDynEngine* engine, uint32_t core, uint32_t value) {
    if (auto* c = get_core(engine, core)) c->cp15.fpexc = value;
}

FUJI_DYN_EXPORT uint32_t fuji_dyn_get_current_pc(FujiDynEngine* engine, uint32_t core) {
    auto* c = get_core(engine, core);
    return c ? c->jit->Regs()[15] : 0u;
}

FUJI_DYN_EXPORT uint64_t fuji_dyn_get_current_vtick(FujiDynEngine* engine) {
    return engine ? engine->live_vtick() : 0u;
}

/* ---- Native C++ MMIO Read Fastpaths ---- */

FUJI_DYN_EXPORT void fuji_dyn_set_timer_shadow(FujiDynEngine* engine, uint32_t phys_base, const FujiDynTimerShadow* shadow) {
    if (!engine) return;
    if (!shadow) {
        engine->timer_bank.disable();
        return;
    }
    engine->timer_bank.configure(phys_base, shadow->active != 0u);
    for (uint32_t i = 0; i < FUJI_DYN_TIMER_CHANNELS; ++i) {
        TimerShadowChannel ch;
        ch.stored = shadow->channels[i].stored;
        ch.reload = shadow->channels[i].reload;
        ch.deadline = shadow->channels[i].deadline;
        ch.enabled = shadow->channels[i].enabled;
        ch.has_deadline = shadow->channels[i].has_deadline;
        engine->timer_bank.set_channel(i, ch);
    }
}

FUJI_DYN_EXPORT void fuji_dyn_set_timer_shadow_ptr(FujiDynEngine* engine, uint32_t phys_base, const FujiDynTimerShadow* shared_shadow) {
    if (!engine) return;
    engine->shared_timer_base = phys_base;
    engine->shared_timer_shadow = shared_shadow;
}

FUJI_DYN_EXPORT void fuji_dyn_clear_timer_shadow(FujiDynEngine* engine) {
    if (!engine) return;
    engine->timer_bank.reset();
    engine->shared_timer_base = 0u;
    engine->shared_timer_shadow = nullptr;
}

FUJI_DYN_EXPORT int32_t fuji_dyn_set_ro_mmio_word(FujiDynEngine* engine, uint32_t phys_addr, uint32_t value) {
    if (!engine) return -1;
    if (engine->mt_epoch_active.load(std::memory_order_acquire)) {
        std::lock_guard<std::mutex> lk(engine->ro_mmio_lock);
        return engine->ro_mmio.insert(phys_addr, value);
    }
    return engine->ro_mmio.insert(phys_addr, value);
}

FUJI_DYN_EXPORT int32_t fuji_dyn_set_ro_mmio_batch(FujiDynEngine* engine, const uint32_t* addrs, const uint32_t* vals, uint32_t count) {
    if (!engine || (!addrs && count > 0u) || (!vals && count > 0u)) return -1;
    if (engine->mt_epoch_active.load(std::memory_order_acquire)) {
        std::lock_guard<std::mutex> lk(engine->ro_mmio_lock);
        int32_t inserted = 0;
        for (uint32_t i = 0; i < count; ++i) {
            if (engine->ro_mmio.insert(addrs[i], vals[i]) == 0) ++inserted;
        }
        return inserted;
    }
    int32_t inserted = 0;
    for (uint32_t i = 0; i < count; ++i) {
        if (engine->ro_mmio.insert(addrs[i], vals[i]) == 0) ++inserted;
    }
    return inserted;
}

FUJI_DYN_EXPORT void fuji_dyn_remove_ro_mmio_word(FujiDynEngine* engine, uint32_t phys_addr) {
    if (!engine) return;
    if (engine->mt_epoch_active.load(std::memory_order_acquire)) {
        std::lock_guard<std::mutex> lk(engine->ro_mmio_lock);
        engine->ro_mmio.remove(phys_addr);
        return;
    }
    engine->ro_mmio.remove(phys_addr);
}

FUJI_DYN_EXPORT void fuji_dyn_clear_ro_mmio_range(FujiDynEngine* engine, uint32_t phys_base, uint32_t size) {
    if (!engine) return;
    if (engine->mt_epoch_active.load(std::memory_order_acquire)) {
        std::lock_guard<std::mutex> lk(engine->ro_mmio_lock);
        engine->ro_mmio.clear_range(phys_base, size);
        return;
    }
    engine->ro_mmio.clear_range(phys_base, size);
}

FUJI_DYN_EXPORT void fuji_dyn_clear_ro_mmio(FujiDynEngine* engine) {
    if (!engine) return;
    if (engine->mt_epoch_active.load(std::memory_order_acquire)) {
        std::lock_guard<std::mutex> lk(engine->ro_mmio_lock);
        engine->ro_mmio.clear_all();
        return;
    }
    engine->ro_mmio.clear_all();
}

FUJI_DYN_EXPORT int32_t fuji_dyn_add_ro_mmio_range(FujiDynEngine* engine, uint32_t phys_base, uint32_t size, const uint8_t* host_ptr) {
    if (!engine || size == 0u) return -1;
    std::unique_lock<std::mutex> lk(engine->ro_mmio_lock, std::defer_lock);
    if (engine->mt_epoch_active.load(std::memory_order_acquire)) lk.lock();
    for (uint32_t i = 0; i < engine->ro_range_count; ++i) {
        if (engine->ro_ranges[i].phys_base == phys_base && engine->ro_ranges[i].size == size) {
            engine->ro_ranges[i].host_ptr = host_ptr;
            return 0;
        }
    }
    if (engine->ro_range_count >= FUJI_DYN_RO_MMIO_RANGES) return -2;
    engine->ro_ranges[engine->ro_range_count++] = RoMmioRange{phys_base, size, host_ptr};
    engine->recompute_ro_range_bounds();
    return 0;
}

FUJI_DYN_EXPORT void fuji_dyn_clear_ro_mmio_ranges(FujiDynEngine* engine) {
    if (!engine) return;
    std::unique_lock<std::mutex> lk(engine->ro_mmio_lock, std::defer_lock);
    if (engine->mt_epoch_active.load(std::memory_order_acquire)) lk.lock();
    engine->ro_range_count = 0u;
    engine->ro_range_min_addr = 0xFFFFFFFFu;
    engine->ro_range_max_end = 0u;
}

FUJI_DYN_EXPORT void fuji_dyn_set_live_state_buf(FujiDynEngine* engine, FujiDynLiveState* buf) {
    if (!engine) return;
    engine->live_state = buf;
    if (buf) {
        std::memset(buf, 0, sizeof(FujiDynLiveState));
        const uint32_t act = engine->active_core.load(std::memory_order_acquire);
        buf->current_vtick = engine->current_vtick.load(std::memory_order_acquire);
        buf->active_core = act;
        for (uint32_t i = 0; i < engine->num_cores; ++i) {
            if (engine->cores[i]) {
                buf->cpsr_by_core[i] = engine->cores[i]->jit->Cpsr();
                buf->valid_mask |= (1u << i);
            }
        }
        if (auto* c = get_core(engine, act)) {
            buf->current_pc = c->jit->Regs()[15];
            buf->current_cpsr = c->jit->Cpsr();
        }
    }
}

FUJI_DYN_EXPORT int32_t fuji_dyn_run_cluster_fast(FujiDynEngine* engine, const FujiDynClusterRequest* req, FujiDynClusterResult* out) {
    if (!engine || !req || !out) return -1;
    std::memset(out, 0, sizeof(FujiDynClusterResult));
    const uint32_t ncores = engine->num_cores;
    const uint32_t valid_cores_mask = (1u << ncores) - 1u;
    uint32_t runnable = req->runnable_mask & valid_cores_mask;
    const uint32_t init_irq_mask = req->irq_pending_mask & valid_cores_mask;
    if (!engine->gic_fastpath.is_active()) {
        for (uint32_t i = 0; i < ncores; ++i) {
            if (engine->cores[i]) {
                engine->cores[i]->store_irq_pending(((init_irq_mask & (1u << i)) != 0u) ? 1u : 0u);
            }
        }
    }
    const uint32_t quantum = req->quantum ? req->quantum : 1024u;
    uint32_t core = (req->start_core < ncores) ? req->start_core : 0u;
    uint32_t used = (req->start_used < quantum) ? req->start_used : 0u;
    uint64_t vtick = req->start_vtick;
    if (vtick != engine->current_vtick.load(std::memory_order_acquire)) {
        engine->current_vtick.store(vtick, std::memory_order_release);
        engine->vtick_subticks = 0u;
    }
    uint64_t remaining_total = req->max_total_ticks;

    engine->in_cluster_fast.store(true, std::memory_order_release);
    engine->host_halt_requested.store(false, std::memory_order_release);
    engine->cluster_runnable_mask.store(runnable, std::memory_order_release);
    engine->cluster_wfe_waiting_mask.store((req->wfe_waiting_mask & valid_cores_mask) & ~runnable, std::memory_order_release);
    engine->cluster_wfi_waiting_mask.store(((req->wfe_waiting_mask >> 4) & valid_cores_mask) & ~runnable, std::memory_order_release);
    engine->cluster_sev_seen_mask.store(0u, std::memory_order_release);
    engine->cluster_irq_woken_mask.store(0u, std::memory_order_release);
    struct ClusterGuard {
        FujiDynEngine* eng;
        FujiDynClusterResult* res;
        ~ClusterGuard() {
            res->sev_mask |= eng->cluster_sev_seen_mask.load(std::memory_order_acquire);
            eng->mt_epoch_active.store(false, std::memory_order_release);
            eng->mt_epoch_mask.store(0u, std::memory_order_release);
            eng->in_cluster_fast.store(false, std::memory_order_release);
            eng->host_halt_requested.store(false, std::memory_order_release);
            eng->cluster_runnable_mask.store(0u, std::memory_order_release);
            eng->cluster_wfe_waiting_mask.store(0u, std::memory_order_release);
            eng->cluster_wfi_waiting_mask.store(0u, std::memory_order_release);
            eng->cluster_sev_seen_mask.store(0u, std::memory_order_release);
            eng->cluster_irq_woken_mask.store(0u, std::memory_order_release);
        }
    } cluster_guard{engine, out};

    out->virtual_tick = vtick;
    out->active_core = core;
    out->active_used = used;

    auto pick_next = [&](uint32_t cur) -> int32_t {
        for (uint32_t d = 1; d <= ncores; ++d) {
            uint32_t cand = (cur + d) % ncores;
            if ((runnable & (1u << cand)) != 0u) return static_cast<int32_t>(cand);
        }
        return -1;
    };

    auto apply_inline_irq_wakeups = [&]() -> uint32_t {
        if (!engine->gic_fastpath.is_active()) return 0u;
        uint32_t woken_any = 0u;
        uint32_t wfi_wait = engine->cluster_wfi_waiting_mask.load(std::memory_order_acquire);
        uint32_t wfe_wait = engine->cluster_wfe_waiting_mask.load(std::memory_order_acquire);
        for (uint32_t i = 0; i < ncores; ++i) {
            const uint32_t bit = 1u << i;
            const bool pend = engine->gic_fastpath.core_has_pending(i);
            if (engine->cores[i]) engine->cores[i]->store_irq_pending(pend ? 1u : 0u);
            if (pend && ((wfi_wait | wfe_wait) & bit) != 0u) {
                runnable |= bit;
                engine->cluster_runnable_mask.store(runnable, std::memory_order_release);
                if ((wfi_wait & bit) != 0u) {
                    wfi_wait &= ~bit;
                    engine->cluster_wfi_waiting_mask.store(wfi_wait, std::memory_order_release);
                    out->wfi_entered_mask &= ~bit;
                    out->wfi_woken_mask |= bit;
                }
                if ((wfe_wait & bit) != 0u) {
                    wfe_wait &= ~bit;
                    engine->cluster_wfe_waiting_mask.store(wfe_wait, std::memory_order_release);
                    out->wfe_entered_mask &= ~bit;
                }
                woken_any |= bit;
            }
        }
        engine->cluster_irq_woken_mask.store(0u, std::memory_order_release);
        return woken_any;
    };

    uint32_t wfi_skips_this_call = 0u;
    auto try_inline_wfi_fastforward = [&]() -> bool {
        if (wfi_skips_this_call >= 8u || !engine->inline_timer_irq_active()) return false;
        if ((engine->timer_irq_shadow->active & 2u) == 0u) return false;
        const uint64_t next_dl = engine->earliest_inline_timer_deadline();
        if (next_dl == std::numeric_limits<uint64_t>::max() || next_dl <= vtick) return false;
        if (next_dl > engine->timer_irq_shadow->next_other_deadline) return false;
        vtick = next_dl;
        out->virtual_tick = vtick;
        engine->current_vtick.store(vtick, std::memory_order_release);
        engine->vtick_subticks = 0u;
        ++engine->timer_irq_shadow->inline_wfi_skips;
        ++wfi_skips_this_call;
        const int32_t fired = engine->try_fire_inline_timers(vtick);
        if (fired < 0) return false;
        return apply_inline_irq_wakeups() != 0u;
    };

    if (runnable == 0u) {
        if (!try_inline_wfi_fastforward()) {
            out->stop_reason = FUJI_DYN_STOP_ALL_WAITING;
            return 0;
        }
    }

    if ((runnable & (1u << core)) == 0u) {
        int32_t nxt = pick_next(core);
        if (nxt < 0) {
            out->stop_reason = FUJI_DYN_STOP_ALL_WAITING;
            return 0;
        }
        core = static_cast<uint32_t>(nxt);
        used = 0u;
        ++out->switches;
    }

    const bool mt_enabled = engine->smp_host_threads.load(std::memory_order_acquire) && ncores > 1u;
    if (mt_enabled) {
        engine->ensure_mt_workers_started();
    }

    while (remaining_total > 0u && runnable != 0u) {
        /* Inline soc-timer comparator check before each quantum / epoch */
        if (engine->inline_timer_irq_active()) {
            const int32_t fired = engine->try_fire_inline_timers(vtick);
            if (fired < 0) {
                out->stop_reason = FUJI_DYN_STOP_QUANTUM;
                return 0;
            }
            if (fired > 0) {
                apply_inline_irq_wakeups();
            }
        }

        const bool single_runnable = (runnable & (runnable - 1u)) == 0u;
        const uint32_t active_count = static_cast<uint32_t>(std::popcount(runnable));

        /* ============================================================================
         * Multi-Core Host-Threaded Parallel Epoch Path (active_count >= 2 && mt_enabled)
         * Single-active-core execution bypasses this completely with zero overhead!
         * ============================================================================ */
        uint64_t mt_total_cap = remaining_total;
        if (mt_enabled && active_count >= 2u && engine->inline_timer_irq_active()) {
            uint64_t next_dl = engine->earliest_inline_timer_deadline();
            const uint64_t other_dl = engine->timer_irq_shadow->next_other_deadline;
            if (other_dl < next_dl) next_dl = other_dl;
            if (next_dl != std::numeric_limits<uint64_t>::max() && next_dl > vtick) {
                const uint64_t until_dl = next_dl - vtick;
                const uint64_t div = engine->vtick_divider;
                const uint64_t until_insns = (div <= 1u)
                    ? until_dl
                    : (until_dl > (std::numeric_limits<uint64_t>::max() / div)
                        ? std::numeric_limits<uint64_t>::max()
                        : (until_dl * div - engine->vtick_subticks));
                if (until_insns < mt_total_cap) mt_total_cap = until_insns;
            }
        }
        if (mt_enabled && active_count >= 2u && mt_total_cap >= static_cast<uint64_t>(active_count) * 128u) {
            const uint32_t epoch_mask = runnable;
            const uint64_t mt_epoch_cap = static_cast<uint64_t>(quantum) * 4u;
            const uint64_t overshoot_margin = static_cast<uint64_t>(active_count) * 32u;
            const uint64_t safe_total_cap = (mt_total_cap > overshoot_margin)
                ? (mt_total_cap - overshoot_margin)
                : mt_total_cap;
            uint64_t epoch_budget = std::min<uint64_t>(
                mt_epoch_cap,
                safe_total_cap / static_cast<uint64_t>(active_count));
            if (epoch_budget == 0u) epoch_budget = 1u;

            /* Sync IRQ lines & Fastmem views on owner thread while workers are quiescent */
            for (uint32_t i = 0u; i < ncores; ++i) {
                if ((epoch_mask & (1u << i)) == 0u || !engine->cores[i]) continue;
                CoreState* c = engine->cores[i].get();
                c->mt_exec_atomic.store(0u, std::memory_order_release);
                if (engine->gic_fastpath.is_active()) {
                    c->store_irq_pending(engine->gic_fastpath.core_has_pending(i) ? 1u : 0u);
                }
                c->process_pending_xcore_ops();
                c->sync_fastmem_safe(false);
            }

            const uint64_t epoch_start_vtick = vtick;
            const uint32_t epoch_start_subticks = engine->vtick_subticks;
            const uint32_t worker_mask = epoch_mask & ~1u;
            const uint32_t worker_count = static_cast<uint32_t>(std::popcount(worker_mask));

            engine->mt_epoch_stop_requested.store(false, std::memory_order_release);
            engine->mt_halt_core.store(0xFFFFFFFFu, std::memory_order_release);
            engine->mt_mmio_pending_mask.store(0u, std::memory_order_release);
            engine->mt_epoch_budget = epoch_budget;
            engine->mt_epoch_total_cap = safe_total_cap;
            engine->mt_epoch_total_exec.store(0u, std::memory_order_release);
            engine->mt_epoch_base_vtick = epoch_start_vtick;
            engine->mt_epoch_base_subticks = epoch_start_subticks;
            engine->mt_epoch_mask.store(epoch_mask, std::memory_order_release);
            engine->mt_workers_remaining.store(worker_count, std::memory_order_release);
            engine->mt_epoch_workers_entered.store(0u, std::memory_order_release);
            engine->mt_epoch_active.store(true, std::memory_order_release);

            for (uint32_t i = 1u; i < ncores; ++i) {
                if ((worker_mask & (1u << i)) != 0u) {
                    engine->mt_worker_gen[i].fetch_add(1u, std::memory_order_seq_cst);
                }
            }
            const uint32_t need_wake = engine->mt_workers_parked_mask.load(std::memory_order_seq_cst) & worker_mask;
            if (need_wake != 0u) {
                std::lock_guard<std::mutex> lk(engine->mt_pool_mutex);
                for (uint32_t i = 1u; i < ncores; ++i) {
                    if ((need_wake & (1u << i)) != 0u) {
                        engine->mt_worker_wake_cv[i].notify_one();
                    }
                }
            }

            /* Start barrier: do not let core 0 run ahead while worker threads are still being woken
             * from their condvar park. Without it core 0 executes its whole slice (or hits an IRQ /
             * MMIO halt that ends the epoch) before parked workers even start, so over IRQ-dense
             * boot phases the secondary cores fall far behind core 0 in guest progress -- an
             * inter-core skew real lock-step silicon never exhibits (firmware init ordering race). */
            if (worker_count != 0u) {
                for (uint32_t spin = 0u;
                     engine->mt_epoch_workers_entered.load(std::memory_order_acquire) < worker_count; ++spin) {
                    if (spin < 4096u) {
                        fuji_cpu_pause();
                    } else {
                        std::this_thread::yield();
                    }
                }
            }

            /* Core 0 runs directly on owner thread so its MMIO upcalls have zero handoff latency */
            if ((epoch_mask & 1u) != 0u && engine->cores[0]) {
                engine->active_core.store(0u, std::memory_order_release);
                execute_core_mt_epoch(engine, engine->cores[0].get(), epoch_budget,
                                      epoch_start_vtick, epoch_start_subticks);
            }

            /* Wait for worker cores 1..N-1 while serving all proxied MMIO upcalls on owner thread */
            for (;;) {
                if (engine->mt_mmio_pending_mask.load(std::memory_order_acquire) != 0u) {
                    engine->drain_worker_mmio_requests();
                }
                if (engine->mt_workers_remaining.load(std::memory_order_acquire) == 0u) {
                    break;
                }
                bool woke = false;
                for (uint32_t spin = 0u; spin < 2000u; ++spin) {
                    if (engine->mt_mmio_pending_mask.load(std::memory_order_acquire) != 0u ||
                        engine->mt_workers_remaining.load(std::memory_order_acquire) == 0u) {
                        woke = true;
                        break;
                    }
                    fuji_cpu_pause();
                }
                if (woke) continue;
                std::unique_lock<std::mutex> lk(engine->mt_pool_mutex);
                engine->mt_owner_parked.store(1u, std::memory_order_seq_cst);
                engine->mt_owner_cv.wait(lk, [&]() {
                    return engine->mt_workers_remaining.load(std::memory_order_seq_cst) == 0u ||
                           engine->mt_mmio_pending_mask.load(std::memory_order_seq_cst) != 0u;
                });
                engine->mt_owner_parked.store(0u, std::memory_order_relaxed);
            }
            engine->drain_worker_mmio_requests();
            engine->mt_epoch_active.store(false, std::memory_order_release);
            engine->mt_epoch_mask.store(0u, std::memory_order_release);

            /* All workers are now quiescent: flush any deferred cross-core TLB/ICache ops on all cores */
            for (uint32_t i = 0u; i < ncores; ++i) {
                if (engine->cores[i]) {
                    engine->cores[i]->process_pending_xcore_ops();
                }
            }

            /* Aggregate executed instructions & advance virtual clock with exact sum_exec/vtick_divider
             * parity to single-threaded round-robin (preserves calibrated firmware timer/I2C/BUG-29 timing). */
            uint64_t sum_exec = 0u;
            for (uint32_t i = 0u; i < ncores; ++i) {
                if ((epoch_mask & (1u << i)) == 0u || !engine->cores[i]) continue;
                const CoreState* c = engine->cores[i].get();
                sum_exec += c->run_executed;
                out->core_executed[i] += c->run_executed;
                out->translated_fetches += c->run_translated_fetches;
                out->fast_mmio_hits += c->run_fast_mmio_hits;
            }
            out->total_executed += sum_exec;
            remaining_total = (sum_exec >= remaining_total) ? 0u : (remaining_total - sum_exec);

            const uint32_t div = engine->vtick_divider;
            uint64_t next_vtick = epoch_start_vtick;
            if (div <= 1u) {
                next_vtick = epoch_start_vtick + sum_exec;
                engine->vtick_subticks = 0u;
            } else if (engine->vtick_shift != 0u) {
                const uint64_t tot = static_cast<uint64_t>(epoch_start_subticks) + sum_exec;
                next_vtick = epoch_start_vtick + (tot >> engine->vtick_shift);
                engine->vtick_subticks = static_cast<uint32_t>(tot & engine->vtick_mask);
            } else {
                const uint64_t tot = static_cast<uint64_t>(epoch_start_subticks) + sum_exec;
                next_vtick = epoch_start_vtick + (tot / div);
                engine->vtick_subticks = static_cast<uint32_t>(tot % div);
            }
            const uint64_t observed_vtick = engine->current_vtick.load(std::memory_order_acquire);
            if (observed_vtick > next_vtick) next_vtick = observed_vtick;
            vtick = next_vtick;
            engine->current_vtick.store(vtick, std::memory_order_release);
            out->virtual_tick = vtick;

            /* Drain inline GIC IRQ wakeups & SEV wakeups before evaluating WFI/WFE sleeps */
            if (engine->cluster_irq_woken_mask.load(std::memory_order_acquire) != 0u) {
                apply_inline_irq_wakeups();
            }
            const uint32_t sev_seen = engine->cluster_sev_seen_mask.exchange(0u, std::memory_order_acq_rel);
            if (sev_seen != 0u) {
                out->sev_mask |= sev_seen;
                const uint32_t wfe_wait = engine->cluster_wfe_waiting_mask.load(std::memory_order_acquire);
                const uint32_t woken = wfe_wait & ~((std::popcount(sev_seen) == 1) ? sev_seen : 0u);
                if (woken != 0u) {
                    runnable |= woken;
                    engine->cluster_runnable_mask.store(runnable, std::memory_order_release);
                    out->wfe_entered_mask &= ~woken;
                    engine->cluster_wfe_waiting_mask.store(wfe_wait & ~woken, std::memory_order_release);
                    engine->wfe_event_mask.fetch_and(~woken, std::memory_order_acq_rel);
                }
            }

            /* Apply per-core WFI/WFE sleep transitions and locate highest-priority host-visible stop */
            int32_t fault_core = -1;
            int32_t irq_core = -1;
            int32_t halt_core = -1;
            const bool host_halted = engine->host_halt_requested.load(std::memory_order_acquire);
            const uint32_t req_halt_core = engine->mt_halt_core.load(std::memory_order_acquire);

            for (uint32_t i = 0u; i < ncores; ++i) {
                const uint32_t bit = 1u << i;
                if ((epoch_mask & bit) == 0u || !engine->cores[i]) continue;
                CoreState* c = engine->cores[i].get();
                const uint32_t sr = c->stop_latched ? c->stop_reason : static_cast<uint32_t>(FUJI_DYN_STOP_QUANTUM);
                if (sr == FUJI_DYN_STOP_WFI) {
                    const uint32_t pend = engine->gic_fastpath.is_active()
                        ? (engine->gic_fastpath.core_has_pending(i) ? 1u : 0u)
                        : c->load_irq_pending();
                    if (pend == 0u) {
                        out->wfi_entered_mask |= bit;
                        engine->cluster_wfi_waiting_mask.fetch_or(bit, std::memory_order_acq_rel);
                        runnable &= ~bit;
                        engine->cluster_runnable_mask.store(runnable, std::memory_order_release);
                    }
                } else if (sr == FUJI_DYN_STOP_WFE) {
                    const uint32_t pend = engine->gic_fastpath.is_active()
                        ? (engine->gic_fastpath.core_has_pending(i) ? 1u : 0u)
                        : c->load_irq_pending();
                    const uint32_t ev = engine->wfe_event_mask.fetch_and(~bit, std::memory_order_acq_rel);
                    if (pend == 0u && (ev & bit) == 0u) {
                        out->wfe_entered_mask |= bit;
                        engine->cluster_wfe_waiting_mask.fetch_or(bit, std::memory_order_release);
                        runnable &= ~bit;
                        engine->cluster_runnable_mask.store(runnable, std::memory_order_release);
                    }
                } else if (sr == FUJI_DYN_STOP_FAULT_DABT || sr == FUJI_DYN_STOP_FAULT_PABT ||
                           sr == FUJI_DYN_STOP_FAULT_UNDEF || sr == FUJI_DYN_STOP_SVC ||
                           sr == FUJI_DYN_STOP_TRACEPOINT) {
                    if (fault_core < 0) {
                        fault_core = static_cast<int32_t>(i);
                    } else {
                        c->deferred_stop_pending = true;
                    }
                } else if (sr == FUJI_DYN_STOP_IRQ) {
                    if (irq_core < 0) irq_core = static_cast<int32_t>(i);
                } else if (sr == FUJI_DYN_STOP_MMIO_HALT || sr == FUJI_DYN_STOP_HALT) {
                    if (halt_core < 0 || i == req_halt_core) halt_core = static_cast<int32_t>(i);
                }
            }

            if (host_halted && halt_core < 0) {
                if (req_halt_core < ncores && (epoch_mask & (1u << req_halt_core)) != 0u) {
                    halt_core = static_cast<int32_t>(req_halt_core);
                } else {
                    halt_core = static_cast<int32_t>(std::countr_zero(epoch_mask));
                }
            }

            int32_t stop_core_idx = -1;
            uint32_t terminal_reason = FUJI_DYN_STOP_QUANTUM;
            if (fault_core >= 0) {
                stop_core_idx = fault_core;
                terminal_reason = engine->cores[stop_core_idx]->stop_reason;
            } else if (irq_core >= 0) {
                stop_core_idx = irq_core;
                terminal_reason = FUJI_DYN_STOP_IRQ;
            } else if (host_halted && halt_core >= 0) {
                stop_core_idx = halt_core;
                const CoreState* hc = engine->cores[stop_core_idx].get();
                terminal_reason = (hc->stop_latched && hc->stop_reason == FUJI_DYN_STOP_HALT)
                    ? static_cast<uint32_t>(FUJI_DYN_STOP_HALT)
                    : static_cast<uint32_t>(FUJI_DYN_STOP_MMIO_HALT);
            }

            /* Publish fresh CPSR/PC state for live_state buffer */
            if (engine->live_state) {
                for (uint32_t i = 0u; i < ncores; ++i) {
                    if (engine->cores[i]) {
                        engine->live_state->cpsr_by_core[i] = engine->cores[i]->jit->Cpsr();
                        engine->live_state->valid_mask |= (1u << i);
                    }
                }
            }

            if (stop_core_idx >= 0) {
                const uint32_t sc = static_cast<uint32_t>(stop_core_idx);
                CoreState* c = engine->cores[sc].get();
                engine->active_core.store(sc, std::memory_order_release);
                c->publish_live_state();
                out->active_core = sc;
                out->active_used = 0u;
                out->stop_pc = c->jit->Regs()[15];
                out->stop_cpsr = c->jit->Cpsr();
                out->stop_insn = c->stop_insn;
                out->fault_addr = c->fault_addr;
                out->fault_status = c->fault_status;
                out->svc_imm = c->svc_imm;
                out->stop_reason = terminal_reason;
                return 0;
            }

            if (runnable == 0u) {
                if (try_inline_wfi_fastforward()) {
                    int32_t nxt = pick_next(core);
                    if (nxt >= 0) {
                        core = static_cast<uint32_t>(nxt);
                        used = 0u;
                        out->active_core = core;
                        out->active_used = used;
                        ++out->switches;
                        continue;
                    }
                }
                out->stop_reason = FUJI_DYN_STOP_ALL_WAITING;
                return 0;
            }

            int32_t nxt = pick_next(core);
            if (nxt >= 0) {
                if (static_cast<uint32_t>(nxt) != core) ++out->switches;
                core = static_cast<uint32_t>(nxt);
                used = 0u;
                out->active_core = core;
                out->active_used = used;
                if (engine->cores[core]) {
                    engine->active_core.store(core, std::memory_order_release);
                    out->stop_pc = engine->cores[core]->jit->Regs()[15];
                    out->stop_cpsr = engine->cores[core]->jit->Cpsr();
                }
            }
            continue;
        }

        /* ============================================================================
         * Single-Threaded / Single-Active-Core Fast Bypass (zero worker/barrier overhead)
         * ============================================================================ */
        uint64_t slice_budget = single_runnable
            ? remaining_total
            : std::min<uint64_t>(remaining_total, static_cast<uint64_t>(quantum - used));
        if (engine->inline_timer_irq_active()) {
            const uint64_t next_dl = engine->earliest_inline_timer_deadline();
            if (next_dl != std::numeric_limits<uint64_t>::max() && next_dl > vtick) {
                const uint64_t until_timer = next_dl - vtick;
                const uint64_t div = engine->vtick_divider;
                const uint64_t until_insns = (div <= 1u)
                    ? until_timer
                    : (until_timer > (std::numeric_limits<uint64_t>::max() / div)
                        ? std::numeric_limits<uint64_t>::max()
                        : (until_timer * div - engine->vtick_subticks));
                if (until_insns < slice_budget) slice_budget = until_insns;
            }
        }
        if (slice_budget == 0u) slice_budget = 1u;

        FujiDynRunResult rr{};
        const uint32_t core_irq = engine->gic_fastpath.is_active()
            ? (engine->gic_fastpath.core_has_pending(core) ? 1u : 0u)
            : (engine->cores[core] ? engine->cores[core]->load_irq_pending() : 0u);
        int32_t rc = fuji_dyn_run_fast(engine, core, slice_budget, vtick, core_irq, &rr);
        if (rc != 0) return rc;

        out->total_executed += rr.executed_ticks;
        out->core_executed[core] += rr.executed_ticks;
        out->translated_fetches += rr.translated_fetches;
        out->fast_mmio_hits += rr.fast_mmio_hits;
        vtick = rr.virtual_tick;
        out->virtual_tick = vtick;
        remaining_total = (rr.executed_ticks >= remaining_total) ? 0u : (remaining_total - rr.executed_ticks);
        used = single_runnable
            ? static_cast<uint32_t>((used + rr.executed_ticks) % quantum)
            : ((used + rr.executed_ticks >= quantum) ? quantum : static_cast<uint32_t>(used + rr.executed_ticks));

        out->active_core = core;
        out->active_used = used;
        out->stop_pc = rr.stop_pc;
        out->stop_cpsr = rr.stop_cpsr;
        out->stop_insn = rr.stop_insn;
        out->fault_addr = rr.fault_addr;
        out->fault_status = rr.fault_status;
        out->svc_imm = rr.svc_imm;

        /* Always drain inline GICD_SGIR / SPI wakeups before processing WFI/WFE/MMIO_HALT
         * (covers same-block [STR GICD_SGIR; WFI] sequences cleanly!). */
        const uint32_t inline_woken = (engine->cluster_irq_woken_mask.load(std::memory_order_acquire) != 0u)
            ? apply_inline_irq_wakeups()
            : 0u;
        if (rr.stop_reason == FUJI_DYN_STOP_MMIO_HALT &&
            !engine->host_halt_requested.load(std::memory_order_acquire) &&
            inline_woken != 0u) {
            if (remaining_total > 0u) {
                int32_t nxt = pick_next(core);
                if (nxt >= 0 && static_cast<uint32_t>(nxt) != core) {
                    core = static_cast<uint32_t>(nxt);
                    used = 0u;
                    out->active_core = core;
                    out->active_used = used;
                    ++out->switches;
                }
                continue;
            }
        }

        if (rr.stop_reason == FUJI_DYN_STOP_WFI) {
            out->wfi_entered_mask |= (1u << core);
            engine->cluster_wfi_waiting_mask.fetch_or(1u << core, std::memory_order_acq_rel);
            runnable &= ~(1u << core);
            engine->cluster_runnable_mask.store(runnable, std::memory_order_release);
            if (runnable == 0u) {
                if (try_inline_wfi_fastforward()) {
                    int32_t nxt = pick_next(core);
                    if (nxt >= 0) {
                        core = static_cast<uint32_t>(nxt);
                        used = 0u;
                        out->active_core = core;
                        out->active_used = used;
                        ++out->switches;
                        continue;
                    }
                }
                out->stop_reason = FUJI_DYN_STOP_ALL_WAITING;
                return 0;
            }
            if (remaining_total == 0u) {
                out->stop_reason = FUJI_DYN_STOP_QUANTUM;
                return 0;
            }
            int32_t nxt = pick_next(core);
            core = static_cast<uint32_t>(nxt);
            used = 0u;
            out->active_core = core;
            out->active_used = used;
            ++out->switches;
            continue;
        }
        if (rr.stop_reason == FUJI_DYN_STOP_WFE) {
            out->wfe_entered_mask |= (1u << core);
            engine->cluster_wfe_waiting_mask.fetch_or(1u << core, std::memory_order_acq_rel);
            runnable &= ~(1u << core);
            engine->cluster_runnable_mask.store(runnable, std::memory_order_release);
            if (runnable == 0u) {
                if (try_inline_wfi_fastforward()) {
                    int32_t nxt = pick_next(core);
                    if (nxt >= 0) {
                        core = static_cast<uint32_t>(nxt);
                        used = 0u;
                        out->active_core = core;
                        out->active_used = used;
                        ++out->switches;
                        continue;
                    }
                }
                out->stop_reason = FUJI_DYN_STOP_ALL_WAITING;
                return 0;
            }
            if (remaining_total == 0u) {
                out->stop_reason = FUJI_DYN_STOP_QUANTUM;
                return 0;
            }
            int32_t nxt = pick_next(core);
            core = static_cast<uint32_t>(nxt);
            used = 0u;
            out->active_core = core;
            out->active_used = used;
            ++out->switches;
            continue;
        }
        if (rr.stop_reason == FUJI_DYN_STOP_SEV) {
            out->sev_mask |= (1u << core);
            const uint32_t wfe_wait = engine->cluster_wfe_waiting_mask.load(std::memory_order_acquire);
            const uint32_t woken = wfe_wait & ~(1u << core);
            if (woken != 0u) {
                runnable |= woken;
                engine->cluster_runnable_mask.store(runnable, std::memory_order_release);
                out->wfe_entered_mask &= ~woken;
                engine->cluster_wfe_waiting_mask.store(wfe_wait & ~woken, std::memory_order_release);
                engine->wfe_event_mask.fetch_and(~woken, std::memory_order_acq_rel);
            }
        } else if (rr.stop_reason != FUJI_DYN_STOP_QUANTUM) {
            out->stop_reason = rr.stop_reason;
            return 0;
        }
        if (remaining_total == 0u) {
            out->stop_reason = FUJI_DYN_STOP_QUANTUM;
            return 0;
        }
        if (used >= quantum) {
            int32_t nxt = pick_next(core);
            if (nxt >= 0 && static_cast<uint32_t>(nxt) != core) {
                core = static_cast<uint32_t>(nxt);
                used = 0u;
                out->active_core = core;
                out->active_used = used;
                ++out->switches;
            } else {
                used = 0u;
                out->active_used = used;
            }
        }
    }

    out->stop_reason = (runnable == 0u) ? FUJI_DYN_STOP_ALL_WAITING : FUJI_DYN_STOP_QUANTUM;
    return 0;
}

FUJI_DYN_EXPORT void fuji_dyn_set_smp_host_threads(FujiDynEngine* engine, uint32_t enabled) {
    if (!engine) return;
    const bool want = (enabled != 0u) && (engine->num_cores > 1u);
    engine->smp_host_threads.store(want, std::memory_order_release);
    if (want) {
        engine->ensure_mt_workers_started();
    } else {
        engine->shutdown_mt_workers();
    }
}

FUJI_DYN_EXPORT uint32_t fuji_dyn_get_smp_host_threads(FujiDynEngine* engine) {
    if (!engine) return 0u;
    return engine->smp_host_threads.load(std::memory_order_acquire) ? 1u : 0u;
}

FUJI_DYN_EXPORT int32_t fuji_dyn_register_mmio_mirror(FujiDynEngine* engine, uint32_t phys_base, uint32_t window_size, const void* mirror_block) {
    if (!engine || window_size == 0u || !mirror_block) return -1;
    if ((reinterpret_cast<uintptr_t>(mirror_block) & 7u) != 0u) return -1;
    auto* header = const_cast<FujiDynMmioMirrorHeader*>(reinterpret_cast<const FujiDynMmioMirrorHeader*>(mirror_block));
    const uint32_t slot_count = header->slot_count;
    if (slot_count == 0u || slot_count > FUJI_DYN_MMIO_MIRROR_MAX_SLOTS) return -1;
    auto* slots = reinterpret_cast<FujiDynMmioMirrorSlot*>(reinterpret_cast<uint8_t*>(header) + sizeof(FujiDynMmioMirrorHeader));
    for (uint32_t s = 0u; s < slot_count; ++s) {
        const uint32_t w = slots[s].width_bytes;
        if (w != 1u && w != 2u && w != 4u) return -1;
        if ((slots[s].offset & (w - 1u)) != 0u) return -1;
        if (static_cast<uint64_t>(slots[s].offset) + w > window_size) return -1;
    }
    /* Update existing bank at phys_base if present */
    for (uint32_t i = 0u; i < engine->mmio_mirror_bank_count; ++i) {
        if (engine->mmio_mirror_banks[i].phys_base == phys_base) {
            engine->mmio_mirror_banks[i] = FujiDynEngine::MmioMirrorBank{phys_base, window_size, header, slots, slot_count};
            engine->rebuild_mmio_mirror_table();
            return 0;
        }
    }
    if (engine->mmio_mirror_bank_count >= FUJI_DYN_MMIO_MIRROR_MAX_BANKS) return -2;
    if (engine->mmio_mirror_slot_total + slot_count >= FujiDynEngine::MMIO_MIRROR_TABLE_SIZE) return -2;
    engine->mmio_mirror_banks[engine->mmio_mirror_bank_count++] =
        FujiDynEngine::MmioMirrorBank{phys_base, window_size, header, slots, slot_count};
    engine->rebuild_mmio_mirror_table();
    return 0;
}

FUJI_DYN_EXPORT int32_t fuji_dyn_unregister_mmio_mirror(FujiDynEngine* engine, uint32_t phys_base) {
    if (!engine) return -1;
    for (uint32_t i = 0u; i < engine->mmio_mirror_bank_count; ++i) {
        if (engine->mmio_mirror_banks[i].phys_base == phys_base) {
            engine->mmio_mirror_banks[i] = engine->mmio_mirror_banks[--engine->mmio_mirror_bank_count];
            engine->mmio_mirror_banks[engine->mmio_mirror_bank_count] = FujiDynEngine::MmioMirrorBank{};
            engine->rebuild_mmio_mirror_table();
            return 0;
        }
    }
    return -1;
}

FUJI_DYN_EXPORT void fuji_dyn_clear_mmio_mirrors(FujiDynEngine* engine) {
    if (!engine) return;
    engine->mmio_mirror_bank_count = 0u;
    engine->mmio_mirror_banks.fill(FujiDynEngine::MmioMirrorBank{});
    engine->mmio_mirror_table.fill(FujiDynEngine::FlatMmioMirrorEntry{});
    engine->mmio_mirror_slot_total = 0u;
    engine->mmio_mirror_min_addr = 0xFFFFFFFFu;
    engine->mmio_mirror_max_end = 0u;
}

FUJI_DYN_EXPORT void fuji_dyn_set_gic_shadow_ptr(FujiDynEngine* engine, uint32_t gicd_base, uint32_t gicc_base, FujiDynGicShadow* shared_shadow) {
    if (!engine) return;
    engine->gic_fastpath.configure(gicd_base, gicc_base, reinterpret_cast<GicV2ShadowState*>(shared_shadow));
    if (engine->gic_fastpath.is_active()) {
        for (uint32_t i = 0; i < engine->num_cores; ++i) {
            if (engine->cores[i]) {
                engine->cores[i]->irq_pending = engine->gic_fastpath.core_has_pending(i) ? 1u : 0u;
            }
        }
    }
}

FUJI_DYN_EXPORT void fuji_dyn_clear_gic_shadow(FujiDynEngine* engine) {
    if (!engine) return;
    engine->gic_fastpath.clear();
}

FUJI_DYN_EXPORT void fuji_dyn_set_timer_irq_shadow_ptr(FujiDynEngine* engine, FujiDynTimerShadow* rw_timer_shadow, FujiDynTimerIrqShadow* irq_shadow) {
    if (!engine) return;
    engine->rw_timer_shadow = rw_timer_shadow;
    engine->timer_irq_shadow = irq_shadow;
}

FUJI_DYN_EXPORT void fuji_dyn_clear_timer_irq_shadow(FujiDynEngine* engine) {
    if (!engine) return;
    engine->rw_timer_shadow = nullptr;
    engine->timer_irq_shadow = nullptr;
}

FUJI_DYN_EXPORT uintptr_t fuji_dyn_get_fastmem_base(FujiDynEngine* engine) {
    if (!engine || !engine->fastmem.active()) return 0u;
    return reinterpret_cast<uintptr_t>(engine->fastmem.core_base(0u));
}

FUJI_DYN_EXPORT uint32_t fuji_dyn_fastmem_is_dual_view(FujiDynEngine* engine) {
    if (!engine || !engine->fastmem.active()) return 0u;
    return engine->fastmem.dual_view ? 1u : 0u;
}

FUJI_DYN_EXPORT void* fuji_dyn_get_ram_host_ptr(FujiDynEngine* engine, uint32_t phys_base) {
    if (!engine) return nullptr;
    return engine->fastmem.find_host_rw(phys_base);
}

FUJI_DYN_EXPORT void fuji_dyn_get_mmio_stats(FujiDynEngine* engine, FujiDynMmioStats* out) {
    if (!engine || !out) return;
    out->timer_fast_hits = engine->mmio_stats.timer_fast_hits;
    out->ro_mmio_fast_hits = engine->mmio_stats.ro_mmio_fast_hits;
    out->upcall_reads = engine->mmio_stats.upcall_reads;
    out->upcall_writes = engine->mmio_stats.upcall_writes;
}

FUJI_DYN_EXPORT void fuji_dyn_reset_mmio_stats(FujiDynEngine* engine) {
    if (!engine) return;
    engine->mmio_stats.reset();
}

} /* extern "C" */
