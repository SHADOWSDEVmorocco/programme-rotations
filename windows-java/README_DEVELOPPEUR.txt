PROGRAMME DES ROTATIONS – VERSION JAVA : SOURCES
================================================

Structure
  src/ma/rotations/
    Core.java          modèle + calculs (identique au logiciel Python, à la tablette et au site)
    SemaineType.java   données « Dakhla Air Connect – semaine type » (générées)
    Json.java          lecture / écriture JSON (sans bibliothèque)
    Storage.java       enregistrement atomique + copies de sécurité (%APPDATA%\ProgrammeRotations)
    Pdf.java           tableau imprimable A4 (sans bibliothèque)
    SyncServer.java    synchronisation Wi-Fi avec la tablette Android (HttpServer du JDK)
    Sharing.java       envoi e-mail (Outlook) / WhatsApp
    MainWindow.java, AircraftPanel.java, Dialogs.java, Icons.java, Main.java   interface Swing
  resources/           icônes, métriques des polices PDF
  launcher/            ProgrammeRotations.exe (lanceur Windows en C) + icône
  lib/flatlaf.jar      apparence moderne (FlatLaf 3.5.4, licence Apache 2.0)
  test/                TestCore (62 vérifications), CrossCheck (comparaison avec la version Python)

Construction (Linux)
  apt install openjdk-17-jdk gcc-mingw-w64-x86-64 zip
  Java pour Windows (dossier runtime-win) : n'importe quel JRE/JDK Windows x64 17+ comprenant
  java.desktop et jdk.httpserver, par exemple :
    pip download --no-deps --only-binary=:all: --platform win_amd64 jdk4py
    puis extraire jdk4py/java-runtime vers runtime-win/
  ./build.sh   →  build/jar/ProgrammeRotations.jar  et  build/ProgrammeRotations-Windows.zip

Tests
  javac --release 17 -cp build/jar/ProgrammeRotations.jar -d build/test test/ma/rotations/*.java
  java -cp build/jar/ProgrammeRotations.jar:build/test ma.rotations.TestCore
