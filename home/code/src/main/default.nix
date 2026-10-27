{ pkgs, sources, settings ? {} }:
let
  inherit (pkgs) lib;
  configuration = import ./backends/nix/configuration.nix { inherit pkgs settings; };
  dbusProxy = import ./backends/nixos/dbus-proxy.nix { inherit pkgs; };
  native = import ./java.nix { inherit pkgs; };
  inherit (import ./backends/nixos/primitives.nix { inherit pkgs; }) scope seccomp;
  toolbox = pkgs.buildEnv {
    name = "pwrap-toolbox";
    paths = [ pkgs.bash pkgs.coreutils pkgs.curl ];
  };
  fontConfig = pkgs.makeFontsConf { fontDirectories = [ pkgs.dejavu_fonts pkgs.noto-fonts-color-emoji ]; };
  waylandFirewall = import ./backends/nixos/wayland-firewall.nix { inherit pkgs; source = sources.wayland-firewall; };
  portalOpen = import ./backends/nixos/portal-open.nix { inherit pkgs; };
  portalTools = pkgs.symlinkJoin {
    name = "pwrap-portal-tools";
    paths = [ portalOpen (pkgs.makeDesktopItem {
      name = "pwrap-portal"; desktopName = "Desktop Portal";
      exec = "${portalOpen}/bin/xdg-open %u"; terminal = false;
      mimeTypes = [ "x-scheme-handler/http" "x-scheme-handler/https" "text/html" ];
    }) ];
    nativeBuildInputs = [ pkgs.desktop-file-utils ];
    postBuild = "update-desktop-database $out/share/applications";
  };
  runtimeConfig = pkgs.writeText "pwrap-runtime.json" (builtins.toJSON {
    storageBackend = "nix";
    platformBackend = "nixos";
    hostConfigDir = "/etc/pwrap";
    metadata = configuration.metadata;
    entry = "${native}/bin/pwrap-native";
    setpriv = "${pkgs.util-linux}/bin/setpriv";
    bwrap = lib.getExe (import ./backends/nixos/bubblewrap.nix { inherit pkgs; source = sources.bubblewrap; });
    inherit portalTools;
    slirp = lib.getExe pkgs.slirp4netns;
    slirpClosure = "${pkgs.closureInfo { rootPaths = [ pkgs.slirp4netns ]; }}/store-paths";
    dbusProxy = lib.getExe dbusProxy;
    dbusProxyClosure = "${pkgs.closureInfo { rootPaths = [ dbusProxy ]; }}/store-paths";
    waylandProxy = lib.getExe waylandFirewall;
    waylandProxyClosure = "${pkgs.closureInfo { rootPaths = [ waylandFirewall ]; }}/store-paths";
    waylandProxyConfig = pkgs.writeText "wayland-filter.toml" ''
      [firewall]
      log_blocked = false
    '';
    # Explicit rendering/focused-input policy, NOT upstream's permissive defaults.
    # In particular no data-control, screencast, fake-input, window-management,
    # security-context (alternate connections), layer-shell or XWayland globals.
    waylandInterfaces = [
      "wl_compositor" "wl_subcompositor" "wl_shm" "wl_seat" "wl_output"
      "xdg_wm_base" "xdg_wm_dialog_v1" "xdg_activation_v1" "xdg_system_bell_v1"
      "wp_viewporter" "wp_fractional_scale_manager_v1" "zxdg_output_manager_v1"
      "zxdg_decoration_manager_v1" "org_kde_kwin_server_decoration_manager"
      "org_kde_kwin_blur_manager" "org_kde_kwin_contrast_manager"
      "org_kde_kwin_slide_manager" "org_kde_kwin_server_decoration_palette_manager"
      "wp_cursor_shape_manager_v1" "wp_presentation" "wp_commit_timing_manager_v1"
      "wp_single_pixel_buffer_manager_v1" "wl_drm" "zwp_linux_dmabuf_v1"
      "wp_linux_drm_syncobj_manager_v1" "wp_fifo_manager_v1"
      "zwp_relative_pointer_manager_v1" "zwp_pointer_constraints_v1"
      "zwp_pointer_gestures_v1" "zwp_idle_inhibit_manager_v1"
      "wp_tearing_control_manager_v1" "zwp_text_input_manager_v1"
      "zwp_text_input_manager_v2" "zwp_text_input_manager_v3" "zwp_tablet_manager_v2"
      "wp_content_type_manager_v1" "wp_alpha_modifier_v1" "wp_color_manager_v1"
      "wl_data_device_manager" "zwp_primary_selection_device_manager_v1"
    ];
    systemdRun = "${pkgs.systemd}/bin/systemd-run";
    busctl = "${pkgs.systemd}/bin/busctl";
    nixStore = "${pkgs.nix}/bin/nix-store";
    ip = "${pkgs.iproute2}/bin/ip";
    nft = "${pkgs.nftables}/bin/nft";
    fcList = "${pkgs.fontconfig.bin}/bin/fc-list";
    gsettings = "${pkgs.glib.bin}/bin/gsettings";
    dconfModules = "${pkgs.dconf.lib}/lib/gio/modules";
    desktopSchemas = "${pkgs.gsettings-desktop-schemas}/share/gsettings-schemas/${pkgs.gsettings-desktop-schemas.name}/glib-2.0/schemas";
    shell = "${pkgs.bash}/bin/bash";
    env = "${pkgs.coreutils}/bin/env";
    inherit toolbox;
    cert = "${pkgs.cacert}/etc/ssl/certs/ca-bundle.crt";
    filter = "${seccomp}/filter.bpf";
    scope = "${scope}/bin/pwrap-ipc-scope";
    inherit fontConfig;
    localeArchive = "${pkgs.glibcLocales}/lib/locale/locale-archive";
    baseClosure = "${pkgs.closureInfo { rootPaths = [ toolbox scope portalTools pkgs.cacert fontConfig pkgs.glibcLocales ]; }}/store-paths";
  });
  runner = pkgs.writeShellScriptBin "pwrap" ''
    exec ${native}/bin/pwrap-native ${runtimeConfig} "$@"
  '';
  checkPreset = import ./backends/nix/app-options.nix { inherit lib; };
  mkApp = raw@{ package, ... }:
    assert lib.assertMsg (lib.isDerivation package) "pwrap.mkApp: package must be a Nix package";
    let
    args = checkPreset (removeAttrs raw [ "package" ]);
    inherit (args) name aliases arguments readOnly immutableData writable persist downloads forbiddenWrites runtime network resources dbus ipc
      desktop audio gpu environment sharedTmp providers;
    transformed = args.packageTransform package;
    # Package metadata is input too: validate the resolved name through the
    # same schema before interpolating it into build scripts.
    executable = if args.executable != null then args.executable else
      let resolved = transformed.meta.mainProgram or transformed.pname;
      in assert lib.assertMsg (resolved != null) "pwrap.mkApp: package mainProgram/pname must name an executable";
      (checkPreset { inherit name; executable = resolved; }).executable;
    policy = import ./core/policy.nix { inherit resources network dbus ipc; };
    manifest = pkgs.writeText "pwrap-${name}.json" (builtins.toJSON ({
      schemaVersion = 1;
      inherit name readOnly immutableData writable persist downloads forbiddenWrites desktop audio gpu environment sharedTmp providers;
      inherit (policy) network allowLocalhost allowLAN ipc dbusTalk dbusOwn dbusCalls dbusBroadcasts dbusActivation instanceBusPrefix memoryMax tasksMax;
      command = [ "${transformed}/bin/${executable}" ] ++ arguments;
      closure = "${pkgs.closureInfo { rootPaths = [ transformed ] ++ runtime; }}/store-paths";
      path = lib.makeBinPath ([ transformed toolbox ] ++ runtime);
    } // lib.optionalAttrs (settings ? network) {
      # Keep explicit common network policy with the compiled preset, so CLI
      # dispatch cannot replace it with a different runner's host settings.
      # Validate this independent input only. Whole runner metadata may refer
      # back to this manifest through its registry/browser fixed point.
      networkConfig = (configuration.check { inherit (settings) network; }).network;
    }));
  in pkgs.runCommand "${name}-pwrap" {
    pname = transformed.pname or name;
    version = transformed.version or "0";
    meta = transformed.meta // { mainProgram = executable; };
    passthru = (transformed.passthru or {}) // { unconfined = transformed; pwrapManifest = manifest; };
  } ''
    mkdir -p "$out/bin"
    cat > "$out/bin/${executable}" <<'EOF'
    #!${pkgs.bash}/bin/bash
    exec ${runner}/bin/pwrap profile ${manifest} -- "$@"
    EOF
    chmod +x "$out/bin/${executable}"
    ${lib.concatMapStringsSep "\n" (alias: ''
      ln -s ${lib.escapeShellArg executable} "$out/bin/${alias}"
    '') (lib.unique (lib.filter (alias: alias != executable) aliases))}
    ${native}/bin/pwrap-native _package-export ${transformed} "$out" '${executable}' '${builtins.toJSON aliases}'
  '' // lib.optionalAttrs (package ? override) {
    override = lib.mirrorFunctionArgs package.override (overrides:
      mkApp (raw // { package = package.override overrides; }));
  } // lib.optionalAttrs (package ? overrideAttrs) {
    overrideAttrs = overrides: mkApp (raw // { package = package.overrideAttrs overrides; });
  };
  mkFileTask = { name, script, writable ? [], persist ? [], forbiddenWrites ? [],
    readOnly ? [], immutableData ? [], home ? "/root", runtime ? [], inheritEnvironment ? [] }:
    let
      files = checkPreset { inherit name writable persist forbiddenWrites readOnly immutableData runtime; };
      payload = pkgs.writeShellScript "${name}-payload" ''
        set -euo pipefail
        export PATH=${lib.makeBinPath ([ pkgs.coreutils pkgs.gnugrep ] ++ runtime)}
        ${script}
      '';
      manifest = pkgs.writeText "${name}-task.json" (builtins.toJSON {
        schemaVersion = 1;
        execution = "direct";
        inherit home inheritEnvironment;
        inherit (files) name writable persist forbiddenWrites readOnly immutableData;
        command = [ (toString payload) ];
        closure = "${pkgs.closureInfo { rootPaths = [ payload ] ++ runtime; }}/store-paths";
      });
    in assert lib.assertMsg (builtins.isString script && builtins.isString home && lib.hasPrefix "/" home)
      "pwrap.mkFileTask: expected script and absolute home";
    assert lib.assertMsg (builtins.isList inheritEnvironment && builtins.all
      (key: builtins.isString key && builtins.match "[A-Za-z_][A-Za-z0-9_]*" key != null) inheritEnvironment)
      "pwrap.mkFileTask: invalid inherited environment keys";
    pkgs.writeShellScriptBin name ''
      exec ${runner}/bin/pwrap profile ${manifest} -- "$@"
    '';
  homeManagerPatcher = import ./backends/nix/patchers/home-manager.nix { inherit lib; };
  confinements = import ./backends/nix/confinements.nix {
    inherit lib checkPreset homeManagerPatcher;
    checkNetwork = network: (configuration.check { inherit network; }).network;
    mkAppFor = overrides: (import ./default.nix { inherit pkgs sources; settings = settings // overrides; }).mkApp;
    presets = import ./presets { inherit pkgs; };
  };
in {
  inherit runner mkApp mkFileTask confinements configuration;
  patchers.homeManager = homeManagerPatcher;
  patchers.defaults = import ./presets/defaults.nix { inherit lib; };
  homeManagerModule = import ./backends/nix/home-manager.nix;
  configure = settings: import ./default.nix { inherit pkgs sources settings; };
  mkRunner = settings: (import ./default.nix { inherit pkgs sources settings; }).runner;
  version = "0.1.0-beta.1";
}
