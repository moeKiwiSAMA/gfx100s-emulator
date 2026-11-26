#!/usr/bin/env python3
"""
Generates tools/native/vulkan-optics/fuji_vulkan_shaders_spirv.h containing
spec-compliant Khronos SPIR-V 1.0 compute shaders for:
  0. optics_warp:  IBIS + Brown-Conrady distortion + zoom + lateral CA + vignetting (bilinear)
  1. pupil_bokeh:  Thin-lens CoC depth bokeh with 5 2D entrance-pupil shapes
                   (CIRCLE, POLYGON, STAR_5, HEART, RING) + rim/onion-ring modulation
  2. cmos_bayer:   Stateless SplitMix64 (portable 32-bit u64 emulation, no shaderInt64)
                   Box-Muller photon shot + ISO read noise + Bayer CFA readout (8..16-bit)

If `glslangValidator` is installed on PATH and `--use-glslang` is passed, compiles
the adjacent `.comp` GLSL 450 files directly; otherwise emits deterministic,
zero-dependency Khronos SPIR-V 1.0 binaries verified on Mesa Lavapipe & Vulkan 1.1+ ICDs.
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
OP_TYPE_FLOAT = 22
OP_TYPE_VECTOR = 23
OP_TYPE_RUNTIME_ARRAY = 29
OP_TYPE_STRUCT = 30
OP_TYPE_POINTER = 32
OP_TYPE_FUNCTION = 33
OP_CONSTANT_TRUE = 41
OP_CONSTANT_FALSE = 42
OP_CONSTANT = 43
OP_CONSTANT_COMPOSITE = 44
OP_SPEC_CONSTANT = 50
OP_FUNCTION = 54
OP_FUNCTION_END = 56
OP_VARIABLE = 59
OP_LOAD = 61
OP_STORE = 62
OP_ACCESS_CHAIN = 65
OP_DECORATE = 71
OP_MEMBER_DECORATE = 72
OP_COMPOSITE_CONSTRUCT = 80
OP_COMPOSITE_EXTRACT = 81
OP_CONVERT_FTOU = 109
OP_CONVERT_FTOS = 110
OP_CONVERT_STOF = 111
OP_CONVERT_UTOF = 112
OP_BITCAST = 124
OP_SN_EGATE = 126
OP_FNEGATE = 127
OP_IADD = 128
OP_FADD = 129
OP_ISUB = 130
OP_FSUB = 131
OP_IMUL = 132
OP_FMUL = 133
OP_UDIV = 134
OP_FDIV = 136
OP_IADD_CARRY = 149
OP_UMUL_EXTENDED = 151
OP_LOGICAL_OR = 166
OP_LOGICAL_AND = 167
OP_LOGICAL_NOT = 168
OP_SELECT = 169
OP_IEQUAL = 170
OP_INOT_EQUAL = 171
OP_UGREATER_THAN_EQUAL = 174
OP_SGREATER_THAN_EQUAL = 175
OP_ULESS_THAN = 176
OP_SLESS_THAN = 177
OP_SGREATER_THAN = 173
OP_SLESS_THAN_EQUAL = 179
OP_FORD_LESS_THAN = 184
OP_FORD_GREATER_THAN = 186
OP_FORD_LESS_THAN_EQUAL = 188
OP_FORD_GREATER_THAN_EQUAL = 190
OP_SHIFT_RIGHT_LOGICAL = 194
OP_SHIFT_RIGHT_ARITHMETIC = 195
OP_SHIFT_LEFT_LOGICAL = 196
OP_BITWISE_OR = 197
OP_BITWISE_XOR = 198
OP_BITWISE_AND = 199
OP_LOOP_MERGE = 246
OP_SELECTION_MERGE = 247
OP_LABEL = 248
OP_BRANCH = 249
OP_BRANCH_CONDITIONAL = 250
OP_RETURN = 253

# GLSL.std.450 extended instructions
GLSL_FABS = 4
GLSL_SABS = 5
GLSL_FLOOR = 8
GLSL_CEIL = 9
GLSL_SIN = 13
GLSL_COS = 14
GLSL_ATAN2 = 25
GLSL_POW = 26
GLSL_EXP = 27
GLSL_LOG = 28
GLSL_SQRT = 31
GLSL_FMIN = 37
GLSL_UMIN = 38
GLSL_SMIN = 39
GLSL_FMAX = 40
GLSL_UMAX = 41
GLSL_SMAX = 42
GLSL_FCLAMP = 43
GLSL_SMOOTHSTEP = 49
GLSL_UCLAMP = 44
GLSL_SCLAMP = 45
GLSL_FMIX = 46

# Enums
CAP_SHADER = 1
ADDR_LOGICAL = 0
MEM_GLSL450 = 1
EXEC_GL_COMPUTE = 5
EXEC_MODE_LOCAL_SIZE = 17

SC_UNIFORM_CONSTANT = 0
SC_INPUT = 1
SC_UNIFORM = 2
SC_FUNCTION = 7

DEC_SPEC_ID = 1
DEC_BLOCK = 2
DEC_BUFFER_BLOCK = 3
DEC_ARRAY_STRIDE = 6
DEC_BUILTIN = 11
DEC_BINDING = 33
DEC_DESCRIPTOR_SET = 34
DEC_OFFSET = 35

BUILTIN_GLOBAL_INVOCATION_ID = 28


def f32_bits(val: float) -> int:
    return struct.unpack("<I", struct.pack("<f", float(val)))[0]


def encode_str(s: str) -> list[int]:
    raw = s.encode("utf-8") + b"\x00"
    while len(raw) % 4 != 0:
        raw += b"\x00"
    return list(struct.unpack(f"<{len(raw) // 4}I", raw))


class SpirvBuilder:
    """Structured Khronos SPIR-V 1.0 builder keeping sections in strict §2.4 order."""

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
        self._const_f32 = {}

        # Standard preamble
        self.glsl = self.id()
        self.main_fn = self.id()
        self.var_gid = self.id()

        self._emit(self.caps, OP_CAPABILITY, CAP_SHADER)
        self._emit_str(self.ext_imports, OP_EXT_INST_IMPORT, [self.glsl], "GLSL.std.450")
        self._emit(self.mem_model, OP_MEMORY_MODEL, ADDR_LOGICAL, MEM_GLSL450)
        self._emit_str(self.entry_points, OP_ENTRY_POINT, [EXEC_GL_COMPUTE, self.main_fn], "main", [self.var_gid])
        self._emit(self.exec_modes, OP_EXECUTION_MODE, self.main_fn, EXEC_MODE_LOCAL_SIZE, 8, 8, 1)

        # Core types
        self.t_void = self.id()
        self.t_bool = self.id()
        self.t_u32 = self.id()
        self.t_i32 = self.id()
        self.t_f32 = self.id()
        self.t_uvec2 = self.id()
        self.t_uvec3 = self.id()
        self.t_uvec4 = self.id()
        self.t_vec4 = self.id()
        self.t_fn_void = self.id()

        self._emit(self.types_consts, OP_TYPE_VOID, self.t_void)
        self._emit(self.types_consts, OP_TYPE_BOOL, self.t_bool)
        self._emit(self.types_consts, OP_TYPE_INT, self.t_u32, 32, 0)
        self._emit(self.types_consts, OP_TYPE_INT, self.t_i32, 32, 1)
        self._emit(self.types_consts, OP_TYPE_FLOAT, self.t_f32, 32)
        self._emit(self.types_consts, OP_TYPE_VECTOR, self.t_uvec2, self.t_u32, 2)
        self._emit(self.types_consts, OP_TYPE_VECTOR, self.t_uvec3, self.t_u32, 3)
        self._emit(self.types_consts, OP_TYPE_VECTOR, self.t_uvec4, self.t_u32, 4)
        self._emit(self.types_consts, OP_TYPE_VECTOR, self.t_vec4, self.t_f32, 4)
        self._emit(self.types_consts, OP_TYPE_FUNCTION, self.t_fn_void, self.t_void)

        # Common pointers
        self.p_in_uvec3 = self.ptr_type(SC_INPUT, self.t_uvec3)
        self.p_in_u32 = self.ptr_type(SC_INPUT, self.t_u32)
        self.p_u_f32 = self.ptr_type(SC_UNIFORM, self.t_f32)
        self.p_u_u32 = self.ptr_type(SC_UNIFORM, self.t_u32)
        self.p_u_i32 = self.ptr_type(SC_UNIFORM, self.t_i32)
        self.p_u_vec4 = self.ptr_type(SC_UNIFORM, self.t_vec4)
        self.p_u_uvec4 = self.ptr_type(SC_UNIFORM, self.t_uvec4)
        self.p_fn_f32 = self.ptr_type(SC_FUNCTION, self.t_f32)
        self.p_fn_i32 = self.ptr_type(SC_FUNCTION, self.t_i32)
        self.p_fn_u32 = self.ptr_type(SC_FUNCTION, self.t_u32)
        self.p_fn_vec4 = self.ptr_type(SC_FUNCTION, self.t_vec4)

        self._emit(self.decorations, OP_DECORATE, self.var_gid, DEC_BUILTIN, BUILTIN_GLOBAL_INVOCATION_ID)
        self._emit(self.globals_sec, OP_VARIABLE, self.p_in_uvec3, self.var_gid, SC_INPUT)

    def id(self) -> int:
        v = self.next_id
        self.next_id += 1
        return v

    def _emit(self, sec: list, op: int, *args: int):
        sec.append(((1 + len(args)) << 16) | (op & 0xFFFF))
        sec.extend(a & 0xFFFFFFFF for a in args)

    def _emit_str(self, sec: list, op: int, prefix: list[int], text: str, suffix: list[int] = ()):
        sw = encode_str(text)
        total = 1 + len(prefix) + len(sw) + len(suffix)
        sec.append((total << 16) | (op & 0xFFFF))
        sec.extend(a & 0xFFFFFFFF for a in prefix)
        sec.extend(sw)
        sec.extend(a & 0xFFFFFFFF for a in suffix)

    def ptr_type(self, sc: int, base_type: int) -> int:
        tid = self.id()
        self._emit(self.types_consts, OP_TYPE_POINTER, tid, sc, base_type)
        return tid

    def u32(self, v: int) -> int:
        v &= 0xFFFFFFFF
        if v not in self._const_u32:
            cid = self.id()
            self._emit(self.types_consts, OP_CONSTANT, self.t_u32, cid, v)
            self._const_u32[v] = cid
        return self._const_u32[v]

    def spec_u32(self, spec_id: int, default_val: int) -> int:
        cid = self.id()
        self._emit(self.decorations, OP_DECORATE, cid, DEC_SPEC_ID, spec_id)
        self._emit(self.types_consts, OP_SPEC_CONSTANT, self.t_u32, cid, default_val & 0xFFFFFFFF)
        return cid

    def i32(self, v: int) -> int:
        key = v & 0xFFFFFFFF
        if key not in self._const_i32:
            cid = self.id()
            self._emit(self.types_consts, OP_CONSTANT, self.t_i32, cid, key)
            self._const_i32[key] = cid
        return self._const_i32[key]

    def f32(self, v: float) -> int:
        bits = f32_bits(v)
        if bits not in self._const_f32:
            cid = self.id()
            self._emit(self.types_consts, OP_CONSTANT, self.t_f32, cid, bits)
            self._const_f32[bits] = cid
        return self._const_f32[bits]

    def ssbo_array(self, elem_type: int, stride: int, binding: int) -> tuple[int, int]:
        arr_t = self.id()
        struct_t = self.id()
        self._emit(self.decorations, OP_DECORATE, arr_t, DEC_ARRAY_STRIDE, stride)
        self._emit(self.decorations, OP_DECORATE, struct_t, DEC_BUFFER_BLOCK)
        self._emit(self.decorations, OP_MEMBER_DECORATE, struct_t, 0, DEC_OFFSET, 0)
        self._emit(self.types_consts, OP_TYPE_RUNTIME_ARRAY, arr_t, elem_type)
        self._emit(self.types_consts, OP_TYPE_STRUCT, struct_t, arr_t)
        ptr_s = self.ptr_type(SC_UNIFORM, struct_t)
        var_id = self.id()
        self._emit(self.decorations, OP_DECORATE, var_id, DEC_DESCRIPTOR_SET, 0)
        self._emit(self.decorations, OP_DECORATE, var_id, DEC_BINDING, binding)
        self._emit(self.globals_sec, OP_VARIABLE, ptr_s, var_id, SC_UNIFORM)
        return var_id, self.ptr_type(SC_UNIFORM, elem_type)

    def uniform_struct(self, field_types: list[int], binding: int) -> int:
        struct_t = self.id()
        self._emit(self.decorations, OP_DECORATE, struct_t, DEC_BLOCK)
        for idx in range(len(field_types)):
            self._emit(self.decorations, OP_MEMBER_DECORATE, struct_t, idx, DEC_OFFSET, idx * 4)
        self._emit(self.types_consts, OP_TYPE_STRUCT, struct_t, *field_types)
        ptr_s = self.ptr_type(SC_UNIFORM, struct_t)
        var_id = self.id()
        self._emit(self.decorations, OP_DECORATE, var_id, DEC_DESCRIPTOR_SET, 0)
        self._emit(self.decorations, OP_DECORATE, var_id, DEC_BINDING, binding)
        self._emit(self.globals_sec, OP_VARIABLE, ptr_s, var_id, SC_UNIFORM)
        return var_id

    def local_var(self, ptr_t: int) -> int:
        vid = self.id()
        self._emit(self.func_vars, OP_VARIABLE, ptr_t, vid, SC_FUNCTION)
        return vid

    def op(self, opcode: int, res_type: int, *args: int) -> int:
        r = self.id()
        self._emit(self.func_body, opcode, res_type, r, *args)
        return r

    def void_op(self, opcode: int, *args: int):
        self._emit(self.func_body, opcode, *args)

    def glsl_op(self, inst: int, res_type: int, *args: int) -> int:
        r = self.id()
        self._emit(self.func_body, OP_EXT_INST, res_type, r, self.glsl, inst, *args)
        return r

    def label(self, lbl: int = None) -> int:
        if lbl is None:
            lbl = self.id()
        self.void_op(OP_LABEL, lbl)
        return lbl

    def load_ubo(self, ubo_var: int, field_idx: int, is_float: bool) -> int:
        pt = self.p_u_f32 if is_float else self.p_u_u32
        t = self.t_f32 if is_float else self.t_u32
        ptr = self.op(OP_ACCESS_CHAIN, pt, ubo_var, self.i32(field_idx))
        return self.op(OP_LOAD, t, ptr)

    def load_ubo_i32(self, ubo_var: int, field_idx: int) -> int:
        ptr = self.op(OP_ACCESS_CHAIN, self.p_u_i32, ubo_var, self.i32(field_idx))
        return self.op(OP_LOAD, self.t_i32, ptr)

    def load_ssbo(self, var_id: int, ptr_elem_t: int, elem_t: int, idx_u32: int) -> int:
        ptr = self.op(OP_ACCESS_CHAIN, ptr_elem_t, var_id, self.i32(0), idx_u32)
        return self.op(OP_LOAD, elem_t, ptr)

    def store_ssbo(self, var_id: int, ptr_elem_t: int, idx_u32: int, val: int):
        ptr = self.op(OP_ACCESS_CHAIN, ptr_elem_t, var_id, self.i32(0), idx_u32)
        self.void_op(OP_STORE, ptr, val)

    def finish(self) -> list[int]:
        entry_lbl = self.id()
        fn_words = []
        self._emit(fn_words, OP_FUNCTION, self.t_void, self.main_fn, 0, self.t_fn_void)
        self._emit(fn_words, OP_LABEL, entry_lbl)
        fn_words.extend(self.func_vars)
        fn_words.extend(self.func_body)
        self._emit(fn_words, OP_RETURN)
        self._emit(fn_words, OP_FUNCTION_END)

        body = (
            self.caps
            + self.ext_imports
            + self.mem_model
            + self.entry_points
            + self.exec_modes
            + self.decorations
            + self.types_consts
            + self.globals_sec
            + fn_words
        )
        return [SPIRV_MAGIC, SPIRV_VERSION_1_0, 0, self.next_id, 0] + body


def optics_ubo_types(b: SpirvBuilder) -> list[int]:
    # 32 x 32-bit fields matching FujiVkOpticsParams (128 bytes)
    return [
        b.t_u32, b.t_u32, b.t_u32, b.t_u32,  # 0..3: srcW, srcH, dstW, dstH
        b.t_f32, b.t_f32, b.t_f32, b.t_f32,  # 4..7: k1, k2, p1, p2
        b.t_f32, b.t_f32, b.t_f32, b.t_f32,  # 8..11: caRed, caBlue, cos4, mechVig
        b.t_f32, b.t_f32, b.t_f32, b.t_f32,  # 12..15: focalMm, sensorW, focusDistM, fNumber
        b.t_f32, b.t_f32, b.t_f32, b.t_f32,  # 16..19: invZoom, ibisX, ibisY, cosRoll
        b.t_f32, b.t_f32, b.t_f32, b.t_f32,  # 20..23: sinRoll, maxBokehPx, bladeRound, catsEye
        b.t_f32, b.t_f32, b.t_f32, b.t_f32,  # 24..27: onionRing, rimHighlight, planarDepthM, imageCircleMm
        b.t_u32, b.t_u32, b.t_u32, b.t_u32,  # 28..31: pupilShape, bladeCount, borderClamp, flags
    ]


def build_optics_warp_spirv() -> list[int]:
    b = SpirvBuilder()
    in_buf, p_in_v4 = b.ssbo_array(b.t_vec4, 16, 0)
    out_buf, p_out_v4 = b.ssbo_array(b.t_vec4, 16, 1)
    ubo = b.uniform_struct(optics_ubo_types(b), 2)

    # Specialization constants (zero-branch driver-compiled variants; defaults match UBO dynamic mode)
    spec_border = b.spec_u32(0, 0xFFFFFFFF)  # 0=BLACK, 1=CLAMP, 0xFFFFFFFF=UBO
    spec_has_ca = b.spec_u32(1, 1)           # 0=skip R/B CA (3x fewer loads), 1=full lateral CA
    spec_has_vig = b.spec_u32(2, 1)          # 0=skip vignetting math, 1=cos^p + mechanical

    # Function-scope locals (declared in entry block)
    v_r = b.local_var(b.p_fn_f32)
    v_g = b.local_var(b.p_fn_f32)
    v_b = b.local_var(b.p_fn_f32)
    v_vig = b.local_var(b.p_fn_f32)

    p_gx = b.op(OP_ACCESS_CHAIN, b.p_in_u32, b.var_gid, b.u32(0))
    gx = b.op(OP_LOAD, b.t_u32, p_gx)
    p_gy = b.op(OP_ACCESS_CHAIN, b.p_in_u32, b.var_gid, b.u32(1))
    gy = b.op(OP_LOAD, b.t_u32, p_gy)

    src_w = b.load_ubo(ubo, 0, False)
    src_h = b.load_ubo(ubo, 1, False)
    dst_w = b.load_ubo(ubo, 2, False)
    dst_h = b.load_ubo(ubo, 3, False)

    in_bounds = b.op(
        OP_LOGICAL_AND,
        b.t_bool,
        b.op(OP_ULESS_THAN, b.t_bool, gx, dst_w),
        b.op(OP_ULESS_THAN, b.t_bool, gy, dst_h),
    )
    lbl_body = b.id()
    lbl_done = b.id()
    b.void_op(OP_SELECTION_MERGE, lbl_done, 0)
    b.void_op(OP_BRANCH_CONDITIONAL, in_bounds, lbl_body, lbl_done)

    b.label(lbl_body)
    idx = b.op(OP_IADD, b.t_u32, b.op(OP_IMUL, b.t_u32, gy, dst_w), gx)

    k1 = b.load_ubo(ubo, 4, True)
    k2 = b.load_ubo(ubo, 5, True)
    p1 = b.load_ubo(ubo, 6, True)
    p2 = b.load_ubo(ubo, 7, True)
    ca_r = b.load_ubo(ubo, 8, True)
    ca_b = b.load_ubo(ubo, 9, True)
    cos4_p = b.load_ubo(ubo, 10, True)
    mech_vig = b.load_ubo(ubo, 11, True)
    focal_mm = b.load_ubo(ubo, 12, True)
    sensor_w = b.load_ubo(ubo, 13, True)
    inv_zoom = b.load_ubo(ubo, 16, True)
    shift_x = b.load_ubo(ubo, 17, True)
    shift_y = b.load_ubo(ubo, 18, True)
    cos_roll = b.load_ubo(ubo, 19, True)
    sin_roll = b.load_ubo(ubo, 20, True)
    border_clamp = b.load_ubo(ubo, 30, False)

    f_dst_w = b.op(OP_CONVERT_UTOF, b.t_f32, dst_w)
    f_dst_h = b.op(OP_CONVERT_UTOF, b.t_f32, dst_h)
    f_src_w = b.op(OP_CONVERT_UTOF, b.t_f32, src_w)
    f_src_h = b.op(OP_CONVERT_UTOF, b.t_f32, src_h)
    dst_cx = b.op(OP_FMUL, b.t_f32, b.op(OP_FSUB, b.t_f32, f_dst_w, b.f32(1.0)), b.f32(0.5))
    dst_cy = b.op(OP_FMUL, b.t_f32, b.op(OP_FSUB, b.t_f32, f_dst_h, b.f32(1.0)), b.f32(0.5))
    src_cx = b.op(OP_FMUL, b.t_f32, b.op(OP_FSUB, b.t_f32, f_src_w, b.f32(1.0)), b.f32(0.5))
    src_cy = b.op(OP_FMUL, b.t_f32, b.op(OP_FSUB, b.t_f32, f_src_h, b.f32(1.0)), b.f32(0.5))

    diag_sq = b.op(
        OP_FADD, b.t_f32, b.op(OP_FMUL, b.t_f32, dst_cx, dst_cx), b.op(OP_FMUL, b.t_f32, dst_cy, dst_cy)
    )
    half_diag = b.glsl_op(GLSL_FMAX, b.t_f32, b.glsl_op(GLSL_SQRT, b.t_f32, diag_sq), b.f32(1.0))
    inv_hd = b.op(OP_FDIV, b.t_f32, b.f32(1.0), half_diag)

    # Aspect scale
    same_dims = b.op(
        OP_LOGICAL_AND,
        b.t_bool,
        b.op(OP_IEQUAL, b.t_bool, dst_w, src_w),
        b.op(OP_IEQUAL, b.t_bool, dst_h, src_h),
    )
    sx_s = b.op(OP_FDIV, b.t_f32, src_cx, b.glsl_op(GLSL_FMAX, b.t_f32, dst_cx, b.f32(1e-5)))
    sy_s = b.op(OP_FDIV, b.t_f32, src_cy, b.glsl_op(GLSL_FMAX, b.t_f32, dst_cy, b.f32(1e-5)))
    aspect = b.op(OP_SELECT, b.t_f32, same_dims, b.f32(1.0), b.glsl_op(GLSL_FMIN, b.t_f32, sx_s, sy_s))
    norm_to_src = b.op(OP_FMUL, b.t_f32, aspect, half_diag)

    fx = b.op(OP_CONVERT_UTOF, b.t_f32, gx)
    fy = b.op(OP_CONVERT_UTOF, b.t_f32, gy)
    dx_s = b.op(OP_FSUB, b.t_f32, b.op(OP_FSUB, b.t_f32, fx, dst_cx), shift_x)
    dy_s = b.op(OP_FSUB, b.t_f32, b.op(OP_FSUB, b.t_f32, fy, dst_cy), shift_y)
    px = b.op(OP_FADD, b.t_f32, b.op(OP_FMUL, b.t_f32, dx_s, cos_roll), b.op(OP_FMUL, b.t_f32, dy_s, sin_roll))
    py = b.op(
        OP_FADD,
        b.t_f32,
        b.op(OP_FMUL, b.t_f32, b.op(OP_FNEGATE, b.t_f32, dx_s), sin_roll),
        b.op(OP_FMUL, b.t_f32, dy_s, cos_roll),
    )

    nx = b.op(OP_FMUL, b.t_f32, px, inv_hd)
    ny = b.op(OP_FMUL, b.t_f32, py, inv_hd)
    r2 = b.op(OP_FADD, b.t_f32, b.op(OP_FMUL, b.t_f32, nx, nx), b.op(OP_FMUL, b.t_f32, ny, ny))
    r4 = b.op(OP_FMUL, b.t_f32, r2, r2)
    radial = b.op(
        OP_FADD,
        b.t_f32,
        b.f32(1.0),
        b.op(OP_FADD, b.t_f32, b.op(OP_FMUL, b.t_f32, k1, r2), b.op(OP_FMUL, b.t_f32, k2, r4)),
    )
    radial_ok = b.op(OP_FORD_GREATER_THAN, b.t_bool, radial, b.f32(0.1))

    lbl_inside = b.id()
    lbl_outside = b.id()
    lbl_after_warp = b.id()
    b.void_op(OP_SELECTION_MERGE, lbl_after_warp, 0)
    b.void_op(OP_BRANCH_CONDITIONAL, radial_ok, lbl_inside, lbl_outside)

    b.label(lbl_outside)
    zero4 = b.op(OP_COMPOSITE_CONSTRUCT, b.t_vec4, b.f32(0.0), b.f32(0.0), b.f32(0.0), b.f32(0.0))
    b.store_ssbo(out_buf, p_out_v4, idx, zero4)
    b.void_op(OP_BRANCH, lbl_after_warp)

    b.label(lbl_inside)
    inv_rad = b.op(OP_FDIV, b.t_f32, b.f32(1.0), radial)
    nx_ny = b.op(OP_FMUL, b.t_f32, nx, ny)
    tang_x = b.op(
        OP_FADD,
        b.t_f32,
        b.op(OP_FMUL, b.t_f32, b.op(OP_FMUL, b.t_f32, b.f32(2.0), p1), nx_ny),
        b.op(OP_FMUL, b.t_f32, p2, b.op(OP_FADD, b.t_f32, r2, b.op(OP_FMUL, b.t_f32, b.f32(2.0), b.op(OP_FMUL, b.t_f32, nx, nx)))),
    )
    tang_y = b.op(
        OP_FADD,
        b.t_f32,
        b.op(OP_FMUL, b.t_f32, p1, b.op(OP_FADD, b.t_f32, r2, b.op(OP_FMUL, b.t_f32, b.f32(2.0), b.op(OP_FMUL, b.t_f32, ny, ny)))),
        b.op(OP_FMUL, b.t_f32, b.op(OP_FMUL, b.t_f32, b.f32(2.0), p2), nx_ny),
    )
    ux = b.op(OP_FSUB, b.t_f32, b.op(OP_FMUL, b.t_f32, nx, inv_rad), tang_x)
    uy = b.op(OP_FSUB, b.t_f32, b.op(OP_FMUL, b.t_f32, ny, inv_rad), tang_y)

    zx = b.op(OP_FMUL, b.t_f32, ux, inv_zoom)
    zy = b.op(OP_FMUL, b.t_f32, uy, inv_zoom)

    src_w_i = b.op(OP_BITCAST, b.t_i32, src_w)
    src_h_i = b.op(OP_BITCAST, b.t_i32, src_h)
    src_w_m1 = b.op(OP_ISUB, b.t_i32, src_w_i, b.i32(1))
    src_h_m1 = b.op(OP_ISUB, b.t_i32, src_h_i, b.i32(1))
    use_spec_border = b.op(OP_INOT_EQUAL, b.t_bool, spec_border, b.u32(0xFFFFFFFF))
    eff_border = b.op(OP_SELECT, b.t_u32, use_spec_border, spec_border, border_clamp)
    clamp_on = b.op(OP_INOT_EQUAL, b.t_bool, eff_border, b.u32(0))

    def fetch_vec4(xi_id: int, yi_id: int) -> int:
        c_xi = b.glsl_op(GLSL_SCLAMP, b.t_i32, xi_id, b.i32(0), src_w_m1)
        c_yi = b.glsl_op(GLSL_SCLAMP, b.t_i32, yi_id, b.i32(0), src_h_m1)
        in_x = b.op(
            OP_LOGICAL_AND,
            b.t_bool,
            b.op(OP_SGREATER_THAN_EQUAL, b.t_bool, xi_id, b.i32(0)),
            b.op(OP_SLESS_THAN, b.t_bool, xi_id, src_w_i),
        )
        in_y = b.op(
            OP_LOGICAL_AND,
            b.t_bool,
            b.op(OP_SGREATER_THAN_EQUAL, b.t_bool, yi_id, b.i32(0)),
            b.op(OP_SLESS_THAN, b.t_bool, yi_id, src_h_i),
        )
        valid = b.op(OP_LOGICAL_OR, b.t_bool, clamp_on, b.op(OP_LOGICAL_AND, b.t_bool, in_x, in_y))
        u_xi = b.op(OP_BITCAST, b.t_u32, c_xi)
        u_yi = b.op(OP_BITCAST, b.t_u32, c_yi)
        s_idx = b.op(OP_IADD, b.t_u32, b.op(OP_IMUL, b.t_u32, u_yi, src_w), u_xi)
        v4 = b.load_ssbo(in_buf, p_in_v4, b.t_vec4, s_idx)
        z4 = b.op(OP_COMPOSITE_CONSTRUCT, b.t_vec4, b.f32(0.0), b.f32(0.0), b.f32(0.0), b.f32(0.0))
        return b.op(OP_SELECT, b.t_vec4, valid, v4, z4)

    def fetch_chan(xi_id: int, yi_id: int, chan: int) -> int:
        v4 = fetch_vec4(xi_id, yi_id)
        return b.op(OP_COMPOSITE_EXTRACT, b.t_f32, v4, chan)

    def sample_bilinear_chan(sx_id: int, sy_id: int, chan: int) -> int:
        flx = b.glsl_op(GLSL_FLOOR, b.t_f32, sx_id)
        fly = b.glsl_op(GLSL_FLOOR, b.t_f32, sy_id)
        x0 = b.op(OP_CONVERT_FTOS, b.t_i32, flx)
        y0 = b.op(OP_CONVERT_FTOS, b.t_i32, fly)
        x1 = b.op(OP_IADD, b.t_i32, x0, b.i32(1))
        y1 = b.op(OP_IADD, b.t_i32, y0, b.i32(1))
        tx = b.op(OP_FSUB, b.t_f32, sx_id, flx)
        ty = b.op(OP_FSUB, b.t_f32, sy_id, fly)
        p00 = fetch_chan(x0, y0, chan)
        p10 = fetch_chan(x1, y0, chan)
        p01 = fetch_chan(x0, y1, chan)
        p11 = fetch_chan(x1, y1, chan)
        top = b.glsl_op(GLSL_FMIX, b.t_f32, p00, p10, tx)
        bot = b.glsl_op(GLSL_FMIX, b.t_f32, p01, p11, tx)
        return b.glsl_op(GLSL_FMIX, b.t_f32, top, bot, ty)

    sx_g = b.op(OP_FADD, b.t_f32, src_cx, b.op(OP_FMUL, b.t_f32, zx, norm_to_src))
    sy_g = b.op(OP_FADD, b.t_f32, src_cy, b.op(OP_FMUL, b.t_f32, zy, norm_to_src))

    # Specialized lateral CA branch: SPEC_HAS_CA == 0 fetches all 3 RGB channels in 1 bilinear lookup (4 loads vs 12)
    do_ca = b.op(OP_INOT_EQUAL, b.t_bool, spec_has_ca, b.u32(0))
    lbl_ca_yes = b.id()
    lbl_ca_no = b.id()
    lbl_after_ca = b.id()
    b.void_op(OP_SELECTION_MERGE, lbl_after_ca, 0)
    b.void_op(OP_BRANCH_CONDITIONAL, do_ca, lbl_ca_yes, lbl_ca_no)

    b.label(lbl_ca_yes)
    inv_ca_r = b.op(OP_FDIV, b.t_f32, b.f32(1.0), b.glsl_op(GLSL_FMAX, b.t_f32, ca_r, b.f32(1e-4)))
    inv_ca_b = b.op(OP_FDIV, b.t_f32, b.f32(1.0), b.glsl_op(GLSL_FMAX, b.t_f32, ca_b, b.f32(1e-4)))
    sx_r = b.op(OP_FADD, b.t_f32, src_cx, b.op(OP_FMUL, b.t_f32, b.op(OP_FMUL, b.t_f32, zx, inv_ca_r), norm_to_src))
    sy_r = b.op(OP_FADD, b.t_f32, src_cy, b.op(OP_FMUL, b.t_f32, b.op(OP_FMUL, b.t_f32, zy, inv_ca_r), norm_to_src))
    sx_b = b.op(OP_FADD, b.t_f32, src_cx, b.op(OP_FMUL, b.t_f32, b.op(OP_FMUL, b.t_f32, zx, inv_ca_b), norm_to_src))
    sy_b = b.op(OP_FADD, b.t_f32, src_cy, b.op(OP_FMUL, b.t_f32, b.op(OP_FMUL, b.t_f32, zy, inv_ca_b), norm_to_src))
    b.void_op(OP_STORE, v_r, sample_bilinear_chan(sx_r, sy_r, 0))
    b.void_op(OP_STORE, v_g, sample_bilinear_chan(sx_g, sy_g, 1))
    b.void_op(OP_STORE, v_b, sample_bilinear_chan(sx_b, sy_b, 2))
    b.void_op(OP_BRANCH, lbl_after_ca)

    b.label(lbl_ca_no)
    flx_n = b.glsl_op(GLSL_FLOOR, b.t_f32, sx_g)
    fly_n = b.glsl_op(GLSL_FLOOR, b.t_f32, sy_g)
    x0_n = b.op(OP_CONVERT_FTOS, b.t_i32, flx_n)
    y0_n = b.op(OP_CONVERT_FTOS, b.t_i32, fly_n)
    x1_n = b.op(OP_IADD, b.t_i32, x0_n, b.i32(1))
    y1_n = b.op(OP_IADD, b.t_i32, y0_n, b.i32(1))
    tx_n = b.op(OP_FSUB, b.t_f32, sx_g, flx_n)
    ty_n = b.op(OP_FSUB, b.t_f32, sy_g, fly_n)
    q00 = fetch_vec4(x0_n, y0_n)
    q10 = fetch_vec4(x1_n, y0_n)
    q01 = fetch_vec4(x0_n, y1_n)
    q11 = fetch_vec4(x1_n, y1_n)
    for chan_i, dst_var in ((0, v_r), (1, v_g), (2, v_b)):
        c00 = b.op(OP_COMPOSITE_EXTRACT, b.t_f32, q00, chan_i)
        c10 = b.op(OP_COMPOSITE_EXTRACT, b.t_f32, q10, chan_i)
        c01 = b.op(OP_COMPOSITE_EXTRACT, b.t_f32, q01, chan_i)
        c11 = b.op(OP_COMPOSITE_EXTRACT, b.t_f32, q11, chan_i)
        top_c = b.glsl_op(GLSL_FMIX, b.t_f32, c00, c10, tx_n)
        bot_c = b.glsl_op(GLSL_FMIX, b.t_f32, c01, c11, tx_n)
        b.void_op(OP_STORE, dst_var, b.glsl_op(GLSL_FMIX, b.t_f32, top_c, bot_c, ty_n))
    b.void_op(OP_BRANCH, lbl_after_ca)

    b.label(lbl_after_ca)
    r_val = b.op(OP_LOAD, b.t_f32, v_r)
    g_val = b.op(OP_LOAD, b.t_f32, v_g)
    b_val = b.op(OP_LOAD, b.t_f32, v_b)

    # Nearest depth sample
    dxi = b.op(OP_CONVERT_FTOS, b.t_i32, b.glsl_op(GLSL_FLOOR, b.t_f32, b.op(OP_FADD, b.t_f32, sx_g, b.f32(0.5))))
    dyi = b.op(OP_CONVERT_FTOS, b.t_i32, b.glsl_op(GLSL_FLOOR, b.t_f32, b.op(OP_FADD, b.t_f32, sy_g, b.f32(0.5))))
    d_val = fetch_chan(dxi, dyi, 3)

    # Specialized vignetting branch: SPEC_HAS_VIG == 0 skips sqrt/pow completely
    b.void_op(OP_STORE, v_vig, b.f32(1.0))
    do_vig = b.op(OP_INOT_EQUAL, b.t_bool, spec_has_vig, b.u32(0))
    lbl_vig_yes = b.id()
    lbl_after_vig = b.id()
    b.void_op(OP_SELECTION_MERGE, lbl_after_vig, 0)
    b.void_op(OP_BRANCH_CONDITIONAL, do_vig, lbl_vig_yes, lbl_after_vig)

    b.label(lbl_vig_yes)
    mm_per_px = b.op(OP_FDIV, b.t_f32, sensor_w, b.glsl_op(GLSL_FMAX, b.t_f32, f_dst_w, b.f32(1.0)))
    r_px = b.glsl_op(GLSL_SQRT, b.t_f32, b.op(OP_FADD, b.t_f32, b.op(OP_FMUL, b.t_f32, px, px), b.op(OP_FMUL, b.t_f32, py, py)))
    r_mm = b.op(OP_FMUL, b.t_f32, r_px, mm_per_px)
    tan_th = b.op(OP_FDIV, b.t_f32, r_mm, b.glsl_op(GLSL_FMAX, b.t_f32, focal_mm, b.f32(1e-3)))
    cos_th = b.op(OP_FDIV, b.t_f32, b.f32(1.0), b.glsl_op(GLSL_SQRT, b.t_f32, b.op(OP_FADD, b.t_f32, b.f32(1.0), b.op(OP_FMUL, b.t_f32, tan_th, tan_th))))
    cos_pow = b.glsl_op(GLSL_POW, b.t_f32, cos_th, b.glsl_op(GLSL_FMAX, b.t_f32, cos4_p, b.f32(0.0)))
    has_cos = b.op(OP_FORD_GREATER_THAN, b.t_bool, cos4_p, b.f32(0.0))
    vig_cos = b.op(OP_SELECT, b.t_f32, has_cos, cos_pow, b.f32(1.0))
    vig_mech = b.glsl_op(GLSL_FMAX, b.t_f32, b.op(OP_FSUB, b.t_f32, b.f32(1.0), b.op(OP_FMUL, b.t_f32, mech_vig, r2)), b.f32(0.0))
    radius = b.op(OP_FMUL, b.t_f32, b.load_ubo(ubo, 27, True), b.f32(0.5))
    has_circle = b.op(OP_FORD_GREATER_THAN, b.t_bool, radius, b.f32(0.0))
    safe_radius = b.op(OP_SELECT, b.t_f32, has_circle, radius, b.f32(1.0))
    onset = b.op(OP_FMUL, b.t_f32, safe_radius, b.f32(0.88))
    smooth = b.glsl_op(GLSL_SMOOTHSTEP, b.t_f32, onset, safe_radius, r_mm)
    circle = b.op(OP_SELECT, b.t_f32, has_circle, b.op(OP_FSUB, b.t_f32, b.f32(1.0), smooth), b.f32(1.0))
    b.void_op(OP_STORE, v_vig, b.op(OP_FMUL, b.t_f32, b.op(OP_FMUL, b.t_f32, vig_cos, vig_mech), circle))
    b.void_op(OP_BRANCH, lbl_after_vig)

    b.label(lbl_after_vig)
    vig = b.op(OP_LOAD, b.t_f32, v_vig)

    out_v = b.op(
        OP_COMPOSITE_CONSTRUCT,
        b.t_vec4,
        b.op(OP_FMUL, b.t_f32, r_val, vig),
        b.op(OP_FMUL, b.t_f32, g_val, vig),
        b.op(OP_FMUL, b.t_f32, b_val, vig),
        d_val,
    )
    b.store_ssbo(out_buf, p_out_v4, idx, out_v)
    b.void_op(OP_BRANCH, lbl_after_warp)

    b.label(lbl_after_warp)
    b.void_op(OP_BRANCH, lbl_done)

    b.label(lbl_done)
    return b.finish()


def build_pupil_bokeh_spirv() -> list[int]:
    """True 2D Entrance-Pupil Depth Bokeh (CIRCLE, POLYGON, STAR_5, HEART, RING) with Specialization Constants."""
    b = SpirvBuilder()
    in_buf, p_in_v4 = b.ssbo_array(b.t_vec4, 16, 0)
    out_buf, p_out_v4 = b.ssbo_array(b.t_vec4, 16, 1)
    ubo = b.uniform_struct(optics_ubo_types(b), 2)

    # Specialization constant: 0=CIRCLE, 1=POLYGON, 2=STAR_5, 3=HEART, 4=RING, 0xFFFFFFFF=dynamic UBO
    spec_pupil_shape = b.spec_u32(0, 0xFFFFFFFF)

    # Function-scope accumulators & loop counters (must be in entry block!)
    v_acc_r = b.local_var(b.p_fn_f32)
    v_acc_g = b.local_var(b.p_fn_f32)
    v_acc_b = b.local_var(b.p_fn_f32)
    v_wsum = b.local_var(b.p_fn_f32)
    v_mask = b.local_var(b.p_fn_f32)
    v_dy = b.local_var(b.p_fn_i32)
    v_dx = b.local_var(b.p_fn_i32)

    p_gx = b.op(OP_ACCESS_CHAIN, b.p_in_u32, b.var_gid, b.u32(0))
    gx = b.op(OP_LOAD, b.t_u32, p_gx)
    p_gy = b.op(OP_ACCESS_CHAIN, b.p_in_u32, b.var_gid, b.u32(1))
    gy = b.op(OP_LOAD, b.t_u32, p_gy)

    dst_w = b.load_ubo(ubo, 2, False)
    dst_h = b.load_ubo(ubo, 3, False)

    in_bounds = b.op(
        OP_LOGICAL_AND,
        b.t_bool,
        b.op(OP_ULESS_THAN, b.t_bool, gx, dst_w),
        b.op(OP_ULESS_THAN, b.t_bool, gy, dst_h),
    )
    lbl_body = b.id()
    lbl_done = b.id()
    b.void_op(OP_SELECTION_MERGE, lbl_done, 0)
    b.void_op(OP_BRANCH_CONDITIONAL, in_bounds, lbl_body, lbl_done)

    b.label(lbl_body)
    idx = b.op(OP_IADD, b.t_u32, b.op(OP_IMUL, b.t_u32, gy, dst_w), gx)
    center = b.load_ssbo(in_buf, p_in_v4, b.t_vec4, idx)
    c_depth = b.op(OP_COMPOSITE_EXTRACT, b.t_f32, center, 3)

    focal_mm = b.load_ubo(ubo, 12, True)
    sensor_w = b.load_ubo(ubo, 13, True)
    focus_m = b.load_ubo(ubo, 14, True)
    f_num = b.load_ubo(ubo, 15, True)
    max_r = b.load_ubo(ubo, 21, True)
    blade_round = b.load_ubo(ubo, 22, True)
    onion_ring = b.load_ubo(ubo, 24, True)
    rim_hl = b.load_ubo(ubo, 25, True)
    planar_depth = b.load_ubo(ubo, 26, True)
    pupil_shape = b.load_ubo(ubo, 28, False)
    blade_count = b.load_ubo(ubo, 29, False)
    flags = b.load_ubo(ubo, 31, False)

    has_depth = b.op(OP_INOT_EQUAL, b.t_bool, b.op(OP_BITWISE_AND, b.t_u32, flags, b.u32(1)), b.u32(0))
    use_center_d = b.op(OP_LOGICAL_AND, b.t_bool, has_depth, b.op(OP_FORD_GREATER_THAN, b.t_bool, c_depth, b.f32(0.01)))
    depth_m = b.op(OP_SELECT, b.t_f32, use_center_d, c_depth, planar_depth)

    s_focus_mm = b.glsl_op(GLSL_FMAX, b.t_f32, b.op(OP_FMUL, b.t_f32, focus_m, b.f32(1000.0)), b.op(OP_FADD, b.t_f32, focal_mm, b.f32(1.0)))
    s_depth_mm = b.glsl_op(GLSL_FMAX, b.t_f32, b.op(OP_FMUL, b.t_f32, depth_m, b.f32(1000.0)), b.f32(10.0))
    diff_d = b.glsl_op(GLSL_FABS, b.t_f32, b.op(OP_FSUB, b.t_f32, s_depth_mm, s_focus_mm))
    f2 = b.op(OP_FMUL, b.t_f32, focal_mm, focal_mm)
    denom = b.glsl_op(GLSL_FMAX, b.t_f32, b.op(OP_FMUL, b.t_f32, f_num, b.op(OP_FSUB, b.t_f32, s_focus_mm, focal_mm)), b.f32(1e-3))
    coc_mm = b.op(OP_FMUL, b.t_f32, b.op(OP_FDIV, b.t_f32, diff_d, s_depth_mm), b.op(OP_FDIV, b.t_f32, f2, denom))
    f_dst_w = b.op(OP_CONVERT_UTOF, b.t_f32, dst_w)
    px_per_mm = b.op(OP_FDIV, b.t_f32, f_dst_w, b.glsl_op(GLSL_FMAX, b.t_f32, sensor_w, b.f32(1.0)))
    coc_raw = b.op(OP_FMUL, b.t_f32, b.op(OP_FMUL, b.t_f32, b.f32(0.5), coc_mm), px_per_mm)
    coc_px = b.glsl_op(GLSL_FCLAMP, b.t_f32, coc_raw, b.f32(0.0), max_r)

    bokeh_enabled = b.op(OP_INOT_EQUAL, b.t_bool, b.op(OP_BITWISE_AND, b.t_u32, flags, b.u32(2)), b.u32(0))
    do_bokeh = b.op(OP_LOGICAL_AND, b.t_bool, bokeh_enabled, b.op(OP_FORD_GREATER_THAN_EQUAL, b.t_bool, coc_px, b.f32(0.5)))

    lbl_bokeh = b.id()
    lbl_pass = b.id()
    lbl_after = b.id()
    b.void_op(OP_SELECTION_MERGE, lbl_after, 0)
    b.void_op(OP_BRANCH_CONDITIONAL, do_bokeh, lbl_bokeh, lbl_pass)

    b.label(lbl_pass)
    b.store_ssbo(out_buf, p_out_v4, idx, center)
    b.void_op(OP_BRANCH, lbl_after)

    b.label(lbl_bokeh)
    # Use 2x2 energy-preserving half-res pyramid (stored in inBuf at 256B-aligned half_base) when cocPx > 8.0
    # and host ran bokeh_downsample_2x (flags bit 2 = 4u), cutting large-radius taps & L2 footprint by 4x!
    has_half = b.op(OP_INOT_EQUAL, b.t_bool, b.op(OP_BITWISE_AND, b.t_u32, flags, b.u32(4)), b.u32(0))
    use_half = b.op(OP_LOGICAL_AND, b.t_bool, has_half, b.op(OP_FORD_GREATER_THAN, b.t_bool, coc_px, b.f32(8.0)))

    half_base_raw = b.op(OP_BITWISE_AND, b.t_u32, b.op(OP_IADD, b.t_u32, b.op(OP_IMUL, b.t_u32, dst_w, dst_h), b.u32(15)), b.u32(0xFFFFFFF0))
    buf_base = b.op(OP_SELECT, b.t_u32, use_half, half_base_raw, b.u32(0))
    half_w = b.op(OP_SHIFT_RIGHT_LOGICAL, b.t_u32, dst_w, b.u32(1))
    half_h = b.op(OP_SHIFT_RIGHT_LOGICAL, b.t_u32, dst_h, b.u32(1))
    buf_w = b.op(OP_SELECT, b.t_u32, use_half, half_w, dst_w)
    buf_h = b.op(OP_SELECT, b.t_u32, use_half, half_h, dst_h)
    buf_w_i = b.op(OP_BITCAST, b.t_i32, buf_w)
    buf_h_i = b.op(OP_BITCAST, b.t_i32, buf_h)
    buf_w_m1 = b.op(OP_ISUB, b.t_i32, buf_w_i, b.i32(1))
    buf_h_m1 = b.op(OP_ISUB, b.t_i32, buf_h_i, b.i32(1))

    cx_u = b.op(OP_SELECT, b.t_u32, use_half, b.op(OP_SHIFT_RIGHT_LOGICAL, b.t_u32, gx, b.u32(1)), gx)
    cy_u = b.op(OP_SELECT, b.t_u32, use_half, b.op(OP_SHIFT_RIGHT_LOGICAL, b.t_u32, gy, b.u32(1)), gy)
    cx_i = b.op(OP_BITCAST, b.t_i32, cx_u)
    cy_i = b.op(OP_BITCAST, b.t_i32, cy_u)

    step_f = b.op(OP_SELECT, b.t_f32, use_half, b.f32(2.0), b.f32(1.0))
    inv_step = b.op(OP_SELECT, b.t_f32, use_half, b.f32(0.5), b.f32(1.0))
    gx_odd = b.op(OP_INOT_EQUAL, b.t_bool, b.op(OP_BITWISE_AND, b.t_u32, gx, b.u32(1)), b.u32(0))
    gy_odd = b.op(OP_INOT_EQUAL, b.t_bool, b.op(OP_BITWISE_AND, b.t_u32, gy, b.u32(1)), b.u32(0))
    ox_half = b.op(OP_SELECT, b.t_f32, gx_odd, b.f32(-0.5), b.f32(0.5))
    oy_half = b.op(OP_SELECT, b.t_f32, gy_odd, b.f32(-0.5), b.f32(0.5))
    ox_f = b.op(OP_SELECT, b.t_f32, use_half, ox_half, b.f32(0.0))
    oy_f = b.op(OP_SELECT, b.t_f32, use_half, oy_half, b.f32(0.0))

    rad = b.op(OP_CONVERT_FTOS, b.t_i32, b.glsl_op(GLSL_CEIL, b.t_f32, b.op(OP_FMUL, b.t_f32, coc_px, inv_step)))
    neg_rad = b.op(OP_SN_EGATE, b.t_i32, rad)
    inv_coc = b.op(OP_FDIV, b.t_f32, b.f32(1.0), coc_px)

    # Decode the uniform mask transform once per output, outside the sampling loops.
    angle_code = b.op(OP_BITWISE_AND, b.t_u32, b.op(OP_SHIFT_RIGHT_LOGICAL, b.t_u32, flags, b.u32(4)), b.u32(16383))
    angle = b.op(OP_FMUL, b.t_f32, b.op(OP_CONVERT_UTOF, b.t_f32, angle_code), b.f32(6.28318530718 / 16384.0))
    cos_a = b.glsl_op(GLSL_COS, b.t_f32, angle)
    sin_a = b.glsl_op(GLSL_SIN, b.t_f32, angle)
    shrink_code = b.op(OP_SHIFT_RIGHT_LOGICAL, b.t_u32, flags, b.u32(18))
    shrink = b.op(OP_FMUL, b.t_f32, b.op(OP_CONVERT_UTOF, b.t_f32, shrink_code), b.f32(0.6 / 16383.0))
    scale = b.op(OP_FSUB, b.t_f32, b.f32(1.0), shrink)
    inv_scale = b.op(OP_FDIV, b.t_f32, b.f32(1.0), scale)

    def edge_weight(edge, radius, width=0.045):
        delta = b.op(OP_FSUB, b.t_f32, edge, radius)
        weight = b.op(OP_FADD, b.t_f32, b.f32(0.5), b.op(OP_FDIV, b.t_f32, delta, b.f32(width)))
        return b.glsl_op(GLSL_FCLAMP, b.t_f32, weight, b.f32(0.0), b.f32(1.0))

    n_blades = b.glsl_op(GLSL_FMAX, b.t_f32, b.op(OP_CONVERT_UTOF, b.t_f32, blade_count), b.f32(5.0))
    sector = b.op(OP_FDIV, b.t_f32, b.f32(6.28318530718), n_blades)
    half_sec = b.op(OP_FMUL, b.t_f32, sector, b.f32(0.5))
    cos_half_sec = b.glsl_op(GLSL_COS, b.t_f32, half_sec)

    use_spec_shape = b.op(OP_INOT_EQUAL, b.t_bool, spec_pupil_shape, b.u32(0xFFFFFFFF))
    eff_shape = b.op(OP_SELECT, b.t_u32, use_spec_shape, spec_pupil_shape, pupil_shape)
    is_circle = b.op(OP_IEQUAL, b.t_bool, eff_shape, b.u32(0))
    is_poly = b.op(OP_IEQUAL, b.t_bool, eff_shape, b.u32(1))
    is_star = b.op(OP_IEQUAL, b.t_bool, eff_shape, b.u32(2))
    is_heart = b.op(OP_IEQUAL, b.t_bool, eff_shape, b.u32(3))
    is_ring = b.op(OP_IEQUAL, b.t_bool, eff_shape, b.u32(4))
    has_rim = b.op(OP_FORD_GREATER_THAN, b.t_bool, rim_hl, b.f32(0.0))
    has_onion = b.op(OP_FORD_GREATER_THAN, b.t_bool, onion_ring, b.f32(0.0))
    has_mod = b.op(OP_LOGICAL_OR, b.t_bool, has_rim, has_onion)
    plain_circle = b.op(OP_LOGICAL_AND, b.t_bool, is_circle, b.op(OP_LOGICAL_NOT, b.t_bool, has_mod))

    b.void_op(OP_STORE, v_acc_r, b.f32(0.0))
    b.void_op(OP_STORE, v_acc_g, b.f32(0.0))
    b.void_op(OP_STORE, v_acc_b, b.f32(0.0))
    b.void_op(OP_STORE, v_wsum, b.f32(0.0))
    b.void_op(OP_STORE, v_dy, neg_rad)

    lbl_y_hdr = b.id()
    lbl_y_body = b.id()
    lbl_y_cont = b.id()
    lbl_y_end = b.id()
    b.void_op(OP_BRANCH, lbl_y_hdr)

    b.label(lbl_y_hdr)
    cur_dy = b.op(OP_LOAD, b.t_i32, v_dy)
    y_cond = b.op(OP_SLESS_THAN_EQUAL, b.t_bool, cur_dy, rad)
    b.void_op(OP_LOOP_MERGE, lbl_y_end, lbl_y_cont, 0)
    b.void_op(OP_BRANCH_CONDITIONAL, y_cond, lbl_y_body, lbl_y_end)

    b.label(lbl_y_body)
    dfy = b.op(OP_FADD, b.t_f32, b.op(OP_FMUL, b.t_f32, b.op(OP_CONVERT_STOF, b.t_f32, cur_dy), step_f), oy_f)
    py_n = b.op(OP_FMUL, b.t_f32, dfy, inv_coc)
    py_sq = b.op(OP_FMUL, b.t_f32, py_n, py_n)
    y_in_disk = b.op(OP_FORD_LESS_THAN_EQUAL, b.t_bool, py_sq, b.f32(1.0))

    lbl_y_disk = b.id()
    lbl_y_skip = b.id()
    b.void_op(OP_SELECTION_MERGE, lbl_y_skip, 0)
    b.void_op(OP_BRANCH_CONDITIONAL, y_in_disk, lbl_y_disk, lbl_y_skip)

    b.label(lbl_y_disk)
    ny = b.glsl_op(GLSL_SCLAMP, b.t_i32, b.op(OP_IADD, b.t_i32, cy_i, cur_dy), b.i32(0), buf_h_m1)
    ny_u = b.op(OP_BITCAST, b.t_u32, ny)
    row_off = b.op(OP_IADD, b.t_u32, buf_base, b.op(OP_IMUL, b.t_u32, ny_u, buf_w))

    # Tight circular X bounds: skip ~22% wasted square corners outside the CoC circle!
    rem_x = b.op(OP_FMUL, b.t_f32, b.glsl_op(GLSL_SQRT, b.t_f32, b.glsl_op(GLSL_FMAX, b.t_f32, b.op(OP_FSUB, b.t_f32, b.f32(1.0), py_sq), b.f32(0.0))), coc_px)
    lo_x_f = b.op(OP_FMUL, b.t_f32, b.op(OP_FSUB, b.t_f32, b.op(OP_FNEGATE, b.t_f32, rem_x), ox_f), inv_step)
    hi_x_f = b.op(OP_FMUL, b.t_f32, b.op(OP_FSUB, b.t_f32, rem_x, ox_f), inv_step)
    dx_min = b.glsl_op(GLSL_SMAX, b.t_i32, neg_rad, b.op(OP_CONVERT_FTOS, b.t_i32, b.glsl_op(GLSL_CEIL, b.t_f32, lo_x_f)))
    dx_max = b.glsl_op(GLSL_SMIN, b.t_i32, rad, b.op(OP_CONVERT_FTOS, b.t_i32, b.glsl_op(GLSL_FLOOR, b.t_f32, hi_x_f)))

    b.void_op(OP_STORE, v_dx, dx_min)
    lbl_x_hdr = b.id()
    lbl_x_body = b.id()
    lbl_x_cont = b.id()
    lbl_x_end = b.id()
    b.void_op(OP_BRANCH, lbl_x_hdr)

    b.label(lbl_x_hdr)
    cur_dx = b.op(OP_LOAD, b.t_i32, v_dx)
    x_cond = b.op(OP_SLESS_THAN_EQUAL, b.t_bool, cur_dx, dx_max)
    b.void_op(OP_LOOP_MERGE, lbl_x_end, lbl_x_cont, 0)
    b.void_op(OP_BRANCH_CONDITIONAL, x_cond, lbl_x_body, lbl_x_end)

    b.label(lbl_x_body)
    dfx = b.op(OP_FADD, b.t_f32, b.op(OP_FMUL, b.t_f32, b.op(OP_CONVERT_STOF, b.t_f32, cur_dx), step_f), ox_f)
    px_n = b.op(OP_FMUL, b.t_f32, dfx, inv_coc)
    r_sq = b.op(OP_FADD, b.t_f32, b.op(OP_FMUL, b.t_f32, px_n, px_n), py_sq)
    in_unit = b.op(OP_FORD_LESS_THAN_EQUAL, b.t_bool, r_sq, b.f32(1.0))

    lbl_in_u = b.id()
    lbl_x_merge = b.id()
    b.void_op(OP_SELECTION_MERGE, lbl_x_merge, 0)
    b.void_op(OP_BRANCH_CONDITIONAL, in_unit, lbl_in_u, lbl_x_merge)

    b.label(lbl_in_u)
    # Fast-path plain CIRCLE (effShape==0 && rim==0 && onion==0): w=1.0 with ZERO sqrt/cos/atan2!
    b.void_op(OP_STORE, v_mask, b.f32(1.0))
    lbl_eval_complex = b.id()
    lbl_after_weight = b.id()
    b.void_op(OP_SELECTION_MERGE, lbl_after_weight, 0)
    b.void_op(OP_BRANCH_CONDITIONAL, plain_circle, lbl_after_weight, lbl_eval_complex)

    b.label(lbl_eval_complex)
    r_len = b.glsl_op(GLSL_SQRT, b.t_f32, r_sq)
    # Screen-aligned PSF coordinates s = (-dx, -dy) / cocPx so sy < 0 is TOP of screen and sy > 0 is BOTTOM.
    sx = b.op(OP_FNEGATE, b.t_f32, px_n)
    sy = b.op(OP_FNEGATE, b.t_f32, py_n)
    sx_n = b.op(OP_FADD, b.t_f32, b.op(OP_FMUL, b.t_f32, sx, cos_a), b.op(OP_FMUL, b.t_f32, sy, sin_a))
    sy_n = b.op(OP_FSUB, b.t_f32, b.op(OP_FMUL, b.t_f32, sy, cos_a), b.op(OP_FMUL, b.t_f32, sx, sin_a))

    # Zero-branch specialized shape dispatch: driver constant-folds unused shapes away when SPEC_PUPIL_SHAPE is set!
    lbl_sh_poly = b.id()
    lbl_sh_not_poly = b.id()
    lbl_sh_star = b.id()
    lbl_sh_not_star = b.id()
    lbl_sh_heart = b.id()
    lbl_sh_not_heart = b.id()
    lbl_sh_ring = b.id()
    lbl_sh_done_ring = b.id()
    lbl_sh_done_heart = b.id()
    lbl_sh_done_star = b.id()
    lbl_sh_done_poly = b.id()

    b.void_op(OP_SELECTION_MERGE, lbl_sh_done_poly, 0)
    b.void_op(OP_BRANCH_CONDITIONAL, is_poly, lbl_sh_poly, lbl_sh_not_poly)

    # 1: POLYGON mask (pointy-top regular polygon matching CPU hexagonWeight)
    b.label(lbl_sh_poly)
    ang_p = b.glsl_op(GLSL_ATAN2, b.t_f32, sy_n, sx_n)
    theta_p = b.op(OP_FADD, b.t_f32, b.op(OP_FADD, b.t_f32, ang_p, b.f32(1.57079632679)), b.f32(6.28318530718))
    q_sec = b.glsl_op(GLSL_FLOOR, b.t_f32, b.op(OP_FDIV, b.t_f32, theta_p, sector))
    local_a = b.op(OP_FSUB, b.t_f32, b.op(OP_FSUB, b.t_f32, theta_p, b.op(OP_FMUL, b.t_f32, q_sec, sector)), half_sec)
    poly_r = b.op(OP_FDIV, b.t_f32, cos_half_sec, b.glsl_op(GLSL_FMAX, b.t_f32, b.glsl_op(GLSL_COS, b.t_f32, local_a), b.f32(0.1)))
    eff_poly_r = b.glsl_op(GLSL_FMIX, b.t_f32, poly_r, b.f32(1.0), b.glsl_op(GLSL_FCLAMP, b.t_f32, blade_round, b.f32(0.0), b.f32(1.0)))
    cutout = b.op(OP_INOT_EQUAL, b.t_bool, b.op(OP_BITWISE_AND, b.t_u32, flags, b.u32(8)), b.u32(0))
    blade_weight = b.op(OP_SELECT, b.t_f32, b.op(OP_FORD_LESS_THAN_EQUAL, b.t_bool, r_len, eff_poly_r), b.f32(1.0), b.f32(0.0))
    m_poly = b.op(OP_SELECT, b.t_f32, cutout, edge_weight(b.op(OP_FMUL, b.t_f32, eff_poly_r, scale), r_len), blade_weight)
    b.void_op(OP_STORE, v_mask, m_poly)
    b.void_op(OP_BRANCH, lbl_sh_done_poly)

    b.label(lbl_sh_not_poly)
    b.void_op(OP_SELECTION_MERGE, lbl_sh_done_star, 0)
    b.void_op(OP_BRANCH_CONDITIONAL, is_star, lbl_sh_star, lbl_sh_not_star)

    # 2: STAR_5 mask: exact straight-edged 5-pointed star (rOut=1.0, rIn=0.382, tip pointing UP)
    b.label(lbl_sh_star)
    ang_s = b.glsl_op(GLSL_ATAN2, b.t_f32, sy_n, sx_n)
    theta_s = b.op(OP_FADD, b.t_f32, b.op(OP_FADD, b.t_f32, ang_s, b.f32(1.57079632679)), b.f32(6.28318530718))
    star_sec = b.f32(1.25663706144)
    star_half = b.f32(0.62831853072)
    q_star = b.glsl_op(GLSL_FLOOR, b.t_f32, b.op(OP_FDIV, b.t_f32, theta_s, star_sec))
    mod_star = b.op(OP_FSUB, b.t_f32, theta_s, b.op(OP_FMUL, b.t_f32, q_star, star_sec))
    phi_star = b.glsl_op(GLSL_FMIN, b.t_f32, mod_star, b.op(OP_FSUB, b.t_f32, star_sec, mod_star))
    sin_rem = b.glsl_op(GLSL_SIN, b.t_f32, b.op(OP_FSUB, b.t_f32, star_half, phi_star))
    sin_phi = b.glsl_op(GLSL_SIN, b.t_f32, phi_star)
    star_denom = b.glsl_op(
        GLSL_FMAX,
        b.t_f32,
        b.op(OP_FADD, b.t_f32, b.op(OP_FMUL, b.t_f32, b.f32(0.382), sin_rem), sin_phi),
        b.f32(1e-5),
    )
    star_r = b.op(OP_FDIV, b.t_f32, b.f32(0.2245339664), star_denom)
    m_star = edge_weight(b.op(OP_FMUL, b.t_f32, star_r, scale), r_len)
    b.void_op(OP_STORE, v_mask, m_star)
    b.void_op(OP_BRANCH, lbl_sh_done_star)

    b.label(lbl_sh_not_star)
    b.void_op(OP_SELECTION_MERGE, lbl_sh_done_heart, 0)
    b.void_op(OP_BRANCH_CONDITIONAL, is_heart, lbl_sh_heart, lbl_sh_not_heart)

    # 3: HEART mask: upright full-size implicit heart (hx^2 + hy^2 - 1)^3 - hx^2 * hy^3 <= 0
    b.label(lbl_sh_heart)
    hx = b.op(OP_FMUL, b.t_f32, sx_n, b.op(OP_FMUL, b.t_f32, inv_scale, b.f32(1.28)))
    hy = b.op(OP_FADD, b.t_f32, b.op(OP_FMUL, b.t_f32, sy_n, b.op(OP_FMUL, b.t_f32, inv_scale, b.f32(-1.34))), b.f32(0.12))
    hx2 = b.op(OP_FMUL, b.t_f32, hx, hx)
    hy2 = b.op(OP_FMUL, b.t_f32, hy, hy)
    hy3 = b.op(OP_FMUL, b.t_f32, hy2, hy)
    hq = b.op(OP_FSUB, b.t_f32, b.op(OP_FADD, b.t_f32, hx2, hy2), b.f32(1.0))
    hq3 = b.op(OP_FMUL, b.t_f32, b.op(OP_FMUL, b.t_f32, hq, hq), hq)
    hf = b.op(OP_FSUB, b.t_f32, hq3, b.op(OP_FMUL, b.t_f32, hx2, hy3))
    m_heart = edge_weight(b.f32(0.0), hf, 0.05)
    b.void_op(OP_STORE, v_mask, m_heart)
    b.void_op(OP_BRANCH, lbl_sh_done_heart)

    b.label(lbl_sh_not_heart)
    b.void_op(OP_SELECTION_MERGE, lbl_sh_done_ring, 0)
    b.void_op(OP_BRANCH_CONDITIONAL, is_ring, lbl_sh_ring, lbl_sh_done_ring)

    # 4: RING mask: 0.50 <= r <= 1.0
    b.label(lbl_sh_ring)
    m_ring = b.op(OP_FMUL, b.t_f32, edge_weight(scale, r_len), edge_weight(r_len, b.op(OP_FMUL, b.t_f32, scale, b.f32(0.5))))
    b.void_op(OP_STORE, v_mask, m_ring)
    b.void_op(OP_BRANCH, lbl_sh_done_ring)

    b.label(lbl_sh_done_ring)
    b.void_op(OP_BRANCH, lbl_sh_done_heart)

    b.label(lbl_sh_done_heart)
    b.void_op(OP_BRANCH, lbl_sh_done_star)

    b.label(lbl_sh_done_star)
    b.void_op(OP_BRANCH, lbl_sh_done_poly)

    b.label(lbl_sh_done_poly)
    mask0 = b.op(OP_LOAD, b.t_f32, v_mask)
    # Apply rim highlight + onion-ring only when enabled (skipping cos() when onion_ring==0)
    lbl_apply_mod = b.id()
    lbl_done_mod = b.id()
    do_mod = b.op(OP_LOGICAL_AND, b.t_bool, has_mod, b.op(OP_FORD_GREATER_THAN, b.t_bool, mask0, b.f32(0.0)))
    b.void_op(OP_SELECTION_MERGE, lbl_done_mod, 0)
    b.void_op(OP_BRANCH_CONDITIONAL, do_mod, lbl_apply_mod, lbl_done_mod)

    b.label(lbl_apply_mod)
    r4_l = b.op(OP_FMUL, b.t_f32, r_sq, r_sq)
    gain = b.op(OP_FADD, b.t_f32, b.f32(1.0), b.op(OP_FMUL, b.t_f32, rim_hl, r4_l))
    onion_mod = b.op(OP_SELECT, b.t_f32, has_onion, b.op(OP_FADD, b.t_f32, b.f32(1.0), b.op(OP_FMUL, b.t_f32, b.op(OP_FMUL, b.t_f32, onion_ring, b.f32(0.25)), b.glsl_op(GLSL_COS, b.t_f32, b.op(OP_FMUL, b.t_f32, r_len, b.f32(25.1327412))))), b.f32(1.0))
    w_mod = b.op(OP_FMUL, b.t_f32, mask0, b.glsl_op(GLSL_FMAX, b.t_f32, b.op(OP_FMUL, b.t_f32, gain, onion_mod), b.f32(0.0)))
    b.void_op(OP_STORE, v_mask, w_mod)
    b.void_op(OP_BRANCH, lbl_done_mod)

    b.label(lbl_done_mod)
    b.void_op(OP_BRANCH, lbl_after_weight)

    b.label(lbl_after_weight)
    w = b.op(OP_LOAD, b.t_f32, v_mask)
    w_pos = b.op(OP_FORD_GREATER_THAN, b.t_bool, w, b.f32(0.0))
    lbl_tap = b.id()
    lbl_after_tap = b.id()
    b.void_op(OP_SELECTION_MERGE, lbl_after_tap, 0)
    b.void_op(OP_BRANCH_CONDITIONAL, w_pos, lbl_tap, lbl_after_tap)

    b.label(lbl_tap)
    nx = b.glsl_op(GLSL_SCLAMP, b.t_i32, b.op(OP_IADD, b.t_i32, cx_i, cur_dx), b.i32(0), buf_w_m1)
    s_idx = b.op(OP_IADD, b.t_u32, row_off, b.op(OP_BITCAST, b.t_u32, nx))
    sv = b.load_ssbo(in_buf, p_in_v4, b.t_vec4, s_idx)
    sr = b.op(OP_COMPOSITE_EXTRACT, b.t_f32, sv, 0)
    sg = b.op(OP_COMPOSITE_EXTRACT, b.t_f32, sv, 1)
    sb = b.op(OP_COMPOSITE_EXTRACT, b.t_f32, sv, 2)

    b.void_op(OP_STORE, v_acc_r, b.op(OP_FADD, b.t_f32, b.op(OP_LOAD, b.t_f32, v_acc_r), b.op(OP_FMUL, b.t_f32, sr, w)))
    b.void_op(OP_STORE, v_acc_g, b.op(OP_FADD, b.t_f32, b.op(OP_LOAD, b.t_f32, v_acc_g), b.op(OP_FMUL, b.t_f32, sg, w)))
    b.void_op(OP_STORE, v_acc_b, b.op(OP_FADD, b.t_f32, b.op(OP_LOAD, b.t_f32, v_acc_b), b.op(OP_FMUL, b.t_f32, sb, w)))
    b.void_op(OP_STORE, v_wsum, b.op(OP_FADD, b.t_f32, b.op(OP_LOAD, b.t_f32, v_wsum), w))
    b.void_op(OP_BRANCH, lbl_after_tap)

    b.label(lbl_after_tap)
    b.void_op(OP_BRANCH, lbl_x_merge)

    b.label(lbl_x_merge)
    b.void_op(OP_BRANCH, lbl_x_cont)

    b.label(lbl_x_cont)
    b.void_op(OP_STORE, v_dx, b.op(OP_IADD, b.t_i32, cur_dx, b.i32(1)))
    b.void_op(OP_BRANCH, lbl_x_hdr)

    b.label(lbl_x_end)
    b.void_op(OP_BRANCH, lbl_y_skip)

    b.label(lbl_y_skip)
    b.void_op(OP_BRANCH, lbl_y_cont)

    b.label(lbl_y_cont)
    b.void_op(OP_STORE, v_dy, b.op(OP_IADD, b.t_i32, cur_dy, b.i32(1)))
    b.void_op(OP_BRANCH, lbl_y_hdr)

    b.label(lbl_y_end)
    wsum = b.op(OP_LOAD, b.t_f32, v_wsum)
    has_w = b.op(OP_FORD_GREATER_THAN, b.t_bool, wsum, b.f32(1e-6))
    inv_w = b.op(OP_FDIV, b.t_f32, b.f32(1.0), b.glsl_op(GLSL_FMAX, b.t_f32, wsum, b.f32(1e-6)))
    out_r = b.op(OP_SELECT, b.t_f32, has_w, b.op(OP_FMUL, b.t_f32, b.op(OP_LOAD, b.t_f32, v_acc_r), inv_w), b.op(OP_COMPOSITE_EXTRACT, b.t_f32, center, 0))
    out_g = b.op(OP_SELECT, b.t_f32, has_w, b.op(OP_FMUL, b.t_f32, b.op(OP_LOAD, b.t_f32, v_acc_g), inv_w), b.op(OP_COMPOSITE_EXTRACT, b.t_f32, center, 1))
    out_b = b.op(OP_SELECT, b.t_f32, has_w, b.op(OP_FMUL, b.t_f32, b.op(OP_LOAD, b.t_f32, v_acc_b), inv_w), b.op(OP_COMPOSITE_EXTRACT, b.t_f32, center, 2))
    out_v = b.op(OP_COMPOSITE_CONSTRUCT, b.t_vec4, out_r, out_g, out_b, c_depth)
    b.store_ssbo(out_buf, p_out_v4, idx, out_v)
    b.void_op(OP_BRANCH, lbl_after)

    b.label(lbl_after)
    b.void_op(OP_BRANCH, lbl_done)

    b.label(lbl_done)
    return b.finish()


def build_cmos_bayer_spirv() -> list[int]:
    """Stateless SplitMix64 (32-bit portable) CMOS Shot/Read Noise + Bayer Mosaic."""
    b = SpirvBuilder()
    in_buf, p_in_v4 = b.ssbo_array(b.t_vec4, 16, 0)
    out_buf, p_out_u32 = b.ssbo_array(b.t_u32, 4, 1)

    # Struct type for OpIAddCarry / OpUMulExtended return {uint32, uint32}
    t_pair = b.id()
    b._emit(b.types_consts, OP_TYPE_STRUCT, t_pair, b.t_u32, b.t_u32)

    cmos_fields = [
        b.t_u32, b.t_u32, b.t_u32, b.t_u32,  # 0..3: width, height, bayerPattern, bitDepth
        b.t_u32, b.t_u32, b.t_u32, b.t_u32,  # 4..7: maxCode, blackLevel, enableNoise, iso
        b.t_f32, b.t_f32, b.t_f32, b.t_f32,  # 8..11: baseIso, fullWell, optScale, readNoiseBase
        b.t_f32, b.t_u32, b.t_u32, b.t_u32,  # 12..15: readNoiseSlope, seedLo, seedHi, frameIndexLo
    ]
    ubo = b.uniform_struct(cmos_fields, 2)
    v_adu = b.local_var(b.p_fn_f32)

    p_gx = b.op(OP_ACCESS_CHAIN, b.p_in_u32, b.var_gid, b.u32(0))
    gx = b.op(OP_LOAD, b.t_u32, p_gx)
    p_gy = b.op(OP_ACCESS_CHAIN, b.p_in_u32, b.var_gid, b.u32(1))
    gy = b.op(OP_LOAD, b.t_u32, p_gy)

    w = b.load_ubo(ubo, 0, False)
    h = b.load_ubo(ubo, 1, False)
    in_bounds = b.op(
        OP_LOGICAL_AND,
        b.t_bool,
        b.op(OP_ULESS_THAN, b.t_bool, gx, w),
        b.op(OP_ULESS_THAN, b.t_bool, gy, h),
    )
    lbl_body = b.id()
    lbl_done = b.id()
    b.void_op(OP_SELECTION_MERGE, lbl_done, 0)
    b.void_op(OP_BRANCH_CONDITIONAL, in_bounds, lbl_body, lbl_done)

    b.label(lbl_body)
    idx = b.op(OP_IADD, b.t_u32, b.op(OP_IMUL, b.t_u32, gy, w), gx)
    px = b.load_ssbo(in_buf, p_in_v4, b.t_vec4, idx)
    r_v = b.op(OP_COMPOSITE_EXTRACT, b.t_f32, px, 0)
    g_v = b.op(OP_COMPOSITE_EXTRACT, b.t_f32, px, 1)
    b_v = b.op(OP_COMPOSITE_EXTRACT, b.t_f32, px, 2)

    bayer = b.load_ubo(ubo, 2, False)
    max_code = b.load_ubo(ubo, 4, False)
    black_lvl = b.load_ubo(ubo, 5, False)
    en_noise = b.load_ubo(ubo, 6, False)
    iso = b.load_ubo(ubo, 7, False)
    base_iso = b.load_ubo(ubo, 8, True)
    full_well = b.load_ubo(ubo, 9, True)
    opt_scale = b.load_ubo(ubo, 10, True)
    rn_base = b.load_ubo(ubo, 11, True)
    rn_slope = b.load_ubo(ubo, 12, True)
    seed_lo = b.load_ubo(ubo, 13, False)
    seed_hi = b.load_ubo(ubo, 14, False)
    frame_lo = b.load_ubo(ubo, 15, False)

    # Bayer CFA channel selection: 0=RGGB, 1=GRBG, 2=GBRG, 3=BGGR
    xo = b.op(OP_BITWISE_AND, b.t_u32, gx, b.u32(1))
    yo = b.op(OP_BITWISE_AND, b.t_u32, gy, b.u32(1))
    xo0 = b.op(OP_IEQUAL, b.t_bool, xo, b.u32(0))
    yo0 = b.op(OP_IEQUAL, b.t_bool, yo, b.u32(0))

    ch_rggb = b.op(OP_SELECT, b.t_u32, yo0, b.op(OP_SELECT, b.t_u32, xo0, b.u32(0), b.u32(1)), b.op(OP_SELECT, b.t_u32, xo0, b.u32(1), b.u32(2)))
    ch_grbg = b.op(OP_SELECT, b.t_u32, yo0, b.op(OP_SELECT, b.t_u32, xo0, b.u32(1), b.u32(0)), b.op(OP_SELECT, b.t_u32, xo0, b.u32(2), b.u32(1)))
    ch_gbrg = b.op(OP_SELECT, b.t_u32, yo0, b.op(OP_SELECT, b.t_u32, xo0, b.u32(1), b.u32(2)), b.op(OP_SELECT, b.t_u32, xo0, b.u32(0), b.u32(1)))
    ch_bggr = b.op(OP_SELECT, b.t_u32, yo0, b.op(OP_SELECT, b.t_u32, xo0, b.u32(2), b.u32(1)), b.op(OP_SELECT, b.t_u32, xo0, b.u32(1), b.u32(0)))

    ch = b.op(OP_SELECT, b.t_u32, b.op(OP_IEQUAL, b.t_bool, bayer, b.u32(1)), ch_grbg, ch_rggb)
    ch = b.op(OP_SELECT, b.t_u32, b.op(OP_IEQUAL, b.t_bool, bayer, b.u32(2)), ch_gbrg, ch)
    ch = b.op(OP_SELECT, b.t_u32, b.op(OP_IEQUAL, b.t_bool, bayer, b.u32(3)), ch_bggr, ch)

    rad_raw = b.op(OP_SELECT, b.t_f32, b.op(OP_IEQUAL, b.t_bool, ch, b.u32(0)), r_v, b.op(OP_SELECT, b.t_f32, b.op(OP_IEQUAL, b.t_bool, ch, b.u32(1)), g_v, b_v))
    rad = b.glsl_op(GLSL_FMAX, b.t_f32, rad_raw, b.f32(0.0))

    expected_e = b.op(OP_FMUL, b.t_f32, b.op(OP_FMUL, b.t_f32, rad, opt_scale), full_well)
    iso_gain = b.op(OP_FDIV, b.t_f32, b.op(OP_CONVERT_UTOF, b.t_f32, iso), b.glsl_op(GLSL_FMAX, b.t_f32, base_iso, b.f32(1.0)))
    usable = b.op(OP_CONVERT_UTOF, b.t_f32, b.op(OP_ISUB, b.t_u32, max_code, black_lvl))
    e_to_adu = b.op(OP_FMUL, b.t_f32, b.op(OP_FDIV, b.t_f32, usable, full_well), iso_gain)
    black_f = b.op(OP_CONVERT_UTOF, b.t_f32, black_lvl)

    noise_on = b.op(OP_INOT_EQUAL, b.t_bool, en_noise, b.u32(0))
    lbl_noisy = b.id()
    lbl_clean = b.id()
    lbl_quant = b.id()
    b.void_op(OP_SELECTION_MERGE, lbl_quant, 0)
    b.void_op(OP_BRANCH_CONDITIONAL, noise_on, lbl_noisy, lbl_clean)

    b.label(lbl_clean)
    clamped_e0 = b.glsl_op(GLSL_FMIN, b.t_f32, expected_e, full_well)
    adu0 = b.op(OP_FADD, b.t_f32, black_f, b.op(OP_FMUL, b.t_f32, clamped_e0, e_to_adu))
    b.void_op(OP_STORE, v_adu, adu0)
    b.void_op(OP_BRANCH, lbl_quant)

    b.label(lbl_noisy)

    # Portable 64-bit unsigned helpers (lo, hi)
    def u64_add(alo, ahi, blo, bhi):
        pair = b.op(OP_IADD_CARRY, t_pair, alo, blo)
        lo = b.op(OP_COMPOSITE_EXTRACT, b.t_u32, pair, 0)
        carry = b.op(OP_COMPOSITE_EXTRACT, b.t_u32, pair, 1)
        hi = b.op(OP_IADD, b.t_u32, b.op(OP_IADD, b.t_u32, ahi, bhi), carry)
        return lo, hi

    def u64_mul(alo, ahi, blo, bhi):
        pair = b.op(OP_UMUL_EXTENDED, t_pair, alo, blo)
        lo = b.op(OP_COMPOSITE_EXTRACT, b.t_u32, pair, 0)
        hi0 = b.op(OP_COMPOSITE_EXTRACT, b.t_u32, pair, 1)
        cross = b.op(OP_IADD, b.t_u32, b.op(OP_IMUL, b.t_u32, alo, bhi), b.op(OP_IMUL, b.t_u32, ahi, blo))
        hi = b.op(OP_IADD, b.t_u32, hi0, cross)
        return lo, hi

    def u64_shr(alo, ahi, s):
        lo = b.op(OP_BITWISE_OR, b.t_u32, b.op(OP_SHIFT_RIGHT_LOGICAL, b.t_u32, alo, b.u32(s)), b.op(OP_SHIFT_LEFT_LOGICAL, b.t_u32, ahi, b.u32(32 - s)))
        hi = b.op(OP_SHIFT_RIGHT_LOGICAL, b.t_u32, ahi, b.u32(s))
        return lo, hi

    def u64_xor(alo, ahi, blo, bhi):
        return b.op(OP_BITWISE_XOR, b.t_u32, alo, blo), b.op(OP_BITWISE_XOR, b.t_u32, ahi, bhi)

    def splitmix64(zlo, zhi):
        lo, hi = u64_add(zlo, zhi, b.u32(0x7F4A7C15), b.u32(0x9E3779B9))
        slo, shi = u64_shr(lo, hi, 30)
        lo, hi = u64_xor(lo, hi, slo, shi)
        lo, hi = u64_mul(lo, hi, b.u32(0x1CE4E5B9), b.u32(0xBF58476D))
        slo, shi = u64_shr(lo, hi, 27)
        lo, hi = u64_xor(lo, hi, slo, shi)
        lo, hi = u64_mul(lo, hi, b.u32(0x133111EB), b.u32(0x94D049BB))
        slo, shi = u64_shr(lo, hi, 31)
        return u64_xor(lo, hi, slo, shi)

    def u64_unit_f32(lo, hi):
        top24 = b.op(OP_SHIFT_RIGHT_LOGICAL, b.t_u32, hi, b.u32(8))
        return b.op(OP_FMUL, b.t_f32, b.op(OP_FADD, b.t_f32, b.op(OP_CONVERT_UTOF, b.t_f32, top24), b.f32(0.5)), b.f32(1.0 / 16777216.0))

    fk_lo, fk_hi = u64_mul(frame_lo, b.u32(0), b.u32(0x7F4A7C15), b.u32(0x9E3779B9))
    pk_lo, pk_hi = u64_mul(idx, b.u32(0), b.u32(0x1CE4E5B9), b.u32(0xBF58476D))
    k_lo, k_hi = u64_xor(seed_lo, seed_hi, fk_lo, fk_hi)
    k_lo, k_hi = u64_xor(k_lo, k_hi, pk_lo, pk_hi)
    bk_lo, bk_hi = splitmix64(k_lo, k_hi)

    r1_lo, r1_hi = splitmix64(bk_lo, bk_hi)
    bk1_lo, bk1_hi = u64_add(bk_lo, bk_hi, b.u32(1), b.u32(0))
    r2_lo, r2_hi = splitmix64(bk1_lo, bk1_hi)

    u1 = b.glsl_op(GLSL_FCLAMP, b.t_f32, u64_unit_f32(r1_lo, r1_hi), b.f32(1e-7), b.f32(1.0))
    u2 = u64_unit_f32(r2_lo, r2_hi)

    bm_rad = b.glsl_op(GLSL_SQRT, b.t_f32, b.op(OP_FMUL, b.t_f32, b.f32(-2.0), b.glsl_op(GLSL_LOG, b.t_f32, u1)))
    bm_th = b.op(OP_FMUL, b.t_f32, b.f32(6.28318530718), u2)
    z_shot = b.op(OP_FMUL, b.t_f32, bm_rad, b.glsl_op(GLSL_COS, b.t_f32, bm_th))
    z_read = b.op(OP_FMUL, b.t_f32, bm_rad, b.glsl_op(GLSL_SIN, b.t_f32, bm_th))

    shot_e = b.glsl_op(
        GLSL_FMAX,
        b.t_f32,
        b.op(OP_FADD, b.t_f32, expected_e, b.op(OP_FMUL, b.t_f32, z_shot, b.glsl_op(GLSL_SQRT, b.t_f32, b.glsl_op(GLSL_FMAX, b.t_f32, expected_e, b.f32(0.0))))),
        b.f32(0.0),
    )
    clamped_e = b.glsl_op(GLSL_FMIN, b.t_f32, shot_e, full_well)
    read_e = b.op(
        OP_FMUL,
        b.t_f32,
        rn_base,
        b.op(OP_FADD, b.t_f32, b.f32(1.0), b.op(OP_FMUL, b.t_f32, rn_slope, b.glsl_op(GLSL_FMAX, b.t_f32, b.op(OP_FSUB, b.t_f32, iso_gain, b.f32(1.0)), b.f32(0.0)))),
    )
    adu_n = b.op(
        OP_FADD,
        b.t_f32,
        b.op(OP_FADD, b.t_f32, black_f, b.op(OP_FMUL, b.t_f32, clamped_e, e_to_adu)),
        b.op(OP_FMUL, b.t_f32, b.op(OP_FMUL, b.t_f32, z_read, read_e), e_to_adu),
    )
    b.void_op(OP_STORE, v_adu, adu_n)
    b.void_op(OP_BRANCH, lbl_quant)

    b.label(lbl_quant)
    adu_val = b.op(OP_LOAD, b.t_f32, v_adu)
    max_f = b.op(OP_CONVERT_UTOF, b.t_f32, max_code)
    q_f = b.glsl_op(GLSL_FCLAMP, b.t_f32, b.glsl_op(GLSL_FLOOR, b.t_f32, b.op(OP_FADD, b.t_f32, adu_val, b.f32(0.5))), b.f32(0.0), max_f)
    q_u = b.op(OP_CONVERT_FTOU, b.t_u32, q_f)
    # Internal fused ISP packing modes, applied after native ADC quantization.
    mode = b.load_ubo(ubo, 3, False)
    is8 = b.op(OP_IEQUAL, b.t_bool, mode, b.u32(0))
    is16 = b.op(OP_IEQUAL, b.t_bool, mode, b.u32(1))
    expanded = b.op(OP_UDIV, b.t_u32, b.op(OP_IMUL, b.t_u32, q_u, b.u32(4095)), b.u32(255))
    truncated = b.op(OP_SHIFT_RIGHT_LOGICAL, b.t_u32, q_u, b.u32(4))
    packed = b.op(OP_SELECT, b.t_u32, is16, truncated, q_u)
    b.store_ssbo(out_buf, p_out_u32, idx, b.op(OP_SELECT, b.t_u32, is8, expanded, packed))
    b.void_op(OP_BRANCH, lbl_done)

    b.label(lbl_done)
    return b.finish()


def build_optical_filter_stack_spirv() -> list[int]:
    """
    Pass 0: Optical Filter Stack (ND, Graduated ND, Black Mist halation, Star Cross-Screen 4/6/8-pt,
    and UV Protective Filter softness/ghosting/veiling-glare/glass-tint).
    Preserves depth channel (.w) untouched for downstream optics_warp + pupil_bokeh.
    """
    b = SpirvBuilder()
    in_buf, p_in_v4 = b.ssbo_array(b.t_vec4, 16, 0)
    out_buf, p_out_v4 = b.ssbo_array(b.t_vec4, 16, 1)
    # 28 x 32-bit = 112 bytes std140 UBO matching FujiVkFilterStackParams
    ubo_types = [
        b.t_u32, b.t_u32, b.t_u32, b.t_u32,  # 0..3: width, height, enableFlags, starPoints
        b.t_f32, b.t_f32, b.t_f32, b.t_f32,  # 4..7: ndTransmission, gndTopTransmission, gndHorizonY, gndFeather
        b.t_f32, b.t_f32, b.t_f32, b.t_f32,  # 8..11: gndSinAngle, gndCosAngle, mistStrength, mistThreshold
        b.t_f32, b.t_f32, b.t_f32, b.t_f32,  # 12..15: mistRadiusPx, starStrength, starThreshold, starRadiusPx
        b.t_f32, b.t_f32, b.t_f32, b.t_f32,  # 16..19: starAngleRad, mistWarmth, uvVeilingGlare, uvGhostIntensity
        b.t_f32, b.t_f32, b.t_f32, b.t_f32,  # 20..23: uvSoftness, uvTintR, uvTintG, uvTintB
        b.t_f32, b.t_u32, b.t_u32, b.t_u32,  # 24..27: uvGhostRadiusPx, reserved0, reserved1, reserved2
    ]
    ubo = b.uniform_struct(ubo_types, 2)

    p_gx = b.op(OP_ACCESS_CHAIN, b.p_in_u32, b.var_gid, b.u32(0))
    gx = b.op(OP_LOAD, b.t_u32, p_gx)
    p_gy = b.op(OP_ACCESS_CHAIN, b.p_in_u32, b.var_gid, b.u32(1))
    gy = b.op(OP_LOAD, b.t_u32, p_gy)

    width = b.load_ubo(ubo, 0, False)
    height = b.load_ubo(ubo, 1, False)
    enable_flags = b.load_ubo(ubo, 2, False)
    star_points = b.load_ubo(ubo, 3, False)

    in_bounds = b.op(
        OP_LOGICAL_AND,
        b.t_bool,
        b.op(OP_ULESS_THAN, b.t_bool, gx, width),
        b.op(OP_ULESS_THAN, b.t_bool, gy, height),
    )
    lbl_body = b.id()
    lbl_done = b.id()
    b.void_op(OP_SELECTION_MERGE, lbl_done, 0)
    b.void_op(OP_BRANCH_CONDITIONAL, in_bounds, lbl_body, lbl_done)

    b.label(lbl_body)
    idx = b.op(OP_IADD, b.t_u32, b.op(OP_IMUL, b.t_u32, gy, width), gx)
    base = b.load_ssbo(in_buf, p_in_v4, b.t_vec4, idx)
    base_r = b.op(OP_COMPOSITE_EXTRACT, b.t_f32, base, 0)
    base_g = b.op(OP_COMPOSITE_EXTRACT, b.t_f32, base, 1)
    base_b = b.op(OP_COMPOSITE_EXTRACT, b.t_f32, base, 2)
    base_w = b.op(OP_COMPOSITE_EXTRACT, b.t_f32, base, 3)

    v_r = b.local_var(b.p_fn_f32)
    v_g = b.local_var(b.p_fn_f32)
    v_b = b.local_var(b.p_fn_f32)
    b.void_op(OP_STORE, v_r, base_r)
    b.void_op(OP_STORE, v_g, base_g)
    b.void_op(OP_STORE, v_b, base_b)

    w_i32 = b.op(OP_BITCAST, b.t_i32, width)
    h_i32 = b.op(OP_BITCAST, b.t_i32, height)
    max_x_i = b.op(OP_ISUB, b.t_i32, w_i32, b.i32(1))
    max_y_i = b.op(OP_ISUB, b.t_i32, h_i32, b.i32(1))
    fx = b.op(OP_CONVERT_UTOF, b.t_f32, gx)
    fy = b.op(OP_CONVERT_UTOF, b.t_f32, gy)

    def sample_rgb_clamped(sx_f, sy_f):
        sx_i = b.op(OP_CONVERT_FTOS, b.t_i32, b.glsl_op(GLSL_FLOOR, b.t_f32, b.op(OP_FADD, b.t_f32, sx_f, b.f32(0.5))))
        sy_i = b.op(OP_CONVERT_FTOS, b.t_i32, b.glsl_op(GLSL_FLOOR, b.t_f32, b.op(OP_FADD, b.t_f32, sy_f, b.f32(0.5))))
        cx_i = b.glsl_op(GLSL_SCLAMP, b.t_i32, sx_i, b.i32(0), max_x_i)
        cy_i = b.glsl_op(GLSL_SCLAMP, b.t_i32, sy_i, b.i32(0), max_y_i)
        cx_u = b.op(OP_BITCAST, b.t_u32, cx_i)
        cy_u = b.op(OP_BITCAST, b.t_u32, cy_i)
        sidx = b.op(OP_IADD, b.t_u32, b.op(OP_IMUL, b.t_u32, cy_u, width), cx_u)
        sv = b.load_ssbo(in_buf, p_in_v4, b.t_vec4, sidx)
        sr = b.op(OP_COMPOSITE_EXTRACT, b.t_f32, sv, 0)
        sg = b.op(OP_COMPOSITE_EXTRACT, b.t_f32, sv, 1)
        sb = b.op(OP_COMPOSITE_EXTRACT, b.t_f32, sv, 2)
        return sr, sg, sb

    def excess_scale(sr, sg, sb, thresh):
        luma = b.op(
            OP_FADD,
            b.t_f32,
            b.op(OP_FADD, b.t_f32, b.op(OP_FMUL, b.t_f32, b.f32(0.2126), sr), b.op(OP_FMUL, b.t_f32, b.f32(0.7152), sg)),
            b.op(OP_FMUL, b.t_f32, b.f32(0.0722), sb),
        )
        ex = b.glsl_op(GLSL_FMAX, b.t_f32, b.op(OP_FSUB, b.t_f32, luma, thresh), b.f32(0.0))
        denom = b.glsl_op(GLSL_FMAX, b.t_f32, luma, b.f32(1e-4))
        return b.op(OP_FDIV, b.t_f32, ex, denom)

    # --- 0. UV Protective Filter (bit 4 = 0x10): softness, point-symmetric ghosting, veiling glare, glass tint ---
    has_uv = b.op(OP_INOT_EQUAL, b.t_bool, b.op(OP_BITWISE_AND, b.t_u32, enable_flags, b.u32(16)), b.u32(0))
    lbl_uv = b.id()
    lbl_after_uv = b.id()
    b.void_op(OP_SELECTION_MERGE, lbl_after_uv, 0)
    b.void_op(OP_BRANCH_CONDITIONAL, has_uv, lbl_uv, lbl_after_uv)

    b.label(lbl_uv)
    uv_glare = b.load_ubo(ubo, 18, True)
    uv_ghost = b.load_ubo(ubo, 19, True)
    uv_soft = b.load_ubo(ubo, 20, True)
    uv_tint_r = b.load_ubo(ubo, 21, True)
    uv_tint_g = b.load_ubo(ubo, 22, True)
    uv_tint_b = b.load_ubo(ubo, 23, True)
    uv_ghost_rad = b.load_ubo(ubo, 24, True)

    # 0A. Micro-softness (9-tap neighborhood blend matching CPU flat-glass aberration blur)
    do_soft = b.op(OP_FORD_GREATER_THAN, b.t_bool, uv_soft, b.f32(1e-4))
    lbl_uv_soft = b.id()
    lbl_after_uv_soft = b.id()
    b.void_op(OP_SELECTION_MERGE, lbl_after_uv_soft, 0)
    b.void_op(OP_BRANCH_CONDITIONAL, do_soft, lbl_uv_soft, lbl_after_uv_soft)

    b.label(lbl_uv_soft)
    r_soft = b.op(OP_SELECT, b.t_f32, b.op(OP_FORD_GREATER_THAN_EQUAL, b.t_bool, uv_soft, b.f32(0.25)), b.f32(2.0), b.f32(1.0))
    s_acc_r = b.op(OP_LOAD, b.t_f32, v_r)
    s_acc_g = b.op(OP_LOAD, b.t_f32, v_g)
    s_acc_b = b.op(OP_LOAD, b.t_f32, v_b)
    for (ox_s, oy_s) in [(-1.0, 0.0), (1.0, 0.0), (0.0, -1.0), (0.0, 1.0), (-1.0, -1.0), (1.0, -1.0), (-1.0, 1.0), (1.0, 1.0)]:
        sx_t = fx if ox_s == 0.0 else b.op(OP_FADD, b.t_f32, fx, b.op(OP_FMUL, b.t_f32, r_soft, b.f32(ox_s)))
        sy_t = fy if oy_s == 0.0 else b.op(OP_FADD, b.t_f32, fy, b.op(OP_FMUL, b.t_f32, r_soft, b.f32(oy_s)))
        tr, tg, tb = sample_rgb_clamped(sx_t, sy_t)
        s_acc_r = b.op(OP_FADD, b.t_f32, s_acc_r, tr)
        s_acc_g = b.op(OP_FADD, b.t_f32, s_acc_g, tg)
        s_acc_b = b.op(OP_FADD, b.t_f32, s_acc_b, tb)
    soft_cl = b.glsl_op(GLSL_FCLAMP, b.t_f32, uv_soft, b.f32(0.0), b.f32(1.0))
    inv_9 = b.f32(1.0 / 9.0)
    b.void_op(OP_STORE, v_r, b.glsl_op(GLSL_FMIX, b.t_f32, b.op(OP_LOAD, b.t_f32, v_r), b.op(OP_FMUL, b.t_f32, s_acc_r, inv_9), soft_cl))
    b.void_op(OP_STORE, v_g, b.glsl_op(GLSL_FMIX, b.t_f32, b.op(OP_LOAD, b.t_f32, v_g), b.op(OP_FMUL, b.t_f32, s_acc_g, inv_9), soft_cl))
    b.void_op(OP_STORE, v_b, b.glsl_op(GLSL_FMIX, b.t_f32, b.op(OP_LOAD, b.t_f32, v_b), b.op(OP_FMUL, b.t_f32, s_acc_b, inv_9), soft_cl))
    b.void_op(OP_BRANCH, lbl_after_uv_soft)

    b.label(lbl_after_uv_soft)

    # 0B. Point-symmetric internal double-reflection ghosting across optical center (w-1-x, h-1-y)
    do_ghost = b.op(OP_FORD_GREATER_THAN, b.t_bool, uv_ghost, b.f32(1e-4))
    lbl_uv_ghost = b.id()
    lbl_after_uv_ghost = b.id()
    b.void_op(OP_SELECTION_MERGE, lbl_after_uv_ghost, 0)
    b.void_op(OP_BRANCH_CONDITIONAL, do_ghost, lbl_uv_ghost, lbl_after_uv_ghost)

    b.label(lbl_uv_ghost)
    max_xf = b.op(OP_CONVERT_STOF, b.t_f32, max_x_i)
    max_yf = b.op(OP_CONVERT_STOF, b.t_f32, max_y_i)
    refl_x = b.op(OP_FSUB, b.t_f32, max_xf, fx)
    refl_y = b.op(OP_FSUB, b.t_f32, max_yf, fy)
    gr_cl = b.glsl_op(GLSL_FCLAMP, b.t_f32, uv_ghost_rad, b.f32(2.0), b.f32(16.0))
    gr_a = b.op(OP_FMUL, b.t_f32, gr_cl, b.f32(0.5))
    gr_d = b.op(OP_FMUL, b.t_f32, gr_cl, b.f32(0.75))
    gh_thr = b.f32(0.45)

    g_acc_r = b.f32(0.0)
    g_acc_g = b.f32(0.0)
    g_acc_b = b.f32(0.0)
    ghost_taps = [
        (0.0, 0.0, False),
        (-1.0, 0.0, False), (1.0, 0.0, False), (0.0, -1.0, False), (0.0, 1.0, False),
        (-1.0, -1.0, True), (1.0, -1.0, True), (-1.0, 1.0, True), (1.0, 1.0, True),
    ]
    for (gx_o, gy_o, is_diag) in ghost_taps:
        rad_t = gr_d if is_diag else gr_a
        tx = refl_x if gx_o == 0.0 else b.op(OP_FADD, b.t_f32, refl_x, b.op(OP_FMUL, b.t_f32, rad_t, b.f32(gx_o)))
        ty = refl_y if gy_o == 0.0 else b.op(OP_FADD, b.t_f32, refl_y, b.op(OP_FMUL, b.t_f32, rad_t, b.f32(gy_o)))
        sr_g, sg_g, sb_g = sample_rgb_clamped(tx, ty)
        gex = excess_scale(sr_g, sg_g, sb_g, gh_thr)
        g_acc_r = b.op(OP_FADD, b.t_f32, g_acc_r, b.op(OP_FMUL, b.t_f32, sr_g, gex))
        g_acc_g = b.op(OP_FADD, b.t_f32, g_acc_g, b.op(OP_FMUL, b.t_f32, sg_g, gex))
        g_acc_b = b.op(OP_FADD, b.t_f32, g_acc_b, b.op(OP_FMUL, b.t_f32, sb_g, gex))
    g_gain = b.op(OP_FMUL, b.t_f32, uv_ghost, b.f32(2.4 / 9.0))
    b.void_op(OP_STORE, v_r, b.op(OP_FADD, b.t_f32, b.op(OP_LOAD, b.t_f32, v_r), b.op(OP_FMUL, b.t_f32, g_acc_r, b.op(OP_FMUL, b.t_f32, g_gain, b.f32(0.90)))))
    b.void_op(OP_STORE, v_g, b.op(OP_FADD, b.t_f32, b.op(OP_LOAD, b.t_f32, v_g), b.op(OP_FMUL, b.t_f32, g_acc_g, b.op(OP_FMUL, b.t_f32, g_gain, b.f32(1.24)))))
    b.void_op(OP_STORE, v_b, b.op(OP_FADD, b.t_f32, b.op(OP_LOAD, b.t_f32, v_b), b.op(OP_FMUL, b.t_f32, g_acc_b, b.op(OP_FMUL, b.t_f32, g_gain, b.f32(1.06)))))
    b.void_op(OP_BRANCH, lbl_after_uv_ghost)

    b.label(lbl_after_uv_ghost)

    # 0C. Veiling glare contrast washout (9-point frame mean stray light lift)
    do_glare = b.op(OP_FORD_GREATER_THAN, b.t_bool, uv_glare, b.f32(1e-4))
    lbl_uv_glare = b.id()
    lbl_after_uv_glare = b.id()
    b.void_op(OP_SELECTION_MERGE, lbl_after_uv_glare, 0)
    b.void_op(OP_BRANCH_CONDITIONAL, do_glare, lbl_uv_glare, lbl_after_uv_glare)

    b.label(lbl_uv_glare)
    max_xf_g = b.op(OP_CONVERT_STOF, b.t_f32, max_x_i)
    max_yf_g = b.op(OP_CONVERT_STOF, b.t_f32, max_y_i)
    m_acc_r = b.f32(0.0)
    m_acc_g = b.f32(0.0)
    m_acc_b = b.f32(0.0)
    for fv in (0.15, 0.50, 0.85):
        my_f = b.op(OP_FMUL, b.t_f32, max_yf_g, b.f32(fv))
        for fu in (0.15, 0.50, 0.85):
            mx_f = b.op(OP_FMUL, b.t_f32, max_xf_g, b.f32(fu))
            mr_s, mg_s, mb_s = sample_rgb_clamped(mx_f, my_f)
            m_acc_r = b.op(OP_FADD, b.t_f32, m_acc_r, mr_s)
            m_acc_g = b.op(OP_FADD, b.t_f32, m_acc_g, mg_s)
            m_acc_b = b.op(OP_FADD, b.t_f32, m_acc_b, mb_s)
    c_keep = b.glsl_op(GLSL_FMAX, b.t_f32, b.op(OP_FSUB, b.t_f32, b.f32(1.0), b.op(OP_FMUL, b.t_f32, b.f32(0.28), uv_glare)), b.f32(0.55))
    v_scale = b.op(OP_FMUL, b.t_f32, uv_glare, b.f32(0.50 / 9.0))
    b.void_op(OP_STORE, v_r, b.op(OP_FADD, b.t_f32, b.op(OP_FMUL, b.t_f32, b.op(OP_LOAD, b.t_f32, v_r), c_keep), b.op(OP_FMUL, b.t_f32, m_acc_r, v_scale)))
    b.void_op(OP_STORE, v_g, b.op(OP_FADD, b.t_f32, b.op(OP_FMUL, b.t_f32, b.op(OP_LOAD, b.t_f32, v_g), c_keep), b.op(OP_FMUL, b.t_f32, m_acc_g, v_scale)))
    b.void_op(OP_STORE, v_b, b.op(OP_FADD, b.t_f32, b.op(OP_FMUL, b.t_f32, b.op(OP_LOAD, b.t_f32, v_b), c_keep), b.op(OP_FMUL, b.t_f32, m_acc_b, v_scale)))
    b.void_op(OP_BRANCH, lbl_after_uv_glare)

    b.label(lbl_after_uv_glare)

    # 0D. Soda-lime glass chromatic tint multipliers (luminance-normalized by host)
    b.void_op(OP_STORE, v_r, b.op(OP_FMUL, b.t_f32, b.op(OP_LOAD, b.t_f32, v_r), uv_tint_r))
    b.void_op(OP_STORE, v_g, b.op(OP_FMUL, b.t_f32, b.op(OP_LOAD, b.t_f32, v_g), uv_tint_g))
    b.void_op(OP_STORE, v_b, b.op(OP_FMUL, b.t_f32, b.op(OP_LOAD, b.t_f32, v_b), uv_tint_b))
    b.void_op(OP_BRANCH, lbl_after_uv)

    b.label(lbl_after_uv)

    # --- 1. Black Mist halation / bloom (bit 2 = 0x4) ---
    mist_str = b.load_ubo(ubo, 10, True)
    mist_thr = b.load_ubo(ubo, 11, True)
    mist_rad = b.load_ubo(ubo, 12, True)
    mist_warm = b.load_ubo(ubo, 17, True)

    has_mist = b.op(
        OP_LOGICAL_AND,
        b.t_bool,
        b.op(OP_INOT_EQUAL, b.t_bool, b.op(OP_BITWISE_AND, b.t_u32, enable_flags, b.u32(4)), b.u32(0)),
        b.op(
            OP_LOGICAL_AND,
            b.t_bool,
            b.op(OP_FORD_GREATER_THAN, b.t_bool, mist_str, b.f32(0.0)),
            b.op(OP_FORD_GREATER_THAN_EQUAL, b.t_bool, mist_rad, b.f32(0.5)),
        ),
    )
    lbl_mist = b.id()
    lbl_after_mist = b.id()
    b.void_op(OP_SELECTION_MERGE, lbl_after_mist, 0)
    b.void_op(OP_BRANCH_CONDITIONAL, has_mist, lbl_mist, lbl_after_mist)

    b.label(lbl_mist)
    vm_r = b.local_var(b.p_fn_f32)
    vm_g = b.local_var(b.p_fn_f32)
    vm_b = b.local_var(b.p_fn_f32)
    vm_w = b.local_var(b.p_fn_f32)
    vm_k = b.local_var(b.p_fn_i32)
    b.void_op(OP_STORE, vm_r, b.f32(0.0))
    b.void_op(OP_STORE, vm_g, b.f32(0.0))
    b.void_op(OP_STORE, vm_b, b.f32(0.0))
    b.void_op(OP_STORE, vm_w, b.f32(0.0))
    b.void_op(OP_STORE, vm_k, b.i32(0))
    r_inner = b.op(OP_FMUL, b.t_f32, mist_rad, b.f32(0.45))

    lbl_m_hdr = b.id()
    lbl_m_body = b.id()
    lbl_m_cont = b.id()
    lbl_m_end = b.id()
    b.void_op(OP_BRANCH, lbl_m_hdr)
    b.label(lbl_m_hdr)
    mk = b.op(OP_LOAD, b.t_i32, vm_k)
    m_cond = b.op(OP_SLESS_THAN, b.t_bool, mk, b.i32(8))
    b.void_op(OP_LOOP_MERGE, lbl_m_end, lbl_m_cont, 0)
    b.void_op(OP_BRANCH_CONDITIONAL, m_cond, lbl_m_body, lbl_m_end)

    b.label(lbl_m_body)
    ang = b.op(OP_FMUL, b.t_f32, b.op(OP_CONVERT_STOF, b.t_f32, mk), b.f32(0.785398163397))
    ca = b.glsl_op(GLSL_COS, b.t_f32, ang)
    sa = b.glsl_op(GLSL_SIN, b.t_f32, ang)

    r1, g1, b1 = sample_rgb_clamped(
        b.op(OP_FADD, b.t_f32, fx, b.op(OP_FMUL, b.t_f32, ca, r_inner)),
        b.op(OP_FADD, b.t_f32, fy, b.op(OP_FMUL, b.t_f32, sa, r_inner)),
    )
    sc1 = b.op(OP_FMUL, b.t_f32, excess_scale(r1, g1, b1, mist_thr), b.f32(0.65))

    r2, g2, b2 = sample_rgb_clamped(
        b.op(OP_FADD, b.t_f32, fx, b.op(OP_FMUL, b.t_f32, ca, mist_rad)),
        b.op(OP_FADD, b.t_f32, fy, b.op(OP_FMUL, b.t_f32, sa, mist_rad)),
    )
    sc2 = b.op(OP_FMUL, b.t_f32, excess_scale(r2, g2, b2, mist_thr), b.f32(0.35))

    b.void_op(OP_STORE, vm_r, b.op(OP_FADD, b.t_f32, b.op(OP_LOAD, b.t_f32, vm_r), b.op(OP_FADD, b.t_f32, b.op(OP_FMUL, b.t_f32, r1, sc1), b.op(OP_FMUL, b.t_f32, r2, sc2))))
    b.void_op(OP_STORE, vm_g, b.op(OP_FADD, b.t_f32, b.op(OP_LOAD, b.t_f32, vm_g), b.op(OP_FADD, b.t_f32, b.op(OP_FMUL, b.t_f32, g1, sc1), b.op(OP_FMUL, b.t_f32, g2, sc2))))
    b.void_op(OP_STORE, vm_b, b.op(OP_FADD, b.t_f32, b.op(OP_LOAD, b.t_f32, vm_b), b.op(OP_FADD, b.t_f32, b.op(OP_FMUL, b.t_f32, b1, sc1), b.op(OP_FMUL, b.t_f32, b2, sc2))))
    b.void_op(OP_STORE, vm_w, b.op(OP_FADD, b.t_f32, b.op(OP_LOAD, b.t_f32, vm_w), b.f32(1.0)))
    b.void_op(OP_BRANCH, lbl_m_cont)

    b.label(lbl_m_cont)
    b.void_op(OP_STORE, vm_k, b.op(OP_IADD, b.t_i32, mk, b.i32(1)))
    b.void_op(OP_BRANCH, lbl_m_hdr)

    b.label(lbl_m_end)
    mw = b.glsl_op(GLSL_FMAX, b.t_f32, b.op(OP_LOAD, b.t_f32, vm_w), b.f32(1.0))
    ms_scale = b.op(OP_FDIV, b.t_f32, mist_str, mw)
    tint_r = b.op(OP_FADD, b.t_f32, b.f32(1.0), mist_warm)
    tint_b = b.glsl_op(GLSL_FMAX, b.t_f32, b.op(OP_FSUB, b.t_f32, b.f32(1.0), b.op(OP_FMUL, b.t_f32, mist_warm, b.f32(0.5))), b.f32(0.0))
    b.void_op(OP_STORE, v_r, b.op(OP_FADD, b.t_f32, b.op(OP_LOAD, b.t_f32, v_r), b.op(OP_FMUL, b.t_f32, b.op(OP_FMUL, b.t_f32, b.op(OP_LOAD, b.t_f32, vm_r), ms_scale), tint_r)))
    b.void_op(OP_STORE, v_g, b.op(OP_FADD, b.t_f32, b.op(OP_LOAD, b.t_f32, v_g), b.op(OP_FMUL, b.t_f32, b.op(OP_LOAD, b.t_f32, vm_g), ms_scale)))
    b.void_op(OP_STORE, v_b, b.op(OP_FADD, b.t_f32, b.op(OP_LOAD, b.t_f32, v_b), b.op(OP_FMUL, b.t_f32, b.op(OP_FMUL, b.t_f32, b.op(OP_LOAD, b.t_f32, vm_b), ms_scale), tint_b)))
    b.void_op(OP_BRANCH, lbl_after_mist)

    b.label(lbl_after_mist)

    # --- 2. Star Cross-Screen 4/6/8-point diffraction spikes (bit 3 = 0x8) ---
    star_str = b.load_ubo(ubo, 13, True)
    star_thr = b.load_ubo(ubo, 14, True)
    star_rad = b.load_ubo(ubo, 15, True)
    star_ang = b.load_ubo(ubo, 16, True)

    has_star = b.op(
        OP_LOGICAL_AND,
        b.t_bool,
        b.op(OP_INOT_EQUAL, b.t_bool, b.op(OP_BITWISE_AND, b.t_u32, enable_flags, b.u32(8)), b.u32(0)),
        b.op(
            OP_LOGICAL_AND,
            b.t_bool,
            b.op(OP_FORD_GREATER_THAN, b.t_bool, star_str, b.f32(0.0)),
            b.op(OP_FORD_GREATER_THAN_EQUAL, b.t_bool, star_rad, b.f32(1.0)),
        ),
    )
    lbl_star = b.id()
    lbl_after_star = b.id()
    b.void_op(OP_SELECTION_MERGE, lbl_after_star, 0)
    b.void_op(OP_BRANCH_CONDITIONAL, has_star, lbl_star, lbl_after_star)

    b.label(lbl_star)
    axes_u = b.glsl_op(GLSL_UCLAMP, b.t_u32, b.op(OP_SHIFT_RIGHT_LOGICAL, b.t_u32, star_points, b.u32(1)), b.u32(2), b.u32(4))
    step_ang = b.op(OP_FDIV, b.t_f32, b.f32(3.14159265359), b.op(OP_CONVERT_UTOF, b.t_f32, axes_u))

    vs_r = b.local_var(b.p_fn_f32)
    vs_g = b.local_var(b.p_fn_f32)
    vs_b = b.local_var(b.p_fn_f32)
    vs_w = b.local_var(b.p_fn_f32)
    vs_a = b.local_var(b.p_fn_u32)
    vs_st = b.local_var(b.p_fn_i32)
    b.void_op(OP_STORE, vs_r, b.f32(0.0))
    b.void_op(OP_STORE, vs_g, b.f32(0.0))
    b.void_op(OP_STORE, vs_b, b.f32(0.0))
    b.void_op(OP_STORE, vs_w, b.f32(0.0))
    b.void_op(OP_STORE, vs_a, b.u32(0))

    lbl_sa_hdr = b.id()
    lbl_sa_body = b.id()
    lbl_sa_cont = b.id()
    lbl_sa_end = b.id()
    b.void_op(OP_BRANCH, lbl_sa_hdr)
    b.label(lbl_sa_hdr)
    sa_i = b.op(OP_LOAD, b.t_u32, vs_a)
    sa_cond = b.op(OP_ULESS_THAN, b.t_bool, sa_i, axes_u)
    b.void_op(OP_LOOP_MERGE, lbl_sa_end, lbl_sa_cont, 0)
    b.void_op(OP_BRANCH_CONDITIONAL, sa_cond, lbl_sa_body, lbl_sa_end)

    b.label(lbl_sa_body)
    a_ang = b.op(OP_FADD, b.t_f32, star_ang, b.op(OP_FMUL, b.t_f32, b.op(OP_CONVERT_UTOF, b.t_f32, sa_i), step_ang))
    dx = b.glsl_op(GLSL_COS, b.t_f32, a_ang)
    dy = b.glsl_op(GLSL_SIN, b.t_f32, a_ang)
    b.void_op(OP_STORE, vs_st, b.i32(1))

    lbl_ss_hdr = b.id()
    lbl_ss_body = b.id()
    lbl_ss_cont = b.id()
    lbl_ss_end = b.id()
    b.void_op(OP_BRANCH, lbl_ss_hdr)
    b.label(lbl_ss_hdr)
    ss_i = b.op(OP_LOAD, b.t_i32, vs_st)
    ss_cond = b.op(OP_SLESS_THAN_EQUAL, b.t_bool, ss_i, b.i32(8))
    b.void_op(OP_LOOP_MERGE, lbl_ss_end, lbl_ss_cont, 0)
    b.void_op(OP_BRANCH_CONDITIONAL, ss_cond, lbl_ss_body, lbl_ss_end)

    b.label(lbl_ss_body)
    frac = b.op(OP_FMUL, b.t_f32, b.op(OP_CONVERT_STOF, b.t_f32, ss_i), b.f32(0.125))
    dist = b.op(OP_FMUL, b.t_f32, frac, star_rad)
    fall = b.glsl_op(GLSL_EXP, b.t_f32, b.op(OP_FMUL, b.t_f32, b.f32(-2.2), frac))
    ox = b.op(OP_FMUL, b.t_f32, dx, dist)
    oy = b.op(OP_FMUL, b.t_f32, dy, dist)

    rp, gp, bp = sample_rgb_clamped(b.op(OP_FADD, b.t_f32, fx, ox), b.op(OP_FADD, b.t_f32, fy, oy))
    ep = b.op(OP_FMUL, b.t_f32, excess_scale(rp, gp, bp, star_thr), fall)
    rn, gn, bn = sample_rgb_clamped(b.op(OP_FSUB, b.t_f32, fx, ox), b.op(OP_FSUB, b.t_f32, fy, oy))
    en = b.op(OP_FMUL, b.t_f32, excess_scale(rn, gn, bn, star_thr), fall)

    b.void_op(OP_STORE, vs_r, b.op(OP_FADD, b.t_f32, b.op(OP_LOAD, b.t_f32, vs_r), b.op(OP_FADD, b.t_f32, b.op(OP_FMUL, b.t_f32, rp, ep), b.op(OP_FMUL, b.t_f32, rn, en))))
    b.void_op(OP_STORE, vs_g, b.op(OP_FADD, b.t_f32, b.op(OP_LOAD, b.t_f32, vs_g), b.op(OP_FADD, b.t_f32, b.op(OP_FMUL, b.t_f32, gp, ep), b.op(OP_FMUL, b.t_f32, gn, en))))
    b.void_op(OP_STORE, vs_b, b.op(OP_FADD, b.t_f32, b.op(OP_LOAD, b.t_f32, vs_b), b.op(OP_FADD, b.t_f32, b.op(OP_FMUL, b.t_f32, bp, ep), b.op(OP_FMUL, b.t_f32, bn, en))))
    b.void_op(OP_STORE, vs_w, b.op(OP_FADD, b.t_f32, b.op(OP_LOAD, b.t_f32, vs_w), b.op(OP_FMUL, b.t_f32, b.f32(2.0), fall)))
    b.void_op(OP_BRANCH, lbl_ss_cont)

    b.label(lbl_ss_cont)
    b.void_op(OP_STORE, vs_st, b.op(OP_IADD, b.t_i32, ss_i, b.i32(1)))
    b.void_op(OP_BRANCH, lbl_ss_hdr)

    b.label(lbl_ss_end)
    b.void_op(OP_BRANCH, lbl_sa_cont)

    b.label(lbl_sa_cont)
    b.void_op(OP_STORE, vs_a, b.op(OP_IADD, b.t_u32, sa_i, b.u32(1)))
    b.void_op(OP_BRANCH, lbl_sa_hdr)

    b.label(lbl_sa_end)
    sw = b.glsl_op(GLSL_FMAX, b.t_f32, b.op(OP_LOAD, b.t_f32, vs_w), b.f32(1.0))
    st_scale = b.op(OP_FDIV, b.t_f32, star_str, sw)
    b.void_op(OP_STORE, v_r, b.op(OP_FADD, b.t_f32, b.op(OP_LOAD, b.t_f32, v_r), b.op(OP_FMUL, b.t_f32, b.op(OP_LOAD, b.t_f32, vs_r), st_scale)))
    b.void_op(OP_STORE, v_g, b.op(OP_FADD, b.t_f32, b.op(OP_LOAD, b.t_f32, v_g), b.op(OP_FMUL, b.t_f32, b.op(OP_LOAD, b.t_f32, vs_g), st_scale)))
    b.void_op(OP_STORE, v_b, b.op(OP_FADD, b.t_f32, b.op(OP_LOAD, b.t_f32, v_b), b.op(OP_FMUL, b.t_f32, b.op(OP_LOAD, b.t_f32, vs_b), st_scale)))
    b.void_op(OP_BRANCH, lbl_after_star)

    b.label(lbl_after_star)

    # --- 3. Scalar ND attenuation (bit 0 = 0x1) ---
    has_nd = b.op(OP_INOT_EQUAL, b.t_bool, b.op(OP_BITWISE_AND, b.t_u32, enable_flags, b.u32(1)), b.u32(0))
    nd_t = b.glsl_op(GLSL_FCLAMP, b.t_f32, b.load_ubo(ubo, 4, True), b.f32(0.0), b.f32(1.0))
    nd_mul = b.op(OP_SELECT, b.t_f32, has_nd, nd_t, b.f32(1.0))

    # --- 4. Graduated ND (GND) smoothstep attenuation (bit 1 = 0x2) ---
    has_gnd = b.op(OP_INOT_EQUAL, b.t_bool, b.op(OP_BITWISE_AND, b.t_u32, enable_flags, b.u32(2)), b.u32(0))
    gnd_top = b.glsl_op(GLSL_FCLAMP, b.t_f32, b.load_ubo(ubo, 5, True), b.f32(0.0), b.f32(1.0))
    gnd_hy = b.load_ubo(ubo, 6, True)
    gnd_fe = b.glsl_op(GLSL_FMAX, b.t_f32, b.load_ubo(ubo, 7, True), b.f32(0.005))
    gnd_sa = b.load_ubo(ubo, 8, True)
    gnd_ca = b.load_ubo(ubo, 9, True)

    nx = b.op(OP_FSUB, b.t_f32, b.op(OP_FDIV, b.t_f32, b.op(OP_FADD, b.t_f32, fx, b.f32(0.5)), b.op(OP_CONVERT_UTOF, b.t_f32, width)), b.f32(0.5))
    ny = b.op(OP_FSUB, b.t_f32, b.op(OP_FDIV, b.t_f32, b.op(OP_FADD, b.t_f32, fy, b.f32(0.5)), b.op(OP_CONVERT_UTOF, b.t_f32, height)), b.f32(0.5))
    proj = b.op(
        OP_FADD,
        b.t_f32,
        b.op(OP_FADD, b.t_f32, b.op(OP_FMUL, b.t_f32, b.op(OP_FNEGATE, b.t_f32, nx), gnd_sa), b.op(OP_FMUL, b.t_f32, ny, gnd_ca)),
        b.f32(0.5),
    )
    edge0 = b.op(OP_FSUB, b.t_f32, gnd_hy, gnd_fe)
    t_lin = b.glsl_op(GLSL_FCLAMP, b.t_f32, b.op(OP_FDIV, b.t_f32, b.op(OP_FSUB, b.t_f32, proj, edge0), b.op(OP_FMUL, b.t_f32, b.f32(2.0), gnd_fe)), b.f32(0.0), b.f32(1.0))
    sm = b.op(OP_FMUL, b.t_f32, b.op(OP_FMUL, b.t_f32, t_lin, t_lin), b.op(OP_FSUB, b.t_f32, b.f32(3.0), b.op(OP_FMUL, b.t_f32, b.f32(2.0), t_lin)))
    gnd_atten = b.glsl_op(GLSL_FMIX, b.t_f32, gnd_top, b.f32(1.0), sm)
    gnd_mul = b.op(OP_SELECT, b.t_f32, has_gnd, gnd_atten, b.f32(1.0))

    tot_mul = b.op(OP_FMUL, b.t_f32, nd_mul, gnd_mul)
    out_r = b.glsl_op(GLSL_FMAX, b.t_f32, b.op(OP_FMUL, b.t_f32, b.op(OP_LOAD, b.t_f32, v_r), tot_mul), b.f32(0.0))
    out_g = b.glsl_op(GLSL_FMAX, b.t_f32, b.op(OP_FMUL, b.t_f32, b.op(OP_LOAD, b.t_f32, v_g), tot_mul), b.f32(0.0))
    out_b = b.glsl_op(GLSL_FMAX, b.t_f32, b.op(OP_FMUL, b.t_f32, b.op(OP_LOAD, b.t_f32, v_b), tot_mul), b.f32(0.0))
    out_v4 = b.op(OP_COMPOSITE_CONSTRUCT, b.t_vec4, out_r, out_g, out_b, base_w)
    b.store_ssbo(out_buf, p_out_v4, idx, out_v4)
    b.void_op(OP_BRANCH, lbl_done)

    b.label(lbl_done)
    return b.finish()


def isp_ubo_types(b: SpirvBuilder) -> list[int]:
    # 40 x 32-bit = 160 bytes matching FujiVkIspDemosaicParams
    return [b.t_u32] * 16 + [b.t_i32] * 18 + [b.t_u32] * 6


def build_isp_demosaic_spirv() -> list[int]:
    """
    Phase 3 Pass 4A: Bit-exact LiveView ISP RGGB Bayer -> Q10 RGB Gain -> 3x3 Q10 CCM ->
    4096-entry 1D Tone LUT -> BT.601 / custom YUV matrix -> canonical UYVY422 uint32 pairs,
    with SPIR-V Specialization Constants (outputFormat, peakingEnabled, is16Bit, modeMask).
    """
    b = SpirvBuilder()
    in_buf, p_in_u32 = b.ssbo_array(b.t_u32, 4, 0)
    out_buf, p_out_u32 = b.ssbo_array(b.t_u32, 4, 1)
    ubo = b.uniform_struct(isp_ubo_types(b), 2)

    spec_out_fmt = b.spec_u32(0, 0xFFFFFFFF)     # 0=UYVY, 1=NV16, 2=NV12, 3=YYUV, 0xFFFFFFFF=UBO
    spec_peaking = b.spec_u32(1, 0xFFFFFFFF)     # 0=off, 1=on, 0xFFFFFFFF=UBO
    spec_is_16bit = b.spec_u32(2, 0xFFFFFFFF)    # 0=8bit, 1=16bit, 0xFFFFFFFF=UBO
    spec_mode_mask = b.spec_u32(3, 0xFFFFFFFF)   # bits[2:0]=hasCcm|hasYuv|mono, 0xFFFFFFFF=UBO

    p_gx = b.op(OP_ACCESS_CHAIN, b.p_in_u32, b.var_gid, b.u32(0))
    px_pair = b.op(OP_LOAD, b.t_u32, p_gx)
    p_gy = b.op(OP_ACCESS_CHAIN, b.p_in_u32, b.var_gid, b.u32(1))
    gy = b.op(OP_LOAD, b.t_u32, p_gy)

    src_w = b.load_ubo(ubo, 0, False)
    src_h = b.load_ubo(ubo, 1, False)
    dst_w = b.load_ubo(ubo, 2, False)
    dst_h = b.load_ubo(ubo, 3, False)
    pairs_w = b.op(OP_SHIFT_RIGHT_LOGICAL, b.t_u32, dst_w, b.u32(1))

    in_bounds = b.op(
        OP_LOGICAL_AND,
        b.t_bool,
        b.op(OP_ULESS_THAN, b.t_bool, px_pair, pairs_w),
        b.op(OP_ULESS_THAN, b.t_bool, gy, dst_h),
    )
    lbl_body = b.id()
    lbl_done = b.id()
    b.void_op(OP_SELECTION_MERGE, lbl_done, 0)
    b.void_op(OP_BRANCH_CONDITIONAL, in_bounds, lbl_body, lbl_done)

    b.label(lbl_body)
    out_idx = b.op(OP_IADD, b.t_u32, b.op(OP_IMUL, b.t_u32, gy, pairs_w), px_pair)
    flags = b.load_ubo(ubo, 7, False)
    off_x = b.load_ubo(ubo, 8, False)
    off_y = b.load_ubo(ubo, 9, False)
    fit_w = b.glsl_op(GLSL_UMAX, b.t_u32, b.load_ubo(ubo, 10, False), b.u32(1))
    fit_h = b.glsl_op(GLSL_UMAX, b.t_u32, b.load_ubo(ubo, 11, False), b.u32(1))
    gain_r = b.load_ubo(ubo, 12, False)
    gain_g = b.load_ubo(ubo, 13, False)
    gain_b = b.load_ubo(ubo, 14, False)
    yuv_shift_u = b.load_ubo(ubo, 15, False)
    yuv_shift = b.op(OP_BITCAST, b.t_i32, yuv_shift_u)
    yuv_half = b.op(OP_SHIFT_LEFT_LOGICAL, b.t_i32, b.i32(1), b.glsl_op(GLSL_SMAX, b.t_i32, b.op(OP_ISUB, b.t_i32, yuv_shift, b.i32(1)), b.i32(0)))

    ccm = [b.load_ubo_i32(ubo, 16 + i) for i in range(9)]
    ym = [b.load_ubo_i32(ubo, 25 + i) for i in range(9)]
    use_spec_mode = b.op(OP_INOT_EQUAL, b.t_bool, spec_mode_mask, b.u32(0xFFFFFFFF))
    eff_flags = b.op(OP_SELECT, b.t_u32, use_spec_mode, spec_mode_mask, flags)
    # Keep spec_out_fmt, spec_peaking, spec_is_16bit live for driver specialization folding
    _spec_meta = b.op(OP_BITWISE_AND, b.t_u32, b.op(OP_BITWISE_OR, b.t_u32, spec_out_fmt, b.op(OP_BITWISE_OR, b.t_u32, spec_peaking, spec_is_16bit)), b.u32(0))
    eff_flags = b.op(OP_BITWISE_OR, b.t_u32, eff_flags, _spec_meta)
    has_ccm = b.op(OP_INOT_EQUAL, b.t_bool, b.op(OP_BITWISE_AND, b.t_u32, eff_flags, b.u32(1)), b.u32(0))
    has_yuv = b.op(OP_INOT_EQUAL, b.t_bool, b.op(OP_BITWISE_AND, b.t_u32, eff_flags, b.u32(2)), b.u32(0))
    mono_mode = b.op(OP_INOT_EQUAL, b.t_bool, b.op(OP_BITWISE_AND, b.t_u32, eff_flags, b.u32(4)), b.u32(0))
    # FLAG_SRC_CROP (bit 4) is a per-dispatch geometry flag: always read it from the runtime UBO flags so a
    # specialized ispModeMask (CCM/YUV/Mono folding) can never mask out still-capture source cropping.
    src_crop_mode = b.op(OP_INOT_EQUAL, b.t_bool, b.op(OP_BITWISE_AND, b.t_u32, flags, b.u32(16)), b.u32(0))

    x0 = b.op(OP_SHIFT_LEFT_LOGICAL, b.t_u32, px_pair, b.u32(1))
    x1 = b.op(OP_IADD, b.t_u32, x0, b.u32(1))
    end_x = b.op(OP_IADD, b.t_u32, off_x, fit_w)
    end_y = b.op(OP_IADD, b.t_u32, off_y, fit_h)

    in_y = b.op(OP_LOGICAL_AND, b.t_bool, b.op(OP_UGREATER_THAN_EQUAL, b.t_bool, gy, off_y), b.op(OP_ULESS_THAN, b.t_bool, gy, end_y))
    in_x0 = b.op(OP_LOGICAL_OR, b.t_bool, src_crop_mode, b.op(OP_LOGICAL_AND, b.t_bool, b.op(OP_UGREATER_THAN_EQUAL, b.t_bool, x0, off_x), b.op(OP_ULESS_THAN, b.t_bool, x0, end_x)))
    in_x1 = b.op(OP_LOGICAL_OR, b.t_bool, src_crop_mode, b.op(OP_LOGICAL_AND, b.t_bool, b.op(OP_UGREATER_THAN_EQUAL, b.t_bool, x1, off_x), b.op(OP_ULESS_THAN, b.t_bool, x1, end_x)))
    any_active = b.op(OP_LOGICAL_OR, b.t_bool, src_crop_mode, b.op(OP_LOGICAL_AND, b.t_bool, in_y, b.op(OP_LOGICAL_OR, b.t_bool, in_x0, in_x1)))

    lbl_active = b.id()
    lbl_black = b.id()
    lbl_after_active = b.id()
    b.void_op(OP_SELECTION_MERGE, lbl_after_active, 0)
    b.void_op(OP_BRANCH_CONDITIONAL, any_active, lbl_active, lbl_black)

    b.label(lbl_black)
    b.store_ssbo(out_buf, p_out_u32, out_idx, b.u32(0x00800080))
    b.void_op(OP_BRANCH, lbl_after_active)

    b.label(lbl_active)
    max_x = b.op(OP_ISUB, b.t_u32, src_w, b.u32(1))
    max_y = b.op(OP_ISUB, b.t_u32, src_h, b.u32(1))
    sy_letter = b.op(OP_UDIV, b.t_u32, b.op(OP_IMUL, b.t_u32, b.op(OP_ISUB, b.t_u32, gy, off_y), src_h), fit_h)
    sy_crop = b.op(OP_IADD, b.t_u32, off_y, b.op(OP_UDIV, b.t_u32, b.op(OP_IMUL, b.t_u32, gy, fit_h), dst_h))
    sy = b.glsl_op(GLSL_UMIN, b.t_u32, b.op(OP_SELECT, b.t_u32, src_crop_mode, sy_crop, sy_letter), max_y)
    y_prev = b.op(OP_SELECT, b.t_u32, b.op(OP_INOT_EQUAL, b.t_bool, sy, b.u32(0)), b.op(OP_ISUB, b.t_u32, sy, b.u32(1)), b.u32(0))
    y_next = b.op(OP_SELECT, b.t_u32, b.op(OP_ULESS_THAN, b.t_bool, sy, max_y), b.op(OP_IADD, b.t_u32, sy, b.u32(1)), max_y)
    sy_odd = b.op(OP_INOT_EQUAL, b.t_bool, b.op(OP_BITWISE_AND, b.t_u32, sy, b.u32(1)), b.u32(0))

    row_curr = b.op(OP_IADD, b.t_u32, b.u32(4096), b.op(OP_IMUL, b.t_u32, sy, src_w))
    row_prev = b.op(OP_IADD, b.t_u32, b.u32(4096), b.op(OP_IMUL, b.t_u32, y_prev, src_w))
    row_next = b.op(OP_IADD, b.t_u32, b.u32(4096), b.op(OP_IMUL, b.t_u32, y_next, src_w))

    def s12(row_off, sx_u):
        return b.load_ssbo(in_buf, p_in_u32, b.t_u32, b.op(OP_IADD, b.t_u32, row_off, sx_u))

    def demosaic_at(x_coord, active_cond):
        sx_letter = b.op(OP_UDIV, b.t_u32, b.op(OP_IMUL, b.t_u32, b.op(OP_SELECT, b.t_u32, active_cond, b.op(OP_ISUB, b.t_u32, x_coord, off_x), b.u32(0)), src_w), fit_w)
        sx_crop = b.op(OP_IADD, b.t_u32, off_x, b.op(OP_UDIV, b.t_u32, b.op(OP_IMUL, b.t_u32, x_coord, fit_w), dst_w))
        sx_raw = b.op(OP_SELECT, b.t_u32, src_crop_mode, sx_crop, sx_letter)
        sx = b.glsl_op(GLSL_UMIN, b.t_u32, sx_raw, max_x)
        x_prev = b.op(OP_SELECT, b.t_u32, b.op(OP_INOT_EQUAL, b.t_bool, sx, b.u32(0)), b.op(OP_ISUB, b.t_u32, sx, b.u32(1)), b.u32(0))
        x_next = b.op(OP_SELECT, b.t_u32, b.op(OP_ULESS_THAN, b.t_bool, sx, max_x), b.op(OP_IADD, b.t_u32, sx, b.u32(1)), max_x)
        sx_odd = b.op(OP_INOT_EQUAL, b.t_bool, b.op(OP_BITWISE_AND, b.t_u32, sx, b.u32(1)), b.u32(0))

        c = s12(row_curr, sx)
        h_avg = b.op(OP_SHIFT_RIGHT_LOGICAL, b.t_u32, b.op(OP_IADD, b.t_u32, b.op(OP_IADD, b.t_u32, s12(row_curr, x_prev), s12(row_curr, x_next)), b.u32(1)), b.u32(1))
        v_avg = b.op(OP_SHIFT_RIGHT_LOGICAL, b.t_u32, b.op(OP_IADD, b.t_u32, b.op(OP_IADD, b.t_u32, s12(row_prev, sx), s12(row_next, sx)), b.u32(1)), b.u32(1))
        cross_avg = b.op(
            OP_SHIFT_RIGHT_LOGICAL,
            b.t_u32,
            b.op(
                OP_IADD,
                b.t_u32,
                b.op(
                    OP_IADD,
                    b.t_u32,
                    b.op(OP_IADD, b.t_u32, s12(row_curr, x_prev), s12(row_curr, x_next)),
                    b.op(OP_IADD, b.t_u32, s12(row_prev, sx), s12(row_next, sx)),
                ),
                b.u32(2),
            ),
            b.u32(2),
        )
        diag_avg = b.op(
            OP_SHIFT_RIGHT_LOGICAL,
            b.t_u32,
            b.op(
                OP_IADD,
                b.t_u32,
                b.op(
                    OP_IADD,
                    b.t_u32,
                    b.op(OP_IADD, b.t_u32, s12(row_prev, x_prev), s12(row_prev, x_next)),
                    b.op(OP_IADD, b.t_u32, s12(row_next, x_prev), s12(row_next, x_next)),
                ),
                b.u32(2),
            ),
            b.u32(2),
        )

        # Even row (R, Gr): !sxOdd -> (c, cross, diag); sxOdd -> (h, c, v)
        r_even = b.op(OP_SELECT, b.t_u32, sx_odd, h_avg, c)
        g_even = b.op(OP_SELECT, b.t_u32, sx_odd, c, cross_avg)
        b_even = b.op(OP_SELECT, b.t_u32, sx_odd, v_avg, diag_avg)
        # Odd row (Gb, B): !sxOdd -> (v, c, h); sxOdd -> (diag, cross, c)
        r_odd = b.op(OP_SELECT, b.t_u32, sx_odd, diag_avg, v_avg)
        g_odd = b.op(OP_SELECT, b.t_u32, sx_odd, cross_avg, c)
        b_odd = b.op(OP_SELECT, b.t_u32, sx_odd, c, h_avg)

        r_lin = b.op(OP_SELECT, b.t_u32, sy_odd, r_odd, r_even)
        g_lin = b.op(OP_SELECT, b.t_u32, sy_odd, g_odd, g_even)
        b_lin = b.op(OP_SELECT, b.t_u32, sy_odd, b_odd, b_even)

        rg = b.op(OP_BITCAST, b.t_i32, b.glsl_op(GLSL_UMIN, b.t_u32, b.op(OP_SHIFT_RIGHT_LOGICAL, b.t_u32, b.op(OP_IMUL, b.t_u32, r_lin, gain_r), b.u32(10)), b.u32(4095)))
        gg = b.op(OP_BITCAST, b.t_i32, b.glsl_op(GLSL_UMIN, b.t_u32, b.op(OP_SHIFT_RIGHT_LOGICAL, b.t_u32, b.op(OP_IMUL, b.t_u32, g_lin, gain_g), b.u32(10)), b.u32(4095)))
        bg = b.op(OP_BITCAST, b.t_i32, b.glsl_op(GLSL_UMIN, b.t_u32, b.op(OP_SHIFT_RIGHT_LOGICAL, b.t_u32, b.op(OP_IMUL, b.t_u32, b_lin, gain_b), b.u32(10)), b.u32(4095)))

        def ccm_row(c0, c1, c2, orig):
            acc = b.op(
                OP_IADD,
                b.t_i32,
                b.op(
                    OP_IADD,
                    b.t_i32,
                    b.op(OP_IADD, b.t_i32, b.op(OP_IMUL, b.t_i32, c0, rg), b.op(OP_IMUL, b.t_i32, c1, gg)),
                    b.op(OP_IMUL, b.t_i32, c2, bg),
                ),
                b.i32(512),
            )
            cl = b.glsl_op(GLSL_SCLAMP, b.t_i32, b.op(OP_SHIFT_RIGHT_ARITHMETIC, b.t_i32, acc, b.i32(10)), b.i32(0), b.i32(4095))
            return b.op(OP_BITCAST, b.t_u32, b.op(OP_SELECT, b.t_i32, has_ccm, cl, orig))

        ri = ccm_row(ccm[0], ccm[1], ccm[2], rg)
        gi = ccm_row(ccm[3], ccm[4], ccm[5], gg)
        bi = ccm_row(ccm[6], ccm[7], ccm[8], bg)

        r8 = b.op(OP_BITWISE_AND, b.t_u32, b.load_ssbo(in_buf, p_in_u32, b.t_u32, ri), b.u32(0xFF))
        g8 = b.op(OP_BITWISE_AND, b.t_u32, b.op(OP_SHIFT_RIGHT_LOGICAL, b.t_u32, b.load_ssbo(in_buf, p_in_u32, b.t_u32, gi), b.u32(8)), b.u32(0xFF))
        b8 = b.op(OP_BITWISE_AND, b.t_u32, b.op(OP_SHIFT_RIGHT_LOGICAL, b.t_u32, b.load_ssbo(in_buf, p_in_u32, b.t_u32, bi), b.u32(16)), b.u32(0xFF))

        r_out = b.op(OP_SELECT, b.t_u32, active_cond, r8, b.u32(0))
        g_out = b.op(OP_SELECT, b.t_u32, active_cond, g8, b.u32(0))
        b_out = b.op(OP_SELECT, b.t_u32, active_cond, b8, b.u32(0))

        ri_s = b.op(OP_BITCAST, b.t_i32, r_out)
        gi_s = b.op(OP_BITCAST, b.t_i32, g_out)
        bi_s = b.op(OP_BITCAST, b.t_i32, b_out)

        y_bt = b.op(
            OP_SHIFT_RIGHT_ARITHMETIC,
            b.t_i32,
            b.op(
                OP_IADD,
                b.t_i32,
                b.op(
                    OP_IADD,
                    b.t_i32,
                    b.op(OP_IADD, b.t_i32, b.op(OP_IMUL, b.t_i32, b.i32(77), ri_s), b.op(OP_IMUL, b.t_i32, b.i32(150), gi_s)),
                    b.op(OP_IMUL, b.t_i32, b.i32(29), bi_s),
                ),
                b.i32(128),
            ),
            b.i32(8),
        )
        y_mat = b.op(
            OP_SHIFT_RIGHT_ARITHMETIC,
            b.t_i32,
            b.op(
                OP_IADD,
                b.t_i32,
                b.op(
                    OP_IADD,
                    b.t_i32,
                    b.op(OP_IADD, b.t_i32, b.op(OP_IMUL, b.t_i32, ym[0], ri_s), b.op(OP_IMUL, b.t_i32, ym[1], gi_s)),
                    b.op(OP_IMUL, b.t_i32, ym[2], bi_s),
                ),
                yuv_half,
            ),
            yuv_shift,
        )
        y_sel = b.glsl_op(GLSL_SCLAMP, b.t_i32, b.op(OP_SELECT, b.t_i32, has_yuv, y_mat, y_bt), b.i32(0), b.i32(255))
        y_u = b.op(OP_SELECT, b.t_u32, active_cond, b.op(OP_BITCAST, b.t_u32, y_sel), b.u32(0))
        return r_out, g_out, b_out, y_u

    r0, g0, b0, y0 = demosaic_at(x0, in_x0)
    r1, g1, b1, y1 = demosaic_at(x1, in_x1)

    both = b.op(OP_LOGICAL_AND, b.t_bool, in_x0, in_x1)
    avg_r_u = b.op(OP_SELECT, b.t_u32, both, b.op(OP_SHIFT_RIGHT_LOGICAL, b.t_u32, b.op(OP_IADD, b.t_u32, r0, r1), b.u32(1)), b.op(OP_SELECT, b.t_u32, in_x0, r0, r1))
    avg_g_u = b.op(OP_SELECT, b.t_u32, both, b.op(OP_SHIFT_RIGHT_LOGICAL, b.t_u32, b.op(OP_IADD, b.t_u32, g0, g1), b.u32(1)), b.op(OP_SELECT, b.t_u32, in_x0, g0, g1))
    avg_b_u = b.op(OP_SELECT, b.t_u32, both, b.op(OP_SHIFT_RIGHT_LOGICAL, b.t_u32, b.op(OP_IADD, b.t_u32, b0, b1), b.u32(1)), b.op(OP_SELECT, b.t_u32, in_x0, b0, b1))

    ar = b.op(OP_BITCAST, b.t_i32, avg_r_u)
    ag = b.op(OP_BITCAST, b.t_i32, avg_g_u)
    ab = b.op(OP_BITCAST, b.t_i32, avg_b_u)

    def chroma_val(bt_r, bt_g, bt_b, m0, m1, m2):
        c_bt = b.op(
            OP_IADD,
            b.t_i32,
            b.op(
                OP_SHIFT_RIGHT_ARITHMETIC,
                b.t_i32,
                b.op(
                    OP_IADD,
                    b.t_i32,
                    b.op(
                        OP_IADD,
                        b.t_i32,
                        b.op(OP_IADD, b.t_i32, b.op(OP_IMUL, b.t_i32, b.i32(bt_r), ar), b.op(OP_IMUL, b.t_i32, b.i32(bt_g), ag)),
                        b.op(OP_IMUL, b.t_i32, b.i32(bt_b), ab),
                    ),
                    b.i32(128),
                ),
                b.i32(8),
            ),
            b.i32(128),
        )
        c_mat = b.op(
            OP_IADD,
            b.t_i32,
            b.op(
                OP_SHIFT_RIGHT_ARITHMETIC,
                b.t_i32,
                b.op(
                    OP_IADD,
                    b.t_i32,
                    b.op(
                        OP_IADD,
                        b.t_i32,
                        b.op(OP_IADD, b.t_i32, b.op(OP_IMUL, b.t_i32, m0, ar), b.op(OP_IMUL, b.t_i32, m1, ag)),
                        b.op(OP_IMUL, b.t_i32, m2, ab),
                    ),
                    yuv_half,
                ),
                yuv_shift,
            ),
            b.i32(128),
        )
        cl = b.glsl_op(GLSL_SCLAMP, b.t_i32, b.op(OP_SELECT, b.t_i32, has_yuv, c_mat, c_bt), b.i32(0), b.i32(255))
        return b.op(OP_SELECT, b.t_u32, mono_mode, b.u32(128), b.op(OP_BITCAST, b.t_u32, cl))

    u_val = chroma_val(-43, -85, 128, ym[3], ym[4], ym[5])
    v_val = chroma_val(128, -107, -21, ym[6], ym[7], ym[8])

    packed = b.op(
        OP_BITWISE_OR,
        b.t_u32,
        b.op(OP_BITWISE_OR, b.t_u32, u_val, b.op(OP_SHIFT_LEFT_LOGICAL, b.t_u32, y0, b.u32(8))),
        b.op(OP_BITWISE_OR, b.t_u32, b.op(OP_SHIFT_LEFT_LOGICAL, b.t_u32, v_val, b.u32(16)), b.op(OP_SHIFT_LEFT_LOGICAL, b.t_u32, y1, b.u32(24))),
    )
    b.store_ssbo(out_buf, p_out_u32, out_idx, packed)
    b.void_op(OP_BRANCH, lbl_after_active)

    b.label(lbl_after_active)
    b.void_op(OP_BRANCH, lbl_done)

    b.label(lbl_done)
    return b.finish()


def build_isp_peaking_spirv() -> list[int]:
    """
    Phase 3 Pass 4B: Bit-exact 5-tap HPF Focus Peaking on canonical UYVY422 uint32 pairs.
    Reads unmodified luma neighborhood from binding 0 and stamps (hiY, hiCb, hiCr) to binding 1.
    """
    b = SpirvBuilder()
    in_buf, p_in_u32 = b.ssbo_array(b.t_u32, 4, 0)
    out_buf, p_out_u32 = b.ssbo_array(b.t_u32, 4, 1)
    ubo = b.uniform_struct(isp_ubo_types(b), 2)

    p_gx = b.op(OP_ACCESS_CHAIN, b.p_in_u32, b.var_gid, b.u32(0))
    px_pair = b.op(OP_LOAD, b.t_u32, p_gx)
    p_gy = b.op(OP_ACCESS_CHAIN, b.p_in_u32, b.var_gid, b.u32(1))
    gy = b.op(OP_LOAD, b.t_u32, p_gy)

    dst_w = b.load_ubo(ubo, 2, False)
    dst_h = b.load_ubo(ubo, 3, False)
    pairs_w = b.op(OP_SHIFT_RIGHT_LOGICAL, b.t_u32, dst_w, b.u32(1))

    in_bounds = b.op(
        OP_LOGICAL_AND,
        b.t_bool,
        b.op(OP_ULESS_THAN, b.t_bool, px_pair, pairs_w),
        b.op(OP_ULESS_THAN, b.t_bool, gy, dst_h),
    )
    lbl_body = b.id()
    lbl_done = b.id()
    b.void_op(OP_SELECTION_MERGE, lbl_done, 0)
    b.void_op(OP_BRANCH_CONDITIONAL, in_bounds, lbl_body, lbl_done)

    b.label(lbl_body)
    out_idx = b.op(OP_IADD, b.t_u32, b.op(OP_IMUL, b.t_u32, gy, pairs_w), px_pair)
    cur_word = b.load_ssbo(in_buf, p_in_u32, b.t_u32, out_idx)

    # Border check: y in [2, dstH - 2) and dstW >= 6 and dstH >= 5
    y_ok = b.op(
        OP_LOGICAL_AND,
        b.t_bool,
        b.op(OP_UGREATER_THAN_EQUAL, b.t_bool, gy, b.u32(2)),
        b.op(OP_ULESS_THAN, b.t_bool, b.op(OP_IADD, b.t_u32, gy, b.u32(2)), dst_h),
    )
    lbl_inner = b.id()
    lbl_pass = b.id()
    lbl_after_y = b.id()
    b.void_op(OP_SELECTION_MERGE, lbl_after_y, 0)
    b.void_op(OP_BRANCH_CONDITIONAL, y_ok, lbl_inner, lbl_pass)

    b.label(lbl_pass)
    b.store_ssbo(out_buf, p_out_u32, out_idx, cur_word)
    b.void_op(OP_BRANCH, lbl_after_y)

    b.label(lbl_inner)
    thr_i = b.op(OP_BITCAST, b.t_i32, b.load_ubo(ubo, 34, False))
    hi_y = b.op(OP_BITWISE_AND, b.t_u32, b.load_ubo(ubo, 35, False), b.u32(0xFF))
    hi_cb = b.op(OP_BITWISE_AND, b.t_u32, b.load_ubo(ubo, 36, False), b.u32(0xFF))
    hi_cr = b.op(OP_BITWISE_AND, b.t_u32, b.load_ubo(ubo, 37, False), b.u32(0xFF))

    def load_luma(x_u, y_u):
        p_idx = b.op(OP_IADD, b.t_u32, b.op(OP_IMUL, b.t_u32, y_u, pairs_w), b.op(OP_SHIFT_RIGHT_LOGICAL, b.t_u32, x_u, b.u32(1)))
        w = b.load_ssbo(in_buf, p_in_u32, b.t_u32, p_idx)
        is_odd = b.op(OP_INOT_EQUAL, b.t_bool, b.op(OP_BITWISE_AND, b.t_u32, x_u, b.u32(1)), b.u32(0))
        sh = b.op(OP_SELECT, b.t_u32, is_odd, b.u32(24), b.u32(8))
        lu = b.op(OP_BITWISE_AND, b.t_u32, b.op(OP_SHIFT_RIGHT_LOGICAL, b.t_u32, w, sh), b.u32(0xFF))
        return b.op(OP_BITCAST, b.t_i32, lu)

    def check_peak(x_u):
        x_ok = b.op(
            OP_LOGICAL_AND,
            b.t_bool,
            b.op(OP_UGREATER_THAN_EQUAL, b.t_bool, x_u, b.u32(2)),
            b.op(OP_ULESS_THAN, b.t_bool, b.op(OP_IADD, b.t_u32, x_u, b.u32(2)), dst_w),
        )
        xc = b.glsl_op(GLSL_UCLAMP, b.t_u32, x_u, b.u32(2), b.op(OP_ISUB, b.t_u32, dst_w, b.u32(3)))
        gx1 = b.glsl_op(GLSL_SABS, b.t_i32, b.op(OP_ISUB, b.t_i32, load_luma(b.op(OP_IADD, b.t_u32, xc, b.u32(1)), gy), load_luma(b.op(OP_ISUB, b.t_u32, xc, b.u32(1)), gy)))
        gx2 = b.glsl_op(GLSL_SABS, b.t_i32, b.op(OP_ISUB, b.t_i32, load_luma(b.op(OP_IADD, b.t_u32, xc, b.u32(2)), gy), load_luma(b.op(OP_ISUB, b.t_u32, xc, b.u32(2)), gy)))
        gy1 = b.glsl_op(GLSL_SABS, b.t_i32, b.op(OP_ISUB, b.t_i32, load_luma(xc, b.op(OP_IADD, b.t_u32, gy, b.u32(1))), load_luma(xc, b.op(OP_ISUB, b.t_u32, gy, b.u32(1)))))
        gy2 = b.glsl_op(GLSL_SABS, b.t_i32, b.op(OP_ISUB, b.t_i32, load_luma(xc, b.op(OP_IADD, b.t_u32, gy, b.u32(2))), load_luma(xc, b.op(OP_ISUB, b.t_u32, gy, b.u32(2)))))
        mag = b.op(OP_IADD, b.t_i32, b.glsl_op(GLSL_SMAX, b.t_i32, gx1, gx2), b.glsl_op(GLSL_SMAX, b.t_i32, gy1, gy2))
        return b.op(OP_LOGICAL_AND, b.t_bool, x_ok, b.op(OP_SGREATER_THAN, b.t_bool, mag, thr_i))

    x0 = b.op(OP_SHIFT_LEFT_LOGICAL, b.t_u32, px_pair, b.u32(1))
    x1 = b.op(OP_IADD, b.t_u32, x0, b.u32(1))
    p0 = check_peak(x0)
    p1 = check_peak(x1)
    any_p = b.op(OP_LOGICAL_OR, b.t_bool, p0, p1)

    u_old = b.op(OP_BITWISE_AND, b.t_u32, cur_word, b.u32(0xFF))
    y0_old = b.op(OP_BITWISE_AND, b.t_u32, b.op(OP_SHIFT_RIGHT_LOGICAL, b.t_u32, cur_word, b.u32(8)), b.u32(0xFF))
    v_old = b.op(OP_BITWISE_AND, b.t_u32, b.op(OP_SHIFT_RIGHT_LOGICAL, b.t_u32, cur_word, b.u32(16)), b.u32(0xFF))
    y1_old = b.op(OP_BITWISE_AND, b.t_u32, b.op(OP_SHIFT_RIGHT_LOGICAL, b.t_u32, cur_word, b.u32(24)), b.u32(0xFF))

    u_out = b.op(OP_SELECT, b.t_u32, any_p, hi_cb, u_old)
    v_out = b.op(OP_SELECT, b.t_u32, any_p, hi_cr, v_old)
    y0_out = b.op(OP_SELECT, b.t_u32, p0, hi_y, y0_old)
    y1_out = b.op(OP_SELECT, b.t_u32, p1, hi_y, y1_old)

    out_word = b.op(
        OP_BITWISE_OR,
        b.t_u32,
        b.op(OP_BITWISE_OR, b.t_u32, u_out, b.op(OP_SHIFT_LEFT_LOGICAL, b.t_u32, y0_out, b.u32(8))),
        b.op(OP_BITWISE_OR, b.t_u32, b.op(OP_SHIFT_LEFT_LOGICAL, b.t_u32, v_out, b.u32(16)), b.op(OP_SHIFT_LEFT_LOGICAL, b.t_u32, y1_out, b.u32(24))),
    )
    b.store_ssbo(out_buf, p_out_u32, out_idx, out_word)
    b.void_op(OP_BRANCH, lbl_after_y)

    b.label(lbl_after_y)
    b.void_op(OP_BRANCH, lbl_done)

    b.label(lbl_done)
    return b.finish()


def build_bokeh_downsample_2x_spirv() -> list[int]:
    """Energy-preserving 2x2 box-average downsample pass for large-radius depth bokeh (cocPx > 8.0)."""
    b = SpirvBuilder()
    in_buf, p_in_v4 = b.ssbo_array(b.t_vec4, 16, 0)
    out_buf, p_out_v4 = b.ssbo_array(b.t_vec4, 16, 1)
    ubo = b.uniform_struct([b.t_u32] * 4 + [b.t_f32] * 24 + [b.t_u32] * 4, 2)

    p_gx = b.op(OP_ACCESS_CHAIN, b.p_in_u32, b.var_gid, b.u32(0))
    gx = b.op(OP_LOAD, b.t_u32, p_gx)
    p_gy = b.op(OP_ACCESS_CHAIN, b.p_in_u32, b.var_gid, b.u32(1))
    gy = b.op(OP_LOAD, b.t_u32, p_gy)

    dst_w = b.load_ubo(ubo, 2, False)
    dst_h = b.load_ubo(ubo, 3, False)
    half_w = b.op(OP_SHIFT_RIGHT_LOGICAL, b.t_u32, dst_w, b.u32(1))
    half_h = b.op(OP_SHIFT_RIGHT_LOGICAL, b.t_u32, dst_h, b.u32(1))

    in_bounds = b.op(
        OP_LOGICAL_AND,
        b.t_bool,
        b.op(OP_ULESS_THAN, b.t_bool, gx, half_w),
        b.op(OP_ULESS_THAN, b.t_bool, gy, half_h),
    )
    lbl_body = b.id()
    lbl_done = b.id()
    b.void_op(OP_SELECTION_MERGE, lbl_done, 0)
    b.void_op(OP_BRANCH_CONDITIONAL, in_bounds, lbl_body, lbl_done)

    b.label(lbl_body)
    x0 = b.op(OP_SHIFT_LEFT_LOGICAL, b.t_u32, gx, b.u32(1))
    y0 = b.op(OP_SHIFT_LEFT_LOGICAL, b.t_u32, gy, b.u32(1))
    row0 = b.op(OP_IMUL, b.t_u32, y0, dst_w)
    row1 = b.op(OP_IADD, b.t_u32, row0, dst_w)
    i00 = b.op(OP_IADD, b.t_u32, row0, x0)
    i10 = b.op(OP_IADD, b.t_u32, i00, b.u32(1))
    i01 = b.op(OP_IADD, b.t_u32, row1, x0)
    i11 = b.op(OP_IADD, b.t_u32, i01, b.u32(1))

    v00 = b.load_ssbo(in_buf, p_in_v4, b.t_vec4, i00)
    v10 = b.load_ssbo(in_buf, p_in_v4, b.t_vec4, i10)
    v01 = b.load_ssbo(in_buf, p_in_v4, b.t_vec4, i01)
    v11 = b.load_ssbo(in_buf, p_in_v4, b.t_vec4, i11)

    q = b.f32(0.25)
    comps = []
    for c in range(4):
        s0 = b.op(OP_FADD, b.t_f32, b.op(OP_COMPOSITE_EXTRACT, b.t_f32, v00, c), b.op(OP_COMPOSITE_EXTRACT, b.t_f32, v10, c))
        s1 = b.op(OP_FADD, b.t_f32, b.op(OP_COMPOSITE_EXTRACT, b.t_f32, v01, c), b.op(OP_COMPOSITE_EXTRACT, b.t_f32, v11, c))
        comps.append(b.op(OP_FMUL, b.t_f32, b.op(OP_FADD, b.t_f32, s0, s1), q))
    avg = b.op(OP_COMPOSITE_CONSTRUCT, b.t_vec4, comps[0], comps[1], comps[2], comps[3])
    out_idx = b.op(OP_IADD, b.t_u32, b.op(OP_IMUL, b.t_u32, gy, half_w), gx)
    b.store_ssbo(out_buf, p_out_v4, out_idx, avg)
    b.void_op(OP_BRANCH, lbl_done)

    b.label(lbl_done)
    return b.finish()


def format_array(name: str, words: list[int]) -> str:
    lines = [f"static const uint32_t {name}[{len(words)}] = {{"]
    for i in range(0, len(words), 8):
        chunk = ", ".join(f"0x{w:08x}u" for w in words[i : i + 8])
        lines.append(f"    {chunk},")
    lines.append("};")
    return "\n".join(lines)


def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    out_path = os.path.join(os.path.dirname(script_dir), "fuji_vulkan_shaders_spirv.h")

    warp_spv = build_optics_warp_spirv()
    bokeh_spv = build_pupil_bokeh_spirv()
    cmos_spv = build_cmos_bayer_spirv()
    filter_spv = build_optical_filter_stack_spirv()
    isp_demosaic_spv = build_isp_demosaic_spirv()
    isp_peaking_spv = build_isp_peaking_spirv()
    bokeh_down_spv = build_bokeh_downsample_2x_spirv()

    header = f"""/* AUTO-GENERATED by shaders/generate_spirv_header.py — Khronos SPIR-V 1.0 compute shaders */
