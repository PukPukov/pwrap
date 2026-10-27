# Compose typed records; storage/package construction is injected by the backend.
{ lib, presets, checkPreset, checkNetwork, mkAppFor, homeManagerPatcher }:
{
  build = { env ? {}, settings ? {}, patchers ? [] }: builder:
    assert lib.assertMsg (builtins.isAttrs env && !lib.isDerivation env)
      "pwrap: env must be an attribute set";
    assert lib.assertMsg (builtins.all (key: builtins.elem key [ "nixos" "home" ]) (builtins.attrNames env))
      "pwrap: env accepts only nixos and home";
    assert lib.assertMsg (builtins.isBool (env.nixos or true)) "pwrap: env.nixos must be a boolean";
    assert lib.assertMsg ((env.home or null) == null || (builtins.isAttrs env.home && !lib.isDerivation env.home))
      "pwrap: env.home must be null or an evaluated Home Manager attribute set";
    assert lib.assertMsg (builtins.isAttrs settings && !lib.isDerivation settings) "pwrap: settings must be an attribute set";
    assert lib.assertMsg (builtins.all (key: builtins.elem key [ "downloads" "network" ]) (builtins.attrNames settings))
      "pwrap: settings accepts only downloads and network";
    assert lib.assertMsg (builtins.isList patchers && builtins.all builtins.isFunction patchers)
      "pwrap: patchers must be a list of preset-record functions";
    assert lib.assertMsg (builtins.isFunction builder) "pwrap: builder must be { presets1, presets2 }: { ... }";
    let
      checkedNetwork = checkNetwork (settings.network or {});
      compiler = mkAppFor (lib.optionalAttrs (settings ? network) { network = checkedNetwork; });
      catalog = presets ((removeAttrs settings [ "network" ]) // { nixos = env.nixos or true; });
      defaults = import ../../presets/defaults.nix { inherit lib; }
        ((removeAttrs settings [ "network" ]) // { nixos = env.nixos or true; });
      effectivePatchers = patchers ++ lib.optional ((env.home or null) != null) (homeManagerPatcher env.home);
      records = builder { inherit (catalog) presets1 presets2; };
      wrap = name: preset: package:
        assert lib.assertMsg (lib.isDerivation package) "pwrap: ${name} requires a Nix package";
        let
          validate = raw:
            assert lib.assertMsg (builtins.isAttrs raw && !lib.isDerivation raw)
              "pwrap: ${name} must be a preset record, not a package or an unapplied preset function";
            let checked = checkPreset (defaults raw);
            in assert lib.assertMsg (checked.name == name) "pwrap: preset name must match its registration key ${name}";
            # Do not feed schema defaults back into patches: gpu=desktop,
            # for example, must follow the final desktop grant, not the old one.
            builtins.seq checked raw;
          initial = assert lib.assertMsg (builtins.isAttrs preset && !lib.isDerivation preset)
            "pwrap: ${name} must be a preset record";
            validate (defaults ({ inherit name; } // preset));
          # Lower convenience flags before each patch, so adapters (including
          # HM) see the actual grants. The defaults patcher is idempotent.
          patched = lib.foldl' (raw: patcher: builtins.seq raw (validate (defaults (patcher raw)))) initial effectivePatchers;
          checked = checkPreset patched;
        in builtins.seq checked (compiler (checked // { inherit package; }));
    in
    assert lib.assertMsg (builtins.isAttrs records && !lib.isDerivation records) "pwrap: builder must return an attribute set of records";
    builtins.deepSeq checkedNetwork (lib.mapAttrs wrap records);
}
