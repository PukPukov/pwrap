{ pkgs, sources }:
let
  pwrap = import ../main { inherit pkgs sources; };
  task = pwrap.mkFileTask {
    name = "direct-policy-proof";
    home = "/srv/task-home";
    writable = [ "/srv/existing-file" ];
    persist = [ "/srv/task-data" ];
    forbiddenWrites = [ "/srv/controller" ];
    inheritEnvironment = [ "VERBOSE" ];
    script = "printf direct-ok";
  };
in pkgs.runCommand "pwrap-unified-file-task-policy" { nativeBuildInputs = [ pkgs.jq ]; } ''
  read -r execute runner action manifest separator forward < <(sed -n '2p' ${task}/bin/direct-policy-proof)
  [[ $execute == exec && $runner == ${pwrap.runner}/bin/pwrap && $action == profile && $separator == -- && $forward == '"$@"' ]]
  jq -e '.execution == "direct" and .home == "/srv/task-home"
    and .writable == ["/srv/existing-file"] and .persist == ["/srv/task-data"]
    and .forbiddenWrites == ["/srv/controller"] and .inheritEnvironment == ["VERBOSE"]
    and (has("tools") | not) and (has("passwd") | not) and (has("group") | not)' "$manifest" > /dev/null
  test -e "$(jq -r '.command[0]' "$manifest")"
  touch $out
''
