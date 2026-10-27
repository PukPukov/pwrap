{ pkgs, sources }:
let
  pwrap = import ../main { inherit pkgs sources; };
  check = import ../main/backends/nix/app-options.nix { inherit (pkgs) lib; };
  operation = {
    destination = "org.example.Service";
    interface = "org.example.Interface";
    path = "/org/example/Object";
    members = [ "Read" ];
  };
  checked = check { name = "probe"; dbus = { calls = [ operation ]; broadcasts = [ operation ]; }; };
  policy = import ../main/core/policy.nix { inherit (checked) dbus; };
  catalog = import ../main/presets { inherit pkgs; } {};
  bad = value: !(builtins.tryEval (builtins.deepSeq (check {
    name = "bad"; dbus.calls = [ value ];
  }) true)).success;
  app = pwrap.mkApp { name = "probe"; package = pkgs.hello; dbus.calls = [ operation ]; };
  manifest = builtins.fromJSON (builtins.unsafeDiscardStringContext app.pwrapManifest.text);
in
assert policy.dbusCalls == [ operation ];
assert policy.dbusBroadcasts == [ operation ];
assert policy.dbusTalk == [];
assert builtins.all bad [
  (operation // { typo = true; })
  (operation // { members = []; })
  (operation // { destination = "org.example.*"; })
  (operation // { interface = "org.example.*"; })
  (operation // { path = "/object/*"; })
  (operation // { members = [ "Read@/else" ]; })
  (removeAttrs operation [ "path" ])
];
assert builtins.all (p: !(builtins.elem "org.freedesktop.Notifications" (p.dbus.talk or []))
  && (p.dbus.calls or []) != [] && (p.dbus.broadcasts or []) != [])
  (builtins.attrValues catalog.presets1);
assert (catalog.presets2.chromium { name = "chromium-local"; network.remote = false; }).dbus.calls == [];
assert manifest.dbusTalk == [];
assert (builtins.head manifest.dbusCalls).members == [ "Read" ];
pkgs.runCommand "pwrap-dbus-operation-proof" {} ''
  test -s ${app.pwrapManifest}
  touch $out
''
