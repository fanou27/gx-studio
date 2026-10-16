;=============================================================================
;  Demo Scroll 8 - projet GX Studio pour GX4000 (cartouche CPR)
;
;  DEMO DEFILEMENT 8 DIRECTIONS : un monde de 48 x 32 tuiles de 16 x 16
;  pixels (768 x 512, plus de 2 ecrans dans chaque sens) parcouru par un
;  vaisseau (sprite materiel) dirige a la manette dans les 8 directions.
;  La camera le suit ; le defilement est fluide au pixel pres (CRTC + soft-
;  scroll horizontal ET vertical de l'ASIC) : lib/common/scroll8.asm.
;  Les murs, les haies et l'eau arretent le vaisseau (attribut "solide").
;
;  Les ressources (assets/) sont converties par le studio dans build/ :
;    build/config.inc : cible, RAM, clavier...      build/gen.asm : tables
;    build/sprites.bin, font.bin, img_*.bin
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

;--------------------------------------------------------------- variables ---
vptr=RAM_START
macro VAR name,size
{name} equ vptr
vptr=vptr+{size}
mend
 VAR frame_flag,1
 VAR frame_count,1
 VAR pri_ptr,2
 VAR pri_pending,2
  VAR pri_arm,1                  ; ligne d'armement des tables raster (pri_detect)
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
 VAR rtab,2*80                          ; deux tables raster (double tampon)
 VAR rbuf,1
 VAR ship_x,2                           ; vaisseau dans le monde : X (pixels mode 2), Y (lignes)
 VAR ship_y,2
 VAR cam_x,2                            ; camera demandee
 VAR cam_y,2
 VAR tile_vars,12                       ; pour lib/common/tiles.asm
 VAR scroll_vars,40                     ; pour lib/common/scroll8.asm
        assert vptr<DMA_RAM

SHIP_SPR        equ 0                   ; sprite materiel du vaisseau
SHIP_W          equ 32                  ; 16 pixels zoomes x2 = 32 pixels mode 2
SHIP_H          equ 16
SPEED_X         equ 4                   ; pixels mode 2 par image
SPEED_Y         equ 2                   ; lignes par image
WORLD_W         equ MAP_MONDE_W*TILES_MONDE_W*4   ; largeur du monde (pixels mode 2)
WORLD_H         equ MAP_MONDE_H*TILES_MONDE_H     ; hauteur (lignes)

;------------------------------------------------------------------ PAGE 0 ---
        bank 0
        org #0000
        include "../lib/plus/boot.asm"
        include "../lib/plus/raster.asm"
        include "../lib/plus/sprites.asm"
        include "../lib/plus/dma.asm"
        include "../lib/common/input.asm"
        include "../lib/common/tiles.asm"   ; decors (editeur de tuiles et de cartes)
        include "../lib/common/scroll8.asm" ; defilement 8 directions (a la place de scroll.asm)
        include "../lib/plus/text1.asm"

main:
        call dma_init
        ld hl,spr_have                  ; aucune image chargee dans l'ASIC
        ld b,16
m_h:    ld (hl),#FF
        inc hl
        djnz m_h
        ld hl,pal_screen
        call set_palette4
        ld hl,pal_sprites
        call set_spr_palette
        ; --- le monde : jeu de tuiles + carte, dessin du coin de depart
        ld hl,TILES_MONDE
        ld bc,TILES_MONDE_W*256+TILES_MONDE_H
        call tiles_use
        ld hl,MAP_MONDE
        ld d,MAP_MONDE_W
        ld e,MAP_MONDE_H
        call scroll8_init               ; ecran de 24 rangees, dessine le haut gauche
        ; --- le vaisseau au milieu du monde (sur le chemin)
        ld hl,(MAP_MONDE_W/3)*32
        ld (ship_x),hl
        ld hl,(MAP_MONDE_H/2)*16
        ld (ship_y),hl
        call cam_calc                   ; camera de depart, sans defilement
        call scroll8_jump
        ld a,MUSIQUE_CH
        ld hl,DMA_RAM+MUSIQUE_OFS
        ld b,MUSIQUE_SPEED
        call dma_play
loop:
        call wait_frame
        call scroll8_apply              ; debut d'ecran (CRTC) et decalages fins (ASIC)
        xor a
        call spr_commit
        call spr_load
        call read_joy
        call move_ship
        call camera                     ; la camera suit le vaisseau
        ; sprite : position a l'ecran = position dans le monde - camera affichee
        ld hl,(ship_x)
        ld de,(s8_camx)
        or a
        sbc hl,de
        push hl
        ld hl,(ship_y)
        ld de,(s8_camy)
        or a
        sbc hl,de
        ex de,hl                        ; DE = Y a l'ecran
        pop hl                          ; HL = X a l'ecran
        ld b,F_VAISSEAU
        ld c,ZOOM_X2Y1
        ld a,SHIP_SPR
        call spr_set
        jr loop

; camera : centree sur le vaisseau (scroll8_to la borne a la carte)
camera:
        call cam_calc
        jp scroll8_to

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
        jr nc,ms_v                      ; bord du monde
        ld de,4                         ; coin gauche
        call try_x
ms_r:   ld a,(joy)
        bit J_RIGHT,a
        jr z,ms_v
        ld hl,(ship_x)
        ld de,SPEED_X
        add hl,de
        ld de,SHIP_W-5                  ; coin droit
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
        push hl                         ; nouvel X
        add hl,de                       ; X du bord
        push hl
        ld de,(ship_y)                  ; coin haut
        inc de
        inc de
        call solid
        pop hl
        jr nz,tx_no
        ld de,(ship_y)                  ; coin bas
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
        ex de,hl                        ; DE = ligne testee
        ld hl,(ship_x)
        ld bc,4
        add hl,bc
        push de
        call solid
        pop de
        jr nz,ty_no
        ld hl,(ship_x)
        ld bc,SHIP_W-5
        add hl,bc
        call solid
        jr nz,ty_no
        pop hl
        ld (ship_y),hl
        ret
ty_no:  pop hl
        ret

; solid : HL = X (pixels mode 2), DE = Y (lignes) dans le monde -> NZ si tuile solide
solid:
        ld a,h                          ; colonne de tuile = X / 32 = H x 8 + L / 32
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
        ld a,e                          ; rangee de tuile = Y / 16
        srl d
        rra
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

        include "../build/gen.asm"
code_end:
        assert code_end<=#4000

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
