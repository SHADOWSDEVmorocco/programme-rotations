# Programme des Rotations

Planification hebdomadaire des rotations (ERJ.145, Beechcraft 1900 Cargo et Passagers) au départ de Dakhla :
propositions de villes et d'heures, remplissage automatique, contrôles (escales, chevauchements, durées de vol
anormales, même destination à la même heure pour un même type d'avion), compteur de fréquences, semaines
datées avec historique, impression PDF au format de la feuille papier, envoi WhatsApp / e-mail, et
synchronisation Wi-Fi entre le PC et la tablette.

| Dossier | Contenu |
|---|---|
| `windows-java/` | Logiciel PC en **Java** (Swing) + lanceur `ProgrammeRotations.exe` – voir `README_DEVELOPPEUR.txt` |
| `android/` | Application **tablette Android** (APK) – voir `LISEZMOI.txt` |
| `web/` | Interface commune (tablette, navigateur) et version en ligne **Firebase** – voir `LISEZMOI-DEPLOIEMENT.txt` |

## Télécharger
Les fichiers prêts à installer sont dans **Releases** : `ProgrammeRotations-Windows.zip` (PC, Java inclus) et
`Rotations-x.y.z.apk` (tablette).

## Construire
- PC : `cd windows-java && ./build.sh` (JDK 17+, `gcc-mingw-w64-x86-64` pour le .exe, Java Windows dans `runtime-win/`).
- Tablette : `cd android && ./build.sh` (outils Android de Debian/Ubuntu). La **clé de signature** (`rotations.keystore`,
  `keystore.properties`) n'est **pas** dans ce dépôt : elle est conservée à part par le propriétaire.
- Tests : `cd web && node tests/core.test.mjs` ; `windows-java` : `ma.rotations.TestCore`.
