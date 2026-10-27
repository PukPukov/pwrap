#!/usr/bin/env bash
# Native host suite. Run only against test-owned compartments; no deployment.
# Usage: bash tests.sh /nix/store/...-pwrap/bin/pwrap /nix/store/...-tests/bin/pwrap-tests
# Optional fixture entry points and coverage are documented in BUILDING.md/.ai.
set -euo pipefail
runner=$(readlink -f "${1:?supply the generated pwrap runner}")
driver=$(readlink -f "${2:?supply the native test-driver executable}")
native=$(sed -n 's|^exec \(/nix/store/[^ ]*/bin/pwrap-native\) .*|\1|p' "$runner")
[[ -x "$native" && -x "$driver" ]] || { echo 'Expected generated runner and native test driver' >&2; exit 2; }
"$driver" "$native" "$(command -v setpriv)" "$(type -P printf)" "$(command -v dbus-daemon)"
exec "$driver" _host "$runner" "$driver"
