{ pkgs, sources }:
let
  pwrap = import ../main { inherit pkgs sources; };
  conf = import ../main/backends/nix/confinements.nix {
    inherit (pkgs) lib;
    homeManagerPatcher = pwrap.patchers.homeManager;
    presets = import ../main/presets { inherit pkgs; };
    checkPreset = import ../main/backends/nix/app-options.nix { inherit (pkgs) lib; };
    mkAppFor = _: x: x;
    checkNetwork = network: (pwrap.configuration.check { inherit network; }).network;
  };
  presets1 = conf.build {} ({ presets1, presets2, ... }: {
    chromium = presets2.chromium { name = "chromium"; };
    chromium-local = presets2.chromium { name = "chromium-local"; network = { localhost = true; remote = false; }; };
    logseq = presets2.logseq { graphs = [ "/srv/notes" ]; };
    limited = pkgs.lib.recursiveUpdate (presets2.chromium { name = "limited"; }) { resources.memoryMax = "1G"; };
    firefox = presets1.firefox // { network = presets1.firefox.network // { localhost = true; }; };
  });
  compile = import ../main/core/policy.nix;
  remote = presets1.chromium pkgs.chromium;
  local = presets1.chromium-local pkgs.chromium;
  notes = presets1.logseq pkgs.hello;
  limits = presets1.limited pkgs.hello;
  public = compile { network.remote = true; };
  loopback = compile { network.localhost = true; };
  private = compile { network.lan = true; };
in
assert builtins.isAttrs presets1;
assert remote.name == "chromium" && local.name == "chromium-local";
assert remote.network.remote && !local.network.remote && local.network.localhost;
assert remote.persist == [ "~/.config/chromium" "~/.cache/chromium" ];
assert local.persist == [ "~/.config/chromium-local" "~/.cache/chromium-local" ];
assert !local.providers.uri.enable && local.dbus.talk == [ ] && local.dbus.calls == [ ] && local.dbus.broadcasts == [ ];
assert local.executable == "chromium-local";
assert pkgs.lib.isFunction local.packageTransform;
assert !(notes ? graphs) && notes.writable == [ "/srv/notes" ];
assert (presets1.firefox pkgs.hello).network.localhost;
assert !(public.allowLocalhost) && public.network == "internet";
assert loopback.network == "local" && !loopback.allowLAN && loopback.allowLocalhost;
assert private.network == "local" && private.allowLAN && !private.allowLocalhost;
assert limits.resources.memoryMax == "1G" && limits.resources.tasksMax == 2048;
assert (compile { dbus.talk = [ "org.test" ]; }).dbusTalk == [ "org.test" ];
assert !(builtins.tryEval (builtins.deepSeq (compile { network = { unrestricted = true; remote = true; }; }) true)).success;
assert !(builtins.tryEval (builtins.deepSeq (compile { dbus.typo = true; }) true)).success;
pkgs.writeText "pwrap-app-presets-proof" "one preset, named instances, grouped DSL"
