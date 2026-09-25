#!/bin/sh
# Builds PhotoVault.apk without Gradle: aapt + javac + dx + zipalign + apksigner (Debian/Ubuntu packages:
# aapt apksigner dalvik-exchange zipalign openjdk-21-jdk-headless) and an API-34 android.jar in sdk/.
set -e
cd "$(dirname "$0")"
JAR=sdk/android-34.jar
[ -f "$JAR" ] || curl -sL -o "$JAR" https://raw.githubusercontent.com/Reginer/aosp-android-jar/main/android-34/android.jar
rm -rf build && mkdir -p build/classes
aapt package -f -0 arsc -M AndroidManifest.xml -S res -I "$JAR" -F build/res.apk
javac -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$JAR" -d build/classes $(find src -name '*.java')
dalvik-exchange --dex --min-sdk-version=26 --output=build/classes.dex build/classes
cp build/res.apk build/unsigned.apk
(cd build && zip -q unsigned.apk classes.dex)
zipalign -f -p 4 build/unsigned.apk build/aligned.apk
[ -f photovault.jks ] || keytool -genkeypair -keystore photovault.jks -storepass photovault -keypass photovault \
    -alias photovault -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=PhotoVault"
apksigner sign --v2-signing-enabled true --v3-signing-enabled true --ks photovault.jks --ks-pass pass:photovault --ks-key-alias photovault --out PhotoVault.apk build/aligned.apk
apksigner verify --print-certs PhotoVault.apk | head -3
echo "OK: $(ls -la PhotoVault.apk)"
