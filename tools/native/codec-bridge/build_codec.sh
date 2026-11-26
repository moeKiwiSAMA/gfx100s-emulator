#!/usr/bin/env bash
set -euo pipefail

# Build zero-static-dependency native codec bridge libraries:
#   - Linux x86_64:   .tools/codec-bridge/build-linux/libfuji_codec.so
#   - Windows x86_64: .tools/codec-bridge/build-win64/fuji_codec.dll (cross-compiled via MinGW-w64)
#
# Usage:
#   bash tools/native/codec-bridge/build_codec.sh [linux|win64|all]

TARGET="${1:-all}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
OUT_LINUX="${REPO_ROOT}/.tools/codec-bridge/build-linux"
OUT_WIN64="${REPO_ROOT}/.tools/codec-bridge/build-win64"

compute_source_sha() {
    sha256sum "${SCRIPT_DIR}/fuji_codec.h" "${SCRIPT_DIR}/fuji_codec.c" "${SCRIPT_DIR}/build_codec.sh" \
        | sha256sum | awk '{print $1}'
}

build_linux() {
    mkdir -p "${OUT_LINUX}"
    local src_sha
    src_sha="$(compute_source_sha)"
    echo "[codec-bridge] Building Linux x86_64 libfuji_codec.so (MT LSRAW + dynamic libjpeg-turbo YUV422)..."
    gcc -O3 -std=c11 -msse2 -fPIC -shared -Wall -Wextra -Werror \
        -fvisibility=hidden \
        "${SCRIPT_DIR}/fuji_codec.c" \
        -o "${OUT_LINUX}/libfuji_codec.so" -lpthread -ldl

    # Run native C self-test
    gcc -O2 -std=c11 -Wall -Wextra -Werror \
        "${SCRIPT_DIR}/test_codec_bridge.c" \
        -L"${OUT_LINUX}" -lfuji_codec -Wl,-rpath,"${OUT_LINUX}" \
        -o "${OUT_LINUX}/test_codec_bridge"
    "${OUT_LINUX}/test_codec_bridge"
    rm -f "${OUT_LINUX}/test_codec_bridge"

    cat > "${OUT_LINUX}/source.properties" <<EOF
bridge=fuji-codec-bridge
abiVersion=1
target=linux-x86_64
features=lsraw-mt,dynamic-libjpeg8-yuv422,dynamic-libjpeg62-yuv422,dynamic-turbojpeg
sourceSha256=${src_sha}
EOF
    echo "[codec-bridge] Built ${OUT_LINUX}/libfuji_codec.so ($(stat -c%s "${OUT_LINUX}/libfuji_codec.so") bytes)"
}

build_win64() {
    local mingw_cc="${MINGW_CC:-x86_64-w64-mingw32-gcc}"
    if ! command -v "${mingw_cc}" >/dev/null 2>&1; then
        echo "[codec-bridge] ERROR: ${mingw_cc} not found on PATH" >&2
        exit 1
    fi
    mkdir -p "${OUT_WIN64}"
    local src_sha
    src_sha="$(compute_source_sha)"
    echo "[codec-bridge] Cross-compiling Windows x86_64 fuji_codec.dll (zero external DLL deps)..."
    "${mingw_cc}" -O3 -std=c11 -msse2 -shared -Wall -Wextra -Werror \
        -DFUJI_CODEC_BUILD_DLL \
        "${SCRIPT_DIR}/fuji_codec.c" \
        -o "${OUT_WIN64}/fuji_codec.dll" \
        -static-libgcc -Wl,--exclude-libs,ALL

    if command -v x86_64-w64-mingw32-objdump >/dev/null 2>&1; then
        local deps
        deps="$(x86_64-w64-mingw32-objdump -p "${OUT_WIN64}/fuji_codec.dll" | awk '/DLL Name:/{print $3}')"
        echo "[codec-bridge] fuji_codec.dll imports: ${deps//$'\n'/, }"
        if echo "${deps}" | grep -iEv '^(KERNEL32\.dll|msvcrt\.dll|api-ms-win-.*|ucrtbase\.dll)$' >/dev/null 2>&1; then
            echo "[codec-bridge] ERROR: unexpected non-system DLL dependency in fuji_codec.dll!" >&2
            echo "${deps}" >&2
            exit 1
        fi
    fi

    cat > "${OUT_WIN64}/source.properties" <<EOF
bridge=fuji-codec-bridge
abiVersion=1
target=windows-x86_64
features=lsraw-mt,dynamic-turbojpeg,dynamic-libjpeg8,dynamic-libjpeg62
sourceSha256=${src_sha}
EOF
    echo "[codec-bridge] Built ${OUT_WIN64}/fuji_codec.dll ($(stat -c%s "${OUT_WIN64}/fuji_codec.dll") bytes)"
}

case "${TARGET}" in
    linux) build_linux ;;
    win64) build_win64 ;;
    all)   build_linux && build_win64 ;;
    *)     echo "Usage: $0 [linux|win64|all]" >&2; exit 2 ;;
esac
