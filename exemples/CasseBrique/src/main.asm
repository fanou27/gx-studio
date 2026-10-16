;=============================================================================
;  CASSE-BRIQUE GX - tutoriel GX Studio (GX4000 / CPC+, cartouche CPR)
;
;  Une raquette et une balle en sprites materiels, 60 briques dessinees dans
;  l'ecran (mode 1), des couleurs differentes par rangee grace aux
;  interruptions raster, des bruitages joues par le DMA.
;=============================================================================
        buildcpr

        include "../build/config.inc"   ; cible, RAM, clavier (genere par le studio)
RAM_START       equ #8000               ; variables (mises a zero au demarrage)
STACK_TOP       equ #C000
DMA_RAM         equ #A000               ; copie des listes de son (le DMA lit la RAM)
PAGE_SPR        equ 1                   ; page de la cartouche : images des sprites
PAGE_DATA       equ 2                   ; page de la cartouche : police, images

        include "../lib/plus/hw.inc"

;=== ETAPE 1 : les constantes du jeu ==========================================
BR_COLS         equ 10                  ; 10 colonnes de briques
        ifndef BR_ROWS                  ; (peut etre change dans Projet > Proprietes : BR_ROWS=2)
BR_ROWS         equ 6                   ; 6 rangees
        endif
BR_TOP          equ 32                  ; ligne de la premiere rangee
BR_H            equ 8                   ; hauteur d'une rangee (lignes)
BR_W            equ 8                   ; largeur d'une brique (octets = 32 pixels mode 1)
PAD_Y           equ 184                 ; ligne de la raquette
PAD_W           equ 64                  ; largeur de la raquette (pixels mode 2)
TOP_WALL        equ 14                  ; plafond (sous la ligne du score)
BALL_SPR        equ 0                   ; numeros des sprites materiels
PAD_SPR         equ 1

;=== ETAPE 1 : les variables ===================================================
vptr=RAM_START
macro VAR name,size
{name} equ vptr
vptr=vptr+{size}
mend
 VAR frame_flag,1                       ; variables demandees par la bibliotheque
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
 VAR spr_tab,16*5
 VAR spr_want,16
 VAR spr_have,16
 VAR bricks,BR_COLS*BR_ROWS             ; 1 = brique presente
 VAR nbricks,1                          ; briques restantes
 VAR pad_x,2                            ; raquette : X en pixels mode 2 (0-639)
 VAR ball_x,2                           ; balle : X (pixels mode 2), Y (lignes)
 VAR ball_y,1
 VAR ball_dx,1                          ; vitesse (signee)
 VAR ball_dy,1
 VAR serve,1                            ; 1 = la balle attend sur la raquette
 VAR lives,1
 VAR score,2                            ; BCD (4 chiffres)
 VAR speed,1                            ; vitesse verticale (2, puis 3)
 VAR numbuf,8
        assert vptr<DMA_RAM

;------------------------------------------------------------------ PAGE 0 ---
        bank 0
        org #0000
        include "../lib/plus/boot.asm"  ; doit etre au debut (vecteurs &0000 et &0038)
        include "../lib/plus/raster.asm"
        include "../lib/plus/sprites.asm"
        include "../lib/plus/dma.asm"
        include "../lib/common/input.asm"
        include "../lib/plus/text1.asm"

;=== ETAPE 2 : demarrage et ecran titre =======================================
main:
        call dma_init                   ; listes de son recopiees en RAM
        ld hl,spr_have                  ; aucune image encore dans l'ASIC
        ld b,16
m_h:    ld (hl),#FF
        inc hl
        djnz m_h
        ld hl,pal_screen
        call set_palette4               ; encres 0-3 (assets/palettes/ecran.pal)
        ld hl,pal_sprites
        call set_spr_palette            ; couleurs 1-15 des sprites
title:
        call spr_hide_all
        ld hl,raster_idle               ; pas de couleurs par rangee sur le titre
        call raster_start
        call clear_screen
        ld a,3
        call set_ink
        ld bc,13*256+70
        ld de,s_title
        call text_at
        ld a,2
        call set_ink
        ld bc,13*256+120
        ld de,s_press
        call text_at
        ld a,MUSIQUE_CH                 ; musique (assets/sons/musique.snd)
        ld hl,DMA_RAM+MUSIQUE_OFS
        ld b,MUSIQUE_SPEED
        call dma_play
