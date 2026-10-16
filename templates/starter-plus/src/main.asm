;=============================================================================
;  {{NAME}} - projet GX Studio pour {{TARGET}} (cartouche CPR)
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
 VAR ship_x,2                           ; vaisseau (pixels mode 2, lignes)
 VAR ship_y,1
 VAR shot_on,1                          ; tir
 VAR shot_x,2
 VAR shot_y,1
 VAR foe_x,2                            ; ennemi
 VAR foe_y,1
 VAR foe_dx,1
 VAR foe_dy,1
 VAR boom_t,1                           ; explosion
 VAR score,2                            ; BCD
 VAR numbuf,6
 VAR tile_vars,12                       ; pour lib/common/tiles.asm
 VAR scroll_vars,16                     ; pour lib/common/scroll.asm (et les sprites logiciels)
        assert vptr<DMA_RAM

SHIP_SPR        equ 0                   ; numeros des sprites materiels
SHOT_SPR        equ 1
FOE_SPR         equ 2

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
        include "../lib/common/scroll.asm"  ; defilement des cartes plus larges que l'ecran
                                        ; (8 directions : scroll8.asm a la place, avec VAR scroll_vars,40)
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
        call dma_init
        ld hl,spr_have                  ; aucune image chargee dans l'ASIC
        ld b,16
m_h:    ld (hl),#FF
        inc hl
        djnz m_h
        ld hl,pal_screen
        if SCREEN_MODE==1
        call set_palette4
        else
        call set_palette16
        endif
        ld hl,pal_sprites
        call set_spr_palette
title:
        call spr_hide_all
        call clear_screen
        ld a,3
        call txt_ink
        ld c,60
        ld de,s_title
        call txt_center
        ld a,2
        call txt_ink
        ld c,120
        ld de,s_press
        call txt_center
        ld a,MUSIQUE_CH                 ; musique (assets/sons/musique.snd)
        ld hl,DMA_RAM+MUSIQUE_OFS
        ld b,MUSIQUE_SPEED
        call dma_play
t_wait: call wait_frame
        call copper
        call read_joy
        ld a,(joy_new)
        and (1<<J_FIRE1)|(1<<J_FIRE2)
        jr z,t_wait

;----------------------------------------------------------------- partie ----
        call dma_stop_all
        call clear_screen
        ld hl,304
        ld (ship_x),hl
        ld a,170
        ld (ship_y),a
        ld hl,100
        ld (foe_x),hl
        ld a,40
        ld (foe_y),a
        ld a,3
        ld (foe_dx),a
        ld a,2
        ld (foe_dy),a
        xor a
        ld (shot_on),a
        ld (boom_t),a
        ld hl,0
        ld (score),hl
        call show_score
loop:
        call wait_frame                 ; ligne 199 : debut du bord bas
        xor a
        call spr_commit                 ; positions calculees a l'image precedente
        call spr_load                   ; images qui ont change
        call copper
        call read_joy
        call move_ship
        call move_shot
        call move_foe
        jr loop

; move_ship : 4 directions, tir
move_ship:
        ld hl,(ship_x)
        ld de,4
        ld a,(joy)
        bit J_LEFT,a
        jr z,ms_r
        or a
        sbc hl,de
        jr nc,ms_r
        ld hl,0
ms_r:   ld a,(joy)
        bit J_RIGHT,a
        jr z,ms_v
        add hl,de
        ld de,640-32
        call cmp_hl_de
        jr c,ms_v
        ex de,hl                        ; bord droit
ms_v:   ld (ship_x),hl
        ld a,(ship_y)
        ld b,a
        ld a,(joy)
        bit J_UP,a
        jr z,ms_d
        ld a,b
        cp 20
        jr c,ms_d
        sub 2
        ld b,a
ms_d:   ld a,(joy)
        bit J_DOWN,a
        jr z,ms_f
        ld a,b
        cp 182
        jr nc,ms_f
        add a,2
        ld b,a
ms_f:   ld a,b
        ld (ship_y),a
        ld a,(joy_new)
        and (1<<J_FIRE1)|(1<<J_FIRE2)
        jr z,ms_s
        ld a,(shot_on)
        or a
        jr nz,ms_s
        inc a
        ld (shot_on),a
        ld hl,(ship_x)
        ld (shot_x),hl
        ld a,(ship_y)
        sub 8
        ld (shot_y),a
        ld a,SON_TIR_CH
        ld hl,DMA_RAM+SON_TIR_OFS
        ld b,SON_TIR_SPEED
        call dma_play
ms_s:   ld a,SHIP_SPR
        ld hl,(ship_x)
        ld de,(ship_y)
        ld d,0
        ld bc,F_VAISSEAU*256+ZOOM_X2Y1
        jp spr_set

move_shot:
        ld a,(shot_on)
        or a
        jr z,sh_off
        ld a,(shot_y)
        sub 6
        jr c,sh_end
        ld (shot_y),a
        ; touche l'ennemi ?  |dx| < 24 et |dy| < 14
        ld hl,(shot_x)
        ld de,(foe_x)
        or a
        sbc hl,de
        call abs_hl
        ld a,h
        or a
        jr nz,sh_draw
        ld a,l
        cp 24
        jr nc,sh_draw
        ld a,(foe_y)
        ld b,a
        ld a,(shot_y)
        sub b
        jr nc,sh_p
        neg
