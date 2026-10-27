{ pkgs }:
{ downloads ? "~/Downloads", nixos ? true }:
assert pkgs.lib.assertMsg (builtins.isBool nixos) "pwrap: env.nixos must be a boolean";
assert pkgs.lib.assertMsg (builtins.isString downloads && downloads != "") "pwrap: downloads must be a nonempty string";
let
  inherit (pkgs) lib;
  defaults = import ./defaults.nix { inherit lib; } { inherit downloads nixos; };
  gui = preset: defaults ({ gui = true; } // preset);
in {
  presets1 = lib.mapAttrs (_: gui) {

    firefox = {
      executable = "firefox";
      aliases = [ "firefox" ];
      resources = { memoryMax = "12G"; tasksMax = 2048; };
      persist = [ "~/.mozilla" "~/.cache/mozilla" ];
  
      audio = true;
      dbus = {
        talk = [ "org.mozilla.firefox.*" ];
        own = [ "org.mozilla.firefox.*" ];
        instancePrefix = "org.mozilla.firefox.";
      };
    };
    thunderbird = {
      executable = "thunderbird";
      persist = [ "~/.thunderbird" "~/.cache/thunderbird" ];
      audio = true;
      dbus = {
        talk = [ "org.mozilla.thunderbird.*" ];
        own = [ "org.mozilla.thunderbird.*" ];
        instancePrefix = "org.mozilla.thunderbird.";
      };
    };
    discord = {
      executable = "discord";
      aliases = [ "Discord" ]; # Upstream desktop entry uses the capitalized name.
      persist = [ "~/.config/discord" "~/.config/Vencord" "~/.config/Equicord" "~/.cache/discord" ];
      audio = true;
      arguments = [ "--ozone-platform=wayland" ];
    };
    telegram = {
      executable = "Telegram";
      aliases = [ "telegram-desktop" ];
      persist = [ "~/.local/share/TelegramDesktop" "~/.config/telegram-desktop" ];
      audio = true;
      dbus = {
        talk = [ "org.telegram.desktop" ];
        own = [ "org.telegram.desktop" ];
        activation = { name = "org.telegram.desktop"; path = "/org/telegram/desktop"; };
      };
    };
    filezilla = {
      executable = "filezilla";
      persist = [ "~/.config/filezilla" ];
    };
    localsend = {
      executable = "localsend_app"; network.lan = true;
      persist = [ "~/.local/share/org.localsend.localsend_app" "~/.config/localsend" ];
    };
    cinny = {
      executable = "cinny";
      persist = [ "~/.config/in.cinny.app" "~/.local/share/in.cinny.app" "~/.local/share/cinny" "~/.cache/in.cinny.app" ];
      audio = true;
      environment.WEBKIT_DISABLE_SANDBOX_THIS_IS_DANGEROUS = "1"; # outer sandbox supplies the boundary
    };
  };
  presets2 = {
    logseq = { graphs }: gui {
      executable = "logseq";
      persist = [ "~/.config/Logseq" "~/.logseq" ];
      # Only the existing graph, not the surrounding notes/database directory.
      writable = graphs;
      arguments = [ "--ozone-platform=wayland" ];
    };
    deluge = { directories }: gui {
      executable = "deluge";
      aliases = [ "deluge-gtk" ]; # Menu entry uses this binary, not `deluge`.
      persist = [ "~/.config/deluge" "~/.cache/deluge" ];
      writable = directories;
    };
    chromium = { name, desktopName ? name, network ? {} }:
      gui ((import ./chromium.nix { inherit pkgs; }) { inherit name desktopName network; });
  };
}
