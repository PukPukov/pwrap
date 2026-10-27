# Normal removal of the last managed file. No hostile paths or exploit control.
{ pkgs, homeManagerSource, testRoot, sources }:
let
  source = pkgs.writeText "hm-lifecycle-content" "managed content\n";
  oldFiles = pkgs.runCommand "home-manager-files" {} ''
    mkdir -p $out/.app
    ln -s ${source} $out/.app/settings
  '';
  emptyFiles = pkgs.runCommand "home-manager-files" {} ''mkdir -p $out'';
  generation = files: pkgs.runCommand "hm-lifecycle-generation" {} ''
    mkdir -p $out
    ln -s ${files} $out/home-files
  '';
  old = generation oldFiles;
  current = generation emptyFiles;
  config = {
    home.homeDirectory = "${testRoot}/home";
    home.file = {};
    home-files = emptyFiles;
    lib.bash.initHomeManagerLib = ''
      export PATH=${pkgs.lib.makeBinPath [ pkgs.bash pkgs.coreutils pkgs.findutils pkgs.gettext pkgs.diffutils ]}
      source ${homeManagerSource}/lib/bash/home-manager.sh
      export HOME_MANAGER_BACKUP_COMMAND="" HOME_MANAGER_BACKUP_EXT=backup HOME_MANAGER_BACKUP_OVERWRITE="" VERBOSE_ARG=""
    '';
  };
  lib = pkgs.lib // { hm.dag = pkgs.lib.genAttrs [ "entryBefore" "entryAfter" ] (_: deps: data: { inherit data; }); };
  adapter = (import ../../main/backends/nix/home-files.nix {
    inherit homeManagerSource sources;
    roots = [ ".app" ];
  }) { inherit pkgs config lib; };
  module = builtins.head adapter.imports;
  activate = pkgs.writeShellScript "hm-lifecycle-activate" ''
    set -euo pipefail
    export HOME=${pkgs.lib.escapeShellArg config.home.homeDirectory}
    ${config.lib.bash.initHomeManagerLib}
    newGenPath=${current}
    oldGenPath=${old}
    ${module.config.home.activation.linkGeneration.data}
  '';
in assert pkgs.lib.hasPrefix "/var/tmp/pwrap-hm-lifecycle-" testRoot;
pkgs.writeShellScriptBin "test-home-files-lifecycle" ''
  set -euo pipefail
  export PATH=${pkgs.lib.makeBinPath [ pkgs.coreutils ]}
  work=${pkgs.lib.escapeShellArg testRoot}
  test ! -e "$work" && test ! -L "$work"
  mkdir -p "$work/home/.app"
  ln -s ${oldFiles}/.app/settings "$work/home/.app/settings"
  test ! -e ${emptyFiles}/.app
  ${activate} > "$work/activation.log" 2>&1
  test ! -L "$work/home/.app/settings"
  test "$(cat ${source})" = 'managed content'
  echo "PASS: confined HM cleanup with no current files/subtree; artifacts: $work"
''
