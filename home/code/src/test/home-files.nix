{ pkgs, sources }:
let
  inherit (pkgs) lib;
  recorder = pkgs.writeShellScript "record-hm-call" ''
    { printf '%s\0' "$@"; printf '\n'; } >> "$CALL_LOG"
  '';
  stubPkgs = pkgs // {
    # Test routing and argv, not nested bwrap in the Nix build sandbox.
    writeShellScriptBin = name: text: if lib.hasPrefix "hm-" name then
      pkgs.writeShellScriptBin name ''
        exec "$RECORDER" confined "$@"
      ''
    else pkgs.writeShellScriptBin name text;
  };
  module = import ../main/backends/nix/home-files.nix {
    inherit sources;
    homeManagerSource = ./fixtures/home-manager;
    roots = [ ".app" ".other" ];
  };
  result = module {
    inherit lib;
    pkgs = stubPkgs;
    config = {
      home.homeDirectory = "/home/test";
      home.file = {};
      home-files = "/nix/store/unused-generation";
      home.activation.linkGeneration.data = scripts.config.home.activation.linkGeneration.data;
    };
  };
  scripts = builtins.head result.imports;
in
assert builtins.all (entry: entry.assertion) result.config.assertions;
pkgs.runCommand "pwrap-hm-router-batching" {} ''
  export RECORDER=${recorder} CALL_LOG=$TMPDIR/calls
  ${scripts.link} /generation /generation/.app/one '/generation/.other/space two' /generation/.app/three /generation/unselected
  ${scripts.cleanup} /generation .app/one '.other/space two' .app/three unselected
  ${scripts.link} /generation
  for prefix in /generation/ ""; do
    printf '%s\0' confined /generation "$prefix.app/one" "$prefix.app/three"; printf '\n'
    printf '%s\0' confined /generation "$prefix.other/space two"; printf '\n'
    printf '%s\0' host /generation "''${prefix}unselected"; printf '\n'
  done > "$TMPDIR/expected"
  cmp "$CALL_LOG" "$TMPDIR/expected"
  touch $out
''
