#ifndef FUJI_DYNARMIC_H
#define FUJI_DYNARMIC_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32) || defined(__CYGWIN__)
  #if defined(FUJI_DYNARMIC_BUILD_DLL)
    #define FUJI_DYN_EXPORT __declspec(dllexport)
  #else
    #define FUJI_DYN_EXPORT
  #endif
#else
  #define FUJI_DYN_EXPORT __attribute__((visibility("default")))
#endif

#define FUJI_DYN_ABI_VERSION 1u
#define FUJI_DYN_MAX_CORES   4u

/* Memory mapping permissions */
#define FUJI_DYN_PERM_READ   1u
#define FUJI_DYN_PERM_WRITE  2u
#define FUJI_DYN_PERM_EXEC   4u
#define FUJI_DYN_PERM_ALL    (FUJI_DYN_PERM_READ | FUJI_DYN_PERM_WRITE | FUJI_DYN_PERM_EXEC)

/* ARMv7-A processor modes (CPSR[4:0]) */
#define FUJI_DYN_MODE_USR    0x10u
#define FUJI_DYN_MODE_FIQ    0x11u
#define FUJI_DYN_MODE_IRQ    0x12u
#define FUJI_DYN_MODE_SVC    0x13u
#define FUJI_DYN_MODE_ABT    0x17u
#define FUJI_DYN_MODE_UND    0x1Bu
#define FUJI_DYN_MODE_SYS    0x1Fu

/* Banked register selector for fuji_dyn_{get,set}_banked_reg */
#define FUJI_DYN_BANKED_R8   8u
#define FUJI_DYN_BANKED_R9   9u
#define FUJI_DYN_BANKED_R10  10u
#define FUJI_DYN_BANKED_R11  11u
#define FUJI_DYN_BANKED_R12  12u
#define FUJI_DYN_BANKED_SP   13u
#define FUJI_DYN_BANKED_LR   14u
#define FUJI_DYN_BANKED_SPSR 16u

/* Extended/VFP register selectors for fuji_dyn_{get,set}_ext_reg */
/* 0..63  : S0..S63 (raw 32-bit halves of D0..D31) */
#define FUJI_DYN_EXT_FPSCR   64u
#define FUJI_DYN_EXT_FPEXC   65u

/* Stop reasons reported in FujiDynRunResult.stop_reason */
typedef enum FujiDynStopReason {
    FUJI_DYN_STOP_QUANTUM     = 0,
    FUJI_DYN_STOP_WFI         = 1,
    FUJI_DYN_STOP_WFE         = 2,
    FUJI_DYN_STOP_SEV         = 3,
    FUJI_DYN_STOP_MMIO_HALT   = 4,
    FUJI_DYN_STOP_SVC         = 5,
    FUJI_DYN_STOP_IRQ         = 6,
    FUJI_DYN_STOP_FAULT_DABT  = 7,
    FUJI_DYN_STOP_FAULT_PABT  = 8,
    FUJI_DYN_STOP_FAULT_UNDEF = 9,
    FUJI_DYN_STOP_HALT        = 10,
    FUJI_DYN_STOP_TRACEPOINT  = 11,
    FUJI_DYN_STOP_ALL_WAITING = 12
} FujiDynStopReason;

/* Auto-exception vectoring bitmask for fuji_dyn_set_auto_exceptions */
#define FUJI_DYN_AUTO_EXCP_SVC   (1u << 0)
#define FUJI_DYN_AUTO_EXCP_UNDEF (1u << 1)
#define FUJI_DYN_AUTO_EXCP_PABT  (1u << 2)
#define FUJI_DYN_AUTO_EXCP_DABT  (1u << 3)
#define FUJI_DYN_AUTO_EXCP_ALL   (FUJI_DYN_AUTO_EXCP_SVC | FUJI_DYN_AUTO_EXCP_UNDEF | FUJI_DYN_AUTO_EXCP_PABT | FUJI_DYN_AUTO_EXCP_DABT)

