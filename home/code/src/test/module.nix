{ pkgs, nixpkgs, sources }:
let
  pwrap = import ../main { inherit pkgs sources; };
  emptySystem = modules: nixpkgs.lib.nixosSystem {
    system = pkgs.stdenv.hostPlatform.system;
    specialArgs.pwrapSources = sources;
    modules = modules ++ [{ system.stateVersion = "26.05"; }];
  };
  plain = emptySystem [];
  allDisabled = emptySystem [ ../main/module.nix ];
  missingIntegrations = emptySystem [ ../main/module.nix {
    programs.pwrap.integrations = {
      homeManager.users.root.maintenance = { apply = true; roots = [ ".app" ]; };
      nixcord.apply = true;
    };
  } ];
  unsupportedNixcordUser = emptySystem [ ../main/module.nix {
    options.programs.nixcord = {
      enable = pkgs.lib.mkOption { type = pkgs.lib.types.bool; };
      user = pkgs.lib.mkOption { type = pkgs.lib.types.str; };
    };
    config = {
      programs.nixcord = { enable = true; user = "alice"; };
      programs.pwrap.integrations.nixcord.apply = true;
    };
  } ];
  evaluated = nixpkgs.lib.nixosSystem {
    system = pkgs.stdenv.hostPlatform.system;
    specialArgs.pwrapSources = sources;
    modules = [ ../main/module.nix ../main/nixos {
      system.stateVersion = "26.05";
      programs.pwrap = {
        cli.enable = true;
        cli.register.probe = pwrap.mkApp { name = "probe"; package = pkgs.hello; };
      };
      security.pwrap.services.beta_test = {
        readOnly = [ "/etc/hosts" ];
        writable = [ "/var/lib/beta_test" ];
        addressFamilies = [ "AF_UNIX" ];
        egress = "deny";
      };
      security.pwrap.services.inherited_resolver = {
        egress = "internet";
      };
      systemd.services.inherited_resolver.serviceConfig = {
        ExecStart = "${pkgs.hello}/bin/hello";
        User = "nobody";
      };
      security.pwrap.services.internet_test = {
        egress = "internet";
        dns = [ "127.0.0.1" ];
      };
      systemd.services.internet_test.serviceConfig = {
        ExecStart = "${pkgs.hello}/bin/hello";
        User = "root";
      };
      systemd.services.beta_test.serviceConfig = {
        ExecStart = "${pkgs.hello}/bin/hello";
        ExecReload = [ "" "-${pkgs.coreutils}/bin/true" ];
        User = "nobody";
      };
    } ];
  };
  desktopOnly = nixpkgs.lib.nixosSystem {
    system = pkgs.stdenv.hostPlatform.system;
    specialArgs.pwrapSources = sources;
    modules = [ ../main/module.nix {
      system.stateVersion = "26.05";
      programs.pwrap.cli.enable = true;
      programs.pwrap.cli.network = { localDeniedPorts = [ 3128 ]; relayServices = [ "proxy.service" ]; };
      services.desktopManager.plasma6.enable = true;
    } ];
  };
  providerOnly = nixpkgs.lib.nixosSystem {
    system = pkgs.stdenv.hostPlatform.system;
    specialArgs.pwrapSources = sources;
    modules = [ ../main/module.nix {
      system.stateVersion = "26.05";
      services.pipewire = { enable = true; systemWide = true; pulse.enable = true; };
      programs.pwrap.providers = {
        audio.enable = true;
      };
    } ];
  };
  legacyWeb = emptySystem [ ../main/module.nix {
    programs.pwrap.providers.webBrowser.broker = null;
  } ];
  legacyWebRejected = !(builtins.tryEval (builtins.deepSeq legacyWeb.config.programs.pwrap true)).success;
  legacyRegistry = emptySystem [ ../main/module.nix {
    programs.pwrap.cli.profiles = {};
  } ];
  legacyRegistryRejected = !(builtins.tryEval (builtins.deepSeq legacyRegistry.config.programs.pwrap true)).success;
  legacyAudio = emptySystem [ ../main/module.nix {
    programs.pwrap.providers.audio.nixos.enable = true;
  } ];
  legacyAudioRejected = !(builtins.tryEval (builtins.deepSeq legacyAudio.config.programs.pwrap true)).success;
  badAudio = nixpkgs.lib.nixosSystem {
    system = pkgs.stdenv.hostPlatform.system;
    specialArgs.pwrapSources = sources;
    modules = [ ../main/module.nix {
      system.stateVersion = "26.05";
      services.pipewire = { enable = true; systemWide = true; pulse.enable = true; };
      programs.pwrap.providers.audio.enable = true;
      services.pipewire.extraConfig.pipewire-pulse."90-pwrap"."pulse.properties"."pulse.allow-module-loading" = pkgs.lib.mkOverride 0 true;
    } ];
  };
  providerConfig = providerOnly.config;
  providerMetadata = builtins.fromJSON providerConfig.environment.etc."pwrap/providers.json".text;
  invalidProfile = nixpkgs.lib.nixosSystem {
    system = pkgs.stdenv.hostPlatform.system;
    specialArgs.pwrapSources = sources;
    modules = [ ../main/module.nix {
      system.stateVersion = "26.05";
      programs.pwrap.cli = { enable = true; register.raw = pkgs.hello; };
    } ];
  };
  rawProfileRejected = !(builtins.tryEval (builtins.deepSeq invalidProfile.config.programs.pwrap.cli.register true)).success;
  c = evaluated.config;
  s = c.systemd.services.beta_test;
  units = c.systemd.units."beta_test.service".text;
  egress = builtins.fromJSON c.environment.etc."pwrap/service-egress.json".text;
  egressSlice = name: "system-pwrap${builtins.substring 0 24 (builtins.hashString "sha256" name)}.slice";
  registry = c.environment.etc."pwrap/profiles.json".text;
