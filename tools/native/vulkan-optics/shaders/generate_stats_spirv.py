#!/usr/bin/env python3
"""
Generates tools/native/vulkan-optics/fuji_vulkan_stats_spirv.h containing a
spec-compliant Khronos SPIR-V 1.0 compute shader for `isp_stats_reduce.comp`:
  - 16x16 workgroups (256 invocations) with shared-memory local atomics
    (4 x 256-bin Y/R/G/B histograms + 16-word AWB/CDAF summary) flushing once
    per workgroup to the global SSBO
  - 16x16 (256-zone) AE/AWB [R, G1, B, G2] sums + quad counts (10-bit linear)
  - Global & highlight-excluded AWB R/G/B sums + counts
  - AF ROI CDAF horizontal & vertical 1st-deriv Tenengrad + 2nd-deriv Laplacian energy
  - Zero shaderInt64 dependency; verified on Mesa Lavapipe & Vulkan 1.1+ GPUs.
"""

import os
import struct
import subprocess
import sys

SPIRV_MAGIC = 0x07230203
SPIRV_VERSION_1_0 = 0x00010000

# Opcodes (Khronos SPIR-V 1.0)
OP_EXT_INST_IMPORT = 11
OP_EXT_INST = 12
OP_MEMORY_MODEL = 14
OP_ENTRY_POINT = 15
OP_EXECUTION_MODE = 16
OP_CAPABILITY = 17
OP_TYPE_VOID = 19
OP_TYPE_BOOL = 20
OP_TYPE_INT = 21
OP_TYPE_VECTOR = 23
OP_TYPE_ARRAY = 28
OP_TYPE_RUNTIME_ARRAY = 29
OP_TYPE_STRUCT = 30
OP_TYPE_POINTER = 32
OP_TYPE_FUNCTION = 33
OP_CONSTANT = 43
OP_FUNCTION = 54
OP_FUNCTION_END = 56
OP_VARIABLE = 59
OP_LOAD = 61
OP_STORE = 62
OP_ACCESS_CHAIN = 65
OP_DECORATE = 71
OP_MEMBER_DECORATE = 72
OP_BITCAST = 124
OP_IADD = 128
OP_ISUB = 130
OP_IMUL = 132
OP_UDIV = 134
OP_LOGICAL_AND = 167
OP_SELECT = 169
OP_IEQUAL = 170
OP_INOT_EQUAL = 171
OP_UGREATER_THAN_EQUAL = 174
OP_ULESS_THAN = 176
OP_SHIFT_RIGHT_LOGICAL = 194
OP_SHIFT_LEFT_LOGICAL = 196
OP_BITWISE_AND = 199
OP_CONTROL_BARRIER = 224
OP_ATOMIC_IADD = 234
OP_ATOMIC_UMAX = 239
OP_SELECTION_MERGE = 247
OP_LABEL = 248
OP_BRANCH = 249
OP_BRANCH_CONDITIONAL = 250
OP_RETURN = 253

# GLSL.std.450 extended instructions
GLSL_SABS = 5
GLSL_UMIN = 38
GLSL_UMAX = 41

# Enums
CAP_SHADER = 1
ADDR_LOGICAL = 0
MEM_GLSL450 = 1
EXEC_GL_COMPUTE = 5
EXEC_MODE_LOCAL_SIZE = 17

SC_INPUT = 1
SC_UNIFORM = 2
SC_WORKGROUP = 4
SC_FUNCTION = 7

DEC_BLOCK = 2
DEC_BUFFER_BLOCK = 3
DEC_ARRAY_STRIDE = 6
DEC_BUILTIN = 11
DEC_BINDING = 33
DEC_DESCRIPTOR_SET = 34
DEC_OFFSET = 35

BUILTIN_GLOBAL_INVOCATION_ID = 28
BUILTIN_LOCAL_INVOCATION_INDEX = 29

# Memory scopes & semantics
SCOPE_DEVICE = 1
SCOPE_WORKGROUP = 2
MEM_SEM_NONE = 0x0
MEM_SEM_ACQ_REL_WG = 0x108  # AcquireRelease (0x8) | WorkgroupMemory (0x100)


def encode_str(s: str) -> list[int]:
    raw = s.encode("utf-8") + b"\x00"
    while len(raw) % 4 != 0:
        raw += b"\x00"
    return list(struct.unpack(f"<{len(raw) // 4}I", raw))