/* Fixed 64-byte ABI result struct filled by fuji_dyn_run_fast */
typedef struct FujiDynRunResult {
    uint64_t executed_ticks;     /* offset 0x00 */
    uint64_t virtual_tick;       /* offset 0x08 */
    uint32_t stop_pc;            /* offset 0x10 */
    uint32_t stop_cpsr;          /* offset 0x14 */
    uint32_t stop_reason;        /* offset 0x18 */
    uint32_t stop_insn;          /* offset 0x1C */
    uint32_t fault_addr;         /* offset 0x20 */
    uint32_t fault_status;       /* offset 0x24 */
    uint32_t svc_imm;            /* offset 0x28 */
    uint32_t reserved0;          /* offset 0x2C */
    uint64_t translated_fetches; /* offset 0x30 */
    uint64_t fast_mmio_hits;     /* offset 0x38: C++ timer + RO-MMIO fastpath hits in this run */
} FujiDynRunResult;

#ifdef __cplusplus
static_assert(sizeof(FujiDynRunResult) == 64, "FujiDynRunResult must be 64 bytes");
#else
_Static_assert(sizeof(FujiDynRunResult) == 64, "FujiDynRunResult must be 64 bytes");
#endif

/* Native C++ soc-timer shadow (bit-exact match to TimerFactory.Counter.value(now)) */
#define FUJI_DYN_TIMER_CHANNELS 8u
#define FUJI_DYN_RO_MMIO_SLOTS  512u
#define FUJI_DYN_RO_MMIO_RANGES 16u
#define FUJI_DYN_MMIO_MIRROR_MAX_BANKS 128u
#define FUJI_DYN_MMIO_MIRROR_MAX_SLOTS 64u

/* Generic declarative per-peripheral live MMIO read-mirror block (ABI v1 backward-compatible extension).
 * Peripheral components declare side-effect-free status/control registers via NativeMmioMirrorPort and
 * update values with release semantics; C++ reads lock-free with acquire semantics before FFM upcall. */
typedef struct FujiDynMmioMirrorHeader {
    uint64_t valid_mask; /* offset 0x00: bit i == 1 enables lock-free read of slot i */
    uint32_t slot_count; /* offset 0x08: number of slots in [1..64] */
    uint32_t reserved;   /* offset 0x0C */
} FujiDynMmioMirrorHeader;

typedef struct FujiDynMmioMirrorSlot {
    uint32_t offset;      /* offset 0x00: byte offset from window phys_base */
    uint32_t width_bytes; /* offset 0x04: exact access width (1, 2, or 4) */
    uint32_t value;       /* offset 0x08: zero-extended live register value */
    uint32_t reserved;    /* offset 0x0C */
} FujiDynMmioMirrorSlot;

/* Shared off-heap live-state struct (48 bytes) written by C++ before MMIO upcalls & on run exit */
typedef struct FujiDynLiveState {
    uint64_t current_vtick;                    /* offset 0x00 */
    uint32_t current_pc;                       /* offset 0x08 */
    uint32_t current_cpsr;                     /* offset 0x0C */
    uint32_t active_core;                      /* offset 0x10 */
    uint32_t valid_mask;                       /* offset 0x14 */
    uint32_t cpsr_by_core[FUJI_DYN_MAX_CORES]; /* offset 0x18..0x27 */
    uint64_t reserved;                         /* offset 0x28..0x2F */
} FujiDynLiveState;

/* Native WFI-aware multi-core round-robin cluster batch request (40 B) & result (120 B) */
typedef struct FujiDynClusterRequest {
    uint64_t max_total_ticks;  /* offset 0x00 */
    uint64_t start_vtick;      /* offset 0x08 */
    uint32_t runnable_mask;    /* offset 0x10 */
    uint32_t irq_pending_mask; /* offset 0x14 */
    uint32_t start_core;       /* offset 0x18 */
    uint32_t start_used;       /* offset 0x1C */
    uint32_t quantum;          /* offset 0x20 */
    uint32_t wfe_waiting_mask; /* offset 0x24: bits[3:0]=WFE waiting cores, bits[7:4]=WFI waiting cores */
} FujiDynClusterRequest;

