#include "fuji_dynarmic.h"

#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <thread>
#include <vector>
#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#endif

#define CHECK(cond, msg)                                                      \
    do {                                                                      \
        if (!(cond)) {                                                        \
            std::fprintf(stderr, "[FAIL] %s:%d: %s\n", __FILE__, __LINE__,    \
                         (msg));                                              \
            std::exit(1);                                                     \
        }                                                                     \
    } while (0)

struct TestMmioState {
    uint32_t last_read_addr = 0;
    uint32_t last_write_addr = 0;
    uint64_t last_write_val = 0;
    uint32_t read_count = 0;
    uint32_t write_count = 0;
    uint32_t halt_on_write_addr = 0;
    uint64_t read_vticks[8]{};
    uint32_t last_read_sizes[8]{};
    uint32_t last_write_sizes[8]{};
    uint32_t last_write_addrs[8]{};
    uint64_t last_write_vals[8]{};
    FujiDynEngine* engine = nullptr;
};

static uint64_t test_mmio_read(void* user, uint32_t phys_addr, uint32_t size) {
    auto* st = static_cast<TestMmioState*>(user);
    st->last_read_addr = phys_addr;
    if (st->read_count < 8) {
        st->last_read_sizes[st->read_count] = size;
        if (st->engine) st->read_vticks[st->read_count] = fuji_dyn_get_current_vtick(st->engine);
    }
    st->read_count++;
    return 0xCAFE0000u | (phys_addr & 0xFFFFu);
}

static void test_mmio_write(void* user, uint32_t phys_addr, uint32_t size, uint64_t val) {
    auto* st = static_cast<TestMmioState*>(user);
    st->last_write_addr = phys_addr;
    st->last_write_val = val;
    if (st->write_count < 8) {
        st->last_write_sizes[st->write_count] = size;
        st->last_write_addrs[st->write_count] = phys_addr;
        st->last_write_vals[st->write_count] = val;
    }
    st->write_count++;
    if (st->halt_on_write_addr != 0 && phys_addr == st->halt_on_write_addr && st->engine) {
        fuji_dyn_halt_run(st->engine);
    }
}

static void write_u32(FujiDynEngine* eng, uint32_t pa, uint32_t val) {
    CHECK(fuji_dyn_mem_write(eng, pa, &val, 4) == 0, "write_u32 failed");
}

static uint32_t read_u32(FujiDynEngine* eng, uint32_t pa) {
    uint32_t v = 0;
    CHECK(fuji_dyn_mem_read(eng, pa, &v, 4) == 0, "read_u32 failed");
    return v;
}

static void test_arm32_and_thumb2(FujiDynEngine* eng) {
    /* ARM32 loop: r0 = sum(1..100) = 5050, then BX r2 to Thumb-2 helper that adds 1000 -> 6050, BX lr -> WFI */
    const uint32_t code_base = 0x00010000u;
    const uint32_t thumb_base = 0x00010100u;
    const uint32_t arm_code[] = {
        0xE3A00000u, /* mov r0, #0 */
        0xE3A01064u, /* mov r1, #100 */
        /* loop (pc = +8): */
        0xE0800001u, /* add r0, r0, r1 */
        0xE2511001u, /* subs r1, r1, #1 */
        0x1AFFFFFCu, /* bne loop (-16 -> +8) */
        0xE59F2008u, /* ldr r2, [pc, #8] -> +0x24 = thumb_base | 1 */
        0xE12FFF32u, /* blx r2 */
        0xE320F003u, /* wfi */
        0xEAFFFFFEu, /* b . */
        thumb_base | 1u,
    };
    for (size_t i = 0; i < sizeof(arm_code) / 4; ++i) {
        write_u32(eng, code_base + static_cast<uint32_t>(i * 4), arm_code[i]);
    }
    /* Thumb-2 helper:
     *   F200 30E8 : addw r0, r0, #1000 (0x3E8 = i:imm3:imm8 = 0:011:11101000 -> hw1=0xF200, hw2=0x30E8)
     *   4770      : bx lr
     */
    const uint16_t thumb_code[] = {0xF200u, 0x30E8u, 0x4770u, 0xBF00u};
    CHECK(fuji_dyn_mem_write(eng, thumb_base, thumb_code, sizeof(thumb_code)) == 0, "write thumb");

    fuji_dyn_reset_core(eng, 0);
    fuji_dyn_set_reg(eng, 0, 15, code_base);
    FujiDynRunResult res{};
    CHECK(fuji_dyn_run_fast(eng, 0, 10000, 0, 0, &res) == 0, "run_fast arm32+thumb2");
    CHECK(res.stop_reason == FUJI_DYN_STOP_WFI, "expected STOP_WFI");
    CHECK(fuji_dyn_get_reg(eng, 0, 0) == 6050u, "ARM32+Thumb-2 sum != 6050");
    std::printf("  [PASS] 1. ARM32 + Thumb-2 interworking (r0=%u, ticks=%llu)\n",
                fuji_dyn_get_reg(eng, 0, 0), static_cast<unsigned long long>(res.executed_ticks));
}

static void test_vfpv4_and_neon(FujiDynEngine* eng) {
    /* VFPv4 double add + NEON VADD.I32 q0, q1, q2 + VMRS r0, FPEXC */
    const uint32_t base = 0x00011000u;
    const uint32_t code[] = {
        0xEE310B02u, /* vadd.f64 d0, d1, d2 */
        0xF2220844u, /* vadd.i32 q0, q1, q2 -> overwrites d0,d1; wait use q3=q1+q2! */
        0xEEF80A10u, /* vmrs r0, fpexc */
        0xE320F003u, /* wfi */
    };
    /* Fix NEON op to vadd.i32 q3 (d6,d7), q1 (d2,d3), q2 (d4,d5):
     * 1111 0010 0 D 10 Vn Vd 1000 N Q M 0 Vm
     * D=0, Vn=2 (q1), Vd=6 (q3), N=0, Q=1, M=0, Vm=4 (q2) -> 0xF2226844
     */
    uint32_t patched[4];
    std::memcpy(patched, code, sizeof(code));
    patched[1] = 0xF2226844u; /* vadd.i32 q3, q1, q2 */
    CHECK(fuji_dyn_mem_write(eng, base, patched, sizeof(patched)) == 0, "write vfp/neon");

    fuji_dyn_reset_core(eng, 0);
    /* d1 = 1.5, d2 = 2.25 -> d0 = 3.75 (0x400E000000000000) */
    uint64_t f1 = 0, f2 = 0, fexp = 0;
    double a = 1.5, b = 2.25, exp = 3.75;
    std::memcpy(&f1, &a, 8);
    std::memcpy(&f2, &b, 8);
    std::memcpy(&fexp, &exp, 8);
    fuji_dyn_set_dreg(eng, 0, 1, f1);
    fuji_dyn_set_dreg(eng, 0, 2, f2);
    /* q1 = {10, 20, 30, 40}, q2 = {1, 2, 3, 4} -> q3 (d6,d7) = {11, 22, 33, 44} */
    fuji_dyn_set_dreg(eng, 0, 2, (20ull << 32) | 10ull);
    fuji_dyn_set_dreg(eng, 0, 3, (40ull << 32) | 30ull);
    fuji_dyn_set_dreg(eng, 0, 4, (2ull << 32) | 1ull);
    fuji_dyn_set_dreg(eng, 0, 5, (4ull << 32) | 3ull);
    /* Re-run d0 = d8 + d9 so q1 doesn't collide with d2 */
    patched[0] = 0xEE380B09u; /* vadd.f64 d0, d8, d9 */
    CHECK(fuji_dyn_mem_write(eng, base, patched, sizeof(patched)) == 0, "write vfp/neon 2");
    fuji_dyn_invalidate_range(eng, base, sizeof(patched));
    fuji_dyn_set_dreg(eng, 0, 8, f1);
    fuji_dyn_set_dreg(eng, 0, 9, f2);
    fuji_dyn_set_reg(eng, 0, 15, base);

    FujiDynRunResult res{};
    CHECK(fuji_dyn_run_fast(eng, 0, 1000, 0, 0, &res) == 0, "run vfp/neon");
    CHECK(res.stop_reason == FUJI_DYN_STOP_WFI, "vfp/neon WFI");
    CHECK(fuji_dyn_get_dreg(eng, 0, 0) == fexp, "VFPv4 vadd.f64 mismatch");
    CHECK(fuji_dyn_get_dreg(eng, 0, 6) == ((22ull << 32) | 11ull), "NEON q3 low mismatch");
    CHECK(fuji_dyn_get_dreg(eng, 0, 7) == ((44ull << 32) | 33ull), "NEON q3 high mismatch");
    CHECK(fuji_dyn_get_reg(eng, 0, 0) == 0x40000000u, "VMRS FPEXC mismatch");
    std::printf("  [PASS] 2. VFPv4 (f64) + NEON (vadd.i32 q3) + VMRS FPEXC\n");
}

static void test_cp15_and_4core_mpidr(FujiDynEngine* eng) {
    const uint32_t base = 0x00012000u;
    const uint32_t code[] = {
        0xEE100F10u, /* mrc p15, 0, r0, c0, c0, 0 (MIDR) */
        0xEE101FB0u, /* mrc p15, 0, r1, c0, c0, 5 (MPIDR) */
        0xEE9F2F10u, /* mrc p15, 4, r2, c15, c0, 0 (CBAR) */
        0xEE0D3F50u, /* mcr p15, 0, r3, c13, c0, 2 (TPIDRURW) */
        0xEE1D4F50u, /* mrc p15, 0, r4, c13, c0, 2 (TPIDRURW) */
        0xEC565F0Eu, /* mrrc p15, 0, r5, r6, c14 (CNTPCT) */
        0xE320F003u, /* wfi */
    };
    CHECK(fuji_dyn_mem_write(eng, base, code, sizeof(code)) == 0, "write cp15");

    for (uint32_t core = 0; core < 4; ++core) {
        fuji_dyn_reset_core(eng, core);
        fuji_dyn_set_reg(eng, core, 3, 0x12340000u + core);
        fuji_dyn_set_reg(eng, core, 15, base);
        FujiDynRunResult res{};
        CHECK(fuji_dyn_run_fast(eng, core, 1000, 100000ull + core, 0, &res) == 0, "run cp15");
        CHECK(res.stop_reason == FUJI_DYN_STOP_WFI, "cp15 WFI");
        CHECK(fuji_dyn_get_reg(eng, core, 0) == 0x410FC075u, "MIDR mismatch");
        CHECK(fuji_dyn_get_reg(eng, core, 1) == (0x80000000u | core), "MPIDR mismatch");
        CHECK(fuji_dyn_get_reg(eng, core, 2) == 0xFFFF0000u, "CBAR mismatch");
        CHECK(fuji_dyn_get_reg(eng, core, 4) == (0x12340000u + core), "TPIDRURW mismatch");
    }
    std::printf("  [PASS] 3. 4-Core CP15 (MIDR/MPIDR/CBAR/TPIDRURW/CNTPCT)\n");
}

static void test_mmu_short_descriptor(FujiDynEngine* eng) {
    /* L1 table at PA 0x00080000 (16 KiB), L2 table at PA 0x00084000 (1 KiB) */
    const uint32_t l1_base = 0x00080000u;
    const uint32_t l2_base = 0x00084000u;
    std::vector<uint8_t> zeros(0x5000, 0);
    CHECK(fuji_dyn_mem_write(eng, l1_base, zeros.data(), static_cast<uint32_t>(zeros.size())) == 0, "zero pt");

    /* Identity map 0x00000000..0x000FFFFF (1 MiB section, AP=11 RWX, dom=0) */
    write_u32(eng, l1_base + (0x000u << 2), 0x00000000u | (3u << 10) | 2u);
    /* Map VA 0x80000000 -> PA 0x00100000 (1 MiB section, AP=11 RWX) */
    write_u32(eng, l1_base + (0x800u << 2), 0x00100000u | (3u << 10) | 2u);
    /* Map VA 0x90000000 via L2 table at l2_base */
    write_u32(eng, l1_base + (0x900u << 2), l2_base | 1u);
    /* L2[1]: VA 0x90001000 -> PA 0x00102000 (4 KiB small page, AP=11 RW, XN=1) */
    write_u32(eng, l2_base + (1u << 2), 0x00102000u | (3u << 4) | 2u | 1u /* XN */);
    /* L2[2]: VA 0x90002000 -> PA 0x00103000 (4 KiB small page, AP2=1, AP10=01 -> RO!) */
    write_u32(eng, l2_base + (2u << 2), 0x00103000u | (1u << 9) | (1u << 4) | 2u);

    write_u32(eng, 0x00100040u, 0xDEADBEEFu);
    write_u32(eng, 0x00102008u, 0x11223344u);

    /* Enable MMU via guest CP15 MCR, read VA 0x80000040 & VA 0x90001008, write VA 0x80000044 */
    const uint32_t base = 0x00013000u;
    const uint32_t code[] = {
        0xEE020F10u, /* mcr p15, 0, r0, c2, c0, 0 (TTBR0) */
        0xEE031F10u, /* mcr p15, 0, r1, c3, c0, 0 (DACR) */
        0xEE112F10u, /* mrc p15, 0, r2, c1, c0, 0 (SCTLR) */
        0xE3822001u, /* orr r2, r2, #1 (MMU enable) */
        0xEE012F10u, /* mcr p15, 0, r2, c1, c0, 0 (SCTLR) */
        0xE5934040u, /* ldr r4, [r3, #0x40] (VA 0x80000040 -> 0xDEADBEEF) */
        0xE5834044u, /* str r4, [r3, #0x44] (VA 0x80000044 -> PA 0x00100044) */
        0xE5956008u, /* ldr r6, [r5, #8]    (VA 0x90001008 -> 0x11223344) */
        0xE320F003u, /* wfi */
    };
    CHECK(fuji_dyn_mem_write(eng, base, code, sizeof(code)) == 0, "write mmu code");

    fuji_dyn_reset_core(eng, 0);
    fuji_dyn_set_reg(eng, 0, 0, l1_base);
    fuji_dyn_set_reg(eng, 0, 1, 0x55555555u); /* All Client domains */
    fuji_dyn_set_reg(eng, 0, 3, 0x80000000u);
    fuji_dyn_set_reg(eng, 0, 5, 0x90001000u);
    fuji_dyn_set_reg(eng, 0, 15, base);

    FujiDynRunResult res{};
    CHECK(fuji_dyn_run_fast(eng, 0, 10000, 0, 0, &res) == 0, "run mmu");
    CHECK(res.stop_reason == FUJI_DYN_STOP_WFI, "mmu WFI");
    CHECK(fuji_dyn_get_reg(eng, 0, 4) == 0xDEADBEEFu, "L1 section read mismatch");
    CHECK(read_u32(eng, 0x00100044u) == 0xDEADBEEFu, "L1 section write mismatch");
    CHECK(fuji_dyn_get_reg(eng, 0, 6) == 0x11223344u, "L2 small page read mismatch");

    uint32_t pa = 0;
    CHECK(fuji_dyn_translate(eng, 0, 0x90001008u, FUJI_DYN_PERM_READ, &pa) == 0 && pa == 0x00102008u, "translate L2 R");
    CHECK(fuji_dyn_translate(eng, 0, 0x90001008u, FUJI_DYN_PERM_EXEC, &pa) < 0, "translate L2 XN should fail");
    CHECK(fuji_dyn_translate(eng, 0, 0x90002000u, FUJI_DYN_PERM_WRITE, &pa) < 0, "translate L2 RO write should fail");
    std::printf("  [PASS] 4. ARMv7 Short-Descriptor MMU (L1 section, L2 page, XN, RO, fastmem)\n");
}

static void test_exclusive_4core(FujiDynEngine* eng) {
    /* Core 0: LDREX r1, [r0]; WFI; STREX r2, r3, [r0]; WFI */
    /* Core 1: LDREX r1, [r0]; STREX r2, r3, [r0]; WFI (interferes with Core 0!) */
    const uint32_t shared_pa = 0x00110000u;
    write_u32(eng, shared_pa, 0x00000001u);

    const uint32_t c0_base = 0x00014000u;
    const uint32_t c0_code[] = {
        0xE1901F9Fu, /* ldrex r1, [r0] */
        0xE320F003u, /* wfi */
        0xE1802F93u, /* strex r2, r3, [r0] */
        0xE320F003u, /* wfi */
    };
    const uint32_t c1_base = 0x00014100u;
    const uint32_t c1_code[] = {
        0xE1901F9Fu, /* ldrex r1, [r0] */
        0xE1802F93u, /* strex r2, r3, [r0] */
        0xE320F003u, /* wfi */
    };
    CHECK(fuji_dyn_mem_write(eng, c0_base, c0_code, sizeof(c0_code)) == 0, "write c0 excl");
    CHECK(fuji_dyn_mem_write(eng, c1_base, c1_code, sizeof(c1_code)) == 0, "write c1 excl");

    fuji_dyn_reset_core(eng, 0);
    fuji_dyn_reset_core(eng, 1);
    fuji_dyn_set_reg(eng, 0, 0, shared_pa);
    fuji_dyn_set_reg(eng, 0, 3, 0xAAAA0000u);
    fuji_dyn_set_reg(eng, 0, 15, c0_base);
    fuji_dyn_set_reg(eng, 1, 0, shared_pa);
    fuji_dyn_set_reg(eng, 1, 3, 0xBBBB0000u);
    fuji_dyn_set_reg(eng, 1, 15, c1_base);

    FujiDynRunResult r0{}, r1{};
    /* Step 1: Core 0 LDREX -> WFI */
    CHECK(fuji_dyn_run_fast(eng, 0, 1000, 0, 0, &r0) == 0 && r0.stop_reason == FUJI_DYN_STOP_WFI, "c0 ldrex");
    CHECK(fuji_dyn_get_reg(eng, 0, 1) == 1u, "c0 ldrex val");
    /* Step 2: Core 1 LDREX + STREX succeeds (r2 == 0), invalidating Core 0's monitor */
    CHECK(fuji_dyn_run_fast(eng, 1, 1000, 10, 0, &r1) == 0 && r1.stop_reason == FUJI_DYN_STOP_WFI, "c1 strex");
    CHECK(fuji_dyn_get_reg(eng, 1, 2) == 0u, "c1 strex should succeed");
    CHECK(read_u32(eng, shared_pa) == 0xBBBB0000u, "c1 wrote shared_pa");
    /* Step 3: Core 0 STREX MUST FAIL (r2 == 1) */
    CHECK(fuji_dyn_run_fast(eng, 0, 1000, 20, 0, &r0) == 0 && r0.stop_reason == FUJI_DYN_STOP_WFI, "c0 strex");
    CHECK(fuji_dyn_get_reg(eng, 0, 2) == 1u, "c0 strex must fail after c1 write");
    CHECK(read_u32(eng, shared_pa) == 0xBBBB0000u, "shared_pa unchanged");
    std::printf("  [PASS] 5. 4-Core ExclusiveMonitor (LDREX/STREX cross-core invalidation)\n");
}

static void test_banked_svc_irq_mmio(FujiDynEngine* eng, TestMmioState* mmio) {
    /* Vector table at VBAR = 0x00020000:
     * +0x08 (SVC): b svc_handler (+0x100)
     * +0x18 (IRQ): b irq_handler (+0x200)
     */
    const uint32_t vbar = 0x00020000u;
    write_u32(eng, vbar + 0x08u, 0xEA00003Cu); /* b 0x00020100: (0x100 - 0x08 - 8)/4 = 0x3C */
    write_u32(eng, vbar + 0x18u, 0xEA000078u); /* b 0x00020200: (0x200 - 0x18 - 8)/4 = 0x78 */

    /* SVC handler at 0x00020100: add r0, r0, #7; movs pc, lr */
    const uint32_t svc_handler[] = {
        0xE2800007u, /* add r0, r0, #7 */
        0xE1B0F00Eu, /* movs pc, lr (exception return!) */
    };
    CHECK(fuji_dyn_mem_write(eng, vbar + 0x100u, svc_handler, sizeof(svc_handler)) == 0, "write svc handler");

    /* IRQ handler at 0x00020200: write 0x55AA1234 to MMIO 0x40000010, then subs pc, lr, #4 */
    const uint32_t irq_handler[] = {
        0xE59F1008u, /* ldr r1, [pc, #8] -> +0x10 = 0x40000010 */
        0xE59F2008u, /* ldr r2, [pc, #8] -> +0x14 = 0x55AA1234 */
        0xE5812000u, /* str r2, [r1] */
        0xE25EF004u, /* subs pc, lr, #4 (IRQ exception return!) */
        0x40000010u,
        0x55AA1234u,
    };
    CHECK(fuji_dyn_mem_write(eng, vbar + 0x200u, irq_handler, sizeof(irq_handler)) == 0, "write irq handler");

    /* Main program at 0x00015000:
     *   msr cpsr_c, #0xD2 (IRQ mode, I=1, F=1) -> mov sp, #0x00040000
     *   msr cpsr_c, #0x5F (SYS mode, I=0, F=1) -> mov sp, #0x00050000
     *   mov r0, #100
     *   svc #0x2A
     *   wfi
     */
    const uint32_t main_base = 0x00015000u;
    const uint32_t main_code[] = {
        0xE321F0D2u, /* msr cpsr_c, #0xD2 (IRQ) */
        0xE3A0D701u, /* mov sp, #0x40000 */
        0xE321F05Fu, /* msr cpsr_c, #0x5F (SYS, IRQ unmasked!) */
        0xE3A0D805u, /* mov sp, #0x50000 */
        0xE3A00064u, /* mov r0, #100 */
        0xEF00002Au, /* svc #0x2A */
        0xE320F003u, /* wfi */
    };
    CHECK(fuji_dyn_mem_write(eng, main_base, main_code, sizeof(main_code)) == 0, "write main");

    fuji_dyn_reset_core(eng, 0);
    fuji_dyn_set_cp15(eng, 0, 0, 12, 0, 0, vbar); /* VBAR = 0x00020000 */
    fuji_dyn_set_cp15(eng, 0, 0, 1, 0, 0, 0x00C50878u & ~(1u << 13)); /* SCTLR.V = 0 */
    fuji_dyn_set_reg(eng, 0, 15, main_base);

    FujiDynRunResult res{};
    CHECK(fuji_dyn_run_fast(eng, 0, 1000, 0, 0, &res) == 0, "run to svc");
    CHECK(res.stop_reason == FUJI_DYN_STOP_SVC && res.svc_imm == 0x2Au, "expected STOP_SVC #0x2A");
    CHECK(fuji_dyn_get_banked_reg(eng, 0, FUJI_DYN_MODE_IRQ, FUJI_DYN_BANKED_SP) == 0x40000u, "banked SP_irq mismatch");
    CHECK(fuji_dyn_get_banked_reg(eng, 0, FUJI_DYN_MODE_SYS, FUJI_DYN_BANKED_SP) == 0x50000u, "banked SP_sys mismatch");

    /* Deliver SVC -> runs handler -> MOVS pc, lr returns to WFI */
    CHECK(fuji_dyn_deliver_svc(eng, 0) == 0, "deliver_svc");
    CHECK(fuji_dyn_run_fast(eng, 0, 1000, res.virtual_tick, 0, &res) == 0, "run after svc");
    CHECK(res.stop_reason == FUJI_DYN_STOP_WFI, "expected WFI after SVC return");
    CHECK(fuji_dyn_get_reg(eng, 0, 0) == 107u, "SVC handler r0 != 107");
    CHECK((fuji_dyn_get_cpsr(eng, 0) & 0x1Fu) == FUJI_DYN_MODE_SYS, "mode not restored to SYS");

    /* Deliver IRQ -> IRQ handler writes MMIO 0x40000010 = 0x55AA1234 -> SUBS pc, lr, #4 returns */
    uint32_t saved_pc = fuji_dyn_get_reg(eng, 0, 15);
    write_u32(eng, saved_pc, 0xE320F003u); /* wfi right after return */
    fuji_dyn_invalidate_range(eng, saved_pc, 4);
    CHECK(fuji_dyn_deliver_irq(eng, 0) == 0, "deliver_irq");
    CHECK(fuji_dyn_run_fast(eng, 0, 1000, res.virtual_tick, 0, &res) == 0, "run irq");
    CHECK(res.stop_reason == FUJI_DYN_STOP_WFI, "expected WFI after IRQ return");
    CHECK(mmio->write_count >= 1 && mmio->last_write_addr == 0x40000010u && mmio->last_write_val == 0x55AA1234u,
          "IRQ MMIO write mismatch");
    CHECK((fuji_dyn_get_cpsr(eng, 0) & 0x1Fu) == FUJI_DYN_MODE_SYS, "IRQ return mode mismatch");
    std::printf("  [PASS] 6. Banked modes + MSR/CPS + SVC + IRQ + MMIO + MOVS/SUBS exception return\n");
}

