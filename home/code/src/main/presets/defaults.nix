# GUI convenience defaults, shared by presets and confinement compilation.
# Explicit values (including false and empty lists) always win.
# Keep gui until registration supplies a name; aliases must not share Downloads.
{ lib }:
{ downloads ? "~/Downloads", nixos ? true }:
preset:
assert lib.assertMsg (builtins.isAttrs preset && !lib.isDerivation preset)
  "pwrap: defaults expects a preset record";
assert lib.assertMsg (builtins.isBool nixos) "pwrap: env.nixos must be a boolean";
assert lib.assertMsg (builtins.isString downloads && downloads != "")
  "pwrap: downloads must be a nonempty string";
assert lib.assertMsg (builtins.isBool (preset.gui or false)) "pwrap: gui must be a boolean";
assert lib.assertMsg (builtins.isBool (preset.audio or false)) "pwrap: audio must be a boolean";
lib.recursiveUpdate (lib.recursiveUpdate (lib.optionalAttrs (preset.gui or false) {
  network.remote = true;
  desktop = true;
  sharedTmp = true;
  providers = {
    uri.enable = true;
    openFile.enable = true;
  };
  dbus = import ./capabilities/notifications.nix;
} // lib.optionalAttrs ((preset.gui or false) && preset ? name) {
  downloads = "${lib.removeSuffix "/" downloads}/${preset.name}";
}) (lib.optionalAttrs (nixos && ((preset.gui or false) || (preset.audio or false))) {
  providers.audio = "nixos";
})) (removeAttrs preset (lib.optional (preset ? name || !(preset.gui or false)) "gui"))
