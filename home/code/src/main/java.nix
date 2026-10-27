# Maven/Native Image build plumbing only; policy and backend Nix logic is unchanged.
{ pkgs }:
let
  inherit (pkgs) lib;
  graal = pkgs.graalvmPackages.graalvm-ce;
  src = lib.fileset.toSource {
    root = ../../../..;
    fileset = lib.fileset.unions [
      ../../../../pom.xml ../../../../.mvn ./java ./checker
      ../test/java ../test/java-reference ./backends/nixos/desktop.nix
    ];
  };
in pkgs.maven.buildMavenPackage {
  pname = "pwrap-native";
  version = "0.1.0-beta.1";
  inherit src;
  outputs = [ "out" "tests" ];
  mvnJdk = graal;
  nativeBuildInputs = [ graal ];
  buildInputs = [ pkgs.zlib ];
  # Native Image freezes the builder's filesystem charset (sun.jnu.encoding).
  # The Nix build locale must exist before Maven and each image builder starts.
  LANG = "en_US.UTF-8";
  LC_ALL = "en_US.UTF-8";
  LOCALE_ARCHIVE = "${pkgs.glibcLocales}/lib/locale/locale-archive";
  mvnHash = "sha256-OrrMPWIJdDoL+kEocrSOEklfp6gbKnStE+VMSB3wXBQ=";
  mvnParameters = "-B -ntp -Pnative,native-tests -Dnative.library.path=${lib.getLib pkgs.zlib}/lib";
  postPatch = ''
    substituteInPlace pom.xml \
      --replace-fail '../../lib/puklapom/pom.xml' '.mvn/parent/pom.xml'
    substituteInPlace .mvn/parent/pom.xml \
      --replace-fail '<version>''${revision}</version>' '<version>1.4.10</version>'
  '';
  # A separate test output, never part of the installed launcher closure.
  postBuild = ''
    native-image --no-fallback -O1 -march=compatibility \
      --features=ru.pukpukov.pwrap.NativeTypes,ru.pukpukov.pwrap.host.HostTypes \
      -H:CLibraryPath=${lib.getLib pkgs.zlib}/lib \
      -cp "target/test-classes:$(find target -maxdepth 1 -name '*-all.jar' -print -quit)" \
      -o target/pwrap-tests ru.pukpukov.pwrap.NativeChecks
  '';
  installPhase = ''
    runHook preInstall
    install -Dm755 target/pwrap $out/bin/pwrap-native
    install -Dm644 target/pwrap.jar $out/share/java/pwrap.jar
    install -Dm755 target/pwrap-tests $tests/bin/pwrap-tests
    ln -s ${import ../test/host/portal-fixture.nix { inherit pkgs; }}/bin/pwrap-portal-fixture $tests/bin/pwrap-portal-fixture
    runHook postInstall
  '';
  meta = { mainProgram = "pwrap-native"; platforms = [ "x86_64-linux" ]; };
}
