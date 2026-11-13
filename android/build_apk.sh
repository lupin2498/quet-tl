#!/bin/bash
# Build APK không cần Gradle (aapt2 + javac + d8/dx + zipalign + apksigner).
# Dùng khi không có Android Studio. Với Android Studio: mở thư mục này và Build > Build APK.
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$(pwd)
SDK=${ANDROID_SDK:-/usr/lib/android-sdk}
AJ=${ANDROID_JAR:-$SDK/platforms/android-23/android.jar}
PKG=vn.quettailieu.scanner
VERSION_CODE=${VERSION_CODE:-5}
VERSION_NAME=${VERSION_NAME:-1.4}
OUT=$ROOT/build/manual
KS=$ROOT/keystore/quettailieu.jks
KS_PASS=quettailieu
ALIAS=quettailieu

rm -rf "$OUT"
mkdir -p "$OUT/flat" "$OUT/gen" "$OUT/classes"

# Manifest: chèn package (Gradle dùng namespace trong build.gradle)
sed "s#<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">#<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"$PKG\">#" \
    app/src/main/AndroidManifest.xml > "$OUT/AndroidManifest.xml"

echo "== aapt2 compile/link"
aapt2 compile --dir app/src/main/res -o "$OUT/flat/res.zip"
aapt2 link -I "$AJ" --manifest "$OUT/AndroidManifest.xml" \
    --min-sdk-version 24 --target-sdk-version 34 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    --java "$OUT/gen" -o "$OUT/base.apk" "$OUT/flat/res.zip"

echo "== javac"
find app/src/main/java "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -nowarn -Xlint:-options -encoding UTF-8 -source 8 -target 8 \
    -bootclasspath "$AJ" -classpath "$AJ" -d "$OUT/classes" @"$OUT/sources.txt"

echo "== dex"
if command -v d8 >/dev/null 2>&1; then
    d8 --min-api 24 --lib "$AJ" --output "$OUT" $(find "$OUT/classes" -name '*.class')
else
    dalvik-exchange --dex --min-sdk-version=24 --output="$OUT/classes.dex" "$OUT/classes"
fi

echo "== package"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT" && zip -q -j unsigned.apk classes.dex)
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f "$KS" ]; then
    mkdir -p "$(dirname "$KS")"
    keytool -genkeypair -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" -alias "$ALIAS" \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Quet Tai Lieu, O=Personal, C=VN" >/dev/null 2>&1
fi
apksigner sign --ks "$KS" --ks-pass pass:"$KS_PASS" --key-pass pass:"$KS_PASS" --ks-key-alias "$ALIAS" \
    --out "$ROOT/build/QuetTaiLieu.apk" "$OUT/aligned.apk"
apksigner verify "$ROOT/build/QuetTaiLieu.apk"
ls -la "$ROOT/build/QuetTaiLieu.apk"
