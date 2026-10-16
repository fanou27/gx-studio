;=============================================================================
;  Demo Decor - projet GX Studio pour CPC 464/664/6128 (disquette DSK)
;
;  Point de depart : un vaisseau (sprite logiciel, mode 0) dirige a la manette
;  ou au clavier, qui tire sur un ennemi rebondissant. Musique et bruitages
;  par le lecteur logiciel (memes listes que le DMA des CPC+).
;  Sur le CPC : RUN"DEMODECO
;
;  Les ressources (assets/) sont converties par le studio dans build/ :
;    build/config.inc, build/gen.asm (palette, sprites, sons...), build/save.inc
;=============================================================================
        include "../build/config.inc"
RAM_START       equ #A700               ; variables (apres l'arret du firmware)
STACK_TOP       equ #C000
SW_SLOTS        equ 4                   ; sprites logiciels simultanes
SW_BUF          equ 128                 ; decor sauvegarde par sprite (16x16 mode 0)

        include "../lib/cpc/hw_cpc.inc"

;--------------------------------------------------------------- variables ---
vptr=RAM_START
macro VAR name,size
{name} equ vptr
vptr=vptr+{size}
mend
 VAR frame_flag,1
 VAR frame_count,1
 VAR lib_tmp,2
 VAR joy,1
 VAR joy_new,1
 VAR txt0_tbl,4
 VAR snd_ch,3*10
 VAR sw_tab,SW_SLOTS*12
 VAR sw_bufs,SW_SLOTS*SW_BUF
 VAR px,1                               ; personnage : X (octets), Y (lignes)
 VAR py,1
 VAR pdx,1
 VAR pvy,1
 VAR anim_state,5                       ; etat de l'animation de marche
 VAR tile_vars,12                       ; pour lib/common/tiles.asm
        assert vptr<=STACK_TOP-256

SHIP_SLOT       equ 0                   ; emplacements des sprites logiciels
SHOT_SLOT       equ 1
FOE_SLOT        equ 2

        org #0400                       ; chargement et lancement par RUN"DEMODECO
        include "../lib/cpc/boot_cpc.asm"
        include "../lib/cpc/swsprites.asm"
        include "../lib/common/text0.asm"
        include "../lib/common/sound_sw.asm"
        include "../lib/common/input.asm"
        include "../lib/common/tiles.asm"   ; decors (editeur de tuiles et de cartes)
        include "../lib/common/anim.asm"    ; animations de sprites

main:
;=============================================================================
;  DEMO DECOR (CPC) : carte de tuiles mode 0, personnage anime en sprite
;  logiciel, gravite et collisions avec les tuiles solides.
;=============================================================================
        ld hl,pal_screen
        call set_palette
        call sw_init
        ld hl,TILES_DECOR
        ld bc,TILES_DECOR_W*256+TILES_DECOR_H
        call tiles_use
        xor a
        ld (map_x0),a
        ld (map_y0),a
        ld hl,MAP_NIVEAU1
        ld d,MAP_NIVEAU1_W
        ld bc,MAP_NIVEAU1_W*256+MAP_NIVEAU1_H
        call map_draw
        ld a,10
        ld (px),a
        ld a,20
        ld (py),a
        ld a,1
        ld (pdx),a
        xor a
        ld (pvy),a
        ld ix,anim_state
        ld hl,ANIM_MARCHE
        call anim_start
loop:
        call wait_frame
        call sw_update                  ; efface / redessine les sprites logiciels
        call read_joy
        call walk
        call gravity
        ld ix,anim_state
        call anim_step
        ld d,a                          ; image
        ld a,(px)
        ld b,a
        ld a,(py)
        ld c,a
        xor a                           ; emplacement 0
        call sw_set
        jr loop

walk:   ld a,(pdx)
        ld b,a
        ld a,(px)
        add a,b
        ld (px),a
        cp 1
        jr c,w_turn
        cp 80-8
        ret c
w_turn: ld a,(pdx)
        neg
        ld (pdx),a
        ret

gravity:
        call ground
        jr z,g_fall
        ld a,(pvy)
        bit 7,a
        jr nz,g_move
        xor a
        ld (pvy),a
        ld a,(py)
        and #F8
        ld (py),a
        ld a,(joy_new)
        and (1<<J_FIRE1)|(1<<J_FIRE2)
        ret z
        ld a,-7
        ld (pvy),a
        jr g_move
g_fall: ld a,(pvy)
        cp 4
        jr z,g_move
        inc a
        ld (pvy),a
g_move: ld a,(pvy)
        ld b,a
        ld a,(py)
        add a,b
        cp 180
        jr c,g_ok
        ld a,20
g_ok:   ld (py),a
        ret

; ground : tuile sous les pieds (colonne = (X+4)/4 octets, rangee = (Y+16)/8)
ground:
        ld a,(px)
        add a,4
        rrca
        rrca
        and 63
        ld b,a
        ld a,(py)
        add a,16
        rrca
        rrca
        rrca
        and 31
        ld c,a
        ld hl,MAP_NIVEAU1
        ld d,MAP_NIVEAU1_W
        call map_get
        ld hl,TFLAGS_DECOR
        jp map_solid

s_title: db "DEMO DECOR",0
s_press: db "APPUIE SUR TIR",0

FONT:   incbin "../build/font.bin"
        include "../build/images.inc"
        include "../build/gen.asm"
code_end:
        assert code_end<=#A700          ; le firmware (AMSDOS) occupe le haut de la memoire
        assert SPR_MAX_BYTES<=SW_BUF

        include "../build/save.inc"     ; ecriture sur la disquette