static void test_mmio_halt_same_block_and_vtick(FujiDynEngine* eng, TestMmioState* mmio) {
    /* 1. Verify MMIO write triggering fuji_dyn_halt_run() does NOT drop a subsequent RAM store
     *    in the SAME basic block (even on an uncached cold RAM page via slow mem_write_impl). */
    const uint32_t base = 0x00017000u;
    const uint32_t cold_ram = 0x00180000u; /* distinct 4KiB page not yet in page_table */
    write_u32(eng, cold_ram, 0u);
    const uint32_t code1[] = {
        0xE5801000u, /* str r1, [r0] (MMIO 0x40000020 -> triggers fuji_dyn_halt_run!) */
        0xE5823000u, /* str r3, [r2] (cold RAM write in SAME basic block: MUST NOT BE DROPPED!) */
        0xE5924000u, /* ldr r4, [r2] (cold RAM read in SAME basic block: MUST SEE r3!) */
        0xE320F003u, /* wfi */
    };
    CHECK(fuji_dyn_mem_write(eng, base, code1, sizeof(code1)) == 0, "write mmio halt test");
    fuji_dyn_reset_core(eng, 0);
    mmio->halt_on_write_addr = 0x40000020u;
    fuji_dyn_set_reg(eng, 0, 0, 0x40000020u);
    fuji_dyn_set_reg(eng, 0, 1, 0x10201011u);
    fuji_dyn_set_reg(eng, 0, 2, cold_ram);
    fuji_dyn_set_reg(eng, 0, 3, 0xABCD9876u);
    fuji_dyn_set_reg(eng, 0, 15, base);

    FujiDynRunResult res{};
    CHECK(fuji_dyn_run_fast(eng, 0, 1000, 100, 0, &res) == 0, "run mmio halt test");
    mmio->halt_on_write_addr = 0;
    CHECK(res.stop_reason == FUJI_DYN_STOP_MMIO_HALT, "expected STOP_MMIO_HALT");
    CHECK(read_u32(eng, cold_ram) == 0xABCD9876u, "post-MMIO-halt RAM store in same block was dropped!");
    CHECK(fuji_dyn_get_reg(eng, 0, 4) == 0xABCD9876u, "post-MMIO-halt RAM load in same block was dropped!");

    /* 2. Verify consecutive MMIO reads across basic blocks observe advancing virtual_tick */
    const uint32_t base2 = 0x00017100u;
    const uint32_t code2[] = {
        0xE5901000u, /* ldr r1, [r0] (MMIO read #1) */
        0xE2822001u, /* add r2, r2, #1 */
        0xE3520003u, /* cmp r2, #3 */
        0x1AFFFFFBu, /* bne base2 (3 iterations -> 3 MMIO reads across separate blocks) */
        0xE320F003u, /* wfi */
    };
    CHECK(fuji_dyn_mem_write(eng, base2, code2, sizeof(code2)) == 0, "write vtick test");
    fuji_dyn_reset_core(eng, 0);
    mmio->read_count = 0;
    fuji_dyn_set_reg(eng, 0, 0, 0x40000030u);
    fuji_dyn_set_reg(eng, 0, 2, 0u);
    fuji_dyn_set_reg(eng, 0, 15, base2);

    CHECK(fuji_dyn_run_fast(eng, 0, 1000, 5000, 0, &res) == 0, "run vtick test");
    CHECK(res.stop_reason == FUJI_DYN_STOP_WFI, "vtick test WFI");
    CHECK(mmio->read_count == 3u, "expected 3 MMIO reads");
    CHECK(mmio->read_vticks[1] > mmio->read_vticks[0] && mmio->read_vticks[2] > mmio->read_vticks[1],
          "virtual_tick did not advance between consecutive MMIO reads!");
    std::printf("  [PASS] 7. Post-MMIO-halt same-block RAM integrity + live MMIO virtual_tick (%llu -> %llu -> %llu)\n",
                static_cast<unsigned long long>(mmio->read_vticks[0]),
                static_cast<unsigned long long>(mmio->read_vticks[1]),
                static_cast<unsigned long long>(mmio->read_vticks[2]));
}

static void test_timer_ro_mmio_and_64bit_split(FujiDynEngine* eng, TestMmioState* mmio) {
    const uint32_t timer_base = 0x40001000u;
    CHECK(fuji_dyn_map_mmio(eng, timer_base, 0x1000u) == 0, "map timer mmio");

    FujiDynTimerShadow shadow{};
    shadow.active = 1u;
    shadow.channels[0].stored = 0x12345678ull;
    shadow.channels[0].reload = 0x00001000ull;
    shadow.channels[0].enabled = 0u;
    shadow.channels[0].has_deadline = 0u;
    shadow.channels[7].stored = 1000ull;
    shadow.channels[7].reload = 999ull;
    shadow.channels[7].deadline = 6000ull;
    shadow.channels[7].enabled = 1u;
    shadow.channels[7].has_deadline = 1u;
    fuji_dyn_set_timer_shadow(eng, timer_base, &shadow);
    fuji_dyn_reset_mmio_stats(eng);

    /* Read ctrl(0x00), ch0 val(0x10), ch0 reload(0x14), then loop 3x reading ch7(0x48) into RAM */
    const uint32_t base = 0x00018000u;
    const uint32_t out_buf = 0x00190000u;
    const uint32_t code1[] = {
        0xE5901000u, /* ldr r1, [r0, #0x00] -> ctrl == 0x80 */
        0xE5902010u, /* ldr r2, [r0, #0x10] -> ch0 stored == 0x12345678 */
        0xE5903014u, /* ldr r3, [r0, #0x14] -> ch0 reload == 0x1000 */
        0xE3A05000u, /* mov r5, #0 */
        /* loop (+0x10): */
        0xE5906048u, /* ldr r6, [r0, #0x48] -> ch7 live value(now) */
        0xE4846004u, /* str r6, [r4], #4 */
        0xE2855001u, /* add r5, r5, #1 */
        0xE3550003u, /* cmp r5, #3 */
        0x1AFFFFFAu, /* bne loop (-24 -> +0x10) */
        0xE5801000u, /* str r1, [r0, #0x00] -> write MUST hit upcall! */
        0xE320F003u, /* wfi */
    };
    CHECK(fuji_dyn_mem_write(eng, base, code1, sizeof(code1)) == 0, "write timer fastpath test");
    fuji_dyn_reset_core(eng, 0);
    mmio->read_count = 0;
    mmio->write_count = 0;
    fuji_dyn_set_reg(eng, 0, 0, timer_base);
    fuji_dyn_set_reg(eng, 0, 4, out_buf);
    fuji_dyn_set_reg(eng, 0, 15, base);

    FujiDynRunResult res{};
    CHECK(fuji_dyn_run_fast(eng, 0, 5000, 5000ull, 0, &res) == 0, "run timer fastpath");
    CHECK(res.stop_reason == FUJI_DYN_STOP_WFI, "timer fastpath WFI");
    CHECK(fuji_dyn_get_reg(eng, 0, 1) == 0x80u, "timer ctrl mismatch");
    CHECK(fuji_dyn_get_reg(eng, 0, 2) == 0x12345678u, "timer ch0 stored mismatch");
    CHECK(fuji_dyn_get_reg(eng, 0, 3) == 0x00001000u, "timer ch0 reload mismatch");
    uint32_t v0 = read_u32(eng, out_buf + 0);
    uint32_t v1 = read_u32(eng, out_buf + 4);
    uint32_t v2 = read_u32(eng, out_buf + 8);
    CHECK(v0 == 999u && v1 < v0 && v2 < v1, "timer ch7 live countdown did not advance strictly");
    CHECK(mmio->read_count == 0u, "timer fastpath should produce ZERO FFM read upcalls");
    CHECK(mmio->write_count == 1u && mmio->last_write_addr == timer_base, "timer write must hit upcall");

    /* Verify wrap formula when now >= deadline (now=6005, deadline=6000, reload=999 -> 999 - 5 = 994) */
    const uint32_t base_wrap = 0x00018100u;
    const uint32_t code_wrap[] = {
        0xE5907048u, /* ldr r7, [r0, #0x48] */
        0xE320F003u, /* wfi */
    };
    CHECK(fuji_dyn_mem_write(eng, base_wrap, code_wrap, sizeof(code_wrap)) == 0, "write timer wrap");
    fuji_dyn_set_reg(eng, 0, 15, base_wrap);
    CHECK(fuji_dyn_run_fast(eng, 0, 1000, 6005ull, 0, &res) == 0, "run timer wrap");
    CHECK(fuji_dyn_get_reg(eng, 0, 7) == 994u, "timer wrap formula mismatch");

    FujiDynMmioStats stats{};
    fuji_dyn_get_mmio_stats(eng, &stats);
    CHECK(stats.timer_fast_hits == 7u && stats.upcall_reads == 0u && stats.upcall_writes == 1u, "timer stats mismatch");
    fuji_dyn_clear_timer_shadow(eng);

    /* Part 2: RO-MMIO shadow cache + write auto-invalidation + 64-bit LDRD/STRD split in C++ */
    CHECK(fuji_dyn_set_ro_mmio_word(eng, 0x40000100u, 0xDEADC0DEu) == 0, "set ro 0x100");
    CHECK(fuji_dyn_set_ro_mmio_word(eng, 0x40000104u, 0xBEEF4242u) == 0, "set ro 0x104");
    fuji_dyn_reset_mmio_stats(eng);
    mmio->read_count = 0;
    mmio->write_count = 0;

    const uint32_t base_ro = 0x00018200u;
    const uint32_t code_ro[] = {
        0xE5901000u, /* ldr r1, [r0, #0x00] -> RO hit 0xDEADC0DE */
        0xE5902004u, /* ldr r2, [r0, #0x04] -> RO hit 0xBEEF4242 */
        0xE5801000u, /* str r1, [r0, #0x00] -> invalidates 0x100 + write upcall */
        0xE5903000u, /* ldr r3, [r0, #0x00] -> MISS, read upcall 0xCAFE0100 */
        0xE5904004u, /* ldr r4, [r0, #0x04] -> still RO hit 0xBEEF4242 */
        0xE1C560F0u, /* strd r6, r7, [r5]   -> splits into 2x 32-bit writes to 0x40000200 & 0x204 */
        0xE1C580D0u, /* ldrd r8, r9, [r5]   -> splits into 2x 32-bit reads  of 0x40000200 & 0x204 */
        0xE320F003u, /* wfi */
    };
    CHECK(fuji_dyn_mem_write(eng, base_ro, code_ro, sizeof(code_ro)) == 0, "write ro+64bit test");
    fuji_dyn_reset_core(eng, 0);
    fuji_dyn_set_reg(eng, 0, 0, 0x40000100u);
    fuji_dyn_set_reg(eng, 0, 5, 0x40000200u);
    fuji_dyn_set_reg(eng, 0, 6, 0x11112222u);
    fuji_dyn_set_reg(eng, 0, 7, 0x33334444u);
    fuji_dyn_set_reg(eng, 0, 15, base_ro);

    CHECK(fuji_dyn_run_fast(eng, 0, 5000, 0, 0, &res) == 0, "run ro+64bit");
    CHECK(res.stop_reason == FUJI_DYN_STOP_WFI, "ro+64bit WFI");
    CHECK(fuji_dyn_get_reg(eng, 0, 1) == 0xDEADC0DEu, "RO hit #1 mismatch");
    CHECK(fuji_dyn_get_reg(eng, 0, 2) == 0xBEEF4242u, "RO hit #2 mismatch");
    CHECK(fuji_dyn_get_reg(eng, 0, 3) == 0xCAFE0100u, "post-write RO invalidation missed");
    CHECK(fuji_dyn_get_reg(eng, 0, 4) == 0xBEEF4242u, "untouched RO slot #2 mismatch");
    CHECK(fuji_dyn_get_reg(eng, 0, 8) == 0xCAFE0200u, "LDRD split lo mismatch");
    CHECK(fuji_dyn_get_reg(eng, 0, 9) == 0xCAFE0204u, "LDRD split hi mismatch");
    CHECK(mmio->write_count == 3u && mmio->last_write_sizes[1] == 4u && mmio->last_write_sizes[2] == 4u &&
          mmio->last_write_addrs[1] == 0x40000200u && mmio->last_write_vals[1] == 0x11112222u &&
          mmio->last_write_addrs[2] == 0x40000204u && mmio->last_write_vals[2] == 0x33334444u,
          "STRD 64-bit MMIO split mismatch");
    CHECK(mmio->read_count == 3u && mmio->last_read_sizes[1] == 4u && mmio->last_read_sizes[2] == 4u,
          "LDRD 64-bit MMIO split size mismatch");

    fuji_dyn_get_mmio_stats(eng, &stats);
    CHECK(stats.ro_mmio_fast_hits == 3u && res.fast_mmio_hits == 3u &&
          stats.upcall_reads == 3u && stats.upcall_writes == 3u, "RO-MMIO stats mismatch");
    fuji_dyn_clear_ro_mmio(eng);
    CHECK(fuji_dyn_unmap_mmio(eng, timer_base, 0x1000u) == 0, "unmap timer mmio");
    std::printf("  [PASS] 8. Native C++ soc-timer fastpath (%u->%u->%u, wrap=994) + RO-MMIO shadow + 64-bit LDRD/STRD split\n",
                v0, v1, v2);
}

static void test_ro_ranges_live_state_shared_timer_and_batch(FujiDynEngine* eng, TestMmioState* mmio) {
    /* 1. Shared live-state buffer + RO ranges (zero & buffer) + batch RO + shared timer ptr */
    FujiDynLiveState ls{};
    fuji_dyn_set_live_state_buf(eng, &ls);

    const uint32_t ro_zero_base = 0x40004000u;
    const uint32_t ro_buf_base  = 0x40005000u;
    const uint32_t timer_base   = 0x40006000u;
    uint32_t ro_buf[4] = {0x11223344u, 0x55667788u, 0x99AABBCCu, 0xDDEEFF00u};
    CHECK(fuji_dyn_add_ro_mmio_range(eng, ro_zero_base, 0x1000u, nullptr) == 0, "add zero ro range");
    CHECK(fuji_dyn_add_ro_mmio_range(eng, ro_buf_base, sizeof(ro_buf), reinterpret_cast<const uint8_t*>(ro_buf)) == 0, "add buf ro range");

    uint32_t b_addrs[2] = {0x40007000u, 0x40007004u};
    uint32_t b_vals[2]  = {0xA1B2C3D4u, 0xE5F60718u};
    CHECK(fuji_dyn_set_ro_mmio_batch(eng, b_addrs, b_vals, 2u) == 2, "set ro batch");

    FujiDynTimerShadow shared_tmr{};
    shared_tmr.active = 1u;
    shared_tmr.channels[2].stored = 42u;
    shared_tmr.channels[2].reload = 500u;
    shared_tmr.channels[2].deadline = 10000ull;
    shared_tmr.channels[2].enabled = 1u;
    shared_tmr.channels[2].has_deadline = 1u;
    fuji_dyn_set_timer_shadow_ptr(eng, timer_base, &shared_tmr);
    fuji_dyn_reset_mmio_stats(eng);
    mmio->read_count = 0;
    mmio->write_count = 0;

    const uint32_t code_base = 0x00019000u;
    const uint32_t code[] = {
        0xE5901000u, /* ldr r1, [r0]       -> ro_zero == 0 */
        0xE5922004u, /* ldr r2, [r2, #4]   -> ro_buf[1] == 0x55667788 */
        0xE5933000u, /* ldr r3, [r3]       -> batch[0] == 0xA1B2C3D4 */
        0xE5945020u, /* ldr r5, [r4, #0x20]-> shared_tmr ch2 = 10000 - 9000 - 1 = 999 */
        0xE5966000u, /* ldr r6, [r6]       -> normal MMIO upcall (populates live_state) */
        0xE320F003u, /* wfi */
    };
    CHECK(fuji_dyn_mem_write(eng, code_base, code, sizeof(code)) == 0, "write range test");
    fuji_dyn_reset_core(eng, 0);
    fuji_dyn_set_reg(eng, 0, 0, ro_zero_base + 0x100u);
    fuji_dyn_set_reg(eng, 0, 2, ro_buf_base);
    fuji_dyn_set_reg(eng, 0, 3, b_addrs[0]);
    fuji_dyn_set_reg(eng, 0, 4, timer_base);
    fuji_dyn_set_reg(eng, 0, 6, 0x40000040u);
    fuji_dyn_set_reg(eng, 0, 15, code_base);

    FujiDynRunResult res{};
    CHECK(fuji_dyn_run_fast(eng, 0, 5000, 9000ull, 0, &res) == 0, "run range test");
    CHECK(res.stop_reason == FUJI_DYN_STOP_WFI, "range test WFI");
    CHECK(fuji_dyn_get_reg(eng, 0, 1) == 0u, "zero range read != 0");
    CHECK(fuji_dyn_get_reg(eng, 0, 2) == 0x55667788u, "buf range read mismatch");
    CHECK(fuji_dyn_get_reg(eng, 0, 3) == 0xA1B2C3D4u, "batch ro read mismatch");
    CHECK(fuji_dyn_get_reg(eng, 0, 5) == 999u, "shared timer ptr read mismatch");
    CHECK(mmio->read_count == 1u, "expected exactly 1 normal MMIO read upcall");
    CHECK(ls.active_core == 0u && ls.current_vtick >= 9000ull && (ls.valid_mask & 1u) != 0u, "live_state mismatch");

    fuji_dyn_clear_ro_mmio_ranges(eng);
    fuji_dyn_clear_ro_mmio(eng);
    fuji_dyn_clear_timer_shadow(eng);
    fuji_dyn_set_live_state_buf(eng, nullptr);
    std::printf("  [PASS] 9. Native RO-MMIO ranges (zero+buf), batch RO, shared timer ptr & live_state\n");
}

static void test_cluster_batch_and_tracepoint_fix(FujiDynEngine* eng) {
    /* Part A: 2 runnable cores compute in parallel round-robin inside C++, both enter WFI -> STOP_ALL_WAITING */
    const uint32_t c0_code_base = 0x0001A000u;
    const uint32_t c1_code_base = 0x0001A100u;
    const uint32_t loop_code[] = {
        0xE3A00000u, /* mov r0, #0 */
        0xE3A01C06u, /* mov r1, #1536 (exceeds 1024 quantum -> forces native switch!) */
        0xE2800001u, /* add r0, r0, #1 */
        0xE2511001u, /* subs r1, r1, #1 */
        0x1AFFFFFCu, /* bne -16 */
        0xE320F003u, /* wfi */
    };
    CHECK(fuji_dyn_mem_write(eng, c0_code_base, loop_code, sizeof(loop_code)) == 0, "write c0 cluster code");
    CHECK(fuji_dyn_mem_write(eng, c1_code_base, loop_code, sizeof(loop_code)) == 0, "write c1 cluster code");
    fuji_dyn_reset_core(eng, 0);
    fuji_dyn_reset_core(eng, 1);
    fuji_dyn_set_reg(eng, 0, 15, c0_code_base);
    fuji_dyn_set_reg(eng, 1, 15, c1_code_base);

    FujiDynClusterRequest creq{};
    creq.max_total_ticks = 100000ull;
    creq.start_vtick = 1000ull;
    creq.runnable_mask = 0x3u; /* cores 0 & 1 runnable, cores 2 & 3 waiting */
    creq.irq_pending_mask = 0u;
    creq.start_core = 0u;
    creq.start_used = 0u;
    creq.quantum = 1024u;
    FujiDynClusterResult cres{};
    CHECK(fuji_dyn_run_cluster_fast(eng, &creq, &cres) == 0, "run_cluster_fast");
    CHECK(cres.stop_reason == FUJI_DYN_STOP_ALL_WAITING, "expected STOP_ALL_WAITING");
    CHECK(cres.wfi_entered_mask == 0x3u, "expected both cores 0 & 1 to enter WFI");
    CHECK(cres.switches >= 2u, "expected native core switches across quanta");
    CHECK(fuji_dyn_get_reg(eng, 0, 0) == 1536u && fuji_dyn_get_reg(eng, 1, 0) == 1536u, "cluster loop result mismatch");

    /* Part B: #769 Tracepoint regression — non-privileged ARM32 instruction in a loop fires 3x cleanly, 0 UNDEF */
    const uint32_t tp_base = 0x0001A200u;
    const uint32_t tp_code[] = {
        0xE3A00000u, /* +0x00: mov r0, #0 */
        0xE3A01003u, /* +0x04: mov r1, #3 */
        0xE2800001u, /* +0x08: add r0, r0, #1  <-- TRACEPOINT PC (non-privileged!) */
        0xE2511001u, /* +0x0C: subs r1, r1, #1 */
        0x1AFFFFFCu, /* +0x10: bne +0x08 */
        0xE320F003u, /* +0x14: wfi */
    };
    CHECK(fuji_dyn_mem_write(eng, tp_base, tp_code, sizeof(tp_code)) == 0, "write tp code");
    fuji_dyn_reset_core(eng, 0);
    fuji_dyn_set_reg(eng, 0, 15, tp_base);
    uint32_t tp_pc = tp_base + 8u;
    fuji_dyn_set_tracepoints(eng, &tp_pc, 1u);

    int tp_hits = 0;
    FujiDynRunResult res{};
    for (int iter = 0; iter < 10; ++iter) {
        CHECK(fuji_dyn_run_fast(eng, 0, 1000, 0, 0, &res) == 0, "run tp");
        if (res.stop_reason == FUJI_DYN_STOP_TRACEPOINT) {
            CHECK(res.stop_pc == tp_pc, "wrong tracepoint PC");
            ++tp_hits;
        } else if (res.stop_reason == FUJI_DYN_STOP_WFI) {
            break;
        } else {
            CHECK(false, "unexpected stop_reason during tracepoint test (e.g. #769 UNDEF)");
        }
    }
    fuji_dyn_set_tracepoints(eng, nullptr, 0u);
    CHECK(tp_hits == 3 && fuji_dyn_get_reg(eng, 0, 0) == 3u, "expected 3 tracepoint hits and r0 == 3");
    std::printf("  [PASS] 10. Native 4-core cluster batch (switches=%u, wfi=0x%x) + #769 tracepoint fix (hits=%d)\n",
                cres.switches, cres.wfi_entered_mask, tp_hits);
}

