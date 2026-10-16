#!/usr/bin/env bash
#=============================================================================
#  GX Studio - construire-outils.sh
#  (c) 2026 ALONSO Stephane [Fanou]
#
#  Recupere rasm et Caprice32 dans leurs depots git, applique a Caprice32 le
#  correctif GX Studio (caprice32/gxstudio-caprice32.patch) et compile les deux.
#
#  Linux, macOS, et Windows dans MSYS2 (MINGW64) : sous Windows, lancer plutot
#  construire-outils.bat, qui prepare MSYS2 et appelle ce script.
#
#  Usage : ./construire-outils.sh [options] [DOSSIER]
#    DOSSIER      ou ranger les sources et les programmes (defaut : ./outils)
#    -d           installer d'abord les paquets necessaires (demande sudo)
#    -p FICHIER   correctif de Caprice32 (defaut : caprice32/gxstudio-caprice32.patch
#                 a cote de ce script)
#    -r VERSION   version de rasm : un commit, une etiquette, ou "dernier"
#                 (defaut : d006078, la version testee avec GX Studio)
#    -j N         nombre de compilations en parallele (defaut : nombre de processeurs)
#    -h           cette aide
#
#  Le script peut etre relance : il met a jour les depots, retire l'ancien
#  correctif, applique le nouveau et recompile.
#=============================================================================
set -euo pipefail

RASM_URL=https://github.com/EdouardBERGE/rasm.git
CAP32_URL=https://github.com/ColinPitrat/caprice32.git
RASM_REF=d006078                # RASM v3.3.2, utilise par GX Studio 0.5.1
CAP32_REF=6c12c4c               # version sur laquelle le correctif est fait

ICI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PATCH="$ICI/caprice32/gxstudio-caprice32.patch"
DEPS=0
JOBS=""

msg()  { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m%s\033[0m\n' "$*"; }
err()  { printf '\033[1;31mERREUR : %s\033[0m\n' "$*" >&2; exit 1; }
aide() { sed -n '3,/^#====/{/^#====/d; s/^#  //; s/^#$//; p;}' "$0"; exit 0; }

while getopts "dp:r:j:h" o; do
  case $o in
    d) DEPS=1 ;;
    p) PATCH="$(cd "$(dirname "$OPTARG")" && pwd)/$(basename "$OPTARG")" ;;
    r) RASM_REF="$OPTARG" ;;
    j) JOBS="$OPTARG" ;;
    h|*) aide ;;
  esac
done
shift $((OPTIND - 1))

#---------------------------------------------------------------- systeme ---
case "$(uname -s)" in
  Linux*)               SYS=linux;  ARCH=linux; EXE="" ;;
  Darwin*)              SYS=macos;  ARCH=macos; EXE="" ;;
  MINGW64*)             SYS=msys2;  ARCH=win64; EXE=".exe" ;;
  MINGW32*)             SYS=msys2;  ARCH=win32; EXE=".exe" ;;
  MSYS*|UCRT64*|CLANG*) err "lancer ce script dans le terminal MSYS2 MINGW64 (ou utiliser construire-outils.bat)" ;;
  *)                    err "systeme non reconnu : $(uname -s)" ;;
esac
# dossier : argument, sinon variable GX_DEST (utilisee par construire-outils.bat), sinon ./outils
DEST="${1:-${GX_DEST:-$PWD/outils}}"
if [ $SYS = msys2 ] && [[ "$DEST" == *:* || "$DEST" == *\\* ]]; then
  DEST="$(cygpath -u "$DEST")"                   # chemin Windows (C:\...) -> /c/...
fi
mkdir -p "$DEST"
DEST="$(cd "$DEST" && pwd)"
if [ -z "$JOBS" ]; then
  JOBS=$( (nproc || sysctl -n hw.ncpu || echo 2) 2>/dev/null | head -1)
fi
[ -f "$PATCH" ] || err "correctif introuvable : $PATCH (option -p)"

#------------------------------------------------------------ dependances ---
installer_paquets() {
  msg "Installation des paquets"
  if [ $SYS = msys2 ]; then
    local p=mingw-w64-x86_64; [ $ARCH = win32 ] && p=mingw-w64-i686
    pacman -S --needed --noconfirm git make $p-gcc $p-pkgconf $p-SDL2 $p-freetype $p-libpng $p-zlib
  elif [ $SYS = macos ]; then
    command -v brew >/dev/null || err "Homebrew est necessaire : https://brew.sh"
    brew install git make pkg-config sdl2 freetype libpng zlib
  elif command -v apt-get >/dev/null; then
    sudo apt-get update
    sudo apt-get install -y git build-essential pkg-config libsdl2-dev libfreetype-dev libpng-dev zlib1g-dev
  elif command -v dnf >/dev/null; then
    sudo dnf install -y git make gcc gcc-c++ pkgconf-pkg-config SDL2-devel freetype-devel libpng-devel zlib-devel
  elif command -v pacman >/dev/null; then
    sudo pacman -S --needed --noconfirm git base-devel pkgconf sdl2 freetype2 libpng zlib
  elif command -v zypper >/dev/null; then
    sudo zypper install -y git make gcc gcc-c++ pkg-config libSDL2-devel freetype2-devel libpng16-devel zlib-devel
  else
    err "gestionnaire de paquets inconnu : installer git, make, gcc, g++, pkg-config, SDL2, freetype, libpng et zlib (versions de developpement)"
  fi
}
[ $DEPS = 1 ] && installer_paquets

