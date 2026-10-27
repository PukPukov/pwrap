# Check the actual NixOS confinement closure, not just the ExecStart spelling.
# The installed host test starts only unique, synthetic units; no live DNS/NM.
{ pkgs, nixpkgs }:
let
  inherit (pkgs) lib;
  names = [ "pwrap-ipc-proof-host@" "pwrap-ipc-proof-private@" ];
  setting = pkgs.writeText "pwrap-ipc-proof-setting" "service configuration\n";
  stage = name: pkgs.writeShellScript "ipc-proof-${name}" ''
    echo PWRAP_STAGE=${name}
  '';
  payload = pkgs.writeShellScript "ipc-proof-payload" ''
    set -euo pipefail
    test "$(${pkgs.coreutils}/bin/cat "$1")" = 'service configuration'
    echo PWRAP_STAGE=start
    trap 'exit 0' TERM
    ${pkgs.coreutils}/bin/sleep 120 &
    wait "$!"
  '';
  commands = {
    ExecCondition = toString (stage "condition");
    ExecStartPre = [ (toString (stage "pre")) ];
    ExecStart = "${payload} ${setting}";
    ExecReload = [ "" "${pkgs.coreutils}/bin/kill -0 $MAINPID" ];
    ExecStop = "${pkgs.coreutils}/bin/kill -TERM $MAINPID";
    ExecStopPost = [ (toString (stage "stopped")) ];
  };
  config = (nixpkgs.lib.nixosSystem {
    system = pkgs.stdenv.hostPlatform.system;
    modules = [ ../main/backends/nixos/services {
      system.stateVersion = "26.05";
      security.pwrap.services = lib.genAttrs names (name: {
        addressFamilies = if lib.hasInfix "private" name then [ "AF_UNIX" ]
          else [ "AF_UNIX" "AF_INET" "AF_INET6" ];
      });
      systemd.services = lib.genAttrs names (_: {
        serviceConfig = commands // { Type = "exec"; TimeoutStartSec = 15; TimeoutStopSec = 15; };
      });
    } ];
  }).config;
  retains = before: after: lib.all (reference:
    (builtins.getContext after).${reference} == (builtins.getContext before).${reference}
  ) (builtins.attrNames (builtins.getContext before));
  wrapped = config.systemd.services.${builtins.head names}.serviceConfig;
  # Check every argument's context, not only argv[0]. Config files matter too.
  contextsPreserved = lib.all (key:
    let before = lib.toList commands.${key}; after = lib.toList wrapped.${key};
    in builtins.length before == builtins.length after && lib.all (index:
      retains (builtins.elemAt before index) (builtins.elemAt after index)
    ) (lib.range 0 (builtins.length before - 1))
  ) (builtins.attrNames commands);
  unitFiles = map (name: pkgs.writeText "${name}.service" config.systemd.units."${name}.service".text) names;
  # These are the upstream-generated closure drop-ins used by real services.
  closures = lib.filter (package: lib.hasSuffix "-chroot-paths" package.name) config.systemd.packages;
in assert contextsPreserved;
pkgs.runCommand "pwrap-service-ipc-confinement-proof" {
  nativeBuildInputs = [ pkgs.makeWrapper ];
} ''
  mkdir -p $out/lib/systemd/system $out/bin
  ${lib.concatMapStringsSep "\n" (name: ''
    cp ${builtins.elemAt unitFiles (if lib.hasInfix "private" name then 1 else 0)} $out/lib/systemd/system/${name}.service
    mkdir -p $out/lib/systemd/system/${name}.service.d
    for package in ${lib.escapeShellArgs (map toString closures)}; do
      fragment="$package/lib/systemd/system/${name}.service.d/confinement.conf"
      if test -f "$fragment"; then cp "$fragment" $out/lib/systemd/system/${name}.service.d/; fi
    done
    fragment=$out/lib/systemd/system/${name}.service.d/confinement.conf
    for expected in ${lib.escapeShellArgs (map toString [ payload setting (stage "condition") (stage "pre") (stage "stopped") ])}; do
      grep -F -- "BindReadOnlyPaths=$expected" "$fragment"
    done
  '') names}
  cp ${./host/test-service-ipc.sh} $out/bin/test-service-ipc
  chmod +x $out/bin/test-service-ipc
  patchShebangs $out/bin/test-service-ipc
  wrapProgram $out/bin/test-service-ipc --prefix PATH : ${lib.makeBinPath [ pkgs.coreutils pkgs.gnugrep pkgs.systemd ]} \
    --set PWRAP_SERVICE_IPC_FIXTURE $out/lib/systemd/system
''
