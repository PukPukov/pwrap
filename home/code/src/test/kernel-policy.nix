{ pkgs }:
let
  policy = import ../main/core/kernel-policy.nix;
  primitives = import ../main/backends/nixos/primitives.nix { inherit pkgs; };
  driver = (import ../main/java.nix { inherit pkgs; }).tests;
in assert policy.addressFamilies == [ "AF_UNIX" "AF_INET" "AF_INET6" ];
assert policy.networkControlFamilies == policy.addressFamilies ++ [ "AF_NETLINK" ];
pkgs.runCommand "pwrap-kernel-policy-proof" {} ''
  ${driver}/bin/pwrap-tests _filter-check ${primitives.seccomp}/filter.bpf
  touch $out
''
