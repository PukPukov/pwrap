{ config, lib, pkgs, ... }: let
  cfg = config.security.pwrap;
  types = lib.types;
  kernel = import ../../../core/kernel-policy.nix;
  inherit (import ../primitives.nix { inherit pkgs; }) scope;
  etcFiles = [ "/etc/hosts" "/etc/resolv.conf" "/etc/nsswitch.conf" "/etc/passwd" "/etc/group" "/etc/localtime" "/etc/ssl/certs/ca-certificates.crt" ];
in {
  imports = [ ./egress.nix ];
  options.security.pwrap = {
    forbiddenPaths = lib.mkOption { type = types.listOf types.str; default = []; description = "Additional paths which must never be visible in generic service roots."; };
    services = lib.mkOption {
      default = {};
      description = "Explicit service filesystem/capability allowlists. Root login is unchanged.";
      type = types.attrsOf (types.submodule {
        options = {
          egress = lib.mkOption { type = types.nullOr (types.enum [ "deny" "internet" ]); default = null; description = "Optional IP egress policy on a dedicated stable slice; independent of User/DynamicUser. Replies remain allowed."; };
          dns = lib.mkOption { type = types.nullOr (types.listOf types.str); default = null; description = "Exact DNS53 resolver endpoints; null snapshots /etc/resolv.conf when pwrap-egress starts, empty denies DNS53. Remote DoT upstreams remain permitted. Restart pwrap-egress after resolver changes."; };
          readOnly = lib.mkOption { type = types.listOf types.str; default = []; };
          writable = lib.mkOption { type = types.listOf types.str; default = []; };
          packages = lib.mkOption { type = types.listOf types.package; default = []; };
          capabilities = lib.mkOption { type = types.listOf types.str; default = []; };
          allowIoUring = lib.mkOption {
            type = types.bool;
            default = false;
            description = "Allow io_uring syscalls for a reviewed service workload; this relaxes the generic syscall baseline and should be enabled only when required.";
          };
          addressFamilies = lib.mkOption { type = types.listOf types.str; default = kernel.addressFamilies; };
          privateUsers = lib.mkOption { type = types.bool; default = true; };
          memoryDenyWriteExecute = lib.mkOption { type = types.bool; default = true; };
          memoryMax = lib.mkOption { type = types.str; default = "2G"; };
          tasksMax = lib.mkOption { type = types.int; default = 256; };
        };
      });
    };
  };
  config = {
    environment.etc."pwrap/services.json".text = builtins.toJSON (lib.mapAttrs (_: p: {
      inherit (p) allowIoUring readOnly writable capabilities addressFamilies egress dns;
    }) cfg.services);
    systemd.services = lib.mapAttrs (_: p: { config, ... }: {
      imports = [ (import ./ipc.nix { inherit lib scope; }) ];
      config = {
      confinement = {
        enable = true; fullUnit = false;
        packages = p.packages ++ [ scope pkgs.cacert pkgs.glibcLocales pkgs.tzdata ]
          # NixOS collects the other Exec* commands itself, but not conditions.
          ++ lib.toList (config.serviceConfig.ExecCondition or []);
      };
      # Global NixOS may select a different archive output with fewer locales;
      # use the archive actually included in this service's runtime closure.
      environment.LOCALE_ARCHIVE = lib.mkForce "${pkgs.glibcLocales}/lib/locale/locale-archive";
      serviceConfig = (import ./hardening.nix { inherit lib; }) // {
        PrivateUsers = lib.mkForce p.privateUsers;
        # Empty Nix lists emit no directive, leaving systemd's default bounding
        # set. An empty string emits the explicit reset required for cap0.
        CapabilityBoundingSet = lib.mkForce (([ "" ] ++ p.capabilities));
        AmbientCapabilities = lib.mkForce (([ "" ] ++ p.capabilities));
        RestrictAddressFamilies = lib.mkForce p.addressFamilies;
        # File-socket-only workloads need no host network namespace. Egress=deny
        # alone does NOT imply this: a service may intentionally serve host TCP.
        PrivateNetwork = lib.mkDefault (p.addressFamilies == [ "AF_UNIX" ]);
        MemoryDenyWriteExecute = lib.mkForce p.memoryDenyWriteExecute;
        SystemCallArchitectures = "native";
        # @system-service follows systemd's evolving groups and includes @aio.
        # Default-deny all three io_uring entrypoints for generic services, but
        # keep an explicit reviewed escape hatch for custom workloads that need
        # io_uring; do not silently break such services or broaden their policy.
        SystemCallFilter = lib.mkForce (
          [
            "@system-service"
            "landlock_create_ruleset landlock_restrict_self"
            ("~@mount @debug @raw-io @reboot @swap @cpu-emulation @obsolete @keyring" +
              lib.optionalString (!p.allowIoUring) " io_uring_setup io_uring_enter io_uring_register")
          ] ++ lib.optional p.allowIoUring "io_uring_setup io_uring_enter io_uring_register"
        );
        SystemCallErrorNumber = "EPERM";
        UMask = lib.mkDefault "0077";
        TasksMax = lib.mkDefault p.tasksMax;
        MemoryMax = p.memoryMax;
        BindReadOnlyPaths = etcFiles ++ p.readOnly;
        BindPaths = p.writable;
        ReadWritePaths = p.writable;
      };
      postStart = ''
        # Fail closed if a future change accidentally exposes host management
        # or the interactive root's credentials inside a service root.
        for path in /etc/shadow /etc/nix/nix.conf /run/dbus/system_bus_socket \
          /run/systemd/private /run/docker.sock /nix/var/nix/daemon-socket/socket \
          ${lib.concatMapStringsSep " " lib.escapeShellArg cfg.forbiddenPaths}; do
          if [[ -e "$path" ]]; then
            echo "compartment boundary violation: $path" >&2
            exit 1
          fi
        done
      '';
      };
    }) cfg.services;
  };
}
