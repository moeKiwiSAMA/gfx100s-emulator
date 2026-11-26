#!/usr/bin/env python3
"""
Generates tools/native/vulkan-optics/fuji_vulkan_scene3d_spirv.h containing
spec-compliant Khronos SPIR-V 1.0 compute shaders for GPU 3D Scene Rendering:
  0. milkfrog_vertex_xform (64x1x1): 60fps belly-laugh deformation + 3-point studio lighting + projection
  1. milkfrog_tri_bin      (64x1x1): double-sided winding normalization + 16x16 screen tile binning
  2. milkfrog_tile_raster  (8x8x1):  static studio stage + pedestal/focus ruler + Z-buffered tile raster + HDR orbs
  3. scene3d_qr_ball       (8x8x1):  analytic 6-DoF 3D raytracer (room, QR wall, 8 HDR lamps, plinth, sphere)
  4. scene3d_mona_lisa     (8x8x1):  60fps sculpted 3D portrait + parallax Tuscan backdrop compositor
  5. scene3d_layered       (8x8x1):  6-DoF parallax reprojection for depth-studio / bokeh-city / star-bokeh

Zero external compiler dependencies; deterministic Khronos SPIR-V 1.0 verified on
Mesa Lavapipe and Vulkan 1.1+ hardware ICDs.
"""

import os
import struct

SPIRV_MAGIC = 0x07230203
SPIRV_VERSION_1_0 = 0x00010000

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
OP_SNEGATE = 126
OP_FNEGATE = 127
OP_IADD = 128
OP_FADD = 129
OP_ISUB = 130
OP_FSUB = 131
OP_IMUL = 132
OP_FMUL = 133
OP_UDIV = 134
OP_FDIV = 136
OP_FMOD = 141
OP_LOGICAL_OR = 166
OP_LOGICAL_AND = 167
OP_LOGICAL_NOT = 168
OP_SELECT = 169
OP_IEQUAL = 170
OP_INOT_EQUAL = 171
OP_UGREATER_THAN = 172
OP_SGREATER_THAN = 173
OP_UGREATER_THAN_EQUAL = 174
OP_SGREATER_THAN_EQUAL = 175
OP_ULESS_THAN = 176
OP_SLESS_THAN = 177
OP_ULESS_THAN_EQUAL = 178
OP_SLESS_THAN_EQUAL = 179
OP_FORD_LESS_THAN = 184
OP_FORD_GREATER_THAN = 186
OP_FORD_LESS_THAN_EQUAL = 188
OP_FORD_GREATER_THAN_EQUAL = 190
OP_SHIFT_RIGHT_LOGICAL = 194
OP_SHIFT_RIGHT_ARITHMETIC = 195
OP_SHIFT_LEFT_LOGICAL = 196
OP_BITWISE_OR = 197
OP_BITWISE_AND = 199
OP_ATOMIC_IADD = 234
OP_ATOMIC_OR = 241
OP_FORD_EQUAL = 180
OP_BIT_COUNT = 205
GLSL_FIND_I_LSB = 73
OP_LOOP_MERGE = 246
OP_SELECTION_MERGE = 247
OP_LABEL = 248
OP_BRANCH = 249
OP_BRANCH_CONDITIONAL = 250
OP_RETURN = 253

GLSL_FABS = 4
GLSL_FLOOR = 8
GLSL_SIN = 13
GLSL_COS = 14
GLSL_ASIN = 16
GLSL_ATAN2 = 25
GLSL_POW = 26
GLSL_EXP = 27
GLSL_SQRT = 31
GLSL_FMIN = 37
GLSL_UMIN = 38
GLSL_SMIN = 39
GLSL_FMAX = 40
GLSL_UMAX = 41
GLSL_SMAX = 42
GLSL_FCLAMP = 43
GLSL_UCLAMP = 44
GLSL_SCLAMP = 45

CAP_SHADER = 1
ADDR_LOGICAL = 0
MEM_GLSL450 = 1
EXEC_GL_COMPUTE = 5
EXEC_MODE_LOCAL_SIZE = 17

SC_INPUT = 1
SC_UNIFORM = 2
SC_FUNCTION = 7

DEC_BLOCK = 2
DEC_BUFFER_BLOCK = 3
DEC_ARRAY_STRIDE = 6
DEC_BUILTIN = 11
DEC_BINDING = 33
DEC_DESCRIPTOR_SET = 34
DEC_OFFSET = 35

BUILTIN_GLOBAL_INVOCATION_ID = 28

SPARSE_TILE_WORDS = 64
MAX_ORBS = 8


def f32_bits(val: float) -> int:
    return struct.unpack("<I", struct.pack("<f", float(val)))[0]


def encode_str(s: str) -> list[int]:
    raw = s.encode("utf-8") + b"\x00"
    while len(raw) % 4 != 0:
        raw += b"\x00"
    return list(struct.unpack(f"<{len(raw) // 4}I", raw))


class SpirvBuilder:
    def __init__(self, lx: int = 8, ly: int = 8, lz: int = 1):
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
        self._ptr_cache = {}

        self.glsl = self.id()
        self.main_fn = self.id()
        self.var_gid = self.id()

        self._emit(self.caps, OP_CAPABILITY, CAP_SHADER)
        self._emit_str(self.ext_imports, OP_EXT_INST_IMPORT, [self.glsl], "GLSL.std.450")
        self._emit(self.mem_model, OP_MEMORY_MODEL, ADDR_LOGICAL, MEM_GLSL450)
        self._emit_str(self.entry_points, OP_ENTRY_POINT, [EXEC_GL_COMPUTE, self.main_fn], "main", [self.var_gid])
        self._emit(self.exec_modes, OP_EXECUTION_MODE, self.main_fn, EXEC_MODE_LOCAL_SIZE, lx, ly, lz)

        self.t_void = self.id()
        self.t_bool = self.id()
        self.t_u32 = self.id()
        self.t_i32 = self.id()
        self.t_f32 = self.id()
        self.t_uvec3 = self.id()
        self.t_vec4 = self.id()
        self.t_fn_void = self.id()

        self._emit(self.types_consts, OP_TYPE_VOID, self.t_void)
        self._emit(self.types_consts, OP_TYPE_BOOL, self.t_bool)
        self._emit(self.types_consts, OP_TYPE_INT, self.t_u32, 32, 0)
        self._emit(self.types_consts, OP_TYPE_INT, self.t_i32, 32, 1)
        self._emit(self.types_consts, OP_TYPE_FLOAT, self.t_f32, 32)
        self._emit(self.types_consts, OP_TYPE_VECTOR, self.t_uvec3, self.t_u32, 3)
        self._emit(self.types_consts, OP_TYPE_VECTOR, self.t_vec4, self.t_f32, 4)
        self._emit(self.types_consts, OP_TYPE_FUNCTION, self.t_fn_void, self.t_void)

        self.p_in_uvec3 = self.ptr_type(SC_INPUT, self.t_uvec3)
        self.p_in_u32 = self.ptr_type(SC_INPUT, self.t_u32)
        self.p_u_f32 = self.ptr_type(SC_UNIFORM, self.t_f32)
        self.p_u_u32 = self.ptr_type(SC_UNIFORM, self.t_u32)
        self.p_u_i32 = self.ptr_type(SC_UNIFORM, self.t_i32)
        self.p_u_vec4 = self.ptr_type(SC_UNIFORM, self.t_vec4)
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
        key = (sc, base_type)
        if key not in self._ptr_cache:
            tid = self.id()
            self._emit(self.types_consts, OP_TYPE_POINTER, tid, sc, base_type)
            self._ptr_cache[key] = tid
        return self._ptr_cache[key]

    def u32(self, v: int) -> int:
        v &= 0xFFFFFFFF
        if v not in self._const_u32:
            cid = self.id()
            self._emit(self.types_consts, OP_CONSTANT, self.t_u32, cid, v)
            self._const_u32[v] = cid
        return self._const_u32[v]

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

    def local_f32(self, init=None) -> int:
        v = self.id()
        self._emit(self.func_vars, OP_VARIABLE, self.p_fn_f32, v, SC_FUNCTION)
        if init is not None:
            self.store(v, init)
        return v

    def local_i32(self, init=None) -> int:
        v = self.id()
        self._emit(self.func_vars, OP_VARIABLE, self.p_fn_i32, v, SC_FUNCTION)
        if init is not None:
            self.store(v, init)
        return v

    def local_u32(self, init=None) -> int:
        v = self.id()
        self._emit(self.func_vars, OP_VARIABLE, self.p_fn_u32, v, SC_FUNCTION)
        if init is not None:
            self.store(v, init)
        return v

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

    def load(self, var_ptr: int, t: int) -> int:
        return self.op(OP_LOAD, t, var_ptr)

    def lf(self, v: int) -> int:
        return self.load(v, self.t_f32)

    def li(self, v: int) -> int:
        return self.load(v, self.t_i32)

    def lu(self, v: int) -> int:
        return self.load(v, self.t_u32)

    def store(self, var_ptr: int, val: int):
        self.void_op(OP_STORE, var_ptr, val)

    def gid(self, comp: int) -> int:
        p = self.op(OP_ACCESS_CHAIN, self.p_in_u32, self.var_gid, self.u32(comp))
        return self.op(OP_LOAD, self.t_u32, p)

    def load_ubo(self, ubo_var: int, idx: int, is_float: bool) -> int:
        pt = self.p_u_f32 if is_float else self.p_u_u32
        t = self.t_f32 if is_float else self.t_u32
        ptr = self.op(OP_ACCESS_CHAIN, pt, ubo_var, self.i32(idx))
        return self.op(OP_LOAD, t, ptr)

    def load_ssbo(self, var_id: int, ptr_t: int, elem_t: int, idx_u32: int) -> int:
        ptr = self.op(OP_ACCESS_CHAIN, ptr_t, var_id, self.i32(0), idx_u32)
        return self.op(OP_LOAD, elem_t, ptr)

    def store_ssbo(self, var_id: int, ptr_t: int, idx_u32: int, val: int):
        ptr = self.op(OP_ACCESS_CHAIN, ptr_t, var_id, self.i32(0), idx_u32)
        self.void_op(OP_STORE, ptr, val)

    def atomic_add_ssbo_u32(self, var_id: int, ptr_t: int, idx_u32: int, val_u32: int) -> int:
        ptr = self.op(OP_ACCESS_CHAIN, ptr_t, var_id, self.i32(0), idx_u32)
        return self.op(OP_ATOMIC_IADD, self.t_u32, ptr, self.u32(1), self.u32(0), val_u32)

    def atomic_or_ssbo_u32(self, var_id: int, ptr_t: int, idx_u32: int, bits: int) -> int:
        ptr = self.op(OP_ACCESS_CHAIN, ptr_t, var_id, self.i32(0), idx_u32)
        return self.op(OP_ATOMIC_OR, self.t_u32, ptr, self.u32(1), self.u32(0), bits)

    # Shorthand math ops
    def fadd(self, a, b): return self.op(OP_FADD, self.t_f32, a, b)
    def fsub(self, a, b): return self.op(OP_FSUB, self.t_f32, a, b)
    def fmul(self, a, b): return self.op(OP_FMUL, self.t_f32, a, b)
    def fdiv(self, a, b): return self.op(OP_FDIV, self.t_f32, a, b)
    def fneg(self, a): return self.op(OP_FNEGATE, self.t_f32, a)
    def fabs(self, a): return self.glsl_op(GLSL_FABS, self.t_f32, a)
    def ffloor(self, a): return self.glsl_op(GLSL_FLOOR, self.t_f32, a)
    def fsqrt(self, a): return self.glsl_op(GLSL_SQRT, self.t_f32, a)
    def fexp(self, a): return self.glsl_op(GLSL_EXP, self.t_f32, a)
    def fpow(self, a, b): return self.glsl_op(GLSL_POW, self.t_f32, self.fmax(a, self.f32(1e-6)), b)
    def fsin(self, a): return self.glsl_op(GLSL_SIN, self.t_f32, a)
    def fcos(self, a): return self.glsl_op(GLSL_COS, self.t_f32, a)
    def fasin(self, a): return self.glsl_op(GLSL_ASIN, self.t_f32, self.fclamp(a, self.f32(-1.0), self.f32(1.0)))
    def fatan2(self, y, x): return self.glsl_op(GLSL_ATAN2, self.t_f32, y, x)
    def fmin(self, a, b): return self.glsl_op(GLSL_FMIN, self.t_f32, a, b)
    def fmax(self, a, b): return self.glsl_op(GLSL_FMAX, self.t_f32, a, b)
    def fclamp(self, x, lo, hi): return self.glsl_op(GLSL_FCLAMP, self.t_f32, x, lo, hi)
    def fmod(self, a, b): return self.op(OP_FMOD, self.t_f32, a, b)

    def flt(self, a, b): return self.op(OP_FORD_LESS_THAN, self.t_bool, a, b)
    def fle(self, a, b): return self.op(OP_FORD_LESS_THAN_EQUAL, self.t_bool, a, b)
    def fgt(self, a, b): return self.op(OP_FORD_GREATER_THAN, self.t_bool, a, b)
    def fge(self, a, b): return self.op(OP_FORD_GREATER_THAN_EQUAL, self.t_bool, a, b)

    def iadd(self, a, b): return self.op(OP_IADD, self.t_i32, a, b)
    def isub(self, a, b): return self.op(OP_ISUB, self.t_i32, a, b)
    def imul(self, a, b): return self.op(OP_IMUL, self.t_i32, a, b)
    def uadd(self, a, b): return self.op(OP_IADD, self.t_u32, a, b)
    def usub(self, a, b): return self.op(OP_ISUB, self.t_u32, a, b)
    def umul(self, a, b): return self.op(OP_IMUL, self.t_u32, a, b)
    def uand(self, a, b): return self.op(OP_BITWISE_AND, self.t_u32, a, b)
    def ushr(self, a, b): return self.op(OP_SHIFT_RIGHT_LOGICAL, self.t_u32, a, b)
    def ishr(self, a, b): return self.op(OP_SHIFT_RIGHT_ARITHMETIC, self.t_i32, a, b)
    def iand(self, a, b): return self.op(OP_BITWISE_AND, self.t_i32, a, b)
    def ueq(self, a, b): return self.op(OP_IEQUAL, self.t_bool, a, b)
    def une(self, a, b): return self.op(OP_INOT_EQUAL, self.t_bool, a, b)
    def ult(self, a, b): return self.op(OP_ULESS_THAN, self.t_bool, a, b)
    def ule(self, a, b): return self.op(OP_ULESS_THAN_EQUAL, self.t_bool, a, b)
    def ieq(self, a, b): return self.op(OP_IEQUAL, self.t_bool, a, b)
    def ilt(self, a, b): return self.op(OP_SLESS_THAN, self.t_bool, a, b)
    def ile(self, a, b): return self.op(OP_SLESS_THAN_EQUAL, self.t_bool, a, b)
    def igt(self, a, b): return self.op(OP_SGREATER_THAN, self.t_bool, a, b)
    def ige(self, a, b): return self.op(OP_SGREATER_THAN_EQUAL, self.t_bool, a, b)

    def land(self, a, b): return self.op(OP_LOGICAL_AND, self.t_bool, a, b)
    def lor(self, a, b): return self.op(OP_LOGICAL_OR, self.t_bool, a, b)
    def lnot(self, a): return self.op(OP_LOGICAL_NOT, self.t_bool, a)
    def sel_f(self, cond, t_val, f_val): return self.op(OP_SELECT, self.t_f32, cond, t_val, f_val)
    def sel_i(self, cond, t_val, f_val): return self.op(OP_SELECT, self.t_i32, cond, t_val, f_val)
    def sel_u(self, cond, t_val, f_val): return self.op(OP_SELECT, self.t_u32, cond, t_val, f_val)

    def utof(self, a): return self.op(OP_CONVERT_UTOF, self.t_f32, a)
    def stof(self, a): return self.op(OP_CONVERT_STOF, self.t_f32, a)
    def ftos(self, a): return self.op(OP_CONVERT_FTOS, self.t_i32, a)
    def ftou(self, a): return self.op(OP_CONVERT_FTOU, self.t_u32, a)
    def i2u(self, a): return self.op(OP_BITCAST, self.t_u32, a)
    def u2i(self, a): return self.op(OP_BITCAST, self.t_i32, a)
    def sclamp(self, x, lo, hi): return self.glsl_op(GLSL_SCLAMP, self.t_i32, x, lo, hi)
    def smin(self, a, b): return self.glsl_op(GLSL_SMIN, self.t_i32, a, b)
    def smax(self, a, b): return self.glsl_op(GLSL_SMAX, self.t_i32, a, b)
    def umin(self, a, b): return self.glsl_op(GLSL_UMIN, self.t_u32, a, b)

    def vec4(self, x, y, z, w): return self.op(OP_COMPOSITE_CONSTRUCT, self.t_vec4, x, y, z, w)
    def ex(self, v, idx: int): return self.op(OP_COMPOSITE_EXTRACT, self.t_f32, v, idx)

    def if_block(self, cond, then_fn, else_fn=None):
        lbl_then = self.id()
        lbl_merge = self.id()
        if else_fn is None:
            self.void_op(OP_SELECTION_MERGE, lbl_merge, 0)
            self.void_op(OP_BRANCH_CONDITIONAL, cond, lbl_then, lbl_merge)
            self.label(lbl_then)
            then_fn()
            self.void_op(OP_BRANCH, lbl_merge)
            self.label(lbl_merge)
        else:
            lbl_else = self.id()
            self.void_op(OP_SELECTION_MERGE, lbl_merge, 0)
            self.void_op(OP_BRANCH_CONDITIONAL, cond, lbl_then, lbl_else)
            self.label(lbl_then)
            then_fn()
            self.void_op(OP_BRANCH, lbl_merge)
            self.label(lbl_else)
            else_fn()
            self.void_op(OP_BRANCH, lbl_merge)
            self.label(lbl_merge)

    def for_u32(self, start_u, end_u, body_fn):
        v_i = self.local_u32(start_u)
        lbl_hdr = self.id()
        lbl_body = self.id()
        lbl_cont = self.id()
        lbl_exit = self.id()
        self.void_op(OP_BRANCH, lbl_hdr)
        self.label(lbl_hdr)
        cur = self.lu(v_i)
        cond = self.ult(cur, end_u)
        self.void_op(OP_LOOP_MERGE, lbl_exit, lbl_cont, 0)
        self.void_op(OP_BRANCH_CONDITIONAL, cond, lbl_body, lbl_exit)
        self.label(lbl_body)
        body_fn(cur)
        self.void_op(OP_BRANCH, lbl_cont)
        self.label(lbl_cont)
        self.store(v_i, self.uadd(self.lu(v_i), self.u32(1)))
        self.void_op(OP_BRANCH, lbl_hdr)
        self.label(lbl_exit)

    def for_i32_inclusive(self, start_i, end_i, body_fn):
        v_i = self.local_i32(start_i)
        lbl_hdr = self.id()
        lbl_body = self.id()
        lbl_cont = self.id()
        lbl_exit = self.id()
        self.void_op(OP_BRANCH, lbl_hdr)
        self.label(lbl_hdr)
        cur = self.li(v_i)
        cond = self.ile(cur, end_i)
        self.void_op(OP_LOOP_MERGE, lbl_exit, lbl_cont, 0)
        self.void_op(OP_BRANCH_CONDITIONAL, cond, lbl_body, lbl_exit)
        self.label(lbl_body)
        body_fn(cur)
        self.void_op(OP_BRANCH, lbl_cont)
        self.label(lbl_cont)
        self.store(v_i, self.iadd(self.li(v_i), self.i32(1)))
        self.void_op(OP_BRANCH, lbl_hdr)
        self.label(lbl_exit)

    def emit_depth_grid_write(self, grid_var, p_grid_f, gx, gy, tiles_x, depth_val):
        is_cx = self.ueq(self.uand(gx, self.u32(15)), self.u32(8))
        is_cy = self.ueq(self.uand(gy, self.u32(15)), self.u32(8))
        def _w():
            tx = self.ushr(gx, self.u32(4))
            ty = self.ushr(gy, self.u32(4))
            gidx = self.uadd(self.umul(ty, tiles_x), tx)
            self.store_ssbo(grid_var, p_grid_f, gidx, depth_val)
        self.if_block(self.land(is_cx, is_cy), _w)

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
            self.caps + self.ext_imports + self.mem_model + self.entry_points
            + self.exec_modes + self.decorations + self.types_consts + self.globals_sec + fn_words
        )
        return [SPIRV_MAGIC, SPIRV_VERSION_1_0, 0, self.next_id, 0] + body


