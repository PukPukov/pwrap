{ pkgs, source }: pkgs.rustPlatform.buildRustPackage rec {
  pname = "pwrap-wayland-firewall";
  version = "0.1.0-bfa5f63";
  src = source;
  cargoLock.lockFile = "${src}/Cargo.lock";
  patches = [ ./patches/wayland-firewall-hardening.patch ];
  doCheck = true;
  checkFlags = [ "--test-threads=1" ]; # Upstream tests share a per-process temp dir.
  meta = {
    mainProgram = "wayland-firewall";
    homepage = "https://github.com/wochap/wayland-firewall";
    license = pkgs.lib.licenses.mit;
    platforms = pkgs.lib.platforms.linux;
  };
}
