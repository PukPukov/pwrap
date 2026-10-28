pwrap — pwn-wrap, wrap a pwn inside a bubblewrap sandbox as an additional layer of defence, so LLM kiddies can't hack you after another CVE.

## Motivation
A new RCE, php2shell, has shown up recently. Opinions on it differ, though I personally consider it log4shell-class. Previously RCEs were mitigated via patches, and this approach worked due to the fact that working exploit creation is a tedious and slow task, and also due to economic unfeasibility of making personalized attacks to whole IPv4 address space. However today LLMs are making personal exploits increasingly cheaper and faster, thus security-by-poverty is starting to die: you still have residential IP address, CPU/GPU and account cookies, moreover when the cost is this cheap you don't even need to have anything to steal from you to get hacked: if you are unprotected, kids can just rm -rf / you for fun. I expect inference to become much cheaper in the future, with LLM-specialized chips similar to Taalas HC1 and another cost-reduction technologies that are still yet to be explored by major AI corporations because at this moment the priority is still intelligence research; and with inference becoming cheaper, it is reasonable to expect cost of personalized attack to plummet too.

When bad security starts to die, the only thing left is good security. Ultimatively, cybersecurity is fundamentally different to physical security, because the attacker is not trying to overpower you, it tries to exploit your mistakes, there is fundamental asymmetry between attacker and defender, and thats why AI does not nullify all cybersecurity no matter how cheap or intelligent it is, though it definitely changes rules of the game in practice. Threat-modeling becomes primary and patching becomes secondary, and we should threat-model with expectation of possible breach anywhere. I expect php2shell to be the last log4shell-class exploit that we did manage to mitigate the old way; starting from now on all exploits should be mitigated by compartmentalization because you should expect that attacker would be faster in its attack than you in your patch.

The solution is: AppArmor, SELinux, Firejail, bubblewrap, etc, that is, distrust even to programs you run, not just to packets from the Internet.

## What is pwrap. Quickstart
A small (~1500 LoC core, ~6000 LoC with bloat) convenience integrating wrapper over bubblewrap and a few other permission tools to sandbox untrusted or not fully trusted code, and a bunch of NixOS service confinement presets (service confinement presets addon).

Currently works only with nix store, but another backend support can be added later — it is an explicit goal, so even the core can be changed if it turns out in the future it needs to be changed to support other distros and storages (it would still be packaged with nix only in the future even with the support of out-of-store binaries). 

### Main utility
Profiles are functions that wrap package. They are made from presets with confinements.build({
  env{boolean nixos, Object home}
  patchers[Patcher]?,
  settings{
    String downloads,
    network{relayServices[String], localDeniedPorts[int]}
  }
} buildSettings, function(attrset<preset1> presets1, attrset<preset2> presets2) -> attrset<preset1> builder) -> attrset<profile> function that is provided with the flake. In builder, presets1 and presets2 are first-order presets (aforementioned presets) and second-order presets, functions, that take settings attribute set and give you first-order preset. You can call it somewhere like this:
```nix
{ flakes, ... }: {
  pwrap = flakes.pwrap.lib.forPkgs corepkgs
}
```
(corepkgs is nixpkgs)
```nix
{ config, pwrap, ... }: pwrap.confinements.build {
  env = {
    nixos = true;
  };
  settings = {
    network = {
      relayServices = [ "v2raya.service" ];
      localDeniedPorts = [ 20170 20171 20172 52345 53129 ];
    };
    downloads = "/home/system/data/выход/загрузки/системные";
  };
} ({ presets1, presets2, ... }: {
  inherit (presets1) thunderbird discord telegram filezilla localsend cinny;
  firefox = presets1.firefox // {
    executable = "firefox-esr";
    network = presets1.firefox.network // { localhost = true; };
  };
  chromium = presets2.chromium { name = "chromium"; desktopName = "Chromium"; };
  chromium-local = presets2.chromium {
    name = "chromium-local"; desktopName = "Chromium (local)";
    network = { localhost = true; lan = true; remote = false; };
  };
  logseq = presets2.logseq { graphs = [ "/home/база данных/заметки/logseq/note-center" ]; };
  deluge = presets2.deluge { directories = [
    "/home/system/data/выход/загрузки/торрент/данные"
    "/home/system/data/выход/загрузки/торрент/файлы"
  ]; };
})
```

Then, with this preset, you can obtain packages like this:
```nix
{
  telegram  = pwrapApps.telegram (corepkgs-unstable.telegram-desktop);
  filezilla = pwrapApps.filezilla(corepkgs.filezilla);
  deluge    = pwrapApps.deluge   (corepkgs.deluge);
  localsend = pwrapApps.localsend(corepkgs.localsend);
  cinny     = pwrapApps.cinny    (corepkgs.cinny-desktop);
}
```
And add them to your system packages or pass to <program>.package nixos config parameters.

