#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
HOME_DIR="${FUJI_GAMEPAD_BUILD_HOME:-${REPO_ROOT}/.tools/gamepad-bridge}"
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
    echo "[gamepad-bridge] Building Linux x86_64 libfuji_gamepad.so + self-tests ..."
    "${cc}" -O2 -std=c11 -fPIC -shared -Wall -Wextra -Werror \
        -fvisibility=hidden \
        -I"${SCRIPT_DIR}" \
        "${SCRIPT_DIR}/fuji_gamepad_linux.c" \
        -o "${LINUX_BUILD}/libfuji_gamepad.so"
    "${cc}" -O2 -std=c11 -Wall -Wextra -Werror \
        -I"${SCRIPT_DIR}" \
        "${SCRIPT_DIR}/test_gamepad_bridge.c" \
        -L"${LINUX_BUILD}" -lfuji_gamepad \
        -Wl,-rpath,'$ORIGIN' \
        -o "${LINUX_BUILD}/test_gamepad_bridge"
    "${cc}" -O2 -std=c11 -Wall -Wextra -Werror \
        -I"${SCRIPT_DIR}" \
        "${SCRIPT_DIR}/test_gamepad_mapping.c" \
        -o "${LINUX_BUILD}/test_gamepad_mapping" \
        -lm

    # Exported surface must be exactly the five ABI functions.
    local exports
    exports="$(nm -D --defined-only "${LINUX_BUILD}/libfuji_gamepad.so" | awk '$2 ~ /^[TDBR]$/ {print $3}' | sort | tr '\n' ' ')"
    if [[ "${exports}" != "fuji_gamepad_abi_version fuji_gamepad_backend_name fuji_gamepad_init fuji_gamepad_poll fuji_gamepad_shutdown " ]]; then
        echo "[gamepad-bridge] ERROR: unexpected .so exports: ${exports}" >&2
        exit 1
    fi

    write_identity "linux-x86_64" "${LINUX_BUILD}/libfuji_gamepad.so" "${LINUX_BUILD}/source.properties"
    echo "[gamepad-bridge] Built: ${LINUX_BUILD}/libfuji_gamepad.so"
    "${LINUX_BUILD}/test_gamepad_bridge"
    "${LINUX_BUILD}/test_gamepad_mapping"
}

build_win64() {
    local mingw_cc="${FUJI_MINGW64_GCC:-x86_64-w64-mingw32-gcc-win32}"
    if ! command -v "${mingw_cc}" >/dev/null 2>&1; then
        mingw_cc="x86_64-w64-mingw32-gcc"
    fi
    if ! command -v "${mingw_cc}" >/dev/null 2>&1; then
        echo "[gamepad-bridge] ERROR: MinGW-w64 gcc not found on PATH" >&2
        exit 1
    fi
    local objdump="${FUJI_MINGW64_OBJDUMP:-x86_64-w64-mingw32-objdump}"
    if ! command -v "${objdump}" >/dev/null 2>&1; then
        echo "[gamepad-bridge] ERROR: ${objdump} not found on PATH (needed to verify DLL imports)" >&2
        exit 1
    fi
    mkdir -p "${WIN64_BUILD}"
    echo "[gamepad-bridge] Cross-compiling Windows x86_64 fuji_gamepad.dll via ${mingw_cc} ..."
    "${mingw_cc}" -O2 -std=c11 -shared -Wall -Wextra -Werror \
        -DFUJI_GAMEPAD_BUILD_DLL=1 \
        -I"${SCRIPT_DIR}" \
        "${SCRIPT_DIR}/fuji_gamepad_win32.c" \
        -static-libgcc \
        -Wl,--exclude-libs,ALL \
        -o "${WIN64_BUILD}/fuji_gamepad.dll"
    # Self-test executable (run under Wine when available; not shipped).
    "${mingw_cc}" -O2 -std=c11 -Wall -Wextra -Werror \
        -I"${SCRIPT_DIR}" \
        "${SCRIPT_DIR}/test_gamepad_bridge.c" \
        "${WIN64_BUILD}/fuji_gamepad.dll" \
        -static-libgcc \
        -o "${WIN64_BUILD}/test_gamepad_bridge.exe"

    local imports bad=0 dll
    imports="$("${objdump}" -p "${WIN64_BUILD}/fuji_gamepad.dll" | awk '/DLL Name:/ {print $3}')"
    echo "[gamepad-bridge] fuji_gamepad.dll imports: $(echo "${imports}" | tr '\n' ' ')"
    if [[ -z "${imports}" ]]; then
        echo "[gamepad-bridge] ERROR: could not read DLL import table" >&2
        exit 1
    fi
    for dll in ${imports}; do
        case "$(echo "${dll}" | tr '[:upper:]' '[:lower:]')" in
            kernel32.dll|msvcrt.dll) ;;
            *) echo "[gamepad-bridge] ERROR: forbidden DLL import: ${dll}" >&2; bad=1 ;;
        esac
    done
    if [[ "${bad}" -ne 0 ]]; then
        exit 1
    fi

    write_identity "windows-x86_64" "${WIN64_BUILD}/fuji_gamepad.dll" "${WIN64_BUILD}/source.properties"
    echo "[gamepad-bridge] Built: ${WIN64_BUILD}/fuji_gamepad.dll"
}

case "${TARGET}" in
    linux) build_linux ;;
    win64) build_win64 ;;
    all)   build_linux; build_win64 ;;
    *)     echo "Usage: $0 [linux|win64|all]" >&2; exit 2 ;;
esac
