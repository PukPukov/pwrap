{ pkgs }:
pkgs.xdg-dbus-proxy.overrideAttrs (old: {
  patches = (old.patches or []) ++ [
    ./patches/xdg-dbus-proxy-owned-socket.patch
    ./patches/xdg-dbus-proxy-portal-open.patch
  ];
  postPatch = (old.postPatch or "") + ''
    cp ${../../../test/portal-open.c} tests/pwrap-portal-tests.c
    # Upstream disables these tests because /etc is absent in a Nix build.
    substituteInPlace tests/test-proxy.c --replace-fail '"--session",' \
      '"--config-file=${pkgs.dbus}/share/dbus-1/session.conf",'
  '';
  doCheck = true;
  mesonCheckFlags = (old.mesonCheckFlags or []) ++ [ "--verbose" ];
  preCheck = (old.preCheck or "") + ''
    export PWRAP_PORTAL_CLIENT=${import ./portal-open.nix { inherit pkgs; }}/bin/xdg-open
  '';
})
