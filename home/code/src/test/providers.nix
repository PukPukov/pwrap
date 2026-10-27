{ pkgs, sources }:
let
  inherit (pkgs) lib;
  pwrap = import ../main { inherit pkgs sources; };
  check = import ../main/backends/nix/app-options.nix { inherit lib; };
  rejects = preset: !(builtins.tryEval (builtins.deepSeq (check ({ name = "probe"; } // preset)) true)).success;
  app = pwrap.mkApp {
    name = "audio-probe"; package = pkgs.hello;
    audio = true; providers.audio = "nixos";
  };
  dormant = check { name = "dormant"; providers.audio = "nixos"; };
  direct = check { name = "direct"; audio = true; providers.audio = "direct"; };
  presets1 = pwrap.confinements.build {} ({ presets1, ... }: {
    firefox = lib.recursiveUpdate presets1.firefox { providers.audio = "direct"; };
  });
  policy = builtins.fromJSON (builtins.unsafeDiscardStringContext app.pwrapManifest.text);
in
assert rejects { audio = true; };
assert rejects { audio = true; providers.audio = "typo"; };
assert rejects { providers.audio = true; };
assert rejects { providers.typo = "nixos"; };
assert (check { name = "uris"; providers.uri = { enable = true; schemes = [ "tg" ]; }; }).providers.uri.schemes == [ "tg" ];
assert rejects { providers.uri.schemes = [ "file" ]; };
assert rejects { providers.uri.schemes = [ "http" ]; providers.uris = "broker"; };
assert rejects { providers.uri.schemes = [ "TG" ]; };
assert rejects { providers.uris = "broker"; };
assert (check { name = "web"; providers.uri.enable = true; }).providers.uri.enable;
assert (check { name = "files"; providers.openFile.enable = true; }).providers.openFile.enable;
assert rejects { providers.uri.enable = true; providers.webBrowser = "direct"; };
assert rejects { providers.webBrowser = "broker"; };
assert !dormant.audio && dormant.providers.audio == "nixos";
assert direct.audio && direct.providers.audio == "direct";
assert (check { name = "headless"; }).providers.audio == null;
assert (presets1.firefox pkgs.hello).pwrapManifest != null;
assert policy.audio;
assert policy.providers == {
  audio = "nixos";
  openFile = { enable = false; ask = true; };
  uri = { enable = false; ask = false; schemes = [ "http" "https" "tg" ]; };
};
assert (check { name = "no-confirm"; providers.openFile = { enable = true; ask = false; }; }).providers.openFile.ask == false;
assert rejects { providers.openFile.ask = "false"; };
assert rejects { providers.uri.ask = 0; };
assert rejects { providers.uri = { enable = true; schemes = []; }; };
assert rejects { integrations.openFile.enable = true; };
assert rejects { portals.openFile.enable = true; };
assert (check { name = "default-uri"; providers.uri.enable = true; }).providers.uri == {
  enable = true; ask = false; schemes = [ "http" "https" "tg" ];
};
assert (check { name = "short-uri"; providers.uri = true; }).providers.uri.enable;
assert (check { name = "short-file"; providers.openFile = true; }).providers.openFile == {
  enable = true; ask = true;
};
assert !(check { name = "no-files"; providers.openFile = false; }).providers.openFile.enable;
assert (check { name = "explicit-uri"; providers.uri = {
  enable = true; ask = true; schemes = [ "tg" "tg" ];
}; }).providers.uri == { enable = true; ask = true; schemes = [ "tg" ]; };
assert rejects { providers.web.enable = true; };
assert rejects { providers.uri = "true"; };
assert rejects { providers.openFile = 1; };
pkgs.runCommand "pwrap-provider-manifest-proof" {} ''
  test -s ${app.pwrapManifest}
  touch $out
''
