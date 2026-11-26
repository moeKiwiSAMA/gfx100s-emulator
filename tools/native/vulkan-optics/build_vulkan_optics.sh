#!/usr/bin/env bash
set -euo pipefail

# Build zero-bloat dynamic Vulkan 1.1 Compute optical pipeline libraries:
#   - Linux x86_64:   .tools/vulkan-optics/build-linux/libfuji_vulkan_optics.so
#   - Windows x86_64: .tools/vulkan-optics/build-win64/fuji_vulkan_optics.dll
#
# Zero static Vulkan linkage and zero extra runtime DLL dependencies:
# loads libvulkan.so.1 (Linux) or vulkan-1.dll (Windows) dynamically at runtime.
#
# Usage:
#   bash tools/native/vulkan-optics/build_vulkan_optics.sh [linux|win64|wine-test|all]

TARGET="${1:-all}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
OUT_LINUX="${REPO_ROOT}/.tools/vulkan-optics/build-linux"
OUT_WIN64="${REPO_ROOT}/.tools/vulkan-optics/build-win64"

compute_source_sha() {
    sha256sum \
        "${SCRIPT_DIR}/fuji_vk_abi.h" \
        "${SCRIPT_DIR}/fuji_vulkan_optics.h" \
        "${SCRIPT_DIR}/fuji_vulkan_pipeline_cache.h" \
        "${SCRIPT_DIR}/fuji_vulkan_shaders_spirv.h" \
        "${SCRIPT_DIR}/fuji_vulkan_pipeline_cache.cpp" \
        "${SCRIPT_DIR}/fuji_vulkan_optics.cpp" \
        "${SCRIPT_DIR}/fuji_vulkan_stats.h" \
        "${SCRIPT_DIR}/fuji_vulkan_stats_spirv.h" \
        "${SCRIPT_DIR}/fuji_vulkan_stats.cpp" \
        "${SCRIPT_DIR}/fuji_vulkan_scene3d.h" \
        "${SCRIPT_DIR}/fuji_vulkan_scene3d_spirv.h" \
        "${SCRIPT_DIR}/fuji_vulkan_scene3d.cpp" \
        "${SCRIPT_DIR}/fuji_vulkan_custom_spv.h" \
        "${SCRIPT_DIR}/fuji_vulkan_custom_spv.cpp" \
        "${SCRIPT_DIR}/build_vulkan_optics.sh" \
        | sha256sum | awk '{print $1}'
}

