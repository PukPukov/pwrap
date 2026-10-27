{ config, lib, pkgs, ... }:
let
  nm = pkgs.networkmanager.overrideAttrs (old: {
    patches = (old.patches or []) ++ [ ./patches/networkmanager-pidns.patch ];
  });
  inherit (import ../../backends/nixos/primitives.nix { inherit pkgs; }) scope;
  scoped = "${scope}/bin/pwrap-ipc-scope";
  native = import ../../java.nix { inherit pkgs; };
  dbusProxy = pkgs.xdg-dbus-proxy.overrideAttrs (old: {
    patches = (old.patches or []) ++ [ ./patches/xdg-dbus-proxy-visible-calls.patch ];
  });
  busDir = "/run/pwrap-nm-bus";
  busRules = [
    "--filter"
    "--own=org.freedesktop.NetworkManager"
    "--talk=fi.w1.wpa_supplicant1"
    # The desktop's registered secret agent has no well-known name. Permit just the
    # three standard callbacks to visible peers, not arbitrary calls to kded.
    "--call-visible=org.freedesktop.NetworkManager.SecretAgent.GetSecrets@/org/freedesktop/NetworkManager/SecretAgent"
    "--call-visible=org.freedesktop.NetworkManager.SecretAgent.SaveSecrets@/org/freedesktop/NetworkManager/SecretAgent"
    "--call-visible=org.freedesktop.NetworkManager.SecretAgent.DeleteSecrets@/org/freedesktop/NetworkManager/SecretAgent"
    "--call=org.freedesktop.login1=org.freedesktop.login1.Manager.Inhibit@/org/freedesktop/login1"
    "--call=org.freedesktop.login1=org.freedesktop.DBus.Properties.GetAll@/org/freedesktop/login1"
    "--broadcast=org.freedesktop.login1=org.freedesktop.login1.Manager.PrepareForSleep@/org/freedesktop/login1"
    "--broadcast=org.freedesktop.login1=org.freedesktop.login1.Manager.PrepareForShutdown@/org/freedesktop/login1"
    "--call=org.freedesktop.PolicyKit1=org.freedesktop.PolicyKit1.Authority.CheckAuthorization@/org/freedesktop/PolicyKit1/Authority"
    "--call=org.freedesktop.PolicyKit1=org.freedesktop.PolicyKit1.Authority.CancelCheckAuthorization@/org/freedesktop/PolicyKit1/Authority"
    "--call=org.freedesktop.PolicyKit1=org.freedesktop.DBus.Properties.GetAll@/org/freedesktop/PolicyKit1/Authority"
    "--broadcast=org.freedesktop.PolicyKit1=org.freedesktop.PolicyKit1.Authority.Changed@/org/freedesktop/PolicyKit1/Authority"
    # Discovery only. No ModemManager/BlueZ/firewalld/resolve1/dispatcher/host
    # service management. Add reviewed calls if such hardware is introduced.
    "--see=org.freedesktop.ModemManager1"
    "--see=org.bluez"
  ];
  common = (import ../../backends/nixos/services/hardening.nix { inherit lib; }) // {
    PrivateUsers = lib.mkForce false;
    SystemCallFilter = lib.mkForce [
      "@system-service"
      "~@mount @debug @raw-io @reboot @swap @cpu-emulation @obsolete @keyring @module bpf ptrace process_vm_readv process_vm_writev io_uring_setup io_uring_enter io_uring_register"
    ];
    UMask = "0077";
    TasksMax = 256;
    MemoryMax = "1G";
    MemoryDenyWriteExecute = true;
    SystemCallArchitectures = "native";
    SystemCallErrorNumber = "EPERM";
    BindReadOnlyPaths = [ "/etc/machine-id" "/etc/passwd" "/etc/group" ];
  };
