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

        androidComposition = pkgs.androidenv.composeAndroidPackages {
          buildToolsVersions = [ "34.0.0" "35.0.0" ];
          platformVersions = [ "34" "35" "36" ];
          abiVersions = [ "x86_64" "arm64-v8a" ];
          includeNDK = true;
          ndkVersions = [ "25.1.8937393" ];
          useGoogleAPIs = false;
        };

        androidSdk = androidComposition.androidsdk;
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
            androidSdk
          ];

          JAVA_HOME = "${jdk.home}";
          ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
          ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
          ANDROID_NDK_ROOT = "${androidSdk}/libexec/android-sdk/ndk/25.1.8937393";
          ANDROID_NDK_HOME = "${androidSdk}/libexec/android-sdk/ndk/25.1.8937393";
          NDK_HOME = "${androidSdk}/libexec/android-sdk/ndk/25.1.8937393";
          NDK_CCACHE = "${pkgs.ccache}/bin/ccache";
          GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidSdk}/libexec/android-sdk/build-tools/35.0.0/aapt2";

          shellHook = ''
            export GRADLE_USER_HOME="''${PWD}/.gradle-home"
            export ANDROID_USER_HOME="''${PWD}/.gradle-home/android"
            export CCACHE_DIR="''${PWD}/.gradle-home/ccache"
            echo "Mumla OLED Development Environment Loaded"
            echo "Java Version: $(${jdk}/bin/java -version 2>&1 | head -n 1)"
            echo "ANDROID_HOME: $ANDROID_HOME"
          '';
        };
      }
    );
}
