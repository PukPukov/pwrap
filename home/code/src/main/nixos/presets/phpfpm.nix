{ config, lib, ... }: let
  cfg = config.security.pwrap.presets.phpfpm;
  pools = cfg.pools;
  poolConfig = name: config.services.phpfpm.pools.${name};
in import ../preset.nix { inherit config lib; } "phpfpm" {
  service = null;
  options = {
    pools = lib.mkOption {
      default = { };
      description = "Existing PHP-FPM pools and their application-data grants.";
      type = lib.types.attrsOf (lib.types.submodule {
        options = {
          readOnly = lib.mkOption {
            type = lib.types.listOf lib.types.str;
            default = [ ];
            description = "Application files this pool may read.";
          };
          writable = lib.mkOption {
            type = lib.types.listOf lib.types.str;
            default = [ ];
            description = "Application state this pool may modify.";
          };
        };
      });
    };
  };

  settings = {
    assertions = [
      {
        assertion = cfg.pools != { };
        message = "security.pwrap.presets.phpfpm requires at least one explicitly selected pool.";
      }
    ];

    security.pwrap.services = lib.mapAttrs' (name: grants:
      let pool = poolConfig name;
      in lib.nameValuePair "phpfpm-${name}" {
        readOnly = grants.readOnly;
        writable = lib.unique (grants.writable ++ [ (builtins.dirOf pool.socket) ]);
        packages = [ pool.phpPackage ];
        addressFamilies = [ "AF_UNIX" ];
        egress = "deny";
        dns = [ ];
        memoryMax = "4G";
      }) pools;

    services.phpfpm.settings.error_log = lib.mkForce "syslog";
    services.phpfpm.pools = lib.mapAttrs (_: _: {
      settings."php_admin_value[error_log]" = lib.mkForce "syslog";
      phpOptions = lib.mkAfter ''
        pcre.jit = 0
        opcache.jit = 0
      '';
    }) pools;

    systemd.services = lib.mapAttrs' (name: _:
      let pool = poolConfig name;
      in lib.nameValuePair "phpfpm-${name}" {
        serviceConfig = {
          User = lib.mkForce pool.user;
          Group = lib.mkForce pool.group;
          BindLogSockets = lib.mkForce true;
        };
      }) pools;
  };
}
