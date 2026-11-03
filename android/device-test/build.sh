#!/usr/bin/env bash
# Separate, same-signature instrumentation APK. Run only on your development device.
set -euo pipefail
cd "$(dirname "$0")/../.."
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
JAR="$SDK/platforms/$(ls "$SDK/platforms" | sort -V | tail -1)/android.jar"
OUT="android/build/ui-test"
mkdir -p "$OUT/classes"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$JAR" --manifest android/device-test/AndroidManifest.xml
javac --release 17 -cp "$JAR" -d "$OUT/classes" android/device-test/UiSmoke.java android/device-test/SharingHold.java
"$BT/d8" --lib "$JAR" --min-api 31 --output "$OUT" "$OUT"/classes/io/folddeck/uitest/*.class
(cd "$OUT" && zip -q base.apk classes.dex)
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks android/debug.keystore --ks-pass pass:android --key-pass pass:android \
    --out "$OUT/ui-test.apk" "$OUT/aligned.apk"
echo "Built $OUT/ui-test.apk"