typedef struct FujiDynClusterResult {
    uint64_t total_executed;                     /* offset 0x00 */
    uint64_t virtual_tick;                       /* offset 0x08 */
    uint64_t core_executed[FUJI_DYN_MAX_CORES];  /* offset 0x10..0x2F */
    uint32_t active_core;                        /* offset 0x30 */
    uint32_t active_used;                        /* offset 0x34 */
    uint32_t stop_reason;                        /* offset 0x38 */
    uint32_t stop_pc;                            /* offset 0x3C */
    uint32_t stop_cpsr;                          /* offset 0x40 */
    uint32_t stop_insn;                          /* offset 0x44 */
    uint32_t fault_addr;                         /* offset 0x48 */
    uint32_t fault_status;                       /* offset 0x4C */
    uint32_t svc_imm;                            /* offset 0x50 */
    uint32_t wfi_entered_mask;                   /* offset 0x54 */
    uint32_t wfe_entered_mask;                   /* offset 0x58 */
    uint32_t sev_mask;                           /* offset 0x5C */
    uint64_t translated_fetches;                 /* offset 0x60 */
    uint64_t fast_mmio_hits;                     /* offset 0x68 */
    uint32_t switches;                           /* offset 0x70 */
    uint32_t wfi_woken_mask;                     /* offset 0x74: cores woken from WFI inline inside C++ */
} FujiDynClusterResult;

typedef struct FujiDynTimerChannel {
    uint64_t stored;       /* offset 0x00 */
    uint64_t reload;       /* offset 0x08 */
    uint64_t deadline;     /* offset 0x10 */
    uint32_t enabled;      /* offset 0x18: 0 or 1 */
    uint32_t has_deadline; /* offset 0x1C: 0 or 1 */
} FujiDynTimerChannel;

typedef struct FujiDynTimerShadow {
    uint32_t active;       /* offset 0x00: non-zero enables C++ read fastpath */
    uint32_t reserved;     /* offset 0x04 */
    FujiDynTimerChannel channels[FUJI_DYN_TIMER_CHANNELS]; /* offset 0x08..0x107 */
} FujiDynTimerShadow;

/* Shared off-heap inline soc-timer comparator IRQ auto-raise state (128 B, DYN-V3) */
typedef struct FujiDynTimerIrqShadow {
    uint32_t active;                                 /* offset 0x00: bit0=inline IRQ fire, bit1=allow WFI fast-forward */
    uint32_t dirty;                                  /* offset 0x04: set non-zero on inline expiry */
    uint64_t last_fire_tick;                         /* offset 0x08: virtual tick of last inline expiry */
    int32_t  irq_routes[FUJI_DYN_TIMER_CHANNELS];    /* offset 0x10..0x2F: GIC SPI line (32..511) or -1 */
    uint64_t fired_periods[FUJI_DYN_TIMER_CHANNELS]; /* offset 0x30..0x6F: cumulative inline elapsed periods */
    uint64_t next_other_deadline;                    /* offset 0x70: earliest non-timer device deadline */
    uint64_t inline_wfi_skips;                       /* offset 0x78: cumulative C++ WFI-to-timer skips */
} FujiDynTimerIrqShadow;

typedef struct FujiDynMmioStats {
    uint64_t timer_fast_hits;   /* offset 0x00 */
    uint64_t ro_mmio_fast_hits; /* offset 0x08 */
    uint64_t upcall_reads;      /* offset 0x10 */
    uint64_t upcall_writes;     /* offset 0x18 */
} FujiDynMmioStats;

/* Shared off-heap GICv2 fastpath state (mirrors Dynarmic::A32Sys::GicV2ShadowState 1:1) */
#define FUJI_DYN_GIC_SPI_WORDS      8u
#define FUJI_DYN_GIC_STACK_CAPACITY 32u

