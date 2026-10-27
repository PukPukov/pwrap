{ config, lib, ... }: import ../preset.nix { inherit config lib; } "pgadmin" {

  settings = {
    security.pwrap.services.pgadmin = {
      egress = "deny";
      dns = [ ];
      writable = [ "/var/lib/pgadmin" "/var/log/pgadmin" ];
      readOnly = [ "/etc/pgadmin" ] ++ map (path: "-${path}") config.security.pwrap.presets.postgresql.socketDirectories;
      packages = [ config.services.postgresql.package ];
      memoryMax = "1G";
    };

    systemd.services.pgadmin.serviceConfig = {
      KillSignal = lib.mkForce "SIGINT";
    };
  };

}
