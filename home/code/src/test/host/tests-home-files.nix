# Host runtime test. Supply a fresh, unused testRoot under /var/tmp; never a
# real HOME. The generated executable keeps all artifacts for inspection.
{ corepkgs, homeManagerSource, testRoot, sources }:
let
  pkgs = corepkgs;
  browserSource = pkgs.writeText "hm-test-browser-source" "browser managed content";
  mailSource = pkgs.writeText "hm-test-mail-source" "mail managed content";
  files = pkgs.runCommand "home-manager-files" {} ''
    mkdir -p $out/.mozilla/test $out/.thunderbird/test "$out/.app[1]/test"
    ln -s ${browserSource} $out/.mozilla/test/entry
    ln -s ${mailSource} $out/.thunderbird/test/entry
    ln -s ${browserSource} "$out/.app[1]/test/entry"
  '';
  generation = pkgs.runCommand "hm-test-generation" {} ''
    mkdir -p $out
    ln -s ${files} $out/home-files
  '';
  oldFiles = pkgs.runCommand "home-manager-files" {} ''
    mkdir -p $out/.mozilla/test
    ln -s ${browserSource} $out/.mozilla/test/removed
  '';
  oldGeneration = pkgs.runCommand "hm-test-old-generation" {} ''
    mkdir -p $out
    ln -s ${oldFiles} $out/home-files
  '';
  config = {
    home.homeDirectory = "${testRoot}/home";
    home.file = {
      browser = { enable = true; force = false; target = ".mozilla/test/entry"; source = browserSource; };
      mail = { enable = true; force = false; target = ".thunderbird/test/entry"; source = mailSource; };
      literal = { enable = true; force = false; target = ".app[1]/test/entry"; source = browserSource; };
    };
    home-files = files;
    lib.bash.initHomeManagerLib = ''
      export PATH=${pkgs.lib.makeBinPath [ pkgs.bash pkgs.coreutils pkgs.findutils pkgs.gettext pkgs.diffutils ]}
      source ${homeManagerSource}/lib/bash/home-manager.sh
      export HOME_MANAGER_BACKUP_COMMAND="" HOME_MANAGER_BACKUP_EXT=backup HOME_MANAGER_BACKUP_OVERWRITE="" VERBOSE_ARG=""
    '';
  };
  lib = pkgs.lib // { hm.dag = pkgs.lib.genAttrs [ "entryBefore" "entryAfter" ] (_: deps: data: { inherit data; }); };
  wrapped = (import ../../main/backends/nix/home-files.nix {
    inherit homeManagerSource sources;
    roots = [ ".mozilla" ".thunderbird" ".app[1]" ];
  }) {
    inherit pkgs config lib;
  };
  module = builtins.head wrapped.imports;
  upstream = import "${homeManagerSource}/modules/files.nix" { inherit pkgs config lib; };
  probeFor = module: pkgs.writeShellScript "hm-link-proof" ''
    set -euo pipefail
    export HOME=${pkgs.lib.escapeShellArg config.home.homeDirectory}
    ${config.lib.bash.initHomeManagerLib}
    newGenPath=${generation}
    oldGenPath=${oldGeneration}
    ${module.config.home.activation.checkLinkTargets.data}
    ${module.config.home.activation.linkGeneration.data}
  '';
  probe = probeFor module;
  unconfined = probeFor upstream;
  pwrap = import ../../main { inherit pkgs sources; };
  layoutScript = ''
    test -z "$(ls -A /proc/driver)"
    cp -f ${mailSource} "$HOME/.thunderbird/test/layout.json"'';
  layout = pwrap.mkFileTask {
    name = "hm-layout-proof";
    home = config.home.homeDirectory;
    persist = [ "${config.home.homeDirectory}/.thunderbird" ];
    script = layoutScript;
  };
