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

# Patch the exec layer so the backend can find the bundled plumesign engine on
# Android. The engine lives in the exec-allowed nativeLibraryDir as
# libplumesign.so, but the backend execs the bare name "plumesign" (PATH
# lookup), which fails on Android 10+ where app-writable dirs are W^X-blocked.
# resolveBinary() lets the launcher pass the real engine path via PLUMESIGN_BIN.
COMMAND_GO="$ATV_DIR/internal/exec/command.go"
if [ ! -f "$COMMAND_GO" ]; then
  echo "error: $COMMAND_GO not found" >&2
  exit 1
fi
python3 - "$COMMAND_GO" <<'PY'
import sys, re
path = sys.argv[1]
src = open(path).read()
if "resolveBinary" in src:
    print("command.go already patched")
    sys.exit(0)
# Route the command name through resolveBinary().
src = src.replace(
    "func Command(name string, args ...string) *Cmd {\n\treturn &Cmd{\n\t\tName:    name,",
    "func Command(name string, args ...string) *Cmd {\n\treturn &Cmd{\n\t\tName:    resolveBinary(name),",
    1,
)
helper = '''
// resolveBinary maps a bare command name to an absolute path when the
// corresponding <NAME>_BIN environment variable is set. This lets packaged
// builds (Android, where the binary must live in the exec-allowed
// nativeLibraryDir under a lib-prefixed name) point the launcher at the real
// engine location without changing any call site. Unset => use PATH lookup.
func resolveBinary(name string) string {
\tif name != "plumesign" {
\t\treturn name
\t}
\tif p := os.Getenv("PLUMESIGN_BIN"); p != "" {
\t\treturn p
\t}
\treturn name
}
'''
# Insert the helper right after the Command() constructor's closing brace.
m = re.search(r"(func Command\(name string, args \.\.\.string\) \*Cmd \{.*?\n\}\n)", src, re.S)
if not m:
    print("error: could not locate Command() in command.go", file=sys.stderr)
    sys.exit(1)
src = src[:m.end()] + helper + src[m.end():]
open(path, "w").write(src)
print("patch applied: internal/exec/command.go resolveBinary installed")
PY

echo "patch applied: pkg/lzfseshim installed, decode_rgb.go import rewritten"