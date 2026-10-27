# Native test tool only, not a dependency of pwrap's runtime.
{ pkgs }:
let driver = (import ../../main/java.nix { inherit pkgs; }).tests;
in pkgs.writeShellScriptBin "test-pwrap-dbus" ''
  exec ${driver}/bin/pwrap-tests _host "$1" ${driver}/bin/pwrap-tests DBUS
''