def milkfrog_ubo_types(b: SpirvBuilder) -> list[int]:
    # 112 scalar 32-bit fields matching FujiVkScene3dMilkFrogParams (448 bytes)
    t = [
        b.t_u32, b.t_u32, b.t_u32, b.t_u32,  # 0..3: width, height, tilesX, tilesY
        b.t_u32, b.t_u32, b.t_u32, b.t_u32,  # 4..7: vertexCount, triCount, hasPose, hasTranslation
        b.t_f32, b.t_f32, b.t_f32, b.t_f32,  # 8..11: halfW, halfH, focal, invFocalScale
        b.t_f32, b.t_f32, b.t_f32, b.t_f32,  # 12..15: ox, oy, oz, cR
        b.t_f32, b.t_f32, b.t_f32, b.t_f32,  # 16..19: sR, yawShiftPx, pitchShiftPx, posXM
        b.t_f32, b.t_f32, b.t_f32, b.t_f32,  # 20..23: posYM, posZM, r00, r01
        b.t_f32, b.t_f32, b.t_f32, b.t_f32,  # 24..27: r02, r10, r11, r12
        b.t_f32, b.t_f32, b.t_f32, b.t_f32,  # 28..31: r20, r21, r22, frogX
        b.t_f32, b.t_f32, b.t_f32, b.t_f32,  # 32..35: frogY, frogZ, cLean, sLean
        b.t_f32, b.t_f32, b.t_f32, b.t_f32,  # 36..39: cRock, sRock, bellySquash, handBounce
        b.t_f32, b.t_f32, b.t_f32, b.t_u32,  # 40..43: kickPhase, mouthVib, arcPulse, orbCount
        b.t_f32, b.t_f32, b.t_f32, b.t_f32,  # 44..47: pedZ, pedTopY, pedBotY, pedRad
    ]
    t.extend([b.t_f32] * (MAX_ORBS * 8))     # 48..111: 8 orbs x 8 floats
    return t


# ---------------------------------------------------------------------------
# Shader 0: milkfrog_vertex_xform (local_size 64, 1, 1)
# ---------------------------------------------------------------------------
def build_milkfrog_vertex_xform() -> list[int]:
    b = SpirvBuilder(64, 1, 1)
    verts_buf, p_verts = b.ssbo_array(b.t_vec4, 16, 0)
    xform_buf, p_xform = b.ssbo_array(b.t_vec4, 16, 1)
    ubo = b.uniform_struct(milkfrog_ubo_types(b), 6)

    vi = b.gid(0)
    v_count = b.load_ubo(ubo, 4, False)

    def body():
        base3 = b.umul(vi, b.u32(3))
        v0 = b.load_ssbo(verts_buf, p_verts, b.t_vec4, base3)
        v1 = b.load_ssbo(verts_buf, p_verts, b.t_vec4, b.uadd(base3, b.u32(1)))
        v2 = b.load_ssbo(verts_buf, p_verts, b.t_vec4, b.uadd(base3, b.u32(2)))

        lx0 = b.ex(v0, 0); ly0 = b.ex(v0, 1); lz0 = b.ex(v0, 2); pid_f = b.ex(v0, 3)
        nx0 = b.ex(v1, 0); ny0 = b.ex(v1, 1); nz0 = b.ex(v1, 2)
        cr = b.ex(v2, 0); cg = b.ex(v2, 1); cb = b.ex(v2, 2)

        half_w = b.load_ubo(ubo, 8, True)
        half_h = b.load_ubo(ubo, 9, True)
        focal = b.load_ubo(ubo, 10, True)
        ox = b.load_ubo(ubo, 12, True); oy = b.load_ubo(ubo, 13, True); oz = b.load_ubo(ubo, 14, True)
        r00 = b.load_ubo(ubo, 22, True); r01 = b.load_ubo(ubo, 23, True); r02 = b.load_ubo(ubo, 24, True)
        r10 = b.load_ubo(ubo, 25, True); r11 = b.load_ubo(ubo, 26, True); r12 = b.load_ubo(ubo, 27, True)
        r20 = b.load_ubo(ubo, 28, True); r21 = b.load_ubo(ubo, 29, True); r22 = b.load_ubo(ubo, 30, True)
        frog_x = b.load_ubo(ubo, 31, True); frog_y = b.load_ubo(ubo, 32, True); frog_z = b.load_ubo(ubo, 33, True)
        c_lean = b.load_ubo(ubo, 34, True); s_lean = b.load_ubo(ubo, 35, True)
        c_rock = b.load_ubo(ubo, 36, True); s_rock = b.load_ubo(ubo, 37, True)
        belly_sq = b.load_ubo(ubo, 38, True); hand_b = b.load_ubo(ubo, 39, True)
        kick_ph = b.load_ubo(ubo, 40, True); mouth_v = b.load_ubo(ubo, 41, True)

        pivot_y = b.f32(-0.455)
        h_feet = b.fmax(b.fsub(ly0, pivot_y), b.f32(0.0))
        dy_b = b.fadd(ly0, b.f32(0.02))
        belly_band = b.fexp(b.fdiv(b.fneg(b.fmul(dy_b, dy_b)), b.f32(0.028)))
        rad_exp = b.fadd(b.f32(1.0), b.fmul(belly_sq, b.fadd(b.f32(0.45), b.fmul(b.f32(0.75), belly_band))))

        v_lx = b.local_f32(b.fmul(lx0, rad_exp))
        v_ly = b.local_f32(b.fadd(pivot_y, b.fmul(h_feet, b.fsub(b.f32(1.0), b.fmul(belly_sq, b.f32(0.28))))))
        v_lz = b.local_f32(b.fmul(lz0, rad_exp))

        pid = b.ftos(b.fadd(pid_f, b.f32(0.5)))

        # pid == 2 (hands & feet)
        def part2():
            cur_ly = b.lf(v_ly)
            is_hand = b.fgt(cur_ly, b.f32(-0.26))
            def do_hand():
                b.store(v_ly, b.fadd(cur_ly, hand_b))
                b.store(v_lz, b.fsub(b.lf(v_lz), b.fmul(belly_sq, b.f32(0.08))))
            def do_foot():
                is_left = b.flt(b.lf(v_lx), b.f32(0.0))
                k_val = b.sel_f(is_left, kick_ph, b.fneg(kick_ph))
                foot_lift = b.fmax(b.f32(0.0), k_val)
                b.store(v_ly, b.fadd(cur_ly, b.fmul(foot_lift, b.f32(0.016))))
            b.if_block(is_hand, do_hand, do_foot)

        # pid == 5 (laughing mouth)
        def part5():
            cur_ly = b.lf(v_ly)
            def do_jaw():
                b.store(v_ly, b.fsub(cur_ly, b.fmul(mouth_v, b.f32(0.008))))
            b.if_block(b.flt(cur_ly, b.f32(0.2215)), do_jaw)

        b.if_block(b.ieq(pid, b.i32(2)), part2)
        b.if_block(b.ieq(pid, b.i32(5)), part5)

        lx = b.lf(v_lx); ly = b.lf(v_ly); lz = b.lf(v_lz)
        py0 = b.fsub(ly, pivot_y)
        ry1 = b.fsub(b.fmul(py0, c_lean), b.fmul(lz, s_lean))
        rz1 = b.fadd(b.fmul(py0, s_lean), b.fmul(lz, c_lean))
        rx2 = b.fsub(b.fmul(lx, c_rock), b.fmul(ry1, s_rock))
        ry2 = b.fadd(b.fmul(lx, s_rock), b.fmul(ry1, c_rock))

        # Actor placement only: turn clockwise 30 degrees from above after local articulation.
        yaw_cos = b.f32(0.8660254); yaw_sin = b.f32(0.5)
        def placed_x(x, z):
            return b.fadd(b.fmul(yaw_cos, x), b.fmul(yaw_sin, z))
        def placed_z(x, z):
            return b.fsub(b.fmul(yaw_cos, z), b.fmul(yaw_sin, x))
        wx = b.fadd(placed_x(rx2, rz1), frog_x)
        wy = b.fadd(b.fadd(ry2, pivot_y), frog_y)
        wz = b.fadd(placed_z(rx2, rz1), frog_z)

        # Rotate normal
        ny1 = b.fsub(b.fmul(ny0, c_lean), b.fmul(nz0, s_lean))
        nz1 = b.fadd(b.fmul(ny0, s_lean), b.fmul(nz0, c_lean))
        nx2 = b.fsub(b.fmul(nx0, c_rock), b.fmul(ny1, s_rock))
        nwx = placed_x(nx2, nz1)
        nwy = b.fadd(b.fmul(nx0, s_rock), b.fmul(ny1, c_rock))
        nwz = placed_z(nx2, nz1)

        vx = b.fsub(wx, ox); vy = b.fsub(wy, oy); vz = b.fsub(wz, oz)
        cam_x = b.fadd(b.fadd(b.fmul(r00, vx), b.fmul(r10, vy)), b.fmul(r20, vz))
        cam_y = b.fadd(b.fadd(b.fmul(r01, vx), b.fmul(r11, vy)), b.fmul(r21, vz))
        cam_z = b.fadd(b.fadd(b.fmul(r02, vx), b.fmul(r12, vy)), b.fmul(r22, vz))

        in_front = b.fgt(cam_z, b.f32(0.15))
        safe_z = b.sel_f(in_front, cam_z, b.f32(1.0))
        inv_z = b.fdiv(b.f32(1.0), safe_z)
        px = b.fadd(half_w, b.fmul(b.fmul(cam_x, inv_z), focal))
        py = b.fsub(half_h, b.fmul(b.fmul(cam_y, inv_z), focal))
        out_inv_z = b.sel_f(in_front, inv_z, b.f32(-1.0))

        # Double-sided matte-gouache 3-point wrap lighting + soft rim + vertical AO
        v_len = b.fmax(b.f32(1e-5), b.fsqrt(b.fadd(b.fadd(b.fmul(vx, vx), b.fmul(vy, vy)), b.fmul(vz, vz))))
        inv_vl = b.fdiv(b.f32(1.0), v_len)
        view_x = b.fmul(b.fneg(vx), inv_vl)
        view_y = b.fmul(b.fneg(vy), inv_vl)
        view_z = b.fmul(b.fneg(vz), inv_vl)

        ndv0 = b.fadd(b.fadd(b.fmul(nwx, view_x), b.fmul(nwy, view_y)), b.fmul(nwz, view_z))
        flip = b.flt(ndv0, b.f32(0.0))
        snx = b.sel_f(flip, b.fneg(nwx), nwx)
        sny = b.sel_f(flip, b.fneg(nwy), nwy)
        snz = b.sel_f(flip, b.fneg(nwz), nwz)
        ndv = b.sel_f(flip, b.fneg(ndv0), ndv0)

        # Normalized key & fill directions matching CPU MilkFrogSceneSource
        k_len = (0.32**2 + 0.66**2 + 0.68**2) ** 0.5
        f_len = (0.70**2 + 0.28**2 + 0.66**2) ** 0.5
        klx = b.f32(-0.32 / k_len); kly = b.f32(0.66 / k_len); klz = b.f32(-0.68 / k_len)
        flx = b.f32(0.70 / f_len); fly = b.f32(0.28 / f_len); flz = b.f32(-0.66 / f_len)

        kd = b.fadd(b.fadd(b.fmul(snx, klx), b.fmul(sny, kly)), b.fmul(snz, klz))
        diff_key = b.fclamp(b.fadd(b.fmul(kd, b.f32(0.62)), b.f32(0.38)), b.f32(0.0), b.f32(1.0))
        fd = b.fadd(b.fadd(b.fmul(snx, flx), b.fmul(sny, fly)), b.fmul(snz, flz))
        diff_fill = b.fmax(b.f32(0.0), fd)

        is_dark_part = b.lor(b.ieq(pid, b.i32(4)), b.ieq(pid, b.i32(5)))
        rim_scale = b.sel_f(is_dark_part, b.f32(0.01), b.f32(0.07))
        om_ndv = b.fsub(b.f32(1.0), b.fclamp(ndv, b.f32(0.0), b.f32(1.0)))
        rim = b.fmul(b.fpow(om_ndv, b.f32(3.5)), rim_scale)

        ao = b.fadd(b.f32(0.84), b.fmul(b.f32(0.16), b.fclamp(b.fdiv(h_feet, b.f32(0.66)), b.f32(0.0), b.f32(1.0))))
        lr = b.fmul(b.fadd(b.fadd(b.f32(0.32), b.fmul(b.f32(0.58), diff_key)), b.fmul(b.f32(0.17), diff_fill)), ao)
        lg = b.fmul(b.fadd(b.fadd(b.f32(0.32), b.fmul(b.f32(0.57), diff_key)), b.fmul(b.f32(0.18), diff_fill)), ao)
        lb = b.fmul(b.fadd(b.fadd(b.f32(0.33), b.fmul(b.f32(0.54), diff_key)), b.fmul(b.f32(0.21), diff_fill)), ao)

        is_pupil = b.ieq(pid, b.i32(4))
        p_shade = b.fadd(b.f32(0.45), b.fmul(b.f32(0.55), diff_key))
        lit_r = b.sel_f(is_pupil, b.fmul(cr, p_shade), b.fclamp(b.fadd(b.fmul(cr, lr), b.fmul(rim, b.f32(0.88))), b.f32(0.0), b.f32(1.15)))
        lit_g = b.sel_f(is_pupil, b.fmul(cg, p_shade), b.fclamp(b.fadd(b.fmul(cg, lg), b.fmul(rim, b.f32(0.94))), b.f32(0.0), b.f32(1.15)))
        lit_b = b.sel_f(is_pupil, b.fmul(cb, p_shade), b.fclamp(b.fadd(b.fmul(cb, lb), b.fmul(rim, b.f32(1.02))), b.f32(0.0), b.f32(1.15)))

        base2 = b.umul(vi, b.u32(2))
        b.store_ssbo(xform_buf, p_xform, base2, b.vec4(px, py, out_inv_z, b.f32(0.0)))
        b.store_ssbo(xform_buf, p_xform, b.uadd(base2, b.u32(1)), b.vec4(lit_r, lit_g, lit_b, b.f32(0.0)))

    b.if_block(b.ult(vi, v_count), body)
    return b.finish()


