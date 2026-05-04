{
  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    liveview-upstream = {
      url = "github:phoenixframework/phoenix_live_view/v1.1.8";
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
        liveviewRev = liveview-upstream.rev or "v1.1.8";
      in
      {
        devShell = pkgs.mkShell {
          buildInputs = [
            pkgs.mill
            pkgs.nodejs
            pkgs.playwright-test
            pkgs.playwright-driver
            pkgs.rsync
          ];
          shellHook = ''
            export JAVA_HOME="${pkgs.jdk}";
            export PLAYWRIGHT_NODEJS_PATH="${pkgs.nodejs}/bin/node";
            export PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS="true";
            export PLAYWRIGHT_BROWSERS_PATH="${pkgs.playwright-driver.browsers}";
            export PLAYWRIGHT_TEST_NODE_PATH="${pkgs.playwright-test}/lib/node_modules";
            export LV_UPSTREAM_SRC="${liveview-upstream}";
            export LV_UPSTREAM_REV="${liveviewRev}";
            export LV_LOCAL_E2E_DIR="$PWD/.e2e-upstream/phoenix_live_view/$LV_UPSTREAM_REV";
            mill --bsp-install
          '';
        };
      }
    );
}
