#!/bin/sh
# Lancement de GX Studio (interface) ou commandes : gxstudio.sh new|build|run ...
#   JAVAFX : dossier lib de JavaFX (defaut : /usr/share/openjfx/lib, paquet openjfx)
#   JNI    : bibliotheques natives de JavaFX du paquet openjfx (libglass.so, libprism_*.so)
#            (inutile avec le SDK JavaFX de Gluon, qui les contient dans son dossier lib)
D="$(cd "$(dirname "$0")" && pwd)"
JAVAFX=${JAVAFX:-/usr/share/openjfx/lib}
JNI=${JNI:-/usr/lib/x86_64-linux-gnu/jni}
export GXSTUDIO_HOME="${GXSTUDIO_HOME:-$D}"
LIBPATH="$JAVAFX"
[ -d "$JNI" ] && LIBPATH="$JAVAFX:$JNI"
exec java -Djava.library.path="$LIBPATH" --module-path "$JAVAFX" --add-modules javafx.controls,javafx.swing \
  -cp "$D/out/gxstudio.jar:$D/libs/richtextfx-fat.jar" fr.gxstudio.Main "$@"
