{ homeManagerSource, roots, sources }:
args@{ config, lib, pkgs, ... }:
let
  upstream = "${homeManagerSource}/modules/files.nix";
  # Only these app-owned trees currently have Home Manager-managed files.
  # Do not expose the entire generation (Codex/SSH/etc.) to their maintenance.
  pwrap = import ../.. { inherit pkgs sources; };
  belongs = root: target: target == root || lib.hasPrefix "${root}/" target;
  buckets = lib.imap0 (index: root: { inherit root; variable = "entries_${toString index}"; }) roots;
  wrap = name: text:
    let
      original = pkgs.writeShellScript "hm-${name}-original" text;
      confined = root: let
        active = lib.filter (f: f.enable && belongs root f.target) (lib.attrValues config.home.file);
      in pwrap.mkFileTask {
        name = "hm-${name}-profile";
        home = config.home.homeDirectory;
        persist = [ "${config.home.homeDirectory}/${root}" ];
        # cleanOldGen still runs when the last current file was removed. The
        # new generation then has no such subtree; do not grant a missing path.
        immutableData = lib.unique (lib.optional (active != []) "${config.home-files}/${root}"
          ++ map (f: toString f.source) active);
        runtime = [ pkgs.diffutils pkgs.gettext pkgs.ncurses pkgs.findutils ];
        inheritEnvironment = [ "DRY_RUN" "VERBOSE" "VERBOSE_ARG" "TEXTDOMAIN" "TEXTDOMAINDIR"
          "HOME_MANAGER_BACKUP_EXT" "HOME_MANAGER_BACKUP_OVERWRITE" ];
        script = ''
          export HOME_MANAGER_BACKUP_COMMAND=""
          exec ${pkgs.runtimeShell} ${original} "$@"
        '';
      };
    in pkgs.writeShellScript "hm-${name}-router" ''
      set -euo pipefail
      generation=$1; shift
      host_entries=()
      ${lib.concatMapStringsSep "\n" (bucket: "${bucket.variable}=()") buckets}
      for entry in "$@"; do
        ${if name == "link" then ''relative=''${entry#"$generation"/}'' else ''relative=$entry''}
        case "$relative" in
          ${lib.concatMapStringsSep "\n" (bucket: ''
          ${lib.escapeShellArg bucket.root}|${lib.escapeShellArg "${bucket.root}/"}*)
            ${bucket.variable}+=("$entry")
            ;;
          '') buckets}
          *) host_entries+=("$entry") ;;
        esac
      done
      ${lib.concatMapStringsSep "\n" (bucket: ''
        if (( ''${#${bucket.variable}[@]} )); then
          ${confined bucket.root}/bin/hm-${name}-profile "$generation" "''${${bucket.variable}[@]}"
        fi
      '') buckets}
      if (( ''${#host_entries[@]} )); then
        ${original} "$generation" "''${host_entries[@]}"
      fi
    '';
  # Reuse upstream linking/backup/cleanup logic rather than maintaining a fork.
  # Only its two host-mutating helpers get an app-tree routing boundary.
  guardedPkgs = pkgs // {
    writeShellScript = name: text:
      if roots != [] && builtins.elem name [ "link" "cleanup" ] then wrap name text
      else pkgs.writeShellScript name text;
  };
in assert lib.assertMsg (lib.all (root: root != "" && lib.all
  (part: part != "" && part != "." && part != "..") (lib.splitString "/" root)) roots)
  "pwrap HM maintenance roots must be canonical relative HOME subtrees";
{
  disabledModules = [ upstream ];
  imports = [ (import upstream (args // { pkgs = guardedPkgs; })) ];
  # Evidence consumed by the pure HM patcher. Only the adapter which actually
  # routes activation helpers provides it; merely declaring NixOS roots doesn't.
  options.pwrap.maintenanceRoots = lib.mkOption {
    internal = true; readOnly = true;
    type = lib.types.listOf lib.types.str;
    default = roots;
    description = "HOME subtrees actually routed through confined HM maintenance.";
  };
  config.assertions = lib.optional (roots != []) {
    assertion = lib.all (marker: lib.hasInfix marker config.home.activation.linkGeneration.data)
      [ "-hm-link-router" "-hm-cleanup-router" ];
    message = "pwrap HM maintenance: upstream link/cleanup helper integration changed; refusing an unverified activation";
  };
}
