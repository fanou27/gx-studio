;=============================================================================
;  Demo Decor - projet GX Studio pour GX4000 (cartouche CPR)
;
;  Point de depart : un vaisseau (sprite materiel) dirige a la manette, qui tire
;  sur un ennemi rebondissant. Musique et bruitages par DMA, degrade "copper"
;  par interruptions raster. Tout est a modifier !
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
 VAR px,2                               ; personnage : X (pixels mode 2), Y (lignes)
 VAR py,1
 VAR pdx,1                              ; vitesse horizontale
 VAR pvy,1                              ; vitesse verticale (signee)
 VAR anim_state,5                       ; etat de l'animation de marche
 VAR tile_vars,12                       ; pour lib/common/tiles.asm
        assert vptr<DMA_RAM


;------------------------------------------------------------------ PAGE 0 ---
        bank 0
        org #0000
        include "../lib/plus/boot.asm"
        include "../lib/plus/raster.asm"
        include "../lib/plus/sprites.asm"
        include "../lib/plus/dma.asm"
        include "../lib/common/input.asm"
        include "../lib/common/tiles.asm"   ; decors (editeur de tuiles et de cartes)
        include "../lib/common/anim.asm"    ; animations de sprites
        if SCREEN_MODE==0               ; texte : mode 0 (20 colonnes) ou mode 1 (40)
        include "../lib/common/text0.asm"
txt_ink equ set_ink0
txt_at  equ text0_at
COLS    equ 20
        else
        include "../lib/plus/text1.asm"
txt_ink equ set_ink
txt_at  equ text_at
COLS    equ 40
        endif

main:
;=============================================================================
;  DEMO DECOR : une carte de tuiles, un personnage anime soumis a la gravite
;  (collisions avec les tuiles solides), un ciel en degrade par rasters.
;=============================================================================
        call dma_init
        ld hl,spr_have
        ld b,16
m_h:    ld (hl),#FF
        inc hl
        djnz m_h
        ld hl,pal_screen
        call set_palette4
        ld hl,pal_sprites
        call set_spr_palette
        ; --- le decor : tuiles + carte (editeur de decors)
        ld hl,TILES_DECOR
        ld bc,TILES_DECOR_W*256+TILES_DECOR_H
        call tiles_use
        xor a
        ld (map_x0),a                   ; coin haut gauche de l'ecran
        ld (map_y0),a
        ld hl,MAP_NIVEAU1
        ld d,MAP_NIVEAU1_W
        ld bc,MAP_NIVEAU1_W*256+MAP_NIVEAU1_H
        call map_draw
        ; --- le ciel : table de rasters (editeur de rasters)
        ld hl,RASTER_CIEL
        call raster_start
        ; --- le personnage
        ld hl,40
        ld (px),hl
        ld a,20
        ld (py),a
        ld a,2
        ld (pdx),a
        xor a
        ld (pvy),a
        ld ix,anim_state
        ld hl,ANIM_MARCHE
        call anim_start
loop:
        call wait_frame
        xor a
        call spr_commit
        call spr_load
        call read_joy
        call walk
        call gravity
        ld ix,anim_state                ; image suivante de l'animation
        call anim_step
        ld b,a
        ld c,ZOOM_X2Y1
        ld a,0
        ld hl,(px)
        ld de,(py)
        ld d,0
        call spr_set
        jr loop

; walk : avance et fait demi-tour aux bords de l'ecran
walk:
        ld hl,(px)
        ld a,(pdx)
        ld e,a
        rla
        sbc a,a
        ld d,a
        add hl,de
        ld (px),hl
        bit 7,h
        jr nz,w_turn
        ld de,640-32
        or a
        sbc hl,de
        ret c
w_turn: ld a,(pdx)
        neg
        ld (pdx),a
        ret

; gravity : tombe tant que la tuile sous les pieds n'est pas solide ; TIR = saut
gravity:
        call ground                     ; NZ = pose sur une tuile solide
        jr z,g_fall
        ld a,(pvy)
        bit 7,a
        jr nz,g_move                    ; en train de monter
        xor a
        ld (pvy),a
        ld a,(py)                       ; recale sur le haut de la tuile
        and #F8
        ld (py),a
        ld a,(joy_new)
        and (1<<J_FIRE1)|(1<<J_FIRE2)
        ret z
        ld a,-7                         ; saut
        ld (pvy),a
        jr g_move
g_fall: ld a,(pvy)
        cp 4
        jr z,g_move
        inc a                           ; acceleration (vitesse maxi 4)
        ld (pvy),a
g_move: ld a,(pvy)
        ld b,a
        ld a,(py)
        add a,b
        cp 180
        jr c,g_ok
        ld a,20                         ; tombe hors de l'ecran : reapparait en haut
g_ok:   ld (py),a
        ret

; ground : la case sous les pieds (centre du sprite, ligne y+16) est-elle solide ?
ground:
        ld hl,(px)
        ld de,16
        add hl,de                       ; centre (pixels mode 2)
        srl h                           ; / 16 = colonne de tuile (8 pixels mode 1)
        rr l
        srl h
        rr l
        srl h
        rr l
        srl h
        rr l
        ld b,l
        ld a,(py)
        add a,16
        rrca
        rrca
        rrca
        and 31                          ; / 8 = rangee de tuile
        ld c,a
        ld hl,MAP_NIVEAU1
        ld d,MAP_NIVEAU1_W
        call map_get                    ; A = numero de tuile
        ld hl,TFLAGS_DECOR
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
