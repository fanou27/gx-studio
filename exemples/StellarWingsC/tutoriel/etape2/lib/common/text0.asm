;=============================================================================
; lib/common/text0.asm - texte en mode 0 (20 colonnes), police 8x8 d'un bit (FONT)
; Requis : FONT, line_addr, variable txt0_tbl (4 octets)
;=============================================================================

; set_ink0 : A = encre (0-15) -> table des 4 octets (paires de pixels)
set_ink0:
        ld c,0
        rra
        rr c                            ; bit 0 -> bit 7
        rra
        jr nc,si0_1
        set 3,c                         ; bit 1 -> bit 3
si0_1:  rra
        jr nc,si0_2
        set 5,c                         ; bit 2 -> bit 5
si0_2:  rra
        jr nc,si0_3
        set 1,c                         ; bit 3 -> bit 1
si0_3:  ld hl,txt0_tbl
        ld (hl),0                       ; 00
        inc hl
        ld a,c
        srl a
        ld (hl),a                       ; 01 : pixel de droite
        inc hl
        ld (hl),c                       ; 10 : pixel de gauche
        inc hl
        or c
        ld (hl),a                       ; 11
        ret

; text0_at : B = colonne (0-19), C = ligne (0-199), DE = chaine (0 final)
text0_at:
        ld a,(de)
        or a
        ret z
        push de
        push bc
        call draw_char0
        pop bc
        pop de
        inc de
        inc b
        jr text0_at

; draw_char0 : A = caractere, B = colonne, C = ligne
draw_char0:
        sub 32
        ld l,a
        ld h,0
        add hl,hl
        add hl,hl
        add hl,hl
        ld de,FONT
        add hl,de
        push hl                         ; glyphe
        ld l,c
        ld h,0
        add hl,hl
        ld de,line_addr
        add hl,de
        ld a,(hl)
        inc hl
        ld h,(hl)
        ld l,a
        ld a,b
        add a,a
        add a,a
        add a,l
        ld l,a
        adc a,h
        sub l
        ld h,a
        ex de,hl                        ; DE = ecran
        pop hl
        ld c,8
dc0_r:  ld a,(hl)
        inc hl
        push hl
        push de
        ld b,4
dc0_c:  rlca
        rlca
        push af
        and 3
        ld l,a
        ld h,0
        push de
        ld de,txt0_tbl
        add hl,de
        pop de
        ld a,(hl)
        ld (de),a
        inc de
        pop af
        djnz dc0_c
        pop de
        ld a,d                          ; ligne suivante (ecran en &C000)
        add a,8
        ld d,a
        jr nc,dc0_n
        ld a,e
        add a,#50
        ld e,a
        ld a,d
        adc a,#C0
        ld d,a
dc0_n:  pop hl
        dec c
        jr nz,dc0_r
        ret

; clear_lines0 : C = premiere ligne, B = nombre de lignes
clear_lines0:
        push bc
        ld l,c
        ld h,0
        add hl,hl
        ld de,line_addr
        add hl,de
        ld a,(hl)
        inc hl
        ld h,(hl)
        ld l,a
        ld b,80
cl0_b:  ld (hl),0
        inc hl
        djnz cl0_b
        pop bc
        inc c
        djnz clear_lines0
        ret
