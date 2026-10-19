{
  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    liveview-upstream = {
      url = "github:phoenixframework/phoenix_live_view/v1.2.12";
      flake = false;
    };
  };

  outputs =
    {
      nixpkgs,
      flake-utils,
      liveview-upstream,
      ...
    }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
        millVersion = "1.3.0";
        millSources = {
          aarch64-darwin = {
            suffix = "native-mac-aarch64";
            hash = "sha256-I5+aiUEGJgacOlDeTJrWlPfy25SRicEyHzPkAcTpXQA=";
          };
          aarch64-linux = {
            suffix = "native-linux-aarch64";
            hash = "sha256-rjZpyeXzFTFIoNYAVMOHwnzUYh45Ir3RX3HYhYR3mc4=";
          };
          x86_64-linux = {
            suffix = "native-linux-amd64";
            hash = "sha256-pY9O2Pl+dLxopknRifCqr0bVHRFq/m0pfJBPnfVWA7U=";
          };
        };
        millSource = millSources.${system} or (throw "Unsupported system for Mill: ${system}");
        mill = pkgs.mill.overrideAttrs {
          version = millVersion;
          src = pkgs.fetchurl {
            url = "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist-${millSource.suffix}/${millVersion}/mill-dist-${millSource.suffix}-${millVersion}.exe";
            inherit (millSource) hash;
          };
        };
        liveviewRev = liveview-upstream.rev or "v1.2.12";
        phoenixTarball = pkgs.fetchurl {
          url = "https://registry.npmjs.org/phoenix/-/phoenix-1.8.15.tgz";
          hash = "sha256-Ur6X3cmnhbxN32QdnehNaGFx8eZoS6QoKUssCgjhs+E=";
        };
        liveViewTarball = pkgs.fetchurl {
          url = "https://registry.npmjs.org/phoenix_live_view/-/phoenix_live_view-1.2.12.tgz";
          hash = "sha256-/KTygAK5lYp6jl6LtrRd2Shj6AIV15R/bM+kf8QX5ps=";
        };
        liveViewClientResources =
          pkgs.runCommand "scalive-live-view-client-resources-1.8.15-1.2.12"
            {
              nativeBuildInputs = [
                pkgs.coreutils
                pkgs.gnutar
              ];
              inherit phoenixTarball liveViewTarball;
            }
            ''
              asset_root="$out/META-INF/scalive/live-view-client/phoenix-1.8.15-live-view-1.2.12"
              license_root="$out/META-INF/licenses/scalive/live-view-client"
              provenance="$out/META-INF/scalive/live-view-client/PROVENANCE.md"
              mkdir -p "$asset_root" "$license_root"

              tar -xzf "$phoenixTarball" -O package/priv/static/phoenix.min.js \
                > "$asset_root/phoenix.min.js"
              tar -xzf "$liveViewTarball" -O package/priv/static/phoenix_live_view.min.js \
                > "$asset_root/phoenix_live_view.min.js"
              tar -xzf "$phoenixTarball" -O package/LICENSE.md \
                > "$license_root/phoenix-1.8.15-LICENSE.md"
              tar -xzf "$liveViewTarball" -O package/LICENSE.md \
                > "$license_root/phoenix-live-view-1.2.12-LICENSE.md"

              (
                cd "$asset_root"
                sha256sum --check - <<'EOF'
              02470e33af505595906535b87432092c19e1053a0029c7257986837781891e41  phoenix.min.js
              b3644c575949ce39f4cebf2d53f01eee659ae2ac4111075f4a7ece1002fecadc  phoenix_live_view.min.js
              EOF
              )
              (
                cd "$license_root"
                sha256sum --check - <<'EOF'
              41a686069f0199ff369d7e5277d5267385cc54d9199f1efc960bacbe2e799255  phoenix-1.8.15-LICENSE.md
              5a76ffa3373ac1fbc8c8645967b980896154c26113c827d3c01c4b0158c057c6  phoenix-live-view-1.2.12-LICENSE.md
              EOF
              )

              cat > "$provenance" <<'EOF'
              # Phoenix LiveView Client Provenance

              Scalive redistributes the upstream browser-global minified files unchanged.

              | File | npm package | Version | Source path | SHA-256 |
              | --- | --- | --- | --- | --- |
              | `phoenix.min.js` | `phoenix` | `1.8.15` | `priv/static/phoenix.min.js` | `02470e33af505595906535b87432092c19e1053a0029c7257986837781891e41` |
              | `phoenix_live_view.min.js` | `phoenix_live_view` | `1.2.12` | `priv/static/phoenix_live_view.min.js` | `b3644c575949ce39f4cebf2d53f01eee659ae2ac4111075f4a7ece1002fecadc` |

              The files were obtained from the corresponding packages published on the npm registry.
              Their license notices are included under `META-INF/licenses/scalive/live-view-client`.

              | npm package | Tarball | npm integrity |
              | --- | --- | --- |
              | `phoenix@1.8.15` | `https://registry.npmjs.org/phoenix/-/phoenix-1.8.15.tgz` | `sha512-AU2t9smrLcSy7DF1ic3VKDVNmjnhcsOgdeXBiVrR29BgFLmO4EN+tyGx/lRY3naxl7eJtyLrnlBOfJu8gYvXdQ==` |
              | `phoenix_live_view@1.2.12` | `https://registry.npmjs.org/phoenix_live_view/-/phoenix_live_view-1.2.12.tgz` | `sha512-MmJSXZrqThk4mTzyvxSujzdRj6OHdVf5P5IS+qLPKdquCzHBYC7YXCkltBPCaom4uKS1cBKGahU7fGhU3Vyo4w==` |
              EOF
            '';
      in
      {
        packages.liveViewClientResources = liveViewClientResources;

        devShell = pkgs.mkShell {
          SCALIVE_LIVE_VIEW_CLIENT_RESOURCES = "${liveViewClientResources}";
          buildInputs = [
            mill
            pkgs.nodejs
            pkgs.playwright-test
            pkgs.playwright-driver
            pkgs.rsync
            pkgs.curl
          ];
          shellHook = ''
            export JAVA_HOME="${pkgs.jdk}";
            export PLAYWRIGHT_NODEJS_PATH="${pkgs.nodejs}/bin/node";
            export PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS="true";
            export PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD="true";
            export PLAYWRIGHT_BROWSERS_PATH="${pkgs.playwright-driver.browsers}";
            export PLAYWRIGHT_TEST_NODE_PATH="${pkgs.playwright-test}/lib/node_modules";
            export LV_UPSTREAM_SRC="${liveview-upstream}";
            export LV_UPSTREAM_REV="${liveviewRev}";
            export LV_LOCAL_E2E_DIR="$PWD/.e2e-upstream/phoenix_live_view/$LV_UPSTREAM_REV";
          '';
        };
      }
    );
}