msg "Verification des outils"
manque=""
for c in git make gcc g++ pkg-config sdl2-config; do
  command -v $c >/dev/null || manque="$manque $c"
done
for lib in freetype2 libpng zlib; do
  pkg-config --exists $lib 2>/dev/null || manque="$manque $lib"
done
[ -z "$manque" ] || err "il manque :$manque
  -> relancer avec l'option -d pour installer les paquets necessaires"
ok "git, make, gcc, g++, pkg-config, SDL2, freetype, libpng, zlib : presents"

#----------------------------------------------------------------- depots ---
# depot URL DOSSIER VERSION : clone ou met a jour, puis se place sur VERSION
depot() {
  local url=$1 dir=$2 ref=$3
  if [ -d "$dir/.git" ]; then
    git -C "$dir" fetch --tags --quiet origin
  else
    git clone --quiet "$url" "$dir"
  fi
  if [ "$ref" = dernier ]; then
    ref=$(git -C "$dir" symbolic-ref --short refs/remotes/origin/HEAD)     # origin/master
  fi
  git -C "$dir" checkout --quiet --force --detach "$ref"
  git -C "$dir" clean -fdq -e obj -e '*.o'      # retire les fichiers ajoutes (ancien correctif)
  echo "$(basename "$dir") : $(git -C "$dir" log -1 --format='%h %ad %s' --date=short)"
}

msg "rasm : recuperation ($RASM_REF)"
depot "$RASM_URL" "$DEST/rasm" "$RASM_REF"

msg "Caprice32 : recuperation ($CAP32_REF)"
depot "$CAP32_URL" "$DEST/caprice32" "$CAP32_REF"

msg "Caprice32 : application du correctif GX Studio"
echo "$PATCH"
git -C "$DEST/caprice32" apply --check "$PATCH" || err "le correctif ne s'applique pas sur Caprice32 $CAP32_REF"
git -C "$DEST/caprice32" apply "$PATCH"
git -C "$DEST/caprice32" status --short src

#------------------------------------------------------------ compilation ---
msg "rasm : compilation"
if [ $SYS = macos ]; then
  RMK=(-f makefile.MacOS); RASM="$DEST/rasm/rasm"
elif [ $SYS = msys2 ]; then                      # Windows : rasm utilise Winsock (-lws2_32)
  RMK=(CC=gcc "CFLAGS=-lm -march=native -o rasm.exe -lws2_32"); RASM="$DEST/rasm/rasm.exe"
else
  RMK=(CC=gcc); RASM="$DEST/rasm/rasm.exe"       # nom donne par le makefile de rasm, sous Linux aussi
fi
make -C "$DEST/rasm" "${RMK[@]}" prod >"$DEST/rasm.log" 2>&1 || { tail -30 "$DEST/rasm.log"; err "la compilation de rasm a echoue (journal : $DEST/rasm.log)"; }
[ -x "$RASM" ] || err "$(basename "$RASM") absent apres la compilation"
if [ $SYS = linux ]; then cp -f "$RASM" "$DEST/rasm/rasm"; RASM="$DEST/rasm/rasm"; fi
ok "$("$RASM" 2>&1 | head -1 | sed 's/\x1b\[[0-9;]*m//g')"

msg "Caprice32 : compilation (ARCH=$ARCH, $JOBS en parallele : quelques minutes)"
make -C "$DEST/caprice32" -j"$JOBS" ARCH=$ARCH >"$DEST/caprice32.log" 2>&1 || { tail -30 "$DEST/caprice32.log"; err "la compilation de Caprice32 a echoue (journal : $DEST/caprice32.log)"; }
CAP="$DEST/caprice32/cap32$EXE"
[ -x "$CAP" ] || err "cap32$EXE absent apres la compilation"
if [ $SYS = msys2 ]; then                        # Windows : les DLL a cote de cap32.exe
  mingw=/mingw64; [ $ARCH = win32 ] && mingw=/mingw32
  ldd "$CAP" | awk -v m="$mingw/bin/" 'index($3, m) == 1 { print $3 }' | sort -u | while read -r dll; do
    cp -f "$dll" "$DEST/caprice32/"
  done
  ok "DLL copiees a cote de cap32.exe"
fi
grep -q "gxdebug" "$DEST/caprice32/src/cap32.cpp" || err "le correctif n'est pas dans le Caprice32 compile"
ok "Caprice32 compile avec le correctif GX Studio"

#---------------------------------------------------------------- resume ---
if [ $SYS = msys2 ]; then
  RASM_W=$(cygpath -w "$RASM"); CAP_W=$(cygpath -w "$CAP")
else
  RASM_W=$RASM; CAP_W=$CAP
fi
msg "Termine"
cat <<FIN
Dans GX Studio : Outils > Reglages
   rasm      : $RASM_W
   Caprice32 : $CAP_W
Caprice32 doit rester dans son dossier (il y lit cap32.cfg et rom/).
Journaux de compilation : $DEST/rasm.log, $DEST/caprice32.log
FIN