static void test_gic_v2_shadow_sev_elision_and_smart_iciallu(FujiDynEngine* eng, TestMmioState* mmio) {
    /* Part A: Native GicV2Fastpath (GICC_IAR, GICC_EOIR, GICD_IPRIORITYR, live irq_pending + CPSIE i) */
    const uint32_t gicd_base = 0x40008000u;
    const uint32_t gicc_base = 0x40009000u;
    FujiDynGicShadow gic{};
    gic.active = 1u;
    gic.core_count = 4u;
    gic.dist_ctrl = 1u;
    for (uint32_t c = 0; c < 4u; ++c) {
        gic.cores[c].cpu_ctrl = 1u;
        gic.cores[c].pmr = 0xF0u;
        gic.cores[c].bpr = 0u;
        gic.cores[c].local_enabled = 0x0000FFFFu;
    }
    const uint32_t spi = 42u;
    gic.shared_enabled[spi >> 6] |= (1ull << (spi & 63u));
    gic.shared_pending[spi >> 6] |= (1ull << (spi & 63u));
    gic.shared_priority[spi - 32u] = 0x40u;
    gic.shared_targets[spi - 32u] = 0x01u;
    gic.target_core_bits[0][spi >> 6] |= (1ull << (spi & 63u));
    gic.cores[0].irq_pending = 1u;

    fuji_dyn_set_gic_shadow_ptr(eng, gicd_base, gicc_base, &gic);
    fuji_dyn_reset_mmio_stats(eng);
    mmio->read_count = 0u;
    mmio->write_count = 0u;

    /* Core 0 reads GICC_IAR (ack 42), writes GICD_IPRIORITYR[42]=0x20, writes GICC_EOIR (42), then WFI */
    const uint32_t gic_code_base = 0x0001B000u;
    const uint32_t gic_code[] = {
        0xE590100Cu, /* ldr r1, [r0, #0x0C]  ; GICC_IAR -> 42 */
        0xE3A03020u, /* mov r3, #0x20 */
        0xE5C2342Au, /* strb r3, [r2, #0x42A]; GICD_IPRIORITYR[42] = 0x20 */
        0xE5D2442Au, /* ldrb r4, [r2, #0x42A]; read back -> 0x20 */
        0xE5801010u, /* str r1, [r0, #0x10]  ; GICC_EOIR = 42 */
        0xE320F003u, /* wfi */
    };
    CHECK(fuji_dyn_mem_write(eng, gic_code_base, gic_code, sizeof(gic_code)) == 0, "write gic code");
    fuji_dyn_reset_core(eng, 0);
    fuji_dyn_set_reg(eng, 0, 0, gicc_base);
    fuji_dyn_set_reg(eng, 0, 2, gicd_base);
    fuji_dyn_set_reg(eng, 0, 15, gic_code_base);

    FujiDynRunResult res{};
    CHECK(fuji_dyn_run_fast(eng, 0, 5000, 0, 0, &res) == 0, "run gic shadow");
    CHECK(res.stop_reason == FUJI_DYN_STOP_WFI, "gic shadow WFI");
    CHECK(fuji_dyn_get_reg(eng, 0, 1) == spi, "GICC_IAR ack != 42");
    CHECK(fuji_dyn_get_reg(eng, 0, 4) == 0x20u, "GICD_IPRIORITYR readback != 0x20");
    CHECK(gic.cores[0].irq_pending == 0u && gic.cores[0].active_stack_size == 0u, "GIC shadow post-EOIR mismatch");
    CHECK(gic.native_seq >= 3u && gic.fast_hits >= 4u, "GIC shadow seq/hits mismatch");
    CHECK(mmio->read_count == 0u && mmio->write_count == 0u, "GIC shadow should incur ZERO MMIO upcalls");

    /* Part B: CPSIE i native JIT unmask triggers immediate STOP_IRQ when GIC shadow irq_pending == 1 */
    gic.shared_pending[spi >> 6] |= (1ull << (spi & 63u));
    gic.cores[0].irq_pending = 1u;
    const uint32_t cpsie_base = 0x0001B100u;
    const uint32_t cpsie_code[] = {
        0xF10C0080u, /* cpsid i */
        0xE3A05077u, /* mov r5, #0x77 */
        0xF1080080u, /* cpsie i -> immediately stops with STOP_IRQ! */
        0xE3A05099u, /* mov r5, #0x99 (must NOT execute before IRQ) */
        0xE320F003u, /* wfi */
    };
    CHECK(fuji_dyn_mem_write(eng, cpsie_base, cpsie_code, sizeof(cpsie_code)) == 0, "write cpsie code");
    fuji_dyn_reset_core(eng, 0);
    fuji_dyn_set_reg(eng, 0, 15, cpsie_base);
    CHECK(fuji_dyn_run_fast(eng, 0, 5000, 0, 1, &res) == 0, "run cpsie");
    CHECK(res.stop_reason == FUJI_DYN_STOP_IRQ, "expected STOP_IRQ on native JIT CPSIE i");
    CHECK(fuji_dyn_get_reg(eng, 0, 5) == 0x77u, "instruction after CPSIE i should not execute before IRQ");
    fuji_dyn_clear_gic_shadow(eng);

    /* Part C: Inline SEV elision inside fuji_dyn_run_cluster_fast (100 SEVs without exiting to host) */
    const uint32_t sev_base = 0x0001B200u;
    const uint32_t sev_code[] = {
        0xE3A00064u, /* mov r0, #100 */
        0xE320F004u, /* sev */
        0xE2500001u, /* subs r0, r0, #1 */
        0x1AFFFFFCu, /* bne -16 */
        0xE320F003u, /* wfi */
    };
    CHECK(fuji_dyn_mem_write(eng, sev_base, sev_code, sizeof(sev_code)) == 0, "write sev code");
    fuji_dyn_reset_core(eng, 0);
    fuji_dyn_set_reg(eng, 0, 15, sev_base);
    FujiDynClusterRequest creq{};
    creq.max_total_ticks = 50000ull;
    creq.start_vtick = 0ull;
    creq.runnable_mask = 0x1u;
    creq.irq_pending_mask = 0u;
    creq.start_core = 0u;
    creq.start_used = 0u;
    creq.quantum = 1024u;
    creq.wfe_waiting_mask = 0u;
    FujiDynClusterResult cres{};
    CHECK(fuji_dyn_run_cluster_fast(eng, &creq, &cres) == 0, "run cluster sev elision");
    CHECK(cres.stop_reason == FUJI_DYN_STOP_ALL_WAITING, "expected STOP_ALL_WAITING after 100 elided SEVs");
    CHECK(fuji_dyn_get_reg(eng, 0, 0) == 0u && (cres.sev_mask & 1u) != 0u, "SEV loop did not complete cleanly");

    /* Part D: Smart ICIALLU dirty-code retention — guest self-modifying code + ICIALLU observes patched insn */
    const uint32_t smc_base = 0x0001B300u;
    const uint32_t smc_code[] = {
        0xE3A00011u, /* +0x00: mov r0, #0x11 (patched to mov r0, #0x22!) */
        0xE12FFF1Eu, /* +0x04: bx lr */
        /* driver @ +0x08: */
        0xEBFFFFFCu, /* +0x08: bl +0x00 -> r0 = 0x11 */
        0xE1A04000u, /* +0x0C: mov r4, r0 */
        0xE5812000u, /* +0x10: str r2, [r1] (writes 0xE3A00022 to +0x00!) */
        0xEE070F15u, /* +0x14: mcr p15, 0, r0, c7, c5, 0 (ICIALLU) */
        0xEBFFFFF8u, /* +0x18: bl +0x00 -> r0 = 0x22 */
        0xE320F003u, /* +0x1C: wfi */
    };
    CHECK(fuji_dyn_mem_write(eng, smc_base, smc_code, sizeof(smc_code)) == 0, "write smc code");
    fuji_dyn_reset_core(eng, 0);
    fuji_dyn_set_reg(eng, 0, 1, smc_base);
    fuji_dyn_set_reg(eng, 0, 2, 0xE3A00022u); /* mov r0, #0x22 */
    fuji_dyn_set_reg(eng, 0, 15, smc_base + 8u);
    CHECK(fuji_dyn_run_fast(eng, 0, 5000, 0, 0, &res) == 0, "run smc iciallu");
    CHECK(res.stop_reason == FUJI_DYN_STOP_WFI, "smc WFI");
    CHECK(fuji_dyn_get_reg(eng, 0, 4) == 0x11u && fuji_dyn_get_reg(eng, 0, 0) == 0x22u,
          "smart ICIALLU failed to flush dirty self-modified code page");

    // ThreadX counters and instructions share a 4 KiB page. A cached block must
    // survive ICIALLUIS after a counter write and observe later instruction writes.
    const uint32_t mixed = 0x0001C800u;
    const uint32_t table = 0x00080000u;
    const uint32_t counter_code[] = {
        0xE3A03007u, // mov r3, #7
        0xE12FFF1Eu, // bx lr
        0xE5812000u, // str r2, [r1]
        0xEE070F11u, // mcr p15, 0, r0, c7, c1, 0 (ICIALLUIS)
        0xF57FF06Fu, // isb
        0xEBFFFFF9u, // bl mixed
        0xE320F003u, // wfi
    };
    fuji_dyn_reset_core(eng, 0);
    write_u32(eng, table, (3u << 10) | 2u);
    CHECK(fuji_dyn_mem_write(eng, mixed, counter_code, sizeof(counter_code)) == 0, "write mixed code/data test");
    fuji_dyn_set_cp15(eng, 0, 0, 2, 0, 0, table);
    fuji_dyn_set_cp15(eng, 0, 0, 3, 0, 0, 3u);
    fuji_dyn_set_cp15(eng, 0, 0, 1, 0, 0, fuji_dyn_get_cp15(eng, 0, 0, 1, 0, 0) | 1u);
    fuji_dyn_clear_cache(eng);
    fuji_dyn_set_reg(eng, 0, 1, mixed + 0x100u);
    for (uint32_t value = 1; value <= 3; ++value) {
        fuji_dyn_set_reg(eng, 0, 2, value);
        fuji_dyn_set_reg(eng, 0, 15, mixed + 8u);
        CHECK(fuji_dyn_run_fast(eng, 0, 5000, 0, 0, &res) == 0 && res.stop_reason == FUJI_DYN_STOP_WFI,
              "run mixed code/data test");
        CHECK(read_u32(eng, mixed + 0x100u) == value, "counter store missing");
        if (value == 3u) CHECK(res.translated_fetches == 0, "data store flushed a warm instruction block");
    }
    fuji_dyn_set_reg(eng, 0, 1, mixed);
    fuji_dyn_set_reg(eng, 0, 2, 0xE3A03009u); // mov r3, #9
    fuji_dyn_set_reg(eng, 0, 15, mixed + 8u);
    CHECK(fuji_dyn_run_fast(eng, 0, 5000, 0, 0, &res) == 0 && fuji_dyn_get_reg(eng, 0, 3) == 9u,
          "code store after same-page data writes must invalidate the warm block");

    // Reinstalling an identical page-table descriptor must retain the warm code.
    // A changed descriptor for the same executed section still requires invalidation.
    if (fuji_dyn_fastmem_is_dual_view(eng)) {
        const uint32_t descriptor = read_u32(eng, table);
        fuji_dyn_set_reg(eng, 0, 1, table);
        fuji_dyn_set_reg(eng, 0, 2, descriptor);
        for (unsigned pass = 0; pass < 2; ++pass) {
            fuji_dyn_set_reg(eng, 0, 15, mixed + 8u);
            CHECK(fuji_dyn_run_fast(eng, 0, 5000, 0, 0, &res) == 0 && res.stop_reason == FUJI_DYN_STOP_WFI,
                  "run unchanged page-table descriptor store");
        }
        CHECK(res.translated_fetches == 0, "identical page-table descriptor flushed a warm block");
        fuji_dyn_set_reg(eng, 0, 2, descriptor ^ 4u); // change section memory attributes
        fuji_dyn_set_reg(eng, 0, 15, mixed + 8u);
        CHECK(fuji_dyn_run_fast(eng, 0, 5000, 0, 0, &res) == 0 && res.stop_reason == FUJI_DYN_STOP_WFI,
              "run changed descriptor store");
        CHECK(read_u32(eng, table) == (descriptor ^ 4u) && res.translated_fetches != 0,
              "changed page-table descriptor must invalidate translated code");
    }

    std::printf("  [PASS] 11. DYNARMIC-V2: GicV2Fastpath (0 upcalls) + native CPSIE IRQ + cluster SEV elision + smart ICIALLU\n");
}

static void test_dyn_v3_fastmem_sgir_timer_and_alignment(FujiDynEngine* eng, TestMmioState* mmio) {
    /* Part A: Hardware 4 GiB Fastmem arena active + direct host pointer match */
    const uintptr_t fm_base = fuji_dyn_get_fastmem_base(eng);
    CHECK(fm_base != 0u, "DYN-V3 hardware Fastmem arena should be active");
    void* host_rw = fuji_dyn_get_ram_host_ptr(eng, 0x00000000u);
    CHECK(host_rw != nullptr, "expected host RW pointer for Fastmem-backed RAM");

    /* Part B: SCTLR.A == 1 traps unaligned 32-bit load with FAULT_ALIGNMENT (0x01), while SCTLR.A == 0 succeeds */
    const uint32_t align_code_base = 0x0001C000u;
    const uint32_t align_data_pa = 0x0001C800u;
    write_u32(eng, align_data_pa, 0x44332211u);
    write_u32(eng, align_data_pa + 4u, 0x88776655u);
    const uint32_t align_code[] = {
        0xE5901001u, /* ldr r1, [r0, #1] -> unaligned! succeeds when SCTLR.A=0 (0x55443322), DABT when SCTLR.A=1 */
        0xE320F003u, /* wfi */
    };
    CHECK(fuji_dyn_mem_write(eng, align_code_base, align_code, sizeof(align_code)) == 0, "write align code");
    fuji_dyn_reset_core(eng, 0);
    fuji_dyn_set_reg(eng, 0, 0, align_data_pa);
    fuji_dyn_set_reg(eng, 0, 15, align_code_base);
    FujiDynRunResult res{};
    CHECK(fuji_dyn_run_fast(eng, 0, 1000, 0, 0, &res) == 0, "run unaligned SCTLR.A=0");
    CHECK(res.stop_reason == FUJI_DYN_STOP_WFI, "expected WFI with SCTLR.A=0");
    CHECK(fuji_dyn_get_reg(eng, 0, 1) == 0x55443322u, "unaligned LDR mismatch with SCTLR.A=0");

    /* Now enable SCTLR.A = 1 (bit 1) -> must fault with FAULT_ALIGNMENT (0x01) */
    fuji_dyn_reset_core(eng, 0);
    const uint32_t sctlr0 = fuji_dyn_get_cp15(eng, 0, 0, 1, 0, 0);
    fuji_dyn_set_cp15(eng, 0, 0, 1, 0, 0, sctlr0 | 2u);
    fuji_dyn_set_reg(eng, 0, 0, align_data_pa);
    fuji_dyn_set_reg(eng, 0, 15, align_code_base);
    CHECK(fuji_dyn_run_fast(eng, 0, 1000, 0, 0, &res) == 0, "run unaligned SCTLR.A=1");
    CHECK(res.stop_reason == FUJI_DYN_STOP_FAULT_DABT && (res.fault_status & 0xFu) == 0x1u,
          "expected FAULT_ALIGNMENT DABT when SCTLR.A=1");
    fuji_dyn_reset_core(eng, 0);

    /* Part C: Inline GICD_SGIR (0xF00) waking Core 1 from WFI inside fuji_dyn_run_cluster_fast (0 upcalls!) */
    const uint32_t gicd_base = 0x40008000u;
    const uint32_t gicc_base = 0x40009000u;
    FujiDynGicShadow gic{};
    gic.active = 1u;
    gic.core_count = 4u;
    gic.dist_ctrl = 1u;
    for (uint32_t c = 0; c < 4u; ++c) {
        gic.cores[c].cpu_ctrl = 1u;
        gic.cores[c].pmr = 0xF0u;
        gic.cores[c].bpr = 0u;
        gic.cores[c].local_enabled = 0x0000FFFFu;
        for (uint32_t i = 0; i < 32u; ++i) gic.cores[c].local_priority[i] = 0x40u;
    }
    const uint32_t timer_spi = 48u;
    gic.shared_enabled[timer_spi >> 6] |= (1ull << (timer_spi & 63u));
    gic.shared_edge[timer_spi >> 6] |= (1ull << (timer_spi & 63u));
    gic.shared_priority[timer_spi - 32u] = 0x40u;
    gic.shared_targets[timer_spi - 32u] = 0x01u; /* target Core 0 */
    gic.target_core_bits[0][timer_spi >> 6] |= (1ull << (timer_spi & 63u));

    fuji_dyn_set_gic_shadow_ptr(eng, gicd_base, gicc_base, &gic);
    mmio->read_count = 0u;
    mmio->write_count = 0u;

    /* Core 0 writes GICD_SGIR (target Core 1, SGI #3: 0x00020003) then WFI.
     * Core 1 wakes inline inside fuji_dyn_run_cluster_fast, acks GICC_IAR, EOIs, sets r7=0xBEEF, then WFI! */
    const uint32_t sgi_c0_base = 0x0001C200u;
    const uint32_t sgi_c1_base = 0x0001C300u;
    const uint32_t sgi_c0_code[] = {
        0xE5801F00u, /* str r1, [r0, #0xF00] ; GICD_SGIR -> wake Core 1 with SGI #3 */
        0xE320F003u, /* wfi */
    };
    const uint32_t sgi_c1_code[] = {
        0xE590200Cu, /* ldr r2, [r0, #0x0C]  ; GICC_IAR -> 3 | (0 << 10) = 3 */
        0xE5802010u, /* str r2, [r0, #0x10]  ; GICC_EOIR */
        0xE3A070EFu, /* mov r7, #0xEF */
        0xE320F003u, /* wfi */
    };
    CHECK(fuji_dyn_mem_write(eng, sgi_c0_base, sgi_c0_code, sizeof(sgi_c0_code)) == 0, "write sgi c0");
    CHECK(fuji_dyn_mem_write(eng, sgi_c1_base, sgi_c1_code, sizeof(sgi_c1_code)) == 0, "write sgi c1");
    fuji_dyn_reset_core(eng, 0);
    fuji_dyn_reset_core(eng, 1);
    fuji_dyn_set_reg(eng, 0, 0, gicd_base);
    fuji_dyn_set_reg(eng, 0, 1, 0x00020003u); /* targetList=bit1 (Core 1), SGI=3 */
    fuji_dyn_set_reg(eng, 0, 15, sgi_c0_base);
    fuji_dyn_set_reg(eng, 1, 0, gicc_base);
    fuji_dyn_set_reg(eng, 1, 15, sgi_c1_base);

    FujiDynClusterRequest creq{};
    creq.max_total_ticks = 20000ull;
    creq.start_vtick = 1000ull;
    creq.runnable_mask = 0x1u; /* Core 0 runnable, Core 1 parked in WFI (bit 4+1 = bit 5) */
    creq.irq_pending_mask = 0u;
    creq.start_core = 0u;
    creq.start_used = 0u;
    creq.quantum = 1024u;
    creq.wfe_waiting_mask = (1u << 5); /* Core 1 in WFI */
    FujiDynClusterResult cres{};
    CHECK(fuji_dyn_run_cluster_fast(eng, &creq, &cres) == 0, "run cluster inline SGIR");
    CHECK(cres.stop_reason == FUJI_DYN_STOP_ALL_WAITING, "expected STOP_ALL_WAITING after inline SGI wakeup");
    CHECK((cres.wfi_woken_mask & 0x2u) != 0u, "expected Core 1 in wfi_woken_mask");
    CHECK(fuji_dyn_get_reg(eng, 1, 2) == 3u && fuji_dyn_get_reg(eng, 1, 7) == 0xEFu,
          "Core 1 did not execute inline SGI handler");
    CHECK(mmio->read_count == 0u && mmio->write_count == 0u, "inline SGIR+IAR+EOIR must incur 0 MMIO upcalls");

    /* Part D: Inline soc-timer comparator IRQ auto-raise + WFI fast-forward inside fuji_dyn_run_cluster_fast */
    FujiDynTimerShadow tsh{};
    tsh.active = 1u;
    tsh.channels[7].enabled = 1u;
    tsh.channels[7].has_deadline = 1u;
    tsh.channels[7].reload = 4999ull; /* period = 5000 */
    tsh.channels[7].deadline = 6000ull;
    FujiDynTimerIrqShadow tirq{};
    tirq.active = 3u; /* bit0=inline fire, bit1=allow WFI fast-forward */
    for (uint32_t i = 0; i < FUJI_DYN_TIMER_CHANNELS; ++i) tirq.irq_routes[i] = -1;
    tirq.irq_routes[7] = static_cast<int32_t>(timer_spi);
    tirq.next_other_deadline = 100000ull;
    fuji_dyn_set_timer_shadow_ptr(eng, 0x40004000u, &tsh);
    fuji_dyn_set_timer_irq_shadow_ptr(eng, &tsh, &tirq);

    /* Core 0 executes WFI at vtick=1000 -> C++ fast-forwards to 6000, fires SPI 48, wakes Core 0,
     * Core 0 acks SPI 48 (GICC_IAR), EOIs, sets r6=0x55, then WFI! */
    const uint32_t tmr_code_base = 0x0001C400u;
    const uint32_t tmr_code[] = {
        0xE320F003u, /* wfi (parks at vtick~1001, C++ jumps to 6000 and fires SPI 48!) */
        0xE590200Cu, /* ldr r2, [r0, #0x0C] ; GICC_IAR -> 48 */
        0xE5802010u, /* str r2, [r0, #0x10] ; GICC_EOIR */
        0xE3A06055u, /* mov r6, #0x55 */
        0xE320F003u, /* wfi (second WFI -> next deadline 11000) */
    };
    CHECK(fuji_dyn_mem_write(eng, tmr_code_base, tmr_code, sizeof(tmr_code)) == 0, "write inline timer code");
    fuji_dyn_reset_core(eng, 0);
    fuji_dyn_set_reg(eng, 0, 0, gicc_base);
    fuji_dyn_set_reg(eng, 0, 15, tmr_code_base);
    /* Prevent second WFI from fast-forwarding by capping next_other_deadline at 7000 */
    tirq.next_other_deadline = 7000ull;
    FujiDynClusterRequest treq{};
    treq.max_total_ticks = 10000ull;
    treq.start_vtick = 1000ull;
    treq.runnable_mask = 0x1u;
    treq.quantum = 1024u;
    FujiDynClusterResult tres{};
    CHECK(fuji_dyn_run_cluster_fast(eng, &treq, &tres) == 0, "run cluster inline timer");
    CHECK(tres.stop_reason == FUJI_DYN_STOP_ALL_WAITING, "expected STOP_ALL_WAITING after inline timer wakeup");
    CHECK(tres.virtual_tick >= 6000ull && tirq.inline_wfi_skips == 1ull && tirq.fired_periods[7] == 1ull,
          "expected exactly 1 inline WFI fast-forward and 1 timer expiry");
    CHECK(tsh.channels[7].deadline == 11000ull, "expected timer ch7 deadline advanced to 11000");
    CHECK(fuji_dyn_get_reg(eng, 0, 2) == timer_spi && fuji_dyn_get_reg(eng, 0, 6) == 0x55u,
          "Core 0 did not ack/handle inline timer SPI 48");
    CHECK(mmio->read_count == 0u && mmio->write_count == 0u, "inline timer+WFI+GIC must incur 0 MMIO upcalls");

    fuji_dyn_clear_timer_irq_shadow(eng);
    fuji_dyn_clear_timer_shadow(eng);
    fuji_dyn_clear_gic_shadow(eng);

#ifdef _WIN32
    /* Part D (Win64 #1155): Validate OsZeroRamAllocator VirtualAlloc(MEM_RESERVE|MEM_COMMIT) large zero-RAM allocation */
    const SIZE_T win_ram_bytes = 512ull * 1024ull * 1024ull;
    uint8_t* win_ram = static_cast<uint8_t*>(VirtualAlloc(nullptr, win_ram_bytes, MEM_RESERVE | MEM_COMMIT, PAGE_READWRITE));
    CHECK(win_ram != nullptr, "Win64 VirtualAlloc(512 MiB) failed");
    CHECK(win_ram[0] == 0u && win_ram[win_ram_bytes - 1u] == 0u, "Win64 VirtualAlloc memory not zero-initialized");
    win_ram[0x1234] = 0xA5u;
    CHECK(win_ram[0x1234] == 0xA5u, "Win64 VirtualAlloc read/write failed");
    CHECK(VirtualFree(win_ram, 0, MEM_RELEASE) != 0, "Win64 VirtualFree failed");
#endif

    std::printf("  [PASS] 12. DYN-V3: 4 GiB Hardware Fastmem (base=0x%llx, dualView=%u) + SCTLR.A + inline SGIR & Timer WFI (0 upcalls)\n",
                static_cast<unsigned long long>(fm_base), fuji_dyn_fastmem_is_dual_view(eng));
}

