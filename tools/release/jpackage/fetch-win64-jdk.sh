#!/bin/sh
# Downloads Eclipse Temurin OpenJDK 25 Windows x64 JMODs (`image_type=jmods`, required since
# JDK 25 JEP 493 removed `jmods/` from standard JDK archives) into .tools/jdk-25-win64/jmods/
# so Linux/macOS hosts can cross-build a trimmed Windows JRE via `jlink --module-path <win64-jdk>/jmods`.
set -eu

ROOT=$(CDPATH= cd -P "$(dirname "$0")/../../.." && pwd)
DEST_DIR="${1:-$ROOT/.tools/jdk-25-win64}"
ARCHIVE="$ROOT/.tools/downloads/OpenJDK25U-jmods_x64_windows_hotspot.zip"
LATEST_URL="https://api.adoptium.net/v3/binary/latest/25/ga/windows/x64/jmods/hotspot/normal/eclipse"

find_jmods_home() {
    base="$1"
    if [ -f "$base/jmods/java.base.jmod" ]; then
        printf '%s\n' "$base"
        return 0
    fi
    for sub in "$base"/jdk-25*; do
        if [ -f "$sub/jmods/java.base.jmod" ]; then
            printf '%s\n' "$sub"
            return 0
        fi
    done
    return 1
}

resolve_java_release_file() {
    if [ -n "${JAVA_HOME:-}" ] && [ -f "$JAVA_HOME/release" ]; then
        printf '%s\n' "$JAVA_HOME/release"
        return 0
    fi
    if java_bin=$(command -v java 2>/dev/null); then
        if command -v readlink >/dev/null 2>&1; then
            java_bin=$(readlink -f "$java_bin" 2>/dev/null || printf '%s' "$java_bin")
        fi
        candidate="$(dirname "$(dirname "$java_bin")")/release"
        if [ -f "$candidate" ]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    fi
    return 1
}

detect_versioned_jmods_url() {
    rel_file=$(resolve_java_release_file) || return 1
    ver=$(awk -F= '/^SEMANTIC_VERSION=/{gsub(/"/, "", $2); print $2; exit}' "$rel_file")
    if [ -z "$ver" ]; then
        ver=$(awk -F= '/^FULL_VERSION=/{gsub(/"/, "", $2); sub(/-LTS$/, "", $2); print $2; exit}' "$rel_file")
    fi
    if [ -z "$ver" ]; then
        ver=$(awk -F= '/^IMPLEMENTOR_VERSION=/{gsub(/"/, "", $2); sub(/^Temurin-/, "", $2); print $2; exit}' "$rel_file")
    fi
    case "$ver" in
        25.*)
            encoded=$(printf '%s' "jdk-$ver" | sed 's/+/%2B/g')
            printf 'https://api.adoptium.net/v3/binary/version/%s/windows/x64/jmods/hotspot/normal/eclipse\n' "$encoded"
            return 0
            ;;
    esac
    return 1
}

extract_archive() {
    src="$1"
    dst="$2"
    if command -v unzip >/dev/null 2>&1 && unzip -tqq "$src" >/dev/null 2>&1; then
        unzip -q -o "$src" -d "$dst"
    elif command -v python3 >/dev/null 2>&1 && python3 -c 'import sys, zipfile; sys.exit(0 if zipfile.is_zipfile(sys.argv[1]) else 1)' "$src"; then
        python3 -c 'import sys, zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])' "$src" "$dst"
    elif command -v tar >/dev/null 2>&1; then
        tar -xf "$src" -C "$dst"
    else
        printf '%s\n' "ERROR: Neither unzip, python3, nor tar is available to extract $src" >&2
        return 1
    fi
}

if existing=$(find_jmods_home "$DEST_DIR"); then
    printf '%s\n' "Windows JDK 25 jmods already cached at: $existing"
    exit 0
fi

mkdir -p "$(dirname "$ARCHIVE")" "$DEST_DIR"

if [ -n "${FUJI_WIN64_JDK_URL:-}" ]; then
    printf '%s\n' "Downloading Windows x64 OpenJDK 25 jmods from $FUJI_WIN64_JDK_URL ..."
    curl -fsSL --retry 3 --retry-delay 2 -o "$ARCHIVE" "$FUJI_WIN64_JDK_URL"
else
    downloaded=0
    if versioned_url=$(detect_versioned_jmods_url); then
        printf '%s\n' "Downloading version-matched Windows x64 OpenJDK 25 jmods from $versioned_url ..."
        if curl -fsSL --retry 3 --retry-delay 2 -o "$ARCHIVE" "$versioned_url"; then
            downloaded=1
        else
            printf '%s\n' "Version-matched jmods URL failed; falling back to latest JDK 25 jmods ..." >&2
        fi
    fi
    if [ "$downloaded" -eq 0 ]; then
        printf '%s\n' "Downloading latest Windows x64 OpenJDK 25 jmods from $LATEST_URL ..."
        curl -fsSL --retry 3 --retry-delay 2 -o "$ARCHIVE" "$LATEST_URL"
    fi
fi

STAGE_DIR="$DEST_DIR/.extract-tmp"
rm -rf "$STAGE_DIR"
mkdir -p "$STAGE_DIR"
extract_archive "$ARCHIVE" "$STAGE_DIR"

base_jmod=$(find "$STAGE_DIR" -type f -name 'java.base.jmod' | head -n 1 || true)
if [ -z "$base_jmod" ]; then
    rm -rf "$STAGE_DIR"
    printf '%s\n' "ERROR: Extracted archive under $DEST_DIR does not contain java.base.jmod" >&2
    exit 1
fi

jmod_src_dir=$(dirname "$base_jmod")
rm -rf "$DEST_DIR/jmods"
mkdir -p "$DEST_DIR/jmods"
cp "$jmod_src_dir"/*.jmod "$DEST_DIR/jmods/"
rm -rf "$STAGE_DIR"

if resolved=$(find_jmods_home "$DEST_DIR"); then
    printf '%s\n' "Windows JDK 25 jmods ready at: $resolved/jmods"
else
    printf '%s\n' "ERROR: Normalized jmods directory under $DEST_DIR does not contain jmods/java.base.jmod" >&2
    exit 1
fi
