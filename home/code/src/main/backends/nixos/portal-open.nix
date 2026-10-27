{ pkgs }:
pkgs.stdenv.mkDerivation {
  pname = "pwrap-portal-open";
  version = "0.1.0";
  src = ./native/portal-open.c;
  dontUnpack = true;
  strictDeps = true;
  nativeBuildInputs = [ pkgs.pkg-config ];
  buildInputs = [ pkgs.libportal ];
  buildPhase = ''
    $CC -std=c11 -Wall -Wextra -Werror "$src" -o xdg-open \
      $(pkg-config --cflags --libs libportal)
  '';
  installPhase = ''
    install -Dm755 xdg-open "$out/bin/xdg-open"
  '';
}
