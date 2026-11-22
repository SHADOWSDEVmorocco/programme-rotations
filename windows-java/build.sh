#!/usr/bin/env bash
# Construction de ProgrammeRotations.jar (+ l'exécutable Windows si mingw est installé).
#   Pré-requis : JDK 17+ ; pour le .exe : gcc-mingw-w64-x86-64 ; runtime Java Windows dans runtime-win/
set -euo pipefail
cd "$(dirname "$0")"
VERSION=2.7.0
WEB=${WEB_SRC:-../firebase_rotations/public}

rm -rf build && mkdir -p build/classes build/jar
javac --release 17 -encoding UTF-8 -nowarn -cp lib/flatlaf.jar -d build/classes src/ma/rotations/*.java
cp -r resources/. build/classes/
mkdir -p build/classes/web && cp -r "$WEB"/. build/classes/web/
sed -i 's|^export const firebaseConfig = .*|export const firebaseConfig = null;|' build/classes/web/js/firebase-config.js
(cd build/classes && unzip -qo ../../lib/flatlaf.jar -x 'META-INF/MANIFEST.MF' 'META-INF/*.SF' 'META-INF/*.RSA')
printf "Main-Class: ma.rotations.Main\nImplementation-Version: %s\nEnable-Native-Access: ALL-UNNAMED\n" "$VERSION" > build/manifest.txt
jar --create --file build/jar/ProgrammeRotations.jar --manifest build/manifest.txt -C build/classes .
echo "JAR : build/jar/ProgrammeRotations.jar ($(du -h build/jar/ProgrammeRotations.jar | cut -f1))"

if command -v x86_64-w64-mingw32-gcc >/dev/null && [ -d runtime-win ]; then
  OUT=build/ProgrammeRotations
  mkdir -p "$OUT/app"
  x86_64-w64-mingw32-windres launcher/launcher.rc -O coff -o build/launcher.res
  x86_64-w64-mingw32-gcc -O2 -municode -mwindows -o "$OUT/ProgrammeRotations.exe" launcher/launcher.c build/launcher.res -lshlwapi
  x86_64-w64-mingw32-strip "$OUT/ProgrammeRotations.exe"
  cp build/jar/ProgrammeRotations.jar "$OUT/app/"
  x86_64-w64-mingw32-gcc -O2 -municode -mwindows -o "$OUT/app/envoyer_mail.exe" launcher/envoyer_mail.c -lshlwapi -lshell32
  x86_64-w64-mingw32-strip "$OUT/app/envoyer_mail.exe"
  cp -r runtime-win "$OUT/runtime"
  cp LISEZMOI.txt "$OUT/" 2>/dev/null || true
  (cd build && rm -f ProgrammeRotations-Windows.zip && zip -qr9 ProgrammeRotations-Windows.zip ProgrammeRotations)
  echo "Windows : build/ProgrammeRotations-Windows.zip ($(du -h build/ProgrammeRotations-Windows.zip | cut -f1))"
fi
