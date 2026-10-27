#!/usr/bin/env bash
# Optional alias for the production audit, in NM's actual unit sandbox/ABI.
# Do not invoke on the host or restart NM merely for testing.
# Usage: tests-networkmanager.sh PWRAP_NATIVE LIBSECCOMP [FORBIDDEN_PATH...]
set -euo pipefail
native=${1:?supply the native pwrap executable}
shift
[[ $# -ge 1 ]] || { echo 'supply the libseccomp path' >&2; exit 2; }
exec "$native" _nm-audit "$@"
