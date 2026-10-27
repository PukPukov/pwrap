# Pure Nix launcher configuration, reusable without a NixOS module.
{ pkgs, settings }:
let
  inherit (pkgs) lib;
  inherit (lib) types mkOption;
  option = type: default: mkOption { inherit type default; };
  group = options: option (types.submodule { inherit options; }) {};
  confinedPackage = (types.addCheck types.package (p: p ? pwrapManifest)) // {
    description = "an already-confined pwrap package";
  };
  options = {
    profiles = option (types.attrsOf confinedPackage) {};
    providers = group {
      audio = group {
        nixos = option (types.nullOr (types.submodule { options = {
          socketPath = mkOption { type = types.strMatching "/.*"; };
        }; })) null;
      };
    };
    desktop = group {
      files = option (types.attrsOf (types.strMatching "/.*")) {};
      readOnly = option (types.listOf types.str) [];
      environment = option (types.attrsOf types.str) {};
      runtimeRoots = option (types.listOf types.str) [];
    };
    network = group {
      localDeniedPorts = option (types.listOf (types.ints.between 1 65535)) [];
      relayServices = option (types.listOf (types.addCheck
        (types.strMatching "[A-Za-z0-9_@.-]+[.]service") (name: !(lib.hasInfix ".." name)))) [];
    };
  };
  providerMetadata = providers: lib.optionalAttrs (providers.audio.nixos != null) {
    audio.nixos = providers.audio.nixos;
  };
  check = raw:
    let checked = (lib.evalModules { modules = [{ inherit options; config = raw; }]; }).config;
        # Force manifests, not derivations: configured wrappers and their runner
        # form a fixed point, but each immutable manifest is runner-independent.
        evidence = checked // {
          profiles = lib.mapAttrs (_: p: toString p.pwrapManifest) checked.profiles;
        };
    in builtins.deepSeq evidence checked;
  checked = check settings;
  normalized = checked // {
    profiles = lib.mapAttrs (_: p: p.pwrapManifest) checked.profiles;
    providers = providerMetadata checked.providers;
  };
  metadata = lib.mapAttrs' (name: _: lib.nameValuePair name normalized.${name}) settings;
  files = data: lib.mapAttrs' (name: value: lib.nameValuePair "pwrap/${name}.json" {
    text = builtins.toJSON value;
  }) data;
in { inherit options check metadata files; }
