{ config, lib, ... }: let
  cfg = config.security.pwrap.presets.dnsproxy;
  # dnsproxy's bootstrap accepts IP:port; the generic DNS policy exposes only
  # port 53, so reject other forms rather than silently granting a wrong server.
  resolverAddress = endpoint: let
    ipv4 = builtins.match "([0-9.]+):53" endpoint;
    ipv6 = builtins.match "[[]([0-9a-fA-F:]+)[]]:53" endpoint;
  in if ipv4 != null then builtins.head ipv4
     else if ipv6 != null then builtins.head ipv6
     else if builtins.match "[0-9]+[.][0-9]+[.][0-9]+[.][0-9]+" endpoint != null
          || builtins.match "[0-9a-fA-F]*:[0-9a-fA-F:.]*" endpoint != null then endpoint
     else throw "pwrap dnsproxy bootstrap requires literal IPs with optional :53; set security.pwrap.presets.dnsproxy.bootstrapDNS explicitly for custom CLI configurations";
in import ../preset.nix { inherit config lib; } "dnsproxy" {
  options = {
    bootstrapDNS = lib.mkOption {
      type = lib.types.listOf lib.types.str;
      default = map resolverAddress (config.services.dnsproxy.settings.bootstrap or []);
      description = "Literal DNS bootstrap servers permitted by egress; derived from effective services.dnsproxy.settings.bootstrap. Set explicitly if command-line flags override bootstrap.";
    };
  };

  settings = {
    security.pwrap.services.dnsproxy = {
      egress = "internet";
      dns = cfg.bootstrapDNS;
      capabilities = [ "CAP_NET_BIND_SERVICE" ];
      privateUsers = false;
      memoryMax = "256M";
    };
  };
}