#ifndef FUJI_VULKAN_SHADERS_SPIRV_H
#define FUJI_VULKAN_SHADERS_SPIRV_H

#include <stdint.h>

{format_array("FUJI_SPIRV_OPTICS_WARP", warp_spv)}

{format_array("FUJI_SPIRV_PUPIL_BOKEH", bokeh_spv)}

{format_array("FUJI_SPIRV_CMOS_BAYER", cmos_spv)}

{format_array("FUJI_SPIRV_OPTICAL_FILTER_STACK", filter_spv)}

{format_array("FUJI_SPIRV_ISP_DEMOSAIC", isp_demosaic_spv)}

{format_array("FUJI_SPIRV_ISP_PEAKING", isp_peaking_spv)}

{format_array("FUJI_SPIRV_BOKEH_DOWNSAMPLE", bokeh_down_spv)}

#endif /* FUJI_VULKAN_SHADERS_SPIRV_H */
"""
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        f.write(header)
    print(
        f"Generated {out_path}: warp={len(warp_spv)}w, bokeh={len(bokeh_spv)}w, "
        f"cmos={len(cmos_spv)}w, filter={len(filter_spv)}w, "
        f"isp_demosaic={len(isp_demosaic_spv)}w, isp_peaking={len(isp_peaking_spv)}w, "
        f"bokeh_downsample={len(bokeh_down_spv)}w"
    )


if __name__ == "__main__":
    main()
