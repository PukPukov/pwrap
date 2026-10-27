{
  description = "pwrap: explicit desktop compartments and native NixOS service presets";
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-26.05";
  inputs.bubblewrap = { url = "github:containers/bubblewrap/v0.13.1"; flake = false; };
  inputs.wayland-firewall = { url = "github:wochap/wayland-firewall/bfa5f6367f5aa1fba104ecac48590dbe08227a37"; flake = false; };
  outputs = inputs: import ./home/code/flake.nix inputs;
}