in
assert legacyWebRejected;
assert !(providerConfig.environment.etc ? "pwrap/web-browser.json");
assert !(providerConfig.environment.etc ? "pwrap/uri-handlers.json");
assert !(desktopOnly.options.programs.pwrap.providers ? webBrowser);
assert legacyRegistryRejected;
assert !(desktopOnly.options.programs.pwrap.cli ? profiles);
assert legacyAudioRejected;
assert !(desktopOnly.options.programs.pwrap.providers.audio ? nixos);
assert !(desktopOnly.options.programs.pwrap.providers ? homeFiles);
assert !(desktopOnly.options.programs.pwrap.providers ? homeManager);
assert !(pkgs.lib.hasAttrByPath [ "security" "pwrap" "network" ] desktopOnly.options);
assert !(desktopOnly.config.environment.etc ? "pwrap/home-files.json");
assert !(providerConfig.programs.pwrap.cli.enable);
assert (builtins.fromJSON desktopOnly.config.environment.etc."pwrap/network.json".text) == {
  localDeniedPorts = [ 3128 ]; relayServices = [ "proxy.service" ];
};
assert !(providerConfig.system.build ? pwrap);
assert !(providerConfig.environment.etc ? "pwrap/profiles.json");
assert providerMetadata == { audio.nixos.socketPath = "/run/pulse/native"; };
assert pkgs.lib.hasInfix "setfacl" providerConfig.systemd.sockets.pipewire-pulse.socketConfig.ExecStartPost;
assert providerConfig.services.pipewire.extraConfig.pipewire-pulse."90-pwrap"."pulse.properties"."pulse.allow-module-loading" == false;
assert pkgs.lib.any (a: !a.assertion && pkgs.lib.hasInfix "module loading" a.message) badAudio.config.assertions;
assert !(desktopOnly.config.programs.pwrap ? enable);
assert builtins.attrNames desktopOnly.options.programs.pwrap == [ "cli" "integrations" "providers" ];
assert pkgs.lib.filter (pkgs.lib.hasPrefix "pwrap/") (builtins.attrNames allDisabled.config.environment.etc) == [];
assert map (p: p.outPath) allDisabled.config.environment.systemPackages == map (p: p.outPath) plain.config.environment.systemPackages;
assert builtins.attrNames allDisabled.config.system.activationScripts == builtins.attrNames plain.config.system.activationScripts;
assert allDisabled.config.system.activationScripts == plain.config.system.activationScripts;
assert !(allDisabled.options ? home-manager);
assert !(allDisabled.options.programs ? nixcord);
assert !(allDisabled.config.system.build ? pwrap);
assert pkgs.lib.any (a: !a.assertion && pkgs.lib.hasInfix "Home Manager NixOS module" a.message) missingIntegrations.config.assertions;
assert pkgs.lib.any (a: !a.assertion && pkgs.lib.hasInfix "homeManager.source" a.message) missingIntegrations.config.assertions;
assert pkgs.lib.any (a: !a.assertion && pkgs.lib.hasInfix "Nixcord NixOS module" a.message) missingIntegrations.config.assertions;
assert pkgs.lib.any (a: !a.assertion && pkgs.lib.hasInfix "root-session Nixcord only" a.message) unsupportedNixcordUser.config.assertions;
assert !(pkgs.lib.hasAttrByPath [ "security" "pwrap" "services" ] desktopOnly.options);
assert !(desktopOnly.config.services.pipewire.extraConfig.pipewire-pulse ? "90-pwrap");
assert !(desktopOnly.config.environment.etc ? "pwrap/services.json");
assert !(desktopOnly.config.programs.pwrap ? appearance);
assert c.security.pwrap.services.inherited_resolver.dns == null;
assert pkgs.lib.any (p: p.network == "internet" && p.dns == null) (builtins.attrValues egress.policies);
assert rawProfileRejected;
assert s.confinement.enable;
assert s.serviceConfig.CapabilityBoundingSet == [ "" ];
assert s.serviceConfig.PrivatePIDs;
assert s.serviceConfig.PrivateDevices;
assert (s.serviceConfig.DeviceAllow or []) == [];
assert s.serviceConfig.RestrictAddressFamilies == [ "AF_UNIX" ];
assert s.serviceConfig.PrivateNetwork;
assert !c.systemd.services.internet_test.serviceConfig.PrivateNetwork;
assert pkgs.lib.hasInfix "pwrap-ipc-scope --allow-signals " s.serviceConfig.ExecStart;
assert builtins.intersectAttrs (builtins.getContext "${pkgs.hello}")
  (builtins.getContext s.serviceConfig.ExecStart) == builtins.getContext "${pkgs.hello}";
