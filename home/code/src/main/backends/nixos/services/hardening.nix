{ lib }: {
  # /home is empty in the new root; only explicitly listed subtrees exist.
  # ProtectHome=yes would mask even those explicit bind mounts.
  ProtectHome = lib.mkForce false;
  PrivateUsers = lib.mkForce true;
  PrivatePIDs = true;
  PrivateTmp = lib.mkForce true;
  PrivateDevices = true;
  ProtectSystem = lib.mkForce "strict";
  ProtectKernelTunables = true;
  ProtectKernelModules = true;
  ProtectKernelLogs = true;
  ProtectControlGroups = lib.mkForce "strict";
  ProtectClock = true;
  ProtectHostname = true;
  ProtectProc = "invisible";
  ProcSubset = "pid";
  NoNewPrivileges = true;
  RestrictNamespaces = true;
  RestrictRealtime = true;
  RestrictSUIDSGID = true;
  LockPersonality = true;
}
