{ config, lib, ... }: let
  cfg = config.security.pwrap.presets.caddy;
  poolNames = lib.filter (name: builtins.hasAttr name config.services.phpfpm.pools) cfg.phpPools;
  socketDirectories = map
    (name: builtins.dirOf config.services.phpfpm.pools.${name}.socket)
    poolNames;
  adminDirectory = builtins.dirOf cfg.adminSocket;
  runtimeDirectory = lib.removePrefix "/run/" adminDirectory;
  reload = "${lib.getExe config.services.caddy.package} reload"
    + " --address unix/${cfg.adminSocket}"
    + " --config /etc/caddy/caddy_config"
    + lib.optionalString (config.services.caddy.adapter != null)
      " --adapter ${config.services.caddy.adapter}"
    + " --force";
in import ../preset.nix { inherit config lib; } "caddy" {
  options = {
    readOnly = lib.mkOption {
      type = lib.types.listOf lib.types.str;
      default = [ ];
      description = "Host-owned content roots Caddy may read.";
    };
    privateFiles = lib.mkOption {
      type = lib.types.listOf lib.types.str;
      default = [ ];
      description = ''
        Files hidden from Caddy to prevent accidental static disclosure, for
        example after a routing mistake. This does not isolate PHP secrets from
        a compromised Caddy that has administrative access through raw FastCGI.
      '';
    };
    phpPools = lib.mkOption {
      type = lib.types.listOf lib.types.str;
      default = [ ];
      description = "PHP-FPM pools whose Unix sockets Caddy may access.";
    };
    adminSocket = lib.mkOption {
      type = lib.types.str;
      default = "/run/caddy/admin.sock";
      description = ''
        Caddy admin API Unix socket. If services.caddy.configFile or JSON settings
        are supplied directly, they must configure this same endpoint; arbitrary
        custom Caddy configuration cannot be inspected safely by this module.
      '';
    };
  };
  
  settings = {
    assertions = [{
      assertion = lib.hasPrefix "/run/" adminDirectory && adminDirectory != "/run";
      message = "security.pwrap.presets.caddy.adminSocket must be inside a RuntimeDirectory below /run.";
    }] ++ map (name: {
      assertion = builtins.hasAttr name config.services.phpfpm.pools;
      message = "security.pwrap.presets.caddy.phpPools names a missing PHP-FPM pool: ${name}";
    }) cfg.phpPools;

    security.pwrap.services.caddy = {
      egress = "internet";
      readOnly = [ "/etc/caddy/caddy_config" ] ++ cfg.readOnly ++ socketDirectories;
      writable = [ (toString config.services.caddy.dataDir) (toString config.services.caddy.logDir) adminDirectory ];
      capabilities = [ "CAP_NET_BIND_SERVICE" ];
      privateUsers = false;
    };

    # NixOS assembles globalConfig from modules for generated Caddyfiles. A
    # caller-supplied configFile owns its own admin directive (see option docs).
    services.caddy.globalConfig = lib.mkBefore "admin unix/${cfg.adminSocket}";

    systemd.services.caddy = {
      wants = map (name: "phpfpm-${name}.service") poolNames;
      after = map (name: "phpfpm-${name}.service") poolNames;
      serviceConfig = {
        RuntimeDirectory = runtimeDirectory;
        ExecReload = lib.mkForce ([ "" ] ++ lib.optional config.services.caddy.enableReload reload);
        InaccessiblePaths = lib.mkAfter (map (path: "-${path}") cfg.privateFiles);
      };
    };
  };
}