static void test_vtick_divider_and_subtick_carry(FujiDynEngine* eng) {
    /* Simple 2-instruction ARM32 countdown loop: subs r0, r0, #1; bne loop; wfi */
    const uint32_t base = 0x00015800u;
    const uint32_t code[] = {
        0xE2500001u, /* subs r0, r0, #1 */
        0x1AFFFFFDu, /* bne -4 (base) */
        0xE320F003u, /* wfi */
    };
    CHECK(fuji_dyn_mem_write(eng, base, code, sizeof(code)) == 0, "write vtick div code");

    /* 1. Power-of-2 divider = 8 with fractional carry across slices */
    fuji_dyn_set_vtick_divider(eng, 8u);
    fuji_dyn_reset_core(eng, 0);
    fuji_dyn_set_reg(eng, 0, 0, 50u); /* 50 * 2 = 100 insns + 1 wfi = 101 insns -> 12 ticks (rem 5) */
    fuji_dyn_set_reg(eng, 0, 15, base);
    FujiDynRunResult r1{};
    CHECK(fuji_dyn_run_fast(eng, 0, 1000u, 1000u, 0u, &r1) == 0, "run div=8 #1");
    CHECK(r1.stop_reason == FUJI_DYN_STOP_WFI, "div=8 WFI #1");
    CHECK(r1.executed_ticks > 0u, "div=8 executed > 0");
    const uint64_t expected_vtick1 = 1000u + (r1.executed_ticks / 8u);
    CHECK(r1.virtual_tick == expected_vtick1, "div=8 vtick #1 mismatch");

    /* Run a second slice starting from r1.virtual_tick so fractional subticks carry over */
    fuji_dyn_set_reg(eng, 0, 0, 10u);
    fuji_dyn_set_reg(eng, 0, 15, base);
    FujiDynRunResult r2{};
    CHECK(fuji_dyn_run_fast(eng, 0, 1000u, r1.virtual_tick, 0u, &r2) == 0, "run div=8 #2");
    CHECK(r2.stop_reason == FUJI_DYN_STOP_WFI, "div=8 WFI #2");
    const uint64_t expected_vtick2 = 1000u + ((r1.executed_ticks + r2.executed_ticks) / 8u);
    CHECK(r2.virtual_tick == expected_vtick2, "div=8 fractional carry mismatch");

    /* 2. External IdleClock jump resets fractional subticks cleanly */
    fuji_dyn_set_reg(eng, 0, 0, 20u);
    fuji_dyn_set_reg(eng, 0, 15, base);
    FujiDynRunResult r3{};
    const uint64_t jumped = r2.virtual_tick + 54000u;
    CHECK(fuji_dyn_run_fast(eng, 0, 1000u, jumped, 0u, &r3) == 0, "run div=8 after idle jump");
    CHECK(r3.virtual_tick == jumped + (r3.executed_ticks / 8u), "div=8 idle jump reset mismatch");

    /* 3. Non-power-of-2 divider = 5 */
    fuji_dyn_set_vtick_divider(eng, 5u);
    fuji_dyn_set_reg(eng, 0, 0, 30u);
    fuji_dyn_set_reg(eng, 0, 15, base);
    FujiDynRunResult r4{};
    CHECK(fuji_dyn_run_fast(eng, 0, 1000u, 2000u, 0u, &r4) == 0, "run div=5");
    CHECK(r4.virtual_tick == 2000u + (r4.executed_ticks / 5u), "div=5 vtick mismatch");

    /* Restore default 1:1 divider before benchmark */
    fuji_dyn_set_vtick_divider(eng, 1u);
    std::printf("  [PASS] 13. Real-silicon vtick divider (div=8/5 + fractional carry + idle jump reset)\n");
}

static void bench_mips(FujiDynEngine* eng) {
    /* 50,000,000-instruction ARM32 ALU + RAM load/store benchmark */
    const uint32_t base = 0x00016000u;
    const uint32_t buf_pa = 0x00120000u;
    const uint32_t code[] = {
        /* 5 insns per iteration * 10,000,000 iters = 50,000,000 guest insns */
        0xE5912000u, /* ldr r2, [r1] */
        0xE0822000u, /* add r2, r2, r0 */
        0xE5812000u, /* str r2, [r1] */
        0xE2500001u, /* subs r0, r0, #1 */
        0x1AFFFFFAu, /* bne loop */
        0xE320F003u, /* wfi */
    };
    CHECK(fuji_dyn_mem_write(eng, base, code, sizeof(code)) == 0, "write bench");
    write_u32(eng, buf_pa, 0);

    fuji_dyn_reset_core(eng, 0);
    fuji_dyn_set_reg(eng, 0, 0, 10000000u);
    fuji_dyn_set_reg(eng, 0, 1, buf_pa);
    fuji_dyn_set_reg(eng, 0, 15, base);

    auto t0 = std::chrono::steady_clock::now();
    FujiDynRunResult res{};
    CHECK(fuji_dyn_run_fast(eng, 0, 60000000ull, 0, 0, &res) == 0, "run bench");
    auto t1 = std::chrono::steady_clock::now();
    CHECK(res.stop_reason == FUJI_DYN_STOP_WFI, "bench WFI");
    double ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
    double mips = (static_cast<double>(res.executed_ticks) / 1e6) / (ms / 1000.0);
    std::printf("  [BENCH] 7. Fastmem JIT: %llu ARM32 insns in %.2f ms = %.1f MIPS!\n",
                static_cast<unsigned long long>(res.executed_ticks), ms, mips);
}

static void test_mixed_4k_fallback_under_fastmem(FujiDynEngine* eng) {
    /* BUG-30 regression: Windows Win32 Fastmem placeholders require 64 KiB alignment/size.
       Non-64KiB-aligned 4 KiB regions (e.g. GFX100S 0x0589F000 len 0x62000) MUST return -3
       without polluting bus.ram_regions_ so host fallback (OsZeroRamAllocator) maps cleanly
       with rc == 0 and guest code executes loads/stores across BOTH Fastmem and fallback RAM. */
    const uint32_t non64k_base = 0x0589F000u;
    const uint32_t non64k_size = 0x00062000u;
    std::vector<uint8_t> fallback_buf(non64k_size, 0u);
    int32_t null_rc = fuji_dyn_map_ram(eng, non64k_base, non64k_size, nullptr, FUJI_DYN_PERM_ALL);
#if defined(_WIN32)
    CHECK(null_rc == -3, "Win32 non-64KiB null-ptr map_ram must return -3 (not 0/-2)");
    CHECK(fuji_dyn_map_ram(eng, non64k_base, non64k_size, fallback_buf.data(), FUJI_DYN_PERM_ALL) == 0,
          "Win32 fallback map_ram after -3 must succeed without overlap -2");
#else
    if (null_rc == -3) {
        CHECK(fuji_dyn_map_ram(eng, non64k_base, non64k_size, fallback_buf.data(), FUJI_DYN_PERM_ALL) == 0,
              "fallback map_ram after -3 must succeed");
    } else {
        CHECK(null_rc == 0, "Linux 4 KiB Fastmem map_ram must succeed");
    }
#endif
    /* Seed sentinel at non64k_base + 0x100 and run ARM32 guest code reading/writing BOTH 0x00010000 and non64k_base */
    const uint32_t seed = 0xCAFEBABEu;
    CHECK(fuji_dyn_mem_write(eng, non64k_base + 0x100u, &seed, 4u) == 0, "write non64k seed");
    const uint32_t code_base = 0x00070000u;
    const uint32_t prog[] = {
        0xE5902000u, /* ldr r2, [r0]       ; r0 = non64k_base + 0x100 */
        0xE2822001u, /* add r2, r2, #1     ; 0xCAFEBABF */
        0xE5802004u, /* str r2, [r0, #4]   ; store back to non64k fallback RAM */
        0xE5812000u, /* str r2, [r1]       ; store to 64KiB Fastmem RAM @ 0x00080000 */
        0xE320F003u  /* wfi */
    };
    fuji_dyn_reset_core(eng, 0);
    CHECK(fuji_dyn_mem_write(eng, code_base, prog, sizeof(prog)) == 0, "write mixed prog");
    fuji_dyn_set_reg(eng, 0, 0, non64k_base + 0x100u);
    fuji_dyn_set_reg(eng, 0, 1, 0x00080000u);
    fuji_dyn_set_reg(eng, 0, 15, code_base);
    FujiDynRunResult res{};
    CHECK(fuji_dyn_run_fast(eng, 0, 10000ull, 0, 0, &res) == 0, "run mixed 4k+fastmem");
    CHECK(res.stop_reason == FUJI_DYN_STOP_WFI, "mixed 4k+fastmem WFI");
    uint32_t out_non64k = 0u, out_fm = 0u;
    CHECK(fuji_dyn_mem_read(eng, non64k_base + 0x104u, &out_non64k, 4u) == 0, "read non64k out");
    CHECK(fuji_dyn_mem_read(eng, 0x00080000u, &out_fm, 4u) == 0, "read fm out");
    CHECK(out_non64k == 0xCAFEBABFu && out_fm == 0xCAFEBABFu, "mixed 4k+fastmem value mismatch");
    CHECK(fuji_dyn_unmap_ram(eng, non64k_base, non64k_size) == 0, "unmap non64k");
    std::printf("  [PASS] 14. Mixed 64 KiB Fastmem + 4 KiB non-64KiB fallback RAM (BUG-30)!\n");
}

static void test_supersection_40bit_pa(FujiDynEngine* eng) {
    /* BUG-V3-7-1: ARMv7-A Short-Descriptor 16 MiB Supersection 40-bit PA decoding:
     *   PA[31:24] = L1[31:24], PA[35:32] = L1[23:20], PA[39:36] = L1[8:5], PA[23:0] = VA[23:0].
     * Verify writing via VA 0xD3000000 -> PA 0x01_01000000 does NOT overwrite low 32-bit PA 0x01000000,
     * verify PA[39:36] (bits [8:5]) -> PA 0x10_01000000 works, and verify unmapped high PA faults with DABT! */
    const uint64_t low_ram_base = 0x01000000ull;
    const uint64_t high_pa_bank1 = 0x0101000000ull; /* PA[35:32] = 1, PA[31:24] = 0x01 (exact GFX100S 4K pool!) */
    const uint64_t high_pa_ext36 = 0x1001000000ull; /* PA[39:36] = 1, PA[31:24] = 0x01 */
    const uint32_t region_size = 16u * 1024u * 1024u;

    CHECK(fuji_dyn_map_ram(eng, static_cast<uint32_t>(low_ram_base), region_size, nullptr, FUJI_DYN_PERM_ALL) == 0,
          "map low 0x01000000");
    CHECK(fuji_dyn_map_ram64(eng, high_pa_bank1, region_size, nullptr, FUJI_DYN_PERM_READ | FUJI_DYN_PERM_WRITE) == 0,
          "map high 0x1_01000000");
    CHECK(fuji_dyn_map_ram64(eng, high_pa_ext36, region_size, nullptr, FUJI_DYN_PERM_READ | FUJI_DYN_PERM_WRITE) == 0,
          "map high 0x10_01000000");

    /* Seed sentinel in low 32-bit RAM at PA 0x01000040 (simulating live section4/section5 code!) */
    const uint32_t low_sentinel = 0x5EC5C0DEu;
    CHECK(fuji_dyn_mem_write(eng, 0x01000040u, &low_sentinel, 4u) == 0, "seed low sentinel");

    const uint32_t l1_base = 0x00080000u;
    std::vector<uint8_t> zeros(0x4000, 0);
    CHECK(fuji_dyn_mem_write(eng, l1_base, zeros.data(), static_cast<uint32_t>(zeros.size())) == 0, "zero l1");

    /* Identity map 0x00000000..0x01FFFFFF (1 MiB sections, AP=11 RWX) */
    for (uint32_t s = 0u; s < 32u; ++s) {
        write_u32(eng, l1_base + (s << 2), (s << 20) | (3u << 10) | 2u);
    }
    /* Supersection VA 0xD3000000..0xD3FFFFFF -> PA 0x01_01000000:
     * L1 = 0x01000000 | (1 << 20) | (1 << 18) | (3 << 10) | 2 = 0x01140C02 */
    const uint32_t l1_bank1 = 0x01000000u | (1u << 20) | (1u << 18) | (3u << 10) | 2u;
    /* Supersection VA 0xD4000000..0xD4FFFFFF -> PA 0x10_01000000:
     * L1 = 0x01000000 | (1 << 5) | (1 << 18) | (3 << 10) | 2 = 0x01040C22 */
    const uint32_t l1_ext36 = 0x01000000u | (1u << 5) | (1u << 18) | (3u << 10) | 2u;
    /* Unmapped high-PA Supersection VA 0xD5000000..0xD5FFFFFF -> PA 0x01_50000000 (NOT mapped!) */
    const uint32_t l1_unmapped_hi = 0x50000000u | (1u << 20) | (1u << 18) | (3u << 10) | 2u;
    for (uint32_t i = 0u; i < 16u; ++i) {
        write_u32(eng, l1_base + ((0xD30u + i) << 2), l1_bank1);
        write_u32(eng, l1_base + ((0xD40u + i) << 2), l1_ext36);
        write_u32(eng, l1_base + ((0xD50u + i) << 2), l1_unmapped_hi);
    }

    const uint32_t code_base = 0x00072000u;
    const uint32_t prog[] = {
        0xEE020F10u, /* mcr p15, 0, r0, c2, c0, 0 (TTBR0) */
        0xEE031F10u, /* mcr p15, 0, r1, c3, c0, 0 (DACR) */
        0xEE112F10u, /* mrc p15, 0, r2, c1, c0, 0 (SCTLR) */
        0xE3822001u, /* orr r2, r2, #1 (MMU enable) */
        0xEE012F10u, /* mcr p15, 0, r2, c1, c0, 0 (SCTLR) */
        0xE5836040u, /* str r6, [r3, #0x40] ; write 0x00000000 to VA 0xD3000040 (PA 0x1_01000040) */
        0xE5837044u, /* str r7, [r3, #0x44] ; write 0xCAFEBABE to VA 0xD3000044 (PA 0x1_01000044) */
        0xE5938044u, /* ldr r8, [r3, #0x44] ; read back VA 0xD3000044 -> 0xCAFEBABE */
        0xE5849040u, /* str r9, [r4, #0x40] ; write 0xA1B2C3D4 to VA 0xD4000040 (PA 0x10_01000040) */
        0xE594A040u, /* ldr r10, [r4, #0x40] ; read back VA 0xD4000040 -> 0xA1B2C3D4 */
        0xE320F003u, /* wfi */
        0xE595B040u, /* ldr r11, [r5, #0x40] ; read unmapped PA 0x1_50000040 -> MUST DABT! */
        0xE320F003u, /* wfi */
    };
    CHECK(fuji_dyn_mem_write(eng, code_base, prog, sizeof(prog)) == 0, "write supersection prog");

    fuji_dyn_reset_core(eng, 0);
    fuji_dyn_set_reg(eng, 0, 0, l1_base);
    fuji_dyn_set_reg(eng, 0, 1, 0x55555555u);
    fuji_dyn_set_reg(eng, 0, 3, 0xD3000000u);
    fuji_dyn_set_reg(eng, 0, 4, 0xD4000000u);
    fuji_dyn_set_reg(eng, 0, 5, 0xD5000000u);
    fuji_dyn_set_reg(eng, 0, 6, 0x00000000u);
    fuji_dyn_set_reg(eng, 0, 7, 0xCAFEBABEu);
    fuji_dyn_set_reg(eng, 0, 9, 0xA1B2C3D4u);
    fuji_dyn_set_reg(eng, 0, 15, code_base);

    FujiDynRunResult res{};
    CHECK(fuji_dyn_run_fast(eng, 0, 20000ull, 0, 0, &res) == 0, "run supersection test");
    CHECK(res.stop_reason == FUJI_DYN_STOP_WFI, "supersection expected WFI");
    CHECK(fuji_dyn_get_reg(eng, 0, 8) == 0xCAFEBABEu, "VA 0xD3000044 readback mismatch");
    CHECK(fuji_dyn_get_reg(eng, 0, 10) == 0xA1B2C3D4u, "VA 0xD4000040 readback mismatch");

    /* CRITICAL CHECK: low 32-bit PA 0x01000040 MUST be untouched! */
    CHECK(read_u32(eng, 0x01000040u) == low_sentinel, "CRITICAL: high-PA Supersection write aliased low 32-bit RAM!");

    uint32_t hi_word = 0u, ext_word = 0u;
    CHECK(fuji_dyn_mem_read64(eng, high_pa_bank1 + 0x44u, &hi_word, 4u) == 0 && hi_word == 0xCAFEBABEu,
          "mem_read64 PA 0x1_01000044 mismatch");
    CHECK(fuji_dyn_mem_read64(eng, high_pa_ext36 + 0x40u, &ext_word, 4u) == 0 && ext_word == 0xA1B2C3D4u,
          "mem_read64 PA 0x10_01000040 mismatch");

    uint64_t pa64 = 0ull;
    CHECK(fuji_dyn_translate64(eng, 0, 0xD3000044u, FUJI_DYN_PERM_READ, &pa64) == 0 && pa64 == (high_pa_bank1 + 0x44u),
          "translate64 0xD3000044 mismatch");
    CHECK(fuji_dyn_translate64(eng, 0, 0xD4000040u, FUJI_DYN_PERM_READ, &pa64) == 0 && pa64 == (high_pa_ext36 + 0x40u),
          "translate64 0xD4000040 mismatch");

    /* Resume at ldr r11, [r5, #0x40] (unmapped high-PA 0x1_50000040) -> MUST refuse with DABT! */
    fuji_dyn_set_reg(eng, 0, 15, code_base + 11u * 4u);
    CHECK(fuji_dyn_run_fast(eng, 0, 10000ull, 0, 0, &res) == 0, "run unmapped high-PA check");
    CHECK(res.stop_reason == FUJI_DYN_STOP_FAULT_DABT && res.fault_addr == 0xD5000040u,
          "unmapped high-PA Supersection must fault with DABT");

    CHECK(fuji_dyn_unmap_ram64(eng, high_pa_bank1, region_size) == 0, "unmap high 0x1_01000000");
    CHECK(fuji_dyn_unmap_ram64(eng, high_pa_ext36, region_size) == 0, "unmap high 0x10_01000000");
    CHECK(fuji_dyn_unmap_ram(eng, static_cast<uint32_t>(low_ram_base), region_size) == 0, "unmap low 0x01000000");
    std::printf("  [PASS] 15. ARMv7-A 40-bit Supersection PA[39:32] isolation & strict refusal (BUG-V3-7-1)!\n");
}

static void test_smp_mt_exclusive_counter_4core(FujiDynEngine* eng) {
    /* 4 cores concurrently execute LDREX/ADD/STREX loop 50,000 times each on shared counter @ 0x00120000.
     * Expected final counter == 200,000 with zero lost updates. */
    const uint32_t code_base = 0x00074000u;
    const uint32_t counter_pa = 0x00120000u;
    const uint32_t iters_per_core = 50000u;
    write_u32(eng, counter_pa, 0u);

    const uint32_t code[] = {
        /* r0 = counter_pa, r1 = iters_per_core */
        /* retry: */
        0xE1902F9Fu, /* ldrex r2, [r0] */
        0xE2822001u, /* add   r2, r2, #1 */
        0xE1803F92u, /* strex r3, r2, [r0] */
        0xE3530000u, /* cmp   r3, #0 */
        0x1AFFFFFAu, /* bne   retry (-24 -> +0) */
        0xE2511001u, /* subs  r1, r1, #1 */
        0x1AFFFFF8u, /* bne   retry (-32 -> +0) */
        0xE320F003u, /* wfi */
    };
    CHECK(fuji_dyn_mem_write(eng, code_base, code, sizeof(code)) == 0, "write mt exclusive code");
    fuji_dyn_clear_cache(eng);
    fuji_dyn_set_vtick_divider(eng, 1u);
    fuji_dyn_set_smp_host_threads(eng, 1u);
    CHECK(fuji_dyn_get_smp_host_threads(eng) == 1u, "smp_host_threads getter != 1");

    for (uint32_t c = 0u; c < 4u; ++c) {
        fuji_dyn_reset_core(eng, c);
        fuji_dyn_set_reg(eng, c, 0, counter_pa);
        fuji_dyn_set_reg(eng, c, 1, iters_per_core);
        fuji_dyn_set_reg(eng, c, 15, code_base);
    }

    uint32_t runnable = 0xFu;
    uint32_t wfi_mask = 0u;
    uint64_t vtick = 0u;
    uint64_t core_exec[4]{};
    uint32_t guard = 0u;
    while (runnable != 0u && guard < 2000u) {
        ++guard;
        FujiDynClusterRequest req{};
        req.runnable_mask = runnable;
        req.wfe_waiting_mask = (wfi_mask << 4);
        req.quantum = 2048u;
        req.max_total_ticks = 2000000u;
        req.start_vtick = vtick;
        FujiDynClusterResult res{};
        CHECK(fuji_dyn_run_cluster_fast(eng, &req, &res) == 0, "mt exclusive run_cluster_fast");
        vtick = res.virtual_tick;
        for (uint32_t c = 0u; c < 4u; ++c) core_exec[c] += res.core_executed[c];
        wfi_mask |= res.wfi_entered_mask;
        runnable &= ~res.wfi_entered_mask;
        CHECK(res.stop_reason == FUJI_DYN_STOP_QUANTUM || res.stop_reason == FUJI_DYN_STOP_ALL_WAITING,
              "unexpected stop_reason in mt exclusive test");
    }

    const uint32_t final_val = read_u32(eng, counter_pa);
    CHECK(runnable == 0u, "mt exclusive test did not finish all 4 cores");
    CHECK(final_val == 4u * iters_per_core, "mt LDREX/STREX counter lost updates!");
    for (uint32_t c = 0u; c < 4u; ++c) {
        CHECK(core_exec[c] > 0u, "core did not execute in mt exclusive test");
    }
    fuji_dyn_set_smp_host_threads(eng, 0u);
    CHECK(fuji_dyn_get_smp_host_threads(eng) == 0u, "smp_host_threads disable failed");
    std::printf("  [PASS] 16. SMP-MT 4-core concurrent LDREX/STREX atomic counter (%u == %u, 0 lost updates)\n",
                final_val, 4u * iters_per_core);
}

