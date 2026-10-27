{ pkgs, source }:
# 0.13 uses mount_setattr instead of reparsing mountinfo for every read-only bind.
if pkgs.lib.versionAtLeast pkgs.bubblewrap.version "0.13.1" then pkgs.bubblewrap
else pkgs.bubblewrap.overrideAttrs {
  version = "0.13.1";
  src = source;
}
