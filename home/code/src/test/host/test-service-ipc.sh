#!/usr/bin/env bash
# Ordinary lifecycle smoke test with the exact NixOS-generated unit and root.
set -euo pipefail
test "$(id -u)" = 0
work=$(mktemp -d /var/tmp/pwrap-service-ipc-proof-XXXXXX)
instance=${work##*/}
templates=(pwrap-ipc-proof-host@ pwrap-ipc-proof-private@)
owned=()
cleanup() {
  for template in "${owned[@]}"; do
    unit=${template}${instance}.service
    systemctl stop "$unit" || true
    journalctl -u "$unit" --no-pager -o cat > "$work/${template}-journal.log" || true
    systemctl reset-failed "$unit" 2>/dev/null || true
    for suffix in .service .service.d; do
      link=/run/systemd/system/${template}${suffix}
      if [[ -L "$link" && "$(readlink "$link")" == "$PWRAP_SERVICE_IPC_FIXTURE/${template}${suffix}" ]]; then
        unlink "$link"
      fi
    done
  done
  systemctl daemon-reload
}
trap cleanup EXIT
for template in "${templates[@]}"; do
  # Never overwrite an existing unit or drop-in, even a dangling symlink.
  for suffix in .service .service.d; do
    target=/run/systemd/system/${template}${suffix}
    test ! -e "$target" && test ! -L "$target"
  done
  test "$(systemctl show "${template}${instance}.service" -p LoadState --value)" = not-found
done
for template in "${templates[@]}"; do
  owned+=("$template")
  for suffix in .service .service.d; do
    ln -s "$PWRAP_SERVICE_IPC_FIXTURE/${template}${suffix}" "/run/systemd/system/${template}${suffix}"
  done
done
systemctl daemon-reload
for template in "${templates[@]}"; do
  unit=${template}${instance}.service
  systemctl start "$unit"
  systemctl is-active "$unit"
  invocation=$(systemctl show "$unit" -p InvocationID --value)
  systemctl reload "$unit"
  systemctl stop "$unit"
  test "$(systemctl show "$unit" -p Result --value)" = success
  journalctl "_SYSTEMD_INVOCATION_ID=$invocation" --no-pager -o cat > "$work/${template}.log"
  for stage in condition pre start stopped; do grep -q "PWRAP_STAGE=$stage" "$work/${template}.log"; done
done
echo "PASS: confined IPC service condition/pre/start/reload/stop, private and host network; artifacts: $work"
