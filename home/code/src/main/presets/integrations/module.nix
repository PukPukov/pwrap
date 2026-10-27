# Explicit NixOS adapters for mutable-file maintenance.
# Neither upstream module is imported or enabled implicitly.
{ config, options, lib, pkgs, pwrapSources, ... }:
let
  inherit (lib) types mkOption;
  cfg = config.programs.pwrap.integrations;
  source = mkOption { type = types.nullOr types.path; default = null; description = "Explicit upstream source tree; required when its maintenance integration is applied."; };
  roots = types.listOf (types.addCheck (types.strMatching ".+") (root:
    builtins.all (part: part != "" && part != "." && part != "..") (lib.splitString "/" root)));
  hm = cfg.homeManager;
  hmActive = builtins.attrNames hm.users != [];
  hmMaintenance = lib.any (user: user.maintenance.apply) (lib.attrValues hm.users);
  hasHM = lib.hasAttrByPath [ "home-manager" "sharedModules" ] options;
  hasNixcord = lib.hasAttrByPath [ "programs" "nixcord" "enable" ] options;
in {
  options.programs.pwrap.integrations = {
    homeManager = {
      inherit source;
      users = mkOption {
        default = {};
        type = types.attrsOf (types.submodule {
          options = {
            maintenance = {
              apply = lib.mkEnableOption "confine HM link/cleanup for explicitly selected roots";
              roots = mkOption { type = roots; default = []; description = "App-owned canonical relative paths below this HM user's HOME; no default user or roots."; };
            };
          };
        });
      };
    };
    nixcord = {
      apply = lib.mkEnableOption "confine Nixcord activation file operations (does not enable Nixcord)";
      inherit source;
    };
  };
  config = lib.mkMerge [
    {
      assertions = lib.optionals hmActive [
        { assertion = hasHM; message = "pwrap integrations.homeManager requires the Home Manager NixOS module to be imported explicitly"; }
        { assertion = !hasHM || lib.all (name: builtins.hasAttr name config.home-manager.users) (builtins.attrNames hm.users); message = "pwrap integrations.homeManager selects an HM user that is not configured"; }
        { assertion = !hmMaintenance || hm.source != null; message = "pwrap HM maintenance requires integrations.homeManager.source"; }
        { assertion = lib.all (user: !user.maintenance.apply || user.maintenance.roots != []) (lib.attrValues hm.users); message = "pwrap HM maintenance requires explicit nonempty roots for each selected user"; }
      ] ++ lib.concatMap (name:
        let selected = hm.users.${name};
            exists = hasHM && builtins.hasAttr name config.home-manager.users;
            home = config.home-manager.users.${name};
        in [
          {
            assertion = !exists || !selected.maintenance.apply || lib.all
              (marker: lib.hasInfix marker home.home.activation.linkGeneration.data)
              [ "-hm-link-router" "-hm-cleanup-router" ];
            message = "pwrap HM maintenance integration was not applied for ${name}";
          }
        ]) (builtins.attrNames hm.users)
      ++ lib.optionals cfg.nixcord.apply [
        {
          assertion = hasNixcord && cfg.nixcord.source != null;
          message = "pwrap integrations.nixcord requires an explicit source and the upstream Nixcord NixOS module";
        }
        {
          assertion = !hasNixcord || !config.programs.nixcord.enable || config.programs.nixcord.user == "root";
          message = "pwrap integrations.nixcord supports root-session Nixcord only; refusing an unapplied confinement for another user";
        }
      ];
    }
    (lib.optionalAttrs hasHM {
      # sharedModules is inside each HM submodule, so a host's mkForce on
      # users.<name> cannot silently discard the selected security adapter.
      home-manager.sharedModules = lib.mapAttrsToList (name: user: { _prefix, ... }: {
          # Nix supplies the structural option prefix before imports; `name`
          # from HM's _module.args instead depends on the module fixed point.
          # Match only this selected user: no identity hooks on other users.
          imports = lib.optionals (_prefix == [ "home-manager" "users" name ]) (
            lib.optional (user.maintenance.apply && hm.source != null) (import ../../backends/nix/home-files.nix {
              homeManagerSource = hm.source;
              sources = pwrapSources;
              inherit (user.maintenance) roots;
            }));
        }) hm.users;
    })
    (lib.mkIf (cfg.nixcord.apply && hasNixcord && cfg.nixcord.source != null)
      ((import ./nixcord.nix { nixcordSource = cfg.nixcord.source; sources = pwrapSources; }) { inherit config lib pkgs; }).config)
  ];
}
