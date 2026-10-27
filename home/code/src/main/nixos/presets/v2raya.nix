{ config, lib, pkgs, ... }: import ../preset.nix { inherit config lib; } "v2raya" {
  settings = {
    security.pwrap.services.v2raya = {
      writable = [ "/etc/v2raya" "/var/log/v2raya" "/run/v2raya" "/run/xtables.lock" "/dev/net/tun" ];
      packages = [ pkgs.iptables pkgs.iproute2 pkgs.bash config.services.v2raya.cliPackage ]
        ++ lib.optional config.networking.nftables.enable pkgs.nftables;
      capabilities = [ "CAP_NET_ADMIN" "CAP_NET_BIND_SERVICE" "CAP_NET_RAW" ];
      addressFamilies = (import ../../core/kernel-policy.nix).networkControlFamilies;
      privateUsers = false;
      memoryDenyWriteExecute = false;
    };

    systemd.services.v2raya = {
      environment.V2RAYA_ADDRESS = lib.mkDefault "127.0.0.1:2017";
      serviceConfig.DeviceAllow = lib.mkAfter [ "/dev/net/tun rw" ];
    };

    systemd.tmpfiles.rules = [
      "d /run/v2raya 0700 root root - -"
      "f /run/xtables.lock 0600 root root - -"
    ];
  };

}
