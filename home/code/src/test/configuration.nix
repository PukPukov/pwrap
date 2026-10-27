{ pkgs, sources }: let
  base = import ../main { inherit pkgs sources; };
  # This fixed point is intentional: manifests do not refer to the runner.
  configured = base.configure {
    profiles.probe = app;
    providers.audio.nixos.socketPath = "/run/pulse-custom/native";
    network = { localDeniedPorts = [ 53 3128 ]; relayServices = [ "proxy.service" ]; };
  };
  app = configured.mkApp { name = "probe"; package = pkgs.hello; executable = "hello"; };
  independent = base.configure {
    profiles.independent = independentBrowser;
  };
  independentBrowser = independent.mkApp { name = "independent"; package = pkgs.hello; executable = "hello"; };
  rejected = value: !(builtins.tryEval (builtins.deepSeq (base.configuration.check value) true)).success;
in
assert rejected { homeFiles = []; };
assert rejected { homeManager.user = "root"; };
assert !(base.configuration.options ? homeFiles);
assert !(base.configuration ? homeFilesFor);
assert rejected { tyop = true; };
assert rejected { uriHandlers.tg.package = pkgs.hello; };
assert rejected { uriHandlers.http.package = app; };
assert rejected { uriHandlers.file.package = app; };
assert rejected { uriHandlers."bad scheme".package = app; };
assert rejected { profiles.raw = pkgs.hello; };
assert rejected { webBrowser.profile = "missing"; };
assert rejected { webBrowser.package = pkgs.hello; };
assert rejected { providers.audio.nixos.socketPath = "relative"; };
assert rejected { providers.audio.unknown = true; };
assert rejected { providers.audio.nixos = true; };
assert rejected { network.localDeniedPorts = [ "53" ]; };
assert rejected { network.localDeniedPorts = [ 0 ]; };
assert rejected { network.relayServices = [ "..bad.service" ]; };
assert rejected { desktop.environment = { KEY = 42; }; };
assert configured.configuration.metadata.profiles.probe == app.pwrapManifest;
assert configured.configuration.metadata.providers.audio.nixos.socketPath == "/run/pulse-custom/native";
assert (base.configure { providers = {}; }).configuration.metadata.providers == {};
assert configured.configuration.metadata.network.localDeniedPorts == [ 53 3128 ];
assert !(base.configuration.metadata ? profiles);
pkgs.runCommand "pwrap-configured-runner-proof" {} ''
  test -x ${app}/bin/hello
  test -x ${independentBrowser}/bin/hello
  ${configured.runner}/bin/pwrap list >$out
  grep -qx probe $out
  ${configured.runner}/bin/pwrap inspect probe > manifest
  grep -q '"name":"probe"' manifest
''
