;=============================================================================
;  Demo Scroll 8 CPC - projet GX Studio pour CPC 464/664/6128 (disquette DSK)
;
;  DEMO DEFILEMENT 8 DIRECTIONS (CPC) : un monde de 48 x 64 tuiles de 8 x 8
;  pixels mode 0 (384 x 512 pixels) parcouru par un vaisseau (sprite
;  logiciel) dirige a la manette ou aux fleches. Defilement materiel du CRTC
;  par caractere (4 pixels mode 0) et par rangee (8 lignes) :
;  lib/common/scroll8.asm. Murs, haies et eau arretent le vaisseau.
;  Sur le CPC : RUN"DEMOSCRO
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
 VAR ship_x,2                           ; vaisseau dans le monde : X (pixels mode 2), Y (lignes)
 VAR ship_y,2
 VAR cam_x,2                            ; camera demandee
 VAR cam_y,2
 VAR tile_vars,12                       ; pour lib/common/tiles.asm
 VAR scroll_vars,40                     ; pour lib/common/scroll8.asm (et les sprites logiciels)
        assert vptr<=STACK_TOP-256

SHIP_SLOT       equ 0                   ; emplacement du sprite logiciel
SHIP_W          equ 64                  ; 16 pixels mode 0 = 64 pixels mode 2
SHIP_H          equ 16
SPEED_X         equ 8                   ; pixels mode 2 par image (1 octet)
SPEED_Y         equ 2                   ; lignes par image

        org #0400                       ; chargement et lancement par RUN"DEMOSCRO
        include "../lib/cpc/boot_cpc.asm"
        include "../lib/cpc/swsprites.asm"
        include "../lib/common/sound_sw.asm"
        include "../lib/common/input.asm"
        include "../lib/common/tiles.asm"   ; decors (editeur de tuiles et de cartes)
        include "../lib/common/scroll8.asm" ; defilement 8 directions (a la place de scroll.asm)

main:
        ld hl,pal_screen
        call set_palette
        call sw_init
        ld hl,TILES_MONDE
        ld bc,TILES_MONDE_W*256+TILES_MONDE_H
        call tiles_use
        ld hl,MAP_MONDE
        ld d,MAP_MONDE_W
        ld e,MAP_MONDE_H
        call scroll8_init               ; ecran de 24 rangees
        ld hl,(MAP_MONDE_W/3)*32        ; le vaisseau sur le chemin, au milieu
        ld (ship_x),hl
        ld hl,(MAP_MONDE_H/2)*8
        ld (ship_y),hl
        call cam_calc                   ; camera de depart
        call scroll8_jump
        ld a,MUSIQUE_CH
        ld hl,snd_lists+MUSIQUE_OFS
        ld b,MUSIQUE_SPEED
        call snd_play
loop:
        call wait_frame
        call sw_restore_all             ; efface le sprite avant de deplacer l'ecran
        call scroll8_apply
        call sw_update                  ; redessine le sprite
        call read_joy
        call move_ship
        call cam_calc
        call scroll8_to                 ; la camera suit (par caractere / rangee)
        ; sprite : position a l'ecran = monde - camera affichee
        ld hl,(ship_x)
        ld de,(s8_camx)
        or a
        sbc hl,de
        srl h                           ; en octets (8 pixels mode 2)
        rr l
        srl h
        rr l
        srl h
        rr l
        ld b,l
        ld hl,(ship_y)
        ld de,(s8_camy)
        or a
        sbc hl,de
        ld c,l
        ld d,F_VAISSEAU
        ld a,SHIP_SLOT
        call sw_set
        jr loop

; cam_calc : HL = X, DE = Y de la camera qui centre le vaisseau
cam_calc:
        ld hl,(ship_x)
        ld de,SHIP_W/2-320
        add hl,de
        bit 7,h
        jr z,ca_x
        ld hl,0
ca_x:   ld (cam_x),hl
        ld hl,(ship_y)
        ld de,SHIP_H/2-96
        add hl,de
        bit 7,h
        jr z,ca_y
        ld hl,0
ca_y:   ld (cam_y),hl
        ex de,hl
        ld hl,(cam_x)
        ret

; move_ship : 8 directions ; un axe n'avance que si les deux coins du cote
; vers lequel on va restent sur des tuiles non solides
move_ship:
        ld a,(joy)
        bit J_LEFT,a
        jr z,ms_r
        ld hl,(ship_x)
        ld de,-SPEED_X
        add hl,de
        jr nc,ms_v
        ld de,8                         ; bord gauche
        call try_x
ms_r:   ld a,(joy)
        bit J_RIGHT,a
        jr z,ms_v
        ld hl,(ship_x)
        ld de,SPEED_X
        add hl,de
        ld de,SHIP_W-9                  ; bord droit
        call try_x
ms_v:   ld a,(joy)
        bit J_UP,a
        jr z,ms_d
        ld hl,(ship_y)
        ld de,-SPEED_Y
        add hl,de
        ret nc
        ld de,2                         ; bord haut
        call try_y
ms_d:   ld a,(joy)
        bit J_DOWN,a
        ret z
        ld hl,(ship_y)
        ld de,SPEED_Y
        add hl,de
        ld de,SHIP_H-3                  ; bord bas
        jp try_y

; try_x : HL = nouvel X, DE = decalage du bord teste -> X accepte si libre
try_x:
        push hl
        add hl,de
        push hl
        ld de,(ship_y)
        inc de
        inc de
        call solid
        pop hl
        jr nz,tx_no
        ld de,(ship_y)
        ld a,e
        add a,SHIP_H-3
        ld e,a
        jr nc,tx_1
        inc d
tx_1:   call solid
        jr nz,tx_no
        pop hl
        ld (ship_x),hl
        ret
tx_no:  pop hl
        ret

; try_y : HL = nouvel Y, DE = decalage du bord teste -> Y accepte si libre
try_y:
        push hl
        add hl,de
        ex de,hl
        ld hl,(ship_x)
        ld bc,8
        add hl,bc
        push de
        call solid
        pop de
        jr nz,ty_no
        ld hl,(ship_x)
        ld bc,SHIP_W-9
        add hl,bc
        call solid
        jr nz,ty_no
        pop hl
        ld (ship_y),hl
        ret
ty_no:  pop hl
        ret

; solid : HL = X (pixels mode 2), DE = Y (lignes) -> NZ si tuile solide
;   tuile de 8 pixels mode 0 = 32 pixels mode 2, 8 lignes
solid:
        ld a,h                          ; colonne = X / 32
        add a,a
        add a,a
        add a,a
        ld b,a
        ld a,l
        rlca
        rlca
        rlca
        and 7
        or b
        ld b,a
        ld a,e                          ; rangee = Y / 8
        srl d
        rra
        srl d
        rra
        srl d
        rra
        ld c,a
        ld hl,MAP_MONDE
        ld d,MAP_MONDE_W
        call map_get
        ld hl,TFLAGS_MONDE
        jp map_solid

FONT:   incbin "../build/font.bin"
        include "../build/images.inc"
        include "../build/gen.asm"
code_end:
        assert code_end<=#A700          ; le firmware (AMSDOS) occupe le haut de la memoire
        assert SPR_MAX_BYTES<=SW_BUF

        include "../build/save.inc"     ; ecriture sur la disquette