static void test_smp_mt_sgir_wfi_and_sev_wfe_stress(FujiDynEngine* eng) {
    /* Part A: 4-core GICD_SGIR + WFI ping-pong ring (5,000 total hops across Cores 0->1->2->3->0) */
    const uint32_t gicd_base = 0xFFFF1000u;
    const uint32_t gicc_base = 0xFFFF2000u;
    fuji_dyn_map_mmio(eng, gicd_base, 0x1000u);
    fuji_dyn_map_mmio(eng, gicc_base, 0x1000u);

    FujiDynGicShadow gic{};
    gic.active = 1u;
    gic.core_count = 4u;
    gic.dist_ctrl = 1u;
    for (uint32_t c = 0u; c < 4u; ++c) {
        gic.cores[c].cpu_ctrl = 1u;
        gic.cores[c].pmr = 0xF0u;
        gic.cores[c].bpr = 0u;
        gic.cores[c].local_enabled = 0xFFFFu; /* SGIs 0..15 enabled */
        for (uint32_t i = 0u; i < 32u; ++i) gic.cores[c].local_priority[i] = 0x40u;
    }
    fuji_dyn_set_gic_shadow_ptr(eng, gicd_base, gicc_base, &gic);
    fuji_dyn_set_smp_host_threads(eng, 1u);

    const uint32_t sgi_code_base = 0x00075000u;
    const uint32_t hops_pa = 0x00120100u;
    const uint32_t rounds_per_core = 1250u; /* 4 * 1250 = 5,000 hops */
    write_u32(eng, hops_pa, 0u);

    /* Each core runs with IRQ masked (CPSR.I = 1) so WFI wakes on pending SGI without taking vector:
     *   r0 = GICC_BASE, r1 = GICD_BASE, r2 = target_sgir_val, r3 = hops_pa, r4 = rounds_left, r5 = is_starter
     *   if (is_starter != 0) goto send_first;
     * loop:
     *   wfi
     *   ldr r6, [r0, #0x0C]  ; GICC_IAR
     *   str r6, [r0, #0x10]  ; GICC_EOIR
     *   ldr r7, [r3]         ; increment hops (only 1 core active in ring at a time!)
     *   add r7, r7, #1
     *   str r7, [r3]
     * send_first:
     *   subs r4, r4, #1
     *   strpl r2, [r1, #0xF00] ; GICD_SGIR -> wake next core (if r4 >= 0 before last send? wait send when >= 0)
     * Let's make the count exact: each core receives `rounds_per_core` SGIs and sends `rounds_per_core` SGIs:
     *   Core 0 starts with starter=1: sends SGI #1 to Core 1, then loops rounds_per_core times:
     *     WFI -> ack IAR/EOIR -> hops++ -> if (--rem > 0) send SGI to next!
     *   Cores 1..3 start with starter=0: loop rounds_per_core times:
     *     WFI -> ack IAR/EOIR -> hops++ -> send SGI to next -> --rem > 0
     *   Total hops = 4 * 1250 = 5,000!
     */
    const uint32_t sgi_prog[] = {
        /* 0x00 */ 0xE3550000u, /* cmp r5, #0 */
        /* 0x04 */ 0x15812F00u, /* strne r2, [r1, #0xF00] ; Core 0 kicks off Core 1 */
        /* loop (0x08): */
        /* 0x08 */ 0xE320F003u, /* wfi */
        /* 0x0C */ 0xE590600Cu, /* ldr r6, [r0, #0x0C] ; GICC_IAR */
        /* 0x10 */ 0xE5806010u, /* str r6, [r0, #0x10] ; GICC_EOIR */
        /* 0x14 */ 0xE5937000u, /* ldr r7, [r3] */
        /* 0x18 */ 0xE2877001u, /* add r7, r7, #1 */
        /* 0x1C */ 0xE5837000u, /* str r7, [r3] */
        /* 0x20 */ 0xE2544001u, /* subs r4, r4, #1 */
        /* 0x24 */ 0x03550000u, /* cmpeq r5, #0 ; if rem==0 && starter==1, skip final send */
        /* 0x28 */ 0x15812F00u, /* strne r2, [r1, #0xF00] ; send SGI unless (rem==0 && starter==1) */
        /* Wait: if rem > 0, Z=0 so cmpeq doesn't run, Z stays 0 -> strne runs!
         * If rem == 0 && starter == 0: cmpeq sets Z=1! Oops, we want Core 1..3 to send on rem==0!
         * Fix: use r8 = total_sends_left (Core 0: rounds-1 after kickoff, Cores 1..3: rounds)! */
        0xE320F003u,
    };
    (void)sgi_prog;
    const uint32_t sgi_prog_exact[] = {
        /* r0=GICC_BASE, r1=GICD_BASE, r2=sgir_val, r3=hops_pa, r4=recv_left, r5=send_left, r8=kickoff */
        /* 0x00 */ 0xE3580000u, /* cmp r8, #0 */
        /* 0x04 */ 0x15812F00u, /* strne r2, [r1, #0xF00] */
        /* loop (0x08): */
        /* 0x08 */ 0xE320F003u, /* wfi */
        /* 0x0C */ 0xE590600Cu, /* ldr r6, [r0, #0x0C] ; GICC_IAR */
        /* 0x10 */ 0xE5806010u, /* str r6, [r0, #0x10] ; GICC_EOIR */
        /* 0x14 */ 0xE5937000u, /* ldr r7, [r3] */
        /* 0x18 */ 0xE2877001u, /* add r7, r7, #1 */
        /* 0x1C */ 0xE5837000u, /* str r7, [r3] */
        /* 0x20 */ 0xE3550000u, /* cmp r5, #0 */
        /* 0x24 */ 0x12455001u, /* subne r5, r5, #1 */
        /* 0x28 */ 0x15812F00u, /* strne r2, [r1, #0xF00] */
        /* 0x2C */ 0xE2544001u, /* subs r4, r4, #1 */
        /* 0x30 */ 0x1AFFFFF4u, /* bne loop (-48 -> 0x08) */
        /* 0x34 */ 0xE320F003u, /* wfi (done) */
    };
    CHECK(fuji_dyn_mem_write(eng, sgi_code_base, sgi_prog_exact, sizeof(sgi_prog_exact)) == 0, "write sgi prog");
    fuji_dyn_clear_cache(eng);

    for (uint32_t c = 0u; c < 4u; ++c) {
        fuji_dyn_reset_core(eng, c);
        const uint32_t next_c = (c + 1u) & 3u;
        const uint32_t sgir_val = ((1u << next_c) << 16) | 1u; /* SGI #1 to next core */
        fuji_dyn_set_reg(eng, c, 0, gicc_base);
        fuji_dyn_set_reg(eng, c, 1, gicd_base);
        fuji_dyn_set_reg(eng, c, 2, sgir_val);
        fuji_dyn_set_reg(eng, c, 3, hops_pa);
        fuji_dyn_set_reg(eng, c, 4, rounds_per_core);
        fuji_dyn_set_reg(eng, c, 5, (c == 0u) ? (rounds_per_core - 1u) : rounds_per_core);
        fuji_dyn_set_reg(eng, c, 8, (c == 0u) ? 1u : 0u);
        fuji_dyn_set_reg(eng, c, 15, sgi_code_base);
    }

    uint32_t runnable = 0xFu;
    uint32_t wfi_mask = 0u;
    uint64_t vtick = 0u;
    uint32_t guard = 0u;
    while (runnable != 0u && guard < 20000u) {
        ++guard;
        FujiDynClusterRequest req{};
        req.runnable_mask = runnable;
        req.wfe_waiting_mask = (wfi_mask << 4);
        req.quantum = 512u;
        req.max_total_ticks = 500000u;
        req.start_vtick = vtick;
        FujiDynClusterResult res{};
        CHECK(fuji_dyn_run_cluster_fast(eng, &req, &res) == 0, "mt sgi run_cluster_fast");
        vtick = res.virtual_tick;
        wfi_mask = (wfi_mask | res.wfi_entered_mask) & ~res.wfi_woken_mask;
        runnable = (runnable & ~res.wfi_entered_mask) | res.wfi_woken_mask;
        if (res.stop_reason == FUJI_DYN_STOP_ALL_WAITING) break;
        CHECK(res.stop_reason == FUJI_DYN_STOP_QUANTUM || res.stop_reason == FUJI_DYN_STOP_MMIO_HALT,
              "unexpected stop_reason in mt SGI ring test");
    }
    const uint32_t total_hops = read_u32(eng, hops_pa);
    CHECK(total_hops == 4u * rounds_per_core, "mt GICD_SGIR+WFI ring missed wakeups!");
    fuji_dyn_clear_gic_shadow(eng);

    /* Part B: 4-core SEV/WFE spinlock contention (4 * 1,500 = 6,000 critical sections) */
    const uint32_t wfe_code_base = 0x00075400u;
    const uint32_t lock_pa = 0x00120200u;
    const uint32_t shared_pa = 0x00120204u;
    const uint32_t cs_per_core = 1500u;
    write_u32(eng, lock_pa, 0u);
    write_u32(eng, shared_pa, 0u);

    const uint32_t spinlock_prog[] = {
        /* r0 = lock_pa, r1 = shared_pa, r2 = cs_per_core, r6 = 1, r7 = 0 */
        /* lock_loop (0x00): */
        /* 0x00 */ 0xE1903F9Fu, /* ldrex r3, [r0] */
        /* 0x04 */ 0xE3530000u, /* cmp r3, #0 */
        /* 0x08 */ 0x0A000001u, /* beq try_lock (+12 -> 0x14) */
        /* 0x0C */ 0xE320F002u, /* wfe */
        /* 0x10 */ 0xEAFFFFFAu, /* b lock_loop (-24 -> 0x00) */
        /* try_lock (0x14): */
        /* 0x14 */ 0xE1804F96u, /* strex r4, r6, [r0] */
        /* 0x18 */ 0xE3540000u, /* cmp r4, #0 */
        /* 0x1C */ 0x1AFFFFF7u, /* bne lock_loop (-36 -> 0x00) */
        /* 0x20 */ 0xF57FF05Fu, /* dmb sy */
        /* Critical section (non-atomic RMW on shared_pa): */
        /* 0x24 */ 0xE5915000u, /* ldr r5, [r1] */
        /* 0x28 */ 0xE2855001u, /* add r5, r5, #1 */
        /* 0x2C */ 0xE5815000u, /* str r5, [r1] */
        /* 0x30 */ 0xF57FF05Fu, /* dmb sy */
        /* Unlock + SEV: */
        /* 0x34 */ 0xE5807000u, /* str r7, [r0] */
        /* 0x38 */ 0xF57FF04Fu, /* dsb sy */
        /* 0x3C */ 0xE320F004u, /* sev */
        /* 0x40 */ 0xE2522001u, /* subs r2, r2, #1 */
        /* 0x44 */ 0x1AFFFFEDu, /* bne lock_loop (-76 -> 0x00) */
        /* 0x48 */ 0xE320F003u, /* wfi */
    };
    CHECK(fuji_dyn_mem_write(eng, wfe_code_base, spinlock_prog, sizeof(spinlock_prog)) == 0, "write spinlock prog");
    fuji_dyn_clear_cache(eng);
    fuji_dyn_set_wfe_event_mask(eng, 0u);

    for (uint32_t c = 0u; c < 4u; ++c) {
        fuji_dyn_reset_core(eng, c);
        fuji_dyn_set_reg(eng, c, 0, lock_pa);
        fuji_dyn_set_reg(eng, c, 1, shared_pa);
        fuji_dyn_set_reg(eng, c, 2, cs_per_core);
        fuji_dyn_set_reg(eng, c, 6, 1u);
        fuji_dyn_set_reg(eng, c, 7, 0u);
        fuji_dyn_set_reg(eng, c, 15, wfe_code_base);
    }

    runnable = 0xFu;
    wfi_mask = 0u;
    uint32_t wfe_mask = 0u;
    guard = 0u;
    while (runnable != 0u && guard < 10000u) {
        ++guard;
        FujiDynClusterRequest req{};
        req.runnable_mask = runnable;
        req.wfe_waiting_mask = (wfi_mask << 4) | (wfe_mask & 0xFu);
        req.quantum = 512u;
        req.max_total_ticks = 500000u;
        req.start_vtick = vtick;
        FujiDynClusterResult res{};
        CHECK(fuji_dyn_run_cluster_fast(eng, &req, &res) == 0, "mt spinlock run_cluster_fast");
        vtick = res.virtual_tick;
        wfi_mask |= res.wfi_entered_mask;
        wfe_mask |= res.wfe_entered_mask;
        runnable &= ~(res.wfi_entered_mask | res.wfe_entered_mask);
        if (res.sev_mask != 0u) {
            uint32_t woken = wfe_mask;
            wfe_mask &= ~woken;
            runnable |= woken;
        }
        if (res.stop_reason == FUJI_DYN_STOP_ALL_WAITING) break;
    }

    const uint32_t final_shared = read_u32(eng, shared_pa);
    CHECK(runnable == 0u && wfe_mask == 0u, "mt SEV/WFE spinlock deadlocked!");
    CHECK(final_shared == 4u * cs_per_core, "mt SEV/WFE spinlock mutual exclusion violated!");
    fuji_dyn_set_smp_host_threads(eng, 0u);
    std::printf("  [PASS] 17. SMP-MT 4-core GICD_SGIR+WFI (%u hops) & SEV/WFE spinlock (%u CS, 0 deadlocks)\n",
                total_hops, final_shared);
}

static void test_smp_mt_cross_core_tlbi_iciallu_smc(FujiDynEngine* eng) {
    /* 4 cores with MMU enabled: Core 0 patches code @ 0x00140000 across 32 rounds, executes
     * ICIALLUIS + TLBIALLIS, publishes generation; Cores 1..3 call patched code and verify return == gen. */
    const uint32_t l1_base = 0x00080000u;
    std::vector<uint8_t> zeros(0x4000, 0);
    CHECK(fuji_dyn_mem_write(eng, l1_base, zeros.data(), static_cast<uint32_t>(zeros.size())) == 0, "zero l1 smc");
    for (uint32_t s = 0u; s < 4u; ++s) {
        write_u32(eng, l1_base + (s << 2), (s << 20) | (3u << 10) | 2u);
    }

    const uint32_t target_fn_pa = 0x00140000u;
    const uint32_t gen_pa = 0x00120300u;
    const uint32_t ack_pa = 0x00120304u; /* ack[1..3] at +4, +8, +12 */
    const uint32_t err_pa = 0x00120320u;
    const uint32_t rounds = 32u;

    /* Initial target_fn: mov r0, #0; bx lr */
    write_u32(eng, target_fn_pa, 0xE3A00000u);
    write_u32(eng, target_fn_pa + 4u, 0xE12FFF1Eu);
    write_u32(eng, gen_pa, 0u);
    for (uint32_t c = 0u; c < 4u; ++c) write_u32(eng, ack_pa + c * 4u, 0u);
    write_u32(eng, err_pa, 0u);

    /* Core 0 writer program:
     * r0=l1_base, r1=0x55555555, r3=target_fn_pa, r4=gen_pa, r5=ack_pa, r6=rounds
     * Enable MMU, then for gen = 1..rounds:
     *   patch [r3] = 0xE3A00000 | gen (mov r0, #gen)
     *   DSB; ICIALLUIS; TLBIALLIS; DSB; ISB
     *   [r4] = gen
     *   wait until ack[1]==gen && ack[2]==gen && ack[3]==gen
     * wfi */
    const uint32_t c0_code_base = 0x00076000u;
    const uint32_t c0_prog[] = {
        /* 0x00 */ 0xEE020F10u, /* mcr p15, 0, r0, c2, c0, 0 (TTBR0) */
        /* 0x04 */ 0xEE031F10u, /* mcr p15, 0, r1, c3, c0, 0 (DACR) */
        /* 0x08 */ 0xEE112F10u, /* mrc p15, 0, r2, c1, c0, 0 (SCTLR) */
        /* 0x0C */ 0xE3822001u, /* orr r2, r2, #1 */
        /* 0x10 */ 0xEE012F10u, /* mcr p15, 0, r2, c1, c0, 0 (SCTLR) */
        /* 0x14 */ 0xE3A07001u, /* mov r7, #1 ; gen = 1 */
        /* 0x18 */ 0xE3A084E3u, /* mov r8, #0xE3000000 */
        /* 0x1C */ 0xE38888A0u, /* orr r8, r8, #0x00A00000 ; mov r0, #0 template */
        /* round_loop (0x20): */
        /* 0x20 */ 0xE1889007u, /* orr r9, r8, r7 ; mov r0, #gen */
        /* 0x24 */ 0xE5839000u, /* str r9, [r3] ; patch target_fn */
        /* 0x28 */ 0xF57FF04Fu, /* dsb sy */
        /* 0x2C */ 0xEE070F11u, /* mcr p15, 0, r0, c7, c1, 0 (ICIALLUIS) */
        /* 0x30 */ 0xEE080F13u, /* mcr p15, 0, r0, c8, c3, 0 (TLBIALLIS) */
        /* 0x34 */ 0xF57FF04Fu, /* dsb sy */
        /* 0x38 */ 0xF57FF06Fu, /* isb sy */
        /* 0x3C */ 0xE5847000u, /* str r7, [r4] ; publish gen */
        /* wait_acks (0x40): */
        /* 0x40 */ 0xE595A004u, /* ldr r10, [r5, #4] */
        /* 0x44 */ 0xE15A0007u, /* cmp r10, r7 */
        /* 0x48 */ 0x1AFFFFFCu, /* bne wait_acks (-16 -> 0x40) */
        /* 0x4C */ 0xE595A008u, /* ldr r10, [r5, #8] */
        /* 0x50 */ 0xE15A0007u, /* cmp r10, r7 */
        /* 0x54 */ 0x1AFFFFF9u, /* bne wait_acks (-28 -> 0x40) */
        /* 0x58 */ 0xE595A00Cu, /* ldr r10, [r5, #12] */
        /* 0x5C */ 0xE15A0007u, /* cmp r10, r7 */
        /* 0x60 */ 0x1AFFFFF6u, /* bne wait_acks (-40 -> 0x40) */
        /* 0x64 */ 0xE2877001u, /* add r7, r7, #1 */
        /* 0x68 */ 0xE1570006u, /* cmp r7, r6 */
        /* 0x6C */ 0xDAFFFFEBu, /* ble round_loop (-84 -> 0x20) */
        /* 0x70 */ 0xE320F003u, /* wfi */
    };

    /* Cores 1..3 reader program:
     * r0=l1_base, r1=0x55555555, r3=target_fn_pa, r4=gen_pa, r5=my_ack_pa, r6=rounds, r11=err_pa */
    const uint32_t cx_code_base = 0x00076400u;
    const uint32_t cx_prog[] = {
        /* 0x00 */ 0xEE020F10u, /* mcr p15, 0, r0, c2, c0, 0 (TTBR0) */
        /* 0x04 */ 0xEE031F10u, /* mcr p15, 0, r1, c3, c0, 0 (DACR) */
        /* 0x08 */ 0xEE112F10u, /* mrc p15, 0, r2, c1, c0, 0 (SCTLR) */
        /* 0x0C */ 0xE3822001u, /* orr r2, r2, #1 */
        /* 0x10 */ 0xEE012F10u, /* mcr p15, 0, r2, c1, c0, 0 (SCTLR) */
        /* 0x14 */ 0xE3A07001u, /* mov r7, #1 ; expected gen */
        /* wait_gen (0x18): */
        /* 0x18 */ 0xE5948000u, /* ldr r8, [r4] */
        /* 0x1C */ 0xE1580007u, /* cmp r8, r7 */
        /* 0x20 */ 0xBAFFFFFCu, /* blt wait_gen (-16 -> 0x18) */
        /* 0x24 */ 0xF57FF06Fu, /* isb sy */
        /* 0x28 */ 0xE12FFF33u, /* blx r3 ; call target_fn -> r0 */
        /* 0x2C */ 0xE1500007u, /* cmp r0, r7 */
        /* 0x30 */ 0x158B0000u, /* strne r0, [r11] ; record mismatch! */
        /* 0x34 */ 0x158B7004u, /* strne r7, [r11, #4] ; record expected gen! */
        /* 0x38 */ 0xF57FF04Fu, /* dsb sy */
        /* 0x3C */ 0xE5857000u, /* str r7, [r5] ; ack[core] = gen */
        /* 0x40 */ 0xE2877001u, /* add r7, r7, #1 */
        /* 0x44 */ 0xE1570006u, /* cmp r7, r6 */
        /* 0x48 */ 0xDAFFFFF2u, /* ble wait_gen (-56 -> 0x18) */
        /* 0x4C */ 0xE320F003u, /* wfi */
    };
    CHECK(fuji_dyn_mem_write(eng, c0_code_base, c0_prog, sizeof(c0_prog)) == 0, "write c0 smc prog");
    CHECK(fuji_dyn_mem_write(eng, cx_code_base, cx_prog, sizeof(cx_prog)) == 0, "write cx smc prog");
    fuji_dyn_clear_cache(eng);
    fuji_dyn_set_smp_host_threads(eng, 1u);

    for (uint32_t c = 0u; c < 4u; ++c) {
        fuji_dyn_reset_core(eng, c);
        fuji_dyn_set_reg(eng, c, 0, l1_base);
        fuji_dyn_set_reg(eng, c, 1, 0x55555555u);
        fuji_dyn_set_reg(eng, c, 3, target_fn_pa);
        fuji_dyn_set_reg(eng, c, 4, gen_pa);
        fuji_dyn_set_reg(eng, c, 5, (c == 0u) ? ack_pa : (ack_pa + c * 4u));
        fuji_dyn_set_reg(eng, c, 6, rounds);
        fuji_dyn_set_reg(eng, c, 11, err_pa);
        fuji_dyn_set_reg(eng, c, 15, (c == 0u) ? c0_code_base : cx_code_base);
    }

    uint32_t runnable = 0xFu;
    uint32_t wfi_mask = 0u;
    uint64_t vtick = 0u;
    uint32_t guard = 0u;
    while (runnable != 0u && guard < 10000u) {
        ++guard;
        FujiDynClusterRequest req{};
        req.runnable_mask = runnable;
        req.wfe_waiting_mask = (wfi_mask << 4);
        req.quantum = 256u;
        req.max_total_ticks = 500000u;
        req.start_vtick = vtick;
        FujiDynClusterResult res{};
        CHECK(fuji_dyn_run_cluster_fast(eng, &req, &res) == 0, "mt smc run_cluster_fast");
        vtick = res.virtual_tick;
        wfi_mask |= res.wfi_entered_mask;
        runnable &= ~res.wfi_entered_mask;
        if (res.stop_reason == FUJI_DYN_STOP_ALL_WAITING) break;
        CHECK(res.stop_reason == FUJI_DYN_STOP_QUANTUM, "unexpected stop_reason in mt SMC test");
    }

    CHECK(runnable == 0u, "mt SMC/TLBIALLIS test did not finish all 4 cores");
    CHECK(read_u32(eng, err_pa) == 0u, "mt SMC/ICIALLUIS stale JIT code executed on secondary core!");
    CHECK(read_u32(eng, gen_pa) == rounds, "mt SMC gen mismatch");
    fuji_dyn_set_smp_host_threads(eng, 0u);
    std::printf("  [PASS] 18. SMP-MT 4-core cross-core ICIALLUIS + TLBIALLIS + SMC coherency (%u rounds, 0 stale fetches)\n",
                rounds);
}