# ---------------------------------------------------------------------------
# Shader 1: milkfrog_tri_bin (local_size 64, 1, 1)
# ---------------------------------------------------------------------------
def build_milkfrog_tri_bin() -> list[int]:
    b = SpirvBuilder(64, 1, 1)
    idx_buf, p_idx = b.ssbo_array(b.t_u32, 4, 0)
    xform_buf, p_xform = b.ssbo_array(b.t_vec4, 16, 1)
    stri_buf, p_stri = b.ssbo_array(b.t_vec4, 16, 2)
    tcnt_buf, p_tcnt = b.ssbo_array(b.t_u32, 4, 3)
    ttris_buf, p_ttris = b.ssbo_array(b.t_u32, 4, 4)
    ubo = b.uniform_struct(milkfrog_ubo_types(b), 6)

    ti = b.gid(0)
    t_count = b.load_ubo(ubo, 5, False)

    def body():
        w = b.load_ubo(ubo, 0, False)
        h = b.load_ubo(ubo, 1, False)
        tiles_x = b.load_ubo(ubo, 2, False)
        tiles_y = b.load_ubo(ubo, 3, False)

        word_count = b.uadd(b.ushr(t_count, b.u32(5)), b.sel_u(b.ult(b.u32(0), b.uand(t_count, b.u32(31))), b.u32(1), b.u32(0)))
        sparse_offset = b.umul(b.umul(tiles_x, tiles_y), word_count)
        word = b.ushr(ti, b.u32(5))
        bit = b.op(OP_SHIFT_LEFT_LOGICAL, b.t_u32, b.u32(1), b.uand(ti, b.u32(31)))

        b3 = b.umul(ti, b.u32(3))
        i0 = b.load_ssbo(idx_buf, p_idx, b.t_u32, b3)
        i1_raw = b.load_ssbo(idx_buf, p_idx, b.t_u32, b.uadd(b3, b.u32(1)))
        i2_raw = b.load_ssbo(idx_buf, p_idx, b.t_u32, b.uadd(b3, b.u32(2)))

        p0 = b.load_ssbo(xform_buf, p_xform, b.t_vec4, b.umul(i0, b.u32(2)))
        p1_r = b.load_ssbo(xform_buf, p_xform, b.t_vec4, b.umul(i1_raw, b.u32(2)))
        p2_r = b.load_ssbo(xform_buf, p_xform, b.t_vec4, b.umul(i2_raw, b.u32(2)))

        iz0 = b.ex(p0, 2); iz1_r = b.ex(p1_r, 2); iz2_r = b.ex(p2_r, 2)
        all_front = b.land(b.fgt(iz0, b.f32(0.0)), b.land(b.fgt(iz1_r, b.f32(0.0)), b.fgt(iz2_r, b.f32(0.0))))

        def visible_tri():
            x0 = b.ex(p0, 0); y0 = b.ex(p0, 1)
            x1_r = b.ex(p1_r, 0); y1_r = b.ex(p1_r, 1)
            x2_r = b.ex(p2_r, 0); y2_r = b.ex(p2_r, 1)

            area_raw = b.fsub(b.fmul(b.fsub(x1_r, x0), b.fsub(y2_r, y0)), b.fmul(b.fsub(y1_r, y0), b.fsub(x2_r, x0)))
            abs_area = b.fabs(area_raw)

            def non_degen():
                neg = b.flt(area_raw, b.f32(0.0))
                i1 = b.sel_u(neg, i2_raw, i1_raw)
                i2 = b.sel_u(neg, i1_raw, i2_raw)
                x1 = b.sel_f(neg, x2_r, x1_r); y1 = b.sel_f(neg, y2_r, y1_r); iz1 = b.sel_f(neg, iz2_r, iz1_r)
                x2 = b.sel_f(neg, x1_r, x2_r); y2 = b.sel_f(neg, y1_r, y2_r); iz2 = b.sel_f(neg, iz1_r, iz2_r)
                inv_area = b.fdiv(b.f32(1.0), abs_area)

                mn_xf = b.fmin(x0, b.fmin(x1, x2))
                mx_xf = b.fmax(x0, b.fmax(x1, x2))
                mn_yf = b.fmin(y0, b.fmin(y1, y2))
                mx_yf = b.fmax(y0, b.fmax(y1, y2))

                w_m1 = b.isub(b.u2i(w), b.i32(1))
                h_m1 = b.isub(b.u2i(h), b.i32(1))
                mn_x = b.ftos(b.ffloor(mn_xf))
                mx_x = b.ftos(b.ffloor(mx_xf))
                mn_y = b.ftos(b.ffloor(mn_yf))
                mx_y = b.ftos(b.ffloor(mx_yf))

                on_screen = b.land(
                    b.land(b.ige(mx_x, b.i32(0)), b.ile(mn_x, w_m1)),
                    b.land(b.ige(mx_y, b.i32(0)), b.ile(mn_y, h_m1)),
                )

                def emit_and_bin():
                    c0 = b.load_ssbo(xform_buf, p_xform, b.t_vec4, b.uadd(b.umul(i0, b.u32(2)), b.u32(1)))
                    c1 = b.load_ssbo(xform_buf, p_xform, b.t_vec4, b.uadd(b.umul(i1, b.u32(2)), b.u32(1)))
                    c2 = b.load_ssbo(xform_buf, p_xform, b.t_vec4, b.uadd(b.umul(i2, b.u32(2)), b.u32(1)))

                    sb = b.umul(ti, b.u32(6))
                    b.store_ssbo(stri_buf, p_stri, sb, b.vec4(x0, y0, iz0, inv_area))
                    b.store_ssbo(stri_buf, p_stri, b.uadd(sb, b.u32(1)), b.vec4(x1, y1, iz1, b.f32(0.0)))
                    b.store_ssbo(stri_buf, p_stri, b.uadd(sb, b.u32(2)), b.vec4(x2, y2, iz2, b.f32(0.0)))
                    b.store_ssbo(stri_buf, p_stri, b.uadd(sb, b.u32(3)), b.vec4(b.ex(c0, 0), b.ex(c0, 1), b.ex(c0, 2), b.ex(c1, 0)))
                    b.store_ssbo(stri_buf, p_stri, b.uadd(sb, b.u32(4)), b.vec4(b.ex(c1, 1), b.ex(c1, 2), b.ex(c2, 0), b.ex(c2, 1)))
                    b.store_ssbo(stri_buf, p_stri, b.uadd(sb, b.u32(5)), b.vec4(b.ex(c2, 2), b.f32(0.0), b.f32(0.0), b.f32(0.0)))

                    tx_m1 = b.isub(b.u2i(tiles_x), b.i32(1))
                    ty_m1 = b.isub(b.u2i(tiles_y), b.i32(1))
                    tx0 = b.sclamp(b.ishr(b.smax(mn_x, b.i32(0)), b.i32(4)), b.i32(0), tx_m1)
                    tx1 = b.sclamp(b.ishr(b.smin(mx_x, w_m1), b.i32(4)), b.i32(0), tx_m1)
                    ty0 = b.sclamp(b.ishr(b.smax(mn_y, b.i32(0)), b.i32(4)), b.i32(0), ty_m1)
                    ty1 = b.sclamp(b.ishr(b.smin(mx_y, h_m1), b.i32(4)), b.i32(0), ty_m1)

                    def row_loop(ty):
                        row_off = b.umul(b.i2u(ty), tiles_x)
                        def col_loop(tx):
                            tid = b.uadd(row_off, b.i2u(tx))
                            membership = b.uadd(b.umul(tid, word_count), word)
                            before = b.atomic_or_ssbo_u32(ttris_buf, p_ttris, membership, bit)
                            def first_in_word():
                                slot = b.atomic_add_ssbo_u32(tcnt_buf, p_tcnt, tid, b.u32(1))
                                def store_word():
                                    dst = b.uadd(sparse_offset, b.uadd(b.umul(tid, b.u32(SPARSE_TILE_WORDS)), slot))
                                    b.store_ssbo(ttris_buf, p_ttris, dst, word)
                                b.if_block(b.ult(slot, b.u32(SPARSE_TILE_WORDS)), store_word)
                            b.if_block(b.ieq(before, b.u32(0)), first_in_word)
                        b.for_i32_inclusive(tx0, tx1, col_loop)
                    b.for_i32_inclusive(ty0, ty1, row_loop)

                b.if_block(on_screen, emit_and_bin)
            b.if_block(b.fge(abs_area, b.f32(1e-4)), non_degen)
        b.if_block(all_front, visible_tri)

    b.if_block(b.ult(ti, t_count), body)
    return b.finish()


