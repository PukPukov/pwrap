# Public declarative DSL → schema-1 runtime policy. No package/distro/app knowledge.
{ resources ? {}, network ? {}, dbus ? {}, ipc ? {} }:
let
  checked = f: attrs:
    assert builtins.all (key: builtins.hasAttr key (builtins.functionArgs f)) (builtins.attrNames attrs);
    f attrs;
  limits = { memoryMax ? "4G", tasksMax ? 512 }: { inherit memoryMax tasksMax; };
  bus = { talk ? [], own ? [], calls ? [], broadcasts ? [], activation ? null, instancePrefix ? null }: {
    dbusTalk = talk; dbusOwn = own; dbusCalls = calls; dbusBroadcasts = broadcasts; dbusActivation = activation;
    instanceBusPrefix = instancePrefix;
  };
  net = { localhost ? false, remote ? false, lan ? false, unrestricted ? false }:
    assert builtins.all builtins.isBool [ localhost remote lan unrestricted ];
    assert !unrestricted || !(localhost || remote || lan);
    {
      network = if unrestricted then "host"
        else if remote then (if lan then "lan" else "internet")
        else if localhost || lan then "local" else "none";
      allowLocalhost = localhost;
      allowLAN = lan;
    };
  ipcPolicy = { hostAbstractSockets ? false }:
    assert builtins.isBool hostAbstractSockets;
    { ipc = { inherit hostAbstractSockets; }; };
  sharesHostNetwork = mode: allowLocalhost:
    builtins.elem mode [ "host" "lan" "local" ] || allowLocalhost;
  checkedNetwork = checked net network;
  checkedIpc = checked ipcPolicy ipc;
in assert !checkedIpc.ipc.hostAbstractSockets ||
  sharesHostNetwork checkedNetwork.network checkedNetwork.allowLocalhost;
checked limits resources // checkedNetwork // checked bus dbus // checkedIpc