typedef struct FujiDynGicCoreShadow {
    uint32_t irq_pending;                         /* offset 0x00 */
    uint32_t cpu_ctrl;                            /* offset 0x04 */
    uint32_t pmr;                                 /* offset 0x08 */
    uint32_t bpr;                                 /* offset 0x0C */
    uint32_t local_enabled;                       /* offset 0x10 */
    uint32_t local_pending;                       /* offset 0x14 */
    uint32_t local_levels;                        /* offset 0x18 */
    uint32_t local_active;                        /* offset 0x1C */
    uint32_t local_edge;                          /* offset 0x20 */
    uint32_t active_stack_size;                   /* offset 0x24 */
    uint32_t active_stack[FUJI_DYN_GIC_STACK_CAPACITY]; /* offset 0x28..0xA7 */
    uint8_t  local_priority[32];                  /* offset 0xA8..0xC7 */
    uint8_t  sgi_senders[16];                     /* offset 0xC8..0xD7 */
    uint32_t dirty_flag;                          /* offset 0xD8 */
    uint32_t reserved;                            /* offset 0xDC */
} FujiDynGicCoreShadow;

typedef struct FujiDynGicShadow {
    uint32_t active;                              /* offset 0x000 */
    uint32_t core_count;                          /* offset 0x004 */
    uint32_t dist_ctrl;                           /* offset 0x008 */
    uint32_t dirty_spi_mask;                      /* offset 0x00C */
    uint64_t native_seq;                          /* offset 0x010 */
    uint64_t fast_hits;                           /* offset 0x018 */
    uint64_t shared_enabled[FUJI_DYN_GIC_SPI_WORDS]; /* offset 0x020..0x05F */
    uint64_t shared_pending[FUJI_DYN_GIC_SPI_WORDS]; /* offset 0x060..0x09F */
    uint64_t shared_levels[FUJI_DYN_GIC_SPI_WORDS];  /* offset 0x0A0..0x0DF */
    uint64_t shared_active[FUJI_DYN_GIC_SPI_WORDS];  /* offset 0x0E0..0x11F */
    uint64_t shared_edge[FUJI_DYN_GIC_SPI_WORDS];    /* offset 0x120..0x15F */
    uint64_t target_core_bits[FUJI_DYN_MAX_CORES][FUJI_DYN_GIC_SPI_WORDS]; /* offset 0x160..0x25F */
    uint8_t  shared_priority[480];                /* offset 0x260..0x43F */
    uint8_t  shared_targets[480];                 /* offset 0x440..0x61F */
    FujiDynGicCoreShadow cores[FUJI_DYN_MAX_CORES];  /* offset 0x620..0x99F */
} FujiDynGicShadow;

#ifdef __cplusplus
static_assert(sizeof(FujiDynTimerChannel) == 32, "FujiDynTimerChannel must be 32 bytes");
static_assert(sizeof(FujiDynTimerShadow) == 264, "FujiDynTimerShadow must be 264 bytes");
static_assert(sizeof(FujiDynTimerIrqShadow) == 128, "FujiDynTimerIrqShadow must be 128 bytes");
static_assert(sizeof(FujiDynMmioStats) == 32, "FujiDynMmioStats must be 32 bytes");
static_assert(sizeof(FujiDynLiveState) == 48, "FujiDynLiveState must be 48 bytes");
static_assert(sizeof(FujiDynClusterRequest) == 40, "FujiDynClusterRequest must be 40 bytes");
static_assert(sizeof(FujiDynClusterResult) == 120, "FujiDynClusterResult must be 120 bytes");
static_assert(sizeof(FujiDynGicCoreShadow) == 224, "FujiDynGicCoreShadow must be 224 bytes");
static_assert(sizeof(FujiDynGicShadow) == 2464, "FujiDynGicShadow must be 2464 bytes");
static_assert(sizeof(FujiDynMmioMirrorHeader) == 16, "FujiDynMmioMirrorHeader must be 16 bytes");
static_assert(sizeof(FujiDynMmioMirrorSlot) == 16, "FujiDynMmioMirrorSlot must be 16 bytes");
#else
_Static_assert(sizeof(FujiDynTimerChannel) == 32, "FujiDynTimerChannel must be 32 bytes");
_Static_assert(sizeof(FujiDynTimerShadow) == 264, "FujiDynTimerShadow must be 264 bytes");
_Static_assert(sizeof(FujiDynTimerIrqShadow) == 128, "FujiDynTimerIrqShadow must be 128 bytes");
_Static_assert(sizeof(FujiDynMmioStats) == 32, "FujiDynMmioStats must be 32 bytes");
_Static_assert(sizeof(FujiDynLiveState) == 48, "FujiDynLiveState must be 48 bytes");
_Static_assert(sizeof(FujiDynClusterRequest) == 40, "FujiDynClusterRequest must be 40 bytes");
_Static_assert(sizeof(FujiDynClusterResult) == 120, "FujiDynClusterResult must be 120 bytes");
_Static_assert(sizeof(FujiDynGicCoreShadow) == 224, "FujiDynGicCoreShadow must be 224 bytes");
_Static_assert(sizeof(FujiDynGicShadow) == 2464, "FujiDynGicShadow must be 2464 bytes");
_Static_assert(sizeof(FujiDynMmioMirrorHeader) == 16, "FujiDynMmioMirrorHeader must be 16 bytes");
_Static_assert(sizeof(FujiDynMmioMirrorSlot) == 16, "FujiDynMmioMirrorSlot must be 16 bytes");
#endif

