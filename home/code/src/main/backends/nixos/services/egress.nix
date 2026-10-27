# Native service lifecycle adapter for the shared endpoint compiler.
{ config, lib, pkgs, ... }:
let
  policies = lib.filterAttrs (_: p: p.egress != null) config.security.pwrap.services;
  names = builtins.attrNames policies;
  key = name: builtins.substring 0 24 (builtins.hashString "sha256" name);
  slice = name: "system-pwrap${key name}.slice";
  slices = map slice names;
  native = import ../../../java.nix { inherit pkgs; };
  document = {
    table = "pwrap_services_egress";
    policies = lib.mapAttrs' (name: p: lib.nameValuePair "svc_${key name}" {
      network = if p.egress == "deny" then "none" else "internet";
      inherit (p) dns;
      cgroup = "system.slice/${slice name}";
    }) policies;
  };
in {
  config = lib.mkIf (names != []) {
    assertions = [{ assertion = !(policies ? pwrap-egress); message = "pwrap-egress is reserved for the service boundary"; }];
    environment.etc."pwrap/service-egress.json".text = builtins.toJSON document;
    # Slice ancestors exist before nft resolves their IDs and survive ordinary
    # service restarts. Binding the boundary to them also covers slice teardown.
    systemd.slices = lib.genAttrs (map (lib.removeSuffix ".slice") slices) (_: {
      unitConfig.StopWhenUnneeded = false;
    });
    systemd.services = {
      pwrap-egress = {
        description = "pwrap native service egress boundary";
        wantedBy = [ "multi-user.target" ];
        before = map (n: "${n}.service") names;
        bindsTo = slices;
        after = [ "network.target" ] ++ slices;
        serviceConfig = {
          Type = "oneshot";
          RemainAfterExit = true;
          NoNewPrivileges = true;
          CapabilityBoundingSet = [ "" "CAP_NET_ADMIN" ];
          ProtectHome = true;
          ProtectSystem = "strict";
          PrivateTmp = true;
        };
        script = ''
          exec ${native}/bin/pwrap-native _service-egress ${config.environment.etc."pwrap/service-egress.json".source} ${pkgs.nftables}/bin/nft
        '';
        # No stop-time deletion: a replacement transaction preserves a working
        # table, and stopped boundaries must not open still-stopping payloads.
      };
    } // lib.genAttrs names (name: {
      bindsTo = [ "pwrap-egress.service" ];
      after = [ "pwrap-egress.service" ];
      partOf = [ "pwrap-egress.service" ];
      serviceConfig.Slice = lib.mkForce (slice name);
    });
  };
}
