# Optional CLI and explicit host providers. Neither requires the other.
{ config, lib, pkgs, pwrapSources, ... }:
let
  cfg = config.programs.pwrap;
  pwrap = import ../.. { inherit pkgs; sources = pwrapSources; };
  audio = cfg.providers.audio;
  inherit (pwrap.configuration) options;
  desktop = cfg.providers.desktop;
  settings = lib.optionalAttrs (desktop.files != {} || desktop.readOnly != []
    || desktop.environment != {} || desktop.runtimeRoots != []) { inherit desktop; }
    // lib.optionalAttrs audio.enable { providers.audio.nixos = { inherit (audio) socketPath; }; }
    // lib.optionalAttrs cfg.cli.enable { inherit (cfg.cli) network; profiles = cfg.cli.register; };
  configured = pwrap.configure settings;
  moduleLoadingDisabled = lib.all (fragment:
    (lib.attrByPath [ "pulse.properties" "pulse.allow-module-loading" ] false fragment) == false
  ) (lib.attrValues config.services.pipewire.extraConfig.pipewire-pulse);
in {
  options.programs.pwrap = {
    cli = {
      enable = lib.mkEnableOption "install the pwrap command and CLI profile registry";
      register = options.profiles;
      inherit (options) network;
    };
    providers = {
      inherit (options) desktop;
      audio = {
        enable = lib.mkEnableOption "the system-wide PipeWire Pulse adapter for pwrap's nixos audio provider";
        user = lib.mkOption { type = lib.types.str; default = "root"; };
        socketPath = lib.mkOption { type = lib.types.strMatching "/.*"; default = "/run/pulse/native"; };
      };
    };
  };
  config = lib.mkMerge [
    {
      # Provider metadata also serves independently built wrappers when CLI is off.
      environment.etc = pwrap.configuration.files configured.configuration.metadata;
    }
    (lib.mkIf cfg.cli.enable {
      environment.systemPackages = [ configured.runner ];
      system.build.pwrap = configured.runner;
    })
    (lib.mkIf audio.enable {
      assertions = [
        {
          assertion = config.services.pipewire.enable && config.services.pipewire.systemWide
            && config.services.pipewire.pulse.enable;
          message = "pwrap providers.audio requires an enabled system-wide PipeWire Pulse server";
        }
        {
          assertion = moduleLoadingDisabled;
          message = "pwrap providers.audio requires Pulse module loading to remain literally false in every configured Pulse fragment";
        }
      ];
      systemd.sockets.pipewire-pulse.socketConfig.ExecStartPost =
        "${pkgs.acl}/bin/setfacl -m ${lib.escapeShellArg "u:${audio.user}:rw"} ${lib.escapeShellArg audio.socketPath}";
      services.pipewire.extraConfig.pipewire-pulse."90-pwrap"."pulse.properties"."pulse.allow-module-loading" = lib.mkForce false;
    })
  ];
}
