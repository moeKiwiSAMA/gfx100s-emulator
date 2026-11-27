#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
HOME_DIR="${FUJI_AUDIO_BUILD_HOME:-${REPO_ROOT}/.tools/audio-bridge}"
LINUX_BUILD="${HOME_DIR}/build-linux"
WIN64_BUILD="${HOME_DIR}/build-win64"

TARGET="${1:-all}"

write_identity() {
    local target="$1"
    local lib_path="$2"
    local props_path="$3"
    local sha bytes
    sha="$(sha256sum "${lib_path}" | awk '{print $1}')"
    bytes="$(stat -c%s "${lib_path}" 2>/dev/null || wc -c < "${lib_path}")"
    cat > "${props_path}" <<EOF
schema=1
target=${target}
abiVersion=1
library=${lib_path}
libraryBytes=${bytes}
librarySha256=${sha}
EOF
}

build_linux() {
    local cc="${CC:-gcc}"
    mkdir -p "${LINUX_BUILD}"
    echo "[audio-bridge] Building Linux x86_64 libfuji_audio.so + test_audio_bridge ..."
    "${cc}" -O3 -std=c11 -fPIC -shared -Wall -Wextra -Werror \
        -fvisibility=hidden \
        -I"${SCRIPT_DIR}" \
        "${SCRIPT_DIR}/fuji_audio_linux.c" \
        -o "${LINUX_BUILD}/libfuji_audio.so" \
        -ldl -lpthread
    "${cc}" -O2 -std=c11 -Wall -Wextra -Werror \
        -I"${SCRIPT_DIR}" \
        "${SCRIPT_DIR}/test_audio_bridge.c" \
        -L"${LINUX_BUILD}" -lfuji_audio \
        -Wl,-rpath,'$ORIGIN' \
        -o "${LINUX_BUILD}/test_audio_bridge" \
        -lpthread
    write_identity "linux-x86_64" "${LINUX_BUILD}/libfuji_audio.so" "${LINUX_BUILD}/source.properties"
    echo "[audio-bridge] Built: ${LINUX_BUILD}/libfuji_audio.so"
    "${LINUX_BUILD}/test_audio_bridge"
}

build_win64() {
    local mingw_cc="${FUJI_MINGW64_GCC:-x86_64-w64-mingw32-gcc-win32}"
    if ! command -v "${mingw_cc}" >/dev/null 2>&1; then
        mingw_cc="x86_64-w64-mingw32-gcc"
    fi
    if ! command -v "${mingw_cc}" >/dev/null 2>&1; then
        echo "[audio-bridge] ERROR: MinGW-w64 gcc not found on PATH" >&2
        exit 1
    fi
    mkdir -p "${WIN64_BUILD}"
    echo "[audio-bridge] Cross-compiling Windows x86_64 fuji_audio.dll via ${mingw_cc} ..."
    "${mingw_cc}" -O3 -std=c11 -shared -Wall -Wextra -Werror \
        -DFUJI_AUDIO_BUILD_DLL=1 \
        -I"${SCRIPT_DIR}" \
        "${SCRIPT_DIR}/fuji_audio_win32.c" \
        -static-libgcc \
        -Wl,--exclude-all-symbols \
        -o "${WIN64_BUILD}/fuji_audio.dll" \
        -lole32 -lavrt -lksguid
    write_identity "windows-x86_64" "${WIN64_BUILD}/fuji_audio.dll" "${WIN64_BUILD}/source.properties"
    echo "[audio-bridge] Built: ${WIN64_BUILD}/fuji_audio.dll"
}

case "${TARGET}" in
    linux) build_linux ;;
    win64) build_win64 ;;
    all)   build_linux; build_win64 ;;
    *)     echo "Usage: $0 [linux|win64|all]" >&2; exit 2 ;;
esac
