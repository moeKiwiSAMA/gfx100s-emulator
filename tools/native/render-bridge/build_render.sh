#!/usr/bin/env bash
set -euo pipefail

# Build zero-dependency native SIMD rendering pipeline libraries:
#   - Linux x86_64:   .tools/render-bridge/build-linux/libfuji_render.so
#   - Windows x86_64: .tools/render-bridge/build-win64/fuji_render.dll (cross-compiled via MinGW-w64)
#
# Usage:
#   bash tools/native/render-bridge/build_render.sh [linux|win64|all]

TARGET="${1:-all}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
OUT_LINUX="${REPO_ROOT}/.tools/render-bridge/build-linux"
OUT_WIN64="${REPO_ROOT}/.tools/render-bridge/build-win64"

compute_source_sha() {
    sha256sum "${SCRIPT_DIR}/fuji_render.h" "${SCRIPT_DIR}/fuji_render.c" "${SCRIPT_DIR}/build_render.sh" \
        | sha256sum | awk '{print $1}'
}

build_linux() {
    mkdir -p "${OUT_LINUX}"
    local src_sha
    src_sha="$(compute_source_sha)"
    echo "[render-bridge] Building Linux x86_64 libfuji_render.so (SSE2 baseline + runtime AVX2/AVX-512 dispatch)..."
    gcc -O3 -std=c11 -msse2 -fPIC -shared -Wall -Wextra -Werror \
        -fvisibility=hidden \
        "${SCRIPT_DIR}/fuji_render.c" \
        -o "${OUT_LINUX}/libfuji_render.so" -lm

    # Run native C parity self-test
    gcc -O2 -std=c11 -Wall -Wextra -Werror \
        "${SCRIPT_DIR}/test_render_bridge.c" \
        -L"${OUT_LINUX}" -lfuji_render -Wl,-rpath,"${OUT_LINUX}" \
        -o "${OUT_LINUX}/test_render_bridge" -lm
    "${OUT_LINUX}/test_render_bridge"
    rm -f "${OUT_LINUX}/test_render_bridge"

    cat > "${OUT_LINUX}/source.properties" <<EOF
bridge=fuji-render-bridge
abiVersion=1
target=linux-x86_64
simdBaseline=sse2
simdRuntimeDispatch=avx2,avx512
sourceSha256=${src_sha}
EOF
    echo "[render-bridge] Built ${OUT_LINUX}/libfuji_render.so ($(stat -c%s "${OUT_LINUX}/libfuji_render.so") bytes)"
}

build_win64() {
    local mingw_cc="${MINGW_CC:-x86_64-w64-mingw32-gcc}"
    if ! command -v "${mingw_cc}" >/dev/null 2>&1; then
        echo "[render-bridge] ERROR: ${mingw_cc} not found on PATH" >&2
        exit 1
    fi
    mkdir -p "${OUT_WIN64}"
    local src_sha
    src_sha="$(compute_source_sha)"
    echo "[render-bridge] Cross-compiling Windows x86_64 fuji_render.dll (zero external DLL deps)..."
    "${mingw_cc}" -O3 -std=c11 -msse2 -shared -Wall -Wextra -Werror \
        -DFUJI_RENDER_BUILD_DLL \
        "${SCRIPT_DIR}/fuji_render.c" \
        -o "${OUT_WIN64}/fuji_render.dll" \
        -static-libgcc -Wl,--exclude-libs,ALL

    if command -v x86_64-w64-mingw32-objdump >/dev/null 2>&1; then
        local deps
        deps="$(x86_64-w64-mingw32-objdump -p "${OUT_WIN64}/fuji_render.dll" | awk '/DLL Name:/{print $3}')"
        echo "[render-bridge] fuji_render.dll imports: ${deps//$'\n'/, }"
        if echo "${deps}" | grep -iEv '^(KERNEL32\.dll|msvcrt\.dll|api-ms-win-.*|ucrtbase\.dll)$' >/dev/null 2>&1; then
            echo "[render-bridge] ERROR: unexpected non-system DLL dependency in fuji_render.dll!" >&2
            echo "${deps}" >&2
            exit 1
        fi
    fi

    cat > "${OUT_WIN64}/source.properties" <<EOF
bridge=fuji-render-bridge
abiVersion=1
target=windows-x86_64
simdBaseline=sse2
simdRuntimeDispatch=avx2,avx512
sourceSha256=${src_sha}
EOF
    echo "[render-bridge] Built ${OUT_WIN64}/fuji_render.dll ($(stat -c%s "${OUT_WIN64}/fuji_render.dll") bytes)"
}

case "${TARGET}" in
    linux) build_linux ;;
    win64) build_win64 ;;
    all)   build_linux && build_win64 ;;
    *)     echo "Usage: $0 [linux|win64|all]" >&2; exit 2 ;;
esac
