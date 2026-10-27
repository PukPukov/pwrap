# Pure adapter: selected HM files are trusted immutable resources, not path grants.
{ lib }:
home: preset:
let
  absolute = path:
    let expanded = if lib.hasPrefix "~/" path
      then "${home.home.homeDirectory}/${lib.removePrefix "~/" path}" else path;
    in assert lib.assertMsg (lib.hasPrefix "/" expanded)
      "pwrap Home Manager patcher: grants must use absolute or ~/ paths";
    if expanded == "/" then "/" else
      "/${lib.removePrefix "./" (lib.path.subpath.normalise (lib.removePrefix "/" expanded))}";
  downloads = lib.optional ((preset.downloads or null) != null) preset.downloads;
  grants = map absolute ((preset.readOnly or []) ++ (preset.writable or []) ++ (preset.persist or []) ++ downloads);
  controller = absolute "${home.xdg.stateHome}/home-manager";
  writes = map absolute ((preset.writable or []) ++ (preset.persist or []) ++ downloads);
  overlaps = a: b: a == "/" || b == "/" || a == b || lib.hasPrefix "${a}/" b || lib.hasPrefix "${b}/" a;
  selected = lib.filter (file:
    file.enable && lib.any (grant:
      let target = absolute "${home.home.homeDirectory}/${lib.path.subpath.normalise file.target}";
      in target == grant || lib.hasPrefix "${grant}/" target
    ) grants
  ) (lib.attrValues home.home.file);
  maintenance = map (root: absolute "${home.home.homeDirectory}/${root}") (home.pwrap.maintenanceRoots or []);
  uncovered = lib.filter (grant:
    lib.any (file: file.enable && overlaps grant (absolute "${home.home.homeDirectory}/${file.target}")) (lib.attrValues home.home.file)
    && !(lib.any (root: grant == root || lib.hasPrefix "${root}/" grant) maintenance)
  ) writes;
  # Current activation is a controller-owned pointer, not app profile state.
  # Using its exact selected targets avoids a derivation cycle through unrelated
  # HM files such as fontconfig, which can depend on the installed wrappers.
  files = map (file: "${controller}/gcroots/current-home/home-files/${file.target}") selected;
in assert lib.assertMsg (!(lib.any (path: overlaps path controller) writes))
  "pwrap Home Manager patcher: application writable paths must not overlap the HM controller state";
assert lib.assertMsg (uncovered == [])
  "pwrap Home Manager patcher: mutable grants overlapping home.file need applied confined maintenance covering the whole grant: ${lib.concatStringsSep ", " uncovered}";
preset // {
  immutableData = lib.unique ((preset.immutableData or []) ++ files);
  forbiddenWrites = lib.unique ((preset.forbiddenWrites or []) ++ [ controller ]);
}