build_linux() {
    mkdir -p "${OUT_LINUX}"
    local src_sha
    src_sha="$(compute_source_sha)"
    echo "[vulkan-optics] Building Linux x86_64 libfuji_vulkan_optics.so (dynamic libvulkan.so.1 loader)..."
    g++ -O3 -std=c++17 -fPIC -shared -Wall -Wextra -Werror \
        -fvisibility=hidden \
        "${SCRIPT_DIR}/fuji_vulkan_pipeline_cache.cpp" \
        "${SCRIPT_DIR}/fuji_vulkan_optics.cpp" \
        "${SCRIPT_DIR}/fuji_vulkan_stats.cpp" \
        "${SCRIPT_DIR}/fuji_vulkan_scene3d.cpp" \
        "${SCRIPT_DIR}/fuji_vulkan_custom_spv.cpp" \
        -o "${OUT_LINUX}/libfuji_vulkan_optics.so" -ldl

    # Verify zero static linkage to libvulkan
    if ldd "${OUT_LINUX}/libfuji_vulkan_optics.so" | grep -i "libvulkan" >/dev/null 2>&1; then
        echo "[vulkan-optics] ERROR: libfuji_vulkan_optics.so must NOT statically link libvulkan!" >&2
        exit 1
    fi

    # Build & execute native Vulkan compute self-tests (optics + scene3d)
    g++ -O2 -std=c++17 -Wall -Wextra -Werror \
        "${SCRIPT_DIR}/test_vulkan_optics.cpp" \
        -L"${OUT_LINUX}" -lfuji_vulkan_optics -Wl,-rpath,"${OUT_LINUX}" \
        -o "${OUT_LINUX}/test_vulkan_optics" -lm
    if ! "${OUT_LINUX}/test_vulkan_optics"; then
        rm -f "${OUT_LINUX}/test_vulkan_optics"
        echo "[vulkan-optics] ERROR: native Vulkan compute self-test failed!" >&2
        exit 1
    fi
    rm -f "${OUT_LINUX}/test_vulkan_optics"

    # Build & execute native Vulkan ISP stats reduction parity self-test
    g++ -O2 -std=c++17 -Wall -Wextra -Werror \
        "${SCRIPT_DIR}/test_vulkan_stats.cpp" \
        -L"${OUT_LINUX}" -lfuji_vulkan_optics -Wl,-rpath,"${OUT_LINUX}" \
        -o "${OUT_LINUX}/test_vulkan_stats" -lm
    if ! "${OUT_LINUX}/test_vulkan_stats"; then
        rm -f "${OUT_LINUX}/test_vulkan_stats"
        echo "[vulkan-optics] ERROR: native Vulkan ISP stats self-test failed!" >&2
        exit 1
    fi
    rm -f "${OUT_LINUX}/test_vulkan_stats"

    # Build & execute native Vulkan Scene3D rasterizer parity self-test
    g++ -O2 -std=c++17 -Wall -Wextra -Werror \
        "${SCRIPT_DIR}/test_vulkan_scene3d.cpp" \
        -L"${OUT_LINUX}" -lfuji_vulkan_optics -Wl,-rpath,"${OUT_LINUX}" \
        -o "${OUT_LINUX}/test_vulkan_scene3d" -lm
    if ! "${OUT_LINUX}/test_vulkan_scene3d"; then
        rm -f "${OUT_LINUX}/test_vulkan_scene3d"
        echo "[vulkan-optics] ERROR: native Vulkan Scene3D rasterizer self-test failed!" >&2
        exit 1
    fi
    rm -f "${OUT_LINUX}/test_vulkan_scene3d"

    # Build & execute native Vulkan Custom SPIR-V Plugin SPI self-test
    g++ -O2 -std=c++17 -Wall -Wextra -Werror \
        "${SCRIPT_DIR}/test_vulkan_custom_spv.cpp" \
        -L"${OUT_LINUX}" -lfuji_vulkan_optics -Wl,-rpath,"${OUT_LINUX}" \
        -o "${OUT_LINUX}/test_vulkan_custom_spv" -lm
    if ! "${OUT_LINUX}/test_vulkan_custom_spv"; then
        rm -f "${OUT_LINUX}/test_vulkan_custom_spv"
        echo "[vulkan-optics] ERROR: native Vulkan Custom SPIR-V self-test failed!" >&2
        exit 1
    fi
    rm -f "${OUT_LINUX}/test_vulkan_custom_spv"

    cat > "${OUT_LINUX}/source.properties" <<EOF
bridge=fuji-vulkan-optics
abiVersion=1
target=linux-x86_64
dynamicLoader=libvulkan.so.1
sourceSha256=${src_sha}
EOF
    echo "[vulkan-optics] Built ${OUT_LINUX}/libfuji_vulkan_optics.so ($(stat -c%s "${OUT_LINUX}/libfuji_vulkan_optics.so") bytes)"
}

