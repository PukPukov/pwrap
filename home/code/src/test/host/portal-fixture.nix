{ pkgs }:
pkgs.stdenv.mkDerivation {
  name = "pwrap-portal-test-fixture";
  src = ./portal-fixture.c;
  dontUnpack = true;
  strictDeps = true;
  nativeBuildInputs = [ pkgs.pkg-config ];
  buildInputs = [ pkgs.glib ];
  buildPhase = ''
    $CC -D_GNU_SOURCE -std=c11 -Wall -Wextra -Werror "$src" -o pwrap-portal-fixture \
      $(pkg-config --cflags --libs gio-unix-2.0)
  '';
  installPhase = ''
    install -Dm755 pwrap-portal-fixture "$out/bin/pwrap-portal-fixture"
  '';
}