# ---------------------------------------------------------------------------
# Shader 2: milkfrog_tile_raster (local_size 8, 8, 1)
# ---------------------------------------------------------------------------
def build_milkfrog_tile_raster() -> list[int]:
    b = SpirvBuilder(8, 8, 1)
    stage_buf, p_stage = b.ssbo_array(b.t_vec4, 16, 0)
    stri_buf, p_stri = b.ssbo_array(b.t_vec4, 16, 1)
    tcnt_buf, p_tcnt = b.ssbo_array(b.t_u32, 4, 2)
    ttris_buf, p_ttris = b.ssbo_array(b.t_u32, 4, 3)
    dst_buf, p_dst = b.ssbo_array(b.t_vec4, 16, 4)
    grid_buf, p_grid = b.ssbo_array(b.t_f32, 4, 5)
    ubo = b.uniform_struct(milkfrog_ubo_types(b), 6)

    gx = b.gid(0)
    gy = b.gid(1)
    w = b.load_ubo(ubo, 0, False)
    h = b.load_ubo(ubo, 1, False)

    def body():
        tiles_x = b.load_ubo(ubo, 2, False)
        has_pose = b.load_ubo(ubo, 6, False)
        has_trans = b.load_ubo(ubo, 7, False)
        half_w = b.load_ubo(ubo, 8, True)
        half_h = b.load_ubo(ubo, 9, True)
        focal = b.load_ubo(ubo, 10, True)
        inv_fs = b.load_ubo(ubo, 11, True)
        ox = b.load_ubo(ubo, 12, True); oy = b.load_ubo(ubo, 13, True); oz = b.load_ubo(ubo, 14, True)
        c_r = b.load_ubo(ubo, 15, True); s_r = b.load_ubo(ubo, 16, True)
        yaw_sh = b.load_ubo(ubo, 17, True); pitch_sh = b.load_ubo(ubo, 18, True)
        pos_xm = b.load_ubo(ubo, 19, True); pos_ym = b.load_ubo(ubo, 20, True); pos_zm = b.load_ubo(ubo, 21, True)
        r00 = b.load_ubo(ubo, 22, True); r01 = b.load_ubo(ubo, 23, True); r02 = b.load_ubo(ubo, 24, True)
        r10 = b.load_ubo(ubo, 25, True); r11 = b.load_ubo(ubo, 26, True); r12 = b.load_ubo(ubo, 27, True)
        r20 = b.load_ubo(ubo, 28, True); r21 = b.load_ubo(ubo, 29, True); r22 = b.load_ubo(ubo, 30, True)
        frog_x = b.load_ubo(ubo, 31, True); frog_z = b.load_ubo(ubo, 33, True)
        orb_count = b.load_ubo(ubo, 43, False)
        ped_z = b.load_ubo(ubo, 44, True); ped_top_y = b.load_ubo(ubo, 45, True)
        ped_bot_y = b.load_ubo(ubo, 46, True); ped_rad = b.load_ubo(ubo, 47, True)

        idx = b.uadd(b.umul(gy, w), gx)
        fx_u = b.utof(gx); fy_u = b.utof(gy)

        out_r = b.local_f32(b.f32(0.014))
        out_g = b.local_f32(b.f32(0.020))
        out_b = b.local_f32(b.f32(0.044))
        out_d = b.local_f32(b.f32(10.5))

        # Step 1: Copy or 6-DoF reproject static stage
        def copy_stage():
            st = b.load_ssbo(stage_buf, p_stage, b.t_vec4, idx)
            b.store(out_r, b.ex(st, 0))
            b.store(out_g, b.ex(st, 1))
            b.store(out_b, b.ex(st, 2))
            b.store(out_d, b.ex(st, 3))

        def reproj_stage():
            max_xi = b.isub(b.u2i(w), b.i32(1))
            max_yi = b.isub(b.u2i(h), b.i32(1))
            max_xf = b.stof(max_xi); max_yf = b.stof(max_yi)
            dx_s = b.fmul(b.fsub(fx_u, half_w), inv_fs)
            dy_s = b.fmul(b.fsub(fy_u, half_h), inv_fs)
            rrx = b.fadd(b.fmul(dx_s, c_r), b.fmul(dy_s, s_r))
            rry = b.fadd(b.fmul(b.fneg(dx_s), s_r), b.fmul(dy_s, c_r))
            v_sx = b.local_f32(b.fadd(b.fadd(half_w, rrx), b.fmul(yaw_sh, inv_fs)))
            v_sy = b.local_f32(b.fsub(b.fadd(half_h, rry), b.fmul(pitch_sh, inv_fs)))
            def apply_trans():
                px_i = b.sclamp(b.ftos(b.ffloor(b.fadd(b.lf(v_sx), b.f32(0.5)))), b.i32(0), max_xi)
                py_i = b.sclamp(b.ftos(b.ffloor(b.fadd(b.lf(v_sy), b.f32(0.5)))), b.i32(0), max_yi)
                p_idx = b.uadd(b.umul(b.i2u(py_i), w), b.i2u(px_i))
                z0 = b.fmax(b.ex(b.load_ssbo(stage_buf, p_stage, b.t_vec4, p_idx), 3), b.f32(0.25))
                b.store(v_sx, b.fadd(b.lf(v_sx), b.fmul(b.fdiv(pos_xm, z0), focal)))
                b.store(v_sy, b.fsub(b.lf(v_sy), b.fmul(b.fdiv(pos_ym, z0), focal)))
            b.if_block(b.une(has_trans, b.u32(0)), apply_trans)

            sx = b.lf(v_sx); sy = b.lf(v_sy)
            in_r = b.land(b.land(b.fge(sx, b.f32(0.0)), b.fle(sx, max_xf)), b.land(b.fge(sy, b.f32(0.0)), b.fle(sy, max_yf)))
            def sample_bilerp():
                x0 = b.sclamp(b.ftos(b.ffloor(sx)), b.i32(0), max_xi)
                y0 = b.sclamp(b.ftos(b.ffloor(sy)), b.i32(0), max_yi)
                x1 = b.smin(b.iadd(x0, b.i32(1)), max_xi)
                y1 = b.smin(b.iadd(y0, b.i32(1)), max_yi)
                fx = b.fsub(sx, b.stof(x0)); fy = b.fsub(sy, b.stof(y0))
                om_fy = b.fsub(b.f32(1.0), fy)
                r0 = b.umul(b.i2u(y0), w); r1 = b.umul(b.i2u(y1), w)
                p00 = b.load_ssbo(stage_buf, p_stage, b.t_vec4, b.uadd(r0, b.i2u(x0)))
                p10 = b.load_ssbo(stage_buf, p_stage, b.t_vec4, b.uadd(r0, b.i2u(x1)))
                p01 = b.load_ssbo(stage_buf, p_stage, b.t_vec4, b.uadd(r1, b.i2u(x0)))
                p11 = b.load_ssbo(stage_buf, p_stage, b.t_vec4, b.uadd(r1, b.i2u(x1)))
                for ch, dst_v in ((0, out_r), (1, out_g), (2, out_b)):
                    c00 = b.ex(p00, ch); c10 = b.ex(p10, ch); c01 = b.ex(p01, ch); c11 = b.ex(p11, ch)
                    top = b.fadd(c00, b.fmul(b.fsub(c10, c00), fx))
                    bot = b.fadd(c01, b.fmul(b.fsub(c11, c01), fx))
                    b.store(dst_v, b.fadd(b.fmul(top, om_fy), b.fmul(bot, fy)))
                b.store(out_d, b.fmax(b.fsub(b.ex(p00, 3), pos_zm), b.f32(0.10)))
            b.if_block(in_r, sample_bilerp)

        b.if_block(b.ueq(has_pose, b.u32(0)), copy_stage, reproj_stage)

        # Step 2: Analytic spotlight turntable pedestal + angled -15..+15cm focus ruler
        ry_loc = b.fdiv(b.fsub(half_h, fy_u), focal)
        need_ped = b.lor(b.une(has_pose, b.u32(0)), b.flt(ry_loc, b.f32(-0.10)))
        def do_pedestal():
            rx_loc = b.fdiv(b.fsub(fx_u, half_w), focal)
            inv_l = b.fdiv(b.f32(1.0), b.fsqrt(b.fadd(b.fadd(b.fmul(rx_loc, rx_loc), b.fmul(ry_loc, ry_loc)), b.f32(1.0))))
            ldx = b.fmul(rx_loc, inv_l); ldy = b.fmul(ry_loc, inv_l); ldz = inv_l
            dx = b.fadd(b.fadd(b.fmul(r00, ldx), b.fmul(r01, ldy)), b.fmul(r02, ldz))
            dy = b.fadd(b.fadd(b.fmul(r10, ldx), b.fmul(r11, ldy)), b.fmul(r12, ldz))
            dz = b.fadd(b.fadd(b.fmul(r20, ldx), b.fmul(r21, ldy)), b.fmul(r22, ldz))
            ped_rad_sq = b.fmul(ped_rad, ped_rad)

            def hit_down_planes():
                # Pedestal top disc
                t_ped = b.fdiv(b.fsub(ped_top_y, oy), dy)
                cam_z = b.fmul(t_ped, ldz)
                def check_ped_top():
                    px = b.fadd(ox, b.fmul(dx, t_ped))
                    pz = b.fadd(oz, b.fmul(dz, t_ped))
                    dpx = b.fsub(px, b.fmul(frog_x, b.f32(0.25)))
                    dpz = b.fsub(pz, ped_z)
                    pr2 = b.fadd(b.fmul(dpx, dpx), b.fmul(dpz, dpz))
                    def shade_ped_top():
                        cdx = b.fsub(px, frog_x); cdz = b.fsub(pz, frog_z)
                        cd2 = b.fadd(b.fmul(cdx, cdx), b.fmul(cdz, cdz))
                        ao = b.fsub(b.f32(1.0), b.fmul(b.f32(0.62), b.fexp(b.fdiv(b.fneg(cd2), b.f32(0.048)))))
                        rim = b.sel_f(b.fgt(pr2, b.fmul(ped_rad_sq, b.f32(0.84))), b.f32(0.42), b.f32(0.20))
                        ring = b.sel_f(b.flt(b.fabs(b.fsub(b.fsqrt(pr2), b.fmul(ped_rad, b.f32(0.90)))), b.f32(0.012)), b.f32(0.55), rim)
                        s = b.fmul(ring, ao)
                        b.store(out_r, b.fmul(s, b.f32(0.98)))
                        b.store(out_g, s)
                        b.store(out_b, b.fmul(s, b.f32(1.08)))
                        b.store(out_d, cam_z)
                    b.if_block(b.fle(pr2, ped_rad_sq), shade_ped_top)
                b.if_block(b.land(b.fgt(t_ped, b.f32(0.15)), b.flt(cam_z, b.lf(out_d))), check_ped_top)

                # Focus ruler card
                ruler_y = b.fadd(ped_top_y, b.f32(0.004))
                t_rul = b.fdiv(b.fsub(ruler_y, oy), dy)
                rz = b.fmul(t_rul, ldz)
                def check_ruler():
                    px = b.fadd(ox, b.fmul(dx, t_rul))
                    pz = b.fadd(oz, b.fmul(dz, t_rul))
                    in_r = b.land(
                        b.land(b.fge(px, b.f32(0.29)), b.fle(px, b.f32(0.42))),
                        b.land(b.fge(pz, b.fsub(ped_z, b.f32(0.16))), b.fle(pz, b.fadd(ped_z, b.f32(0.16)))),
                    )
                    def shade_ruler():
                        ux = b.fdiv(b.fsub(px, b.f32(0.29)), b.f32(0.13))
                        cm = b.fmul(b.fsub(pz, ped_z), b.f32(100.0))
                        cm_frac = b.fabs(b.fsub(cm, b.ffloor(b.fadd(cm, b.f32(0.5)))))
                        is_zero = b.flt(b.fabs(cm), b.f32(0.35))
                        cm5 = b.fabs(b.fsub(cm, b.fmul(b.ffloor(b.fdiv(b.fadd(cm, b.f32(2.5)), b.f32(5.0))), b.f32(5.0))))
                        is_maj5 = b.flt(cm5, b.f32(0.35))
                        tick_w = b.sel_f(is_maj5, b.f32(0.78), b.f32(0.48))
                        is_tick = b.land(b.flt(cm_frac, b.f32(0.14)), b.flt(ux, tick_w))
                        is_bdr = b.lor(b.flt(ux, b.f32(0.05)), b.fgt(ux, b.f32(0.95)))
                        is_blk = b.lor(is_tick, is_bdr)
                        rr = b.sel_f(is_zero, b.f32(0.86), b.sel_f(is_blk, b.f32(0.03), b.f32(0.82)))
                        rg = b.sel_f(is_zero, b.f32(0.12), b.sel_f(is_blk, b.f32(0.03), b.f32(0.81)))
                        rb = b.sel_f(is_zero, b.f32(0.10), b.sel_f(is_blk, b.f32(0.04), b.f32(0.76)))
                        b.store(out_r, rr); b.store(out_g, rg); b.store(out_b, rb); b.store(out_d, rz)
                    b.if_block(in_r, shade_ruler)
                b.if_block(b.land(b.fgt(t_rul, b.f32(0.15)), b.flt(rz, b.lf(out_d))), check_ruler)
            b.if_block(b.flt(dy, b.f32(-1e-5)), hit_down_planes)

            # Cylindrical pedestal rim
            ped_a = b.fadd(b.fmul(dx, dx), b.fmul(dz, dz))
            def check_cyl():
                pox = b.fsub(ox, b.fmul(frog_x, b.f32(0.25)))
                poz = b.fsub(oz, ped_z)
                ped_b = b.fadd(b.fmul(pox, dx), b.fmul(poz, dz))
                ped_c = b.fsub(b.fadd(b.fmul(pox, pox), b.fmul(poz, poz)), ped_rad_sq)
                disc = b.fsub(b.fmul(ped_b, ped_b), b.fmul(ped_a, ped_c))
                def hit_cyl():
                    t_side = b.fdiv(b.fsub(b.fneg(ped_b), b.fsqrt(disc)), ped_a)
                    py = b.fadd(oy, b.fmul(dy, t_side))
                    cz = b.fmul(t_side, ldz)
                    ok = b.land(
                        b.land(b.fgt(t_side, b.f32(0.15)), b.flt(cz, b.lf(out_d))),
                        b.land(b.fge(py, ped_bot_y), b.fle(py, ped_top_y)),
                    )
                    def shade_cyl():
                        sh = b.fadd(b.f32(0.08), b.fmul(b.f32(0.08), b.fdiv(b.fsub(py, ped_bot_y), b.fsub(ped_top_y, ped_bot_y))))
                        b.store(out_r, b.fmul(sh, b.f32(0.95)))
                        b.store(out_g, sh)
                        b.store(out_b, b.fmul(sh, b.f32(1.15)))
                        b.store(out_d, cz)
                    b.if_block(ok, shade_cyl)
                b.if_block(b.fge(disc, b.f32(0.0)), hit_cyl)
            b.if_block(b.fgt(ped_a, b.f32(1e-6)), check_cyl)
        b.if_block(need_ped, do_pedestal)

        # Step 3: Exact Z-buffered triangle rasterization for this pixel's 16x16 tile!
        tx = b.ushr(gx, b.u32(4))
        ty = b.ushr(gy, b.u32(4))
        tid = b.uadd(b.umul(ty, tiles_x), tx)
        active_words = b.load_ssbo(tcnt_buf, p_tcnt, b.t_u32, tid)
        ntris = b.load_ubo(ubo, 5, False)
        word_count = b.uadd(b.ushr(ntris, b.u32(5)), b.sel_u(b.ult(b.u32(0), b.uand(ntris, b.u32(31))), b.u32(1), b.u32(0)))
        membership = b.umul(tid, word_count)
        sparse_offset = b.umul(b.umul(tiles_x, b.load_ubo(ubo, 3, False)), word_count)
        sparse = b.uadd(sparse_offset, b.umul(tid, b.u32(SPARSE_TILE_WORDS)))
        dense = b.ult(b.u32(SPARSE_TILE_WORDS), active_words)
        px_c = b.fadd(fx_u, b.f32(0.5))
        py_c = b.fadd(fy_u, b.f32(0.5))
        winner = b.local_u32(b.u32(0xFFFFFFFF))

        def shade_triangle(t_idx):
            sb = b.umul(t_idx, b.u32(6))
            s0 = b.load_ssbo(stri_buf, p_stri, b.t_vec4, sb)
            s1 = b.load_ssbo(stri_buf, p_stri, b.t_vec4, b.uadd(sb, b.u32(1)))
            s2 = b.load_ssbo(stri_buf, p_stri, b.t_vec4, b.uadd(sb, b.u32(2)))

            x0 = b.ex(s0, 0); y0 = b.ex(s0, 1); iz0 = b.ex(s0, 2); inv_a = b.ex(s0, 3)
            x1 = b.ex(s1, 0); y1 = b.ex(s1, 1); iz1 = b.ex(s1, 2)
            x2 = b.ex(s2, 0); y2 = b.ex(s2, 1); iz2 = b.ex(s2, 2)

            e0 = b.fsub(b.fmul(b.fsub(x2, x1), b.fsub(py_c, y1)), b.fmul(b.fsub(y2, y1), b.fsub(px_c, x1)))
            e1 = b.fsub(b.fmul(b.fsub(x0, x2), b.fsub(py_c, y2)), b.fmul(b.fsub(y0, y2), b.fsub(px_c, x2)))
            e2 = b.fsub(b.fmul(b.fsub(x1, x0), b.fsub(py_c, y0)), b.fmul(b.fsub(y1, y0), b.fsub(px_c, x0)))

            inside = b.land(b.fge(e0, b.f32(-1e-4)), b.land(b.fge(e1, b.f32(-1e-4)), b.fge(e2, b.f32(-1e-4))))
            def shade_tri():
                w0 = b.fmul(e0, inv_a); w1 = b.fmul(e1, inv_a); w2 = b.fmul(e2, inv_a)
                inv_z = b.fadd(b.fadd(b.fmul(w0, iz0), b.fmul(w1, iz1)), b.fmul(w2, iz2))
                def z_test():
                    z = b.fdiv(b.f32(1.0), inv_z)
                    def z_pass():
                        s3 = b.load_ssbo(stri_buf, p_stri, b.t_vec4, b.uadd(sb, b.u32(3)))
                        s4 = b.load_ssbo(stri_buf, p_stri, b.t_vec4, b.uadd(sb, b.u32(4)))
                        s5 = b.load_ssbo(stri_buf, p_stri, b.t_vec4, b.uadd(sb, b.u32(5)))
                        r0 = b.ex(s3, 0); g0 = b.ex(s3, 1); b0 = b.ex(s3, 2); r1 = b.ex(s3, 3)
                        g1 = b.ex(s4, 0); b1 = b.ex(s4, 1); r2 = b.ex(s4, 2); g2 = b.ex(s4, 3)
                        b2 = b.ex(s5, 0)
                        b.store(winner, t_idx)
                        b.store(out_d, z)
                        b.store(out_r, b.fadd(b.fadd(b.fmul(w0, r0), b.fmul(w1, r1)), b.fmul(w2, r2)))
                        b.store(out_g, b.fadd(b.fadd(b.fmul(w0, g0), b.fmul(w1, g1)), b.fmul(w2, g2)))
                        b.store(out_b, b.fadd(b.fadd(b.fmul(w0, b0), b.fmul(w1, b1)), b.fmul(w2, b2)))
                    # Match source triangle order on exact depth ties. The stage/pedestal wins a tie
                    # before the first mesh hit, as in the sequential CPU reference.
                    prior = b.lu(winner)
                    same_depth = b.op(OP_FORD_EQUAL, b.t_bool, z, b.lf(out_d))
                    tied_mesh = b.land(same_depth, b.land(b.ult(prior, b.u32(0xFFFFFFFF)), b.ult(t_idx, prior)))
                    b.if_block(b.lor(b.flt(z, b.lf(out_d)), tied_mesh), z_pass)
                b.if_block(b.fgt(inv_z, b.f32(1e-5)), z_test)
            b.if_block(inside, shade_tri)

        def shade_word(k):
            # Dense tiles scan their whole bitmap. Sparse tiles touch only the occupied words.
            sparse_idx = b.uadd(sparse, b.umin(k, b.u32(SPARSE_TILE_WORDS - 1)))
            word = b.sel_u(dense, k, b.load_ssbo(ttris_buf, p_ttris, b.t_u32, sparse_idx))
            bits = b.load_ssbo(ttris_buf, p_ttris, b.t_u32, b.uadd(membership, word))
            remaining = b.local_u32(bits)
            base = b.umul(word, b.u32(32))
            def shade_set_bit(_):
                v = b.lu(remaining)
                bit = b.i2u(b.glsl_op(GLSL_FIND_I_LSB, b.t_i32, v))
                b.store(remaining, b.uand(v, b.usub(v, b.u32(1))))
                shade_triangle(b.uadd(base, bit))
            b.for_u32(b.u32(0), b.op(OP_BIT_COUNT, b.t_u32, bits), shade_set_bit)
        b.for_u32(b.u32(0), b.sel_u(dense, word_count, active_words), shade_word)

        # Step 4: Up to 8 depth-aware HDR orbs (4 flying laugh tears + 4 near-foreground HDR bokeh bulbs)
        for oi in range(MAX_ORBS):
            def do_orb(idx_o=oi):
                ob = 48 + idx_o * 8
                ocx = b.load_ubo(ubo, ob, True)
                ocy = b.load_ubo(ubo, ob + 1, True)
                orad = b.load_ubo(ubo, ob + 2, True)
                ohrad = b.load_ubo(ubo, ob + 3, True)
                ocr = b.load_ubo(ubo, ob + 4, True)
                ocg = b.load_ubo(ubo, ob + 5, True)
                ocb = b.load_ubo(ubo, ob + 6, True)
                od = b.load_ubo(ubo, ob + 7, True)

                ddx = b.fsub(fx_u, ocx); ddy = b.fsub(fy_u, ocy)
                dist = b.fsqrt(b.fadd(b.fmul(ddx, ddx), b.fmul(ddy, ddy)))
                in_orb = b.land(b.fle(dist, ohrad), b.fle(od, b.lf(out_d)))
                def shade_orb():
                    in_core = b.fle(dist, orad)
                    def do_core():
                        norm = b.fdiv(dist, b.fmax(orad, b.f32(1.0)))
                        u = b.fdiv(b.fsub(norm, b.f32(0.45)), b.f32(0.55))
                        fall_in = b.fsub(b.f32(1.0), b.fmul(b.f32(0.10), b.fdiv(norm, b.f32(0.45))))
                        fall_out = b.fmul(b.f32(0.90), b.fsub(b.f32(1.0), b.fmul(b.fmul(u, u), b.f32(0.65))))
                        fall = b.sel_f(b.fle(norm, b.f32(0.45)), fall_in, fall_out)
                        b.store(out_r, b.fmax(b.lf(out_r), b.fmul(ocr, fall)))
                        b.store(out_g, b.fmax(b.lf(out_g), b.fmul(ocg, fall)))
                        b.store(out_b, b.fmax(b.lf(out_b), b.fmul(ocb, fall)))
                        b.store(out_d, od)
                    def do_halo():
                        u = b.fdiv(b.fsub(dist, orad), b.fmax(b.fsub(ohrad, orad), b.f32(1.0)))
                        omu = b.fsub(b.f32(1.0), u)
                        halo = b.fmul(b.f32(0.20), b.fmul(omu, omu))
                        b.store(out_r, b.fmin(b.fmax(b.lf(out_r), ocr), b.fadd(b.lf(out_r), b.fmul(ocr, halo))))
                        b.store(out_g, b.fmin(b.fmax(b.lf(out_g), ocg), b.fadd(b.lf(out_g), b.fmul(ocg, halo))))
                        b.store(out_b, b.fmin(b.fmax(b.lf(out_b), ocb), b.fadd(b.lf(out_b), b.fmul(ocb, halo))))
                    b.if_block(in_core, do_core, do_halo)
                b.if_block(in_orb, shade_orb)
            b.if_block(b.ult(b.u32(oi), orb_count), do_orb)

        final_d = b.lf(out_d)
        b.store_ssbo(dst_buf, p_dst, idx, b.vec4(b.lf(out_r), b.lf(out_g), b.lf(out_b), final_d))
        b.emit_depth_grid_write(grid_buf, p_grid, gx, gy, tiles_x, final_d)

    b.if_block(b.land(b.ult(gx, w), b.ult(gy, h)), body)
    return b.finish()


