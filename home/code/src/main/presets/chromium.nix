{ pkgs }:
{ name, network, desktopName }:
let
  inherit (pkgs) lib;
  state = ".config/${name}";
  cache = ".cache/${name}";
in {
  inherit name network;
  providers.uri.enable = network.remote or true;
  providers.openFile.enable = network.remote or true;
  dbus = if network.remote or true then import ./capabilities/notifications.nix
    else { talk = []; calls = []; broadcasts = []; };
  executable = name;
  aliases = lib.optional (name == "chromium") "chromium-browser";
  persist = [ "~/${state}" "~/${cache}" ];
  resources = { memoryMax = "12G"; tasksMax = 2048; };
  audio = true;
  arguments = lib.optionals (!(network.remote or true)) [ "--no-proxy-server" ];
  # Chromium's singleton key is its user-data-dir. A renamed command alone
  # would hand local-only URLs to the existing Internet browser.
  packageTransform = package: pkgs.symlinkJoin {
    name = "${name}-launcher";
    version = package.version or "0";
    meta = package.meta // { mainProgram = name; };
    postBuild = "ln -s ${package}/share/icons $out/share/icons";
    paths = [
      (pkgs.writeShellScriptBin name ''
        exec ${package}/bin/chromium --user-data-dir="$HOME"/${lib.escapeShellArg state} \
          --disk-cache-dir="$HOME"/${lib.escapeShellArg cache} --class=${lib.escapeShellArg name} "$@"
      '')
      (pkgs.makeDesktopItem {
        inherit name desktopName;
        exec = "${name} %U"; icon = "chromium"; terminal = false;
        categories = [ "Network" "WebBrowser" ]; startupWMClass = name;
      })
    ];
  };
}
