#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
DYNARMIC_COMMIT="69ce39e56e97f1ffd5e1afd82707acb178fd2196"
DYNARMIC_BRANCH="${FUJI_DYNARMIC_BRANCH:-master}"
DYNARMIC_URL="${FUJI_DYNARMIC_URL:-https://github.com/moeKiwiSAMA/dynarmic.git}"

HOME_DIR="${FUJI_DYNARMIC_HOME:-${REPO_ROOT}/.tools/dynarmic-v1}"
SRC_DIR="${HOME_DIR}/source"
LINUX_BUILD="${HOME_DIR}/build-linux"
WIN64_BUILD="${HOME_DIR}/build-win64"

TARGET="${1:-all}"
JOBS="${FUJI_BUILD_JOBS:-$(nproc 2>/dev/null || echo 4)}"

ensure_source() {
    mkdir -p "${HOME_DIR}"
    local cache_dir="${FUJI_DYNARMIC_CACHE_DIR:-/opt/fuji-cache/dynarmic-v1/source}"
    if [ ! -e "${SRC_DIR}/.git" ]; then
        if [ -e "${cache_dir}/.git" ]; then
            echo "[dynarmic] Copying pinned upstream from cache ${cache_dir} ..."
            cp -a "${cache_dir}" "${SRC_DIR}"
        else
            echo "[dynarmic] Cloning pinned moeKiwiSAMA/dynarmic (${DYNARMIC_BRANCH}) @ ${DYNARMIC_COMMIT} ..."
            git clone --branch "${DYNARMIC_BRANCH}" --no-checkout "${DYNARMIC_URL}" "${SRC_DIR}"
            git -C "${SRC_DIR}" checkout "${DYNARMIC_COMMIT}"
            git -C "${SRC_DIR}" submodule update --init --recursive --depth 1
        fi
    fi
    local head
    head="$(git -C "${SRC_DIR}" rev-parse HEAD)"
    if [ "${head}" != "${DYNARMIC_COMMIT}" ]; then
        echo "[dynarmic] Updating source checkout from ${head} to ${DYNARMIC_COMMIT} (${DYNARMIC_BRANCH}) ..."
        git -C "${SRC_DIR}" checkout -- .
        if ! git -C "${SRC_DIR}" cat-file -e "${DYNARMIC_COMMIT}^{commit}" 2>/dev/null; then
            if [ -d "${cache_dir}/.git" ] && git -C "${cache_dir}" cat-file -e "${DYNARMIC_COMMIT}^{commit}" 2>/dev/null; then
                git -C "${SRC_DIR}" fetch --force "${cache_dir}" "${DYNARMIC_COMMIT}"
            else
                git -C "${SRC_DIR}" fetch --force "${DYNARMIC_URL}" "${DYNARMIC_BRANCH}"
            fi
        fi
        git -C "${SRC_DIR}" checkout --force "${DYNARMIC_COMMIT}"
        head="$(git -C "${SRC_DIR}" rev-parse HEAD)"
    fi
    if [ "${head}" != "${DYNARMIC_COMMIT}" ]; then
        echo "[dynarmic] ERROR: expected ${DYNARMIC_COMMIT}, got ${head}" >&2
        exit 1
    fi
    if [ ! -f "${SRC_DIR}/externals/mcl/CMakeLists.txt" ]; then
        git -C "${SRC_DIR}" submodule update --init --recursive --depth 1
    fi
}

resolve_boost_wrap() {
    local boost_src=""
    for cand in "${FUJI_BOOST_INCLUDE_DIR:-}" "/opt/fuji-cache/boost-headers" "/usr/include"; do
        if [ -n "${cand}" ] && [ -d "${cand}/boost" ]; then
            boost_src="${cand}/boost"
            break
        fi
    done
    if [ -z "${boost_src}" ]; then
        echo "[dynarmic] ERROR: Boost headers not found (install libboost-dev or set FUJI_BOOST_INCLUDE_DIR)" >&2
        exit 1
    fi
    local boost_wrap="${HOME_DIR}/boost-headers-only"
    mkdir -p "${boost_wrap}"
    ln -sfn "${boost_src}" "${boost_wrap}/boost"
    echo "${boost_wrap}"
}

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
upstreamUrl=${DYNARMIC_URL}
upstreamBranch=${DYNARMIC_BRANCH}
upstreamCommit=${DYNARMIC_COMMIT}
library=${lib_path}
libraryBytes=${bytes}
librarySha256=${sha}
EOF
}

cmake_gen_args() {
    if command -v ninja >/dev/null 2>&1; then
        echo "-G Ninja"
    else
        echo ""
    fi
}

build_linux() {
    echo "[dynarmic] Building Linux x86_64 libfuji_dynarmic.so + test_dynarmic_bridge (jobs=${JOBS}) ..."
    local boost_wrap
    boost_wrap="$(resolve_boost_wrap)"
    # shellcheck disable=SC2046
    cmake $(cmake_gen_args) \
        -S "${SCRIPT_DIR}" \
        -B "${LINUX_BUILD}" \
        -DCMAKE_BUILD_TYPE=Release \
        -DDYNARMIC_SOURCE_DIR="${SRC_DIR}" \
        -DBoost_INCLUDE_DIR="${boost_wrap}" \
        -DFUJI_DYNARMIC_BUILD_TESTS=ON
    cmake --build "${LINUX_BUILD}" --parallel "${JOBS}"
    write_identity "linux-x86_64" "${LINUX_BUILD}/libfuji_dynarmic.so" "${LINUX_BUILD}/source.properties"
    echo "[dynarmic] Built: ${LINUX_BUILD}/libfuji_dynarmic.so"
    "${LINUX_BUILD}/test_dynarmic_bridge"
}

build_win64() {
    echo "[dynarmic] Cross-compiling Windows x86_64 fuji_dynarmic.dll via MinGW-w64 (jobs=${JOBS}) ..."
    local boost_wrap
    boost_wrap="$(resolve_boost_wrap)"
    # shellcheck disable=SC2046
    cmake $(cmake_gen_args) \
        -DCMAKE_TOOLCHAIN_FILE="${SCRIPT_DIR}/mingw64-dynarmic-toolchain.cmake" \
        -S "${SCRIPT_DIR}" \
        -B "${WIN64_BUILD}" \
        -DCMAKE_BUILD_TYPE=Release \
        -DDYNARMIC_SOURCE_DIR="${SRC_DIR}" \
        -DBoost_INCLUDE_DIR="${boost_wrap}" \
        -DFUJI_DYNARMIC_BUILD_TESTS=OFF
    cmake --build "${WIN64_BUILD}" --parallel "${JOBS}"
    write_identity "windows-x86_64" "${WIN64_BUILD}/fuji_dynarmic.dll" "${WIN64_BUILD}/source.properties"
    echo "[dynarmic] Built: ${WIN64_BUILD}/fuji_dynarmic.dll"
}

# Fail fast (<1s) on missing Boost headers before cloning upstream dynarmic
resolve_boost_wrap >/dev/null
ensure_source

case "${TARGET}" in
    linux) build_linux ;;
    win64) build_win64 ;;
    all)   build_linux; build_win64 ;;
    *)     echo "Usage: $0 [linux|win64|all]" >&2; exit 2 ;;
esac
