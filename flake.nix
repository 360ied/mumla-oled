{
  description = "Mumla OLED Android development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        # Centralized Android SDK and NDK configuration
        androidConfig = {
          buildToolsVersion = "35.0.0";
          platformVersions = [ "36" ];
          abiVersions = [ "x86_64" "arm64-v8a" ];
          ndkVersion = "25.1.8937393"; # Must match ndkVersion in libraries/humla/build.gradle
        };

        androidComposition = pkgs.androidenv.composeAndroidPackages {
          buildToolsVersions = [ androidConfig.buildToolsVersion ];
          inherit (androidConfig) platformVersions abiVersions;
          includeNDK = true;
          ndkVersions = [ androidConfig.ndkVersion ];
          useGoogleAPIs = false;
        };

        androidSdk = androidComposition.androidsdk;
        androidSdkRoot = "${androidSdk}/libexec/android-sdk";
        ndkRoot = "${androidSdkRoot}/ndk/${androidConfig.ndkVersion}";
        aapt2Path = "${androidSdkRoot}/build-tools/${androidConfig.buildToolsVersion}/aapt2";
        jdk = pkgs.jdk21;
      in
      {
        devShells.default = pkgs.mkShell {
          name = "mumla-oled-dev-shell";

          buildInputs = [
            jdk
            pkgs.gradle
            pkgs.protobuf
            pkgs.git
            pkgs.gnumake
            pkgs.python3
            pkgs.ccache
            pkgs.markdownlint-cli
            pkgs.cmark
            pkgs.cmark-gfm
            pkgs.prettier
            androidSdk
          ];

          JAVA_HOME = "${jdk.home}";
          ANDROID_HOME = androidSdkRoot;
          ANDROID_SDK_ROOT = androidSdkRoot;
          ANDROID_NDK_ROOT = ndkRoot;
          ANDROID_NDK_HOME = ndkRoot;
          NDK_HOME = ndkRoot;
          NDK_CCACHE = "${pkgs.ccache}/bin/ccache";
          GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${aapt2Path}";

          shellHook = ''
            REPO_ROOT="$(git rev-parse --path-format=absolute --git-common-dir 2>/dev/null | sed -E 's#/\.git(/.*)?$##')"
            if [ -z "$REPO_ROOT" ] || [ ! -d "$REPO_ROOT" ]; then
              REPO_ROOT="''${PWD}"
            fi
            export GRADLE_USER_HOME="''${REPO_ROOT}/.gradle-home"
            export ANDROID_USER_HOME="''${REPO_ROOT}/.gradle-home/android"
            export CCACHE_DIR="''${REPO_ROOT}/.gradle-home/ccache"
            echo "Mumla OLED Development Environment Loaded"
            echo "Java Version: $(${jdk}/bin/java -version 2>&1 | head -n 1)"
            echo "ANDROID_HOME: $ANDROID_HOME"
            echo "GRADLE_USER_HOME: $GRADLE_USER_HOME"
          '';
        };
      }
    );
}