in {
  options.security.pwrap.networkmanager = {
    apply = lib.mkOption {
      type = lib.types.bool;
      default = false;
      description = ''
        Apply native NetworkManager confinement. This does not enable
        networking.networkmanager.enable; review hardware integrations first.
      '';
    };
    forbiddenPaths = lib.mkOption {
      type = lib.types.listOf lib.types.str;
      default = lib.attrByPath [ "security" "pwrap" "forbiddenPaths" ] [] config;
      description = "Additional host paths that must be absent in NetworkManager's service root.";
    };
  };
  config = lib.mkIf config.security.pwrap.networkmanager.apply {
  assertions = [
    { assertion = config.networking.networkmanager.enable; message = "pwrap NetworkManager confinement requires networking.networkmanager.enable"; }
    { assertion = config.networking.networkmanager.wifi.backend == "wpa_supplicant"; message = "pwrap NetworkManager preset currently supports the wpa_supplicant backend; iwd needs an explicitly reviewed D-Bus grant."; }
    { assertion = config.networking.networkmanager.dispatcherScripts == []; message = "pwrap NetworkManager preset intentionally denies the dispatcher; dispatcherScripts require a separately confined integration."; }
  ];

  networking.networkmanager.package = nm;
  systemd.services.pwrap-nm-bus = {
    description = "Filtered system D-Bus for NetworkManager";
    requires = [ "dbus.service" ];
    after = [ "dbus.service" ];
    confinement = {
      enable = true;
      fullUnit = false;
      packages = [ native dbusProxy ];
    };
    serviceConfig = common // {
      Type = "notify";
      NotifyAccess = "main";
      ExecStart = "${scoped} ${native}/bin/pwrap-native _nm-bus ${dbusProxy}/bin/xdg-dbus-proxy unix:path=/run/dbus/system_bus_socket ${busDir}/bus ${lib.escapeShellArgs busRules}";
      RuntimeDirectory = [ "pwrap-nm-bus" ];
      RuntimeDirectoryMode = "0700";
      Restart = "always";
      RestartMode = "direct"; # Keep dependants up during an automatic retry.
      RestartSec = "2s";
      TimeoutStartSec = 30;
      KillMode = "control-group";
      CapabilityBoundingSet = "";
      AmbientCapabilities = "";
      RestrictAddressFamilies = [ "AF_UNIX" ];
      BindReadOnlyPaths = common.BindReadOnlyPaths ++ [ "/run/dbus/system_bus_socket" ];
      BindPaths = [ busDir ];
      ReadWritePaths = [ busDir ];
    };
  };
  systemd.services.NetworkManager = {
    requires = [ "pwrap-nm-bus.service" ];
    after = [ "pwrap-nm-bus.service" ];
    bindsTo = [ "pwrap-nm-bus.service" ];
    partOf = [ "pwrap-nm-bus.service" ]; # Broker upgrades/restarts restart NM too.
    confinement = {
      enable = true;
      fullUnit = false;
      packages = [ nm pkgs.iproute2 pkgs.coreutils pkgs.glibcLocales native pkgs.libseccomp ];
    };
    environment = {
      DBUS_SYSTEM_BUS_ADDRESS = "unix:path=${busDir}/bus";
      LOCALE_ARCHIVE = lib.mkForce "${pkgs.glibcLocales}/lib/locale/locale-archive";
    };
    serviceConfig = common // {
      ExecStart = lib.mkForce [ "" "${scoped} ${nm}/sbin/NetworkManager --no-daemon" ];
      # Host networking is intentional; not SYS_MODULE/BPF/DAC_OVERRIDE or
      # arbitrary host filesystem/process control. Driver loading is host udev's job.
      # The first empty assignment clears the upstream unit's broad set.
      # mkForce alone only replaces Nix definitions, not upstream directives.
      CapabilityBoundingSet = lib.mkForce [ "" "CAP_NET_ADMIN CAP_NET_RAW CAP_NET_BIND_SERVICE" ];
      AmbientCapabilities = [ "CAP_NET_ADMIN" "CAP_NET_RAW" "CAP_NET_BIND_SERVICE" ];
      RestrictAddressFamilies = [ "AF_UNIX" "AF_NETLINK" "AF_INET" "AF_INET6" "AF_PACKET" ];
      KillMode = lib.mkForce "control-group";
      Restart = lib.mkForce "always";
      RestartSec = "2s";
      ProcSubset = "all"; # /proc/sys/net; /proc still has only this PID namespace.
      BindReadOnlyPaths = common.BindReadOnlyPaths ++ [
        "/etc/NetworkManager" "/etc/NetworkManager/NetworkManager.conf"
        "/etc/hosts" "/etc/resolv.conf" "/etc/nsswitch.conf"
        "/run/udev/data" busDir
      ];
      BindPaths = [
        "/etc/NetworkManager/system-connections" "/var/lib/NetworkManager"
        "/run/NetworkManager" "/run/NetworkManager:/var/run/NetworkManager"
        "/proc/sys/net" "-/dev/rfkill" "-/dev/net/tun"
      ];
      ReadWritePaths = [
        "/etc/NetworkManager/system-connections" "/var/lib/NetworkManager"
        "/run/NetworkManager" "/var/run/NetworkManager" "/proc/sys/net"
      ];
      DeviceAllow = [ "/dev/rfkill rw" "/dev/net/tun rw" ];
      RuntimeDirectory = [ "NetworkManager" ];
      RuntimeDirectoryMode = "0755";
    };
    postStart = ''
      ${scoped} ${native}/bin/pwrap-native _nm-audit ${lib.getLib pkgs.libseccomp}/lib/libseccomp.so.2 ${lib.escapeShellArgs config.security.pwrap.networkmanager.forbiddenPaths}
    '';
  };
  };
}
