# Directory ownership and independent module graphs are architectural contracts.
{ pkgs, nixpkgs, sources }:
let
  inherit (pkgs) lib;
  evaluate = modules: (nixpkgs.lib.nixosSystem {
    system = pkgs.stdenv.hostPlatform.system;
    specialArgs.pwrapSources = sources;
    modules = modules ++ [{ system.stateVersion = "26.05"; }];
  });
  backend = evaluate [ ../main/backends/nixos/services {
    security.pwrap.services.probe = {};
    systemd.services.probe.serviceConfig.ExecStart = "${pkgs.hello}/bin/hello";
  } ];
  desktop = evaluate [ ../main/module.nix ];
  addon = evaluate [ ../main/nixos ];
  standaloneNM = evaluate [ ../main/nixos/presets/networkmanager.nix ];
in
# This directory is the opt-in presets addon, NOT the generic NixOS backend.
assert builtins.readDir ../main/nixos == {
  "default.nix" = "regular";
  "preset.nix" = "regular";
  presets = "directory";
};
assert !((builtins.readDir ../main/nixos/presets) ? "default.nix");
assert !((builtins.readDir ../main/nixos/presets) ? "preset.nix");
assert lib.all (name: lib.hasSuffix ".nix" name || name == "patches")
  (builtins.attrNames (builtins.readDir ../main/nixos/presets));
assert lib.all (lib.hasSuffix ".patch")
  (builtins.attrNames (builtins.readDir ../main/nixos/presets/patches));
assert builtins.readDir ../main/core == {
  "kernel-policy.nix" = "regular";
  "policy.nix" = "regular";
  native = "directory";
};
assert lib.all (lib.hasSuffix ".patch")
  (builtins.attrNames (builtins.readDir ../main/backends/nixos/patches));
assert !(backend.options.programs ? pwrap);
assert !(backend.options.security.pwrap ? presets);
assert !(backend.options.security.pwrap ? networkmanager);
assert backend.config.systemd.services.probe.confinement.enable;
assert lib.hasInfix "pwrap-ipc-scope --allow-signals" backend.config.systemd.services.probe.serviceConfig.ExecStart;
assert !(lib.hasInfix "pwrap-native" backend.config.systemd.services.probe.serviceConfig.ExecStart);
assert !(lib.hasAttrByPath [ "security" "pwrap" "services" ] desktop.options);
assert !(addon.options.programs ? pwrap);
assert addon.config.security.pwrap.services == {};
assert !(standaloneNM.options.programs ? pwrap);
assert !(standaloneNM.options.security.pwrap ? services);
assert !standaloneNM.config.security.pwrap.networkmanager.apply;
pkgs.writeText "pwrap-module-boundaries-proof" "independent backend, main module and service-presets addon\n"
