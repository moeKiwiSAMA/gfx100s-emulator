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
mkdir -p "$work" "$package/runtime/media" "$package/runtime/media/licenses"

host_uname="$(uname -s)"
is_windows_host=0
case "$host_uname" in
    MINGW*|MSYS*|CYGWIN*) is_windows_host=1 ;;
esac

case "$target" in
    linux) [[ "$host_uname" == Linux ]] || { echo "Linux playback FFmpeg requires a Linux build host" >&2; exit 1; }
           executable=ffmpeg; target_args=() ;;
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
if ! command -v nasm >/dev/null; then asm+=(--disable-x86asm); fi
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
cat > "$package/runtime/media/licenses/SOURCE.txt" <<EOF
FFmpeg $version (LGPL-2.1-or-later)
Upstream source archive: https://ffmpeg.org/releases/$archive
Archive SHA-256: $sha256
Build script: runtime/media/licenses/build.sh

The matching unmodified $archive source archive is published alongside each
release distribution so binary release archives remain within hosting size
limits while preserving full LGPL-2.1 corresponding-source compliance.
EOF
sha256sum "$script_dir/build.sh" | awk '{print $1}' > "$package/bridge.sha256"
