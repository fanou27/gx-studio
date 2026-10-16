;=============================================================================
;  {{NAME}} - projet GX Studio en C pour {{TARGET}} (disquette DSK)
;
;  Le jeu est ecrit en C (src/main.c, et autres fichiers .c de src/) ; ce
;  fichier assemble le programme : demarrage, bibliotheque GX Studio (lib/),
;  liaison C <-> assembleur (lib/c/gx_c.asm), code C traduit (build/c_code.asm).
;  Sur le CPC : RUN"{{SHORT}}
;=============================================================================
        include "../build/config.inc"
RAM_START       equ #A700               ; variables (apres l'arret du firmware)
STACK_TOP       equ #C000
SW_SLOTS        equ 4                   ; sprites logiciels simultanes
SW_BUF          equ 128                 ; decor sauvegarde par sprite (16x16 mode 0)

        include "../lib/cpc/hw_cpc.inc"
        include "../build/c_vars.inc"   ; C_RAM_SIZE : variables du C (genere)

;--------------------------------------------------------------- variables ---
vptr=RAM_START
macro VAR name,size
{name} equ vptr
vptr=vptr+{size}
mend
 VAR frame_flag,1                       ; variables de la bibliotheque
 VAR frame_count,1
 VAR lib_tmp,2
 VAR joy,1
 VAR joy_new,1
 VAR txt0_tbl,4
 VAR snd_ch,3*10
 VAR sw_tab,SW_SLOTS*12
 VAR sw_bufs,SW_SLOTS*SW_BUF
 VAR tile_vars,12
 VAR scroll_vars,16
 VAR c_ram,C_RAM_SIZE                   ; variables du C
        assert vptr<=STACK_TOP-256

        org #0400                       ; chargement et lancement par RUN"{{SHORT}}
        include "../lib/cpc/boot_cpc.asm"
        include "../lib/cpc/swsprites.asm"
        include "../lib/common/text0.asm"
        include "../lib/common/sound_sw.asm"
        include "../lib/common/input.asm"
        include "../lib/common/tiles.asm"
        include "../lib/common/anim.asm"
txt_ink equ set_ink0
txt_at  equ text0_at
        include "../lib/c/gx_c.asm"     ; liaison C <-> bibliotheque

main:
        call c_init                     ; variables du C
        call _main                      ; main() de src/main.c
        jr main

FONT:   incbin "../build/font.bin"
        include "../build/images.inc"
        include "../build/gen.asm"
        include "../build/c_code.asm"   ; le C, traduit pour rasm
code_end:
        assert code_end<=#A700          ; le firmware (AMSDOS) occupe le haut de la memoire
        assert SPR_MAX_BYTES<=SW_BUF

        include "../build/save.inc"     ; ecriture sur la disquette
