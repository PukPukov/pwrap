{ pkgs, sources }:
let
  inherit (pkgs) lib;
  pwrap = import ../main { inherit pkgs sources; };
  check = import ../main/backends/nix/app-options.nix { inherit lib; };
  rejected = attrs: !(builtins.tryEval (builtins.deepSeq (check ({ name = "parity"; } // attrs)) true)).success;
  networks = lib.cartesianProduct (lib.genAttrs [ "localhost" "remote" "lan" "unrestricted" ] (_: [ false true ]));
  validNetworks = lib.filter (n: !n.unrestricted || !(n.localhost || n.remote || n.lan)) networks;
  localNetworks = lib.filter (n: !n.unrestricted && !n.remote && (n.localhost || n.lan)) validNetworks;
  operation = { destination = "org.example.Service"; interface = "org.example.API"; path = "/"; members = [ "Read" ]; };
  brokers = [
    { providers.openFile.enable = true; }
    { providers.uri.enable = true; }
    { providers.uri = { enable = true; schemes = [ "tg" ]; }; }
    { dbus.talk = [ "org.example.Service" ]; }
    { dbus.own = [ "org.example.App" ]; }
    { dbus.calls = [ operation ]; }
    { dbus.broadcasts = [ operation ]; }
  ];
  accepted = map (network: { inherit network; }) validNetworks
    ++ map (broker: broker // { network.remote = true; }) brokers
    ++ [ { environment = { _A9 = ""; LANG = "C.UTF-8"; }; arguments = [ "" "with spaces" ]; } ];
  manifests = map (attrs:
    (pwrap.mkApp ({ name = "parity"; package = pkgs.hello; } // attrs)).pwrapManifest) accepted;
  native = import ../main/java.nix { inherit pkgs; };
in
assert builtins.length validNetworks == 9;
assert lib.all (network: rejected { inherit network; }) (lib.subtractLists validNetworks networks);
assert lib.all (network: lib.all (broker: rejected (broker // { inherit network; })) brokers) localNetworks;
assert lib.all (key: rejected { environment.${key} = "value"; }) [ "" "BAD-NAME" "BAD NAME" "9BAD" ];
assert lib.all rejected [
  { audio = true; }
  { providers.webBrowser = "broker"; }
  { providers.uris = "broker"; }
  { ipc.hostAbstractSockets = true; }
  { network.remote = true; ipc.hostAbstractSockets = true; }
  { resources.tasksMax = 0; }
  { resources.memoryMax = "0G"; }
  { environment.OK = 1; }
];
pkgs.runCommand "pwrap-policy-parity" {} ''
  ${native}/bin/pwrap-native _policy-check ${lib.escapeShellArgs manifests}
  echo '${toString (builtins.length manifests)} compiled manifests accepted by native runtime; invalid configurations rejected at Nix evaluation'
  touch $out
''