typedef struct FujiDynEngine FujiDynEngine;

typedef uint64_t (*FujiDynMmioReadFn)(void* user, uint32_t phys_addr, uint32_t size);
typedef void     (*FujiDynMmioWriteFn)(void* user, uint32_t phys_addr, uint32_t size, uint64_t value);

/* ---- Lifecycle ---- */
FUJI_DYN_EXPORT uint32_t       fuji_dyn_abi_version(void);
FUJI_DYN_EXPORT FujiDynEngine* fuji_dyn_create(uint32_t num_cores, uint32_t cbar_base);
FUJI_DYN_EXPORT void           fuji_dyn_destroy(FujiDynEngine* engine);
FUJI_DYN_EXPORT void           fuji_dyn_reset_core(FujiDynEngine* engine, uint32_t core);

/* ---- Memory & MMIO ---- */
FUJI_DYN_EXPORT int32_t fuji_dyn_map_ram(FujiDynEngine* engine, uint32_t phys_base, uint32_t size, void* host_ptr, uint32_t perms);
FUJI_DYN_EXPORT int32_t fuji_dyn_unmap_ram(FujiDynEngine* engine, uint32_t phys_base, uint32_t size);
FUJI_DYN_EXPORT int32_t fuji_dyn_map_ram64(FujiDynEngine* engine, uint64_t phys_base, uint64_t size, void* host_ptr, uint32_t perms);
FUJI_DYN_EXPORT int32_t fuji_dyn_unmap_ram64(FujiDynEngine* engine, uint64_t phys_base, uint64_t size);
FUJI_DYN_EXPORT int32_t fuji_dyn_map_mmio(FujiDynEngine* engine, uint32_t phys_base, uint32_t size);
FUJI_DYN_EXPORT int32_t fuji_dyn_unmap_mmio(FujiDynEngine* engine, uint32_t phys_base, uint32_t size);
FUJI_DYN_EXPORT void    fuji_dyn_set_mmio_callbacks(FujiDynEngine* engine, FujiDynMmioReadFn read_cb, FujiDynMmioWriteFn write_cb, void* user);
FUJI_DYN_EXPORT int32_t fuji_dyn_mem_read(FujiDynEngine* engine, uint32_t phys_addr, void* dst, uint32_t size);
FUJI_DYN_EXPORT int32_t fuji_dyn_mem_write(FujiDynEngine* engine, uint32_t phys_addr, const void* src, uint32_t size);
FUJI_DYN_EXPORT int32_t fuji_dyn_mem_read64(FujiDynEngine* engine, uint64_t phys_addr, void* dst, uint32_t size);
FUJI_DYN_EXPORT int32_t fuji_dyn_mem_write64(FujiDynEngine* engine, uint64_t phys_addr, const void* src, uint32_t size);
FUJI_DYN_EXPORT void    fuji_dyn_invalidate_range(FujiDynEngine* engine, uint32_t phys_base, uint32_t size);
FUJI_DYN_EXPORT void    fuji_dyn_clear_cache(FujiDynEngine* engine);
FUJI_DYN_EXPORT void    fuji_dyn_flush_tlb(FujiDynEngine* engine, uint32_t core);
FUJI_DYN_EXPORT int32_t fuji_dyn_translate(FujiDynEngine* engine, uint32_t core, uint32_t va, uint32_t access, uint32_t* out_pa);
FUJI_DYN_EXPORT int32_t fuji_dyn_translate64(FujiDynEngine* engine, uint32_t core, uint32_t va, uint32_t access, uint64_t* out_pa);

