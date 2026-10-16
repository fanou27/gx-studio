;=============================================================================
;  {{NAME}} - projet GX Studio en C pour {{TARGET}} (cartouche CPR)
;
;  Le jeu est ecrit en C (src/main.c, et autres fichiers .c de src/) ; ce
;  fichier assemble la cartouche : demarrage, bibliotheque GX Studio (lib/),
;  liaison C <-> assembleur (lib/c/gx_c.asm), code C traduit (build/c_code.asm).
;  On peut aussi y ajouter des routines en assembleur, appelables du C
;  (voir la documentation : "Programmer en C").
;  Pages de la cartouche : 0 = code, 1 = images des sprites, 2 = police/images
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
