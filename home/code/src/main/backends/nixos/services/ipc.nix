# Transform the final commands, including upstream scripts and list resets.
# Do not parse/requote argv: systemd still expands its own arguments/specifiers.
{ lib, scope }:
let
  commands = [ "ExecCondition" "ExecStartPre" "ExecStart" "ExecStartPost"
    "ExecReload" "ExecStop" "ExecStopPost" ];
  wrap = value:
    let command = toString value;
        parts = builtins.match "([-+!:]*)(.*)" command;
    in if command == "" then "" else
      assert lib.assertMsg (parts != null && builtins.match "[@|].*" (builtins.elemAt parts 1) == null)
        "pwrap service IPC scope: custom argv[0]/shell command prefixes need an explicit adapter";
      # Regex captures lose Nix string context. Keep ALL original references:
      # confinement derives its runtime closure from these final commands.
      builtins.appendContext
        "${builtins.head parts}${scope}/bin/pwrap-ipc-scope --allow-signals ${builtins.elemAt parts 1}"
        (builtins.getContext command);
in {
  options.serviceConfig = lib.mkOption {
    apply = lib.mapAttrs (key: value:
      if !(builtins.elem key commands) then value
      else if builtins.isList value then map wrap value else wrap value);
  };
}
