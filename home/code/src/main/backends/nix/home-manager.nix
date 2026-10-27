# Ordinary Home Manager module, independent of NixOS orchestration.
{ config, lib, options, ... }:
let
  cfg = config.pwrap;
  profiles = lib.mkOption {
    type = lib.types.lazyAttrsOf (lib.types.functionTo lib.types.package);
    default = {};
    description = "Package profiles applied to the selected upstream Home Manager options.";
  };
  bind = leaf: lib.mapAttrs (name: profile: {
    ${leaf} = lib.mkOption {
      apply = package: if package == null then null else
        let
          wrapped = profile package;
          confined = assert lib.assertMsg (lib.isDerivation wrapped && wrapped ? pwrapManifest)
            "pwrap HM ${name}.${leaf}: profile must return a confined package"; wrapped;
        in lib.lazyDerivation {
          derivation = confined;
          passthru = { inherit (confined) pwrapManifest; };
        };
    };
  });
  assertions = scope: leaf: profiles: lib.mapAttrsToList (name: _:
    let path = [ scope name leaf ]; label = lib.concatStringsSep "." path;
    in {
      assertion = (lib.getAttrFromPath path options).isDefined;
      message = "pwrap HM ${label}: selected binding has no upstream package definition";
    }) profiles;
in {
  options = {
    pwrap = { programs = profiles; services = profiles; };
    # These declarations depend on a separate option namespace, NOT imports.
    # Profile values can therefore be configured like other HM settings.
    programs = bind "finalPackage" cfg.programs;
    services = bind "package" cfg.services;
  };
  config.assertions = assertions "programs" "finalPackage" cfg.programs
    ++ assertions "services" "package" cfg.services;
}
