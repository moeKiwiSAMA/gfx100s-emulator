#!/usr/bin/env bash
set -euo pipefail

# Build script for libfuji_isp_simd.so (Linux x86_64) and fuji_isp_simd.dll (Win64 cross).
# Usage:
#   bash tools/native/isp-simd/build_isp_simd.sh [linux|win64|all]

MODE="${1:-all}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
OUT_LINUX="${ROOT_DIR}/.tools/isp-simd/build-linux"
OUT_WIN64="${ROOT_DIR}/.tools/isp-simd/build-win64"

COMMON_CFLAGS="-O3 -std=c11 -Wall -Wextra -Werror -fvisibility=hidden"

build_linux() {
    mkdir -p "${OUT_LINUX}"
    echo "[isp-simd] Building Linux x86_64 libfuji_isp_simd.so ..."
    gcc ${COMMON_CFLAGS} -msse2 -fPIC -shared -pthread \
        -o "${OUT_LINUX}/libfuji_isp_simd.so" \
        "${SCRIPT_DIR}/fuji_isp_simd.c" \
        -lm
    ls -lh "${OUT_LINUX}/libfuji_isp_simd.so"

    echo "[isp-simd] Building and running Linux C parity & benchmark self-test ..."
    gcc -O3 -std=c11 -Wall -Wextra -Werror -pthread \
        -I"${SCRIPT_DIR}" \
        -o "${OUT_LINUX}/test_isp_simd" \
        "${SCRIPT_DIR}/test_isp_simd.c" \
        -Wl,-rpath,"${OUT_LINUX}" \
        -L"${OUT_LINUX}" -lfuji_isp_simd -lm
    "${OUT_LINUX}/test_isp_simd"
}

build_win64() {
    if ! command -v x86_64-w64-mingw32-gcc >/dev/null 2>&1; then
        echo "[isp-simd] ERROR: x86_64-w64-mingw32-gcc not found" >&2
        exit 1
    fi
    mkdir -p "${OUT_WIN64}"
    echo "[isp-simd] Cross-compiling Win64 x86_64 fuji_isp_simd.dll (zero external DLL deps) ..."
    x86_64-w64-mingw32-gcc ${COMMON_CFLAGS} -msse2 -shared \
        -static-libgcc -Wl,--exclude-libs,ALL \
        -o "${OUT_WIN64}/fuji_isp_simd.dll" \
        "${SCRIPT_DIR}/fuji_isp_simd.c" \
        -lm
    ls -lh "${OUT_WIN64}/fuji_isp_simd.dll"

    if command -v x86_64-w64-mingw32-objdump >/dev/null 2>&1; then
        echo "[isp-simd] Verifying Win64 DLL imports (must be KERNEL32.dll + msvcrt.dll ONLY):"
        IMPORTS="$(x86_64-w64-mingw32-objdump -p "${OUT_WIN64}/fuji_isp_simd.dll" | grep -i "DLL Name:" || true)"
        echo "${IMPORTS}"
        if echo "${IMPORTS}" | grep -Eiv "KERNEL32\.dll|msvcrt\.dll" >/dev/null 2>&1; then
            echo "[isp-simd] ERROR: Unexpected Win64 DLL import detected!" >&2
            exit 1
        fi
        echo "[isp-simd] Win64 DLL import check PASSED."
    fi
}

case "${MODE}" in
    linux)
        build_linux
        ;;
    win64)
        build_win64
        ;;
    all)
        build_linux
        build_win64
        ;;
    *)
        echo "Usage: $0 [linux|win64|all]" >&2
        exit 2
        ;;
esac
