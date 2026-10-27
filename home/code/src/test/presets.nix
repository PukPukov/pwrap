{ pkgs, nixpkgs }:
let
  lib = nixpkgs.lib;
  module = ../main/nixos;

  disabled = (nixpkgs.lib.nixosSystem {
    system = pkgs.stdenv.hostPlatform.system;
    modules = [
      module
      { system.stateVersion = "26.05"; }
    ];
  }).config;

  enabled = (nixpkgs.lib.nixosSystem {
    system = pkgs.stdenv.hostPlatform.system;
    modules = [
      module
      {
        system.stateVersion = "26.05";

        users.groups.pwrap-fpm = { };
        users.users.pwrap-fpm = {
          isSystemUser = true;
          group = "pwrap-fpm";
        };

        services.caddy = {
          enable = true;
          dataDir = "/srv/caddy-state";
          logDir = "/srv/caddy-log";
        };
        services.caddy.configFile = pkgs.writeText "pwrap-test-caddyfile" ''
          {
            admin unix//run/caddy/admin.sock
          }
        '';
        services.phpfpm.pools.mediawiki = {
          user = "pwrap-fpm";
          group = "pwrap-fpm";
          phpPackage = pkgs.php;
        };
        services.dnsproxy = {
          enable = true;
          settings.bootstrap = [ "9.9.9.9:53" "[2620:fe::fe]:53" ];
        };
        services.vnstat.enable = true;
        services.postgresql = {
          enable = true;
          dataDir = "/srv/postgres-data";
          settings.unix_socket_directories = "/run/pg-sockets, /srv/pg-sockets";
        };
        services.pgadmin = {
          enable = true;
          initialEmail = "pwrap@example.invalid";
          initialPasswordFile = pkgs.writeText "pwrap-test-pgadmin-password" "test-only";
        };
        services.v2raya.enable = true;
        networking.nftables.enable = true;
        systemd.services.v2raya.environment.V2RAYA_ADDRESS = "127.0.0.2:2018";

        security.pwrap.presets = {
          caddy = {
            apply = true;
            readOnly = [ "/srv/wiki/html" ];
            privateFiles = [ "/srv/wiki/html/LocalSettings.php" ];
            phpPools = [ "mediawiki" ];
          };
          phpfpm.apply = true;
          phpfpm.pools.mediawiki = {
            readOnly = [ "/srv/wiki/html" ];
            writable = [ "/srv/wiki/db" "/srv/wiki/html/images" ];
          };
          dnsproxy.apply = true;
          vnstat = {
            apply = true;
            dataDirectory = "/srv/vnstat-data";
          };
          postgresql.apply = true;
          pgadmin.apply = true;
          v2raya.apply = true;
        };
      }
    ];
  }).config;

  dnsConfiguration = settings: (nixpkgs.lib.nixosSystem {
    system = pkgs.stdenv.hostPlatform.system;
    modules = [ module {
      system.stateVersion = "26.05";
      services.dnsproxy = { enable = true; inherit settings; };
      security.pwrap.presets.dnsproxy.apply = true;
    } ];
  }).config;
  noBootstrap = dnsConfiguration {};
  badBootstrap = builtins.tryEval (builtins.deepSeq
    (dnsConfiguration { bootstrap = [ "192.0.2.1:5353" ]; }).security.pwrap.services.dnsproxy.dns true);

  networkmanager = (nixpkgs.lib.nixosSystem {
    system = pkgs.stdenv.hostPlatform.system;
    modules = [ module {
      system.stateVersion = "26.05";
      networking.networkmanager.enable = true;
      security.pwrap.forbiddenPaths = [ "/srv/private-authority" ];
      security.pwrap.networkmanager.apply = true;
    } ];
  }).config;

  missingUnderlying = (nixpkgs.lib.nixosSystem {
    system = pkgs.stdenv.hostPlatform.system;
    modules = [
      module
      {
        system.stateVersion = "26.05";
        security.pwrap.presets.postgresql.apply = true;
      }
    ];
  }).config;

  missingPhpPool = nixpkgs.lib.nixosSystem {
    system = pkgs.stdenv.hostPlatform.system;
    modules = [
      module
      {
        system.stateVersion = "26.05";
        security.pwrap.presets.phpfpm.apply = true;
        security.pwrap.presets.phpfpm.pools.missing = { };
      }
    ];
  };
  missingPhpPoolFails = builtins.tryEval (builtins.deepSeq
    missingPhpPool.config.systemd.services.phpfpm-missing.serviceConfig.User
    true);

  svc = enabled.security.pwrap.services;
  caddy = enabled.systemd.services.caddy;
  phpUnit = enabled.systemd.services.phpfpm-mediawiki;
  pgadmin = enabled.systemd.services.pgadmin;
  v2raya = enabled.systemd.services.v2raya;
  configHasAssertion = config: needle: lib.any
    (a: !a.assertion && lib.hasInfix needle a.message)
    config.assertions;
  proof = {
    optedInServices = builtins.attrNames svc;
    caddyRequiresPhp = caddy.after;
    phpAddressFamilies = svc.phpfpm-mediawiki.addressFamilies;
    egressServices = lib.filterAttrs (_: p: p.egress != null) svc;
    v2rayaCapabilities = svc.v2raya.capabilities;
  };