static void test_smp_mt_mmio_owner_thread_and_timer_irq_interleaving(FujiDynEngine* eng, TestMmioState* orig_mmio) {
    struct MtMmioVerify {
        std::thread::id owner_tid;
        std::atomic<uint32_t> in_cb{0u};
        std::atomic<uint32_t> wrong_thread{0u};
        std::atomic<uint32_t> reentrant{0u};
        uint32_t reads = 0u;
        uint32_t writes = 0u;
        uint32_t reg_vals[4]{};
    };
    MtMmioVerify mv{};
    mv.owner_tid = std::this_thread::get_id();

    auto mt_read = [](void* user, uint32_t pa, uint32_t sz) -> uint64_t {
        auto* m = static_cast<MtMmioVerify*>(user);
        if (std::this_thread::get_id() != m->owner_tid) m->wrong_thread.fetch_add(1u);
        if (m->in_cb.exchange(1u) != 0u) m->reentrant.fetch_add(1u);
        (void)sz;
        m->reads++;
        uint32_t slot = ((pa - 0x40001000u) >> 2) & 3u;
        uint32_t val = m->reg_vals[slot];
        m->in_cb.store(0u);
        return val;
    };
    auto mt_write = [](void* user, uint32_t pa, uint32_t sz, uint64_t val) {
        auto* m = static_cast<MtMmioVerify*>(user);
        if (std::this_thread::get_id() != m->owner_tid) m->wrong_thread.fetch_add(1u);
        if (m->in_cb.exchange(1u) != 0u) m->reentrant.fetch_add(1u);
        (void)sz;
        m->writes++;
        uint32_t slot = ((pa - 0x40001000u) >> 2) & 3u;
        m->reg_vals[slot] = static_cast<uint32_t>(val);
        m->in_cb.store(0u);
    };

    fuji_dyn_set_mmio_callbacks(eng, mt_read, mt_write, &mv);
    fuji_dyn_set_smp_host_threads(eng, 1u);

    /* Configure inline GIC + Timer IRQ firing every 1000 vticks to Core 0 (IRQ #40) */
    const uint32_t gicd_base = 0xFFFF1000u;
    const uint32_t gicc_base = 0xFFFF2000u;
    const uint32_t timer_base = 0xFFF3C000u;
    fuji_dyn_map_mmio(eng, gicd_base, 0x1000u);
    fuji_dyn_map_mmio(eng, gicc_base, 0x1000u);
    fuji_dyn_map_mmio(eng, timer_base, 0x1000u);

    FujiDynGicShadow gic{};
    gic.active = 1u;
    gic.core_count = 4u;
    gic.dist_ctrl = 1u;
    const uint32_t irq = 40u;
    gic.shared_enabled[irq >> 6] |= (1ull << (irq & 63u));
    gic.shared_edge[irq >> 6] |= (1ull << (irq & 63u));
    gic.shared_priority[irq - 32u] = 0x20u;
    gic.shared_targets[irq - 32u] = 0x1u; /* Core 0 */
    gic.target_core_bits[0][irq >> 6] |= (1ull << (irq & 63u));
    for (uint32_t c = 0u; c < 4u; ++c) {
        gic.cores[c].cpu_ctrl = 1u;
        gic.cores[c].pmr = 0xF0u;
    }

    FujiDynTimerShadow tsh{};
    tsh.active = 1u;
    tsh.channels[0].enabled = 1u;
    tsh.channels[0].has_deadline = 1u;
    tsh.channels[0].reload = 999u;
    tsh.channels[0].deadline = 1000u;

    FujiDynTimerIrqShadow tis{};
    tis.active = 1u;
    tis.next_other_deadline = 0xFFFFFFFFFFFFFFFFull;
    for (uint32_t i = 0u; i < FUJI_DYN_TIMER_CHANNELS; ++i) tis.irq_routes[i] = -1;
    tis.irq_routes[0] = static_cast<int32_t>(irq);

    fuji_dyn_set_gic_shadow_ptr(eng, gicd_base, gicc_base, &gic);
    fuji_dyn_set_timer_shadow_ptr(eng, timer_base, &tsh);
    fuji_dyn_set_timer_irq_shadow_ptr(eng, &tsh, &tis);

    /* IRQ vector at VBAR=0x00077000 (+0x18): ack GICC_IAR, write GICC_EOIR, inc [0x00120400], subs pc, lr, #4 */
    const uint32_t vbar = 0x00077000u;
    const uint32_t irq_cnt_pa = 0x00120400u;
    write_u32(eng, irq_cnt_pa, 0u);
    write_u32(eng, vbar + 0x18u, 0xEA000008u); /* b +0x40 (vbar + 0x40) */
    const uint32_t irq_handler[] = {
        /* vbar + 0x40 */
        0xE92D000Fu, /* push {r0-r3} */
        0xE59F001Cu, /* ldr r0, [pc, #28] ; gicc_base */
        0xE590100Cu, /* ldr r1, [r0, #0x0C] ; GICC_IAR */
        0xE5801010u, /* str r1, [r0, #0x10] ; GICC_EOIR */
        0xE59F2014u, /* ldr r2, [pc, #20] ; irq_cnt_pa */
        0xE5923000u, /* ldr r3, [r2] */
        0xE2833001u, /* add r3, r3, #1 */
        0xE5823000u, /* str r3, [r2] */
        0xE8BD000Fu, /* pop {r0-r3} */
        0xE25EF004u, /* subs pc, lr, #4 */
        gicc_base,
        irq_cnt_pa,
    };
    CHECK(fuji_dyn_mem_write(eng, vbar + 0x40u, irq_handler, sizeof(irq_handler)) == 0, "write irq handler");

    /* Each core performs 1,000 MMIO write + 1,000 MMIO read = 2,000 upcalls (8,000 total across 4 cores) */
    const uint32_t mmio_code_base = 0x00077400u;
    const uint32_t ops_per_core = 1000u;
    const uint32_t mmio_prog[] = {
        /* r0 = my_mmio_addr (0x40001000 + core*4), r1 = ops_per_core, r2 = core_tag, r3 = mismatch_count */
        /* loop (0x00): */
        /* 0x00 */ 0xE0824001u, /* add r4, r2, r1 */
        /* 0x04 */ 0xE5804000u, /* str r4, [r0] */
        /* 0x08 */ 0xE5905000u, /* ldr r5, [r0] */
        /* 0x0C */ 0xE1550004u, /* cmp r5, r4 */
        /* 0x10 */ 0x12833001u, /* addne r3, r3, #1 */
        /* 0x14 */ 0xE2511001u, /* subs r1, r1, #1 */
        /* 0x18 */ 0x1AFFFFF8u, /* bne loop (-32 -> 0x00) */
        /* 0x1C */ 0xE320F003u, /* wfi */
        /* 0x20 */ 0xEAFFFFFDu, /* b -4 -> re-enter wfi if woken by Timer IRQ */
    };
    CHECK(fuji_dyn_mem_write(eng, mmio_code_base, mmio_prog, sizeof(mmio_prog)) == 0, "write mt mmio prog");
    fuji_dyn_clear_cache(eng);

    for (uint32_t c = 0u; c < 4u; ++c) {
        fuji_dyn_reset_core(eng, c);
        fuji_dyn_set_cp15(eng, c, 0, 12, 0, 0, vbar);
        fuji_dyn_set_banked_reg(eng, c, FUJI_DYN_MODE_IRQ, 13, 0x00130000u + c * 0x1000u);
        fuji_dyn_set_reg(eng, c, 0, 0x40001000u + c * 4u);
        fuji_dyn_set_reg(eng, c, 1, ops_per_core);
        fuji_dyn_set_reg(eng, c, 2, (c + 1u) << 16);
        fuji_dyn_set_reg(eng, c, 3, 0u);
        fuji_dyn_set_reg(eng, c, 15, mmio_code_base);
        if (c == 0u) {
            fuji_dyn_set_cpsr(eng, c, FUJI_DYN_MODE_SVC | 0x40u); /* IRQ unmasked on Core 0! */
        }
    }

    uint32_t runnable = 0xFu;
    uint32_t wfi_mask = 0u;
    uint64_t vtick = 0u;
    uint32_t irqs_delivered = 0u;
    uint32_t guard = 0u;
    while (runnable != 0u && guard < 10000u) {
        ++guard;
        FujiDynClusterRequest req{};
        req.runnable_mask = runnable;
        req.wfe_waiting_mask = (wfi_mask << 4);
        req.quantum = 512u;
        req.max_total_ticks = 200000u;
        req.start_vtick = vtick;
        FujiDynClusterResult res{};
        CHECK(fuji_dyn_run_cluster_fast(eng, &req, &res) == 0, "mt mmio run_cluster_fast");
        vtick = res.virtual_tick;
        wfi_mask = (wfi_mask | res.wfi_entered_mask) & ~res.wfi_woken_mask;
        runnable = (runnable & ~res.wfi_entered_mask) | res.wfi_woken_mask;
        if (res.stop_reason == FUJI_DYN_STOP_IRQ) {
            CHECK(fuji_dyn_deliver_irq(eng, res.active_core) == 0, "deliver_irq in mt mmio test");
            ++irqs_delivered;
        } else if (res.stop_reason == FUJI_DYN_STOP_ALL_WAITING) {
            break;
        } else {
            CHECK(res.stop_reason == FUJI_DYN_STOP_QUANTUM || res.stop_reason == FUJI_DYN_STOP_MMIO_HALT,
                  "unexpected stop_reason in mt mmio test");
        }
    }

    CHECK(runnable == 0u, "mt mmio+irq test did not finish all 4 cores");
    CHECK(mv.wrong_thread.load() == 0u, "CRITICAL: MMIO upcall executed off owner thread!");
    CHECK(mv.reentrant.load() == 0u, "CRITICAL: concurrent re-entrant MMIO upcall detected!");
    CHECK(mv.reads == 4u * ops_per_core && mv.writes == 4u * ops_per_core, "mt MMIO read/write count mismatch");
    for (uint32_t c = 0u; c < 4u; ++c) {
        CHECK(fuji_dyn_get_reg(eng, c, 3) == 0u, "mt MMIO readback corruption on core!");
    }
    const uint32_t irq_cnt = read_u32(eng, irq_cnt_pa);
    CHECK(irqs_delivered > 0u && irq_cnt == irqs_delivered, "mt Timer IRQ delivery count mismatch");

    fuji_dyn_clear_timer_irq_shadow(eng);
    fuji_dyn_clear_timer_shadow(eng);
    fuji_dyn_clear_gic_shadow(eng);
    fuji_dyn_set_smp_host_threads(eng, 0u);
    fuji_dyn_set_mmio_callbacks(eng, test_mmio_read, test_mmio_write, orig_mmio);
    std::printf("  [PASS] 19. SMP-MT owner-thread-only MMIO proxy (%uR+%uW, 0 off-thread) + %u Timer IRQs\n",
                mv.reads, mv.writes, irq_cnt);
}

static void test_smp_mt_lifecycle_reset_destroy_stress() {
    const uint32_t code[] = {
        0xE3A00064u, /* mov r0, #100 */
        0xE2500001u, /* subs r0, r0, #1 */
        0x1AFFFFFDu, /* bne -12 */
        0xE320F003u, /* wfi */
    };
    for (uint32_t cycle = 0u; cycle < 20u; ++cycle) {
        FujiDynEngine* e = fuji_dyn_create(4u, 0xFFFF0000u);
        CHECK(e != nullptr, "lifecycle create failed");
        CHECK(fuji_dyn_map_ram(e, 0x00000000u, 2u * 1024u * 1024u, nullptr, FUJI_DYN_PERM_ALL) == 0, "lifecycle map_ram");
        CHECK(fuji_dyn_mem_write(e, 0x00010000u, code, sizeof(code)) == 0, "lifecycle write code");
        fuji_dyn_set_smp_host_threads(e, 1u);
        for (uint32_t pass = 0u; pass < 2u; ++pass) {
            for (uint32_t c = 0u; c < 4u; ++c) {
                fuji_dyn_reset_core(e, c);
                fuji_dyn_set_reg(e, c, 15, 0x00010000u);
            }
            FujiDynClusterRequest req{};
            req.runnable_mask = 0xFu;
            req.quantum = 256u;
            req.max_total_ticks = 100000u;
            FujiDynClusterResult res{};
            CHECK(fuji_dyn_run_cluster_fast(e, &req, &res) == 0, "lifecycle run");
            CHECK(res.stop_reason == FUJI_DYN_STOP_ALL_WAITING, "lifecycle expected ALL_WAITING");
            for (uint32_t c = 0u; c < 4u; ++c) {
                CHECK(fuji_dyn_get_reg(e, c, 0) == 0u, "lifecycle r0 != 0");
            }
            fuji_dyn_clear_cache(e);
            if (pass == 0u) {
                fuji_dyn_set_smp_host_threads(e, 0u);
                fuji_dyn_set_smp_host_threads(e, 1u);
            }
        }
        fuji_dyn_destroy(e);
    }
    std::printf("  [PASS] 20. SMP-MT rapid create/enable/run/reset/toggle/destroy lifecycle stress (20 cycles, 0 hangs)\n");
}

static void bench_smp_mt_4core_ab(FujiDynEngine* eng) {
    /* Pure ARM32 4-core compute kernel: 4 cores * 2,000,000 iters * 4 insns = 32,000,000 ARM32 insns */
    const uint32_t code_base = 0x00078000u;
    const uint32_t iters = 2000000u;
    const uint32_t code[] = {
        /* r0 = acc, r1 = iters, r2 = step */
        /* loop (0x00): */
        0xE0800002u, /* add r0, r0, r2 */
        0xE0200181u, /* eor r0, r0, r1, lsl #3 */
        0xE2511001u, /* subs r1, r1, #1 */
        0x1AFFFFFBu, /* bne loop (-20 -> 0x00) */
        0xE320F003u, /* wfi */
    };
    CHECK(fuji_dyn_mem_write(eng, code_base, code, sizeof(code)) == 0, "write ab bench code");
    fuji_dyn_clear_cache(eng);
    fuji_dyn_set_vtick_divider(eng, 1u);

    auto run_mode = [&](uint32_t mt_flag, uint32_t out_r0[4], uint64_t& out_insns) -> double {
        fuji_dyn_set_smp_host_threads(eng, mt_flag);
        for (uint32_t c = 0u; c < 4u; ++c) {
            fuji_dyn_reset_core(eng, c);
            fuji_dyn_set_reg(eng, c, 0, 0x13579BDFu ^ (c * 0x11111111u));
            fuji_dyn_set_reg(eng, c, 1, iters);
            fuji_dyn_set_reg(eng, c, 2, 0x9E3779B9u + c);
            fuji_dyn_set_reg(eng, c, 15, code_base);
        }
        auto t0 = std::chrono::steady_clock::now();
        uint32_t runnable = 0xFu;
        uint32_t wfi_mask = 0u;
        uint64_t vtick = 0u;
        out_insns = 0u;
        while (runnable != 0u) {
            FujiDynClusterRequest req{};
            req.runnable_mask = runnable;
            req.wfe_waiting_mask = (wfi_mask << 4);
            req.quantum = 4096u;
            req.max_total_ticks = 64000000u;
            req.start_vtick = vtick;
            FujiDynClusterResult res{};
            CHECK(fuji_dyn_run_cluster_fast(eng, &req, &res) == 0, "ab bench run");
            vtick = res.virtual_tick;
            out_insns += res.total_executed;
            wfi_mask |= res.wfi_entered_mask;
            runnable &= ~res.wfi_entered_mask;
        }
        auto t1 = std::chrono::steady_clock::now();
        for (uint32_t c = 0u; c < 4u; ++c) out_r0[c] = fuji_dyn_get_reg(eng, c, 0);
        return std::chrono::duration<double, std::milli>(t1 - t0).count();
    };

    /* Warm JIT caches once */
    uint32_t warm_r0[4]{};
    uint64_t warm_insns = 0u;
    (void)run_mode(0u, warm_r0, warm_insns);

    uint32_t st_r0[4]{}, mt_r0[4]{};
    uint64_t st_insns = 0u, mt_insns = 0u;
    const double st_ms = run_mode(0u, st_r0, st_insns);
    const double mt_ms = run_mode(1u, mt_r0, mt_insns);
    fuji_dyn_set_smp_host_threads(eng, 0u);

    for (uint32_t c = 0u; c < 4u; ++c) {
        CHECK(st_r0[c] == mt_r0[c], "A/B compute result mismatch between ST and MT!");
    }
    const double st_mips = (st_ms > 0.0) ? (static_cast<double>(st_insns) / (st_ms * 1000.0)) : 0.0;
    const double mt_mips = (mt_ms > 0.0) ? (static_cast<double>(mt_insns) / (mt_ms * 1000.0)) : 0.0;
    const double speedup = (mt_ms > 0.0) ? (st_ms / mt_ms) : 0.0;
    std::printf("  [PASS] 21. SMP-MT 4-core A/B compute: ST=%.2f ms (%.1f MIPS) vs MT=%.2f ms (%.1f MIPS) -> %.2fx speedup (bit-exact)\n",
                st_ms, st_mips, mt_ms, mt_mips, speedup);
}

