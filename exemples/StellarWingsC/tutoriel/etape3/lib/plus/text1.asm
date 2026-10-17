;=============================================================================
; lib/text1.asm - texte en mode 1 (40 colonnes) avec une police 8x8 d'un bit
; La police (ASCII 32-95) est en ROM, page PAGE_DATA, au label FONT.
;=============================================================================

; set_ink : A = encre (0-3) -> table des 16 motifs de 4 pixels
set_ink:
        ld c,a
        and 1
        rrca                            ; bit 7
        ld b,a
        ld a,c
        and 2
        add a,a
        add a,a                         ; bit 3
        or b
        ld b,a                          ; B = motif du pixel 0
        ld hl,txt_tbl
        ld c,0                          ; C = quartet 0..15
si_l:   push bc
        ld e,0
        ld a,c
        ld d,4
si_p:   rla                             ; bits du quartet : 3 = pixel de gauche
        bit 4,a
        jr z,si_n
        push af
        ld a,e
        or b
        ld e,a
        pop af
si_n:   srl b
        dec d
        jr nz,si_p
        ld (hl),e
        inc hl
        pop bc
        inc c
        ld a,c
        cp 16
        jr nz,si_l
        ret

; text_at : B = colonne (0-39), C = ligne (0-199), DE = chaine (0 final)
text_at:
        ld a,(de)
        or a
        ret z
        push de
        push bc
        call draw_char
        pop bc
        pop de
        inc de
        inc b
        jr text_at

; draw_char : A = caractere, B = colonne, C = ligne
draw_char:
        sub 32
        ld l,a
        ld h,0
        add hl,hl
        add hl,hl
        add hl,hl
        ld de,FONT
        add hl,de
        ex de,hl                        ; DE = glyphe
        ld h,8
dc_l:   push hl
        push bc
        ld l,c
        ld h,0
        add hl,hl
        push de
        ld de,line_addr
        add hl,de
        ld a,(hl)
        inc hl
        ld h,(hl)
        ld l,a
        ld a,b
        add a,a
        add a,l
        ld l,a
        adc a,h
        sub l
        ld h,a                          ; HL = adresse ecran
        pop de
        ld a,(de)
        inc de
        push de
        ld c,a
        rrca
        rrca
        rrca
        rrca
        and 15
        ld e,a
        ld d,0
        push hl
        ld hl,txt_tbl
        add hl,de
        ld a,(hl)
        pop hl
        ld (hl),a
        inc hl
        ld a,c
        and 15
        ld e,a
        push hl
        ld hl,txt_tbl
        add hl,de
        ld a,(hl)
        pop hl
        ld (hl),a
        pop de
        pop bc
        inc c
        pop hl
        dec h
        jr nz,dc_l
        ret

; clear_lines : C = premiere ligne, B = nombre de lignes (efface toute la largeur)
clear_lines:
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
cl_b:   ld (hl),0
        inc hl
        djnz cl_b
        pop bc
        inc c
        djnz clear_lines
        ret

; blit : HL = source (ROM page PAGE_DATA), B = largeur (octets), C = hauteur,
;        D = colonne (octet 0-79), E = ligne ecran
blit:
        push bc
        push de
        push hl
        ld l,e
        ld h,0
        add hl,hl
        ld bc,line_addr
        add hl,bc
        ld a,(hl)
        inc hl
        ld h,(hl)
        add a,d
        ld l,a
        adc a,h
        sub l
        ld d,a
        ld e,l                          ; DE = adresse ecran de la ligne
        pop hl                          ; HL = source
        pop af                          ; A = colonne (ignoree), F = ligne
        pop bc                          ; B = largeur, C = hauteur
        push bc
        push af
        ld c,b
        ld b,0
        ldir                            ; une ligne
        pop de                          ; D = colonne, E = ligne
        inc e
        pop bc
        dec c
        jr nz,blit
        ret