# ---------------------------------------------------------------------------
# Shader 3: scene3d_qr_ball (local_size 8, 8, 1)
# ---------------------------------------------------------------------------
def build_scene3d_qr_ball() -> list[int]:
    b = SpirvBuilder(8, 8, 1)
    qr_buf, p_qr = b.ssbo_array(b.t_u32, 4, 0)
    dst_buf, p_dst = b.ssbo_array(b.t_vec4, 16, 4)
    grid_buf, p_grid = b.ssbo_array(b.t_f32, 4, 5)
    ubo = b.uniform_struct([
        b.t_u32, b.t_u32, b.t_u32, b.t_u32,  # 0..3: w, h, tilesX, tilesY
        b.t_u32, b.t_u32, b.t_u32, b.t_f32,  # 4..7: qrModules, quietZone, totalQrModules, rotationTurns
        b.t_f32, b.t_f32, b.t_f32, b.t_f32,  # 8..11: halfW, halfH, focal, ox
        b.t_f32, b.t_f32, b.t_f32, b.t_f32,  # 12..15: oy, oz, r00, r01
        b.t_f32, b.t_f32, b.t_f32, b.t_f32,  # 16..19: r02, r10, r11, r12
        b.t_f32, b.t_f32, b.t_f32, b.t_f32,  # 20..23: r20, r21, r22, wallZ
    ], 6)

    gx = b.gid(0); gy = b.gid(1)
    w = b.load_ubo(ubo, 0, False); h = b.load_ubo(ubo, 1, False)

    def body():
        tiles_x = b.load_ubo(ubo, 2, False)
        qr_mods = b.u2i(b.load_ubo(ubo, 4, False))
        quiet_z = b.u2i(b.load_ubo(ubo, 5, False))
        tot_qr = b.u2i(b.load_ubo(ubo, 6, False))
        half_w = b.load_ubo(ubo, 8, True); half_h = b.load_ubo(ubo, 9, True)
        focal = b.load_ubo(ubo, 10, True)
        ox = b.load_ubo(ubo, 11, True); oy = b.load_ubo(ubo, 12, True); oz = b.load_ubo(ubo, 13, True)
        r00 = b.load_ubo(ubo, 14, True); r01 = b.load_ubo(ubo, 15, True); r02 = b.load_ubo(ubo, 16, True)
        r10 = b.load_ubo(ubo, 17, True); r11 = b.load_ubo(ubo, 18, True); r12 = b.load_ubo(ubo, 19, True)
        r20 = b.load_ubo(ubo, 20, True); r21 = b.load_ubo(ubo, 21, True); r22 = b.load_ubo(ubo, 22, True)
        wall_z = b.load_ubo(ubo, 23, True)

        rx = b.fdiv(b.fsub(b.fadd(b.utof(gx), b.f32(0.5)), half_w), focal)
        ry = b.fdiv(b.fsub(half_h, b.fadd(b.utof(gy), b.f32(0.5))), focal)
        inv_l = b.fdiv(b.f32(1.0), b.fsqrt(b.fadd(b.fadd(b.fmul(rx, rx), b.fmul(ry, ry)), b.f32(1.0))))
        ldx = b.fmul(rx, inv_l); ldy = b.fmul(ry, inv_l); ldz = inv_l
        dx = b.fadd(b.fadd(b.fmul(r00, ldx), b.fmul(r01, ldy)), b.fmul(r02, ldz))
        dy = b.fadd(b.fadd(b.fmul(r10, ldx), b.fmul(r11, ldy)), b.fmul(r12, ldz))
        dz = b.fadd(b.fadd(b.fmul(r20, ldx), b.fmul(r21, ldy)), b.fmul(r22, ldz))

        best_t = b.local_f32(b.f32(1e9))
        hit_k = b.local_i32(b.i32(0))
        hit_x = b.local_f32(ox); hit_y = b.local_f32(oy); hit_z = b.local_f32(wall_z)

        room_hx = b.f32(4.2); floor_y = b.f32(-1.75); ceil_y = b.f32(2.35)
        sx = b.f32(-0.28); sy = b.f32(-0.08); sz = b.f32(1.05); srad = b.f32(0.22); srad2 = b.f32(0.22 * 0.22)
        ptop = b.f32(-0.30); pbot = b.f32(-0.355)
        pminx = b.f32(-0.56); pmaxx = b.f32(0.0); pminz = b.f32(0.80); pmaxz = b.f32(1.32)
        cminx = b.f32(-0.365); cmaxx = b.f32(-0.195); cminz = b.f32(0.965); cmaxz = b.f32(1.135)

        def try_hit(cond, t_val, hx, hy, hz, kind_i):
            def _set():
                b.store(best_t, t_val); b.store(hit_x, hx); b.store(hit_y, hy); b.store(hit_z, hz); b.store(hit_k, b.i32(kind_i))
            b.if_block(b.land(cond, b.land(b.fgt(t_val, b.f32(0.05)), b.flt(t_val, b.lf(best_t)))), _set)

        # Back & rear wall
        def chk_back():
            tb = b.fdiv(b.fsub(wall_z, oz), dz)
            try_hit(b.fgt(tb, b.f32(0.05)), tb, b.fadd(ox, b.fmul(dx, tb)), b.fadd(oy, b.fmul(dy, tb)), wall_z, 0)
        def chk_rear():
            tr = b.fdiv(b.fsub(b.f32(-3.0), oz), dz)
            try_hit(b.fgt(tr, b.f32(0.05)), tr, b.fadd(ox, b.fmul(dx, tr)), b.fadd(oy, b.fmul(dy, tr)), b.f32(-3.0), 3)
        b.if_block(b.fgt(dz, b.f32(1e-6)), chk_back, lambda: b.if_block(b.flt(dz, b.f32(-1e-6)), chk_rear))

        # Floor & ceiling
        def chk_floor():
            tf = b.fdiv(b.fsub(floor_y, oy), dy)
            xf = b.fadd(ox, b.fmul(dx, tf)); zf = b.fadd(oz, b.fmul(dz, tf))
            ok = b.land(b.fle(b.fabs(xf), b.f32(4.201)), b.land(b.fge(zf, b.f32(-3.05)), b.fle(zf, b.fadd(wall_z, b.f32(0.05)))))
            try_hit(ok, tf, xf, floor_y, zf, 1)
        def chk_ceil():
            tc = b.fdiv(b.fsub(ceil_y, oy), dy)
            xc = b.fadd(ox, b.fmul(dx, tc)); zc = b.fadd(oz, b.fmul(dz, tc))
            ok = b.land(b.fle(b.fabs(xc), b.f32(4.201)), b.land(b.fge(zc, b.f32(-3.05)), b.fle(zc, b.fadd(wall_z, b.f32(0.05)))))
            try_hit(ok, tc, xc, ceil_y, zc, 2)
        b.if_block(b.flt(dy, b.f32(-1e-6)), chk_floor, lambda: b.if_block(b.fgt(dy, b.f32(1e-6)), chk_ceil))

        # Left & right wall
        def chk_left():
            tl = b.fdiv(b.fsub(b.fneg(room_hx), ox), dx)
            yl = b.fadd(oy, b.fmul(dy, tl)); zl = b.fadd(oz, b.fmul(dz, tl))
            ok = b.land(b.land(b.fge(yl, floor_y), b.fle(yl, ceil_y)), b.land(b.fge(zl, b.f32(-3.05)), b.fle(zl, b.fadd(wall_z, b.f32(0.05)))))
            try_hit(ok, tl, b.fneg(room_hx), yl, zl, 3)
        def chk_right():
            tr = b.fdiv(b.fsub(room_hx, ox), dx)
            yr = b.fadd(oy, b.fmul(dy, tr)); zr = b.fadd(oz, b.fmul(dz, tr))
            ok = b.land(b.land(b.fge(yr, floor_y), b.fle(yr, ceil_y)), b.land(b.fge(zr, b.f32(-3.05)), b.fle(zr, b.fadd(wall_z, b.f32(0.05)))))
            try_hit(ok, tr, room_hx, yr, zr, 4)
        b.if_block(b.flt(dx, b.f32(-1e-6)), chk_left, lambda: b.if_block(b.fgt(dx, b.f32(1e-6)), chk_right))

        # Plinth top
        def chk_ptop():
            tt = b.fdiv(b.fsub(ptop, oy), dy)
            xt = b.fadd(ox, b.fmul(dx, tt)); zt = b.fadd(oz, b.fmul(dz, tt))
            ok = b.land(b.land(b.fge(zt, pminz), b.fle(zt, pmaxz)), b.land(b.fge(xt, pminx), b.fle(xt, pmaxx)))
            try_hit(ok, tt, xt, ptop, zt, 5)
        b.if_block(b.flt(dy, b.f32(-1e-6)), chk_ptop)

        # Plinth front + column front
        def chk_pfront():
            tf = b.fdiv(b.fsub(pminz, oz), dz)
            xf = b.fadd(ox, b.fmul(dx, tf)); yf = b.fadd(oy, b.fmul(dy, tf))
            try_hit(b.land(b.land(b.fge(xf, pminx), b.fle(xf, pmaxx)), b.land(b.fge(yf, pbot), b.fle(yf, ptop))), tf, xf, yf, pminz, 6)
            tc = b.fdiv(b.fsub(cminz, oz), dz)
            xc = b.fadd(ox, b.fmul(dx, tc)); yc = b.fadd(oy, b.fmul(dy, tc))
            try_hit(b.land(b.land(b.fge(xc, cminx), b.fle(xc, cmaxx)), b.land(b.fge(yc, floor_y), b.fle(yc, pbot))), tc, xc, yc, cminz, 7)
        b.if_block(b.fgt(dz, b.f32(1e-6)), chk_pfront)

        # Column sides
        def chk_cside_l():
            ts = b.fdiv(b.fsub(cmaxx, ox), dx)
            zs = b.fadd(oz, b.fmul(dz, ts)); ys = b.fadd(oy, b.fmul(dy, ts))
            try_hit(b.land(b.land(b.fge(zs, cminz), b.fle(zs, cmaxz)), b.land(b.fge(ys, floor_y), b.fle(ys, pbot))), ts, cmaxx, ys, zs, 7)
        def chk_cside_r():
            ts = b.fdiv(b.fsub(cminx, ox), dx)
            zs = b.fadd(oz, b.fmul(dz, ts)); ys = b.fadd(oy, b.fmul(dy, ts))
            try_hit(b.land(b.land(b.fge(zs, cminz), b.fle(zs, cmaxz)), b.land(b.fge(ys, floor_y), b.fle(ys, pbot))), ts, cminx, ys, zs, 7)
        b.if_block(b.flt(dx, b.f32(-1e-6)), chk_cside_l, lambda: b.if_block(b.fgt(dx, b.f32(1e-6)), chk_cside_r))

        # Foreground calibration sphere
        ocx = b.fsub(sx, ox); ocy = b.fsub(sy, oy); ocz = b.fsub(sz, oz)
        oc_d = b.fsub(b.fadd(b.fadd(b.fmul(ocx, ocx), b.fmul(ocy, ocy)), b.fmul(ocz, ocz)), srad2)
        bproj = b.fadd(b.fadd(b.fmul(dx, ocx), b.fmul(dy, ocy)), b.fmul(dz, ocz))
        disc = b.fsub(b.fmul(bproj, bproj), oc_d)
        def chk_sphere():
            th = b.fsub(bproj, b.fsqrt(disc))
            try_hit(b.fgt(th, b.f32(0.05)), th, b.fadd(ox, b.fmul(dx, th)), b.fadd(oy, b.fmul(dy, th)), b.fadd(oz, b.fmul(dz, th)), 8)
        b.if_block(b.fge(disc, b.f32(0.0)), chk_sphere)

        bt = b.lf(best_t)
        cam_d = b.sel_f(b.flt(bt, b.f32(1e8)), b.fmax(b.fmul(bt, ldz), b.f32(0.05)), wall_z)
        hx = b.lf(hit_x); hy = b.lf(hit_y); hz = b.lf(hit_z); hk = b.li(hit_k)

        out_r = b.local_f32(b.f32(0.0))
        out_g = b.local_f32(b.f32(0.0))
        out_b = b.local_f32(b.f32(0.0))

        # Shading by hitKind
        def shade_sphere():
            nx = b.fdiv(b.fsub(hx, sx), srad); ny = b.fdiv(b.fsub(hy, sy), srad); nz = b.fdiv(b.fsub(hz, sz), srad)
            phi = b.fasin(ny)
            theta = b.fatan2(nx, b.fneg(nz))
            longitude = b.fadd(b.fdiv(theta, b.f32(6.28318530718)), b.f32(0.5))
            ug = b.fmul(b.fsub(longitude, b.load_ubo(ubo, 7, True)), b.f32(32.0))
            vg = b.fmul(b.fadd(b.fdiv(phi, b.f32(3.14159265359)), b.f32(0.5)), b.f32(16.0))
            cu = b.ftos(b.ffloor(ug)); cv = b.ftos(b.ffloor(vg))
            fu = b.fsub(ug, b.stof(cu)); fv = b.fsub(vg, b.stof(cv))
            chk_dark = b.ieq(b.iand(b.iadd(cu, cv), b.i32(1)), b.i32(0))
            fine = b.lor(b.lor(b.flt(fu, b.f32(0.08)), b.fgt(fu, b.f32(0.92))), b.lor(b.flt(fv, b.f32(0.08)), b.fgt(fv, b.f32(0.92))))
            aphi = b.fabs(phi)
            eq_band = b.flt(aphi, b.f32(0.075))
            eq_tick = b.land(b.flt(aphi, b.f32(0.045)), b.ieq(b.iand(b.ftos(b.ffloor(b.fmul(ug, b.f32(2.0)))), b.i32(1)), b.i32(0)))

            br = b.sel_f(eq_tick, b.f32(0.96), b.sel_f(eq_band, b.f32(0.78), b.sel_f(fine, b.f32(0.05), b.sel_f(chk_dark, b.f32(0.10), b.f32(0.86)))))
            bg = b.sel_f(eq_tick, b.f32(0.96), b.sel_f(eq_band, b.f32(0.09), b.sel_f(fine, b.f32(0.06), b.sel_f(chk_dark, b.f32(0.13), b.f32(0.85)))))
            bb = b.sel_f(eq_tick, b.f32(0.94), b.sel_f(eq_band, b.f32(0.07), b.sel_f(fine, b.f32(0.08), b.sel_f(chk_dark, b.f32(0.19), b.f32(0.82)))))

            l1l = (0.55**2 + 0.70**2 + 0.45**2) ** 0.5
            l2l = (0.65**2 + 0.25**2 + 0.55**2) ** 0.5
            l1x = b.f32(-0.55 / l1l); l1y = b.f32(0.70 / l1l); l1z = b.f32(-0.45 / l1l)
            l2x = b.f32(0.65 / l2l); l2y = b.f32(0.25 / l2l); l2z = b.f32(-0.55 / l2l)

            d1 = b.fmax(b.f32(0.0), b.fadd(b.fadd(b.fmul(nx, l1x), b.fmul(ny, l1y)), b.fmul(nz, l1z)))
            d2 = b.fmax(b.f32(0.0), b.fadd(b.fadd(b.fmul(nx, l2x), b.fmul(ny, l2y)), b.fmul(nz, l2z)))
            vx = b.fneg(dx); vy = b.fneg(dy); vz = b.fneg(dz)
            hvx = b.fadd(l1x, vx); hvy = b.fadd(l1y, vy); hvz = b.fadd(l1z, vz)
            invh = b.fdiv(b.f32(1.0), b.fmax(b.f32(1e-6), b.fsqrt(b.fadd(b.fadd(b.fmul(hvx, hvx), b.fmul(hvy, hvy)), b.fmul(hvz, hvz)))))
            ndh = b.fmax(b.f32(0.0), b.fmul(b.fadd(b.fadd(b.fmul(nx, hvx), b.fmul(ny, hvy)), b.fmul(nz, hvz)), invh))
            spec = b.fmul(b.fpow(ndh, b.f32(52.0)), b.f32(0.48))
            ndv = b.fmax(b.f32(0.0), b.fadd(b.fadd(b.fmul(nx, vx), b.fmul(ny, vy)), b.fmul(nz, vz)))
            om = b.fsub(b.f32(1.0), ndv)
            rim = b.fmul(b.fmul(b.fmul(om, om), om), b.f32(0.14))
            lr = b.fadd(b.fadd(b.f32(0.12), b.fmul(b.f32(0.82), d1)), b.fmul(b.f32(0.18), d2))
            lg = b.fadd(b.fadd(b.f32(0.12), b.fmul(b.f32(0.81), d1)), b.fmul(b.f32(0.21), d2))
            lb = b.fadd(b.fadd(b.f32(0.13), b.fmul(b.f32(0.78), d1)), b.fmul(b.f32(0.26), d2))
            b.store(out_r, b.fclamp(b.fadd(b.fadd(b.fmul(br, lr), spec), b.fmul(rim, b.f32(0.85))), b.f32(0.0), b.f32(1.0)))
            b.store(out_g, b.fclamp(b.fadd(b.fadd(b.fmul(bg, lg), spec), b.fmul(rim, b.f32(0.92))), b.f32(0.0), b.f32(1.0)))
            b.store(out_b, b.fclamp(b.fadd(b.fadd(b.fmul(bb, lb), spec), rim), b.f32(0.0), b.f32(1.0)))

        def shade_back_wall():
            in_qr = b.land(b.land(b.fge(hx, b.f32(0.15)), b.fle(hx, b.f32(3.05))), b.land(b.fge(hy, b.f32(-1.27)), b.fle(hy, b.f32(1.63))))
            def do_qr():
                u = b.fdiv(b.fsub(hx, b.f32(0.15)), b.f32(2.90))
                v = b.fdiv(b.fsub(b.f32(1.63), hy), b.f32(2.90))
                tf = b.stof(tot_qr)
                tm1 = b.isub(tot_qr, b.i32(1))
                mc = b.sclamp(b.ftos(b.ffloor(b.fmul(u, tf))), b.i32(0), tm1)
                mr = b.sclamp(b.ftos(b.ffloor(b.fmul(v, tf))), b.i32(0), tm1)
                ri = b.isub(mr, quiet_z); ci = b.isub(mc, quiet_z)
                in_m = b.land(b.land(b.ige(ri, b.i32(0)), b.ilt(ri, qr_mods)), b.land(b.ige(ci, b.i32(0)), b.ilt(ci, qr_mods)))
                v_dark = b.local_u32(b.u32(0))
                def check_bit():
                    rw = b.load_ssbo(qr_buf, p_qr, b.t_u32, b.i2u(ri))
                    bit = b.uand(b.ushr(rw, b.i2u(ci)), b.u32(1))
                    b.store(v_dark, bit)
                b.if_block(in_m, check_bit)
                lum = b.sel_f(b.une(b.lu(v_dark), b.u32(0)), b.f32(0.02), b.f32(0.94))
                b.store(out_r, lum); b.store(out_g, lum); b.store(out_b, lum)

            def do_bezel_or_wall():
                in_bez = b.land(b.land(b.fge(hx, b.f32(0.05)), b.fle(hx, b.f32(3.15))), b.land(b.fge(hy, b.f32(-1.43)), b.fle(hy, b.f32(1.73))))
                def do_bez():
                    in_bar = b.land(b.land(b.flt(hy, b.f32(-1.305)), b.fgt(hy, b.f32(-1.395))), b.land(b.fge(hx, b.f32(0.30)), b.fle(hx, b.f32(2.90))))
                    bp = b.ftos(b.fmul(b.fsub(hx, b.f32(0.15)), b.f32(14.0)))
                    tick = b.land(b.ieq(b.iand(bp, b.i32(1)), b.i32(0)), b.fgt(hx, b.f32(2.00)))
                    lum = b.sel_f(tick, b.f32(0.72), b.f32(0.055))
                    b.store(out_r, b.sel_f(in_bar, lum, b.f32(0.045)))
                    b.store(out_g, b.sel_f(in_bar, lum, b.f32(0.050)))
                    b.store(out_b, b.sel_f(in_bar, lum, b.f32(0.060)))
                def do_wall():
                    dxw = b.fsub(hx, b.f32(0.4)); dyw = b.fsub(hy, b.f32(0.3))
                    r2 = b.fadd(b.fmul(b.fmul(dxw, dxw), b.f32(0.035)), b.fmul(b.fmul(dyw, dyw), b.f32(0.07)))
                    wl = b.fclamp(b.fsub(b.f32(0.28), r2), b.f32(0.11), b.f32(0.30))
                    sm = b.fsub(b.fmod(b.fadd(hx, b.f32(12.0)), b.f32(1.2)), b.f32(0.6))
                    pf = b.sel_f(b.fgt(b.fabs(sm), b.f32(0.585)), b.f32(0.84), b.f32(1.0))
                    wb = b.fmul(wl, pf)
                    lr = b.local_f32(b.f32(0.0)); lg = b.local_f32(b.f32(0.0)); lb = b.local_f32(b.f32(0.0))
                    wall_lamps = [
                        (-3.20, 1.85, 0.13, 6.8, 5.1, 2.1),
                        (-1.75, 2.02, 0.12, 6.2, 6.0, 5.4),
                        (-0.35, 2.04, 0.11, 7.0, 4.3, 1.6),
                        (1.55, 2.06, 0.11, 5.4, 6.2, 7.2),
                        (3.68, 1.78, 0.12, 6.8, 5.0, 2.0),
                        (-3.45, 0.45, 0.12, 2.4, 5.8, 7.0),
                    ]
                    for lx, ly, rad, cr, cg, cb in wall_lamps:
                        ldx2 = b.fsub(hx, b.f32(lx)); ldy2 = b.fsub(hy, b.f32(ly))
                        d2 = b.fadd(b.fmul(ldx2, ldx2), b.fmul(ldy2, ldy2))
                        hrad = rad * 2.1
                        def hit_l(rad_=rad, hrad_=hrad, cr_=cr, cg_=cg, cb_=cb, d2_=d2):
                            d = b.fsqrt(d2_)
                            u1 = b.fdiv(b.fsub(d, b.f32(rad_ * 0.42)), b.f32(rad_ * 0.58))
                            w_mid = b.fsub(b.f32(1.0), b.fmul(b.f32(0.55), b.fmul(u1, u1)))
                            u2 = b.fdiv(b.fsub(d, b.f32(rad_)), b.f32(hrad_ - rad_))
                            om2 = b.fsub(b.f32(1.0), u2)
                            w_hal = b.fmul(b.f32(0.18), b.fmul(om2, om2))
                            wl_w = b.sel_f(b.fle(d, b.f32(rad_ * 0.42)), b.f32(1.0), b.sel_f(b.fle(d, b.f32(rad_)), w_mid, w_hal))
                            b.store(lr, b.fmax(b.lf(lr), b.fmul(b.f32(cr_), wl_w)))
                            b.store(lg, b.fmax(b.lf(lg), b.fmul(b.f32(cg_), wl_w)))
                            b.store(lb, b.fmax(b.lf(lb), b.fmul(b.f32(cb_), wl_w)))
                        b.if_block(b.fle(d2, b.f32(hrad * hrad)), hit_l)
                    b.store(out_r, b.fclamp(b.fadd(b.fmul(wb, b.f32(0.98)), b.lf(lr)), b.f32(0.0), b.f32(12.0)))
                    b.store(out_g, b.fclamp(b.fadd(wb, b.lf(lg)), b.f32(0.0), b.f32(12.0)))
                    b.store(out_b, b.fclamp(b.fadd(b.fmul(wb, b.f32(1.05)), b.lf(lb)), b.f32(0.0), b.f32(12.0)))
                b.if_block(in_bez, do_bez, do_wall)
            b.if_block(in_qr, do_qr, do_bezel_or_wall)

        def shade_other():
            def do_plinth_top():
                ddx = b.fsub(hx, sx); ddz = b.fsub(hz, sz)
                dsq = b.fadd(b.fmul(ddx, ddx), b.fmul(ddz, ddz))
                ao = b.fsub(b.f32(1.0), b.fmul(b.f32(0.82), b.fexp(b.fdiv(b.fneg(dsq), b.f32(0.22 * 0.22 * 0.42)))))
                ed = b.lor(
                    b.lor(b.flt(b.fabs(b.fsub(hx, pminx)), b.f32(0.012)), b.flt(b.fabs(b.fsub(hx, pmaxx)), b.f32(0.012))),
                    b.lor(b.flt(b.fabs(b.fsub(hz, pminz)), b.f32(0.012)), b.flt(b.fabs(b.fsub(hz, pmaxz)), b.f32(0.012))),
                )
                eh = b.sel_f(ed, b.f32(0.28), b.f32(0.19))
                b.store(out_r, b.fmul(eh, ao))
                b.store(out_g, b.fmul(b.fadd(eh, b.f32(0.01)), ao))
                b.store(out_b, b.fmul(b.fadd(eh, b.f32(0.025)), ao))

            def do_plinth_front():
                grad = b.fclamp(b.fdiv(b.fsub(hy, pbot), b.fsub(ptop, pbot)), b.f32(0.0), b.f32(1.0))
                v = b.fadd(b.f32(0.07), b.fmul(b.f32(0.05), grad))
                b.store(out_r, v); b.store(out_g, b.fmul(v, b.f32(1.04))); b.store(out_b, b.fmul(v, b.f32(1.12)))

            def do_col():
                sh = b.sel_f(b.flt(b.fabs(b.fsub(hz, cminz)), b.f32(1e-3)), b.f32(0.065), b.f32(0.042))
                b.store(out_r, sh); b.store(out_g, b.fmul(sh, b.f32(1.05))); b.store(out_b, b.fmul(sh, b.f32(1.14)))

            def do_floor():
                hx2 = b.fmul(hx, b.f32(2.0)); hz2 = b.fmul(hz, b.f32(2.0))
                gxf = b.fabs(b.fsub(hx2, b.ffloor(b.fadd(hx2, b.f32(0.5)))))
                gzf = b.fabs(b.fsub(hz2, b.ffloor(b.fadd(hz2, b.f32(0.5)))))
                gl = b.lor(b.flt(gxf, b.f32(0.032)), b.flt(gzf, b.f32(0.032)))
                par = b.ieq(b.iand(b.iadd(b.ftos(b.ffloor(hx2)), b.ftos(b.ffloor(hz2))), b.i32(1)), b.i32(0))
                df = b.fclamp(b.fsub(b.f32(1.15), b.fmul(b.fsub(hz, b.f32(1.0)), b.f32(0.09))), b.f32(0.45), b.f32(1.0))
                base = b.fmul(b.sel_f(gl, b.f32(0.32), b.sel_f(par, b.f32(0.145), b.f32(0.115))), df)
                b.store(out_r, b.fmul(base, b.f32(0.97))); b.store(out_g, base); b.store(out_b, b.fmul(base, b.f32(1.06)))

            def do_ceil():
                hz85 = b.fmul(hz, b.f32(0.85))
                fl85 = b.ffloor(hz85)
                ls = b.land(
                    b.land(b.flt(b.fabs(hx), b.f32(1.6)), b.ieq(b.iand(b.ftos(fl85), b.i32(1)), b.i32(0))),
                    b.flt(b.fabs(b.fsub(b.fsub(hz85, fl85), b.f32(0.5))), b.f32(0.22)),
                )
                fade = b.fclamp(b.fsub(b.f32(0.22), b.fmul(hz, b.f32(0.018))), b.f32(0.08), b.f32(0.20))
                b.store(out_r, b.sel_f(ls, b.f32(0.72), b.fmul(fade, b.f32(0.96))))
                b.store(out_g, b.sel_f(ls, b.f32(0.74), b.fmul(fade, b.f32(0.98))))
                b.store(out_b, b.sel_f(ls, b.f32(0.78), b.fmul(fade, b.f32(1.04))))
                for sx_c, sz_c, rad, cr, cg, cb in [(-1.65, 4.75, 0.14, 6.4, 5.8, 4.6), (1.75, 4.75, 0.14, 5.8, 6.2, 6.8)]:
                    sdx = b.fsub(hx, b.f32(sx_c)); sdz = b.fsub(hz, b.f32(sz_c))
                    d2 = b.fadd(b.fmul(sdx, sdx), b.fmul(sdz, sdz))
                    hrad = rad * 2.0
                    def hit_sp(rad_=rad, hrad_=hrad, cr_=cr, cg_=cg, cb_=cb, d2_=d2):
                        d = b.fsqrt(d2_)
                        u1 = b.fdiv(b.fsub(d, b.f32(rad_ * 0.45)), b.f32(rad_ * 0.55))
                        wm = b.fsub(b.f32(1.0), b.fmul(b.f32(0.55), b.fmul(u1, u1)))
                        u2 = b.fdiv(b.fsub(d, b.f32(rad_)), b.f32(hrad_ - rad_))
                        om2 = b.fsub(b.f32(1.0), u2)
                        wh = b.fmul(b.f32(0.18), b.fmul(om2, om2))
                        ws = b.sel_f(b.fle(d, b.f32(rad_ * 0.45)), b.f32(1.0), b.sel_f(b.fle(d, b.f32(rad_)), wm, wh))
                        b.store(out_r, b.fclamp(b.fadd(b.lf(out_r), b.fmul(b.f32(cr_), ws)), b.f32(0.0), b.f32(12.0)))
                        b.store(out_g, b.fclamp(b.fadd(b.lf(out_g), b.fmul(b.f32(cg_), ws)), b.f32(0.0), b.f32(12.0)))
                        b.store(out_b, b.fclamp(b.fadd(b.lf(out_b), b.fmul(b.f32(cb_), ws)), b.f32(0.0), b.f32(12.0)))
                    b.if_block(b.fle(d2, b.f32(hrad * hrad)), hit_sp)

            def do_side():
                df = b.fclamp(b.fsub(b.f32(1.10), b.fmul(b.fsub(hz, b.f32(1.0)), b.f32(0.085))), b.f32(0.45), b.f32(1.0))
                dado = b.flt(b.fabs(b.fadd(hy, b.f32(0.65))), b.f32(0.045))
                rib = b.fgt(b.fabs(b.fsub(b.fmod(b.fadd(hz, b.f32(10.0)), b.f32(1.0)), b.f32(0.5))), b.f32(0.48))
                base = b.fmul(b.sel_f(dado, b.f32(0.08), b.sel_f(rib, b.f32(0.14), b.sel_f(b.flt(hy, b.f32(-0.65)), b.f32(0.16), b.f32(0.23)))), df)
                sbias = b.sel_f(b.ieq(hk, b.i32(3)), b.f32(0.92), b.f32(1.04))
                v = b.fmul(base, sbias)
                b.store(out_r, b.fmul(v, b.f32(0.98))); b.store(out_g, v); b.store(out_b, b.fmul(v, b.f32(1.06)))

            b.if_block(b.ieq(hk, b.i32(5)), do_plinth_top, lambda:
                b.if_block(b.ieq(hk, b.i32(6)), do_plinth_front, lambda:
                    b.if_block(b.ieq(hk, b.i32(7)), do_col, lambda:
                        b.if_block(b.ieq(hk, b.i32(1)), do_floor, lambda:
                            b.if_block(b.ieq(hk, b.i32(2)), do_ceil, do_side)))))

        b.if_block(b.ieq(hk, b.i32(8)), shade_sphere, lambda:
            b.if_block(b.ieq(hk, b.i32(0)), shade_back_wall, shade_other))

        idx = b.uadd(b.umul(gy, w), gx)
        b.store_ssbo(dst_buf, p_dst, idx, b.vec4(b.lf(out_r), b.lf(out_g), b.lf(out_b), cam_d))
        b.emit_depth_grid_write(grid_buf, p_grid, gx, gy, tiles_x, cam_d)

    b.if_block(b.land(b.ult(gx, w), b.ult(gy, h)), body)
    return b.finish()


