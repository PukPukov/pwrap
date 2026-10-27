#!/usr/bin/env bash
# Evaluate real HM modules/packages without building, activating or launching them.
set -euo pipefail
nixpkgs=$(readlink -f "${1:?NIXPKGS_SOURCE}")
home_manager=$(readlink -f "${2:?HOME_MANAGER_SOURCE}")
native=$(readlink -f "${3:?PWRAP_NATIVE}")
fixture=$(readlink -f "$(dirname "$0")/tests-hm-launch.nix")
work=$(mktemp -d /var/tmp/pwrap-hm-launch-XXXXXX)
printf 'Evidence: %s\n' "$work"
nix-instantiate --eval --strict --json --argstr nixpkgs "$nixpkgs" \
  --argstr homeManagerSource "$home_manager" --argstr fixture "$fixture" --expr '
  { nixpkgs, homeManagerSource, fixture }:
  import (builtins.toPath fixture) {
    corepkgs = import (builtins.toPath nixpkgs) { system = "x86_64-linux"; };
    homeManagerSource = builtins.toPath homeManagerSource;
  }' > "$work/result.json" 2> "$work/evaluation.log"
# The fixture asserts package/ExecStart/selected-file invariants in Nix itself.
# Validate each exact generated manifest through the production Java boundary.
for app in chromium copyq firefox thunderbird; do
  jq -er --arg app "$app" '.[] | select(.app == $app) | .manifestText' "$work/result.json" > "$work/$app.json"
  "$native" _policy-check "$work/$app.json"
done
printf 'PASS: actual browser/CopyQ HM launch expressions, lazy selected files and native policy schema\n'
