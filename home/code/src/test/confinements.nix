{ pkgs, sources }:
let
  pwrap = import ../main { inherit pkgs sources; };
  # Test the real Nix schema/catalog; capture runtime overrides separately.
  checked = import ../main/backends/nix/app-options.nix { inherit (pkgs) lib; };
  conf = import ../main/backends/nix/confinements.nix {
    inherit (pkgs) lib;
    presets = import ../main/presets { inherit pkgs; };
    checkPreset = checked;
    homeManagerPatcher = pwrap.patchers.homeManager;
    checkNetwork = network: (pwrap.configuration.check { inherit network; }).network;
    mkAppFor = runtimeOverrides: preset: preset // { inherit runtimeOverrides; };
  };
  built = conf.build { settings.downloads = "/srv/downloads"; } ({ presets1, presets2, ... }: {
    firefox = presets1.firefox // { executable = "firefox-esr"; network = presets1.firefox.network // { localhost = true; }; };
    logseq = presets2.logseq { graphs = [ "/srv/notes" ]; };
    deluge = presets2.deluge { directories = [ "/srv/torrents" ]; };
    chromium = presets2.chromium { name = "chromium"; };
    chromium-local = presets2.chromium { name = "chromium-local"; network = { localhost = true; remote = false; }; };
  });
  catalog = import ../main/presets { inherit pkgs; } {};
  defaults = conf.build {} ({ presets1, ... }: presets1);
  empty = conf.build {} (_: {});
  clearedBus = conf.build {} ({ presets1, ... }: {
    firefox = presets1.firefox // { dbus = {}; };
  });
  attempt = thunk: builtins.tryEval (builtins.deepSeq thunk true);
  fails = thunk: !(attempt thunk).success;
  badRecord = conf.build {} ({ ... }: { firefox = 42; });
  badField = conf.build {} ({ presets1, ... }: { firefox = presets1.firefox // { typo = true; }; });
  badNested = conf.build {} ({ presets1, ... }: { firefox = presets1.firefox // { network = { executable = "firefox"; }; }; });
  badBool = conf.build {} ({ presets1, ... }: { firefox = presets1.firefox // { network.remote = "yes"; }; });
  badPaths = conf.build {} ({ presets2, ... }: { logseq = presets2.logseq { graphs = "wrong"; }; });
  badFunction = conf.build {} ({ presets2, ... }: { logseq = presets2.logseq; });
  badName = conf.build {} ({ presets2, ... }: { wrong = presets2.chromium { name = "other"; }; });
  lazy = conf.build {} ({ ... }: { unused = throw "unused profile must remain lazy"; });
  patched = conf.build {
    settings.downloads = "/srv/common-downloads";
    env.nixos = true;
    patchers = [
      (preset: preset // {
        readOnly = (preset.readOnly or []) ++ (preset.writable or []);
        arguments = (preset.arguments or []) ++ [ "first" ];
        environment = (preset.environment or {}) // {
          FINAL_EXECUTABLE = preset.executable;
          SELECTED_AUDIO = preset.providers.audio;
        };
      })
      (preset: preset // {
        readOnly = preset.readOnly ++ [ "/srv/second" ];
        arguments = preset.arguments ++ [ "second" ];
      })
    ];
  } ({ presets1, presets2, ... }: {
    firefox = presets1.firefox // { executable = "firefox-esr"; providers = presets1.firefox.providers // { audio = "direct"; }; writable = [ "/srv/final-downloads" ]; readOnly = [ "/srv/original" ]; };
    inherit (presets1) thunderbird;
    logseq = presets2.logseq { graphs = [ "/srv/final-notes" ]; };
    custom = { executable = "hello"; writable = [ "/srv/custom" ]; providers.audio = "direct"; };
  });
  noNixos = conf.build { env.nixos = false; } ({ presets1, ... }: presets1);
  explicitProviders = conf.build { env.nixos = false; } ({ presets1, ... }: {
    firefox = presets1.firefox // { providers = { audio = "direct"; uri.enable = true; openFile.enable = true; }; };
  });
  home = {
    pwrap.maintenanceRoots = [ ".initial" ".extra" ];
    home.homeDirectory = "/home/alice";
    xdg.stateHome = "/home/alice/.local/state";
    home.file = {
      initial = { enable = true; target = ".initial/settings"; source = throw "HM source must stay lazy"; };
      extra = { enable = true; target = ".extra/custom/settings"; source = throw "HM source must stay lazy"; };
      unrelated = { enable = true; target = ".ssh/key"; source = throw "ungranted HM key forced"; };
    };
    home-files = throw "aggregate HM generation must stay lazy";
    unrelated = throw "env.home must not be deep-forced";
  };
  addGrant = preset: preset // { persist = (preset.persist or []) ++ [ "~/.extra" ]; };
  envHome = conf.build { env = { inherit home; }; patchers = [ addGrant ]; }
    ({ ... }: { custom.persist = [ "~/.initial" ]; });
  explicitHome = conf.build { patchers = [ addGrant (pwrap.patchers.homeManager home) ]; }
    ({ ... }: { custom.persist = [ "~/.initial" ]; });
  guiHome = conf.build { env = { inherit home; }; settings.downloads = "~/.extra"; }
    (_: { custom.gui = true; });
  guiExplicitHome = conf.build {
    settings.downloads = "~/.extra";
    patchers = [ (pwrap.patchers.homeManager home) ];
  } (_: { custom.gui = true; });
  autoAudio = conf.build { env.nixos = true; } (_: { custom.audio = true; });
  directAudio = conf.build { env.nixos = true; }
    (_: { custom = { audio = true; providers.audio = "direct"; }; });
  unknownAudio = conf.build { env.nixos = false; } (_: { custom.audio = true; });
  lazyHome = conf.build { env.home.unrelated = throw "unused env.home forced"; } ({ presets1, ... }: presets1);
  envManifest = (pwrap.confinements.build { env = { inherit home; }; patchers = [ addGrant ]; }
    ({ ... }: { hm-probe = { executable = "hello"; persist = [ "~/.initial" ]; }; })).hm-probe pkgs.hello;
  explicitManifest = (pwrap.confinements.build { patchers = [ addGrant (pwrap.patchers.homeManager home) ]; }
    ({ ... }: { hm-probe = { executable = "hello"; persist = [ "~/.initial" ]; }; })).hm-probe pkgs.hello;
  lateDesktop = conf.build {
    patchers = [ (preset: preset // { desktop = true; }) ];
  } ({ ... }: { custom.executable = "hello"; });
  lateGpuOptout = conf.build {
    patchers = [ (preset: preset // { desktop = true; }) ];
  } ({ ... }: { custom = { executable = "hello"; gpu = false; }; });
  once = conf.build {
    patchers = [ (preset: preset // { arguments = (preset.arguments or []) ++ [ "once" ]; }) ];
  } ({ presets1, ... }: presets1);
  wrongPatcherResult = result: conf.build { patchers = [ (_: result) ]; } ({ presets1, ... }: presets1);
  intermediateInvalid = field: value: conf.build {
    patchers = [ (preset: preset // { ${field} = value; }) (preset: removeAttrs preset [ field ]) ];
  } ({ presets1, ... }: presets1);
  restoredName = conf.build {
    patchers = [ (preset: preset // { name = "wrong"; }) (preset: preset // { name = "firefox"; }) ];
  } ({ presets1, ... }: presets1);
  missingPatchedName = conf.build { patchers = [ (preset: removeAttrs preset [ "name" ]) ]; } ({ presets1, ... }: presets1);
  wrongPatchedName = conf.build { patchers = [ (preset: preset // { name = "other"; }) ]; } ({ presets1, ... }: presets1);
  invalidBeforePatch = conf.build { patchers = [ (preset: removeAttrs preset [ "typo" ]) ]; }
    ({ ... }: { firefox.typo = true; });
  invalidPatch = conf.build { patchers = [ (preset: preset // { typo = true; }) ]; }
    ({ presets1, ... }: presets1);
  compiledProfiles = pwrap.confinements.build {
    patchers = [ (preset: preset // { arguments = (preset.arguments or []) ++ [ "once" ]; }) ];
  } ({ ... }: { probe.executable = "hello"; });
  compiled = compiledProfiles.probe pkgs.hello;
  commonNetwork = { localDeniedPorts = [ 2080 ]; relayServices = [ "relay.service" ]; };
  withNetwork = conf.build { settings.network = commonNetwork; } ({ presets1, ... }: presets1);
  emptyNetwork = conf.build { settings.network = {}; } ({ presets1, ... }: presets1);
  configuredNetwork = pwrap.configure {
    network = { localDeniedPorts = [ 3128 ]; relayServices = [ "existing.service" ]; };
    providers.audio.nixos.socketPath = "/run/test-pulse/native";
  };
  inheritedNetworkProfiles = configuredNetwork.confinements.build {} ({ ... }: { inherited.executable = "hello"; });
  overriddenNetworkProfiles = configuredNetwork.confinements.build { settings.network = commonNetwork; }
    ({ ... }: { overridden.executable = "hello"; });
  inheritedNetworkApp = inheritedNetworkProfiles.inherited pkgs.hello;
  overriddenNetworkApp = overriddenNetworkProfiles.overridden pkgs.hello;
  patchedLazy = conf.build { patchers = [ (preset: assert preset.name != "unused"; preset) ]; }
    ({ ... }: { unused = throw "unused patched profile must remain lazy"; });
  manifest = builtins.fromJSON (builtins.unsafeDiscardStringContext compiled.pwrapManifest.text);
  envHm = builtins.fromJSON (builtins.unsafeDiscardStringContext envManifest.pwrapManifest.text);
  explicitHm = builtins.fromJSON (builtins.unsafeDiscardStringContext explicitManifest.pwrapManifest.text);
in
assert builtins.attrNames empty == [];
assert (clearedBus.firefox pkgs.hello).dbus.talk == [];
assert (clearedBus.firefox pkgs.hello).dbus.own == [];
# Every invoked profile confines its package. There is no passthrough selector.
assert builtins.functionArgs conf.build == { env = true; settings = true; patchers = true; };
assert (withNetwork.firefox pkgs.hello).runtimeOverrides.network == commonNetwork;
assert (defaults.firefox pkgs.hello).runtimeOverrides == {};
assert (emptyNetwork.firefox pkgs.hello).runtimeOverrides.network == { localDeniedPorts = []; relayServices = []; };
assert fails (conf.build { settings = []; } ({ presets1, ... }: presets1));
assert fails (conf.build { settings = "wrong"; } ({ presets1, ... }: presets1));
assert fails (conf.build { env.nixos = "true"; } ({ presets1, ... }: presets1));
assert fails (conf.build { env = []; } ({ presets1, ... }: presets1));
assert fails (conf.build { env.home = []; } ({ presets1, ... }: presets1));
assert fails (conf.build { env.home = pkgs.hello; } ({ presets1, ... }: presets1));
assert fails (conf.build { env.unknown = true; } ({ presets1, ... }: presets1));
assert fails (conf.build { settings.nixos = true; } ({ presets1, ... }: presets1));
assert fails (conf.build { settings.providers.audio = "nixos"; } ({ presets1, ... }: presets1));
assert builtins.attrNames lazyHome == builtins.attrNames defaults;
assert (envHome.custom pkgs.hello).readOnly == (explicitHome.custom pkgs.hello).readOnly;
assert (envHome.custom pkgs.hello).readOnly == [];
assert (envHome.custom pkgs.hello).immutableData == (explicitHome.custom pkgs.hello).immutableData;
assert (envHome.custom pkgs.hello).immutableData == [
  "/home/alice/.local/state/home-manager/gcroots/current-home/home-files/.extra/custom/settings"
  "/home/alice/.local/state/home-manager/gcroots/current-home/home-files/.initial/settings"
];
assert (envHome.custom pkgs.hello).forbiddenWrites == [ "/home/alice/.local/state/home-manager" ];
assert envManifest.pwrapManifest.outPath == explicitManifest.pwrapManifest.outPath;
assert (guiHome.custom pkgs.hello).downloads == "~/.extra/custom";
assert (guiHome.custom pkgs.hello).immutableData == [
  "/home/alice/.local/state/home-manager/gcroots/current-home/home-files/.extra/custom/settings"
];
assert (guiHome.custom pkgs.hello).immutableData == (guiExplicitHome.custom pkgs.hello).immutableData;
assert (autoAudio.custom pkgs.hello).providers.audio == "nixos";
assert (directAudio.custom pkgs.hello).providers.audio == "direct";
assert fails (unknownAudio.custom pkgs.hello);
assert fails (noNixos.firefox pkgs.hello);
assert (explicitProviders.firefox pkgs.hello).audio;
assert (explicitProviders.firefox pkgs.hello).providers.uri.enable;
assert (explicitProviders.firefox pkgs.hello).providers.audio == "direct";
assert !((explicitProviders.firefox pkgs.hello).providers ? webBrowser);
# Common networking is validated even if no returned profile is invoked.
assert fails (conf.build { settings.network = "internet"; } ({ presets1, ... }: presets1));
assert fails (conf.build { settings.network.localDeniedPorts = [ "53" ]; } ({ presets1, ... }: presets1));
assert fails (conf.build { settings.network.localDeniedPorts = [ 0 ]; } ({ presets1, ... }: presets1));
assert fails (conf.build { settings.network.relayServices = [ "../escape.service" ]; } ({ presets1, ... }: presets1));
assert fails (conf.build { settings.network.relayServices = [ "..escape.service" ]; } ({ presets1, ... }: presets1));
assert fails (conf.build { settings.network.unknown = true; } ({ presets1, ... }: presets1));
# Patches see final merged presets1, run in order, and apply once to every path.
assert (patched.firefox pkgs.hello).readOnly == [ "/srv/original" "/srv/final-downloads" "/srv/second" ];
assert (patched.firefox pkgs.hello).arguments == [ "first" "second" ];
assert (patched.firefox pkgs.hello).environment.FINAL_EXECUTABLE == "firefox-esr";
assert (patched.firefox pkgs.hello).environment.SELECTED_AUDIO == "direct";
assert (patched.thunderbird pkgs.hello).readOnly == [ "/srv/second" ];
assert (patched.thunderbird pkgs.hello).downloads == "/srv/common-downloads/thunderbird";
assert (patched.logseq pkgs.hello).readOnly == [ "/srv/final-notes" "/srv/second" ];
assert (patched.custom pkgs.hello).readOnly == [ "/srv/custom" "/srv/second" ];
assert (once.firefox pkgs.hello).arguments == [ "once" ];
# Validation before patches must not freeze derived gpu=desktop defaults.
assert (lateDesktop.custom pkgs.hello).gpu;
assert !(lateGpuOptout.custom pkgs.hello).gpu;
assert fails ((wrongPatcherResult 42).firefox pkgs.hello);
assert fails ((wrongPatcherResult (_: {})).firefox pkgs.hello);
assert fails ((wrongPatcherResult pkgs.hello).firefox pkgs.hello);
assert fails ((intermediateInvalid "typo" true).firefox pkgs.hello);
assert fails ((intermediateInvalid "audio" "yes").firefox pkgs.hello);
assert fails (wrongPatchedName.firefox pkgs.hello);
assert fails (restoredName.firefox pkgs.hello);
assert fails (missingPatchedName.firefox pkgs.hello);
assert fails (invalidBeforePatch.firefox pkgs.hello);
assert fails (invalidPatch.firefox pkgs.hello);
assert builtins.attrNames patchedLazy == [ "unused" ];
assert fails (conf.build { patchers = "bad"; } ({ presets1, ... }: presets1));
assert fails (conf.build { patchers = [ 42 ]; } ({ presets1, ... }: presets1));
assert fails (conf.build { patchers = {}; } ({ presets1, ... }: presets1));
assert builtins.functionArgs catalog.presets2.logseq == { graphs = false; };
assert builtins.functionArgs catalog.presets2.deluge == { directories = false; };
assert (builtins.functionArgs catalog.presets2.chromium).name == false;
assert fails (conf.build { settings.downloads = 42; } ({ presets1, ... }: presets1));
assert builtins.attrNames defaults == [ "cinny" "discord" "filezilla" "firefox" "localsend" "telegram" "thunderbird" ];
assert builtins.attrNames built == [ "chromium" "chromium-local" "deluge" "firefox" "logseq" ];
assert (built.firefox pkgs.hello).executable == "firefox-esr";
assert (built.firefox pkgs.hello).network.localhost && (built.firefox pkgs.hello).network.remote;
assert (built.firefox pkgs.hello).writable == [] && (built.firefox pkgs.hello).downloads == "/srv/downloads/firefox";
assert (built.logseq pkgs.hello).writable == [ "/srv/notes" ];
assert (built.deluge pkgs.hello).writable == [ "/srv/torrents" ] && (built.deluge pkgs.hello).downloads == "/srv/downloads/deluge";
assert (built.chromium-local pkgs.hello).downloads == "/srv/downloads/chromium-local";
assert (built.chromium pkgs.hello).downloads != (built.chromium-local pkgs.hello).downloads;
assert (built.chromium-local pkgs.hello).persist == [ "~/.config/chromium-local" "~/.cache/chromium-local" ];
assert !(built.chromium-local pkgs.hello).providers.uri.enable;
assert (built.chromium-local pkgs.hello).dbus.talk == [];
assert fails (defaults.firefox 42);
assert builtins.attrNames lazy == [ "unused" ];
assert fails (badRecord.firefox pkgs.hello);
assert fails (badField.firefox pkgs.hello);
assert fails (badNested.firefox pkgs.hello);
assert fails (badBool.firefox pkgs.hello);
assert fails (badPaths.logseq pkgs.hello);
assert fails (badFunction.logseq pkgs.hello);
assert fails (badName.wrong pkgs.hello);
assert fails (conf.build {} ({ ... }: 42));
assert fails (conf.build {} (_: 42));
assert fails (checked { name = "test"; resources.tasksMax = "512"; });
assert fails (checked { name = "test"; packageTransform = 42; });
assert (checked { name = "test"; }).resources.tasksMax == 512;
# Desktop convenience does not grant a GPU to headless presets1, and an explicit
# opt-out wins over the desktop default. Headless compute may opt in explicitly.
assert !(checked { name = "headless"; }).gpu;
assert (checked { name = "gui"; desktop = true; }).gpu;
assert !(checked { name = "software-gui"; desktop = true; gpu = false; }).gpu;
assert (checked { name = "compute"; gpu = true; }).gpu;
assert fails (checked { name = "bad"; gpu = "false"; });
assert fails (checked { name = "run"; sharedTmp = true; });
# Command names are interpolated into build shell snippets. Reject
# metacharacters, including names obtained implicitly from package metadata.
assert fails (checked { name = "bad"; executable = "$(touch pwn)"; });
assert fails (checked { name = "bad"; aliases = [ "$(touch pwn)" ]; });
assert fails (checked { name = "bad"; aliases = [ ".." ]; });
assert fails (checked { name = "bad"; executable = "."; });
assert fails (pwrap.mkApp { name = "bad"; package = pkgs.hello.overrideAttrs (_: { meta.mainProgram = "$(touch pwn)"; }); }).drvPath;
assert fails (pwrap.mkApp { name = "bad"; package = pkgs.hello; packageTransform = package: package.overrideAttrs (_: { meta.mainProgram = "hello'; touch pwn; '"; }); }).drvPath;
assert (checked { name = "good"; executable = ".Discord-wrapped"; aliases = [ "7z" "a+b" ]; }).executable == ".Discord-wrapped";
assert fails (pwrap.mkApp { name = "bad"; package = pkgs.hello; network.remote = "false"; }).drvPath;
assert fails (pwrap.mkApp { name = "bad"; package = pkgs.hello; packageTransform = _: 42; }).drvPath;
assert builtins.tail manifest.command == [ "once" ] && manifest.name == "probe";
assert envHm == explicitHm;
assert envHm.readOnly == [] && builtins.length envHm.immutableData == 2;
assert envHm.forbiddenWrites == [ "/home/alice/.local/state/home-manager" ];
pkgs.runCommand "pwrap-confinements-proof" { nativeBuildInputs = [ pkgs.jq ]; } ''
  test -s ${compiled.pwrapManifest}
  test -s ${envManifest.pwrapManifest}
  test -s ${explicitManifest.pwrapManifest}
  check_runtime() {
    local wrapper=$1 policy=$2 expected=$3 runner config
    runner=$(sed -n 's/^exec \([^ ]*\) profile .*/\1/p' "$wrapper")
    config=$(sed -n 's/^exec [^ ]* \([^ ]*-pwrap-runtime.json\) .*/\1/p' "$runner")
    jq -e --argjson expected "$expected" '.metadata.network == $expected
      and .metadata.providers.audio.nixos.socketPath == "/run/test-pulse/native"' "$config" > /dev/null
    # CLI dispatch must retain the same declarative network policy.
    jq -se '.[0].networkConfig == .[1].metadata.network' "$policy" "$config" > /dev/null
  }
  check_runtime ${inheritedNetworkApp}/bin/hello ${inheritedNetworkApp.pwrapManifest} '{"localDeniedPorts":[3128],"relayServices":["existing.service"]}'
  check_runtime ${overriddenNetworkApp}/bin/hello ${overriddenNetworkApp.pwrapManifest} '${builtins.toJSON commonNetwork}'
  touch $out
''