in
assert disabled.security.pwrap.services == { };
assert noBootstrap.security.pwrap.services.dnsproxy.dns == [];
assert !(noBootstrap.services.dnsproxy.settings ? bootstrap);
assert !badBootstrap.success;
# Native service confinement is an independent addon: no desktop module/runner.
assert !(disabled.programs ? pwrap);
assert networkmanager.security.pwrap.networkmanager.forbiddenPaths == [ "/srv/private-authority" ];
assert lib.hasInfix "_nm-audit" networkmanager.systemd.services.NetworkManager.postStart;
assert !(lib.hasInfix "tests-networkmanager.py" networkmanager.systemd.services.NetworkManager.postStart);
assert lib.hasInfix "/srv/private-authority" networkmanager.systemd.services.NetworkManager.postStart;
# Native presets are headless: no accidental graphics devices through /dev.
# VPN's explicitly requested tun is the only nonstandard device exception here.
assert lib.all (name: let unit = enabled.systemd.services.${name}.serviceConfig; in
  unit.PrivateDevices && lib.all (device: builtins.elem device
    [ "char-rtc r" "/dev/net/tun rw" ]) (unit.DeviceAllow or []))
  (builtins.attrNames svc);
assert !(disabled.security.pwrap.presets.caddy.apply);
assert !(disabled.security.pwrap.presets.phpfpm.apply);
assert !(disabled.security.pwrap.presets.dnsproxy.apply);
assert !(disabled.security.pwrap.presets.vnstat.apply);
assert !(disabled.security.pwrap.presets.postgresql.apply);
assert !(disabled.security.pwrap.presets.pgadmin.apply);
assert !(disabled.security.pwrap.presets.v2raya.apply);
assert !(disabled.security.pwrap.presets.caddy ? enable);
assert !(disabled.security.pwrap.presets.phpfpm ? enable);
assert !(disabled.security.pwrap.presets.dnsproxy ? enable);
assert !(disabled.security.pwrap.presets.vnstat ? enable);
assert !(disabled.security.pwrap.presets.postgresql ? enable);
assert !(disabled.security.pwrap.presets.pgadmin ? enable);
assert !(disabled.security.pwrap.presets.v2raya ? enable);
assert !(disabled.security.pwrap.networkmanager.apply);
assert !(disabled.security.pwrap.networkmanager ? enable);
assert !(disabled.services.caddy.enable);
assert !(disabled.services.dnsproxy.enable);
assert !(disabled.services.vnstat.enable);
assert !(disabled.services.postgresql.enable);
assert !(disabled.services.pgadmin.enable);
assert !(disabled.services.v2raya.enable);
assert !(missingUnderlying.services.postgresql.enable);
assert builtins.elem "/srv/caddy-state" svc.caddy.writable;
assert builtins.elem "/srv/caddy-log" svc.caddy.writable;
assert !(builtins.elem "/var/lib/caddy" svc.caddy.writable);
assert builtins.elem "/srv/wiki/html" svc.caddy.readOnly;
assert builtins.elem "/run/phpfpm" svc.caddy.readOnly;
assert builtins.elem "phpfpm-mediawiki.service" caddy.wants;
assert builtins.elem "phpfpm-mediawiki.service" caddy.after;
assert builtins.elem "-/srv/wiki/html/LocalSettings.php" caddy.serviceConfig.InaccessiblePaths;
assert builtins.elem "caddy" (lib.toList caddy.serviceConfig.RuntimeDirectory);
assert lib.hasInfix "admin unix//run/caddy/admin.sock" enabled.services.caddy.globalConfig;
assert lib.any (line: lib.hasInfix "--address unix//run/caddy/admin.sock" line) caddy.serviceConfig.ExecReload;
assert svc.phpfpm-mediawiki.egress == "deny";
assert svc.phpfpm-mediawiki.dns == [ ];
assert svc.phpfpm-mediawiki.addressFamilies == [ "AF_UNIX" ];
assert !missingPhpPoolFails.success;
assert builtins.elem "/run/phpfpm" svc.phpfpm-mediawiki.writable;
assert builtins.elem "/srv/wiki/db" svc.phpfpm-mediawiki.writable;
assert phpUnit.serviceConfig.User == "pwrap-fpm";
assert phpUnit.serviceConfig.Group == "pwrap-fpm";
assert phpUnit.serviceConfig.BindLogSockets;
assert enabled.services.phpfpm.settings.error_log == "syslog";
assert enabled.services.phpfpm.pools.mediawiki.settings."php_admin_value[error_log]" == "syslog";
assert lib.hasInfix "pcre.jit = 0" enabled.services.phpfpm.pools.mediawiki.phpOptions;
assert lib.hasInfix "opcache.jit = 0" enabled.services.phpfpm.pools.mediawiki.phpOptions;
assert !(enabled.users.users ? dnsproxy);
assert enabled.systemd.services.dnsproxy.serviceConfig.DynamicUser;
assert enabled.services.dnsproxy.settings.bootstrap == [ "9.9.9.9:53" "[2620:fe::fe]:53" ];
assert svc.dnsproxy.dns == [ "9.9.9.9" "2620:fe::fe" ];
assert svc.vnstat.writable == [ "/srv/vnstat-data" "/var/lib/vnstat" ];
assert svc.vnstat.addressFamilies == [ "AF_UNIX" "AF_NETLINK" ];
assert enabled.systemd.services.vnstat.serviceConfig.ProcSubset == "all";
assert svc.postgresql.egress == "deny";
assert svc.postgresql.dns == [ ];
assert builtins.elem enabled.services.postgresql.dataDir svc.postgresql.writable;
assert builtins.elem "/run/pg-sockets" svc.postgresql.writable;
assert builtins.elem "/srv/pg-sockets" svc.postgresql.writable;
assert !(builtins.elem "/run/postgresql" svc.postgresql.writable);
assert !(svc.postgresql.memoryDenyWriteExecute);
assert svc.pgadmin.egress == "deny";
assert svc.pgadmin.dns == [ ];
assert builtins.elem "-/run/pg-sockets" svc.pgadmin.readOnly;
assert builtins.elem "-/srv/pg-sockets" svc.pgadmin.readOnly;
assert builtins.elem "/var/lib/pgadmin" svc.pgadmin.writable;
assert pgadmin.serviceConfig.DynamicUser;
assert pgadmin.serviceConfig.KillSignal == "SIGINT";
assert builtins.elem pkgs.nftables svc.v2raya.packages;
assert svc.v2raya.privateUsers == false;
assert svc.v2raya.capabilities == [ "CAP_NET_ADMIN" "CAP_NET_BIND_SERVICE" "CAP_NET_RAW" ];
assert builtins.elem "/dev/net/tun" svc.v2raya.writable;
assert enabled.systemd.services.v2raya.environment.V2RAYA_ADDRESS == "127.0.0.2:2018";
assert !(enabled.security.pwrap ? network);
assert !(enabled.environment.etc ? "pwrap/network.json");
assert builtins.elem "/dev/net/tun rw" v2raya.serviceConfig.DeviceAllow;
assert builtins.elem "d /run/v2raya 0700 root root - -" enabled.systemd.tmpfiles.rules;
assert configHasAssertion missingUnderlying "requires services.postgresql.enable";
pkgs.writeText "pwrap-service-presets-proof" (builtins.toJSON proof)