# ---------------------------------------------------------------------------
# Shader 4: scene3d_mona_lisa (local_size 8, 8, 1)
# ---------------------------------------------------------------------------
def build_scene3d_mona_lisa() -> list[int]:
    b = SpirvBuilder(8, 8, 1)
    fg_buf, p_fg = b.ssbo_array(b.t_vec4, 16, 0)
    bg_buf, p_bg = b.ssbo_array(b.t_vec4, 16, 1)
    dst_buf, p_dst = b.ssbo_array(b.t_vec4, 16, 4)
    grid_buf, p_grid = b.ssbo_array(b.t_f32, 4, 5)
    ubo = b.uniform_struct([
        b.t_u32, b.t_u32, b.t_u32, b.t_u32,  # 0..3: dstW, dstH, srcW, srcH
        b.t_u32, b.t_u32, b.t_u32, b.t_u32,  # 4..7: tilesX, tilesY, res0, res1
        b.t_f32, b.t_f32, b.t_f32, b.t_f32,  # 8..11: bgShiftPx, bgScaleX, bgScaleY, invBgZoom
        b.t_f32, b.t_f32, b.t_f32, b.t_f32,  # 12..15: bgCx, bgCy, dstPivotX, dstPivotY
        b.t_f32, b.t_f32, b.t_f32, b.t_f32,  # 16..19: srcPivotX, srcPivotY, stepXX, stepXY
        b.t_f32, b.t_f32, b.t_f32, b.t_f32,  # 20..23: stepYX, stepYY, subjectDepthM, backdropDepthM
    ], 6)

    gx = b.gid(0); gy = b.gid(1)
    dw = b.load_ubo(ubo, 0, False); dh = b.load_ubo(ubo, 1, False)

    def body():
        sw = b.load_ubo(ubo, 2, False); sh = b.load_ubo(ubo, 3, False)
        tiles_x = b.load_ubo(ubo, 4, False)
        bg_sh = b.load_ubo(ubo, 8, True); bg_sx = b.load_ubo(ubo, 9, True)
        bg_sy = b.load_ubo(ubo, 10, True); inv_bgz = b.load_ubo(ubo, 11, True)
        bg_cx = b.load_ubo(ubo, 12, True); bg_cy = b.load_ubo(ubo, 13, True)
        dp_x = b.load_ubo(ubo, 14, True); dp_y = b.load_ubo(ubo, 15, True)
        sp_x = b.load_ubo(ubo, 16, True); sp_y = b.load_ubo(ubo, 17, True)
        s_xx = b.load_ubo(ubo, 18, True); s_xy = b.load_ubo(ubo, 19, True)
        s_yx = b.load_ubo(ubo, 20, True); s_yy = b.load_ubo(ubo, 21, True)
        subj_d = b.load_ubo(ubo, 22, True); bg_d = b.load_ubo(ubo, 23, True)

        sw_i = b.u2i(sw); sh_i = b.u2i(sh)
        sw_m1 = b.isub(sw_i, b.i32(1)); sh_m1 = b.isub(sh_i, b.i32(1))
        fx = b.utof(gx); fy = b.utof(gy)

        bx_f = b.fsub(b.fadd(b.fmul(b.fsub(b.fmul(fx, bg_sx), bg_cx), inv_bgz), bg_cx), bg_sh)
        by_f = b.fadd(b.fmul(b.fsub(b.fmul(fy, bg_sy), bg_cy), inv_bgz), bg_cy)
        bxi = b.sclamp(b.ftos(bx_f), b.i32(0), sw_m1)
        byi = b.sclamp(b.ftos(by_f), b.i32(0), sh_m1)
        bg_px = b.load_ssbo(bg_buf, p_bg, b.t_vec4, b.uadd(b.umul(b.i2u(byi), sw), b.i2u(bxi)))

        out_r = b.local_f32(b.ex(bg_px, 0))
        out_g = b.local_f32(b.ex(bg_px, 1))
        out_b = b.local_f32(b.ex(bg_px, 2))
        out_d = b.local_f32(bg_d)

        dx = b.fsub(fx, dp_x); dy = b.fsub(fy, dp_y)
        u = b.fadd(b.fadd(sp_x, b.fmul(dy, s_xy)), b.fmul(dx, s_xx))
        v = b.fadd(b.fadd(sp_y, b.fmul(dy, s_yy)), b.fmul(dx, s_yx))

        def sample_fg():
            ui = b.ftos(u); vi = b.ftos(v)
            def in_fg():
                fu = b.fsub(u, b.stof(ui)); fv = b.fsub(v, b.stof(vi))
                s00 = b.uadd(b.umul(b.i2u(vi), sw), b.i2u(ui))
                s10 = b.uadd(s00, b.u32(1))
                s01 = b.uadd(s00, sw)
                s11 = b.uadd(s01, b.u32(1))
                p00 = b.load_ssbo(fg_buf, p_fg, b.t_vec4, s00)
                p10 = b.load_ssbo(fg_buf, p_fg, b.t_vec4, s10)
                p01 = b.load_ssbo(fg_buf, p_fg, b.t_vec4, s01)
                p11 = b.load_ssbo(fg_buf, p_fg, b.t_vec4, s11)
                a00 = b.ex(p00, 3); a10 = b.ex(p10, 3); a01 = b.ex(p01, 3); a11 = b.ex(p11, 3)
                a0 = b.fadd(a00, b.fmul(b.fsub(a10, a00), fu))
                a1 = b.fadd(a01, b.fmul(b.fsub(a11, a01), fu))
                alpha = b.fclamp(b.fadd(a0, b.fmul(b.fsub(a1, a0), fv)), b.f32(0.0), b.f32(1.0))
                def blend_fg():
                    inv_a = b.fsub(b.f32(1.0), alpha)
                    for ch, ov in ((0, out_r), (1, out_g), (2, out_b)):
                        c00 = b.ex(p00, ch); c10 = b.ex(p10, ch); c01 = b.ex(p01, ch); c11 = b.ex(p11, ch)
                        t = b.fadd(c00, b.fmul(b.fsub(c10, c00), fu))
                        bt = b.fadd(c01, b.fmul(b.fsub(c11, c01), fu))
                        fc = b.fadd(t, b.fmul(b.fsub(bt, t), fv))
                        b.store(ov, b.fadd(b.fmul(fc, alpha), b.fmul(b.lf(ov), inv_a)))
                    def set_d():
                        do_v = b.ex(b.load_ssbo(bg_buf, p_bg, b.t_vec4, s00), 3)
                        b.store(out_d, b.fmax(b.fadd(subj_d, do_v), b.f32(0.5)))
                    b.if_block(b.fge(alpha, b.f32(0.45)), set_d)
                b.if_block(b.fgt(alpha, b.f32(0.005)), blend_fg)
            b.if_block(b.land(b.ilt(ui, sw_m1), b.ilt(vi, sh_m1)), in_fg)

        b.if_block(b.land(b.fge(u, b.f32(0.0)), b.fge(v, b.f32(0.0))), sample_fg)

        idx = b.uadd(b.umul(gy, dw), gx)
        fd = b.lf(out_d)
        b.store_ssbo(dst_buf, p_dst, idx, b.vec4(
            b.fclamp(b.lf(out_r), b.f32(0.0), b.f32(1.0)),
            b.fclamp(b.lf(out_g), b.f32(0.0), b.f32(1.0)),
            b.fclamp(b.lf(out_b), b.f32(0.0), b.f32(1.0)),
            fd,
        ))
        b.emit_depth_grid_write(grid_buf, p_grid, gx, gy, tiles_x, fd)

    b.if_block(b.land(b.ult(gx, dw), b.ult(gy, dh)), body)
    return b.finish()