build_win64() {
    local mingw_cxx="${MINGW_CXX:-}"
    if [ -z "${mingw_cxx}" ]; then
        if command -v x86_64-w64-mingw32-g++-win32 >/dev/null 2>&1; then
            mingw_cxx="x86_64-w64-mingw32-g++-win32"
        else
            mingw_cxx="x86_64-w64-mingw32-g++"
        fi
    fi
    if ! command -v "${mingw_cxx}" >/dev/null 2>&1; then
        echo "[vulkan-optics] ERROR: ${mingw_cxx} not found on PATH" >&2
        exit 1
    fi
    mkdir -p "${OUT_WIN64}"
    local src_sha
    src_sha="$(compute_source_sha)"
    echo "[vulkan-optics] Cross-compiling Windows x86_64 fuji_vulkan_optics.dll via ${mingw_cxx} (dynamic vulkan-1.dll, zero extra DLLs)..."
    "${mingw_cxx}" -O3 -std=c++17 -shared -Wall -Wextra -Werror \
        -DFUJI_VK_OPTICS_BUILD_DLL \
        "${SCRIPT_DIR}/fuji_vulkan_pipeline_cache.cpp" \
        "${SCRIPT_DIR}/fuji_vulkan_optics.cpp" \
        "${SCRIPT_DIR}/fuji_vulkan_stats.cpp" \
        "${SCRIPT_DIR}/fuji_vulkan_scene3d.cpp" \
        "${SCRIPT_DIR}/fuji_vulkan_custom_spv.cpp" \
        -o "${OUT_WIN64}/fuji_vulkan_optics.dll" \
        -static -static-libgcc -static-libstdc++ -Wl,--exclude-libs,ALL

    if command -v x86_64-w64-mingw32-objdump >/dev/null 2>&1; then
        local deps
        deps="$(x86_64-w64-mingw32-objdump -p "${OUT_WIN64}/fuji_vulkan_optics.dll" | awk '/DLL Name:/{print $3}')"
        echo "[vulkan-optics] fuji_vulkan_optics.dll imports: ${deps//$'\n'/, }"
        if echo "${deps}" | grep -iEv '^(KERNEL32\.dll|msvcrt\.dll|api-ms-win-.*|ucrtbase\.dll)$' >/dev/null 2>&1; then
            echo "[vulkan-optics] ERROR: unexpected non-system DLL dependency in fuji_vulkan_optics.dll!" >&2
            echo "${deps}" >&2
            exit 1
        fi
    fi

    cat > "${OUT_WIN64}/source.properties" <<EOF
bridge=fuji-vulkan-optics
abiVersion=1
target=windows-x86_64
dynamicLoader=vulkan-1.dll
sourceSha256=${src_sha}
EOF
    echo "[vulkan-optics] Built ${OUT_WIN64}/fuji_vulkan_optics.dll ($(stat -c%s "${OUT_WIN64}/fuji_vulkan_optics.dll") bytes)"
}

wine_test_win64() {
    if [ ! -f "${OUT_WIN64}/fuji_vulkan_optics.dll" ]; then
        build_win64
    fi
    local mingw_cxx="${MINGW_CXX:-}"
    if [ -z "${mingw_cxx}" ]; then
        if command -v x86_64-w64-mingw32-g++-win32 >/dev/null 2>&1; then
            mingw_cxx="x86_64-w64-mingw32-g++-win32"
        else
            mingw_cxx="x86_64-w64-mingw32-g++"
        fi
    fi
    local wine_bin=""
    if command -v wine64 >/dev/null 2>&1; then
        wine_bin="wine64"
    elif command -v wine >/dev/null 2>&1; then
        wine_bin="wine"
    else
        echo "[vulkan-optics] ERROR: wine/wine64 not found on PATH" >&2
        exit 1
    fi
    echo "[vulkan-optics] Cross-compiling Win64 self-test harness and executing under ${wine_bin}..."
    "${mingw_cxx}" -O2 -std=c++17 -Wall -Wextra -Werror \
        "${SCRIPT_DIR}/test_vulkan_optics.cpp" \
        "${OUT_WIN64}/fuji_vulkan_optics.dll" \
        -o "${OUT_WIN64}/test_vulkan_optics_win64.exe" \
        -static -static-libgcc -static-libstdc++ -Wl,--exclude-libs,ALL
    local prefix="${WINEPREFIX:-/tmp/wine-vk-test}"
    mkdir -p "${prefix}"
    if ! WINEPREFIX="${prefix}" WINEARCH=win64 WINEDEBUG=-all "${wine_bin}" "${OUT_WIN64}/test_vulkan_optics_win64.exe"; then
        rm -f "${OUT_WIN64}/test_vulkan_optics_win64.exe"
        echo "[vulkan-optics] ERROR: Wine Win64 verification failed!" >&2
        exit 1
    fi
    rm -f "${OUT_WIN64}/test_vulkan_optics_win64.exe"
    echo "[vulkan-optics] Wine Win64 verification PASSED (zero missing DLL imports, graceful fallback verified)"
}

case "${TARGET}" in
    linux)     build_linux ;;
    win64)     build_win64 ;;
    wine-test) wine_test_win64 ;;
    all)       build_linux; build_win64 ;;
    *)         echo "Usage: $0 [linux|win64|wine-test|all]" >&2; exit 2 ;;
esac
