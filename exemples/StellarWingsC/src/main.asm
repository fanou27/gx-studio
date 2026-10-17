;=============================================================================
;  STELLAR WINGS C - shoot'em up pour GX4000 / CPC+ ecrit en C (SDCC) et
;  assembleur Z80, projet GX Studio (cartouche CPR de 48 Ko)
;
;  Ce fichier assemble la cartouche ; le jeu est dans les fichiers C de src/ :
;    main.c (titre, parties), play.c (vaisseau, tirs), aliens.c (formation,
;    plongeurs, boss), fx.c (score, etoiles, explosions), stellar.h
;  et deux routines en assembleur appelees par le C :
;    stars.asm (etoiles du fond), bands.asm (bandes de sprites multiplexes)
;  Pages : 0 = code (bibliotheque + jeu), 1 = images des sprites,
;          2 = police et logo (assets/images/logo.png)
;  RAM : &8000 variables de la bibliotheque puis du C (c_ram), &A000 sons DMA,
;        pile sous &C000, ecran mode 1 en &C000.
;=============================================================================
        buildcpr

        include "../build/config.inc"
RAM_START       equ #8000               ; variables (mises a zero au demarrage)
STACK_TOP       equ #C000
DMA_RAM         equ #A000               ; copie des listes de son (le DMA lit la RAM)
PAGE_SPR        equ 1
PAGE_DATA       equ 2

        include "../lib/plus/hw.inc"
        include "../build/c_vars.inc"   ; C_RAM_SIZE : variables du C (genere)

;--------------------------------------------------------------- variables ---
vptr=RAM_START
macro VAR name,size
{name} equ vptr
vptr=vptr+{size}
mend
 VAR frame_flag,1                       ; variables de la bibliotheque
 VAR frame_count,1
 VAR pri_ptr,2
 VAR pri_pending,2
 VAR pri_arm,1
 VAR cur_page,1
 VAR lib_tmp,2
 VAR dma_en,1
 VAR joy,1
 VAR joy_new,1
 VAR txt_tbl,16
 VAR txt0_tbl,4
 VAR spr_tab,16*5
 VAR spr_want,16
 VAR spr_have,16
 VAR tile_vars,12
 VAR scroll_vars,16
 VAR c_ram,C_RAM_SIZE                   ; variables du C
        assert vptr<DMA_RAM

;------------------------------------------------------------------ PAGE 0 ---
        bank 0
        org #0000
        include "../lib/plus/boot.asm"  ; au debut (vecteurs &0000 et &0038)
        include "../lib/plus/raster.asm"
        include "../lib/plus/sprites.asm"
        include "../lib/plus/dma.asm"
        include "../lib/common/input.asm"
        include "../lib/common/tiles.asm"
        include "../lib/common/anim.asm"
        if SCREEN_MODE==0               ; texte : mode 0 (20 colonnes) ou mode 1 (40)
        include "../lib/common/text0.asm"
txt_ink equ set_ink0
txt_at  equ text0_at
        else
        include "../lib/plus/text1.asm"
txt_ink equ set_ink
txt_at  equ text_at
        endif
        include "../lib/c/gx_c.asm"     ; liaison C <-> bibliotheque
        ifndef STARS_EN_C               ; (STARS_EN_C=1 : version C de fx.c, pour comparer)
        include "stars.asm"             ; etoiles du fond (appelees par le C)
        endif
        ifndef BANDES_EN_C              ; (BANDES_EN_C=1 : version C d'aliens.c)
        include "bands.asm"             ; bandes de sprites de la formation
        endif

main:
        call c_init                     ; variables du C
        call _main                      ; main() de src/main.c
        jr main

        include "../build/gen.asm"      ; ressources (assets/)
        include "../build/c_code.asm"   ; le C, traduit pour rasm
code_end:
        assert code_end<=#4000          ; la page 0 est pleine : reduire le code ou les donnees

;------------------------------------------------------------------ PAGE 1 ---
        bank 1
        org #C000
SPRITES:
        incbin "../build/sprites.bin"

;------------------------------------------------------------------ PAGE 2 ---
        bank 2
        org #C000
FONT:   incbin "../build/font.bin"
        include "../build/images.inc"
