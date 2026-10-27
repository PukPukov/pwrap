# Real generated service/slice units, renamed only to isolate this fixture.
{ pkgs, testRoot, token, probePort, resolverAddress }:
let
  lib = pkgs.lib;
  guardName = "pwrapfixture${token}";
  table = "pwrap_fixture_${token}";
  names = map (kind: "pwrapfixture${token}${kind}") [ "root" "shared" "dynamic" ];
  driver = (import ../../main/java.nix { inherit pkgs; }).tests;
  evaluation = import (pkgs.path + "/nixos/lib/eval-config.nix") {
    system = pkgs.stdenv.hostPlatform.system;
    modules = [ ../../main/nixos {
      system.stateVersion = "26.05";
      fileSystems."/" = { device = "none"; fsType = "tmpfs"; };
      boot.loader.grub.enable = false;
      security.pwrap.services = lib.genAttrs names (name: {
        egress = if lib.hasSuffix "root" name then "internet" else "deny";
        dns = [ resolverAddress ];
        packages = [ driver ];
      });
      systemd.services = lib.genAttrs names (name: {
        serviceConfig = {
          ExecStart = "${driver}/bin/pwrap-tests _fixture SERVICE ${toString probePort} ${resolverAddress}";
          User = if lib.hasSuffix "dynamic" name then null else "root";
          DynamicUser = lib.hasSuffix "dynamic" name;
          StandardOutput = "append:${testRoot}/${name}.log";
          StandardError = "append:${testRoot}/${name}.log";
          # The fixture is PID1 in PrivatePIDs; terminate it explicitly
          # rather than waiting for PID1's ignored default SIGTERM disposition.
          KillSignal = "SIGKILL";
          TimeoutStopSec = 5;
        };
      });
    } ];
  };
  cfg = evaluation.config;
  originalDocument = cfg.environment.etc."pwrap/service-egress.json";
  document = (builtins.fromJSON originalDocument.text) // { inherit table; };
  fixtureDocument = pkgs.writeText "pwrap-fixture-egress.json" (builtins.toJSON document);
  guardScript = pkgs.writeShellScript "pwrap-fixture-boundary" (lib.replaceStrings
    [ (toString originalDocument.source) ] [ (toString fixtureDocument) ] cfg.systemd.services.pwrap-egress.script);
  originalExec = cfg.systemd.services.pwrap-egress.serviceConfig.ExecStart;
  unitNames = [ "pwrap-egress.service" ] ++ map (name: "${name}.service") names ++
    cfg.systemd.services.pwrap-egress.bindsTo;
  rename = lib.replaceStrings [ "pwrap-egress.service" originalExec ]
    [ "${guardName}.service" (toString guardScript) ];
  units = lib.listToAttrs (map (name: lib.nameValuePair
    (lib.replaceStrings [ "pwrap-egress" ] [ guardName ] name)
    (pkgs.writeText name (rename cfg.systemd.units.${name}.text))) unitNames);
  dropins = pkgs.runCommand "pwrap-fixture-confinement-dropins" {} ''
    mkdir -p $out
    ${lib.concatMapStringsSep "\n" (name: ''
      for package in ${lib.escapeShellArgs (map toString cfg.systemd.packages)}; do
        input="$package/lib/systemd/system/${name}.service.d/confinement.conf"
        if test -f "$input"; then
          mkdir -p "$out/${name}.service.d"
          cp "$input" "$out/${name}.service.d/confinement.conf"
        fi
      done
      test -f "$out/${name}.service.d/confinement.conf"
    '') names}
  '';
in assert lib.hasPrefix "/var/tmp/pwrap-egress-lifecycle-" testRoot;
assert builtins.match "[a-z0-9]+" token != null;
assert lib.assertMsg (lib.all (assertion: assertion.assertion) cfg.assertions)
  (lib.concatMapStringsSep "\n" (assertion: assertion.message) (lib.filter (assertion: !assertion.assertion) cfg.assertions));
pkgs.writeText "pwrap-egress-lifecycle.json" (builtins.toJSON {
  inherit units names table document dropins;
  guard = "${guardName}.service";
  compiler = "${import ../../main/java.nix { inherit pkgs; }}/bin/pwrap-native";
  nft = "${pkgs.nftables}/bin/nft";
  systemctl = "${pkgs.systemd}/bin/systemctl";
  fail = "${pkgs.coreutils}/bin/false";
})
