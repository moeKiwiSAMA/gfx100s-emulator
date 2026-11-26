#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -P "$(dirname "$0")" && pwd)

if [ -z "${FUJI_RENDER_LIBRARY:-}" ] && [ -f "$ROOT/runtime/native/libfuji_render.so" ]; then
    export FUJI_RENDER_LIBRARY="$ROOT/runtime/native/libfuji_render.so"
fi

if [ -z "${FUJI_VULKAN_OPTICS_LIBRARY:-}" ] && [ -f "$ROOT/runtime/native/libfuji_vulkan_optics.so" ]; then
    export FUJI_VULKAN_OPTICS_LIBRARY="$ROOT/runtime/native/libfuji_vulkan_optics.so"
fi

if [ -z "${FUJI_ISP_SIMD_LIBRARY:-}" ] && [ -f "$ROOT/runtime/native/libfuji_isp_simd.so" ]; then
    export FUJI_ISP_SIMD_LIBRARY="$ROOT/runtime/native/libfuji_isp_simd.so"
fi

if [ -z "${FUJI_CODEC_LIBRARY:-}" ] && [ -f "$ROOT/runtime/native/libfuji_codec.so" ]; then
    export FUJI_CODEC_LIBRARY="$ROOT/runtime/native/libfuji_codec.so"
fi

if [ -z "${FUJI_GAMEPAD_LIBRARY:-}" ] && [ -f "$ROOT/runtime/native/libfuji_gamepad.so" ]; then
    export FUJI_GAMEPAD_LIBRARY="$ROOT/runtime/native/libfuji_gamepad.so"
fi

if [ -x "$ROOT/bin/fuji-emulator-gfx100s" ]; then
    exec "$ROOT/bin/fuji-emulator-gfx100s" --home "$ROOT" --config "$ROOT/configs/emulator.yaml" --auto-boot "$@"
fi

if [ -x "$ROOT/jdk/bin/java" ]; then
    export JAVA_HOME="$ROOT/jdk"
elif [ -z "${JAVA_HOME:-}" ]; then
    if ! command -v java >/dev/null 2>&1; then
        printf '%s\n' "ERROR: JDK 25 is required (bundled jdk/bin/java not found). Install JDK 25 or set JAVA_HOME before running start-gfx100s.sh." >&2
        exit 1
    fi
fi

if [ -z "${DESKTOP_OPTS:-}" ]; then
    export DESKTOP_OPTS="-Dprism.order=es2,sw -Dprism.forceGPU=true"
fi

if [ -f "$ROOT/runtime/fuji-emulator.aot" ]; then
    export DESKTOP_OPTS="${DESKTOP_OPTS:-} -XX:AOTCache=$ROOT/runtime/fuji-emulator.aot"
fi

exec "$ROOT/bin/desktop" --home "$ROOT" --config "$ROOT/configs/emulator.yaml" --auto-boot "$@"
