#!/bin/sh
# Compilation sans Maven : JavaFX (paquet openjfx ou SDK) + RichTextFX (jar "fat")
# JAVAFX = dossier lib du SDK JavaFX (defaut : /usr/share/openjfx/lib)
# javac et jar sont pris a cote du "java" du PATH (evite un vieux javac d'une autre version)
set -e
cd "$(dirname "$0")"
JAVAFX=${JAVAFX:-/usr/share/openjfx/lib}
JBIN="$(dirname "$(readlink -f "$(command -v java)")")"
[ -x "$JBIN/javac" ] || { echo "javac introuvable a cote de $JBIN/java : installer un JDK (pas seulement un JRE)"; exit 1; }
[ -f "$JAVAFX/javafx.controls.jar" ] || { echo "JavaFX introuvable dans $JAVAFX : sudo apt install openjfx, ou JAVAFX=<SDK JavaFX>/lib"; exit 1; }
rm -rf out && mkdir -p out/classes
"$JBIN/javac" -encoding UTF-8 --release 17 -d out/classes \
  --module-path "$JAVAFX" --add-modules javafx.controls,javafx.swing \
  -cp libs/richtextfx-fat.jar $(find src/main/java -name '*.java')
cp -r src/main/resources/* out/classes/
"$JBIN/jar" --create --file out/gxstudio.jar --main-class fr.gxstudio.Main -C out/classes .
rm -rf out/classes
echo "OK : out/gxstudio.jar"
