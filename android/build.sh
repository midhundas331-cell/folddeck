#!/usr/bin/env bash
# Build the phase-0 spike APK with no Gradle: aapt2 + javac + d8 + apksigner.
#
# Everything here ships with the Android SDK already, so there is no wrapper jar
# to fetch and no dependency resolution. Builds in about two seconds.
set -euo pipefail

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BT_VERSION="${BT_VERSION:-$(ls "$SDK/build-tools" | sort -V | tail -1)}"
BT="$SDK/build-tools/$BT_VERSION"
PLATFORM_API="${PLATFORM_API:-$(ls "$SDK/platforms" | sort -V | tail -1)}"
ANDROID_JAR="$SDK/platforms/$PLATFORM_API/android.jar"

MIN_SDK=31
TARGET_SDK=36
PKG=io.folddeck.spike
OUT="$(cd "$(dirname "$0")" && pwd)/build"
SRC="$(cd "$(dirname "$0")" && pwd)/src"
MANIFEST="$(cd "$(dirname "$0")" && pwd)/AndroidManifest.xml"

die() { echo "error: $*" >&2; exit 1; }

[ -d "$SDK" ]            || die "Android SDK not found at $SDK (set ANDROID_HOME)"
[ -f "$ANDROID_JAR" ]    || die "android.jar not found at $ANDROID_JAR"
[ -x "$BT/aapt2" ]       || die "aapt2 not found in $BT"
command -v javac >/dev/null || die "no JDK on PATH. Install one:
    sudo apt install -y openjdk-21-jdk-headless
(d8, apksigner and keytool are all JVM tools — there is no way around this.)"

echo "SDK          $SDK"
echo "build-tools  $BT_VERSION"
echo "platform     $PLATFORM_API"
echo

rm -rf "$OUT"
mkdir -p "$OUT/classes"

# 1. Compile res/ (the launcher icon) then link it with the manifest and
#    assets/ (the bundled JetBrains Mono, Omarchy's font).
echo "[1/5] aapt2 compile + link"
RES_DIR="$(cd "$(dirname "$0")" && pwd)/res"
LINK_RES=()
if [ -d "$RES_DIR" ]; then
    "$BT/aapt2" compile --dir "$RES_DIR" -o "$OUT/res.zip"
    LINK_RES=(-R "$OUT/res.zip")
fi
"$BT/aapt2" link \
    -o "$OUT/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$MANIFEST" \
    "${LINK_RES[@]}" \
    -A "$(cd "$(dirname "$0")" && pwd)/assets" \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    --java "$OUT/gen"

# 2. Java -> classes. android.jar goes on the classpath rather than the
#    bootclasspath; modern javac rejects -bootclasspath with a modern -source.
echo "[2/5] javac"
javac --release 17 -nowarn \
    -classpath "$ANDROID_JAR" \
    -d "$OUT/classes" \
    $(find "$SRC" -name '*.java')

# 3. classes -> dex. d8 also desugars the lambdas in MainActivity.
echo "[3/5] d8"
"$BT/d8" \
    --lib "$ANDROID_JAR" \
    --min-api "$MIN_SDK" \
    --output "$OUT" \
    $(find "$OUT/classes" -name '*.class')

# 4. Fold the dex into the APK and align. zipalign must run before signing:
#    apksigner preserves alignment, but zipalign would break an existing signature.
echo "[4/5] package + align"
(cd "$OUT" && zip -q base.apk classes.dex)
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

# 5. Sign with a debug key, generating one on first run.
echo "[5/5] sign"
KEYSTORE="$OUT/../debug.keystore"
BACKUP_KEYSTORE="$HOME/.secrets/folddeck-debug.keystore"

# Restore before generating. Android refuses to install an APK signed with a
# different key over an existing one, so silently minting a fresh key here would
# force an uninstall — losing the app's PIN and its pairing with the host — the
# first time this runs on a clean checkout. The keystore is gitignored, so a
# clean checkout is exactly when that happens.
if [ ! -f "$KEYSTORE" ] && [ -f "$BACKUP_KEYSTORE" ]; then
    echo "      restoring debug keystore from $BACKUP_KEYSTORE"
    install -m 0600 "$BACKUP_KEYSTORE" "$KEYSTORE"
fi

if [ ! -f "$KEYSTORE" ]; then
    echo "      generating a NEW debug keystore"
    echo "      NOTE: if this app is already installed on a device, that install"
    echo "            was signed with a different key and must be uninstalled"
    echo "            first (losing its PIN and host pairing)."
    keytool -genkeypair \
        -keystore "$KEYSTORE" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=FoldDeck Spike, OU=dev, O=dev, L=dev, S=dev, C=GB"
    if [ -d "$HOME/.secrets" ]; then
        install -m 0600 "$KEYSTORE" "$BACKUP_KEYSTORE"
        echo "      backed up to $BACKUP_KEYSTORE"
    fi
fi
"$BT/apksigner" sign \
    --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey \
    --min-sdk-version "$MIN_SDK" \
    --out "$OUT/folddeck-spike.apk" \
    "$OUT/aligned.apk"

rm -f "$OUT/base.apk" "$OUT/aligned.apk" "$OUT/aligned.apk.idsig"
echo
echo "built: $OUT/folddeck-spike.apk"
echo
echo "next:"
echo "  adb install -r $OUT/folddeck-spike.apk"
echo "  adb reverse tcp:5000 tcp:5000"
echo "  adb shell am start -n $PKG/.MainActivity --es host 127.0.0.1 --ei port 5000"