/* ---- Execution & Exceptions ---- */
FUJI_DYN_EXPORT int32_t  fuji_dyn_run_fast(FujiDynEngine* engine, uint32_t core, uint64_t max_ticks, uint64_t current_vtick, uint32_t irq_pending, FujiDynRunResult* out);
FUJI_DYN_EXPORT void     fuji_dyn_halt_run(FujiDynEngine* engine);
FUJI_DYN_EXPORT void     fuji_dyn_set_irq_pending(FujiDynEngine* engine, uint32_t core, uint32_t pending);
FUJI_DYN_EXPORT int32_t  fuji_dyn_deliver_irq(FujiDynEngine* engine, uint32_t core);
FUJI_DYN_EXPORT int32_t  fuji_dyn_deliver_fiq(FujiDynEngine* engine, uint32_t core);
FUJI_DYN_EXPORT int32_t  fuji_dyn_deliver_svc(FujiDynEngine* engine, uint32_t core);
FUJI_DYN_EXPORT int32_t  fuji_dyn_deliver_undef(FujiDynEngine* engine, uint32_t core);
FUJI_DYN_EXPORT int32_t  fuji_dyn_deliver_pabt(FujiDynEngine* engine, uint32_t core, uint32_t far, uint32_t fsr);
FUJI_DYN_EXPORT int32_t  fuji_dyn_deliver_dabt(FujiDynEngine* engine, uint32_t core, uint32_t far, uint32_t fsr);
FUJI_DYN_EXPORT void     fuji_dyn_set_auto_exceptions(FujiDynEngine* engine, uint32_t enable_mask);
FUJI_DYN_EXPORT void     fuji_dyn_set_wfe_event_mask(FujiDynEngine* engine, uint32_t mask);
FUJI_DYN_EXPORT uint32_t fuji_dyn_get_wfe_event_mask(FujiDynEngine* engine);
FUJI_DYN_EXPORT void     fuji_dyn_set_tracepoints(FujiDynEngine* engine, const uint32_t* pcs, uint32_t count);
FUJI_DYN_EXPORT void     fuji_dyn_set_cntfrq(FujiDynEngine* engine, uint32_t freq_hz);
FUJI_DYN_EXPORT void     fuji_dyn_set_vtick_divider(FujiDynEngine* engine, uint32_t divider);
FUJI_DYN_EXPORT void     fuji_dyn_clear_exclusive(FujiDynEngine* engine, uint32_t core);

/* ---- Registers / Banked / VFP / CP15 ---- */
FUJI_DYN_EXPORT uint32_t fuji_dyn_get_reg(FujiDynEngine* engine, uint32_t core, uint32_t reg);
FUJI_DYN_EXPORT void     fuji_dyn_set_reg(FujiDynEngine* engine, uint32_t core, uint32_t reg, uint32_t value);
FUJI_DYN_EXPORT void     fuji_dyn_get_regs(FujiDynEngine* engine, uint32_t core, uint32_t out_regs[16]);
FUJI_DYN_EXPORT void     fuji_dyn_set_regs(FujiDynEngine* engine, uint32_t core, const uint32_t in_regs[16]);
FUJI_DYN_EXPORT uint32_t fuji_dyn_get_cpsr(FujiDynEngine* engine, uint32_t core);
FUJI_DYN_EXPORT void     fuji_dyn_set_cpsr(FujiDynEngine* engine, uint32_t core, uint32_t value);
FUJI_DYN_EXPORT uint32_t fuji_dyn_get_spsr(FujiDynEngine* engine, uint32_t core);
FUJI_DYN_EXPORT void     fuji_dyn_set_spsr(FujiDynEngine* engine, uint32_t core, uint32_t value);
FUJI_DYN_EXPORT uint32_t fuji_dyn_get_banked_reg(FujiDynEngine* engine, uint32_t core, uint32_t mode, uint32_t reg);
FUJI_DYN_EXPORT void     fuji_dyn_set_banked_reg(FujiDynEngine* engine, uint32_t core, uint32_t mode, uint32_t reg, uint32_t value);

