#!/usr/bin/env bash
#
# scripts/gen_nanopb.sh: Generate nanopb C sources from the vendored
# MumbleUDP.proto (see docs/target-architecture/voice.md#protobuf-through-nanopb).
#
# The generated MumbleUDP.pb.{c,h} are committed (like the generated Java
# protobuf classes), in commits separate from manual edits.
#
# Fails if the dev-shell generator and the jni/nanopb submodule versions
# differ: mixed versions silently change wire encoding.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PROTO="$ROOT_DIR/libraries/humla/src/MumbleUDP.proto"
OPTIONS="$ROOT_DIR/libraries/humla/src/main/jni/voice_codec/MumbleUDP.options"
OUT_DIR="$ROOT_DIR/libraries/humla/src/main/jni/voice_codec/generated"
SUBMODULE_GENERATOR="$ROOT_DIR/libraries/humla/src/main/jni/nanopb/generator/nanopb_generator.py"

for f in "$PROTO" "$OPTIONS" "$SUBMODULE_GENERATOR"; do
    if [[ ! -f "$f" ]]; then
        echo "gen_nanopb.sh: error: missing $f" >&2
        exit 1
    fi
done

NANOPB_GEN="$(command -v nanopb_generator.py || true)"
PROTOC_BIN="$(command -v protoc || true)"
PLUGIN_BIN="$(command -v protoc-gen-nanopb || true)"
if [[ -z "$NANOPB_GEN" || -z "$PROTOC_BIN" || -z "$PLUGIN_BIN" ]]; then
    echo "gen_nanopb.sh: error: nanopb_generator.py, protoc, and protoc-gen-nanopb are required (enter 'nix develop' with pkgs.nanopb + pkgs.protobuf)." >&2
    exit 1
fi

# Version pin: the dev-shell generator must match the submodule the build
# compiles against. Both report e.g. "nanopb-0.4.9.1".
GEN_VERSION="$("$NANOPB_GEN" --version 2>/dev/null | tr -d ' \t\r\n')"
SUB_VERSION="$(grep -m1 '^nanopb_version *=' "$SUBMODULE_GENERATOR" | sed -E 's/.*\"([^\"]+)\".*/\1/' | tr -d ' \t\r\n')"
if [[ -z "$GEN_VERSION" || -z "$SUB_VERSION" ]]; then
    echo "gen_nanopb.sh: error: could not determine nanopb versions (gen='$GEN_VERSION' sub='$SUB_VERSION')." >&2
    exit 1
fi
if [[ "$GEN_VERSION" != "$SUB_VERSION" ]]; then
    echo "gen_nanopb.sh: error: generator/submodule version mismatch (generator $GEN_VERSION, submodule $SUB_VERSION)." >&2
    echo "Update flake.nix pkgs.nanopb and jni/nanopb together, then re-run." >&2
    exit 1
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
cp "$PROTO" "$TMP/MumbleUDP.proto"
cp "$OPTIONS" "$TMP/MumbleUDP.options"
(
    cd "$TMP"
    "$PROTOC_BIN" --plugin="protoc-gen-nanopb=$PLUGIN_BIN" --nanopb_out=. MumbleUDP.proto
)

mkdir -p "$OUT_DIR"
cp "$TMP/MumbleUDP.pb.c" "$OUT_DIR/MumbleUDP.pb.c"
cp "$TMP/MumbleUDP.pb.h" "$OUT_DIR/MumbleUDP.pb.h"
echo "gen_nanopb.sh: wrote $OUT_DIR/MumbleUDP.pb.{c,h} ($GEN_VERSION)."
