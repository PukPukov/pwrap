{ nixcordSource, sources }:
{ config, lib, pkgs, ... }:
let
  pwrap = import ../.. { inherit pkgs sources; };
  cfg = config.programs.nixcord;
  common = import "${nixcordSource}/modules/lib/mkCommonConfig.nix" {
    inherit config lib pkgs;
  };
  directories = lib.unique ([ cfg.configDir ] ++ map
    (branch: "${cfg.xdgConfigHome}/${branch}")
    [ "discord" "discordptb" "discordcanary" "discorddevelopment" ]);
  confine = name: script: let
    task = pwrap.mkFileTask {
      inherit name script;
      home = cfg.homeDirectory;
      persist = directories;
    };
  in "${task}/bin/${name}";
  scripts = common.mkActivationScripts (confine "nixcord-maintenance");
in {
  config = lib.mkIf (cfg.enable && cfg.user == "root") {
    # Never parse/write an internet-facing app's mutable files as unrestricted
    # host root during nixos-rebuild. Symlink escape stays inside the empty root.
    system.activationScripts = lib.mkMerge [
      (lib.mkIf cfg.discord.enable {
        disableDiscordUpdates.text = lib.mkForce scripts.disableDiscordUpdates;
        fixDiscordModules.text = lib.mkForce scripts.fixDiscordModules;
      })
      (lib.mkIf cfg.dorion.enable {
        setupDorionVencordSettings.text = lib.mkForce scripts.setupDorionVencordSettings;
      })
      (lib.mkIf (common.fileSpecs != []) {
        writeDiscordClientFiles.text = lib.mkForce (confine "nixcord-write-files" ''
          copy_file() {
            ${pkgs.coreutils}/bin/install -D -m "$3" "$1" "$2"
          }
          ${common.fileCopyCommands}
        '');
      })
    ];
  };
}