static void test_declarative_mmio_mirror_bank(FujiDynEngine* eng, TestMmioState* orig_mmio) {
    struct alignas(8) MirrorBlock {
        FujiDynMmioMirrorHeader hdr{};
        FujiDynMmioMirrorSlot slots[4]{};
    };
    MirrorBlock blk{};
    blk.hdr.slot_count = 3u;
    blk.hdr.valid_mask = 0x7ull;
    blk.slots[0] = FujiDynMmioMirrorSlot{0x00u, 4u, 0x11223344u, 0u}; /* u32 */
    blk.slots[1] = FujiDynMmioMirrorSlot{0x04u, 2u, 0x0000BEEFu, 0u}; /* u16 */
    blk.slots[2] = FujiDynMmioMirrorSlot{0x08u, 1u, 0x000000A5u, 0u}; /* u8  */

    struct MirrorTestCtx {
        MirrorBlock* blk = nullptr;
        std::atomic<uint32_t> upcall_reads{0u};
        std::atomic<uint32_t> upcall_writes{0u};
    } ctx{&blk};

    auto rd_cb = [](void* user, uint32_t pa, uint32_t sz) -> uint64_t {
        auto* c = static_cast<MirrorTestCtx*>(user);
        c->upcall_reads.fetch_add(1u, std::memory_order_relaxed);
        if (pa == 0x40005000u && sz == 2u) return 0x3344u;        /* width mismatch fallback */
        if (pa == 0x40005008u && sz == 1u) return 0x5Au;          /* invalid-bit fallback */
        if (pa == 0x4000500Cu && sz == 4u) return 0xCAFEBABEu;    /* unmirrored FIFO-pop offset */
        return 0xDEADBEEFu;
    };
    auto wr_cb = [](void* user, uint32_t pa, uint32_t sz, uint64_t val) {
        auto* c = static_cast<MirrorTestCtx*>(user);
        (void)sz;
        c->upcall_writes.fetch_add(1u, std::memory_order_relaxed);
        if (pa == 0x40005000u) {
            /* Live peripheral state update stays hot in mirror across writes! */
            std::atomic_ref<uint32_t>(c->blk->slots[0].value).store(static_cast<uint32_t>(val ^ 0xFFu), std::memory_order_release);
        } else if (pa == 0x40005008u) {
            /* Toggle valid_mask bit 2 off so subsequent read falls back to upcall */
            std::atomic_ref<uint64_t>(c->blk->hdr.valid_mask).store(0x3ull, std::memory_order_release);
        }
    };

    fuji_dyn_set_mmio_callbacks(eng, rd_cb, wr_cb, &ctx);
    CHECK(fuji_dyn_register_mmio_mirror(eng, 0x40005000u, 0x40u, &blk) == 0, "register_mmio_mirror");

    /* Program on Core 0:
     * r10 = 0x40005000
     * 1) ldr  r0, [r10, #0]    -> mirror hit 0x11223344 (0 upcalls)
     * 2) ldrh r1, [r10, #4]    -> mirror hit 0xBEEF     (0 upcalls)
     * 3) ldrb r2, [r10, #8]    -> mirror hit 0xA5       (0 upcalls)
     * 4) str  r0, [r10, #0]    -> upcall write updates slot 0 to 0x112233BB
     * 5) ldr  r3, [r10, #0]    -> mirror hit 0x112233BB (0 upcalls, persists across write!)
     * 6) ldrh r4, [r10, #0]    -> width mismatch (2 != 4) -> upcall read 0x3344
     * 7) strb r2, [r10, #8]    -> upcall write clears valid_mask bit 2
     * 8) ldrb r5, [r10, #8]    -> valid bit 0 -> upcall read 0x5A
     * 9) ldr  r6, [r10, #12]   -> unmirrored offset -> upcall read 0xCAFEBABE
     * 10) wfi */
    const uint32_t prog[] = {
        0xE59A0000u, /* ldr  r0, [r10, #0] */
        0xE1DA10B4u, /* ldrh r1, [r10, #4] */
        0xE5DA2008u, /* ldrb r2, [r10, #8] */
        0xE58A0000u, /* str  r0, [r10, #0] */
        0xE59A3000u, /* ldr  r3, [r10, #0] */
        0xE1DA40B0u, /* ldrh r4, [r10, #0] */
        0xE5CA2008u, /* strb r2, [r10, #8] */
        0xE5DA5008u, /* ldrb r5, [r10, #8] */
        0xE59A600Cu, /* ldr  r6, [r10, #12] */
        0xE320F003u, /* wfi */
    };
    const uint32_t code_base = 0x0007A000u;
    CHECK(fuji_dyn_mem_write(eng, code_base, prog, sizeof(prog)) == 0, "write mirror prog");
    fuji_dyn_clear_cache(eng);
    fuji_dyn_reset_mmio_stats(eng);
    fuji_dyn_reset_core(eng, 0u);
    fuji_dyn_set_reg(eng, 0u, 10u, 0x40005000u);
    fuji_dyn_set_reg(eng, 0u, 15u, code_base);

    FujiDynRunResult rr{};
    CHECK(fuji_dyn_run_fast(eng, 0u, 1000u, 0u, 0u, &rr) == 0, "run mirror prog");
    CHECK(rr.stop_reason == FUJI_DYN_STOP_WFI, "mirror prog WFI stop");
    CHECK(fuji_dyn_get_reg(eng, 0u, 0u) == 0x11223344u, "mirror u32 read");
    CHECK(fuji_dyn_get_reg(eng, 0u, 1u) == 0x0000BEEFu, "mirror u16 read");
    CHECK(fuji_dyn_get_reg(eng, 0u, 2u) == 0x000000A5u, "mirror u8 read");
    CHECK(fuji_dyn_get_reg(eng, 0u, 3u) == 0x112233BBu, "mirror persists live value across write");
    CHECK(fuji_dyn_get_reg(eng, 0u, 4u) == 0x00003344u, "width-mismatch fallback to upcall");
    CHECK(fuji_dyn_get_reg(eng, 0u, 5u) == 0x0000005Au, "cleared valid_mask fallback to upcall");
    CHECK(fuji_dyn_get_reg(eng, 0u, 6u) == 0xCAFEBABEu, "unmirrored offset fallback to upcall");
    CHECK(ctx.upcall_reads.load() == 3u, "exact 3 upcall reads (width mismatch + invalid bit + unmirrored)");
    CHECK(ctx.upcall_writes.load() == 2u, "exact 2 upcall writes");
    CHECK(rr.fast_mmio_hits == 4u, "exact 4 native mirror fast hits");

    /* Part B: 4-core SMP-MT lock-free concurrent mirror reads (0 read upcalls!) + owner writes */
    std::atomic_ref<uint32_t>(blk.slots[0].value).store(0x00000001u, std::memory_order_release);
    std::atomic_ref<uint64_t>(blk.hdr.valid_mask).store(0x7ull, std::memory_order_release);
    ctx.upcall_reads.store(0u);
    ctx.upcall_writes.store(0u);
    const uint32_t mt_prog[] = {
        /* loop: r1 counts 500 reads of [r10, #0] */
        0xE59A0000u, /* ldr r0, [r10, #0] */
        0xE0822000u, /* add r2, r2, r0 */
        0xE2511001u, /* subs r1, r1, #1 */
        0x1AFFFFFBu, /* bne loop (-20 -> 0x00) */
        0xE320F003u, /* wfi */
    };
    const uint32_t mt_code_base = 0x0007A100u;
    CHECK(fuji_dyn_mem_write(eng, mt_code_base, mt_prog, sizeof(mt_prog)) == 0, "write mt mirror prog");
    fuji_dyn_clear_cache(eng);
    fuji_dyn_set_smp_host_threads(eng, 1u);
    for (uint32_t c = 0u; c < 4u; ++c) {
        fuji_dyn_reset_core(eng, c);
        fuji_dyn_set_reg(eng, c, 1u, 500u);
        fuji_dyn_set_reg(eng, c, 2u, 0u);
        fuji_dyn_set_reg(eng, c, 10u, 0x40005000u);
        fuji_dyn_set_reg(eng, c, 15u, mt_code_base);
    }
    FujiDynClusterRequest creq{};
    creq.runnable_mask = 0xFu;
    creq.quantum = 256u;
    creq.max_total_ticks = 500000u;
    FujiDynClusterResult cres{};
    CHECK(fuji_dyn_run_cluster_fast(eng, &creq, &cres) == 0, "mt mirror run_cluster_fast");
    CHECK(cres.stop_reason == FUJI_DYN_STOP_ALL_WAITING, "mt mirror all cores WFI");
    for (uint32_t c = 0u; c < 4u; ++c) {
        CHECK(fuji_dyn_get_reg(eng, c, 2u) == 500u, "mt mirror core sum == 500");
    }
    CHECK(ctx.upcall_reads.load() == 0u, "mt mirror 2000 reads served with 0 upcalls");
    CHECK(cres.fast_mmio_hits == 2000u, "mt mirror fast_mmio_hits == 2000");

    fuji_dyn_set_smp_host_threads(eng, 0u);
    CHECK(fuji_dyn_unregister_mmio_mirror(eng, 0x40005000u) == 0, "unregister_mmio_mirror");
    fuji_dyn_clear_mmio_mirrors(eng);
    fuji_dyn_set_mmio_callbacks(eng, test_mmio_read, test_mmio_write, orig_mmio);
    std::printf("  [PASS] 22. Declarative native MMIO read-mirror bank (1/2/4B exact width, validMask toggle, 4-core MT 2000R / 0 upcalls)\n");
}

static void test_64bit_alignment_normal_nc_device_and_strexd(FujiDynEngine* eng) {
    /* Verify ARMv7-A A3.2.1 64-bit alignment rules when SCTLR.A == 0:
     *   (a) Word-aligned (va & 7 == 4) LDRD/STRD on Normal Non-Cacheable memory (TEX=001 C=0 B=0,
     *       TEX=100 C=0 B=0, and TRE=1 PRRR=Normal+NMRR=NC) MUST SUCCEED.
     *   (b) Word-aligned (va & 7 == 4) LDRD/STRD on Device / Strongly-Ordered memory (TEX=000 C=0 B=1,
     *       TEX=000 C=0 B=0, TEX=010 C=0 B=0, and TRE=1 PRRR=Device) must succeed as two aligned word transfers.
     *   (c) 64-bit STREXD on word-aligned (va & 7 == 4) Normal memory MUST FAULT even with SCTLR.A == 0,
     *       while 8-byte aligned LDREXD+STREXD succeeds. */
    const uint32_t l1_base = 0x00090000u;
    std::vector<uint8_t> zeros(0x4000u, 0u);
    CHECK(fuji_dyn_mem_write(eng, l1_base, zeros.data(), static_cast<uint32_t>(zeros.size())) == 0,
          "zero align64 L1 table");

    /* Section descriptors (AP=11 RWX, dom=0, type=2):
     *   VA 0x00000000 -> PA 0x00000000: Normal Cacheable (TEX=000, C=1, B=1)
     *   VA 0x10000000 -> PA 0x00100000: Normal Non-Cacheable (TEX=001, C=0, B=0)
     *   VA 0x11000000 -> PA 0x00100000: Normal Non-Cacheable via TEX[2]=1 (TEX=100, C=0, B=0)
     *   VA 0x12000000 -> PA 0x00100000: Shareable Device (TEX=000, C=0, B=1)
     *   VA 0x13000000 -> PA 0x00100000: Strongly-Ordered (TEX=000, C=0, B=0)
     *   VA 0x14000000 -> PA 0x00100000: Non-shareable Device (TEX=010, C=0, B=0) */
    const uint32_t sec_rw = (3u << 10) | 2u;
    write_u32(eng, l1_base + (0x000u << 2), 0x00000000u | sec_rw | (1u << 3) | (1u << 2));
    write_u32(eng, l1_base + (0x100u << 2), 0x00100000u | sec_rw | (1u << 12));
    write_u32(eng, l1_base + (0x110u << 2), 0x00100000u | sec_rw | (4u << 12));
    write_u32(eng, l1_base + (0x120u << 2), 0x00100000u | sec_rw | (1u << 2));
    write_u32(eng, l1_base + (0x130u << 2), 0x00100000u | sec_rw);
    write_u32(eng, l1_base + (0x140u << 2), 0x00100000u | sec_rw | (2u << 12));

    const uint32_t data_pa = 0x00100000u;
    write_u32(eng, data_pa + 0x00u, 0x11111111u);
    write_u32(eng, data_pa + 0x04u, 0x22334455u);
    write_u32(eng, data_pa + 0x08u, 0x66778899u);
    write_u32(eng, data_pa + 0x0Cu, 0xAAAAAAAAu);

    const uint32_t ldrd_code_pa = 0x0007B000u;
    const uint32_t strd_code_pa = 0x0007B040u;
    const uint32_t strexd_code_pa = 0x0007B080u;
    const uint32_t thumb_pair_pa = 0x0007B0C0u;
    const uint16_t thumb_pair[] = {
        0xe9d0u, 0x2300u, // ldrd r2,r3,[r0]
        0xe9c1u, 0x2300u, // strd r2,r3,[r1]
        0xbf30u,          // wfi
    };
    const uint32_t ldrd_code[] = {
        0xE1C020D0u, /* ldrd r2, r3, [r0] */
        0xE320F003u, /* wfi */
    };
    const uint32_t strd_code[] = {
        0xE1C020F0u, /* strd r2, r3, [r0] */
        0xE320F003u, /* wfi */
    };
    const uint32_t strexd_code[] = {
        0xE1B02F9Fu, /* ldrexd r2, r3, [r0] */
        0xE1A04F96u, /* strexd r4, r6, r7, [r0] */
        0xE320F003u, /* wfi */
    };
    CHECK(fuji_dyn_mem_write(eng, ldrd_code_pa, ldrd_code, sizeof(ldrd_code)) == 0, "write ldrd code");
    CHECK(fuji_dyn_mem_write(eng, strd_code_pa, strd_code, sizeof(strd_code)) == 0, "write strd code");
    CHECK(fuji_dyn_mem_write(eng, strexd_code_pa, strexd_code, sizeof(strexd_code)) == 0, "write strexd code");
    CHECK(fuji_dyn_mem_write(eng, thumb_pair_pa, thumb_pair, sizeof(thumb_pair)) == 0, "write Thumb-2 pair code");
    fuji_dyn_clear_cache(eng);

    auto setup_mmu_core = [&](bool enable_tre) {
        fuji_dyn_reset_core(eng, 0u);
        fuji_dyn_set_cp15(eng, 0u, 0u, 2u, 0u, 0u, l1_base);      /* TTBR0 */
        fuji_dyn_set_cp15(eng, 0u, 0u, 3u, 0u, 0u, 0x55555555u);  /* DACR: all Client */
        if (enable_tre) {
            /* PRRR: idx=0 (TEX0=0,C=0,B=0) -> 0b01 (Device), idx=1 (TEX0=0,C=0,B=1) -> 0b10 (Normal),
             *       idx=3 (TEX0=0,C=1,B=1) -> 0b10 (Normal).
             * NMRR: idx=1 Inner=00 (NC), Outer=00 (NC); idx=3 Inner=01 (WBWA), Outer=01 (WBWA). */
            const uint32_t prrr = (1u << 0) | (2u << 2) | (2u << 6);
            const uint32_t nmrr = (1u << 6) | (1u << 22);
            fuji_dyn_set_cp15(eng, 0u, 0u, 10u, 2u, 0u, prrr);
            fuji_dyn_set_cp15(eng, 0u, 0u, 10u, 2u, 1u, nmrr);
        }
        uint32_t sctlr = fuji_dyn_get_cp15(eng, 0u, 0u, 1u, 0u, 0u);
        sctlr &= ~2u; /* SCTLR.A = 0 */
        sctlr |= 1u;  /* SCTLR.M = 1 */
        if (enable_tre) sctlr |= (1u << 28); else sctlr &= ~(1u << 28);
        fuji_dyn_set_cp15(eng, 0u, 0u, 1u, 0u, 0u, sctlr);
    };

    FujiDynRunResult res{};

    /* Word-aligned pairs are legal on Normal, Device and Strongly-Ordered pages. */
    for (uint32_t va_base : {0x10000000u, 0x11000000u, 0x12000000u, 0x13000000u, 0x14000000u}) {
        write_u32(eng, data_pa + 0x04u, 0x22334455u);
        write_u32(eng, data_pa + 0x08u, 0x66778899u);
        setup_mmu_core(false);
        fuji_dyn_set_reg(eng, 0u, 0u, va_base + 4u);
        fuji_dyn_set_reg(eng, 0u, 15u, ldrd_code_pa);
        CHECK(fuji_dyn_run_fast(eng, 0u, 1000u, 0u, 0u, &res) == 0, "run word-aligned LDRD");
        CHECK(res.stop_reason == FUJI_DYN_STOP_WFI, "word-aligned LDRD must succeed with SCTLR.A=0");
        CHECK(fuji_dyn_get_reg(eng, 0u, 2u) == 0x22334455u && fuji_dyn_get_reg(eng, 0u, 3u) == 0x66778899u,
              "word-aligned LDRD values");

        setup_mmu_core(false);
        fuji_dyn_set_reg(eng, 0u, 0u, va_base + 4u);
        fuji_dyn_set_reg(eng, 0u, 2u, 0xCAFE0001u);
        fuji_dyn_set_reg(eng, 0u, 3u, 0xBEEF0002u);
        fuji_dyn_set_reg(eng, 0u, 15u, strd_code_pa);
        CHECK(fuji_dyn_run_fast(eng, 0u, 1000u, 0u, 0u, &res) == 0, "run word-aligned STRD");
        CHECK(res.stop_reason == FUJI_DYN_STOP_WFI, "word-aligned STRD must succeed with SCTLR.A=0");
        CHECK(read_u32(eng, data_pa + 0x04u) == 0xCAFE0001u && read_u32(eng, data_pa + 0x08u) == 0xBEEF0002u,
              "word-aligned STRD values");
    }

    for (bool big_endian : {false, true}) {
        write_u32(eng, data_pa + 4, 0x22334455u);
        write_u32(eng, data_pa + 8, 0x66778899u);
        write_u32(eng, data_pa + 12, 0u);
        write_u32(eng, data_pa + 16, 0u);
        setup_mmu_core(false);
        fuji_dyn_set_cpsr(eng, 0, fuji_dyn_get_cpsr(eng, 0) | 0x20u | (big_endian ? 0x200u : 0u));
        fuji_dyn_set_reg(eng, 0, 0, 0x12000004u);
        fuji_dyn_set_reg(eng, 0, 1, 0x1200000cu);
        fuji_dyn_set_reg(eng, 0, 15, thumb_pair_pa);
        CHECK(fuji_dyn_run_fast(eng, 0, 1000, 0, 0, &res) == 0 && res.stop_reason == FUJI_DYN_STOP_WFI,
              "Thumb-2 word-aligned Device pair");
        CHECK(fuji_dyn_get_reg(eng, 0, 2) == (big_endian ? 0x55443322u : 0x22334455u) &&
              fuji_dyn_get_reg(eng, 0, 3) == (big_endian ? 0x99887766u : 0x66778899u),
              "Thumb-2 pair register ordering");
        CHECK(read_u32(eng, data_pa + 12) == 0x22334455u && read_u32(eng, data_pa + 16) == 0x66778899u,
              "Thumb-2 pair memory ordering");
    }

    /* (a2) TRE=1: idx=1 (VA 0x12000000: TEX0=0,C=0,B=1) remapped to Normal Non-Cacheable -> 4B-aligned LDRD succeeds */
    write_u32(eng, data_pa + 0x04u, 0x12345678u);
    write_u32(eng, data_pa + 0x08u, 0x9ABCDEF0u);
    setup_mmu_core(true);
    fuji_dyn_set_reg(eng, 0u, 0u, 0x12000004u);
    fuji_dyn_set_reg(eng, 0u, 15u, ldrd_code_pa);
    CHECK(fuji_dyn_run_fast(eng, 0u, 1000u, 0u, 0u, &res) == 0, "run TRE=1 word-aligned LDRD");
    CHECK(res.stop_reason == FUJI_DYN_STOP_WFI, "TRE=1 word-aligned LDRD must succeed");
    CHECK(fuji_dyn_get_reg(eng, 0u, 2u) == 0x12345678u && fuji_dyn_get_reg(eng, 0u, 3u) == 0x9ABCDEF0u,
          "TRE=1 word-aligned LDRD values");

    /* (b) Device & Strongly-Ordered (Shareable Device 0x12, Strongly-Ordered 0x13, Non-shareable Device 0x14):
     *     Non-word-aligned LDRD & STRD must fault with FAULT_ALIGNMENT (0x01) even with SCTLR.A == 0 */
    for (uint32_t va_base : {0x12000000u, 0x13000000u, 0x14000000u}) {
        setup_mmu_core(false);
        fuji_dyn_set_reg(eng, 0u, 0u, va_base + 2u);
        fuji_dyn_set_reg(eng, 0u, 15u, ldrd_code_pa);
        CHECK(fuji_dyn_run_fast(eng, 0u, 1000u, 0u, 0u, &res) == 0, "run Device/SO LDRD");
        CHECK(res.stop_reason == FUJI_DYN_STOP_FAULT_DABT && (res.fault_status & 0xFu) == 0x1u,
              "Device/SO non-word-aligned LDRD must raise FAULT_ALIGNMENT");

        setup_mmu_core(false);
        fuji_dyn_set_reg(eng, 0u, 0u, va_base + 2u);
        fuji_dyn_set_reg(eng, 0u, 2u, 0x11112222u);
        fuji_dyn_set_reg(eng, 0u, 3u, 0x33334444u);
        fuji_dyn_set_reg(eng, 0u, 15u, strd_code_pa);
        CHECK(fuji_dyn_run_fast(eng, 0u, 1000u, 0u, 0u, &res) == 0, "run Device/SO STRD");
        CHECK(res.stop_reason == FUJI_DYN_STOP_FAULT_DABT && (res.fault_status & 0xFu) == 0x1u &&
              (res.fault_status & (1u << 11)) != 0u,
              "Device/SO non-word-aligned STRD must raise write FAULT_ALIGNMENT");
    }

    /* (b2) TRE=1: idx=0 (VA 0x13000000: TEX0=0,C=0,B=0) remapped to Device -> non-word-aligned LDRD faults */
    setup_mmu_core(true);
    fuji_dyn_set_reg(eng, 0u, 0u, 0x13000002u);
    fuji_dyn_set_reg(eng, 0u, 15u, ldrd_code_pa);
    CHECK(fuji_dyn_run_fast(eng, 0u, 1000u, 0u, 0u, &res) == 0, "run TRE=1 Device LDRD");
    CHECK(res.stop_reason == FUJI_DYN_STOP_FAULT_DABT && (res.fault_status & 0xFu) == 0x1u,
          "TRE=1 Device non-word-aligned LDRD must raise FAULT_ALIGNMENT");

    /* (c) 64-bit STREXD: 8-byte aligned succeeds; 4-byte aligned (va & 7 == 4) faults even with SCTLR.A == 0 */
    const uint32_t excl_pa = 0x0007C000u;
    write_u32(eng, excl_pa + 0x00u, 0x01020304u);
    write_u32(eng, excl_pa + 0x04u, 0x05060708u);
    write_u32(eng, excl_pa + 0x08u, 0x090A0B0Cu);

    setup_mmu_core(false);
    fuji_dyn_set_reg(eng, 0u, 0u, excl_pa); /* 8-byte aligned on Normal Cacheable RAM */
    fuji_dyn_set_reg(eng, 0u, 6u, 0xA1A2A3A4u);
    fuji_dyn_set_reg(eng, 0u, 7u, 0xB1B2B3B4u);
    fuji_dyn_set_reg(eng, 0u, 15u, strexd_code_pa);
    CHECK(fuji_dyn_run_fast(eng, 0u, 1000u, 0u, 0u, &res) == 0, "run aligned STREXD");
    CHECK(res.stop_reason == FUJI_DYN_STOP_WFI && fuji_dyn_get_reg(eng, 0u, 4u) == 0u,
          "8B-aligned STREXD must succeed with rd=0");
    CHECK(read_u32(eng, excl_pa + 0x00u) == 0xA1A2A3A4u && read_u32(eng, excl_pa + 0x04u) == 0xB1B2B3B4u,
          "8B-aligned STREXD wrote expected 64-bit value");

    setup_mmu_core(false);
    fuji_dyn_set_reg(eng, 0u, 0u, excl_pa + 4u); /* 4-byte aligned (va & 7 == 4) on Normal Cacheable RAM, SCTLR.A == 0 */
    fuji_dyn_set_reg(eng, 0u, 6u, 0xDEADBEEFu);
    fuji_dyn_set_reg(eng, 0u, 7u, 0xFEEDFACEu);
    fuji_dyn_set_reg(eng, 0u, 15u, strexd_code_pa);
    CHECK(fuji_dyn_run_fast(eng, 0u, 1000u, 0u, 0u, &res) == 0, "run misaligned STREXD");
    CHECK(res.stop_reason == FUJI_DYN_STOP_FAULT_DABT && (res.fault_status & 0xFu) == 0x1u &&
          (res.fault_status & (1u << 11)) != 0u,
          "4B-aligned STREXD must raise write FAULT_ALIGNMENT even when SCTLR.A == 0");
    CHECK(read_u32(eng, excl_pa + 0x04u) == 0xB1B2B3B4u && read_u32(eng, excl_pa + 0x08u) == 0x090A0B0Cu,
          "faulting 4B-aligned STREXD must not modify memory");

    fuji_dyn_reset_core(eng, 0u);
    std::printf("  [PASS] 23. 64-bit alignment: word-aligned Normal/Device pairs, unaligned Device faults and strict STREXD\n");
}

