#!/data/data/com.termux/files/usr/bin/bash
# Builds the accessibility toggle app on the phone.
set -e
cd "$(dirname "$0")"

JAR=android.jar
if [ ! -f "$JAR" ]; then
    JAR=$(ls -1 "$HOME"/android-sdk/platforms/*/android.jar 2>/dev/null | tail -1)
fi
if [ -z "${JAR:-}" ] || [ ! -f "$JAR" ]; then
    echo "android.jar not found" >&2
    exit 1
fi

KS="$HOME/projects/wifidebug/debug.keystore"
[ -f "$KS" ] || KS=debug.keystore

rm -rf build
mkdir -p build/classes build/dex
javac -source 8 -target 8 -cp "$JAR" -d build/classes $(find src -name '*.java')
d8 --min-api 29 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')

aapt package -f --debug-mode -M AndroidManifest.xml -S res -I "$JAR" \
    -F build/autotoggle-unsigned.apk
(cd build/dex && aapt add ../autotoggle-unsigned.apk classes.dex >/dev/null)
apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out build/phone-link-autotoggle.apk build/autotoggle-unsigned.apk
ls -l build/phone-link-autotoggle.apk
