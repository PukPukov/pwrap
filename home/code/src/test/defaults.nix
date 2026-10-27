{ pkgs, sources }:
let
  pwrap = import ../main { inherit pkgs sources; };
  catalog = import ../main/presets { inherit pkgs; };
  check = import ../main/backends/nix/app-options.nix { inherit (pkgs) lib; };
  conf = import ../main/backends/nix/confinements.nix {
    inherit (pkgs) lib;
    presets = catalog;
    checkPreset = check;
    homeManagerPatcher = pwrap.patchers.homeManager;
    checkNetwork = network: (pwrap.configuration.check { inherit network; }).network;
    mkAppFor = _: preset: preset;
  };
  build = options: conf.build options;
  custom = build { settings.downloads = "/srv/shared-downloads"; }
    ({ presets1, presets2, ... }: {
      custom = { gui = true; executable = "hello"; network.remote = false;
        providers.uri.enable = false; writable = [ "/srv/custom" ]; gpu = false; };
      inherited = { gui = true; executable = "hello"; };
      flagged = { gui = true; executable = "hello"; };
      optout = {
        gui = true; desktop = false; gpu = false; sharedTmp = false;
        network.remote = false; writable = []; downloads = null;
        providers = { uri = false; openFile = false; audio = null; };
        dbus = { calls = []; broadcasts = []; talk = []; own = []; };
      };
      plain.executable = "hello";
      comparison = { gui = true; executable = "firefox"; };
      notes = presets2.logseq { graphs = [ "/srv/notes" ]; };
      firefox = presets1.firefox;
      unused = throw "unused profile body must remain lazy";
    });
  result = custom.custom pkgs.hello;
  inherited = custom.inherited pkgs.hello;
  plain = custom.plain pkgs.hello;
  fails = thunk: !(builtins.tryEval (builtins.deepSeq thunk true)).success;
  bad = build {} ({ ... }: { custom = { gui = true; typo = true; }; });
  invalidRecord = value: build {} ({ ... }: { custom = value; });
  manifest = ((pwrap.confinements.build { settings.downloads = "/srv/custom-downloads"; }
    ({ ... }: { custom = { gui = true; executable = "hello"; gpu = false; }; })).custom pkgs.hello).pwrapManifest;
  patched = build { patchers = [ (record: record // {
    arguments = (record.arguments or []) ++ [ "once" ];
  }) ]; } ({ ... }: { custom = { gui = true; executable = "hello"; }; });
  policy = builtins.fromJSON (builtins.unsafeDiscardStringContext manifest.text);
  defaults = pwrap.patchers.defaults { downloads = "/srv/shared-downloads"; };
  viaGui = pwrap.mkApp { name = "direct-gui"; package = pkgs.hello; gui = true; };
  directPolicy = builtins.fromJSON (builtins.unsafeDiscardStringContext viaGui.pwrapManifest.text);
  lateGui = build {
    settings.downloads = "/srv/late";
    patchers = [ (record: record // { gui = true; }) ];
  } (_: { late.executable = "hello"; });
  patchSeesGui = build {
    settings.downloads = "/srv/patched";
    patchers = [ (record: record // { readOnly = [ record.downloads ]; }) ];
  } (_: { custom.gui = true; });
  renamed = build {} ({ presets1, ... }: { other-browser = presets1.firefox; });
in
assert builtins.attrNames (catalog {}) == [ "presets1" "presets2" ];
assert result.desktop && !result.gpu;
assert !result.network.remote && !result.providers.uri.enable;
assert result.writable == [ "/srv/custom" ];
assert result.dbus.calls == (custom.firefox pkgs.hello).dbus.calls;
assert inherited.network.remote && inherited.desktop && inherited.gpu;
assert inherited.providers.uri.enable && inherited.sharedTmp && !inherited.audio;
assert inherited.writable == [] && inherited.downloads == "/srv/shared-downloads/inherited";
assert (custom.firefox pkgs.hello).downloads == "/srv/shared-downloads/firefox";
assert (renamed.other-browser pkgs.hello).downloads == "~/Downloads/other-browser";
assert inherited.providers.audio == "nixos";
assert pkgs.lib.getAttrs [ "desktop" "gpu" "sharedTmp" "network" "writable" "dbus" "providers" ]
  (custom.flagged pkgs.hello) == pkgs.lib.getAttrs [ "desktop" "gpu" "sharedTmp" "network" "writable" "dbus" "providers" ] inherited;
assert inherited.providers.uri.schemes == [ "http" "https" "tg" ];
assert inherited.providers.openFile.ask && !inherited.providers.uri.ask;
assert defaults (defaults { name = "app"; gui = true; }) == defaults { name = "app"; gui = true; };
assert !(defaults { name = "app"; gui = true; } ? gui);
assert defaults (defaults { gui = true; }) == defaults { gui = true; }; # identity still deferred
assert !((custom.optout pkgs.hello).providers.uri.enable);
assert !((custom.optout pkgs.hello).providers.openFile.enable);
assert !(custom.optout pkgs.hello).desktop && !(custom.optout pkgs.hello).gpu;
assert !(custom.optout pkgs.hello).sharedTmp && !(custom.optout pkgs.hello).network.remote;
assert (custom.optout pkgs.hello).writable == [] && (custom.optout pkgs.hello).dbus.calls == [];
assert (custom.optout pkgs.hello).downloads == null;
assert (lateGui.late pkgs.hello).desktop && (lateGui.late pkgs.hello).gpu;
assert (lateGui.late pkgs.hello).downloads == "/srv/late/late";
assert (patchSeesGui.custom pkgs.hello).readOnly == [ "/srv/patched/custom" ];
assert directPolicy.desktop && directPolicy.gpu && directPolicy.providers.uri.enable;
assert directPolicy.writable == [] && directPolicy.downloads == "~/Downloads/direct-gui" && !(directPolicy ? gui);
assert fails (check { name = "bad-gui"; gui = "true"; });
assert fails (check { name = "bad-gui"; gui = {}; });
assert !plain.desktop && !plain.gpu && !plain.network.remote && !plain.providers.uri.enable;
assert plain.writable == [] && plain.dbus.calls == [];
assert (custom.notes pkgs.hello).writable == [ "/srv/notes" ];
assert (patched.custom pkgs.hello).arguments == [ "once" ];
assert (custom.firefox pkgs.hello).executable == "firefox";
assert fails (bad.custom pkgs.hello);
assert fails ((invalidRecord 42).custom pkgs.hello);
assert fails ((invalidRecord []).custom pkgs.hello);
assert fails ((invalidRecord (_: {})).custom pkgs.hello);
assert fails (build {} (_: _: {})); # former two-argument callback, no fallback
assert builtins.attrNames (build {} (_: {})) == [];
assert fails (build {} (_: _: 42));
assert fails (build {} ({ ... }: 42));
assert fails (build {} 42);
assert (build { env.nixos = false; } ({ ... }: {
  custom = { gui = true; executable = "hello"; providers.uri.enable = false; };
})).custom pkgs.hello != null;
assert policy.desktop && !policy.gpu && !policy.audio;
assert policy.network == "internet" && policy.providers.uri.enable && policy.sharedTmp;
assert policy.writable == [] && policy.downloads == "/srv/custom-downloads/custom";
assert policy.dbusCalls != [] && !(builtins.elem "org.freedesktop.Notifications" policy.dbusTalk);
pkgs.runCommand "pwrap-defaults-proof" {} ''
  test -s ${manifest}
  touch $out
''
