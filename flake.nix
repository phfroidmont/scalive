{
  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    liveview-upstream = {
      url = "github:phoenixframework/phoenix_live_view/v1.2.10";
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
        liveviewRev = liveview-upstream.rev or "v1.2.10";
        phoenixTarball = pkgs.fetchurl {
          url = "https://registry.npmjs.org/phoenix/-/phoenix-1.8.9.tgz";
          hash = "sha256-p6jLS1xZPB16IccI7vLNAPYtZFb18Ur8LpDD+umWUbo=";
        };
        liveViewTarball = pkgs.fetchurl {
          url = "https://registry.npmjs.org/phoenix_live_view/-/phoenix_live_view-1.2.10.tgz";
          hash = "sha256-+R7bEVuHjuJGpzD54zwRjUs6Dyu7yAdrTU9myaAPKDQ=";
        };
        liveViewClientResources =
          pkgs.runCommand "scalive-live-view-client-resources-1.8.9-1.2.10"
            {
              nativeBuildInputs = [
                pkgs.coreutils
                pkgs.gnutar
              ];
              inherit phoenixTarball liveViewTarball;
            }
            ''
              asset_root="$out/META-INF/scalive/live-view-client/phoenix-1.8.9-live-view-1.2.10"
              license_root="$out/META-INF/licenses/scalive/live-view-client"
              provenance="$out/META-INF/scalive/live-view-client/PROVENANCE.md"
              mkdir -p "$asset_root" "$license_root"

              tar -xzf "$phoenixTarball" -O package/priv/static/phoenix.min.js \
                > "$asset_root/phoenix.min.js"
              tar -xzf "$liveViewTarball" -O package/priv/static/phoenix_live_view.min.js \
                > "$asset_root/phoenix_live_view.min.js"
              tar -xzf "$phoenixTarball" -O package/LICENSE.md \
                > "$license_root/phoenix-1.8.9-LICENSE.md"
              tar -xzf "$liveViewTarball" -O package/LICENSE.md \
                > "$license_root/phoenix-live-view-1.2.10-LICENSE.md"

              (
                cd "$asset_root"
                sha256sum --check - <<'EOF'
              7f96de34f92e9d8bab93552210a435ec1bdb049fa54793eba876ab5153e1c233  phoenix.min.js
              0a18a51060d0dc19842068191400b2e0f3f75c853af0a04a879e29b91ec0a629  phoenix_live_view.min.js
              EOF
              )
              (
                cd "$license_root"
                sha256sum --check - <<'EOF'
              41a686069f0199ff369d7e5277d5267385cc54d9199f1efc960bacbe2e799255  phoenix-1.8.9-LICENSE.md
              5a76ffa3373ac1fbc8c8645967b980896154c26113c827d3c01c4b0158c057c6  phoenix-live-view-1.2.10-LICENSE.md
              EOF
              )

              cat > "$provenance" <<'EOF'
              # Phoenix LiveView Client Provenance

              Scalive redistributes the upstream browser-global minified files unchanged.

              | File | npm package | Version | Source path | SHA-256 |
              | --- | --- | --- | --- | --- |
              | `phoenix.min.js` | `phoenix` | `1.8.9` | `priv/static/phoenix.min.js` | `7f96de34f92e9d8bab93552210a435ec1bdb049fa54793eba876ab5153e1c233` |
              | `phoenix_live_view.min.js` | `phoenix_live_view` | `1.2.10` | `priv/static/phoenix_live_view.min.js` | `0a18a51060d0dc19842068191400b2e0f3f75c853af0a04a879e29b91ec0a629` |

              The files were obtained from the corresponding packages published on the npm registry.
              Their license notices are included under `META-INF/licenses/scalive/live-view-client`.

              | npm package | Tarball | npm integrity |
              | --- | --- | --- |
              | `phoenix@1.8.9` | `https://registry.npmjs.org/phoenix/-/phoenix-1.8.9.tgz` | `sha512-/2qzAZB3P2s08fFAYaG65lqaNFmVXUSlXdY4/JDdDKIC81y2cFWkPwI8gycy4VLpv197JwZ5PpBf3VhoG32yGA==` |
              | `phoenix_live_view@1.2.10` | `https://registry.npmjs.org/phoenix_live_view/-/phoenix_live_view-1.2.10.tgz` | `sha512-fDSIs4R04ubLZ71JkwgnsnTtIwBYGSMvrGgf1rIDyH7zJnhd2umsFR/nw9/6yHGKqyb7XYaOU3f01wjRZ1lsOg==` |
              EOF
            '';
      in
      {
        packages.liveViewClientResources = liveViewClientResources;

        devShell = pkgs.mkShell {
          SCALIVE_LIVE_VIEW_CLIENT_RESOURCES = "${liveViewClientResources}";
          buildInputs = [
            pkgs.mill
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
