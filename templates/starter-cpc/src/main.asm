;=============================================================================
;  {{NAME}} - projet GX Studio pour {{TARGET}} (disquette DSK)
;
;  Point de depart : un vaisseau (sprite logiciel, mode 0) dirige a la manette
;  ou au clavier, qui tire sur un ennemi rebondissant. Musique et bruitages
;  par le lecteur logiciel (memes listes que le DMA des CPC+).
;  Sur le CPC : RUN"{{SHORT}}
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
 VAR ship_x,1                           ; vaisseau : X en octets (2 pixels), Y en lignes
 VAR ship_y,1
 VAR shot_on,1
 VAR shot_x,1
 VAR shot_y,1
 VAR foe_x,1
 VAR foe_y,1
 VAR foe_dx,1
 VAR foe_dy,1
 VAR boom_t,1
 VAR score,2
 VAR numbuf,6
 VAR tile_vars,12                       ; pour lib/common/tiles.asm
 VAR scroll_vars,16                     ; pour lib/common/scroll.asm (et les sprites logiciels)
        assert vptr<=STACK_TOP-256

SHIP_SLOT       equ 0                   ; emplacements des sprites logiciels
SHOT_SLOT       equ 1
FOE_SLOT        equ 2

        org #0400                       ; chargement et lancement par RUN"{{SHORT}}
        include "../lib/cpc/boot_cpc.asm"
        include "../lib/cpc/swsprites.asm"
        include "../lib/common/text0.asm"
        include "../lib/common/sound_sw.asm"
        include "../lib/common/input.asm"
        include "../lib/common/tiles.asm"   ; decors (editeur de tuiles et de cartes)
        include "../lib/common/anim.asm"    ; animations de sprites
        include "../lib/common/scroll.asm"  ; defilement des cartes plus larges que l'ecran
                                        ; (8 directions : scroll8.asm a la place, avec VAR scroll_vars,40)

main:
        ld hl,pal_screen
        call set_palette
        call sw_init
title:
        call clear_screen
        ld a,3
        call set_ink0
        ld bc,2*256+60
        ld de,s_title
        call text0_at
        ld a,2
        call set_ink0
        ld bc,3*256+120
        ld de,s_press
        call text0_at
        ld a,MUSIQUE_CH
        ld hl,snd_lists+MUSIQUE_OFS
        ld b,MUSIQUE_SPEED
        call snd_play
t_wait: call wait_frame
        call read_joy
        ld a,(joy_new)
        and (1<<J_FIRE1)|(1<<J_FIRE2)
        jr z,t_wait

;----------------------------------------------------------------- partie ----
        call snd_stop_all
        call clear_screen
        ld a,36
        ld (ship_x),a
        ld a,170
        ld (ship_y),a
        ld a,10
        ld (foe_x),a
        ld a,40
        ld (foe_y),a
        ld a,1
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
        call wait_frame                 ; synchro verticale
        call sw_update                  ; efface et redessine les sprites
        call read_joy
        call move_ship
        call move_shot
        call move_foe
        jr loop

move_ship:
        ld a,(ship_x)
        ld b,a
        ld a,(joy)
        bit J_LEFT,a
        jr z,ms_r
        ld a,b
        or a
        jr z,ms_r
        dec b
ms_r:   ld a,(joy)
        bit J_RIGHT,a
        jr z,ms_v
        ld a,b
        cp 80-8
        jr nc,ms_v
        inc b
ms_v:   ld a,b
        ld (ship_x),a
        ld a,(ship_y)
        ld c,a
        ld a,(joy)
        bit J_UP,a
        jr z,ms_d
        ld a,c
        cp 20
        jr c,ms_d
        sub 2
        ld c,a
ms_d:   ld a,(joy)
        bit J_DOWN,a
        jr z,ms_f
        ld a,c
        cp 182
        jr nc,ms_f
        add a,2
        ld c,a
ms_f:   ld a,c
        ld (ship_y),a
        ld a,(joy_new)
        and (1<<J_FIRE1)|(1<<J_FIRE2)
        jr z,ms_s
        ld a,(shot_on)
        or a
        jr nz,ms_s
        inc a
        ld (shot_on),a
        ld a,(ship_x)
        ld (shot_x),a
        ld a,(ship_y)
        sub 12
        ld (shot_y),a
        ld a,SON_TIR_CH
        ld hl,snd_lists+SON_TIR_OFS
        ld b,SON_TIR_SPEED
        call snd_play
ms_s:   ld a,(ship_x)
        ld b,a
        ld a,(ship_y)
        ld c,a
        ld d,F_VAISSEAU
        ld a,SHIP_SLOT
        jp sw_set

move_shot:
        ld a,(shot_on)
        or a
        jr z,sh_off
        ld a,(shot_y)
        sub 6
        jr c,sh_end
        cp 16
        jr c,sh_end
        ld (shot_y),a
        ld a,(foe_x)                    ; touche l'ennemi ? |dx| < 6 octets, |dy| < 14
        ld b,a
        ld a,(shot_x)
        sub b
        jr nc,sh_1
        neg
sh_1:   cp 6
        jr nc,sh_draw
        ld a,(foe_y)
        ld b,a
        ld a,(shot_y)
        sub b
        jr nc,sh_2
        neg
sh_2:   cp 14
        jr nc,sh_draw
        ld a,(boom_t)
        or a
        jr nz,sh_draw
        call foe_hit
sh_end: xor a
        ld (shot_on),a
sh_off: ld a,SHOT_SLOT
        jp sw_hide
sh_draw:
        ld a,(shot_x)
        ld b,a
        ld a,(shot_y)
        ld c,a
        ld d,F_TIR
        ld a,SHOT_SLOT
        jp sw_set

foe_hit:
        ld a,SON_BOOM_CH
        ld hl,snd_lists+SON_BOOM_OFS
        ld b,SON_BOOM_SPEED
        call snd_play
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
        ld d,F_EXPLOSION
        jr nz,mf_draw
        ld a,30
        ld (foe_y),a
mf_live:
        ld a,(foe_dx)
        ld b,a
        ld a,(foe_x)
        add a,b
        ld (foe_x),a
        cp 2
        jr c,mf_fx
        cp 80-10
        jr c,mf_y
mf_fx:  ld a,b
        neg
        ld (foe_dx),a
mf_y:   ld a,(foe_dy)
        ld b,a
        ld a,(foe_y)
        add a,b
        ld (foe_y),a
        cp 20
        jr c,mf_fy
        cp 180
        jr c,mf_ok
mf_fy:  ld a,b
        neg
        ld (foe_dy),a
mf_ok:  ld d,F_ENNEMI
mf_draw:
        ld a,(foe_x)
        ld b,a
        ld a,(foe_y)
        ld c,a
        ld a,FOE_SLOT
        jp sw_set

show_score:
        ld a,3
        call set_ink0
        ld hl,numbuf
        ld a,(score+1)
        call bcd_out
        ld a,(score)
        call bcd_out
        ld (hl),0
        ld bc,0*256+0
        ld de,numbuf
        jp text0_at
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

s_title: db "{{NAME_UPPER}}",0
s_press: db "APPUIE SUR TIR",0

FONT:   incbin "../build/font.bin"
        include "../build/images.inc"
        include "../build/gen.asm"
code_end:
        assert code_end<=#A700          ; le firmware (AMSDOS) occupe le haut de la memoire
        assert SPR_MAX_BYTES<=SW_BUF

        include "../build/save.inc"     ; ecriture sur la disquette
