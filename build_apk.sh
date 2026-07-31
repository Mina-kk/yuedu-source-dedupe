#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
SDK="$HOME/.buildozer/android/platform/android-sdk"
BT="$SDK/build-tools/34.0.0"
AJAR="$SDK/platforms/android-34/android.jar"
rm -rf bin dist
mkdir -p bin/classes dist
export JAVA_TOOL_OPTIONS="-Xmx384m -XX:MaxMetaspaceSize=192m"
echo '=== tests ==='
./run_tests.sh
echo '=== javac ==='
find src -name '*.java' | sort > bin/java-inputs.txt
javac -J-Xmx384m -source 1.8 -target 1.8 -cp "$AJAR" -d bin/classes @bin/java-inputs.txt
echo '=== d8 ==='
find bin/classes -name '*.class' | sort > bin/d8-inputs.txt
"$BT/d8" --release --min-api 21 --lib "$AJAR" --output bin/ @bin/d8-inputs.txt
echo '=== aapt ==='
"$BT/aapt" package -f -M src/AndroidManifest.xml -S res -A assets -I "$AJAR" -F dist/unsigned.apk
echo '=== dex ==='
cp bin/classes.dex dist/
(cd dist && zip -q -j unsigned.apk classes.dex)
echo '=== zipalign ==='
"$BT/zipalign" -f 4 dist/unsigned.apk dist/aligned.apk
echo '=== sign ==='
KEYSTORE="$HOME/.android/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then keytool -genkeypair -keystore "$KEYSTORE" -storepass android -alias androiddebugkey -keypass android -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1; fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --ks-key-alias androiddebugkey --v2-signing-enabled true --v3-signing-enabled true --min-sdk-version 21 --out dist/yuedu.apk dist/aligned.apk
"$BT/apksigner" verify --min-sdk-version 21 dist/yuedu.apk
ls -lh dist/yuedu.apk