t_wait: call wait_frame
        call read_joy
        ld a,(joy_new)
        and (1<<J_FIRE1)|(1<<J_FIRE2)
        jr z,t_wait

;=== ETAPE 3 : une nouvelle partie =============================================
        call dma_stop_all
        call clear_screen
        ld a,3
        ld (lives),a
        ld a,2
        ld (speed),a
        ld hl,0
        ld (score),hl
        ld hl,(640-PAD_W)/2
        ld (pad_x),hl
        call new_level
        ld hl,raster_bricks             ; couleurs par rangee (etape 8)
        call raster_start
        call show_score

;=== ETAPE 4 : la boucle de jeu (50 fois par seconde) ==========================
loop:
        call wait_frame                 ; ligne 199 : l'ecran visible est fini
        xor a
        call spr_commit                 ; positions calculees a l'image precedente
        call spr_load                   ; images de sprites qui ont change
        call read_joy
        call move_pad
        call move_ball
        ld a,(lives)
        or a
        jr nz,loop
        ; --- plus de vie : fin de partie
        call spr_hide_all
        ld a,3
        call set_ink
        ld bc,15*256+120
        ld de,s_over
        call text_at
        ld b,150
        call wait_frames
        jp title

;=== ETAPE 5 : les briques =====================================================
; new_level : remplit le tableau et dessine les 60 briques, balle sur la raquette
new_level:
        ld hl,bricks
        ld b,BR_COLS*BR_ROWS
nl_1:   ld (hl),1
        inc hl
        djnz nl_1
        ld a,BR_COLS*BR_ROWS
        ld (nbricks),a
        ld c,0                          ; C = rangee
nl_r:   ld b,0                          ; B = colonne
nl_c:   push bc
        ld a,#FF                        ; encre 3 sur 4 pixels
        call draw_brick
        pop bc
        inc b
        ld a,b
        cp BR_COLS
        jr nz,nl_c
        inc c
        ld a,c
        cp BR_ROWS
        jr nz,nl_r
        ld a,1                          ; la balle attend sur la raquette
        ld (serve),a
        ret