class StatsSpirvBuilder:
    """Minimal Khronos SPIR-V 1.0 builder supporting 16x16 workgroups, shared arrays, barriers, and atomics."""

    def __init__(self):
        self.next_id = 1
        self.caps = []
        self.ext_imports = []
        self.mem_model = []
        self.entry_points = []
        self.exec_modes = []
        self.decorations = []
        self.types_consts = []
        self.globals_sec = []
        self.func_vars = []
        self.func_body = []
        self._const_u32 = {}
        self._const_i32 = {}

        self.glsl = self.id()
        self.main_fn = self.id()
        self.var_gid = self.id()
        self.var_lid = self.id()

        self._emit(self.caps, OP_CAPABILITY, CAP_SHADER)
        self._emit_str(self.ext_imports, OP_EXT_INST_IMPORT, [self.glsl], "GLSL.std.450")
        self._emit(self.mem_model, OP_MEMORY_MODEL, ADDR_LOGICAL, MEM_GLSL450)
        self._emit_str(
            self.entry_points,
            OP_ENTRY_POINT,
            [EXEC_GL_COMPUTE, self.main_fn],
            "main",
            [self.var_gid, self.var_lid],
        )
        self._emit(self.exec_modes, OP_EXECUTION_MODE, self.main_fn, EXEC_MODE_LOCAL_SIZE, 16, 16, 1)

        # Core types
        self.t_void = self.id()
        self.t_bool = self.id()
        self.t_u32 = self.id()
        self.t_i32 = self.id()
        self.t_uvec3 = self.id()
        self.t_fn_void = self.id()

        self._emit(self.types_consts, OP_TYPE_VOID, self.t_void)
        self._emit(self.types_consts, OP_TYPE_BOOL, self.t_bool)
        self._emit(self.types_consts, OP_TYPE_INT, self.t_u32, 32, 0)
        self._emit(self.types_consts, OP_TYPE_INT, self.t_i32, 32, 1)
        self._emit(self.types_consts, OP_TYPE_VECTOR, self.t_uvec3, self.t_u32, 3)
        self._emit(self.types_consts, OP_TYPE_FUNCTION, self.t_fn_void, self.t_void)

        # Pointer types
        self.p_in_uvec3 = self.ptr_type(SC_INPUT, self.t_uvec3)
        self.p_in_u32 = self.ptr_type(SC_INPUT, self.t_u32)
        self.p_u_u32 = self.ptr_type(SC_UNIFORM, self.t_u32)
        self.p_wg_u32 = self.ptr_type(SC_WORKGROUP, self.t_u32)
        self.p_fn_u32 = self.ptr_type(SC_FUNCTION, self.t_u32)

        # Built-in inputs: gl_GlobalInvocationID & gl_LocalInvocationIndex
        self._emit(self.decorations, OP_DECORATE, self.var_gid, DEC_BUILTIN, BUILTIN_GLOBAL_INVOCATION_ID)
        self._emit(self.decorations, OP_DECORATE, self.var_lid, DEC_BUILTIN, BUILTIN_LOCAL_INVOCATION_INDEX)
        self._emit(self.globals_sec, OP_VARIABLE, self.p_in_uvec3, self.var_gid, SC_INPUT)
        self._emit(self.globals_sec, OP_VARIABLE, self.p_in_u32, self.var_lid, SC_INPUT)

        # Shared workgroup arrays (4 x 256 histogram bins + 16 summary words = 4,160 bytes)
        c256 = self.u32(256)
        c16 = self.u32(16)
        self.t_arr_256 = self.id()
        self.t_arr_16 = self.id()
        self._emit(self.types_consts, OP_TYPE_ARRAY, self.t_arr_256, self.t_u32, c256)
        self._emit(self.types_consts, OP_TYPE_ARRAY, self.t_arr_16, self.t_u32, c16)
        self.p_wg_arr_256 = self.ptr_type(SC_WORKGROUP, self.t_arr_256)
        self.p_wg_arr_16 = self.ptr_type(SC_WORKGROUP, self.t_arr_16)

        self.s_histY = self.decl_shared(self.p_wg_arr_256)
        self.s_histR = self.decl_shared(self.p_wg_arr_256)
        self.s_histG = self.decl_shared(self.p_wg_arr_256)
        self.s_histB = self.decl_shared(self.p_wg_arr_256)
        self.s_summary = self.decl_shared(self.p_wg_arr_16)

    def id(self) -> int:
        v = self.next_id
        self.next_id += 1
        return v

    def _emit(self, sec: list[int], op: int, *words: int):
        wc = 1 + len(words)
        sec.append((wc << 16) | op)
        sec.extend(words)

    def _emit_str(self, sec: list[int], op: int, prefix: list[int], text: str, suffix: list[int] | None = None):
        sw = encode_str(text)
        tail = suffix if suffix is not None else []
        self._emit(sec, op, *(prefix + sw + tail))

    def ptr_type(self, sc: int, base: int) -> int:
        tid = self.id()
        self._emit(self.types_consts, OP_TYPE_POINTER, tid, sc, base)
        return tid

    def decl_shared(self, ptr_tid: int) -> int:
        vid = self.id()
        self._emit(self.globals_sec, OP_VARIABLE, ptr_tid, vid, SC_WORKGROUP)
        return vid

    def u32(self, val: int) -> int:
        v = val & 0xFFFFFFFF
        if v not in self._const_u32:
            cid = self.id()
            self._emit(self.types_consts, OP_CONSTANT, self.t_u32, cid, v)
            self._const_u32[v] = cid
        return self._const_u32[v]

    def i32(self, val: int) -> int:
        v = val & 0xFFFFFFFF
        if v not in self._const_i32:
            cid = self.id()
            self._emit(self.types_consts, OP_CONSTANT, self.t_i32, cid, v)
            self._const_i32[v] = cid
        return self._const_i32[v]

    def decl_ssbo_u32(self, binding: int) -> int:
        rt = self.id()
        st = self.id()
        self._emit(self.types_consts, OP_TYPE_RUNTIME_ARRAY, rt, self.t_u32)
        self._emit(self.decorations, OP_DECORATE, rt, DEC_ARRAY_STRIDE, 4)
        self._emit(self.types_consts, OP_TYPE_STRUCT, st, rt)
        self._emit(self.decorations, OP_DECORATE, st, DEC_BUFFER_BLOCK)
        self._emit(self.decorations, OP_MEMBER_DECORATE, st, 0, DEC_OFFSET, 0)
        pt = self.ptr_type(SC_UNIFORM, st)
        var = self.id()
        self._emit(self.decorations, OP_DECORATE, var, DEC_DESCRIPTOR_SET, 0)
        self._emit(self.decorations, OP_DECORATE, var, DEC_BINDING, binding)
        self._emit(self.globals_sec, OP_VARIABLE, pt, var, SC_UNIFORM)
        return var

    def decl_ubo_u32(self, binding: int, field_count: int) -> int:
        st = self.id()
        self._emit(self.types_consts, OP_TYPE_STRUCT, st, *([self.t_u32] * field_count))
        self._emit(self.decorations, OP_DECORATE, st, DEC_BLOCK)
        for i in range(field_count):
            self._emit(self.decorations, OP_MEMBER_DECORATE, st, i, DEC_OFFSET, i * 4)
        pt = self.ptr_type(SC_UNIFORM, st)
        var = self.id()
        self._emit(self.decorations, OP_DECORATE, var, DEC_DESCRIPTOR_SET, 0)
        self._emit(self.decorations, OP_DECORATE, var, DEC_BINDING, binding)
        self._emit(self.globals_sec, OP_VARIABLE, pt, var, SC_UNIFORM)
        return var

    def local_u32(self) -> int:
        vid = self.id()
        self._emit(self.func_vars, OP_VARIABLE, self.p_fn_u32, vid, SC_FUNCTION)
        return vid

    def load(self, ty: int, ptr: int) -> int:
        r = self.id()
        self._emit(self.func_body, OP_LOAD, ty, r, ptr)
        return r

    def store(self, ptr: int, val: int):
        self._emit(self.func_body, OP_STORE, ptr, val)

    def ubo_get(self, ubo: int, idx: int) -> int:
        p = self.id()
        self._emit(self.func_body, OP_ACCESS_CHAIN, self.p_u_u32, p, ubo, self.i32(idx))
        return self.load(self.t_u32, p)

    def ssbo_ptr(self, ssbo: int, idx: int) -> int:
        p = self.id()
        self._emit(self.func_body, OP_ACCESS_CHAIN, self.p_u_u32, p, ssbo, self.i32(0), idx)
        return p

    def ssbo_get(self, ssbo: int, idx: int) -> int:
        return self.load(self.t_u32, self.ssbo_ptr(ssbo, idx))

    def wg_ptr(self, wg_var: int, idx: int) -> int:
        p = self.id()
        self._emit(self.func_body, OP_ACCESS_CHAIN, self.p_wg_u32, p, wg_var, idx)
        return p

    def atomic_add_global(self, ssbo: int, idx: int, val: int):
        p = self.ssbo_ptr(ssbo, idx)
        r = self.id()
        self._emit(self.func_body, OP_ATOMIC_IADD, self.t_u32, r, p, self.u32(SCOPE_DEVICE), self.u32(MEM_SEM_NONE), val)

    def atomic_max_global(self, ssbo: int, idx: int, val: int):
        p = self.ssbo_ptr(ssbo, idx)
        r = self.id()
        self._emit(self.func_body, OP_ATOMIC_UMAX, self.t_u32, r, p, self.u32(SCOPE_DEVICE), self.u32(MEM_SEM_NONE), val)

    def atomic_add_wg(self, wg_var: int, idx: int, val: int):
        p = self.wg_ptr(wg_var, idx)
        r = self.id()
        self._emit(self.func_body, OP_ATOMIC_IADD, self.t_u32, r, p, self.u32(SCOPE_WORKGROUP), self.u32(MEM_SEM_NONE), val)

    def atomic_max_wg(self, wg_var: int, idx: int, val: int):
        p = self.wg_ptr(wg_var, idx)
        r = self.id()
        self._emit(self.func_body, OP_ATOMIC_UMAX, self.t_u32, r, p, self.u32(SCOPE_WORKGROUP), self.u32(MEM_SEM_NONE), val)

    def barrier_wg(self):
        self._emit(
            self.func_body,
            OP_CONTROL_BARRIER,
            self.u32(SCOPE_WORKGROUP),
            self.u32(SCOPE_WORKGROUP),
            self.u32(MEM_SEM_ACQ_REL_WG),
        )

    def bin(self, op: int, ty: int, a: int, b: int) -> int:
        r = self.id()
        self._emit(self.func_body, op, ty, r, a, b)
        return r

    def uadd(self, a: int, b: int) -> int: return self.bin(OP_IADD, self.t_u32, a, b)
    def usub(self, a: int, b: int) -> int: return self.bin(OP_ISUB, self.t_u32, a, b)
    def umul(self, a: int, b: int) -> int: return self.bin(OP_IMUL, self.t_u32, a, b)
    def udiv(self, a: int, b: int) -> int: return self.bin(OP_UDIV, self.t_u32, a, b)
    def shr(self, a: int, b: int) -> int: return self.bin(OP_SHIFT_RIGHT_LOGICAL, self.t_u32, a, b)
    def shl(self, a: int, b: int) -> int: return self.bin(OP_SHIFT_LEFT_LOGICAL, self.t_u32, a, b)
    def band(self, a: int, b: int) -> int: return self.bin(OP_BITWISE_AND, self.t_u32, a, b)
    def ult(self, a: int, b: int) -> int: return self.bin(OP_ULESS_THAN, self.t_bool, a, b)
    def uge(self, a: int, b: int) -> int: return self.bin(OP_UGREATER_THAN_EQUAL, self.t_bool, a, b)
    def ueq(self, a: int, b: int) -> int: return self.bin(OP_IEQUAL, self.t_bool, a, b)
    def une(self, a: int, b: int) -> int: return self.bin(OP_INOT_EQUAL, self.t_bool, a, b)
    def land(self, a: int, b: int) -> int: return self.bin(OP_LOGICAL_AND, self.t_bool, a, b)

    def isub(self, a: int, b: int) -> int: return self.bin(OP_ISUB, self.t_i32, a, b)
    def imul(self, a: int, b: int) -> int: return self.bin(OP_IMUL, self.t_i32, a, b)

    def to_i32(self, u: int) -> int:
        r = self.id()
        self._emit(self.func_body, OP_BITCAST, self.t_i32, r, u)
        return r

    def to_u32(self, i: int) -> int:
        r = self.id()
        self._emit(self.func_body, OP_BITCAST, self.t_u32, r, i)
        return r

    def sabs(self, x_i32: int) -> int:
        r = self.id()
        self._emit(self.func_body, OP_EXT_INST, self.t_i32, r, self.glsl, GLSL_SABS, x_i32)
        return r

    def umin(self, a: int, b: int) -> int:
        r = self.id()
        self._emit(self.func_body, OP_EXT_INST, self.t_u32, r, self.glsl, GLSL_UMIN, a, b)
        return r

    def umax(self, a: int, b: int) -> int:
        r = self.id()
        self._emit(self.func_body, OP_EXT_INST, self.t_u32, r, self.glsl, GLSL_UMAX, a, b)
        return r

    def select_u(self, cond: int, tval: int, fval: int) -> int:
        r = self.id()
        self._emit(self.func_body, OP_SELECT, self.t_u32, r, cond, tval, fval)
        return r

    def label(self, lbl: int):
        self._emit(self.func_body, OP_LABEL, lbl)

    def br(self, lbl: int):
        self._emit(self.func_body, OP_BRANCH, lbl)

    def if_block(self, cond: int, body_fn):
        l_then = self.id()
        l_end = self.id()
        self._emit(self.func_body, OP_SELECTION_MERGE, l_end, 0)
        self._emit(self.func_body, OP_BRANCH_CONDITIONAL, cond, l_then, l_end)
        self.label(l_then)
        body_fn()
        self.br(l_end)
        self.label(l_end)

    def if_else(self, cond: int, then_fn, else_fn):
        l_then = self.id()
        l_else = self.id()
        l_end = self.id()
        self._emit(self.func_body, OP_SELECTION_MERGE, l_end, 0)
        self._emit(self.func_body, OP_BRANCH_CONDITIONAL, cond, l_then, l_else)
        self.label(l_then)
        then_fn()
        self.br(l_end)
        self.label(l_else)
        else_fn()
        self.br(l_end)
        self.label(l_end)

    def finalize(self) -> list[int]:
        entry_lbl = self.id()
        func = []
        self._emit(func, OP_FUNCTION, self.t_void, self.main_fn, 0, self.t_fn_void)
        self._emit(func, OP_LABEL, entry_lbl)
        func.extend(self.func_vars)
        func.extend(self.func_body)
        self._emit(func, OP_RETURN)
        self._emit(func, OP_FUNCTION_END)

        header = [SPIRV_MAGIC, SPIRV_VERSION_1_0, 0, self.next_id, 0]
        return (
            header
            + self.caps
            + self.ext_imports
            + self.mem_model
            + self.entry_points
            + self.exec_modes
            + self.decorations
            + self.types_consts
            + self.globals_sec
            + func
        )


