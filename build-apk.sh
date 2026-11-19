#!/usr/bin/env bash
# Package the atvloadly Android APK.
#
# Inputs (env):
#   GO_BIN     path to the Go backend binary built for android/arm64 (required)
#   ENGINE_BIN path to the plumesign binary built for aarch64-linux-android (required)
#   ANDROID_SDK_ROOT / ANDROID_HOME   Android SDK location (default /opt/android-sdk)
#
# Both binaries are bundled as "native libraries" (libatvloadly.so / libplumesign.so)
# so Android extracts them to the app's nativeLibraryDir, where exec is allowed.
# The app then runs them as child processes.
#
# Run on x86_64 (aapt2/zipalign/d8 execute natively). Produces
# dist/atvloadly-android-arm64.apk (signed with a generated debug keystore).
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}"
BT="$SDK/build-tools/34.0.0"
AJ="$SDK/platforms/android-34/android.jar"
ROOT="$(cd "$(dirname "$0")" && pwd)"        # android/ dir (script lives here)
APP="$ROOT/app/src/com/bitxeno/atvloadly"
RES="$ROOT/res"
WORK="$ROOT/build"

: "${GO_BIN:?set GO_BIN to the android/arm64 Go backend binary}"
: "${ENGINE_BIN:?set ENGINE_BIN to the aarch64-linux-android plumesign binary}"

command -v aapt2 >/dev/null || export PATH="$BT:$PATH"
command -v java  >/dev/null || export PATH="/usr/lib/jvm/java-17-openjdk-amd64/bin:$PATH"

mkdir -p "$WORK/gen" "$WORK/classes" "$WORK/dexout" "$WORK/lib/arm64-v8a" "$ROOT/dist" "$ROOT/keystore"

echo "[1/6] aapt2 compile + link resources"
aapt2 compile --dir "$RES" -o "$WORK/res.zip"
aapt2 link -o "$WORK/app-unsigned.apk" \
  -I "$AJ" \
  --manifest "$APP/AndroidManifest.xml" \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code 102 --version-name 1.0.2 \
  --java "$WORK/gen" --auto-add-overlay "$WORK/res.zip"

echo "[2/6] javac"
javac --release 11 -cp "$AJ" -d "$WORK/classes" \
  "$APP/MainActivity.java" \
  "$WORK/gen/com/bitxeno/atvloadly/R.java"

echo "[3/6] d8 (dex)"
"$BT/d8" --release --min-api 24 --output "$WORK/dexout" \
  $(find "$WORK/classes" -name '*.class')

echo "[4/6] bundle native binaries"
cp "$GO_BIN"     "$WORK/lib/arm64-v8a/libatvloadly.so"
cp "$ENGINE_BIN" "$WORK/lib/arm64-v8a/libplumesign.so"
chmod +x "$WORK/lib/arm64-v8a/"*.so

echo "[5/6] assemble + zipalign"
cp "$WORK/app-unsigned.apk" "$WORK/app-withdex.apk"
# Bundle native libs STORED (uncompressed) + 4-byte aligned via zipalign below.
# Android requires uncompressed .so to be page-aligned when extractNativeLibs=false;
# we keep extractNativeLibs default (true) but store uncompressed anyway for safety.
python3 - "$WORK" <<'PY'
import sys, zipfile, os
work = sys.argv[1]
apk = os.path.join(work, "app-withdex.apk")
adds = [
    (os.path.join(work, "dexout/classes.dex"), "classes.dex"),
    (os.path.join(work, "lib/arm64-v8a/libatvloadly.so"), "lib/arm64-v8a/libatvloadly.so"),
    (os.path.join(work, "lib/arm64-v8a/libplumesign.so"), "lib/arm64-v8a/libplumesign.so"),
]
# On Android 11+ with targetSdk >= 30, resources.arsc MUST be stored
# uncompressed and 4-byte aligned, or the installer rejects the APK
# ("App not installed"). Keep it STORED; native .so also STORED.
UNCOMPRESSED = {"resources.arsc"}
with zipfile.ZipFile(apk) as z:
    items = [(i.filename, z.read(i.filename)) for i in z.infolist() if i.filename != "classes.dex"]
with zipfile.ZipFile(apk, "w") as z:
    for name, data in items:
        ct = zipfile.ZIP_STORED if (name in UNCOMPRESSED or name.endswith(".so")) else zipfile.ZIP_DEFLATED
        z.writestr(name, data, compress_type=ct)
    for path, arc in adds:
        ct = zipfile.ZIP_STORED if arc.endswith(".so") else zipfile.ZIP_DEFLATED
        z.write(path, arc, compress_type=ct)
print("  bundled", ", ".join(a for _, a in adds))
PY
# -p: page-align uncompressed native libs; resources.arsc aligned by 4-byte zipalign
zipalign -f -p 4 "$WORK/app-withdex.apk" "$WORK/app-aligned.apk"

echo "[6/6] sign"
KS="$ROOT/keystore/atvloadly.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias atvloadly \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass atvloadly -keypass atvloadly \
    -dname "CN=atvloadly,O=bitxeno,C=CN"
fi
"$BT/apksigner" sign \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --ks "$KS" --ks-pass pass:atvloadly --key-pass pass:atvloadly \
  --ks-key-alias atvloadly \
  --out "$ROOT/dist/atvloadly-android-arm64.apk" "$WORK/app-aligned.apk"
"$BT/apksigner" verify --verbose "$ROOT/dist/atvloadly-android-arm64.apk" | grep -iE "verified|scheme" || true

ls -la "$ROOT/dist/atvloadly-android-arm64.apk"
echo "done: dist/atvloadly-android-arm64.apk"