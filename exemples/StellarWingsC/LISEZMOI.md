# STELLAR WINGS C

Shoot'em up pour GX4000 / CPC+ écrit en C (SDCC) et en assembleur Z80 :
projet **GX Studio 0.5.1** (cible GX4000). Même jeu que STELLAR WINGS en assembleur.

- `build/stellarw.cpr` : la cartouche (après F5), à copier sur la PicoGX.
- GX Studio 0.5.1 : Fichier > Ouvrir un projet > `projet.gxs`, puis F5 (SDCC requis : `sudo apt install sdcc`).
- `src/` : le jeu en C (`main.c`, `play.c`, `aliens.c`, `fx.c`, `stellar.h`) et deux routines
  en assembleur (`stars.asm`, `bands.asm`) ; `src/main.asm` assemble la cartouche.
- `assets/` : sprites, palettes, sons, logo (éditables dans le studio).
- `doc/Stellar_Wings_C_documentation.pdf` : documentation complète du code.
- `doc/Stellar_Wings_C_tutoriel.pdf` : tutoriel pas à pas ; `tutoriel/etape1..3` : les projets des étapes.
- Mise au point (Projet > Propriétés) : `START_WAVE=4`, `GOD_MODE=1`,
  `STARS_EN_C=1 BANDES_EN_C=1` (versions C des deux routines, pour comparer).
