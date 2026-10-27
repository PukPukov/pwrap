# Exact D-Bus identifiers, not raw xdg-dbus-proxy rule syntax.
{ lib }:
let
  inherit (lib) types mkOption;
  identifier = pattern: types.addCheck (types.strMatching pattern)
    (value: builtins.stringLength value <= 255);
  name = identifier "[A-Za-z_][A-Za-z0-9_-]*(\\.[A-Za-z_][A-Za-z0-9_-]*)+";
  interface = identifier "[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+";
  member = identifier "[A-Za-z_][A-Za-z0-9_]*";
in types.listOf (types.submodule { options = {
  destination = mkOption { type = name; };
  interface = mkOption { type = interface; };
  path = mkOption { type = types.strMatching "/([A-Za-z0-9_]+(/[A-Za-z0-9_]+)*)?"; };
  members = mkOption { type = types.addCheck (types.listOf member) (values: values != []); };
}; })
