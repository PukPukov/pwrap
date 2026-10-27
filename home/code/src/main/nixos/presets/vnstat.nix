{ config, lib, ... }: let
  cfg = config.security.pwrap.presets.vnstat;
in import ../preset.nix { inherit config lib; } "vnstat" {

  options = {
    dataDirectory = lib.mkOption {
      type = lib.types.str;
      default = "/var/lib/vnstat";
      description = "Additional host-owned vnStat data directory.";
    };
  };

  settings = {
    security.pwrap.services.vnstat = {
      readOnly = [ "/etc/vnstat.conf" ];
      writable = lib.unique [ cfg.dataDirectory "/var/lib/vnstat" ];
      addressFamilies = [ "AF_UNIX" "AF_NETLINK" ];
      memoryMax = "256M";
    };

    # vnstat reads /proc/stat for uptime. ProtectProc=invisible still hides
    # unrelated processes while ProcSubset=all exposes that system file.
    systemd.services.vnstat.serviceConfig.ProcSubset = lib.mkForce "all";
  };
  
}