FUJI_DYN_EXPORT uint32_t fuji_dyn_get_cp15(FujiDynEngine* engine, uint32_t core, uint32_t opc1, uint32_t crn, uint32_t crm, uint32_t opc2);
FUJI_DYN_EXPORT void     fuji_dyn_set_cp15(FujiDynEngine* engine, uint32_t core, uint32_t opc1, uint32_t crn, uint32_t crm, uint32_t opc2, uint32_t value);
FUJI_DYN_EXPORT uint64_t fuji_dyn_get_cp15_64(FujiDynEngine* engine, uint32_t core, uint32_t opc1, uint32_t crm);
FUJI_DYN_EXPORT void     fuji_dyn_set_cp15_64(FujiDynEngine* engine, uint32_t core, uint32_t opc1, uint32_t crm, uint64_t value);

FUJI_DYN_EXPORT uint32_t fuji_dyn_get_ext_reg(FujiDynEngine* engine, uint32_t core, uint32_t index);
FUJI_DYN_EXPORT void     fuji_dyn_set_ext_reg(FujiDynEngine* engine, uint32_t core, uint32_t index, uint32_t value);
FUJI_DYN_EXPORT uint64_t fuji_dyn_get_dreg(FujiDynEngine* engine, uint32_t core, uint32_t d_index);
FUJI_DYN_EXPORT void     fuji_dyn_set_dreg(FujiDynEngine* engine, uint32_t core, uint32_t d_index, uint64_t value);
FUJI_DYN_EXPORT uint32_t fuji_dyn_get_fpscr(FujiDynEngine* engine, uint32_t core);
FUJI_DYN_EXPORT void     fuji_dyn_set_fpscr(FujiDynEngine* engine, uint32_t core, uint32_t value);
FUJI_DYN_EXPORT uint32_t fuji_dyn_get_fpexc(FujiDynEngine* engine, uint32_t core);
FUJI_DYN_EXPORT void     fuji_dyn_set_fpexc(FujiDynEngine* engine, uint32_t core, uint32_t value);

/* ---- Live Mid-Run Queries (safe to call from MMIO upcalls) ---- */
FUJI_DYN_EXPORT uint32_t fuji_dyn_get_current_pc(FujiDynEngine* engine, uint32_t core);
FUJI_DYN_EXPORT uint64_t fuji_dyn_get_current_vtick(FujiDynEngine* engine);

