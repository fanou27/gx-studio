;=============================================================================
; lib/common/splash.asm - ecran de presentation de GX Studio (3 secondes)
; (c) 2026 ALONSO Stephane [Fanou]
;
; Affiche au demarrage de chaque jeu le logo FANOU 1972 et le texte
; "Jeux developpe avec GXStudio By Fanou" (mode 0, 16 encres), pendant
; 3 secondes, puis rend un ecran noir. Appele par le demarrage de la
; bibliotheque (lib/plus/boot.asm, lib/cpc/boot_cpc.asm), ecran deja efface.
;   gx_splash : A = registre de mode du Gate Array du jeu, sans les bits de
;               mode (CPC+ : #80, ROM actives ; CPC : #8C, ROM coupees).
;               Pendant le splash, la ROM haute est coupee (l'ecran se relit).
;               En sortie : mode SCREEN_MODE, palette noire, ecran efface.
;               Modifie tout sauf IY et SP. Variables : 6 octets sous la pile
;               (STACK_TOP-128), remis a zero.
; Donnees : splash_data.asm (produit par tools/mksplash.py du studio).
;=============================================================================
SPLASH_FRAMES   equ 150                 ; 3 secondes (50 images par seconde)
spl_tx          equ STACK_TOP-128       ; variables (RAM sous la pile)
spl_ty          equ STACK_TOP-127
spl_ink         equ STACK_TOP-126
spl_row         equ STACK_TOP-125
spl_m           equ STACK_TOP-124

gx_splash:
        push af
        or 8                            ; mode 0, ROM haute coupee
        ld bc,GA
        out (c),a
        call spl_logo
        ld hl,splash_texts
        call spl_texts
        if ASIC
        ld hl,splash_asic               ; couleurs (registres de l'ASIC, page ouverte)
        ld de,ASIC_PAL
        ld bc,32
        ldir
        ld hl,0
        ld (ASIC_BORDER),hl
        else
        ld hl,splash_fw                 ; couleurs (Gate Array, bord = encre 0)
        call set_palette
        endif
        ld e,SPLASH_FRAMES              ; 3 secondes (balayage vertical : port B du PPI)
        ld b,PPI_B/256
spl_w0: in a,(c)
        rra
        jr c,spl_w0
spl_w1: in a,(c)
        rra
        jr nc,spl_w1
        dec e
        jr nz,spl_w0
        if ASIC                         ; palette noire
        ld hl,ASIC_PAL
        ld b,17*2
spl_k:  ld (hl),0
        inc hl
        djnz spl_k
        else
        ld hl,pal_black16
        call set_palette
        endif
        call clear_screen
        ld hl,spl_tx                    ; variables remises a zero
        ld b,5
spl_z:  ld (hl),0
        inc hl
        djnz spl_z
        pop af
        or SCREEN_MODE
        ld bc,GA
        out (c),a                       ; mode du jeu
        ret

; spl_addr : A = ligne -> HL = &C000 + (y & 7) x &800 + (y / 8) x 80. Modifie A.
spl_addr:
        push de
        push af
        rrca
        rrca
        rrca
        and #1F
        ld l,a
        ld h,0
        add hl,hl
        add hl,hl
        add hl,hl
        add hl,hl                       ; n x 16
        ld d,h
        ld e,l
        add hl,hl
        add hl,hl                       ; n x 64
        add hl,de                       ; n x 80
        pop af
        and 7
        add a,a
        add a,a
        add a,a
        or #C0
        add a,h
        ld h,a
        pop de
        ret

; spl_logo : le logo, compacte (RLE) : n < &80 : n+1 octets ; n >= &80 :
;   (n & &7F) + 2 fois l'octet suivant. Rempli ligne par ligne, SPLASH_W
;   octets a partir de l'octet SPLASH_X de chaque ligne.
spl_logo:
        ld hl,splash_rle
        ld d,SPLASH_Y
        call spl_dst
spl_lp: ld a,(hl)
        inc hl
        bit 7,a
        jr nz,spl_run
        inc a
        ld e,a                          ; n+1 octets copies
spl_cp: ld a,(hl)
        inc hl
        call spl_put
        dec e
        jr nz,spl_cp
        jr spl_nx
spl_run:
        and #7F
        add a,2
        ld e,a                          ; (n & &7F) + 2 fois
        ld c,(hl)
        inc hl
spl_rp: ld a,c
        call spl_put
        dec e
        jr nz,spl_rp
spl_nx: ld a,d
        cp SPLASH_Y+SPLASH_H
        jr c,spl_lp
        ret
; spl_put : A -> (IX), octet suivant ; fin de ligne : ligne D + 1
spl_put:
        ld (ix+0),a
        inc ix
        dec b
        ret nz
        inc d
; spl_dst : IX = ligne D, octet SPLASH_X ; B = SPLASH_W ; C conserve
spl_dst:
        push hl
        ld a,d
        call spl_addr
        ld a,l                          ; HL + SPLASH_X (C = octet d'une repetition : garde)
        add a,SPLASH_X
        ld l,a
        adc a,h
        sub l
        ld h,a
        push hl
        pop ix
        pop hl
        ld b,SPLASH_W
        ret

; spl_texts : HL = table : x (pixel, pair), y, encre, caracteres..., &FF ;
;   &FF = fin. Caractere = numero dans splash_font (8 x 16), &FE = espace.
;   Les pixels eteints du caractere laissent voir le fond.
spl_texts:
        ld a,(hl)
        cp #FF
        ret z
        inc hl
        rra                             ; x / 2 = octet de depart
        and #7F
        ld (spl_tx),a
        ld a,(hl)
        inc hl
        ld (spl_ty),a
        ld a,(hl)                       ; encre -> bits du pixel de gauche
        inc hl
        ld c,0
        rra
        rr c                            ; bit 0 -> bit 7
        ld e,a
        and 1
        jr z,spl_i1
        set 3,c                         ; bit 1 -> bit 3
spl_i1: ld a,e
        rra
        ld e,a
        and 1
        jr z,spl_i2
        set 5,c                         ; bit 2 -> bit 5
spl_i2: ld a,e
        rra
        and 1
        jr z,spl_i3
        set 1,c                         ; bit 3 -> bit 1
spl_i3: ld a,c
        ld (spl_ink),a
spl_ch: ld a,(hl)
        inc hl
        cp #FF
        jr z,spl_texts
        cp #FE
        jr z,spl_sp
        push hl
        ld l,a                          ; caractere : 16 octets
        ld h,0
        add hl,hl
        add hl,hl
        add hl,hl
        add hl,hl
        ld de,splash_font
        add hl,de
        ex de,hl                        ; DE = dessin du caractere
        ld a,(spl_ty)
        ld (spl_row),a
        ld b,16
spl_r:  push bc
        ld a,(spl_row)
        call spl_addr
        ld a,(spl_tx)
        ld c,a
        ld b,0
        add hl,bc                       ; HL = ecran
        ld a,(de)                       ; 8 pixels = 4 octets
        inc de
        ld b,4
spl_px: push bc
        ld c,a                          ; C = pixels restants
        ld b,0                          ; B = bits a poser, masque dans spl_m
        xor a
        ld (spl_m),a
        bit 7,c
        jr z,spl_p1
        ld a,(spl_ink)
        ld b,a
        ld a,#AA
        ld (spl_m),a
spl_p1: bit 6,c
        jr z,spl_p2
        ld a,(spl_ink)
        rrca                            ; pixel de droite = bits decales de 1
        and #55
        or b
        ld b,a
        ld a,(spl_m)
        or #55
        ld (spl_m),a
spl_p2: ld a,(spl_m)
        cpl
        and (hl)
        or b
        ld (hl),a
        inc hl
        ld a,c
        add a,a
        add a,a                         ; 2 pixels suivants
        pop bc
        djnz spl_px
        ld hl,spl_row
        inc (hl)
        pop bc
        djnz spl_r
        pop hl
spl_sp: ld a,(spl_tx)                   ; caractere suivant : 4 octets plus loin
        add a,4
        ld (spl_tx),a
        jr spl_ch

        include "splash_data.asm"