# ---------------------------------------------------------------------------
# Shader 5: scene3d_layered (local_size 8, 8, 1)
# ---------------------------------------------------------------------------
def build_scene3d_layered() -> list[int]:
    b = SpirvBuilder(8, 8, 1)
    src_buf, p_src = b.ssbo_array(b.t_vec4, 16, 0)
    dst_buf, p_dst = b.ssbo_array(b.t_vec4, 16, 4)
    grid_buf, p_grid = b.ssbo_array(b.t_f32, 4, 5)
    ubo = b.uniform_struct([
        b.t_u32, b.t_u32, b.t_u32, b.t_u32,  # 0..3: w, h, tilesX, tilesY
        b.t_u32, b.t_u32, b.t_u32, b.t_u32,  # 4..7: hasPose, hasTranslation, res0, res1
        b.t_f32, b.t_f32, b.t_f32, b.t_f32,  # 8..11: halfW, halfH, invFocalScale, focal
        b.t_f32, b.t_f32, b.t_f32, b.t_f32,  # 12..15: cosR, sinR, yawShiftPx, pitchShiftPx
        b.t_f32, b.t_f32, b.t_f32, b.t_f32,  # 16..19: posXM, posYM, posZM, bgDepthM
    ], 6)

    gx = b.gid(0); gy = b.gid(1)
    w = b.load_ubo(ubo, 0, False); h = b.load_ubo(ubo, 1, False)

    def body():
        tiles_x = b.load_ubo(ubo, 2, False)
        has_pose = b.load_ubo(ubo, 4, False); has_trans = b.load_ubo(ubo, 5, False)
        half_w = b.load_ubo(ubo, 8, True); half_h = b.load_ubo(ubo, 9, True)
        inv_fs = b.load_ubo(ubo, 10, True); focal = b.load_ubo(ubo, 11, True)
        cos_r = b.load_ubo(ubo, 12, True); sin_r = b.load_ubo(ubo, 13, True)
        yaw_sh = b.load_ubo(ubo, 14, True); pitch_sh = b.load_ubo(ubo, 15, True)
        pos_xm = b.load_ubo(ubo, 16, True); pos_ym = b.load_ubo(ubo, 17, True)
        pos_zm = b.load_ubo(ubo, 18, True); bg_d = b.load_ubo(ubo, 19, True)

        idx = b.uadd(b.umul(gy, w), gx)
        out_r = b.local_f32(b.f32(0.015))
        out_g = b.local_f32(b.f32(0.018))
        out_b = b.local_f32(b.f32(0.025))
        out_d = b.local_f32(bg_d)

        def passthrough():
            px = b.load_ssbo(src_buf, p_src, b.t_vec4, idx)
            b.store(out_r, b.ex(px, 0)); b.store(out_g, b.ex(px, 1))
            b.store(out_b, b.ex(px, 2)); b.store(out_d, b.ex(px, 3))

        def reproj():
            max_xi = b.isub(b.u2i(w), b.i32(1)); max_yi = b.isub(b.u2i(h), b.i32(1))
            max_xf = b.stof(max_xi); max_yf = b.stof(max_yi)
            dx = b.fmul(b.fsub(b.utof(gx), half_w), inv_fs)
            dy = b.fmul(b.fsub(b.utof(gy), half_h), inv_fs)
            rx = b.fadd(b.fmul(dx, cos_r), b.fmul(dy, sin_r))
            ry = b.fadd(b.fmul(b.fneg(dx), sin_r), b.fmul(dy, cos_r))
            v_sx = b.local_f32(b.fadd(b.fadd(half_w, rx), b.fmul(yaw_sh, inv_fs)))
            v_sy = b.local_f32(b.fsub(b.fadd(half_h, ry), b.fmul(pitch_sh, inv_fs)))
            def do_tr():
                px_i = b.sclamp(b.ftos(b.ffloor(b.fadd(b.lf(v_sx), b.f32(0.5)))), b.i32(0), max_xi)
                py_i = b.sclamp(b.ftos(b.ffloor(b.fadd(b.lf(v_sy), b.f32(0.5)))), b.i32(0), max_yi)
                z0 = b.fmax(b.ex(b.load_ssbo(src_buf, p_src, b.t_vec4, b.uadd(b.umul(b.i2u(py_i), w), b.i2u(px_i))), 3), b.f32(0.05))
                b.store(v_sx, b.fadd(b.lf(v_sx), b.fmul(b.fdiv(pos_xm, z0), focal)))
                b.store(v_sy, b.fsub(b.lf(v_sy), b.fmul(b.fdiv(pos_ym, z0), focal)))
            b.if_block(b.une(has_trans, b.u32(0)), do_tr)

            sx = b.lf(v_sx); sy = b.lf(v_sy)
            in_b = b.land(b.land(b.fge(sx, b.f32(0.0)), b.fle(sx, max_xf)), b.land(b.fge(sy, b.f32(0.0)), b.fle(sy, max_yf)))
            def do_sample():
                x0 = b.sclamp(b.ftos(b.ffloor(sx)), b.i32(0), max_xi)
                y0 = b.sclamp(b.ftos(b.ffloor(sy)), b.i32(0), max_yi)
                x1 = b.smin(b.iadd(x0, b.i32(1)), max_xi); y1 = b.smin(b.iadd(y0, b.i32(1)), max_yi)
                fx = b.fsub(sx, b.stof(x0)); fy = b.fsub(sy, b.stof(y0))
                r0 = b.umul(b.i2u(y0), w); r1 = b.umul(b.i2u(y1), w)
                p00 = b.load_ssbo(src_buf, p_src, b.t_vec4, b.uadd(r0, b.i2u(x0)))
                p10 = b.load_ssbo(src_buf, p_src, b.t_vec4, b.uadd(r0, b.i2u(x1)))
                p01 = b.load_ssbo(src_buf, p_src, b.t_vec4, b.uadd(r1, b.i2u(x0)))
                p11 = b.load_ssbo(src_buf, p_src, b.t_vec4, b.uadd(r1, b.i2u(x1)))
                for ch, ov in ((0, out_r), (1, out_g), (2, out_b)):
                    c00 = b.ex(p00, ch); c10 = b.ex(p10, ch); c01 = b.ex(p01, ch); c11 = b.ex(p11, ch)
                    top = b.fadd(c00, b.fmul(b.fsub(c10, c00), fx))
                    bot = b.fadd(c01, b.fmul(b.fsub(c11, c01), fx))
                    b.store(ov, b.fadd(top, b.fmul(b.fsub(bot, top), fy)))
                b.store(out_d, b.fmax(b.fsub(b.ex(p00, 3), pos_zm), b.f32(0.05)))
            b.if_block(in_b, do_sample)

        b.if_block(b.ueq(has_pose, b.u32(0)), passthrough, reproj)
        fd = b.lf(out_d)
        b.store_ssbo(dst_buf, p_dst, idx, b.vec4(b.lf(out_r), b.lf(out_g), b.lf(out_b), fd))
        b.emit_depth_grid_write(grid_buf, p_grid, gx, gy, tiles_x, fd)

    b.if_block(b.land(b.ult(gx, w), b.ult(gy, h)), body)
    return b.finish()


