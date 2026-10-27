{ lib }: let
  reserved = [ "http" "https" "file" "javascript" "data" "about" "chrome" "resource" "blob" ];
  allowed = name: builtins.match "[a-z][a-z0-9+.-]*" name != null && !(builtins.elem name reserved);
in {
  scheme = lib.types.addCheck lib.types.str allowed;
  externalScheme = lib.types.addCheck lib.types.str (s: s == "http" || s == "https" || allowed s);
}
