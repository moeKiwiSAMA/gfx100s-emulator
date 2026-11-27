#!/usr/bin/env bash
# Build the redistributable decoder used by the MVE firmware playback path.
set -euo pipefail

target="${1:?expected linux or win64}"
script_dir="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$script_dir/../../.." && pwd)"
version=8.1.3
archive="ffmpeg-$version.tar.xz"
sha256=7138d28c96d9d3e3af4ee3d8cad72741f8ffb40da90c1112235dea3ecd3178a3
cache_dir="$repo/.tools/playback-ffmpeg/cache"
work="$repo/.tools/playback-ffmpeg/$target"
package="$repo/.tools/playback-ffmpeg/build-$target"
source_dir="$work/ffmpeg-$version"
nasm_version=2.16.03
nasm_archive="nasm-$nasm_version.tar.xz"
nasm_sha256=1412a1c760bbd05db026b6c0d1657affd6631cd0a63cddb6f73cc6d4aa616148

host_uname="$(uname -s)"
is_windows_host=0
case "$host_uname" in
    MINGW*|MSYS*|CYGWIN*) is_windows_host=1 ;;
esac

case "$target" in
    linux) executable=ffmpeg; target_args=() ;;
    win64) executable=ffmpeg.exe
           if [[ "$is_windows_host" == 1 ]]; then
               target_args=(--target-os=mingw32 --arch=x86_64
                            --disable-pthreads --enable-w32threads --extra-ldflags=-static)
           else
               target_args=(--target-os=mingw32 --arch=x86_64 --cross-prefix=x86_64-w64-mingw32-
                            --enable-cross-compile --disable-pthreads --enable-w32threads --extra-ldflags=-static)
           fi ;;
    *) echo "Unknown FFmpeg target: $target" >&2; exit 2 ;;
esac

uses_x86=false
if [[ "$target" == win64 || "$(uname -m)" =~ ^(x86_64|amd64|i[3-6]86)$ ]]; then uses_x86=true; fi
nasm_tarball=""
for candidate in "${FUJI_NASM_TARBALL:-}" "$cache_dir/$nasm_archive" "/opt/fuji-cache/playback-ffmpeg/$nasm_archive"; do
    if [[ -f "$candidate" ]]; then
        nasm_tarball="$(cd "$(dirname "$candidate")" && pwd)/$(basename "$candidate")"
        break
    fi
done

if [[ "${2:-}" == --ensure ]]; then
    script_hash=$(sha256sum "$script_dir/build.sh" | awk '{print $1}')
    cache_mode() {
        local dir=$1 artifact
        [[ -s "$dir/bridge.sha256" && "$(<"$dir/bridge.sha256")" == "$script_hash" ]] || return 0
        for artifact in "$executable" licenses/COPYING.LGPLv2.1 licenses/build.sh licenses/SOURCE.txt; do
            [[ -s "$dir/runtime/media/$artifact" ]] || return 0
        done
        sed -n 's/^FFmpeg x86 assembly: //p' "$dir/runtime/media/licenses/SOURCE.txt"
    }
    if [[ "$target" == win64 ]]; then
        image="${FUJI_PLAYBACK_FFMPEG_WIN64_CACHE:-/opt/fuji-cache/playback-ffmpeg/build-win64}"
    else
        image="${FUJI_PLAYBACK_FFMPEG_LINUX_CACHE:-/opt/fuji-cache/playback-ffmpeg/build-linux}"
    fi
    local_mode=$(cache_mode "$package")
    image_mode=$(cache_mode "$image")
    can_upgrade=false
    if "$uses_x86" && { command -v nasm >/dev/null || [[ -n "$nasm_tarball" ]]; }; then can_upgrade=true; fi
    # Prefer an existing optimized package on either side, even on a host with no assembler.
    if [[ "$local_mode" == enabled || ( "$local_mode" == disabled && "$image_mode" != enabled && "$can_upgrade" == false ) ]]; then
        if [[ "$target" == linux ]]; then chmod +x "$package/runtime/media/$executable"; fi
        echo "Reusing local $target playback FFmpeg (x86 assembly $local_mode)"
        exit 0
    fi
    if [[ "$image_mode" == enabled || ( "$image_mode" == disabled && "$can_upgrade" == false ) ]]; then
        mkdir -p "$package"
        cp -a "$image/." "$package/"
        if [[ "$target" == linux ]]; then chmod +x "$package/runtime/media/$executable"; fi
        echo "Reusing image $target playback FFmpeg (x86 assembly $image_mode)"
        exit 0
    fi
fi

if [[ "$target" == linux && "$host_uname" != Linux ]]; then
    echo "Linux playback FFmpeg requires a Linux build host" >&2
    exit 1
fi
mkdir -p "$work" "$package/runtime/media/licenses"
cd "$work"
if [[ ! -f "$archive" ]]; then
    cached_tarball=""
    for candidate in \
        "${FUJI_FFMPEG_TARBALL:-}" \
        "$cache_dir/$archive" \
        "/opt/fuji-cache/playback-ffmpeg/$archive"; do
        if [[ -n "$candidate" && -f "$candidate" ]]; then
            cached_tarball="$candidate"
            break
        fi
    done
    if [[ -n "$cached_tarball" ]]; then
        echo "Using cached FFmpeg source archive: $cached_tarball"
        cp "$cached_tarball" "$archive"
    else
        curl -fL --retry 3 "https://ffmpeg.org/releases/$archive" -o "$archive.tmp"
        mv "$archive.tmp" "$archive"
    fi
