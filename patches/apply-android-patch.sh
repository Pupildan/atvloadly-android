#!/usr/bin/env bash
# Apply the Android build patch to a checkout of atvloadly.
#
# Upstream lzfse-go calls the C lzfse library through purego (dlopen), which
# requires cgo and therefore cannot build with CGO_ENABLED=0 on Android. The
# Assets.car icon decode is purely cosmetic, so on Android we swap in a shim
# that returns "unsupported" and the asset decoder skips .car icons gracefully
# (regular PNG icons still work). No cgo, no reimplemented codec.
#
# Usage: apply-android-patch.sh /path/to/atvloadly [patch-dir]
set -euo pipefail

ATV_DIR="${1:?usage: apply-android-patch.sh <atvloadly-dir> [patch-dir]}"
PATCH_DIR="${2:-$(cd "$(dirname "$0")/.." && pwd)/patch}"

SHIM_SRC="$PATCH_DIR/lzfseshim"
DEST="$ATV_DIR/pkg/lzfseshim"
DECODE="$ATV_DIR/pkg/bom/pkg/asset/decode_rgb.go"

mkdir -p "$DEST"
cp "$SHIM_SRC/lzfse.go" "$SHIM_SRC/lzfse_android.go" "$DEST/"

if [ ! -f "$DECODE" ]; then
  echo "error: $DECODE not found" >&2
  exit 1
fi

# Point decode_rgb.go at the shim instead of upstream lzfse-go.
sed -i 's#lzfse "github.com/bitxeno/lzfse-go"#lzfse "github.com/bitxeno/atvloadly/pkg/lzfseshim"#' "$DECODE"

echo "patch applied: pkg/lzfseshim installed, decode_rgb.go import rewritten"