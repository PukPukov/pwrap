# Minimal upstream contract fixture: helpers accept a generation plus N entries.
{ pkgs, ... }:
let
  helper = name: pkgs.writeShellScript name ''
    exec "$RECORDER" host "$@"
  '';
  link = helper "link";
  cleanup = helper "cleanup";
in {
  inherit link cleanup;
  config.home.activation.linkGeneration.data = "${link} ${cleanup}";
}