/* ---- Native C++ MMIO Read Fastpaths (soc-timer, RO ranges, RO words, live state, cluster batch) ---- */
FUJI_DYN_EXPORT void    fuji_dyn_set_timer_shadow(FujiDynEngine* engine, uint32_t phys_base, const FujiDynTimerShadow* shadow);
FUJI_DYN_EXPORT void    fuji_dyn_set_timer_shadow_ptr(FujiDynEngine* engine, uint32_t phys_base, const FujiDynTimerShadow* shared_shadow);
FUJI_DYN_EXPORT void    fuji_dyn_clear_timer_shadow(FujiDynEngine* engine);
FUJI_DYN_EXPORT int32_t fuji_dyn_set_ro_mmio_word(FujiDynEngine* engine, uint32_t phys_addr, uint32_t value);
FUJI_DYN_EXPORT int32_t fuji_dyn_set_ro_mmio_batch(FujiDynEngine* engine, const uint32_t* addrs, const uint32_t* vals, uint32_t count);
FUJI_DYN_EXPORT void    fuji_dyn_remove_ro_mmio_word(FujiDynEngine* engine, uint32_t phys_addr);
FUJI_DYN_EXPORT void    fuji_dyn_clear_ro_mmio_range(FujiDynEngine* engine, uint32_t phys_base, uint32_t size);
FUJI_DYN_EXPORT void    fuji_dyn_clear_ro_mmio(FujiDynEngine* engine);
FUJI_DYN_EXPORT int32_t fuji_dyn_add_ro_mmio_range(FujiDynEngine* engine, uint32_t phys_base, uint32_t size, const uint8_t* host_ptr);
FUJI_DYN_EXPORT void    fuji_dyn_clear_ro_mmio_ranges(FujiDynEngine* engine);
FUJI_DYN_EXPORT void    fuji_dyn_set_live_state_buf(FujiDynEngine* engine, FujiDynLiveState* buf);
FUJI_DYN_EXPORT int32_t fuji_dyn_run_cluster_fast(FujiDynEngine* engine, const FujiDynClusterRequest* req, FujiDynClusterResult* out);
FUJI_DYN_EXPORT void    fuji_dyn_set_gic_shadow_ptr(FujiDynEngine* engine, uint32_t gicd_base, uint32_t gicc_base, FujiDynGicShadow* shared_shadow);
FUJI_DYN_EXPORT void    fuji_dyn_clear_gic_shadow(FujiDynEngine* engine);
FUJI_DYN_EXPORT void    fuji_dyn_set_timer_irq_shadow_ptr(FujiDynEngine* engine, FujiDynTimerShadow* rw_timer_shadow, FujiDynTimerIrqShadow* irq_shadow);
FUJI_DYN_EXPORT void    fuji_dyn_clear_timer_irq_shadow(FujiDynEngine* engine);
FUJI_DYN_EXPORT uintptr_t fuji_dyn_get_fastmem_base(FujiDynEngine* engine);
FUJI_DYN_EXPORT uint32_t  fuji_dyn_fastmem_is_dual_view(FujiDynEngine* engine);
FUJI_DYN_EXPORT void*     fuji_dyn_get_ram_host_ptr(FujiDynEngine* engine, uint32_t phys_base);
FUJI_DYN_EXPORT void    fuji_dyn_get_mmio_stats(FujiDynEngine* engine, FujiDynMmioStats* out);
FUJI_DYN_EXPORT void    fuji_dyn_reset_mmio_stats(FujiDynEngine* engine);

/* ---- Multi-Core Host-Threaded SMP Execution (ABI v1 backward-compatible extension) ---- */
/* Default is 0 (single-threaded round-robin). When enabled (1) and >= 2 cores are runnable,
 * fuji_dyn_run_cluster_fast() executes runnable cores concurrently on persistent host threads
 * synchronized at calibrated epochs, proxying all non-fastpath MMIO upcalls strictly onto the
 * caller thread. Single-active-core execution bypasses worker threads with zero overhead. */
FUJI_DYN_EXPORT void     fuji_dyn_set_smp_host_threads(FujiDynEngine* engine, uint32_t enabled);
FUJI_DYN_EXPORT uint32_t fuji_dyn_get_smp_host_threads(FujiDynEngine* engine);

/* ---- Generic Declarative Native MMIO Read-Mirror Bank (ABI v1 backward-compatible extension) ---- */
FUJI_DYN_EXPORT int32_t  fuji_dyn_register_mmio_mirror(FujiDynEngine* engine, uint32_t phys_base, uint32_t window_size, const void* mirror_block);
FUJI_DYN_EXPORT int32_t  fuji_dyn_unregister_mmio_mirror(FujiDynEngine* engine, uint32_t phys_base);
FUJI_DYN_EXPORT void     fuji_dyn_clear_mmio_mirrors(FujiDynEngine* engine);

#ifdef __cplusplus
}
#endif

#endif /* FUJI_DYNARMIC_H */
