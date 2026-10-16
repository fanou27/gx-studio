GX Studio 0.5.1 - atelier de creation de jeux Amstrad CPC / CPC+ / GX4000
(c) 2026 ALONSO Stephane [Fanou]
==================================================================

Interface JavaFX (Linux), assembleur rasm, emulateur Caprice32.
Documentation complete : doc/GX_Studio_documentation.odt

Prerequis
  - Java 17 ou plus (teste avec Java 21)
  - JavaFX : paquet "openjfx" (sudo apt install openjfx) ou SDK JavaFX
  - rasm (https://github.com/EdouardBerge/rasm)
  - Caprice32 avec le correctif fourni (caprice32/LISEZMOI.txt) : fidelite
    CPC+ et debogage dans le studio (sans lui : outils de Caprice32, Maj+F2)
    rasm et Caprice32 corrige se construisent automatiquement :
      Linux / macOS : ./construire-outils.sh -d     (-d : installe les paquets)
      Windows       : construire-outils.bat         (avec MSYS2, propose de l'installer)
    Les programmes vont dans outils/ ; le script affiche les chemins a donner
    dans Outils > Reglages. ./construire-outils.sh -h pour les options.
  - SDCC 4.x (facultatif) pour les projets en C : sudo apt install sdcc
  - ffmpeg (facultatif) pour importer des MP3 / OGG / FLAC : sudo apt install ffmpeg

Compilation / lancement
  ./build.sh                      (javac + jar, sans Maven ; JAVAFX=<lib du SDK> si besoin)
  ./gxstudio.sh                   (interface)
  ou avec Maven : mvn package  puis  mvn javafx:run

Premier lancement : Outils > Reglages : chemins de rasm et de cap32.
  Caprice32 est lance depuis son propre dossier, avec ses ROM (rom/ a cote
  de l'executable). Si un projet CPC ferme Caprice32 aussitot (message
  "Couldn't open ROM file" dans la console), ajouter dans les options des
  Reglages : -O rom.rom_path=/chemin/vers/caprice32/rom

Nouveau en 0.5.1
  - Ecran de presentation : tout jeu construit avec la bibliotheque du studio
    (asm ou C, CPC, CPC+, GX4000) affiche au demarrage, pendant 3 secondes, le
    logo FANOU 1972 et "Jeux developpe avec GXStudio By Fanou"
    (lib/common/splash.asm, appele par lib/plus/boot.asm et lib/cpc/boot_cpc.asm ;
    donnees produites par tools/mksplash.py). Projets existants : recopier
    ces fichiers depuis templates/lib.
  - Boite "A propos" avec le logo de l'auteur.
  - Profil d'execution : Debogage > Mesurer les performances (Ctrl+F6) ou
    bouton "Profil 5 s" : les routines, fonctions C et lignes ou le programme
    passe son temps, et le temps libre par image (onglet Profil). Necessite
    le Caprice32 corrige 0.5.1 (commande "prof" du serveur de debogage).
    Le temps libre compte aussi les boucles d'attente du C "while (...) ;".
    Quand deux pages de cartouche occupent les memes adresses (page 0 de
    demarrage et programme recopie en RAM en &0000), le profil, la pile et le
    debogueur prennent les noms de la page de numero le plus grand.
  - Caprice32 corrige 0.5.1 : un point d'arret pouvait etre manque a une fin
    d'image ; il est maintenant teste avant l'instruction.
  - API C (lib/c/gx.h) : gx_spr_update_from, gx_raster_queue (tables raster
    en double tampon), RASTER_BAND_ENTRY et GX_BAND_SIZE (multiplexage),
    gx_blit (images), gx_clear_lines, gx_sound_tempo, GX_ASIC_PAL et
    GX_ASIC_SPR_PAL (palettes de l'ASIC), spr_want ; gx_spr_set prend un y
    signe (sprite au-dessus de l'ecran). Recopier lib/c dans les projets C.
  - Exemple exemples/StellarWingsC : STELLAR WINGS en C, avec deux routines
    en assembleur choisies grace au profil.

Nouveau en 0.5
  - Programmation en C (SDCC) melangee avec l'assembleur : Nouveau projet >
    Langage "C (SDCC) + assembleur" (ligne de commande : new ... MODE C).
    API lib/c/gx.h (sprites, sons, texte, rasters, tuiles, animations,
    manettes), ressources vues du C dans build/gen.h et build/config.h.
    Le C est traduit pour rasm et assemble avec le reste en une passe.
  - Debogage du C : points d'arret sur les lignes .c, pas a pas par ligne C,
    variables C par leur nom (meme static), fonctions C dans la pile.
  - Outils > Reglages : chemin de sdcc (par defaut celui du PATH).
  - Exemple exemples/CasseBriqueC : le casse-brique du tutoriel en C.
  - Documentation : chapitre 6 "Programmer en C" ; tutoriel : chapitre 12.

Nouveau en 0.4.2
  - Sprites materiels plus hauts que 16 lignes (16 pixels de large au plus) :
    decoupes en images 16x16 empilees (F_NOM = la premiere, H_NOM = leur
    nombre, NB_HWIMG = total) ; plus de 64 images : sprites.bin s'etend sur
    plusieurs pages (le programme les place lui-meme).
  - build/sprites_m0.bin : tous les sprites en octets mode 0 (2 pixels par
    octet, ligne par ligne), pour toutes les cibles (dessin logiciel, decors) ;
    M0_NOM = position de chaque sprite dans le fichier.
  - Bibliotheque CPC+ / GX4000 : le demarrage mesure si l'interruption raster
    (PRI) compare 8 ou 9 bits du numero de ligne (pri_detect) : les tables
    raster tournent a 50 images/s sur la console comme sur tous les
    emulateurs. Nouvelle variable a declarer : VAR pri_arm,1 (deja dans les
    exemples et le modele). Pour un ancien projet : recopier lib/plus/boot.asm
    et lib/plus/raster.asm depuis templates/lib/plus et ajouter cette variable.
  - Caprice32 corrige (0.4.2) : son DMA juste (LOOP, STOP, registres) et
    option CAP32_PRI_9BITS=1 pour essayer le mode 9 bits.

Nouveau en 0.4
  - Debogage dans le studio : F5 relie le studio a Caprice32 ; ligne courante
    surlignee, F6 pause, F8 continuer, F10/F11 pas a pas, Maj+F11 sortir,
    onglet Debogage (registres, pile, variables, memoire), points d'arret
    poses ou retires pendant l'execution.
  - Defilement 8 directions : lib/common/scroll8.asm (fluide au pixel sur
    CPC+/GX4000, par caractere et par rangee sur CPC) ; exemples
    DemoScroll8GX et DemoScroll8CPC.

Commandes sans interface
  ./gxstudio.sh new  DOSSIER "Nom" CPC|CPC_PLUS|GX4000 RAM [MODE] [C]
  ./gxstudio.sh build DOSSIER [src/main.asm:LIGNE ...]
  ./gxstudio.sh run  DOSSIER
  ./gxstudio.sh import MUSIQUE.mid|.mp3|.wav SORTIE.snd [tempo] [secondes]

Contenu
  src/        code Java (fr.gxstudio : model, gen, build, ui)
  templates/  bibliotheques Z80 (lib/plus, lib/cpc, lib/common, lib/c) et projets de depart
  exemples/   projets d exemple (CasseBrique, CasseBriqueC, decors, defilements)
  libs/       RichTextFX (jar complet) pour build.sh
  caprice32/  correctif de fidelite CPC+ pour Caprice32
  doc/        documentation
