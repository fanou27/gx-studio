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
 VAR px,2                               ; personnage : X dans le monde (pixels mode 2), Y (lignes)
 VAR py,1
 VAR pdx,1
 VAR pvy,1
 VAR anim_state,5                       ; etat de l'animation de marche
 VAR cam,2                              ; camera (pixels mode 2)
 VAR tile_vars,12                       ; pour lib/common/tiles.asm
 VAR scroll_vars,16                     ; pour lib/common/scroll.asm
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
        include "../lib/common/scroll.asm"  ; defilement

main:
;=============================================================================
;  DEMO SCROLL (CPC) : carte trois fois plus large que l'ecran, defilement
;  materiel par pas de 4 pixels mode 0 (CRTC), personnage en sprite logiciel
;  dirige a la manette (gauche / droite, TIR = saut).
;=============================================================================
        ld hl,pal_screen
        call set_palette
        call sw_init
        ld hl,TILES_DECOR
        ld bc,TILES_DECOR_W*256+TILES_DECOR_H
        call tiles_use
        ld hl,MAP_NIVEAU1
        ld d,MAP_NIVEAU1_W
        ld e,MAP_NIVEAU1_H
        call scroll_init
        ld hl,80
        ld (px),hl
        ld a,20
        ld (py),a
        xor a
        ld (pvy),a
        ld ix,anim_state
        ld hl,ANIM_MARCHE
        call anim_start
        ld a,IMPORT_MIDI_CH             ; musique importee d'un fichier MIDI (lecteur logiciel)
        ld hl,snd_lists+IMPORT_MIDI_OFS
        ld b,IMPORT_MIDI_SPEED
        call snd_play
loop:
        call wait_frame
        call sw_hide_all_now            ; efface les sprites avant de deplacer l'ecran
        call scroll_apply
        call sw_update                  ; redessine les sprites logiciels
        call read_joy
        call walk
        call gravity
        ld ix,anim_state
        call anim_step
        push af
        call camera
        ld hl,(px)                      ; X ecran (octets) = (X - camera reelle) / 8
        ld de,(scr_char)
        ex de,hl
        add hl,hl
        add hl,hl
        add hl,hl
        add hl,hl                       ; camera reelle = caractere x 16
        ex de,hl
        or a
        sbc hl,de
        srl h
        rr l
        srl h
        rr l
        srl h
        rr l
        ld b,l
        ld a,(py)
        ld c,a
        pop af
        ld d,a                          ; image
        xor a                           ; emplacement 0
        call sw_set
        jr loop

camera:
        ld hl,(px)
        ld de,-304
        add hl,de
        bit 7,h
        jr z,ca_1
        ld hl,0
ca_1:   ld de,(MAP_NIVEAU1_W*TILES_DECOR_W/2-SCR_COLS)*16
        call cmp16
        jr c,ca_2
        ex de,hl
ca_2:   ld (cam),hl
        jp scroll_to

cmp16:  push hl
        or a
        sbc hl,de
        pop hl
        ret

; sw_hide_all_now : restaure le decor sous les sprites (avant un defilement)
sw_hide_all_now:
        ld a,SW_SLOTS-1
sh_l:   push af
        call sw_slot
        ld a,(ix+5)
        or a
        call nz,sw_restore
        pop af
        dec a
        jp p,sh_l
        ret

walk:
        ld hl,(px)
        ld de,4
        ld a,(joy)
        bit J_LEFT,a
        jr z,w_r
        or a
        sbc hl,de
        jr nc,w_r
        ld hl,0
w_r:    ld a,(joy)
        bit J_RIGHT,a
        jr z,w_s
        add hl,de
        ld de,MAP_NIVEAU1_W*TILES_DECOR_W*8-64
        call cmp16
        jr c,w_s
        ex de,hl
w_s:    ld (px),hl
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
        and (1<<J_FIRE1)|(1<<J_FIRE2)|(1<<J_UP)
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
        ld hl,(px)                      ; colonne de tuile = (X + 32) / 32 (tuile = 4 octets = 32 pixels mode 2)
        ld de,32
        add hl,de
        ld a,l
        srl h
        rra
        srl h
        rra
        srl h
        rra
        srl h
        rra
        srl h
        rra
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
