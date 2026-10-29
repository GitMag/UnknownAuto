#!/bin/sh
# Builds unknownauto.apk with the plain SDK tools (no Gradle), like the other apps here.
#   ./build.sh           build unknownauto.apk (own key)
#   ./build.sh install   build, then adb install -r onto the phone and open it
#
# UnknownAuto is an ordinary phone app: a plain adb install is fine for it. It does
# not need the Play attribution itself — it gives that attribution to other APKs.
#
# Bundles the Shizuku API (dev.rikka.shizuku:api + :provider) so the app can drive
# the install as the shell user on-device, with no computer. Fetched to libs/ once.
set -e
cd "$(dirname "$0")"
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk}
export PATH=$JAVA_HOME/bin:$PATH
SDK=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
BT=$SDK/build-tools/35.0.0
JAR=$SDK/platforms/android-35/android.jar
APK=unknownauto.apk
MODE=${1:-build}

# --- dependencies (Shizuku API) -------------------------------------------
# api+provider are the client; aidl+shared hold the moe.shizuku.server.* stubs they need.
SHIZUKU_VER=13.1.5
mkdir -p libs
for a in api provider aidl shared; do
    if [ ! -f "libs/shizuku-$a.jar" ]; then
        echo "fetching shizuku-$a $SHIZUKU_VER"
        curl -fsSL "https://repo1.maven.org/maven2/dev/rikka/shizuku/$a/$SHIZUKU_VER/$a-$SHIZUKU_VER.aar" -o "libs/_$a.aar"
        (cd libs && unzip -oq "_$a.aar" classes.jar && mv classes.jar "shizuku-$a.jar" && rm -f "_$a.aar")
    fi
done
LIBS="libs/shizuku-api.jar:libs/shizuku-aidl.jar:libs/shizuku-shared.jar:libs/shizuku-provider.jar"

rm -rf build
mkdir -p build/classes build/dex build/gen

$BT/aapt2 link -o build/base.apk -I "$JAR" \
    --manifest AndroidManifest.xml \
    --min-sdk-version 26 --target-sdk-version 35

# AIDL -> generated Java (the Shizuku user-service interface)
$BT/aidl -I src -o build/gen src/com/mag/unknownauto/IShellService.aidl

javac --release 17 -nowarn -Xlint:-options -encoding UTF-8 -classpath "$JAR:$LIBS" \
    -d build/classes $(find src build/gen -name '*.java')
(cd build/classes && jar cf ../classes.jar .)
$BT/d8 --release --min-api 26 --lib "$JAR" --output build/dex \
    build/classes.jar libs/shizuku-api.jar libs/shizuku-aidl.jar \
    libs/shizuku-shared.jar libs/shizuku-provider.jar

cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes*.dex)
$BT/zipalign -f 4 build/unsigned.apk build/aligned.apk

[ -f keys/unknownauto.keystore ] || { mkdir -p keys; keytool -genkeypair -keystore keys/unknownauto.keystore \
    -storepass android -keypass android -alias unknownauto -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=UnknownAuto" >/dev/null 2>&1; }
$BT/apksigner -J-enable-native-access=ALL-UNNAMED sign --ks keys/unknownauto.keystore \
    --ks-pass pass:android --ks-key-alias unknownauto --out $APK build/aligned.apk
rm -f $APK.idsig
echo "built $APK"

if [ "$MODE" = "install" ]; then
    adb install -r $APK
    adb shell am start -n com.mag.unknownauto/.InstallActivity
    echo "opened UnknownAuto on the phone"
fi
