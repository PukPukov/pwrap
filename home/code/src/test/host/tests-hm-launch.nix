# Evaluation only. Real upstream modules/packages; never build/activate a profile or launch an app.
{ corepkgs, homeManagerSource, sources }:
let
  pkgs = corepkgs;
  inherit (pkgs) lib;
  hm = import "${homeManagerSource}/lib" { inherit lib; };
  pwrap = import ../../main { inherit pkgs sources; };
  roots = { firefox = ".mozilla"; chromium = ".config/chromium"; thunderbird = ".thunderbird"; copyq = ".config/copyq"; };
  evaluate = app: confined: hm.homeManagerConfiguration {
    inherit pkgs;
    modules = [ ({ config, ... }: let
      profile = (pwrap.confinements.build { env.home = config; } (_: {
        ${app} = { executable = app; persist = [ "~/${roots.${app}}" ]; };
      })).${app};
      settings = {
        firefox = {
          configPath = ".mozilla/firefox";
          policies.DisableTelemetry = true;
          profiles.default = { id = 0; isDefault = true; search = { default = "DuckDuckGo"; force = true; }; };
        };
        chromium.commandLineArgs = [ "--disable-sync" ];
        thunderbird = { policies.DisableTelemetry = true; profiles.default.isDefault = true; };
        copyq.forceXWayland = false;
      };
    in {
      imports = lib.optional confined (pwrap.homeManagerModule {
        ${if app == "copyq" then "services" else "programs"}.${app} = profile;
      });
      config = {
        home = { username = "pwrap-launch-fixture"; homeDirectory = "/home/pwrap-launch-fixture"; stateVersion = "26.05"; };
        ${if app == "copyq" then "services" else "programs"}.${app} = settings.${app} // {
          enable = true; package = pkgs.${app};
        };
        home.file."${roots.${app}}/pwrap-selected".text = "selected settings";
        home.file.".ssh/pwrap-unselected".text = "unrelated settings";
      };
    }) ];
  };
  package = app: cfg: if app == "copyq" then cfg.services.copyq.package else cfg.programs.${app}.finalPackage;
  cases = map (app: let
    home = evaluate app true;
    cfg = home.config;
    wrapped = package app cfg;
    raw = package app (evaluate app false).config;
  in {
    inherit app;
    wrapper = toString wrapped;
    original = toString raw;
    manifestPath = toString wrapped.pwrapManifest;
    manifestText = wrapped.pwrapManifest.text;
    homeFilesDrv = cfg.home-files.drvPath;
    unselectedFileEnabled = cfg.home.file.".ssh/pwrap-unselected".enable;
    generationDrv = home.activationPackage.drvPath;
    installed = map toString cfg.home.packages;
    serviceExec = if app == "copyq" then cfg.systemd.user.services.copyq.Service.ExecStart else null;
  }) (builtins.attrNames roots);
in
assert builtins.sort builtins.lessThan (map (case: case.app) cases) == [ "chromium" "copyq" "firefox" "thunderbird" ];
assert lib.all (case: let
  policy = builtins.fromJSON (builtins.unsafeDiscardStringContext case.manifestText);
in builtins.elem case.wrapper case.installed && !(builtins.elem case.original case.installed)
  && policy.command == [ "${case.original}/bin/${case.app}" ]
  && lib.any (path: lib.hasSuffix "/pwrap-selected" path) policy.immutableData
  && !(lib.any (path: lib.hasInfix ".ssh" path) policy.immutableData)
  && case.unselectedFileEnabled && lib.hasSuffix ".drv" case.homeFilesDrv && lib.hasSuffix ".drv" case.generationDrv
  && (case.serviceExec == null || case.serviceExec == [ "${case.wrapper}/bin/copyq" ])) cases;
cases
