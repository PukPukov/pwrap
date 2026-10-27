# Closed Nix preset schema shared by confinements.build and mkApp.
{ lib }:
let
  inherit (lib) types mkOption;
  strings = types.listOf (types.strMatching ".+");
  commandName = types.addCheck (types.strMatching "[A-Za-z0-9_+.-]+")
    (name: name != "." && name != "..");
  option = type: default: mkOption { inherit type default; };
  group = options: option (types.submodule { inherit options; }) {};
  operation = options: option (types.coercedTo types.bool (enable: { inherit enable; })
    (types.submodule { inherit options; })) {};
  # A schema alone has no host context. confinements supplies env.nixos.
  defaults = import ../../presets/defaults.nix { inherit lib; } { nixos = false; };
  uriTypes = import ./uri-types.nix { inherit lib; };
  schema = {
    name = mkOption { type = types.strMatching "[A-Za-z0-9_-]+"; };
    executable = option (types.nullOr commandName) null;
    aliases = option (types.listOf commandName) [];
    arguments = option (types.listOf types.str) [];
    readOnly = option strings [];
    immutableData = option strings [];
    writable = option strings [];
    persist = option strings [];
    downloads = option (types.nullOr (types.strMatching ".+")) null;
    forbiddenWrites = option strings [];
    runtime = option (types.listOf types.package) [];
    network = group (lib.genAttrs [ "localhost" "remote" "lan" "unrestricted" ] (_: option types.bool false));
    ipc = group {
      hostAbstractSockets = option types.bool false;
    };
    resources = group {
      memoryMax = option (types.strMatching "[1-9][0-9]*[KMGTPE]?") "4G";
      tasksMax = option types.ints.positive 512;
    };
    dbus = group {
      calls = option (import ./dbus-types.nix { inherit lib; }) [];
      broadcasts = option (import ./dbus-types.nix { inherit lib; }) [];
      talk = option strings [];
      own = option strings [];
      instancePrefix = option (types.nullOr types.str) null;
      activation = option (types.nullOr (types.submodule { options = {
        name = mkOption { type = types.strMatching ".+"; };
        path = mkOption { type = types.strMatching "/.*"; };
      }; })) null;
    };
    desktop = option types.bool false;
    gpu = mkOption { type = types.bool; default = false; };
    audio = option types.bool false;
    providers = group {
      audio = option (types.nullOr (types.enum [ "direct" "nixos" ])) null;
      openFile = operation {
        enable = option types.bool false;
        ask = option types.bool true;
      };
      uri = operation {
        enable = option types.bool false;
        ask = option types.bool false;
        schemes = (option (types.listOf uriTypes.externalScheme) [ "http" "https" "tg" ]) // {
          apply = schemes: lib.sort builtins.lessThan (lib.unique schemes);
        };
      };
    };
    sharedTmp = option types.bool false;
    environment = option (types.addCheck (types.attrsOf types.str) (env:
      builtins.all (key: builtins.match "[A-Za-z_][A-Za-z0-9_]*" key != null) (builtins.attrNames env))) {};
    packageTransform = option (types.functionTo types.package) lib.id;
  };
in raw:
assert lib.assertMsg (builtins.isAttrs raw) "pwrap: preset must be an attribute set";
let
  preset = defaults raw;
  evaluated = lib.evalModules { modules = [{
    options.preset = mkOption { type = types.submodule {
      options = schema;
      config.gpu = lib.mkDefault (preset.desktop or false);
    }; };
    config.preset = preset;
  }]; };
  checked = evaluated.config.preset;
  policy = import ../../core/policy.nix { inherit (checked) resources network dbus ipc; };
in assert lib.assertMsg (!checked.providers.uri.enable || checked.providers.uri.schemes != [])
  "pwrap: enabled OpenURI needs at least one permitted scheme";
assert lib.assertMsg (!checked.sharedTmp || checked.name != "run")
  "pwrap: sharedTmp requires a named profile (run is reserved for ad-hoc launches)";
assert lib.assertMsg (!checked.audio || checked.providers.audio != null)
  "pwrap: audio = true requires an explicit providers.audio (nixos or direct)";
assert lib.assertMsg (policy.network != "local" || !(checked.providers.openFile.enable || checked.providers.uri.enable ||
  builtins.any (field: checked.dbus.${field} != []) [ "talk" "own" "calls" "broadcasts" ]))
  "pwrap: local-only mode forbids host URL and D-Bus brokers";
builtins.deepSeq (checked // { runtime = map (package: package.outPath) checked.runtime; }) checked
