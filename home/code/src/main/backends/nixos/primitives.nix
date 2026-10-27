{ pkgs }:
let
  kernel = import ../../core/kernel-policy.nix;
  seccomp = pkgs.runCommandCC "pwrap-seccomp" { buildInputs = [ pkgs.libseccomp ]; } ''
    $CC -Wall -Wextra -Werror ${pkgs.lib.escapeShellArg "-DPWRAP_SOCKET_FAMILIES=${pkgs.lib.concatStringsSep "," kernel.networkControlFamilies}"} \
      ${../../core/native/seccomp.c} -lseccomp -o generate
    mkdir -p $out
    ./generate > $out/filter.bpf
  '';
  scope = pkgs.runCommandCC "pwrap-ipc-scope" {} ''
    mkdir -p $out/bin
    $CC -Wall -Wextra -O2 ${../../core/native/scope.c} -o $out/bin/pwrap-ipc-scope
  '';
in { inherit scope seccomp; }
