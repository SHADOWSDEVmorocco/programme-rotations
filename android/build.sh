#!/usr/bin/env bash
# Construction de l'APK sans Android Studio (outils Debian/Ubuntu) :
#   apt install android-sdk-build-tools android-sdk-platform-23 dalvik-exchange apksigner zipalign openjdk-17-jdk
# Usage : ./build.sh            (l'interface web est copiée depuis ../firebase_rotations/public)
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_SDK:-/usr/lib/android-sdk}
AJ=$SDK/platforms/android-23/android.jar
WEB=${WEB_SRC:-../firebase_rotations/public}
VERSION_NAME=2.1.0
VERSION_CODE=23

[ -f keystore.properties ] || { echo "keystore.properties manquant (voir LISEZMOI)"; exit 1; }
source keystore.properties

rm -rf build assets && mkdir -p build/gen build/obj assets
cp -r "$WEB" assets/www
# la tablette fonctionne toujours en mode Wi-Fi avec le PC (jamais Firebase)
sed -i 's|^export const firebaseConfig = .*|export const firebaseConfig = null;|' assets/www/js/firebase-config.js

aapt2 compile --dir res -o build/res.zip
aapt2 link -o build/app.unsigned.apk -I "$AJ" --manifest AndroidManifest.xml --java build/gen \
  -A assets build/res.zip --min-sdk-version 21 --target-sdk-version 34 \
  --version-code $VERSION_CODE --version-name $VERSION_NAME
javac -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$AJ" -classpath "$AJ" -d build/obj \
  $(find src build/gen -name '*.java')
dalvik-exchange --dex --min-sdk-version=21 --output=build/classes.dex build/obj
(cd build && zip -q app.unsigned.apk classes.dex)
zipalign -f -p 4 build/app.unsigned.apk build/app.aligned.apk
apksigner sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" --ks-pass "pass:$STORE_PASS" --key-pass "pass:$KEY_PASS" \
  --out "Rotations-$VERSION_NAME.apk" build/app.aligned.apk
apksigner verify --verbose "Rotations-$VERSION_NAME.apk" | head -5
echo "APK prêt : $(pwd)/Rotations-$VERSION_NAME.apk"
