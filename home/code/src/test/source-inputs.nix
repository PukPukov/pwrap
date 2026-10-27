{ pkgs, sources }:
let
  # Recipes must use the supplied flake input, not fetch another copy internally.
  noFetch = pkgs // { fetchFromGitHub = _: throw "Unexpected recipe-local GitHub fetch"; };
  wayland = source: import ../main/backends/nixos/wayland-firewall.nix { pkgs = noFetch; inherit source; };
  bwrap = source: import ../main/backends/nixos/bubblewrap.nix {
    pkgs = noFetch // { bubblewrap = pkgs.bubblewrap // { version = "0"; }; };
    inherit source;
  };
  alternate = pkgs.writeTextDir "Cargo.lock" "test-only source override";
in
assert (wayland sources.wayland-firewall).src == sources.wayland-firewall;
assert (bwrap sources.bubblewrap).src == sources.bubblewrap;
assert (wayland alternate).src == alternate;
assert (bwrap alternate).src == alternate;
pkgs.writeText "pwrap-flake-source-inputs-proof" "explicit source inputs and overrides\n"