sh_p:   cp 14
        jr nc,sh_draw
        call foe_hit
sh_end: xor a
        ld (shot_on),a
sh_off: ld a,SHOT_SPR
        jp spr_hide
sh_draw:
        ld a,SHOT_SPR
        ld hl,(shot_x)
        ld de,(shot_y)
        ld d,0
        ld bc,F_TIR*256+ZOOM_X2Y1
        jp spr_set

; foe_hit : explosion, score, l'ennemi repart d'en haut
foe_hit:
        ld a,SON_BOOM_CH
        ld hl,DMA_RAM+SON_BOOM_OFS
        ld b,SON_BOOM_SPEED
        call dma_play
        ld a,24
        ld (boom_t),a
        ld a,(score)
        add a,#10
        daa
        ld (score),a
        ld a,(score+1)
        adc a,0
        daa
        ld (score+1),a
        jp show_score

move_foe:
        ld a,(boom_t)
        or a
        jr z,mf_live
        dec a
        ld (boom_t),a
        jr nz,mf_boom
        ld a,30                         ; reapparait en haut
        ld (foe_y),a
mf_boom:
        ld a,FOE_SPR
        ld hl,(foe_x)
        ld de,(foe_y)
        ld d,0
        ld bc,F_EXPLOSION*256+ZOOM_X2Y1
        jp spr_set
mf_live:
        ld hl,(foe_x)                   ; rebonds sur les bords
        ld a,(foe_dx)
        ld e,a
        rla
        sbc a,a
        ld d,a
        add hl,de
        ld (foe_x),hl
        bit 7,h
        jr nz,mf_flipx
        ld de,8
        call cmp_hl_de
        jr c,mf_flipx
        ld de,640-40
        call cmp_hl_de
        jr c,mf_y
mf_flipx:
        ld a,(foe_dx)
        neg
        ld (foe_dx),a
mf_y:   ld a,(foe_dy)
        ld b,a
        ld a,(foe_y)
        add a,b
        ld (foe_y),a
        cp 20
        jr c,mf_flipy
        cp 180
        jr c,mf_draw
mf_flipy:
        ld a,(foe_dy)
        neg
        ld (foe_dy),a
mf_draw:
        ld a,FOE_SPR
        ld hl,(foe_x)
        ld de,(foe_y)
        ld d,0
        ld bc,F_ENNEMI*256+ZOOM_X2Y1
        jp spr_set

; copper : degrade de l'encre 3 sur les lignes 40 a 103 (16 interruptions raster)
copper:
        ld a,(rbuf)
        xor 1
        ld (rbuf),a
        ld hl,rtab
        jr z,cp_0
        ld hl,rtab+80
cp_0:   push hl
        ld (hl),40                      ; premiere ligne
        inc hl
        ld a,(frame_count)
        ld c,a
        ld d,44                         ; ligne suivante
        ld b,16
cp_l:   ld (hl),0                       ; type 0 : couleur de l'encre 3
        inc hl
        ld a,c
        and 15                          ; degrade : &0GRB
        ld (hl),a                       ; bleu
        inc hl
        ld a,c
        rrca
        and 15
        or 8
        ld (hl),a                       ; vert
        inc hl
        ld a,b
        dec a
        ld a,d
        jr nz,cp_n
        ld a,199
cp_n:   ld (hl),a
        inc hl
        ld a,d
        add a,4
        ld d,a
        inc c
        djnz cp_l
        ld (hl),2                       ; fin d'image
        inc hl
        ld (hl),0
        inc hl
        ld (hl),0
        inc hl
        ld (hl),ARM_LINE
        inc hl
        ld (hl),3                       ; armement
        inc hl
        ld (hl),0
        inc hl
        ld (hl),0
        inc hl
        ld (hl),0
        pop hl
        di
        ld (pri_pending),hl
        ei
        ret

show_score:
        ld a,3
        call txt_ink
        ld hl,numbuf
        ld a,(score+1)
        call bcd_out
        ld a,(score)
        call bcd_out
        ld (hl),0
        ld bc,0*256+0
        ld de,numbuf
        jp txt_at

; txt_center : DE = chaine, C = ligne -> centree
txt_center:
        ld h,d
        ld l,e
        ld b,0
tc_l:   ld a,(hl)
        or a
        jr z,tc_d
        inc hl
        inc b
        jr tc_l
tc_d:   ld a,COLS
        sub b
        jr nc,tc_ok
        xor a
tc_ok:  srl a
        ld b,a
        jp txt_at
bcd_out:
        ld c,a
        rrca
        rrca
        rrca
        rrca
        and 15
        add a,'0'
        ld (hl),a
        inc hl
        ld a,c
        and 15
        add a,'0'
        ld (hl),a
        inc hl
        ret

; cmp_hl_de : CF = 1 si HL < DE (non signes)
cmp_hl_de:
        push hl
        or a
        sbc hl,de
        pop hl
        ret

abs_hl: bit 7,h
        ret z
        xor a
        sub l
        ld l,a
        sbc a,a
        sub h
        ld h,a
        ret

s_title: db "{{NAME_UPPER}}",0
s_press: db "APPUIE SUR TIR",0

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
