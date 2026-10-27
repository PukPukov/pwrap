#!/usr/bin/env bash
# Run after mvn -Pnative package, with a GraalVM toolchain and C compiler in PATH.
set -euo pipefail
cd "$(dirname "$0")/../../../.."
native-image --no-fallback -O1 -march=compatibility \
  --features=ru.pukpukov.pwrap.NativeTypes,ru.pukpukov.pwrap.host.HostTypes \
  ${NATIVE_LIBRARY_PATH:+-H:CLibraryPath="$NATIVE_LIBRARY_PATH"} \
  -cp "target/test-classes:$(find target -maxdepth 1 -name '*-all.jar' -print -quit)" \
  -o target/pwrap-native-checks ru.pukpukov.pwrap.NativeChecks
target/pwrap-native-checks "${1:-$PWD/target/pwrap}" \
  "$(command -v setpriv)" "$(type -P printf)" "$(command -v dbus-daemon)"