in assert pkgs.lib.hasPrefix "/var/tmp/pwrap-hm-test-" testRoot;
pkgs.writeShellScriptBin "test-home-files" ''
  set -euo pipefail
  export PATH=${pkgs.lib.makeBinPath [ pkgs.coreutils ]}
  work=${pkgs.lib.escapeShellArg testRoot}
  if [[ -e "$work" || -L "$work" ]]; then
    echo "Refusing to reuse test path: $work" >&2; exit 1
  fi
  mkdir -p "$work/home/.mozilla/test" "$work/home/.thunderbird/test" "$work/home/.app[1]/test" "$work/victim"
  printf 'host canary unchanged' > "$work/victim/entry"
  ln -s ${oldFiles}/.mozilla/test/removed "$work/home/.mozilla/test/removed"
  ${probe}
  test ! -L "$work/home/.mozilla/test/removed"
  test "$(cat "$work/home/.mozilla/test/entry")" = 'browser managed content'
  test "$(cat "$work/home/.thunderbird/test/entry")" = 'mail managed content'
  mv "$work/home/.mozilla/test" "$work/home/.mozilla/test-normal-backup"
  ln -s "$work/victim" "$work/home/.mozilla/test"
  ln -s ${oldFiles}/.mozilla/test/removed "$work/victim/removed"
  if ${probe} > "$work/external.log" 2>&1; then
    echo 'FAIL: hostile external symlink did not fail closed' >&2; exit 1
  fi
  test "$(cat "$work/victim/entry")" = 'host canary unchanged'
  test -L "$work/victim/removed"
  unlink "$work/home/.mozilla/test"
  ln -s "$work/home/.thunderbird/test" "$work/home/.mozilla/test"
  ln -s ${oldFiles}/.mozilla/test/removed "$work/home/.thunderbird/test/removed"
  if ${probe} > "$work/cross-profile.log" 2>&1; then
    echo 'FAIL: cross-profile symlink did not fail closed' >&2; exit 1
  fi
  test "$(cat "$work/home/.thunderbird/test/entry")" = 'mail managed content'
  test ! -e "$work/home/.thunderbird/test/entry.backup"
  test -L "$work/home/.thunderbird/test/removed"
  # Upstream collision checking accepts the backup policy used by this host.
  # A parent symlink redirects both cleanup and link operations outside HOME.
  unlink "$work/home/.mozilla/test"
  ln -s "$work/victim" "$work/home/.mozilla/test"
  ${unconfined} > "$work/unconfined.log" 2>&1
  test ! -L "$work/victim/removed"
  test "$(cat "$work/victim/entry.backup")" = 'host canary unchanged'
  test "$(cat "$work/victim/entry")" = 'browser managed content'
  echo 'RED CONTROL: upstream collision-check + cleanup/link follow a mutable parent outside the app tree'
  # Unlike ln -Tsf, cp follows the final symlink. The host Thunderbird layout
  # hook uses this operation: one app-controlled symlink can truncate a victim.
  printf 'private database canary' > "$work/victim/layout.json"
  ln -s "$work/victim/layout.json" "$work/home/.thunderbird/test/layout.json"
  HOME="$work/home" ${pkgs.runtimeShell} -c ${pkgs.lib.escapeShellArg layoutScript}
  test "$(cat "$work/victim/layout.json")" = 'mail managed content'
  echo 'RED CONTROL: unconfined layout cp follows a final symlink and overwrites an arbitrary canary'
  printf 'private database canary' > "$work/victim/layout.json"
  if ${layout}/bin/hm-layout-proof > "$work/layout.log" 2>&1; then
    echo 'FAIL: confined layout copied through an external symlink' >&2; exit 1
  fi
  test "$(cat "$work/victim/layout.json")" = 'private database canary'
  echo 'PASS: current file-task confinement prevents the final-symlink overwrite'
  # Root names are literal paths, not shell case patterns. A bracket must not
  # make this entry fall through to the unrestricted upstream helper.
  unlink "$work/home/.mozilla/test"
  mv "$work/home/.mozilla/test-normal-backup" "$work/home/.mozilla/test"
  mv "$work/home/.app[1]/test" "$work/home/.app[1]/test-normal-backup"
  ln -s "$work/victim" "$work/home/.app[1]/test"
  mv "$work/victim/entry" "$work/victim/entry-red-control"
  mv "$work/victim/entry.backup" "$work/victim/entry-initial-backup"
  printf 'literal-root canary' > "$work/victim/entry"
  if ${probe} > "$work/literal-root.log" 2>&1; then
    echo 'FAIL: literal root fell through to an unrestricted helper' >&2; exit 1
  fi
  test "$(cat "$work/victim/entry")" = 'literal-root canary'
  echo 'PASS: shell-pattern characters in a configured root remain literal'
  echo "PASS: normal HM linking/cleanup, external/cross-profile symlink isolation; artifacts: $work"
''
