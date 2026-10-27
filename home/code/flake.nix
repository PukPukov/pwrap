{ self, nixpkgs, bubblewrap, wayland-firewall }:
  let
    systems = [ "x86_64-linux" ];
    forSystems = nixpkgs.lib.genAttrs systems;
    sources = { bubblewrap = bubblewrap.outPath; wayland-firewall = wayland-firewall.outPath; };
    withSources = module: { ... }: {
      imports = [ module ];
      _module.args.pwrapSources = sources;
    };
  in {
    lib = {
      forPkgs = pkgs: import ./src/main { inherit pkgs sources; };
      homeFilesModule = args: import ./src/main/backends/nix/home-files.nix (args // { inherit sources; });
      homeManagerModule = import ./src/main/backends/nix/home-manager.nix;
      nixcordModule = args: import ./src/main/presets/integrations/nixcord.nix (args // { inherit sources; });
    };
    nixosModules = {
      default = withSources ./src/main/module.nix;
      desktop = withSources ./src/main/backends/nixos/desktop.nix;
      services = ./src/main/nixos;
      networkmanager = ./src/main/nixos/presets/networkmanager.nix;
    };
    packages = forSystems (system: let
      pkgs = import nixpkgs { inherit system; };
      pwrap = self.lib.forPkgs pkgs;
    in { default = pwrap.runner; pwrap = pwrap.runner; test-driver = let driver = (import ./src/main/java.nix { inherit pkgs; }).tests; in driver // { meta = driver.meta // { mainProgram = "pwrap-tests"; }; }; });
    apps = forSystems (system: {
      default = { type = "app"; meta.description = "Explicit pwrap compartments (NixOS beta)"; program = "${self.packages.${system}.pwrap}/bin/pwrap"; };
    });
    checks = forSystems (system: let
      pkgs = import nixpkgs { inherit system; };
    in {
      # Maven executes both JVM and native JUnit checks while building this artifact.
      unit = import ./src/main/java.nix { inherit pkgs; };
      kernel-policy = import ./src/test/kernel-policy.nix { inherit pkgs; };
      configuration = import ./src/test/configuration.nix { inherit pkgs sources; };
      providers = import ./src/test/providers.nix { inherit pkgs sources; };
      home-manager = import ./src/test/home-manager.nix { inherit pkgs sources; };
      home-files = import ./src/test/home-files.nix { inherit pkgs sources; };
      file-task = import ./src/test/file-task.nix { inherit pkgs sources; };
      confinements = import ./src/test/confinements.nix { inherit pkgs sources; };
      dbus = import ./src/test/dbus.nix { inherit pkgs sources; };
      portal-policy = import ./src/main/backends/nixos/dbus-proxy.nix { inherit pkgs; };
      defaults = import ./src/test/defaults.nix { inherit pkgs sources; };
      app-presets = import ./src/test/apps.nix { inherit pkgs sources; };
      policy-parity = import ./src/test/policy-parity.nix { inherit pkgs sources; };
      service-presets = import ./src/test/presets.nix { inherit pkgs nixpkgs; };
      service-ipc = import ./src/test/service-ipc.nix { inherit pkgs nixpkgs; };
      module-boundaries = import ./src/test/module-boundaries.nix { inherit pkgs nixpkgs sources; };
      source-inputs = import ./src/test/source-inputs.nix { inherit pkgs sources; };
      wrapper = import ./src/test/wrapper.nix {
        inherit pkgs;
        pwrap = self.lib.forPkgs pkgs;
      };
      runner = self.packages.${system}.pwrap;
      module = import ./src/test/module.nix { inherit pkgs nixpkgs sources; };
    });
  }
