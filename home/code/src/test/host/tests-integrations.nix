# Evaluation/build only: real HM source, no activation and no real HOME access.
{ corepkgs, homeManagerSource, sources }:
let
  pkgs = corepkgs;
  inherit (pkgs) lib;
  pwrap = import ../../main { inherit pkgs sources; };
  profile = package: pwrap.mkApp { name = "hello"; inherit package; };
  evaluate = selected: import (pkgs.path + "/nixos/lib/eval-config.nix") {
    system = pkgs.stdenv.hostPlatform.system;
    specialArgs.pwrapSources = sources;
    modules = [ ../../main/module.nix "${homeManagerSource}/nixos" ({ config, ... }: {
      system.stateVersion = "26.05";
      users.users.other = { isNormalUser = true; home = "/var/tmp/pwrap-integration-other"; };
      home-manager = {
        useGlobalPkgs = true;
        useUserPackages = true;
        users = lib.genAttrs [ "root" "other" ] (user: lib.mkForce {
          # An unrelated user may have another package postprocessor. Merely
          # selecting root must not add even an identity apply hook to other.
          options = lib.optionalAttrs (user == "other") {
            home.packages = lib.mkOption { apply = lib.id; };
          };
          config = {
            home.stateVersion = "26.05";
            home.packages = [ (if selected && user == "root" then profile pkgs.hello else pkgs.hello) ];
            home.file.".app/setting".text = "managed setting";
          };
        });
      };
      programs.pwrap.integrations.homeManager = lib.mkIf selected {
        source = homeManagerSource;
        users.root = {
          maintenance = { apply = true; roots = [ ".app" ]; };
        };
      };
    }) ];
  };
  plain = (evaluate false).config;
  selected = (evaluate true).config;
  original = user: plain.home-manager.users.${user};
  actual = user: selected.home-manager.users.${user};
  finalPackage = profile pkgs.hello;
  rootPackages = map (p: p.outPath) (actual "root").home.packages;
  otherPackages = map (p: p.outPath) (actual "other").home.packages;
in
assert plain.home-manager.sharedModules == [];
assert !(plain.system.build ? pwrap);
assert !(selected.system.build ? pwrap);
assert lib.filter (a: lib.hasPrefix "pwrap " a.message)
  (lib.filter (a: !a.assertion) selected.assertions) == [];
assert finalPackage ? pwrapManifest;
assert builtins.elem finalPackage.outPath rootPackages;
assert !(builtins.elem pkgs.hello.outPath rootPackages);
assert otherPackages == map (p: p.outPath) (original "other").home.packages;
assert (actual "other").home.activation.linkGeneration.data == (original "other").home.activation.linkGeneration.data;
assert lib.hasInfix "-hm-link-router" (actual "root").home.activation.linkGeneration.data;
assert lib.hasInfix "-hm-cleanup-router" (actual "root").home.activation.linkGeneration.data;
assert !(lib.hasInfix "-hm-link-router" (original "root").home.activation.linkGeneration.data);
pkgs.runCommand "pwrap-integrations-proof" {} ''
  test -x ${(actual "root").home.path}/bin/hello
  test "$(readlink -f ${(actual "root").home.path}/bin/hello)" = "${finalPackage}/bin/hello"
  touch $out
''