fi
echo "$sha256  $archive" | sha256sum --check --status
if [[ ! -f "$cache_dir/$archive" ]]; then
    mkdir -p "$cache_dir"
    cp "$archive" "$cache_dir/$archive" || true
fi
if [[ ! -f "$source_dir/configure" ]]; then
    "${TAR:-tar}" -xf "$archive"
fi
mkdir -p "$work/build"
cd "$work/build"
asm=()
if "$uses_x86" && ! command -v nasm >/dev/null; then
    # NASM runs on the build host, including when FFmpeg targets Windows from Linux.
    nasm_source="$work/nasm-$nasm_version"
    nasm_executable=nasm
    if [[ "$is_windows_host" == 1 ]]; then nasm_executable=nasm.exe; fi
    if [[ -z "$nasm_tarball" ]]; then
        nasm_url="https://www.nasm.us/pub/nasm/releasebuilds/$nasm_version/$nasm_archive"
        if curl -fsSL --connect-timeout 5 --max-time 30 "$nasm_url" -o "$work/$nasm_archive.tmp"; then
            nasm_tarball="$work/$nasm_archive.tmp"
        fi
    fi
    if [[ -n "$nasm_tarball" ]]; then
        if ! echo "$nasm_sha256  $nasm_tarball" | sha256sum --check --status; then
            echo "Unexpected SHA-256 for NASM source archive: $nasm_tarball" >&2
            exit 1
        fi
        if [[ "$nasm_tarball" == "$work/$nasm_archive.tmp" ]]; then
            mkdir -p "$cache_dir"
            mv "$nasm_tarball" "$cache_dir/$nasm_archive"
            nasm_tarball="$cache_dir/$nasm_archive"
        fi
        if [[ ! -f "$nasm_source/configure" ]]; then "${TAR:-tar}" -xf "$nasm_tarball" -C "$work"; fi
        if "$nasm_source/$nasm_executable" -v >/dev/null 2>&1 ||
            (cd "$nasm_source" && ./configure CC="${HOST_CC:-cc}" && make -j"${CMAKE_BUILD_PARALLEL_LEVEL:-4}" "$nasm_executable"); then
            export PATH="$nasm_source:$PATH"
        fi
    fi
    if ! command -v nasm >/dev/null; then
        echo "NASM unavailable on this build host; building the portable FFmpeg decoder" >&2
        asm+=(--disable-x86asm)
    fi
fi
"$source_dir/configure" "${target_args[@]}" "${asm[@]}" \
    --disable-autodetect --disable-network --disable-doc --disable-debug --disable-shared --enable-static \
    --disable-everything --disable-ffplay --disable-ffprobe --enable-ffmpeg \
    --enable-demuxer=h264,hevc --enable-parser=h264,hevc --enable-decoder=h264,hevc \
    --enable-encoder=wrapped_avframe --enable-muxer=yuv4mpegpipe --enable-protocol=pipe \
    --enable-filter=scale,format
make -j"${CMAKE_BUILD_PARALLEL_LEVEL:-4}" "$executable"
# Exercise both decoders when the build host can run the target executable.
if [[ "$target" == linux || "$is_windows_host" == 1 ]]; then
    verify_frame() {
        local codec="$1" sample="$2" hash
        hash=$(printf '%s' "$sample" | base64 -d | "./$executable" -v error -xerror -f "$codec" -i pipe:0 \
            -pix_fmt yuv420p -f yuv4mpegpipe pipe:1 | tail -c 384 | sha256sum)
        [[ "${hash%% *}" == df7fa6b29395ce3a6fcfb65d7b3d6fc3af2889c58bdddc1c412622c14da81595 ]] || {
            echo "FFmpeg $codec failed the 16x16 playback smoke test" >&2; return 1;
        }
    }
    verify_frame h264 'AAAAAWdkAAqs2V7ARAAAAwAEAAADAMg8SJZYAAAAAWjr48siwAAAAAFliIQAK//+9nN8JX0='
    verify_frame hevc 'AAAAAUABDAH//wFgAAADAJAAAAMAAAMAHpWYCQAAAAFCAQEBYAAAAwCQAAADAAADAB6giEWWVmq8rwFoCAAAAwAIAAADAMhAAAAAAUQBwXPQiQAAAAEoAa8dgO4jjCQ='
fi
cp "$executable" "$package/runtime/media/$executable"
rm -f "$package/runtime/media/licenses/$archive"
cp "$source_dir/COPYING.LGPLv2.1" "$script_dir/build.sh" "$package/runtime/media/licenses/"
x86_assembly=disabled
if grep -Fxq '#define HAVE_X86ASM 1' config.h; then x86_assembly=enabled; fi
cat > "$package/runtime/media/licenses/SOURCE.txt" <<EOF
FFmpeg $version (LGPL-2.1-or-later)
FFmpeg x86 assembly: $x86_assembly
Upstream source archive: https://ffmpeg.org/releases/$archive
Archive SHA-256: $sha256
Build script: runtime/media/licenses/build.sh

The matching unmodified $archive source archive is published alongside each
release distribution so binary release archives remain within hosting size
limits while preserving full LGPL-2.1 corresponding-source compliance.
EOF
sha256sum "$script_dir/build.sh" | awk '{print $1}' > "$package/bridge.sha256"
