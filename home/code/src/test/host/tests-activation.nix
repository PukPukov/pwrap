# A normal synthetic org.freedesktop.Application, not an exploit fixture.
{ pkgs, testRoot, token, sources }:
let
  pwrap = import ../../main { inherit pkgs sources; };
  dbusName = "org.pwrap.Activation${token}";
  driver = (import ../../main/java.nix { inherit pkgs; }).tests;
  app = pkgs.writeShellScriptBin "activation-fixture" ''
    exec ${driver}/bin/pwrap-tests _fixture APPLICATION ${pkgs.lib.escapeShellArgs [ testRoot dbusName ]} "$@"
  '';
  wrapped = pwrap.mkApp {
    name = "activation-${token}";
    package = app;
    executable = "activation-fixture";
    writable = [ testRoot ];
    dbus = {
      own = [ dbusName ];
      activation = { name = dbusName; path = "/org/pwrap/Application"; };
    };
  };
in assert pkgs.lib.hasPrefix "/var/tmp/pwrap-activation-smoke-" testRoot;
assert builtins.match "[a-z0-9]+" token != null;
pkgs.writeText "activation-fixture.json" (builtins.toJSON {
  inherit testRoot;
  command = "${wrapped}/bin/activation-fixture";
})
