{ pkgs, pwrap }:
let
  wrapped = pwrap.mkApp {
    name = "alias-equals-executable";
    package = pkgs.hello;
    executable = "hello";
    aliases = [ "hello" ];
  };
  multi = pkgs.runCommand "multi-entry-package" { meta.mainProgram = "app"; } ''
    mkdir -p $out/bin $out/libexec $out/share/applications $out/share/dbus-1/services $out/share/icons
    printf '#!/bin/sh\n' | tee $out/bin/app $out/bin/helper $out/libexec/helper >/dev/null
    chmod +x $out/bin/* $out/libexec/*
    printf '[Desktop Entry]\nType=Application\nName=App\nExec=%s/bin/app %%U\nTryExec=%s/bin/app\nMimeType=x-scheme-handler/pwrap-fixture;\nDBusActivatable=true\n' "$out" "$out" > $out/share/applications/app.desktop
    printf '[Desktop Entry]\nType=Application\nName=Helper\nExec=%s/libexec/helper\n' "$out" > $out/share/applications/helper.desktop
    printf '[D-BUS Service]\nName=test.App\nExec=%s/libexec/helper\n' "$out" > $out/share/dbus-1/services/test.App.service
  '';
  multiWrapped = pwrap.mkApp { name = "multi"; package = multi; aliases = [ "alias" ]; };
  chromium = pkgs.runCommand "fake-chromium" { meta.mainProgram = "chromium"; } ''
    mkdir -p $out/bin $out/share/icons
    echo '#!/bin/sh' > $out/bin/chromium
    chmod +x $out/bin/chromium
  '';
  apps = pwrap.confinements.build {} ({ presets2, ... }: {
    chromium = presets2.chromium { name = "chromium"; };
    chromium-local = presets2.chromium {
      name = "chromium-local";
      network = { localhost = true; remote = false; };
    };
  });
  remote = apps.chromium chromium;
  local = apps.chromium-local chromium;
  software = pwrap.mkApp {
    name = "software-gui";
    package = pkgs.hello;
    desktop = true;
    gpu = false;
  };
  policy = app: builtins.fromJSON (builtins.unsafeDiscardStringContext app.pwrapManifest.text);
in
assert !(policy wrapped).desktop && !(policy wrapped).gpu;
assert (policy software).desktop && !(policy software).gpu;
assert (policy remote).desktop && (policy remote).gpu;
assert (policy remote).network == "internet" && !(policy remote).allowLocalhost;
assert (policy local).network == "local" && (policy local).allowLocalhost && !(policy local).allowLAN;
assert !(policy local).providers.uri.enable && builtins.all (key: (policy local).${key} == []) [ "dbusTalk" "dbusOwn" "dbusCalls" "dbusBroadcasts" ];
pkgs.runCommand "pwrap-wrapper-preserved" {} ''
  test -x ${multiWrapped}/bin/app
  test -x ${multiWrapped}/bin/alias
  test ! -e ${multiWrapped}/bin/helper
  test ! -e ${multiWrapped}/libexec
  test ! -e ${multiWrapped}/share/dbus-1
  test ! -e ${multiWrapped}/share/applications/helper.desktop
  grep -q 'DBusActivatable=false' ${multiWrapped}/share/applications/app.desktop
  grep -Fq '${multiWrapped}/bin/app" %U' ${multiWrapped}/share/applications/app.desktop
  grep -Fxq 'TryExec=${multiWrapped}/bin/app' ${multiWrapped}/share/applications/app.desktop
  # GLib rejects quoted TryExec paths even when KDE can launch their Exec.
  # Exercise the actual standard URI-handler lookup, with isolated MIME state.
  export XDG_DATA_HOME="$TMPDIR/xdg-data" XDG_CONFIG_HOME="$TMPDIR/xdg-config"
  export XDG_DATA_DIRS="$TMPDIR/empty-data" XDG_CONFIG_DIRS="$TMPDIR/empty-config" LC_ALL=C
  mkdir -p "$XDG_DATA_HOME/applications" "$XDG_CONFIG_HOME"
  cp ${multiWrapped}/share/applications/app.desktop "$XDG_DATA_HOME/applications/"
  printf '[Default Applications]\nx-scheme-handler/pwrap-fixture=app.desktop;\n' > "$XDG_CONFIG_HOME/mimeapps.list"
  ${pkgs.desktop-file-utils}/bin/update-desktop-database "$XDG_DATA_HOME/applications"
  ${pkgs.glib.bin}/bin/gio mime x-scheme-handler/pwrap-fixture > "$TMPDIR/handler"
  grep -Fq 'app.desktop' "$TMPDIR/handler"
  test -x ${wrapped}/bin/hello
  test ! -L ${wrapped}/bin/hello
  grep -q 'pwrap profile' ${wrapped}/bin/hello
  test -x ${remote}/bin/chromium
  test -x ${local}/bin/chromium-local
  test ! -e ${local}/bin/chromium
  test -e ${local}/share/icons
  grep -Fq '${local}/bin/chromium-local" %U' ${local}/share/applications/chromium-local.desktop
  ${pkgs.lib.concatMapStringsSep "\n" (app: let manifest = policy app; in ''
    grep -Fq '$HOME' ${pkgs.lib.escapeShellArg (builtins.head manifest.command)}
    grep -Fq '.config/${manifest.name}' ${pkgs.lib.escapeShellArg (builtins.head manifest.command)}
    grep -Fq '.cache/${manifest.name}' ${pkgs.lib.escapeShellArg (builtins.head manifest.command)}
  '') [ remote local ]}
  touch $out
''