def build_isp_stats_reduce_spirv() -> list[int]:
    b = StatsSpirvBuilder()

    in_ssbo = b.decl_ssbo_u32(0)
    out_ssbo = b.decl_ssbo_u32(1)
    ubo = b.decl_ubo_u32(2, 24)

    # Scratch function variables for decoded quad channels
    v_r10 = b.local_u32()
    v_g1 = b.local_u32()
    v_g2 = b.local_u32()
    v_b10 = b.local_u32()
    v_r8 = b.local_u32()
    v_g8 = b.local_u32()
    v_b8 = b.local_u32()
    v_tmp = b.local_u32()

    c0 = b.u32(0)
    c1 = b.u32(1)
    c2 = b.u32(2)
    c3 = b.u32(3)
    c4 = b.u32(4)
    c6 = b.u32(6)
    c8 = b.u32(8)
    c16 = b.u32(16)
    c255 = b.u32(255)
    c0xFF = b.u32(0xFF)
    c0xFFFF = b.u32(0xFFFF)

    # Load gl_LocalInvocationIndex and zero shared memory
    lid = b.load(b.t_u32, b.var_lid)
    b.store(b.wg_ptr(b.s_histY, lid), c0)
    b.store(b.wg_ptr(b.s_histR, lid), c0)
    b.store(b.wg_ptr(b.s_histG, lid), c0)
    b.store(b.wg_ptr(b.s_histB, lid), c0)
    b.if_block(b.ult(lid, c16), lambda: b.store(b.wg_ptr(b.s_summary, lid), c0))
    b.barrier_wg()

    # Load GlobalInvocationID.x and .y
    p_gx = b.id()
    p_gy = b.id()
    b._emit(b.func_body, OP_ACCESS_CHAIN, b.p_in_u32, p_gx, b.var_gid, b.i32(0))
    b._emit(b.func_body, OP_ACCESS_CHAIN, b.p_in_u32, p_gy, b.var_gid, b.i32(1))
    gid_x = b.load(b.t_u32, p_gx)
    gid_y = b.load(b.t_u32, p_gy)

    # Load uniform fields
    u_width = b.ubo_get(ubo, 0)
    u_height = b.ubo_get(ubo, 1)
    u_fmt = b.ubo_get(ubo, 2)
    u_flags = b.ubo_get(ubo, 3)
    u_viewX = b.ubo_get(ubo, 4)
    u_viewY = b.ubo_get(ubo, 5)
    u_viewW = b.ubo_get(ubo, 6)
    u_viewH = b.ubo_get(ubo, 7)
    u_gridCols = b.ubo_get(ubo, 8)
    u_gridRows = b.ubo_get(ubo, 9)
    u_stepQuad = b.ubo_get(ubo, 10)
    u_awbThresh = b.ubo_get(ubo, 11)
    u_afX0 = b.ubo_get(ubo, 12)
    u_afY0 = b.ubo_get(ubo, 13)
    u_afX1 = b.ubo_get(ubo, 14)
    u_afY1 = b.ubo_get(ubo, 15)

    def read_sample10(sx: int, sy: int) -> int:
        idx = b.uadd(b.umul(sy, u_width), sx)
        def fmt0():
            w = b.ssbo_get(in_ssbo, b.shr(idx, c1))
            odd = b.une(b.band(idx, c1), c0)
            v16 = b.select_u(odd, b.shr(w, c16), b.band(w, c0xFFFF))
            b.store(v_tmp, b.shr(v16, c6))
        def fmt_other():
            def fmt1():
                w = b.ssbo_get(in_ssbo, b.shr(idx, c2))
                sh = b.shl(b.band(idx, c3), c3)
                b.store(v_tmp, b.shl(b.band(b.shr(w, sh), c0xFF), c2))
            def fmt2_or_3():
                def fmt3():
                    w = b.ssbo_get(in_ssbo, idx)
                    b.store(v_tmp, b.shr(b.band(w, b.u32(0xFFF)), c2))
                def fmt2():
                    w = b.ssbo_get(in_ssbo, idx)
                    r = b.band(w, c0xFF)
                    g = b.band(b.shr(w, c8), c0xFF)
                    bl = b.band(b.shr(w, c16), c0xFF)
                    yv = b.uadd(b.uadd(b.umul(b.u32(77), r), b.umul(b.u32(150), g)), b.uadd(b.umul(b.u32(29), bl), b.u32(128)))
                    y8 = b.umin(b.shr(yv, c8), c255)
                    b.store(v_tmp, b.shl(y8, c2))
                b.if_else(b.ueq(u_fmt, c3), fmt3, fmt2)
            b.if_else(b.ueq(u_fmt, c1), fmt1, fmt2_or_3)
        b.if_else(b.ueq(u_fmt, c0), fmt0, fmt_other)
        return b.load(b.t_u32, v_tmp)

    stepPx = b.shl(b.umax(u_stepQuad, c1), c1)
    px = b.uadd(u_viewX, b.umul(gid_x, stepPx))
    py = b.uadd(u_viewY, b.umul(gid_y, stepPx))
    endX = b.uadd(u_viewX, u_viewW)
    endY = b.uadd(u_viewY, u_viewH)
    in_bounds = b.land(b.ult(b.uadd(px, c1), endX), b.ult(b.uadd(py, c1), endY))

    def process_quad():
        def decode_rgba8():
            p = b.ssbo_get(in_ssbo, b.uadd(b.umul(py, u_width), px))
            r8 = b.band(p, c0xFF)
            g8 = b.band(b.shr(p, c8), c0xFF)
            b8 = b.band(b.shr(p, c16), c0xFF)
            b.store(v_r8, r8)
            b.store(v_g8, g8)
            b.store(v_b8, b8)
            b.store(v_r10, b.shl(r8, c2))
            g10 = b.shl(g8, c2)
            b.store(v_g1, g10)
            b.store(v_g2, g10)
            b.store(v_b10, b.shl(b8, c2))

        def decode_bayer():
            r10 = read_sample10(px, py)
            g1 = read_sample10(b.uadd(px, c1), py)
            g2 = read_sample10(px, b.uadd(py, c1))
            b10 = read_sample10(b.uadd(px, c1), b.uadd(py, c1))
            b.store(v_r10, r10)
            b.store(v_g1, g1)
            b.store(v_g2, g2)
            b.store(v_b10, b10)
            b.store(v_r8, b.shr(r10, c2))
            b.store(v_g8, b.shr(b.uadd(b.uadd(g1, g2), c1), c3))
            b.store(v_b8, b.shr(b10, c2))

        b.if_else(b.ueq(u_fmt, c2), decode_rgba8, decode_bayer)

        r10 = b.load(b.t_u32, v_r10)
        g1 = b.load(b.t_u32, v_g1)
        g2 = b.load(b.t_u32, v_g2)
        b10 = b.load(b.t_u32, v_b10)
        r8 = b.load(b.t_u32, v_r8)
        g8 = b.load(b.t_u32, v_g8)
        b8 = b.load(b.t_u32, v_b8)

        yv = b.uadd(b.uadd(b.umul(b.u32(77), r8), b.umul(b.u32(150), g8)), b.uadd(b.umul(b.u32(29), b8), b.u32(128)))
        y8 = b.umin(b.shr(yv, c8), c255)
        gAvg10 = b.shr(b.uadd(b.uadd(g1, g2), c1), c1)

        # 1. 16x16 AE/AWB grid
        def do_ae():
            gx = b.umin(b.udiv(b.umul(b.usub(px, u_viewX), u_gridCols), b.umax(u_viewW, c1)), b.usub(u_gridCols, c1))
            gy = b.umin(b.udiv(b.umul(b.usub(py, u_viewY), u_gridRows), b.umax(u_viewH, c1)), b.usub(u_gridRows, c1))
            cell = b.uadd(b.umul(gy, u_gridCols), gx)
            b.atomic_add_global(out_ssbo, cell, r10)
            b.atomic_add_global(out_ssbo, b.uadd(b.u32(256), cell), g1)
            b.atomic_add_global(out_ssbo, b.uadd(b.u32(512), cell), b10)
            b.atomic_add_global(out_ssbo, b.uadd(b.u32(768), cell), g2)
            b.atomic_add_global(out_ssbo, b.uadd(b.u32(1024), cell), c1)

        b.if_block(b.une(b.band(u_flags, c4), c0), do_ae)

        # 2. 256-bin Y/R/G/B histograms in shared memory
        def do_hist():
            b.atomic_add_wg(b.s_histY, y8, c1)
            b.atomic_add_wg(b.s_histR, r8, c1)
            b.atomic_add_wg(b.s_histG, g8, c1)
            b.atomic_add_wg(b.s_histB, b8, c1)
            b.atomic_add_wg(b.s_summary, b.u32(15), c1)

        b.if_block(b.une(b.band(u_flags, c2), c0), do_hist)

        # 3. Global & highlight-excluded AWB sums in shared memory
        b.atomic_add_wg(b.s_summary, c0, r10)
        b.atomic_add_wg(b.s_summary, c1, gAvg10)
        b.atomic_add_wg(b.s_summary, c2, b10)
        b.atomic_add_wg(b.s_summary, c3, c1)
        maxCh = b.umax(b.umax(r10, b10), b.umax(g1, g2))

        def do_awb_valid():
            b.atomic_add_wg(b.s_summary, c4, r10)
            b.atomic_add_wg(b.s_summary, b.u32(5), gAvg10)
            b.atomic_add_wg(b.s_summary, c6, b10)
            b.atomic_add_wg(b.s_summary, b.u32(7), c1)

        b.if_block(b.ult(maxCh, u_awbThresh), do_awb_valid)

        # 4. AF ROI CDAF horizontal & vertical Tenengrad + Laplacian energy
        in_af = b.land(
            b.une(b.band(u_flags, c1), c0),
            b.land(
                b.land(b.uge(px, u_afX0), b.ult(px, u_afX1)),
                b.land(b.uge(py, u_afY0), b.ult(py, u_afY1)),
            ),
        )

        def do_cdaf():
            is_rgba = b.ueq(u_fmt, c2)
            gx_s = b.select_u(is_rgba, px, b.uadd(px, c1))
            gC = b.select_u(is_rgba, b.shl(y8, c2), g1)
            gC14 = b.shl(gC, c4)
            b.atomic_max_wg(b.s_summary, b.u32(12), gC14)
            b.atomic_add_wg(b.s_summary, b.u32(13), gC)
            b.atomic_add_wg(b.s_summary, b.u32(14), c1)

            def do_horiz():
                gR1 = read_sample10(b.uadd(gx_s, c2), py)
                dH1 = b.sabs(b.isub(b.to_i32(gR1), b.to_i32(gC)))
                eH1 = b.shr(b.to_u32(b.imul(dH1, dH1)), c2)
                b.atomic_add_wg(b.s_summary, c8, eH1)

                def do_horiz2():
                    gR2 = read_sample10(b.uadd(gx_s, c4), py)
                    t2 = b.to_i32(b.shl(gR1, c1))
                    lapH = b.sabs(b.isub(b.isub(t2, b.to_i32(gC)), b.to_i32(gR2)))
                    eLapH = b.shr(b.to_u32(b.imul(lapH, lapH)), c1)
                    b.atomic_add_wg(b.s_summary, b.u32(9), eLapH)

                b.if_block(b.ult(b.uadd(gx_s, c4), u_width), do_horiz2)

            b.if_block(b.ult(b.uadd(gx_s, c2), u_width), do_horiz)

            def do_vert():
                gD1 = read_sample10(gx_s, b.uadd(py, c2))
                dV1 = b.sabs(b.isub(b.to_i32(gD1), b.to_i32(gC)))
                eV1 = b.shr(b.to_u32(b.imul(dV1, dV1)), c2)
                b.atomic_add_wg(b.s_summary, b.u32(10), eV1)

                def do_vert2():
                    gD2 = read_sample10(gx_s, b.uadd(py, c4))
                    t2 = b.to_i32(b.shl(gD1, c1))
                    lapV = b.sabs(b.isub(b.isub(t2, b.to_i32(gC)), b.to_i32(gD2)))
                    eLapV = b.shr(b.to_u32(b.imul(lapV, lapV)), c1)
                    b.atomic_add_wg(b.s_summary, b.u32(11), eLapV)

                b.if_block(b.ult(b.uadd(py, c4), u_height), do_vert2)

            b.if_block(b.ult(b.uadd(py, c2), u_height), do_vert)

        b.if_block(in_af, do_cdaf)

    b.if_block(in_bounds, process_quad)

    b.barrier_wg()

    # Single workgroup flush from shared memory to global SSBO
    for wg_arr, base_off in [
        (b.s_histY, 1280),
        (b.s_histR, 1536),
        (b.s_histG, 1792),
        (b.s_histB, 2048),
    ]:
        val = b.load(b.t_u32, b.wg_ptr(wg_arr, lid))
        b.if_block(b.une(val, c0), lambda a=base_off, v=val: b.atomic_add_global(out_ssbo, b.uadd(b.u32(a), lid), v))

    def flush_summary():
        sv = b.load(b.t_u32, b.wg_ptr(b.s_summary, lid))
        def flush_nz():
            idx = b.uadd(b.u32(2304), lid)
            b.if_else(
                b.ueq(lid, b.u32(12)),
                lambda: b.atomic_max_global(out_ssbo, idx, sv),
                lambda: b.atomic_add_global(out_ssbo, idx, sv),
            )
        b.if_block(b.une(sv, c0), flush_nz)

    b.if_block(b.ult(lid, c16), flush_summary)

    return b.finalize()