Thats cool. Until we need access granularity that host system does not provide no matter which already existing features we use. The main nixos module of pwrap alters your system so presets can finally give required permissions without simultaneously giving permissions to do some random ass shit. It also gives you ```pwrap``` cli. Example:
```nix
{ my-pkgs-attrset, ... }: {
  programs.pwrap = {
    cli = {
      enable = true;
      network = {
        relayServices = [ "v2raya.service" ];
        localDeniedPorts = [ 20170 20171 20172 52345 53129 ];
      };
      register = {
        inherit (my-pkgs-attrset) chromium chromium-local logseq telegram filezilla deluge localsend cinny;
      };
    };
    providers = {
      audio.enable = true;
    };
    # ...
  };
}
```

### Home manager

Home manager requires non-standard intergration.

Building confinements:
```nix
{ config, pwrap, ... }: pwrap.confinements.build {
  env = {
    home = config.home-manager.users.root;
  };
  # ...
} ({ presets1, presets2, ... }: {
  # ...
})
```
In home manager optionspace:
```nix
{ pwrapApps, ... }: {
  pwrap.programs = { inherit (pwrapApps) firefox thunderbird; };
}
```
In nixos optionspace:
```nix
{ config, flakes, pwrapHome, pwrapApps, ... }: let
  hmpkgs = config.programs.pwrap.integrations.homeManager.users.root.finalPackages;
in {
  programs.pwrap = {
    cli = {
      # ...
      register = {
        # ...
        inherit (hmpkgs) firefox thunderbird;
        # ...
      };
    };
    # ...
    integrations = {
      homeManager = {
        source = flakes.home-manager.outPath;
        users.root = {
          maintenance = { apply = true; roots = [ ".mozilla" ".thunderbird" ]; };
        };
      };
      # ...
    };
  };
}
```

### NixOS service confinement presets addon
Example:

```nix
{ settings, ... }: let
  site = settings.linkage.directories.lurkap;
in {
  security.pwrap = {
    # ...
    presets = {
      networkmanager = {
        apply = true;
        forbiddenPaths = [ settings.linkage.directories.user ];
      };
      caddy = {
        apply = true;
        readOnly = [ settings.linkage.directories.www-root "${site}/html" ];
        privateFiles = map(file: "${site}/html/${file}")(["LocalSettings.php" "composer.local.json" "composer.lock"]);
        phpPools = [ "caddy" ];
      };
      phpfpm = {
        apply = true;
        pools.caddy = {
          readOnly = [ "${site}/html" ];
          writable = [ "${site}/db" "${site}/html/images" ];
        };
      };
      dnsproxy.apply = true;
      v2raya.apply = true;
      vnstat = {
        apply = true;
        dataDirectory = settings.linkage.directories.vnstat-data;
      };
      postgresql.apply = true;
      pgadmin.apply = true;
    };
  };
}

```

<service>.apply merges confinement configuration with your configuration of this service. This addon does not depend on core and can be used separately.

Internal implementation deliberately diverges with main pwrap as main pwrap is not battle-tested yet. This divergence will be removed in the future and it will be backwards compatibility breakage.

## Why not X?
- SELinux: does not work on NixOS, the SELinux PR is still open and already stale.
- Flatpak: can't reuse foreign application storages, brings its own storage, which has a downside of dependency duplication (even shared runtimes do not fully solve it).
- AppArmor, Firejail: should be considered as alternatives to bubblewrap in backend implementation, not as alternatives to pwrap. You can consider pwrap an integration bundle. I could build pwrap on top of AppArmor or on top of Firejail, bubblewrap was chosen as the most small, portable and non-invasive out of all three.
- NixPak: NixPak code may be reused in the future as a dependency with patches. ATP for me reinventing the wheel seems like LESS of an overengineering than doing interproject code deduplication up-front.

## Disclaimer
Slop-coded. You can ask me in Discord why I decided to vibecode anything security-related. I dogfood pwrap on my desktop with a fair bunch of world-open services though, try pwning me: 93.90.234.186. Please note that inaccessible IP does not necessarily mean I was pwned, because I usually turn off my PC when I go to sleep.

## TODO
- Non-root main user support (I'm running everything as root, but I understand there are weirdos who love typing `sudo` 50 times a day for the illusion of security, thus I will remove root hardcode later)
- Other backends (non-nix, maybe non-wayland)
- Speedup program launch