assert builtins.head s.serviceConfig.ExecReload == "";
assert pkgs.lib.hasPrefix "-/nix/store/" (builtins.elemAt s.serviceConfig.ExecReload 1);
assert pkgs.lib.hasInfix "pwrap-ipc-scope --allow-signals " (builtins.elemAt s.serviceConfig.ExecReload 1);
assert builtins.intersectAttrs (builtins.getContext "${pkgs.coreutils}")
  (builtins.getContext (builtins.elemAt s.serviceConfig.ExecReload 1)) == builtins.getContext "${pkgs.coreutils}";
assert pkgs.lib.all (cmd: pkgs.lib.hasInfix "pwrap-ipc-scope --allow-signals " cmd) s.serviceConfig.ExecStartPost;
assert builtins.elem "landlock_create_ruleset landlock_restrict_self" s.serviceConfig.SystemCallFilter;
assert builtins.elem "pwrap-egress.service" s.bindsTo;
assert !(pkgs.lib.hasInfix "bubblewrap" units);
assert pkgs.lib.hasInfix "probe" registry;
assert egress.table == "pwrap_services_egress";
assert builtins.length (builtins.attrNames egress.policies) == 3;
assert pkgs.lib.all (name: let
  unit = c.systemd.services.${name};
  slice = egressSlice name;
  sliceKey = pkgs.lib.removeSuffix ".slice" slice;
in unit.serviceConfig.Slice == slice
  && builtins.elem "pwrap-egress.service" unit.bindsTo
  && builtins.elem "pwrap-egress.service" unit.after
  && builtins.elem "pwrap-egress.service" unit.partOf
  && builtins.elem slice c.systemd.services.pwrap-egress.bindsTo
  && builtins.elem slice c.systemd.services.pwrap-egress.after
  && c.systemd.slices.${sliceKey}.unitConfig.StopWhenUnneeded == false
  && pkgs.lib.any (p: p.cgroup == "system.slice/${slice}") (builtins.attrValues egress.policies))
  [ "beta_test" "inherited_resolver" "internet_test" ];
assert pkgs.lib.hasInfix "_service-egress" c.systemd.services.pwrap-egress.script;
assert !(pkgs.lib.hasInfix "meta skuid" c.systemd.services.pwrap-egress.script);
assert pwrap.version == "0.1.0-beta.1";
pkgs.writeText "pwrap-module-proof" units