def format_array(name: str, words: list[int]) -> str:
    lines = [f"static const uint32_t {name}[{len(words)}] = {{"]
    for i in range(0, len(words), 8):
        chunk = ", ".join(f"0x{w:08x}u" for w in words[i : i + 8])
        lines.append(f"    {chunk},")
    lines.append("};")
    return "\n".join(lines)


def main():
    shaders = [
        ("kSpvScene3dMilkFrogVertex", build_milkfrog_vertex_xform()),
        ("kSpvScene3dMilkFrogBin", build_milkfrog_tri_bin()),
        ("kSpvScene3dMilkFrogRaster", build_milkfrog_tile_raster()),
        ("kSpvScene3dQrBall", build_scene3d_qr_ball()),
        ("kSpvScene3dMonaLisa", build_scene3d_mona_lisa()),
        ("kSpvScene3dLayered", build_scene3d_layered()),
    ]
    out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "fuji_vulkan_scene3d_spirv.h")
    parts = [
        "#ifndef FUJI_VULKAN_SCENE3D_SPIRV_H",
        "#define FUJI_VULKAN_SCENE3D_SPIRV_H",
        "",
        "/* Auto-generated by shaders/generate_scene3d_spirv.py — Khronos SPIR-V 1.0 compute shaders */",
        "#include <stdint.h>",
        "",
    ]
    for name, words in shaders:
        parts.append(format_array(name, words))
        parts.append("")
    parts.append("#endif /* FUJI_VULKAN_SCENE3D_SPIRV_H */")
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(parts) + "\n")
    summary = ", ".join(f"{n}={len(w)}w" for n, w in shaders)
    print(f"Generated {out_path} ({summary})")


if __name__ == "__main__":
    main()
