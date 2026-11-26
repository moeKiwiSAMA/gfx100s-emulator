#!/bin/sh
# Portable entry: Java/Kotlin own the behavior; this file only preserves argv/paths.
set -eu
repo=$(CDPATH= cd -P "$(dirname "$0")" && pwd)
command_name=build
if [ "$#" -gt 0 ]; then
    case "$1" in
        -h|--help|help) command_name=help; shift ;;
        --*) : ;;
        *) command_name=$1; shift ;;
    esac
fi
cygwin=false
shell_host=$(uname -s)
case "$shell_host" in CYGWIN*|MINGW*|MSYS*) cygwin=true ;; esac
FUJI_TOOL_ENTRYPOINT="sh jvm.sh $command_name"
case "$shell_host" in CYGWIN*) FUJI_TOOL_SHELL="Cygwin POSIX sh" ;; MINGW*|MSYS*) FUJI_TOOL_SHELL="MSYS POSIX sh" ;; *) FUJI_TOOL_SHELL="POSIX sh" ;; esac
export FUJI_TOOL_ENTRYPOINT FUJI_TOOL_SHELL
# Known paths are converted explicitly below; MSYS must not reinterpret arbitrary argv.
case "$shell_host" in MINGW*|MSYS*) MSYS2_ARG_CONV_EXCL='*'; export MSYS2_ARG_CONV_EXCL ;; esac
JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
export JAVA_TOOL_OPTIONS
if "$cygwin" && ! command -v cygpath >/dev/null 2>&1; then
    printf '%s\n' 'Windows shell requires cygpath; use Cygwin or the direct Java diagnostic entry.' >&2
    exit 1
fi
java_command=
if [ -n "${JAVA_HOME:-}" ]; then
    java_home=$JAVA_HOME
    if "$cygwin"; then java_home=$(cygpath -u "$java_home"); fi
    if [ -x "$java_home/bin/java" ]; then
        java_command=$java_home/bin/java
    elif [ -x "$java_home/Contents/Home/bin/java" ]; then
        java_command=$java_home/Contents/Home/bin/java
    fi
fi
if [ -z "$java_command" ]; then
    for candidate in "$repo"/.tools/jdk-25*; do
        if [ -x "$candidate/bin/java" ]; then java_command=$candidate/bin/java; break; fi
        if [ -x "$candidate/Contents/Home/bin/java" ]; then java_command=$candidate/Contents/Home/bin/java; break; fi
    done
fi
if [ -z "$java_command" ]; then java_command=$(command -v java || true); fi
if [ -z "$java_command" ]; then
    printf '%s\n' 'Install Java17+ first (JDK25 for build/runtime), or set JAVA_HOME.' >&2
    exit 1
fi
source_file=$repo/tools/EmulatorTools.java
repo_native=$repo
if "$cygwin"; then
    source_file=$(cygpath -m "$source_file")
    repo_native=$(cygpath -m "$repo")
    if [ -n "${JAVA_HOME:-}" ]; then JAVA_HOME=$(cygpath -m "$JAVA_HOME"); export JAVA_HOME; fi
fi
# Rotate argv without eval or command strings. Relative file options use the repository root.
count=$#
expect_path=false
literal=false
while [ "$count" -gt 0 ]; do
    arg=$1; shift; count=$((count - 1))
    if "$expect_path"; then
        if "$cygwin"; then
            case "$arg" in
                /*|[A-Za-z]:[\\/]*) arg=$(cygpath -m "$arg") ;;
                *) arg=$(cygpath -m "$repo/$arg") ;;
            esac
        fi
        expect_path=false
    else
        case "$arg" in
            --) literal=true ;;
            --python) if ! "$literal"; then printf '%s\n' '--python has been retired; research uses the JVM.' >&2; exit 1; fi ;;
            --repo) if ! "$literal"; then printf '%s\n' 'Shell entry uses its own repository; use the direct Java entry for --repo override.' >&2; exit 1; fi ;;
            --target|--downloads|--wheel|--release-metadata|--home|--config|--metadata|--output|--run|--before|--after|--body-file|--update|--report|--reference-root|--power-peer-inputs|--adc-inputs|--i2c-inputs|--spi-ports-inputs|--cluster-reset-inputs|--exclusive-inputs|--timer-inputs) expect_path=true; path_option=$arg ;;
            --java-home|--java-temp-directory|--gradle-home|--firmware|--native-library|--native-header)
                if ! "$literal"; then expect_path=true; path_option=$arg; fi ;;
        esac
    fi
    set -- "$@" "$arg"
done
if "$expect_path"; then printf '%s\n' "Missing path after $path_option" >&2; exit 1; fi
exec "$java_command" "$source_file" "$command_name" --repo "$repo_native" "$@"
