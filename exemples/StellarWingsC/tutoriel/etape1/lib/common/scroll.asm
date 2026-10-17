;=============================================================================
; lib/common/scroll.asm - defilement horizontal d'une carte plus large que
; l'ecran (GX Studio) : CPC, CPC+, GX4000
;
; Principe : l'ecran n'est jamais redessine en entier. On deplace l'adresse de
; debut d'ecran du CRTC (registres 12/13) d'un "caractere" (2 octets = 8 pixels
; mode 1 = 4 pixels mode 0) et on dessine seulement la colonne qui apparait.
; Sur CPC+/GX4000, le soft-scroll de l'ASIC (&6804) decale en plus l'image de
; 0 a 15 pixels mode 2 : le defilement est fluide, au pixel pres. Le bord
; gauche est alors elargi d'un caractere (bit 7) pour cacher la colonne qui
; se dessine.
;
; La memoire ecran devient "circulaire" (chaque bloc de 2 Ko boucle sur
; lui-meme) : pour dessiner a l'ecran pendant un defilement, utiliser scr_xy
; (adresse d'un octet) et non line_addr directement.
;
; Requis : lib/common/tiles.asm (tiles_use deja appele), line_addr,
;          variable scroll_vars (16 octets). Tuiles de 8 pixels de large
;          en mode 1 (1 caractere) ou 8/16 pixels en mode 0 (2/4 caracteres).
; Coordonnees : camera et sprites en pixels "mode 2" (16 par caractere).
;=============================================================================
scr_off   equ scroll_vars               ; debut d'ecran (caracteres, 0-1023)
scr_off2  equ scroll_vars+2             ; le meme en octets (0-2047)
scr_char  equ scroll_vars+4             ; caractere de la carte en haut a gauche
scr_map   equ scroll_vars+6             ; carte (MAP_NOM)
scr_mapw  equ scroll_vars+8             ; largeur de la carte (cases)
scr_rows  equ scroll_vars+9             ; rangees de tuiles dessinees
scr_shift equ scroll_vars+10            ; decalage fin (CPC+ : 0-15 pixels mode 2)
scr_cw    equ scroll_vars+11            ; caracteres par tuile
scr_max   equ scroll_vars+12            ; dernier caractere de depart possible
scr_tmp   equ scroll_vars+14

SCR_COLS  equ 40                        ; largeur de l'ecran en caracteres

; scroll_init : HL = carte (MAP_NOM), D = largeur (cases), E = rangees a afficher
;   dessine le debut de la carte (camera en 0) ; tiles_use doit avoir ete appele
scroll_init:
        ld (scr_map),hl
        ld a,d
        ld (scr_mapw),a
        ld a,e
        ld (scr_rows),a
        ld a,(tl_w)                     ; caracteres par tuile = largeur / 2
        srl a
        jr nz,sci_cw
        inc a
sci_cw:  ld (scr_cw),a
        ld b,a                          ; dernier depart = largeur x cw - 40
        ld hl,0
        ld a,(scr_mapw)
        ld e,a
        ld d,0
sci_m:   add hl,de
        djnz sci_m
        ld de,SCR_COLS
        or a
        sbc hl,de
        jr nc,sci_mx
        ld hl,0                         ; carte plus etroite que l'ecran
sci_mx:  ld (scr_max),hl
        ld hl,0
        ld (scr_off),hl
        ld (scr_off2),hl
        ld (scr_char),hl
        xor a
        ld (scr_shift),a
        ld b,0                          ; dessine les 40 colonnes
sci_c:   push bc
        ld a,b
        ld l,b
        ld h,0
        call scroll_column
        pop bc
        inc b
        ld a,b
        cp SCR_COLS
        jr nz,sci_c
        jp scroll_apply

; scroll_to : HL = position de la camera (pixels mode 2) -> avance d'une colonne
;   au plus par appel (appeler a chaque image)
scroll_to:
        bit 7,h                         ; bornes : 0 .. scr_max x 16
        jr z,sct_p
        ld hl,0
sct_p:   push hl
        ld hl,(scr_max)
        add hl,hl
        add hl,hl
        add hl,hl
        add hl,hl
        ex de,hl                        ; DE = maximum
        pop hl
        push hl
        or a
        sbc hl,de
        pop hl
        jr c,sct_in
        ex de,hl
sct_in:
        if ASIC
        ld de,15                        ; CPC+ : caractere = (X + 15) / 16, decalage fin
        add hl,de
        endif
        push hl
        srl h
        rr l
        srl h
        rr l
        srl h
        rr l
        srl h
        rr l                            ; HL = caractere vise
        if ASIC
        pop de
        ld a,e
        and 15
        cpl
        and 15                          ; decalage = 15 - ((X+15) mod 16)
        ld (scr_shift),a
        else
        pop de
        endif
        ld de,(scr_char)
        or a
        sbc hl,de
        ret z                           ; deja en place
        jr c,scroll_left
; scroll_right : un caractere vers la droite (nouvelle colonne a droite)
scroll_right:
        ld hl,(scr_char)
        ld de,(scr_max)
        or a
        sbc hl,de
        ret nc
        ld hl,(scr_char)                ; colonne qui apparait : caractere + 40
        ld de,SCR_COLS
        add hl,de
        ld a,SCR_COLS                   ; position ecran 40 (juste a droite)
        call scroll_column
        ld hl,(scr_char)
        inc hl
        ld (scr_char),hl
        ld hl,(scr_off)
        inc hl
        jr scroll_set
; scroll_left : un caractere vers la gauche
scroll_left:
        ld hl,(scr_char)
        ld a,h
        or l
        ret z
        dec hl
        ld (scr_char),hl
        ld hl,(scr_off)
        dec hl
        call scroll_set
        ld hl,(scr_char)
        xor a                           ; nouvelle colonne en position 0
        jp scroll_column
scroll_set:
        ld a,h
        and 3
        ld h,a
        ld (scr_off),hl
        add hl,hl
        ld (scr_off2),hl
        ret

; scroll_apply : envoie le debut d'ecran au CRTC (et le decalage fin sur CPC+).
;   A appeler juste apres wait_frame.
scroll_apply:
        ld hl,(scr_off)
        ld bc,CRTC_SEL+12
        out (c),c
        ld a,h
        or #30                          ; ecran en &C000
        inc b
        out (c),a
        dec b
        inc c
        out (c),c
        inc b
        out (c),l
        if ASIC
        ld a,(scr_shift)
        or #80                          ; bord gauche elargi : cache la colonne en cours
        ld (ASIC_SSCR),a
        endif
        ret

; scroll_column : HL = caractere de la carte, A = position a l'ecran (0-40)
scroll_column:
        add a,a
        ld (scr_tmp),a                  ; colonne ecran en octets
        ld a,(scr_cw)                   ; tuile = HL / cw, octet dans la tuile = reste x 2
        ld c,a
        ld b,0
        ld de,0
scc_d:   or a
        sbc hl,bc
        jr c,scc_dd
        inc de
        jr scc_d
scc_dd:  add hl,bc
        ld a,l
        add a,a
        ld (scr_tmp+1),a                ; octet dans la tuile
        ld hl,(scr_map)
        add hl,de                       ; HL = case de la rangee 0
        ld c,0                          ; C = ligne ecran
        ld a,(scr_rows)
        ld b,a
scc_r:   push bc                         ; B = rangees restantes, C = ligne
        push hl                         ; case de la carte
        ld a,(hl)
        call sc_gfx                     ; HL = graphismes de la tuile (+ octet)
        ld a,(tl_h)
scc_l:   push af
        push hl
        ld a,(scr_tmp)
        ld b,a
        call scr_xy                     ; HL = ecran (colonne B, ligne C)
        ex de,hl
        pop hl
        ld a,(hl)                       ; 2 octets = un caractere (jamais coupe)
        ld (de),a
        inc hl
        inc de
        ld a,(hl)
        ld (de),a
        dec hl
        ld a,(tl_w)                     ; ligne suivante de la tuile
        add a,l
        ld l,a
        adc a,h
        sub l
        ld h,a
        inc c
        pop af
        dec a
        jr nz,scc_l
        pop hl                          ; rangee suivante de la carte
        ld a,(scr_mapw)
        add a,l
        ld l,a
        adc a,h
        sub l
        ld h,a
        pop bc
        ld a,(tl_h)
        add a,c
        ld c,a
        djnz scc_r
        ret

; sc_gfx : A = tuile -> HL = tl_gfx + tuile x taille + octet de la colonne
sc_gfx:
        push bc
        ld hl,0
        ld de,(tl_size)
        ld b,8
scg_m:   add hl,hl
        rla
        jr nc,scg_n
        add hl,de
scg_n:   djnz scg_m
        ld de,(tl_gfx)
        add hl,de
        ld a,(scr_tmp+1)
        ld e,a
        ld d,0
        add hl,de
        pop bc
        ret

; scr_xy : B = colonne (octets, 0-81), C = ligne -> HL = adresse ecran,
;   en tenant compte du defilement (memoire circulaire par bloc de 2 Ko)
scr_xy:
        push de
        ld l,c
        ld h,0
        add hl,hl
        ld de,line_addr
        add hl,de
        ld e,(hl)
        inc hl
        ld d,(hl)                       ; DE = adresse de la ligne sans defilement
        ld hl,(scr_off2)
        ld a,b
        add a,l
        ld l,a
        adc a,h
        sub l
        ld h,a
        add hl,de
        ld a,h                          ; garde le bloc (bits 11-15) de la ligne
        and 7
        ld h,a
        ld a,d
        and #F8
        or h
        ld h,a
        pop de
        ret
