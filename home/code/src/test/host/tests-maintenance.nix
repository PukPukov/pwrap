# Only synthetic paths under a fresh /var/tmp fixture, never actual HOME.
{ corepkgs, testRoot, sources }:
let
  pkgs = corepkgs;
  pwrap = import ../../main { inherit pkgs sources; };
  driver = (import ../../main/java.nix { inherit pkgs; }).tests;
  selected = "${testRoot}/shared/parent/app";
  makeTask = writable: pwrap.mkFileTask {
    name = if writable then "maintenance-write-proof" else "maintenance-read-proof";
    home = "${testRoot}/home";
    writable = pkgs.lib.optional writable selected;
    readOnly = pkgs.lib.optional (!writable) selected;
    runtime = [ driver ];
    inheritEnvironment = [ "PWRAP_MAINTENANCE_ALLOWED" ];
    script = ''exec ${driver}/bin/pwrap-tests _payload MAINTENANCE ${pkgs.lib.escapeShellArgs [ testRoot selected (if writable then "true" else "false") ]} "$@"'';
  };
  writeTask = makeTask true;
  readTask = makeTask false;
  createTask = pwrap.mkFileTask {
    name = "maintenance-create-proof";
    home = "${testRoot}/home";
    persist = [ selected ];
    script = ''printf created > ${pkgs.lib.escapeShellArg "${selected}/created"}'';
  };
in assert pkgs.lib.hasPrefix "/var/tmp/pwrap-maintenance-test-" testRoot;
pkgs.writeText "maintenance-fixture.json" (builtins.toJSON {
  inherit testRoot selected;
  tasks = {
    write = "${writeTask}/bin/maintenance-write-proof";
    read = "${readTask}/bin/maintenance-read-proof";
    create = "${createTask}/bin/maintenance-create-proof";
  };
})
