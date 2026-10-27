{ config, lib, ... }: let cfg = config.security.pwrap.presets.postgresql; in import ../preset.nix { inherit config lib; } "postgresql" {

  options.socketDirectories = lib.mkOption {
    type = lib.types.listOf lib.types.str;
    default = let
      configured = config.services.postgresql.settings.unix_socket_directories or "/run/postgresql";
      paths = map lib.trim (lib.splitString "," configured);
    in lib.filter (path: path != "" && !lib.hasPrefix "@" path)
      (map (path: if path == "" || lib.hasPrefix "/" path || lib.hasPrefix "@" path then path
        else throw "pwrap PostgreSQL socket directories must be absolute paths; set security.pwrap.presets.postgresql.socketDirectories explicitly for quoted/comma-containing paths") paths);
    description = "Filesystem Unix socket directories, derived from PostgreSQL's comma-separated unix_socket_directories setting. Abstract sockets need no bind. Override explicitly for quoted/comma-containing directory names.";
  };

  settings = {
    security.pwrap.services.postgresql = {
      egress = "deny";
      dns = [ ];
      writable = [ config.services.postgresql.dataDir ] ++ cfg.socketDirectories;
      packages = [ config.services.postgresql.finalPackage ];
      memoryDenyWriteExecute = false; # PostgreSQL's configured LLVM JIT is an explicit capability.
      memoryMax = "4G";
    };
  };

}