def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    out_path = os.path.join(script_dir, "..", "fuji_vulkan_stats_spirv.h")
    words = build_isp_stats_reduce_spirv()

    if "--use-glslang" in sys.argv:
        comp_path = os.path.join(script_dir, "isp_stats_reduce.comp")
        spv_path = comp_path + ".spv"
        subprocess.run(["glslangValidator", "-V", comp_path, "-o", spv_path], check=True)
        with open(spv_path, "rb") as f:
            raw = f.read()
        os.remove(spv_path)
        words = list(struct.unpack(f"<{len(raw) // 4}I", raw))

    lines = [
        "#ifndef FUJI_VULKAN_STATS_SPIRV_H",
        "#define FUJI_VULKAN_STATS_SPIRV_H",
        "",
        "/* Auto-generated by tools/native/vulkan-optics/shaders/generate_stats_spirv.py — DO NOT EDIT */",
        "#include <stdint.h>",
        "",
        f"static const uint32_t kFujiSpirvIspStatsReduce[{len(words)}] = {{",
    ]
    for i in range(0, len(words), 8):
        chunk = words[i : i + 8]
        lines.append("    " + ", ".join(f"0x{w:08x}u" for w in chunk) + ",")
    lines.extend([
        "};",
        "",
        "#endif /* FUJI_VULKAN_STATS_SPIRV_H */",
        "",
    ])
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines))
    print(f"Generated {out_path} ({len(words)} SPIR-V words = {len(words) * 4} bytes)")


if __name__ == "__main__":
    main()
