#!/bin/sh
set -eu
repo=$(CDPATH= cd -P "$(dirname "$0")/.." && pwd)
exec sh "$repo/jvm.sh" setup-native "$@"
