{ pkgs, sources }:
let
  inherit (pkgs) lib;
  pwrap = import ../main { inherit pkgs sources; };
  source = pkgs.writeText "managed-selected" "selected settings";
  generation = pkgs.runCommand "managed-files" {} ''
    mkdir -p $out/.app
    ln -s ${source} $out/.app/settings
  '';
  home = {
    pwrap.maintenanceRoots = [ ".app" ];
    home.homeDirectory = "/home/alice";
    xdg.stateHome = "/home/alice/.local/state";
    home.file = {
      selected = { enable = true; target = ".app/settings"; inherit source; };
      sibling = { enable = true; target = ".application/settings"; source = throw "ungranted source forced"; };
      other = { enable = true; target = ".ssh/key"; source = throw "ungranted key forced"; };
      disabled = { enable = false; target = ".app/disabled"; source = throw "disabled source forced"; };
    };
    # Accessing a complete generation would depend on unrelated managed files
    # (fontconfig can depend on the very packages being wrapped).
    home-files = throw "patcher must not force aggregate HM generation";
  };
  patcher = pwrap.patchers.homeManager home;
  raw = { name = "app"; persist = [ "~/.app" ]; readOnly = [ "/etc/selected" ]; };
  patched = patcher raw;
  denied = patcher { name = "app"; persist = [ "~/.other" ]; };
  profile = (pwrap.confinements.build { patchers = [ patcher ]; }
    ({ ... }: { app = raw; })).app;
  wrapped = profile pkgs.hello;
  configuredSource = pkgs.hello.overrideAttrs (_: { pname = "hm-configured-hello"; });
  fixedProfile = (pwrap.confinements.build { env.home = fixed.config; }
    (_: { app = raw; })).app;
  evaluate = binding: lib.evalModules { modules = [ ({ config, ... }: {
    options = {
      home.homeDirectory = lib.mkOption { type = lib.types.str; default = home.home.homeDirectory; };
      xdg.stateHome = lib.mkOption { type = lib.types.str; default = home.xdg.stateHome; };
      home.file = lib.mkOption { type = lib.types.attrsOf lib.types.raw; };
      home.packages = lib.mkOption { type = lib.types.listOf lib.types.package; };
      programs.app.package = lib.mkOption { type = lib.types.package; default = pkgs.hello; };
      programs.app.finalPackage = lib.mkOption { type = lib.types.package; readOnly = true; };
      generatedExec = lib.mkOption { type = lib.types.str; };
      assertions = lib.mkOption { type = lib.types.listOf lib.types.raw; default = []; };
      pwrap.maintenanceRoots = lib.mkOption { type = lib.types.listOf lib.types.str; default = home.pwrap.maintenanceRoots; };
    };
    config = {
      programs.app.finalPackage = config.programs.app.package.overrideAttrs (_: { pname = "hm-configured-hello"; });
      # Like real HM modules, file declarations can depend on package presence.
      home.file = lib.optionalAttrs (config.programs.app.finalPackage != null) home.home.file;
      home.packages = [ config.programs.app.finalPackage pkgs.coreutils pkgs.hello ];
      generatedExec = "${config.programs.app.finalPackage}/bin/hello";
    };
  }) pwrap.homeManagerModule { pwrap.programs.app = binding; } ]; };
  fixed = evaluate fixedProfile;
  missing = lib.evalModules { modules = [ {
    options.assertions = lib.mkOption { type = lib.types.listOf lib.types.raw; default = []; };
  } pwrap.homeManagerModule { pwrap.programs.missing = profile; } ]; };
  absent = lib.evalModules { modules = [ {
    options.assertions = lib.mkOption { type = lib.types.listOf lib.types.raw; default = []; };
    options.services.absent.package = lib.mkOption { type = lib.types.nullOr lib.types.package; default = null; };
  } pwrap.homeManagerModule { pwrap.services.absent = _: throw "null packages must not be wrapped"; } ]; };
  finalPackage = fixed.config.programs.app.finalPackage;
  rejected = value: !(builtins.tryEval (builtins.deepSeq value true)).success;
  policy = builtins.fromJSON (builtins.unsafeDiscardStringContext wrapped.pwrapManifest.text);
  final = builtins.fromJSON (builtins.unsafeDiscardStringContext finalPackage.pwrapManifest.text);
in
assert patched.persist == raw.persist;
assert patched.forbiddenWrites == [ "/home/alice/.local/state/home-manager" ];
assert patched.readOnly == [ "/etc/selected" ];
assert patched.immutableData == [ "/home/alice/.local/state/home-manager/gcroots/current-home/home-files/.app/settings" ];
assert (patcher patched).readOnly == patched.readOnly;
assert (patcher patched).immutableData == patched.immutableData;
assert (denied.readOnly or []) == [];
assert denied.immutableData == [];
assert rejected ((pwrap.patchers.homeManager (removeAttrs home [ "pwrap" ])) raw);
assert rejected ((pwrap.patchers.homeManager (home // { pwrap.maintenanceRoots = [ ".application" ]; })) raw);
assert rejected ((pwrap.patchers.homeManager (home // { pwrap.maintenanceRoots = [ ".app/subdirectory" ]; })) raw);
# Read-only resources do not require mutable activation authority.
assert ((pwrap.patchers.homeManager (removeAttrs home [ "pwrap" ])) { readOnly = [ "~/.app" ]; }).immutableData == patched.immutableData;
assert rejected (patcher { name = "bad"; writable = [ "~/.app/../.other" ]; });
assert rejected (patcher { name = "bad"; persist = [ "relative/app" ]; });
assert rejected (patcher { name = "bad"; writable = [ "~/.local/state" ]; });
assert rejected (patcher { name = "bad"; writable = [ "/" ]; });
assert rejected (patcher { name = "bad"; persist = [ "~/.local/state/home-manager/gcroots/current-home" ]; });
assert rejected ((pwrap.confinements.build {} ({ ... }: {
  bad = { executable = "hello"; immutableData = true; };
})).bad pkgs.hello);
assert (builtins.head fixed.config.home.packages).outPath == finalPackage.outPath;
assert (builtins.elemAt fixed.config.home.packages 1).outPath == pkgs.coreutils.outPath;
assert finalPackage ? pwrapManifest;
assert lib.all (entry: entry.assertion) fixed.config.assertions;
assert lib.any (entry: !entry.assertion) missing.config.assertions;
assert absent.config.services.absent.package == null && lib.all (entry: entry.assertion) absent.config.assertions;
assert rejected (evaluate (_: pkgs.hello)).config.programs.app.finalPackage.pwrapManifest;
assert fixed.config.generatedExec == "${finalPackage}/bin/hello";
# Explicit raw installation/source access stays possible; nothing blacklists it.
assert fixed.config.programs.app.package.outPath == pkgs.hello.outPath;
assert (builtins.elemAt fixed.config.home.packages 2).outPath == pkgs.hello.outPath;
assert policy.readOnly == [ "/etc/selected" ];
assert policy.immutableData == [ "/home/alice/.local/state/home-manager/gcroots/current-home/home-files/.app/settings" ];
assert policy.persist == [ "~/.app" ];
assert policy.forbiddenWrites == [ "/home/alice/.local/state/home-manager" ];
assert final.immutableData == policy.immutableData;
assert final.command == [ "${configuredSource}/bin/hello" ];
pkgs.runCommand "pwrap-home-manager-library-proof" {} ''
  test -x ${wrapped}/bin/hello
  test -x ${finalPackage}/bin/hello
  touch $out
''
