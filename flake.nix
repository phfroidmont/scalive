{
  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs =
    { nixpkgs, flake-utils, ... }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
        mill = pkgs.mill.overrideAttrs (old: rec {
          version = "1.0.5";
          src = pkgs.fetchurl {
            url = "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist-native-linux-amd64/${version}/mill-dist-native-linux-amd64-${version}.exe";
            hash = "sha256-xxQMCJ659b/RrSzGKYvXdTcXS9F3TZRGTAbcAc7yF3o=";
          };
          buildInputs = [ pkgs.zlib ];
          nativeBuildInputs = [
            pkgs.makeWrapper
          ]
          ++ pkgs.lib.optional pkgs.stdenvNoCC.isLinux pkgs.autoPatchelfHook;

          installPhase = ''
            runHook preInstall

            install -Dm 555 $src $out/bin/.mill-wrapped
            # can't use wrapProgram because it sets --argv0
            makeWrapper $out/bin/.mill-wrapped $out/bin/mill \
              --prefix PATH : "${pkgs.jre}/bin" \
              --set-default JAVA_HOME "${pkgs.jre}"

            runHook postInstall
          '';
          doInstallCheck = false;
        });
      in
      {
        devShell = pkgs.mkShell {
          buildInputs = [
            mill
            pkgs.scalafmt
            pkgs.tailwindcss_4
          ];
          shellHook = "mill --bsp-install";
        };
      }
    );
}