static void test_vfp_fpexc_lazy_undef_and_cache_invalidation(FujiDynEngine* eng) {
    fuji_dyn_reset_core(eng, 0u);
    fuji_dyn_clear_cache(eng);
    fuji_dyn_set_auto_exceptions(eng, 0u);

    /* 1. FPEXC.EN == 0 traps VFP, FPSCR, and ASIMD instructions at exact fault_pc without side effects */
    const uint32_t trap_vpush_pa = 0x00080000u;
    const uint32_t trap_vadd_pa  = 0x00080020u;
    const uint32_t trap_vmrs_pa  = 0x00080040u;
    const uint32_t trap_neon_pa  = 0x00080060u;
    const uint32_t vpush_code[] = { 0xED2D8B02u, 0xE320F003u }; /* vpush {d8}; wfi */
    const uint32_t vadd_code[]  = { 0xEE310B02u, 0xE320F003u }; /* vadd.f64 d0, d1, d2; wfi */
    const uint32_t vmrs_code[]  = { 0xEEF10A10u, 0xE320F003u }; /* vmrs r0, fpscr; wfi */
    const uint32_t neon_code[]  = { 0xF2210802u, 0xE320F003u }; /* vadd.i32 d0, d1, d2; wfi */
    CHECK(fuji_dyn_mem_write(eng, trap_vpush_pa, vpush_code, sizeof(vpush_code)) == 0, "write vpush");
    CHECK(fuji_dyn_mem_write(eng, trap_vadd_pa, vadd_code, sizeof(vadd_code)) == 0, "write vadd");
    CHECK(fuji_dyn_mem_write(eng, trap_vmrs_pa, vmrs_code, sizeof(vmrs_code)) == 0, "write vmrs fpscr");
    CHECK(fuji_dyn_mem_write(eng, trap_neon_pa, neon_code, sizeof(neon_code)) == 0, "write neon");

    fuji_dyn_set_fpexc(eng, 0u, 0x00000000u);
    fuji_dyn_set_reg(eng, 0u, 13u, 0x00090000u);
    fuji_dyn_set_dreg(eng, 0u, 0u, 0x1122334455667788ull);
    fuji_dyn_set_reg(eng, 0u, 0u, 0xDEADC0DEu);

    FujiDynRunResult res{};
    fuji_dyn_set_reg(eng, 0u, 15u, trap_vpush_pa);
    CHECK(fuji_dyn_run_fast(eng, 0u, 100u, 0u, 0u, &res) == 0, "run vpush fpexc=0");
    CHECK(res.stop_reason == FUJI_DYN_STOP_FAULT_UNDEF && fuji_dyn_get_reg(eng, 0u, 15u) == trap_vpush_pa &&
          fuji_dyn_get_reg(eng, 0u, 13u) == 0x00090000u,
          "vpush with FPEXC.EN=0 must fault at exact PC without decrementing SP");

    fuji_dyn_set_reg(eng, 0u, 15u, trap_vadd_pa);
    CHECK(fuji_dyn_run_fast(eng, 0u, 100u, 0u, 0u, &res) == 0, "run vadd fpexc=0");
    CHECK(res.stop_reason == FUJI_DYN_STOP_FAULT_UNDEF && fuji_dyn_get_reg(eng, 0u, 15u) == trap_vadd_pa &&
          fuji_dyn_get_dreg(eng, 0u, 0u) == 0x1122334455667788ull,
          "vadd.f64 with FPEXC.EN=0 must fault at exact PC without modifying d0");

    fuji_dyn_set_reg(eng, 0u, 15u, trap_vmrs_pa);
    CHECK(fuji_dyn_run_fast(eng, 0u, 100u, 0u, 0u, &res) == 0, "run vmrs fpscr fpexc=0");
    CHECK(res.stop_reason == FUJI_DYN_STOP_FAULT_UNDEF && fuji_dyn_get_reg(eng, 0u, 15u) == trap_vmrs_pa &&
          fuji_dyn_get_reg(eng, 0u, 0u) == 0xDEADC0DEu,
          "vmrs fpscr with FPEXC.EN=0 must fault without modifying Rt");

    fuji_dyn_set_reg(eng, 0u, 15u, trap_neon_pa);
    CHECK(fuji_dyn_run_fast(eng, 0u, 100u, 0u, 0u, &res) == 0, "run neon fpexc=0");
    CHECK(res.stop_reason == FUJI_DYN_STOP_FAULT_UNDEF && fuji_dyn_get_reg(eng, 0u, 15u) == trap_neon_pa &&
          fuji_dyn_get_dreg(eng, 0u, 0u) == 0x1122334455667788ull,
          "ASIMD vadd.i32 with FPEXC.EN=0 must fault without modifying d0");

    /* 2. Conditional ARM VFP with FPEXC.EN == 0: false condition executes as NOP, true condition faults */
    const uint32_t cond_vfp_pa = 0x00080100u;
    const uint32_t cond_vfp_code[] = {
        0xE3500001u, /* cmp r0, #1 */
        0x0E310B02u, /* vaddeq.f64 d0, d1, d2 */
        0xE2811005u, /* add r1, r1, #5 */
        0xE320F003u, /* wfi */
    };
    CHECK(fuji_dyn_mem_write(eng, cond_vfp_pa, cond_vfp_code, sizeof(cond_vfp_code)) == 0, "write cond vfp");
    fuji_dyn_set_reg(eng, 0u, 0u, 0u); /* r0 != 1 -> EQ false */
    fuji_dyn_set_reg(eng, 0u, 1u, 10u);
    fuji_dyn_set_reg(eng, 0u, 15u, cond_vfp_pa);
    CHECK(fuji_dyn_run_fast(eng, 0u, 100u, 0u, 0u, &res) == 0, "run false-cond vfp");
    CHECK(res.stop_reason == FUJI_DYN_STOP_WFI && fuji_dyn_get_reg(eng, 0u, 1u) == 15u,
          "false-condition ARM VFP with FPEXC.EN=0 must execute as NOP without trapping");

    fuji_dyn_set_reg(eng, 0u, 0u, 1u); /* r0 == 1 -> EQ true */
    fuji_dyn_set_reg(eng, 0u, 1u, 10u);
    fuji_dyn_set_reg(eng, 0u, 15u, cond_vfp_pa);
    CHECK(fuji_dyn_run_fast(eng, 0u, 100u, 0u, 0u, &res) == 0, "run true-cond vfp");
    CHECK(res.stop_reason == FUJI_DYN_STOP_FAULT_UNDEF &&
          fuji_dyn_get_reg(eng, 0u, 15u) == cond_vfp_pa + 4u &&
          fuji_dyn_get_reg(eng, 0u, 1u) == 10u,
          "true-condition ARM VFP with FPEXC.EN=0 must trap at vaddeq PC");

    /* 3. Privileged VMRS/VMSR on FPEXC/FPSID/MVFR0/MVFR1 succeed in SVC with FPEXC=0, trap in USR, and honor CPACR */
    const uint32_t sysreg_pa = 0x00080200u;
    const uint32_t sysreg_code[] = {
        0xEEF02A10u, /* vmrs r2, fpsid */
        0xEEF73A10u, /* vmrs r3, mvfr0 */
        0xEEF64A10u, /* vmrs r4, mvfr1 */
        0xEEF85A10u, /* vmrs r5, fpexc */
        0xE320F003u, /* wfi */
    };
    CHECK(fuji_dyn_mem_write(eng, sysreg_pa, sysreg_code, sizeof(sysreg_code)) == 0, "write vfp sysregs");
    fuji_dyn_set_fpexc(eng, 0u, 0u);
    fuji_dyn_set_reg(eng, 0u, 15u, sysreg_pa);
    CHECK(fuji_dyn_run_fast(eng, 0u, 100u, 0u, 0u, &res) == 0 && res.stop_reason == FUJI_DYN_STOP_WFI,
          "SVC VMRS on FPSID/MVFR0/MVFR1/FPEXC must succeed when FPEXC.EN=0");
    CHECK(fuji_dyn_get_reg(eng, 0u, 2u) == 0x41023075u &&
          fuji_dyn_get_reg(eng, 0u, 3u) == 0x10110222u &&
          fuji_dyn_get_reg(eng, 0u, 4u) == 0x11111111u &&
          fuji_dyn_get_reg(eng, 0u, 5u) == 0u,
          "VFP system register values mismatch");

    /* USR mode: VMRS/VMSR FPEXC must fault as UND even when FPEXC.EN=1 and CPACR=0x00F00000 */
    const uint32_t usr_vmsr_pa = 0x00080240u;
    const uint32_t usr_vmsr_code[] = { 0xEEE81A10u, 0xE320F003u }; /* vmsr fpexc, r1; wfi */
    CHECK(fuji_dyn_mem_write(eng, usr_vmsr_pa, usr_vmsr_code, sizeof(usr_vmsr_code)) == 0, "write usr vmsr");
    fuji_dyn_set_fpexc(eng, 0u, 0x40000000u);
    fuji_dyn_set_cpsr(eng, 0u, 0x000001D0u); /* USR mode */
    fuji_dyn_set_reg(eng, 0u, 2u, 0xCAFE1234u);
    fuji_dyn_set_reg(eng, 0u, 15u, sysreg_pa);
    CHECK(fuji_dyn_run_fast(eng, 0u, 100u, 0u, 0u, &res) == 0 &&
          res.stop_reason == FUJI_DYN_STOP_FAULT_UNDEF &&
          fuji_dyn_get_reg(eng, 0u, 15u) == sysreg_pa &&
          fuji_dyn_get_reg(eng, 0u, 2u) == 0xCAFE1234u,
          "USR mode VMRS FPSID must trap as UND without clobbering Rt");

    fuji_dyn_set_reg(eng, 0u, 1u, 0u);
    fuji_dyn_set_reg(eng, 0u, 15u, usr_vmsr_pa);
    CHECK(fuji_dyn_run_fast(eng, 0u, 100u, 0u, 0u, &res) == 0 &&
          res.stop_reason == FUJI_DYN_STOP_FAULT_UNDEF &&
          fuji_dyn_get_reg(eng, 0u, 15u) == usr_vmsr_pa &&
          fuji_dyn_get_fpexc(eng, 0u) == 0x40000000u,
          "USR mode VMSR FPEXC must trap as UND without modifying FPEXC");

    /* CPACR.cp10 == 0 in SVC mode: MCR CPACR immediately disables VFP on the very next instruction */
    fuji_dyn_set_cpsr(eng, 0u, 0x000001D3u); /* back to SVC */
    const uint32_t cpacr_pa = 0x00080280u;
    const uint32_t cpacr_code[] = {
        0xE3A00000u, /* mov r0, #0 */
        0xEE010F50u, /* mcr p15, 0, r0, c1, c0, 2 (CPACR = 0) */
        0xEE310B02u, /* vadd.f64 d0, d1, d2 -> must fault immediately! */
        0xE320F003u, /* wfi */
    };
    CHECK(fuji_dyn_mem_write(eng, cpacr_pa, cpacr_code, sizeof(cpacr_code)) == 0, "write cpacr code");
    fuji_dyn_set_reg(eng, 0u, 15u, cpacr_pa);
    CHECK(fuji_dyn_run_fast(eng, 0u, 100u, 0u, 0u, &res) == 0 &&
          res.stop_reason == FUJI_DYN_STOP_FAULT_UNDEF &&
          fuji_dyn_get_reg(eng, 0u, 15u) == cpacr_pa + 8u,
          "MCR CPACR=0 must disable VFP immediately on the next instruction");

    /* 4. Full ThreadX-style AUTO_EXCP_UNDEF lazy VFP context switch (warm JIT cache + re-disable + Thumb-2 IT retry) */
    fuji_dyn_reset_core(eng, 0u);
    fuji_dyn_set_auto_exceptions(eng, FUJI_DYN_AUTO_EXCP_UNDEF);

    const uint32_t vbar      = 0x00081000u;
    const uint32_t und_h_pa  = vbar + 0x100u;
    const uint32_t tcb_pa    = 0x00082000u;
    const uint32_t sp_und_pa = 0x00083000u;
    const uint32_t sp_svc_pa = 0x00084000u;
    const uint32_t vfp_fn_pa = 0x00085000u;
    const uint32_t caller_pa = 0x00085100u;
    const uint32_t thumb_pa  = 0x00085200u;

    /* Vector +0x04 (UND): b und_h_pa -> offset = (0x100 - 0x04 - 8)/4 = 0x3D */
    write_u32(eng, vbar + 0x04u, 0xEA00003Du);
    const uint32_t und_handler[] = {
        0xE92D0003u, /* push {r0, r1} */
        0xE59D0008u, /* ldr  r0, [sp, #8] (tcb_pa) */
        0xE5901000u, /* ldr  r1, [r0, #0] (trap_count) */
        0xE2811001u, /* add  r1, r1, #1 */
        0xE5801000u, /* str  r1, [r0, #0] */
        0xE580E004u, /* str  lr, [r0, #4] (last LR_und) */
        0xE3A01101u, /* mov  r1, #0x40000000 */
        0xEEE81A10u, /* vmsr fpexc, r1 */
        0xE14F0000u, /* mrs  r0, spsr */
        0xE3100020u, /* tst  r0, #0x20 (Thumb?) */
        0xE8BD0003u, /* pop  {r0, r1} */
        0x125EF002u, /* subsne pc, lr, #2 */
        0xE25EF004u, /* subs   pc, lr, #4 */
    };
    CHECK(fuji_dyn_mem_write(eng, und_h_pa, und_handler, sizeof(und_handler)) == 0, "write und handler");

    const uint32_t vfp_fn[] = {
        0xED2D8B02u, /* vpush {d8} */
        0xEE318B02u, /* vadd.f64 d8, d1, d2 */
        0xEC532B18u, /* vmov r2, r3, d8 */
        0xECBD8B02u, /* vpop {d8} */
        0xE12FFF1Eu, /* bx lr */
    };
    CHECK(fuji_dyn_mem_write(eng, vfp_fn_pa, vfp_fn, sizeof(vfp_fn)) == 0, "write vfp_fn");

    /* Warm up vfp_fn_pa with FPEXC.EN=1 FIRST so its enabled block is already compiled & cached! */
    const uint32_t warmup_pa = 0x00085080u;
    const uint32_t warmup_code[] = {
        0xEBFFFFDEu, /* bl vfp_fn_pa: (0x85000 - 0x85080 - 8)/4 = -34 = 0xFFFFDE */
        0xE320F003u, /* wfi */
    };
    CHECK(fuji_dyn_mem_write(eng, warmup_pa, warmup_code, sizeof(warmup_code)) == 0, "write warmup");

    write_u32(eng, tcb_pa + 0u, 0u);
    write_u32(eng, tcb_pa + 4u, 0u);
    write_u32(eng, sp_und_pa, tcb_pa);
    fuji_dyn_set_cp15(eng, 0u, 0u, 12u, 0u, 0u, vbar);
    fuji_dyn_set_banked_reg(eng, 0u, FUJI_DYN_MODE_UND, 13u, sp_und_pa);
    fuji_dyn_set_reg(eng, 0u, 13u, sp_svc_pa);

    const double a = 12.5, b = 29.75, expect_sum = 42.25;
    uint64_t a_bits = 0, b_bits = 0, sum_bits = 0;
    std::memcpy(&a_bits, &a, 8);
    std::memcpy(&b_bits, &b, 8);
    std::memcpy(&sum_bits, &expect_sum, 8);
    fuji_dyn_set_dreg(eng, 0u, 1u, a_bits);
    fuji_dyn_set_dreg(eng, 0u, 2u, b_bits);
    fuji_dyn_set_dreg(eng, 0u, 8u, 0xCAFEBABE12345678ull);

    fuji_dyn_set_reg(eng, 0u, 15u, warmup_pa);
    CHECK(fuji_dyn_run_fast(eng, 0u, 1000u, 0u, 0u, &res) == 0 && res.stop_reason == FUJI_DYN_STOP_WFI, "run warmup");
    CHECK(read_u32(eng, tcb_pa) == 0u, "warmup with FPEXC=1 must not trap");

    /* Now disable FPEXC twice in guest code and call the ALREADY-CACHED vfp_fn_pa WITHOUT ClearCache()! */
    const int32_t bl1_off = static_cast<int32_t>(vfp_fn_pa - (caller_pa + 8u) - 8u) / 4;
    const int32_t bl2_off = static_cast<int32_t>(vfp_fn_pa - (caller_pa + 20u) - 8u) / 4;
    const uint32_t caller_code[] = {
        0xE3A00000u,                                        /* +0x00: mov r0, #0 */
        0xEEE80A10u,                                        /* +0x04: vmsr fpexc, r0 (disable VFP!) */
        0xEB000000u | (static_cast<uint32_t>(bl1_off) & 0x00FFFFFFu), /* +0x08: bl vfp_fn_pa */
        0xE3A00000u,                                        /* +0x0C: mov r0, #0 */
        0xEEE80A10u,                                        /* +0x10: vmsr fpexc, r0 (re-disable VFP!) */
        0xEB000000u | (static_cast<uint32_t>(bl2_off) & 0x00FFFFFFu), /* +0x14: bl vfp_fn_pa */
        0xE320F003u,                                        /* +0x18: wfi */
    };
    CHECK(fuji_dyn_mem_write(eng, caller_pa, caller_code, sizeof(caller_code)) == 0, "write caller");
    fuji_dyn_set_reg(eng, 0u, 2u, 0u);
    fuji_dyn_set_reg(eng, 0u, 3u, 0u);
    fuji_dyn_set_reg(eng, 0u, 15u, caller_pa);
    CHECK(fuji_dyn_run_fast(eng, 0u, 5000u, 0u, 0u, &res) == 0 && res.stop_reason == FUJI_DYN_STOP_WFI,
          "run ThreadX-style lazy VFP round-trip");
    const uint64_t got_sum = static_cast<uint64_t>(fuji_dyn_get_reg(eng, 0u, 2u)) |
                             (static_cast<uint64_t>(fuji_dyn_get_reg(eng, 0u, 3u)) << 32);
    CHECK(read_u32(eng, tcb_pa + 0u) == 2u, "lazy VFP UND handler must fire on BOTH disable->use transitions without ClearCache");
    CHECK(read_u32(eng, tcb_pa + 4u) == vfp_fn_pa + 4u, "ARM UND LR_und must equal fault_pc + 4");
    CHECK(got_sum == sum_bits, "retried VFP function must produce exact IEEE-754 f64 result");
    CHECK(fuji_dyn_get_dreg(eng, 0u, 8u) == 0xCAFEBABE12345678ull &&
          fuji_dyn_get_reg(eng, 0u, 13u) == sp_svc_pa,
          "callee-saved d8 and SP must be preserved across lazy VFP trap + retry");
    CHECK(fuji_dyn_get_fpexc(eng, 0u) == 0x40000000u, "FPEXC must remain 0x40000000 after lazy enable");

    /* 5. Thumb-2 VFP with IT false-skip + true-trap lazy retry (LR_und = fault_pc + 2) */
    const uint16_t thumb_code[] = {
        0x2800u,             /* +0x00: cmp r0, #0 */
        0xBF18u,             /* +0x02: it ne (NE is false -> skip next VFP as NOP!) */
        0xEE30u, 0x0A81u,    /* +0x04: vaddne.f32 s0, s1, s2 (skipped!) */
        0xEE30u, 0x0A81u,    /* +0x08: vadd.f32   s0, s1, s2 (traps at +0x08, retries cleanly!) */
        0xEE10u, 0x4A10u,    /* +0x0C: vmov r4, s0 */
        0xBF30u, 0xBF00u,    /* +0x10: wfi; nop */
    };
    CHECK(fuji_dyn_mem_write(eng, thumb_pa, thumb_code, sizeof(thumb_code)) == 0, "write thumb vfp");
    const float f1 = 3.5f, f2 = 2.25f, f_expect = 5.75f;
    uint32_t f1_u = 0, f2_u = 0, f_exp_u = 0;
    std::memcpy(&f1_u, &f1, 4);
    std::memcpy(&f2_u, &f2, 4);
    std::memcpy(&f_exp_u, &f_expect, 4);
    fuji_dyn_set_ext_reg(eng, 0u, 1u, f1_u); /* s1 */
    fuji_dyn_set_ext_reg(eng, 0u, 2u, f2_u); /* s2 */
    fuji_dyn_set_ext_reg(eng, 0u, 0u, 0u);   /* s0 */
    fuji_dyn_set_fpexc(eng, 0u, 0u);         /* disable VFP via host API */
    fuji_dyn_set_cpsr(eng, 0u, 0x000001F3u); /* SVC + Thumb */
    fuji_dyn_set_reg(eng, 0u, 0u, 0u);
    fuji_dyn_set_reg(eng, 0u, 4u, 0u);
    fuji_dyn_set_reg(eng, 0u, 15u, thumb_pa);
    CHECK(fuji_dyn_run_fast(eng, 0u, 2000u, 0u, 0u, &res) == 0 && res.stop_reason == FUJI_DYN_STOP_WFI,
          "run Thumb-2 lazy VFP retry");
    CHECK(read_u32(eng, tcb_pa + 0u) == 3u, "Thumb-2 VFP must skip false IT VFP and trap once on unconditional VFP");
    CHECK(read_u32(eng, tcb_pa + 4u) == thumb_pa + 8u + 2u, "Thumb-2 32-bit VFP UND LR_und must equal fault_pc + 2");
    CHECK(fuji_dyn_get_reg(eng, 0u, 4u) == f_exp_u, "Thumb-2 retried VADD.F32 result mismatch");

    fuji_dyn_set_auto_exceptions(eng, 0u);
    fuji_dyn_reset_core(eng, 0u);
    std::printf("  [PASS] 24. VFP/ASIMD FPEXC.EN=0 trapping, conditional NOP, PL1/CPACR checks & lazy VFP switching\n");
}

int main() {
    CHECK(fuji_dyn_abi_version() == FUJI_DYN_ABI_VERSION, "ABI version mismatch");
    FujiDynEngine* eng = fuji_dyn_create(4, 0xFFFF0000u);
    CHECK(eng != nullptr, "fuji_dyn_create failed");

    /* Map 4 MiB RAM @ 0x00000000 and 64 KiB MMIO @ 0x40000000 */
    CHECK(fuji_dyn_map_ram(eng, 0x00000000u, 4u * 1024u * 1024u, nullptr, FUJI_DYN_PERM_ALL) == 0, "map_ram");
    CHECK(fuji_dyn_map_mmio(eng, 0x40000000u, 0x10000u) == 0, "map_mmio");

    TestMmioState mmio{};
    mmio.engine = eng;
    fuji_dyn_set_mmio_callbacks(eng, test_mmio_read, test_mmio_write, &mmio);

    std::printf("[DYNARMIC-BRIDGE] Running standalone Cortex-A7 4-core self-test suite...\n");
    test_arm32_and_thumb2(eng);
    test_vfpv4_and_neon(eng);
    test_cp15_and_4core_mpidr(eng);
    test_mmu_short_descriptor(eng);
    test_exclusive_4core(eng);
    test_banked_svc_irq_mmio(eng, &mmio);
    test_mmio_halt_same_block_and_vtick(eng, &mmio);
    test_timer_ro_mmio_and_64bit_split(eng, &mmio);
    test_ro_ranges_live_state_shared_timer_and_batch(eng, &mmio);
    test_cluster_batch_and_tracepoint_fix(eng);
    test_gic_v2_shadow_sev_elision_and_smart_iciallu(eng, &mmio);
    test_dyn_v3_fastmem_sgir_timer_and_alignment(eng, &mmio);
    test_vtick_divider_and_subtick_carry(eng);
    test_mixed_4k_fallback_under_fastmem(eng);
    test_supersection_40bit_pa(eng);
    test_smp_mt_exclusive_counter_4core(eng);
    test_smp_mt_sgir_wfi_and_sev_wfe_stress(eng);
    test_smp_mt_cross_core_tlbi_iciallu_smc(eng);
    test_smp_mt_mmio_owner_thread_and_timer_irq_interleaving(eng, &mmio);
    test_smp_mt_lifecycle_reset_destroy_stress();
    bench_smp_mt_4core_ab(eng);
    test_declarative_mmio_mirror_bank(eng, &mmio);
    test_64bit_alignment_normal_nc_device_and_strexd(eng);
    test_vfp_fpexc_lazy_undef_and_cache_invalidation(eng);
    bench_mips(eng);

    fuji_dyn_destroy(eng);
    std::printf("[DYNARMIC-BRIDGE] ALL 25/25 SELF-TESTS PASSED!\n");
    return 0;
}