; draw_brick : B = colonne, C = rangee, A = motif (#FF = brique, 0 = effacer)
;   7 lignes de 8 octets ; le dernier pixel de chaque brique reste noir (joint)
draw_brick:
        ld e,a                          ; E = motif
        ld a,c                          ; ligne ecran = BR_TOP + rangee x 8
        add a,a
        add a,a
        add a,a
        add a,BR_TOP
        ld l,a
        ld h,0
        add hl,hl
        push de
        ld de,line_addr
        add hl,de                       ; HL -> adresse de la ligne
        pop de
        ld a,b                          ; colonne x 8 octets
        add a,a
        add a,a
        add a,a
        ld c,a
        ld d,7                          ; 7 lignes
dbr_l:   push hl
        ld a,(hl)
        inc hl
        ld h,(hl)
        add a,c
        ld l,a
        adc a,h
        sub l
        ld h,a                          ; HL = ecran
        ld b,BR_W-1
dbr_b:   ld (hl),e                       ; 7 octets pleins
        inc hl
        djnz dbr_b
        ld a,e
        and #EE                         ; 8e octet : 3 pixels sur 4
        ld (hl),a
        pop hl
        inc hl
        inc hl                          ; ligne suivante dans line_addr
        dec d
        jr nz,dbr_l
        ret

;=== ETAPE 6 : la raquette =====================================================
move_pad:
        ld hl,(pad_x)
        ld de,8                         ; 8 pixels mode 2 par image
        ld a,(joy)
        bit J_LEFT,a
        jr z,mp_r
        or a
        sbc hl,de
        jr nc,mp_r
        ld hl,0                         ; bord gauche
mp_r:   ld a,(joy)
        bit J_RIGHT,a
        jr z,mp_ok
        add hl,de
        ld de,640-PAD_W
        call cmp_hl_de
        jr c,mp_ok
        ex de,hl                        ; bord droit
mp_ok:  ld (pad_x),hl
        ld a,PAD_SPR
        ld de,PAD_Y
        ld bc,F_RAQUETTE*256+ZOOM_X4Y1  ; zoom x4 en largeur : 64 pixels mode 2
        jp spr_set

;=== ETAPE 7 : la balle ========================================================
move_ball:
        ld a,(serve)
        or a
        jr z,mb_fly
        ; --- service : la balle suit la raquette jusqu'a l'appui sur tir
        ld hl,(pad_x)
        ld de,PAD_W/2-4
        add hl,de
        ld (ball_x),hl
        ld a,PAD_Y-4
        ld (ball_y),a
        ld a,(joy_new)
        and (1<<J_FIRE1)|(1<<J_FIRE2)
        jr z,mb_draw
        xor a
        ld (serve),a
        ld a,4
        ld (ball_dx),a
        ld a,(speed)
        neg
        ld (ball_dy),a                  ; vers le haut
        jr mb_draw
mb_fly:
        ; --- deplacement horizontal et murs gauche / droit
        ld a,(ball_dx)
        ld e,a
        rla
        sbc a,a
        ld d,a                          ; DE = dx (signe)
        ld hl,(ball_x)
        add hl,de
        bit 7,h
        jr z,mb_x1
        ld hl,0                         ; mur gauche
        call bounce_x_pos
mb_x1:  ld de,640-8
        call cmp_hl_de
        jr c,mb_x2
        ex de,hl                        ; mur droit
        call bounce_x_neg
mb_x2:  ld (ball_x),hl
        ; --- deplacement vertical et plafond
        ld a,(ball_dy)
        ld b,a
        ld a,(ball_y)
        add a,b
        ld (ball_y),a
        cp TOP_WALL
        jr nc,mb_y1
        ld a,TOP_WALL
        ld (ball_y),a
        call bounce_y_pos
mb_y1:  call hit_bricks
        call hit_pad
        ld a,(ball_y)                   ; balle perdue ?
        cp 196
        jr c,mb_draw
        call ball_lost
mb_draw:
        ld a,BALL_SPR
        ld hl,(ball_x)
        ld de,(ball_y)
        ld d,0
        ld bc,F_BALLE*256+ZOOM_X2Y1
        jp spr_set

bounce_x_pos:                           ; dx devient positif
        ld a,(ball_dx)
        call abs_a
        ld (ball_dx),a
        jr snd_rebond
bounce_x_neg:                           ; dx devient negatif
        ld a,(ball_dx)
        call abs_a
        neg
        ld (ball_dx),a
        jr snd_rebond
bounce_y_pos:                           ; dy devient positif
        ld a,(ball_dy)
        call abs_a
        ld (ball_dy),a
snd_rebond:
        push hl
        ld a,REBOND_CH
        ld hl,DMA_RAM+REBOND_OFS
        ld b,REBOND_SPEED
        call dma_play
        pop hl
        ret

; hit_pad : la balle touche la raquette -> remonte, l'angle depend du point d'impact
hit_pad:
        ld a,(ball_dy)
        bit 7,a
        ret nz                          ; elle monte deja
        ld a,(ball_y)
        add a,4                         ; bas de la balle
        cp PAD_Y
        ret c
        cp PAD_Y+5
        ret nc
        ld hl,(ball_x)                  ; position relative : (balle+4) - raquette
        ld de,4
        add hl,de
        ld de,(pad_x)
        or a
        sbc hl,de
        ret c                           ; a gauche de la raquette
        ld de,PAD_W
        call cmp_hl_de
        ret nc                          ; a droite
        ld a,l                          ; 0..63 -> 8 zones
        rrca
        rrca
        rrca
        and 7
        ld e,a
        ld d,0
        ld hl,pad_angle
        add hl,de
        ld a,(hl)
        ld (ball_dx),a
        ld a,(speed)
        neg
        ld (ball_dy),a
        jr snd_rebond
pad_angle:  db -6,-4,-4,-2,2,4,4,6     ; vitesse horizontale selon la zone touchee

; hit_bricks : le centre de la balle est-il dans une brique ?
hit_bricks:
        ld a,(ball_y)
        add a,2                         ; centre vertical
        sub BR_TOP
        ret c
        cp BR_ROWS*BR_H
        ret nc
        rrca
        rrca
        rrca
        and 31
        ld c,a                          ; C = rangee
        ld hl,(ball_x)
        ld de,4
        add hl,de                       ; centre horizontal (0-639)
        ld b,0                          ; B = colonne = X / 64
        ld de,64
hb_d:   or a
        sbc hl,de
        jr c,hb_c
        inc b
        jr hb_d
hb_c:   ld a,b
        cp BR_COLS
        ret nc
        ld a,c                          ; indice = rangee x 10 + colonne
        add a,a
        ld e,a
        add a,a
        add a,a
        add a,e
        add a,b
        ld e,a
        ld d,0
        ld hl,bricks
        add hl,de
        ld a,(hl)
        or a
        ret z                           ; deja cassee
        ld (hl),0
        push bc
        xor a
        call draw_brick                 ; efface la brique
        pop bc
        ld a,(ball_dy)                  ; rebond vertical
        neg
        ld (ball_dy),a
        ld a,BR_ROWS                    ; points : 10 x (6 - rangee)
        sub c
        call add_points
        ld a,BRIQUE_CH
        ld hl,DMA_RAM+BRIQUE_OFS
        ld b,BRIQUE_SPEED
        call dma_play
        ld hl,nbricks
        dec (hl)
        ret nz
        ; --- tableau termine : on recommence, un peu plus vite
        ld a,3
        ld (speed),a
        jp new_level

; ball_lost : une vie de moins, la balle revient sur la raquette
ball_lost:
        ld a,PERDU_CH
        ld hl,DMA_RAM+PERDU_OFS
        ld b,PERDU_SPEED
        call dma_play
        ld hl,lives
        dec (hl)
        ld a,1
        ld (serve),a
        jp show_score

;=== ETAPE 7 bis : score et petites routines ===================================
; add_points : A = nombre de dizaines a ajouter (score BCD)
add_points:
        add a,a                         ; A x 10 en BCD : chiffre des dizaines
        add a,a
        add a,a
        add a,a
        ld b,a
        ld a,(score)
        add a,b
        daa
        ld (score),a
        ld a,(score+1)
        adc a,0
        daa
        ld (score+1),a
        jp show_score

show_score:
        ld a,3
        call set_ink
        ld bc,0*256+0
        ld de,s_score
        call text_at
        ld hl,numbuf
        ld a,(score+1)
        call bcd_out
        ld a,(score)
        call bcd_out
        ld (hl),0
        ld bc,6*256+0
        ld de,numbuf
        call text_at
        ld bc,32*256+0
        ld de,s_lives
        call text_at
        ld a,(lives)
        add a,'0'
        ld (numbuf),a
        xor a
        ld (numbuf+1),a
        ld bc,37*256+0
        ld de,numbuf
        jp text_at

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

abs_a:  or a
        ret p
        neg
        ret

;=== ETAPE 8 : une couleur par rangee de briques (interruptions raster) =======
; Les briques sont toutes dessinees avec l'encre 3. A chaque rangee, une
; interruption raster change la couleur de l'encre 3 ; apres la derniere
; rangee, l'encre 3 redevient blanche (pour le score en haut de l'ecran).
; Format : premiere ligne, puis entrees (type, donnee, ligne suivante).
raster_bricks:
        db BR_TOP-2                             ; un peu avant la brique (temps de reaction)
        db 0 : dw #00F2 : db BR_TOP-1+1*BR_H    ; type 0 = couleur de l'encre 3 : &0GRB (rouge)
        db 0 : dw #08F0 : db BR_TOP-1+2*BR_H    ; orange
        db 0 : dw #0FF0 : db BR_TOP-1+3*BR_H    ; jaune
        db 0 : dw #0F40 : db BR_TOP-1+4*BR_H    ; vert
        db 0 : dw #0F0F : db BR_TOP-1+5*BR_H    ; cyan
        db 0 : dw #044F : db BR_TOP-1+6*BR_H    ; bleu
        db 0 : dw #0FFF : db 199                ; retour au blanc (score)
        db 2 : dw 0 : db ARM_LINE               ; fin d'image (ligne 199)
        db 3 : dw 0 : db 0                      ; armement (ligne 304) : on recommence

s_title: db "CASSE-BRIQUE GX",0
s_press: db "APPUIE SUR TIR",0
s_over:  db "GAME OVER",0
s_score: db "SCORE",0
s_lives: db "VIES",0

        include "../build/gen.asm"      ; palettes, sons, numeros des sprites (genere)
code_end:
        assert code_end<=#4000

;------------------------------------------------------------------ PAGE 1 ---
        bank 1
        org #C000
SPRITES:
        incbin "../build/sprites.bin"   ; images des sprites (generees)

;------------------------------------------------------------------ PAGE 2 ---
        bank 2
        org #C000
FONT:   incbin "../build/font.bin"
        include "../build/images.inc"
