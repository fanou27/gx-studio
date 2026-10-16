;=============================================================================
; lib/common/scroll8.asm - defilement dans les 8 directions d'une carte plus
; grande que l'ecran (GX Studio) : CPC, CPC+, GX4000
;
; A utiliser A LA PLACE de lib/common/scroll.asm (memes noms scr_off, scr_off2,
; scr_xy : les sprites logiciels du CPC suivent le defilement).
;
; Principe : comme scroll.asm, on deplace le debut d'ecran du CRTC (registres
; 12/13) et on ne dessine que ce qui apparait : une colonne de caracteres a
; droite ou a gauche, une rangee de caracteres (8 lignes) en haut ou en bas.
; L'adresse avance de 1 par caractere et de 40 par rangee ; chaque bloc de
; 2 Ko de la memoire ecran boucle sur lui-meme.
;
; Organisation de l'ecran : 40 caracteres x 24 rangees visibles (192 lignes,
; registre 6 du CRTC = 24). Une 25e rangee, cachee, recoit le decor qui va
; apparaitre : 25 x 40 = 1000 caracteres, le bloc en contient 1024.
;
; Sur CPC+/GX4000, le soft-scroll de l'ASIC (&6804) affine au pixel pres :
;   horizontal 0-15 pixels mode 2 (bord gauche elargi pour cacher la colonne
;   qui se dessine), vertical 0-7 lignes (la 25e rangee devient visible en bas).
; Sur CPC, le defilement se fait par caractere (16 pixels mode 2) et par rangee
; (8 lignes) : pour placer les sprites, utiliser s8_camx / s8_camy (camera
; reellement affichee) plutot que la camera demandee.
;
; Requis : lib/common/tiles.asm (tiles_use deja appele), variable scroll_vars
;          (40 octets). Tuiles de 1, 2 ou 4 caracteres de large (mode 1 :
;          8, 16 ou 32 pixels ; mode 0 : 4, 8 ou 16 pixels) et de 8 ou 16
;          lignes. Carte : 255 rangees de caracteres au plus.
; Coordonnees : camera X en pixels "mode 2" (16 par caractere), Y en lignes.
;=============================================================================
scr_off   equ scroll_vars               ; debut d'ecran (caracteres, 0-1023)
scr_off2  equ scroll_vars+2             ; le meme en octets (0-2047)
s8_cx     equ scroll_vars+4             ; colonne de caracteres de la carte a gauche de l'ecran
s8_map    equ scroll_vars+6             ; carte (MAP_NOM)
s8_mapw   equ scroll_vars+8             ; largeur de la carte (cases)
s8_cy     equ scroll_vars+9             ; rangee de caracteres de la carte en haut de l'ecran
s8_hs     equ scroll_vars+10            ; decalage fin horizontal (CPC+ : 0-15)
s8_vs     equ scroll_vars+11            ; decalage fin vertical (CPC+ : 0-7)
s8_maxx   equ scroll_vars+12            ; s8_cx maximum
s8_maxy   equ scroll_vars+14            ; s8_cy maximum
s8_nrows   equ scroll_vars+15            ; rangees de caracteres de la carte
s8_ncols   equ scroll_vars+16            ; colonnes de caracteres de la carte
s8_cws    equ scroll_vars+18            ; log2(caracteres par tuile en largeur)
s8_rhs    equ scroll_vars+19            ; log2(rangees par tuile en hauteur)
s8_szs    equ scroll_vars+20            ; log2(octets par tuile)
s8_camx   equ scroll_vars+21            ; camera affichee : X (pixels mode 2)
s8_camy   equ scroll_vars+23            ;                   Y (lignes)
s8_todo   equ scroll_vars+25            ; bit 0 : colonne a dessiner, bit 1 : rangee
s8_dcol   equ scroll_vars+26            ; colonne ecran a dessiner
s8_drow   equ scroll_vars+27            ; rangee ecran a dessiner
s8_t0     equ scroll_vars+28            ; (8 octets de travail)

S8_COLS   equ 40                        ; caracteres visibles en largeur
S8_ROWS   equ 24                        ; rangees visibles (+ 1 cachee)
SCR_COLS  equ S8_COLS

; scroll8_init : HL = carte (MAP_NOM), D = largeur, E = hauteur (cases)
;   regle l'ecran (24 rangees) et dessine le coin haut gauche de la carte
scroll8_init:
        ld (s8_map),hl
        ld a,d
        ld (s8_mapw),a
        push de
        ld a,(tl_w)                     ; caracteres par tuile = tl_w / 2 -> log2
        srl a
        call s8_log2
        ld (s8_cws),a
        ld a,(tl_h)                     ; rangees par tuile = tl_h / 8 -> log2
        rrca
        rrca
        rrca
        and 31
        call s8_log2
        ld (s8_rhs),a
        ld hl,(tl_size)                 ; log2(taille) (puissance de 2)
        ld c,0
s8i_s:  ld a,h
        or a
        jr nz,s8i_s1
        ld a,l
        cp 2
        jr c,s8i_s2
s8i_s1: srl h
        rr l
        inc c
        jr s8i_s
s8i_s2: ld a,c
        ld (s8_szs),a
        pop de
        ld a,(s8_rhs)                   ; rangees de la carte = hauteur << rhs
        ld b,a
        ld a,e
        inc b
        jr s8i_r1
s8i_r:  add a,a
s8i_r1: djnz s8i_r
        ld (s8_nrows),a
        sub S8_ROWS                     ; s8_cy maximum
        jr nc,s8i_my
        xor a
s8i_my: ld (s8_maxy),a
        ld a,(s8_mapw)                  ; colonnes de la carte = largeur << cws
        ld l,a
        ld h,0
        ld a,(s8_cws)
        inc a
        jr s8i_c1
s8i_c:  add hl,hl
s8i_c1: dec a
        jr nz,s8i_c
        ld (s8_ncols),hl
        ld de,S8_COLS
        or a
        sbc hl,de
        jr nc,s8i_mx
        ld hl,0
s8i_mx: ld (s8_maxx),hl
        ld hl,0
        ld (scr_off),hl
        ld (scr_off2),hl
        ld (s8_cx),hl
        ld (s8_camx),hl
        ld (s8_camy),hl
        xor a
        ld (s8_cy),a
        ld (s8_hs),a
        ld (s8_vs),a
        ld (s8_todo),a
        ld bc,CRTC_SEL+6                ; 24 rangees visibles (192 lignes)
        out (c),c
        inc b
        ld a,S8_ROWS
        out (c),a
        xor a                           ; dessine les 25 rangees
s8i_d:  push af
        call s8_row
        pop af
        inc a
        cp S8_ROWS+1
        jr nz,s8i_d
        jp scroll8_apply

; s8_log2 : A = 1, 2 ou 4 -> A = 0, 1 ou 2
s8_log2:
        ld c,0
s8l_l:  cp 2
        jr c,s8l_e
        srl a
        inc c
        jr s8l_l
s8l_e:  ld a,c
        ret

; s8_target : HL = X, DE = Y -> s8_t0 = colonne visee, s8_t0+2 = rangee visee,
;   s8_hs / s8_vs = decalages fins (CPC+), camera bornee a la carte
s8_target:
        push de
        bit 7,h                         ; X : 0 .. maxx x 16
        jr z,s8g_x0
        ld hl,0
s8g_x0: push hl
        ld hl,(s8_maxx)
        add hl,hl
        add hl,hl
        add hl,hl
        add hl,hl
        ex de,hl
        pop hl
        call s8_min                     ; HL = min(HL, DE)
        if ASIC
        ld de,15                        ; CPC+ : caractere = (X + 15) / 16, decalage fin
        add hl,de
        ld a,l
        and 15
        cpl
        and 15
        ld (s8_hs),a                    ; decalage = 15 - ((X+15) mod 16)
        endif
        srl h
        rr l
        srl h
        rr l
        srl h
        rr l
        srl h
        rr l                            ; HL = colonne visee
        ld (s8_t0),hl
        pop hl                          ; Y : 0 .. maxy x 8
        bit 7,h
        jr z,s8g_y0
        ld hl,0
s8g_y0: ld a,(s8_maxy)
        ld e,a
        ld d,0
        ex de,hl
        add hl,hl
        add hl,hl
        add hl,hl
        ex de,hl
        call s8_min
        if ASIC
        ld a,l
        and 7
        ld (s8_vs),a
        endif
        srl h
        rr l
        srl h
        rr l
        srl h
        rr l
        ld a,l
        ld (s8_t0+2),a                  ; rangee visee
        ret

; scroll8_jump : HL = camera X, DE = camera Y : place la camera directement
;   (debut de niveau, teleportation) et redessine tout l'ecran
scroll8_jump:
        call s8_target
        ld hl,(s8_t0)
        ld (s8_cx),hl
        ld a,(s8_t0+2)
        ld (s8_cy),a
        call s8_cam
        xor a
        ld (s8_todo),a
s8j_d:  push af                         ; les 25 rangees
        call s8_row
        pop af
        inc a
        cp S8_ROWS+1
        jr nz,s8j_d
        ret

; scroll8_to : HL = camera X (pixels mode 2), DE = camera Y (lignes)
;   avance d'une colonne et/ou d'une rangee au plus par appel (a chaque image)
scroll8_to:
        call s8_target
        ; --- horizontal
        ld hl,(s8_t0)
        ld de,(s8_cx)
        or a
        sbc hl,de
        jr z,s8t_v
        jr c,s8t_l
        inc de                          ; vers la droite : nouvelle colonne a droite
        ld (s8_cx),de
        ld hl,(scr_off)
        inc hl
        call s8_setoff
        ld a,S8_COLS-1
        jr s8t_hc
s8t_l:  dec de                          ; vers la gauche : nouvelle colonne a gauche
        ld (s8_cx),de
        ld hl,(scr_off)
        dec hl
        call s8_setoff
        xor a
s8t_hc: ld (s8_dcol),a
        ld hl,s8_todo
        set 0,(hl)
        ; --- vertical
s8t_v:  ld a,(s8_cy)
        ld b,a
        ld a,(s8_t0+2)
        cp b
        jr z,s8t_d
        jr c,s8t_u
        inc b                           ; vers le bas : nouvelle rangee en bas (cachee)
        ld a,b
        ld (s8_cy),a
        ld hl,(scr_off)
        ld de,S8_COLS
        add hl,de
        call s8_setoff
        ld a,S8_ROWS
        jr s8t_vc
s8t_u:  dec b                           ; vers le haut : nouvelle rangee en haut
        ld a,b
        ld (s8_cy),a
        ld hl,(scr_off)
        ld de,-S8_COLS
        add hl,de
        call s8_setoff
        xor a
s8t_vc: ld (s8_drow),a
        ld hl,s8_todo
        set 1,(hl)
s8t_d:  call s8_cam
        ; --- dessin de ce qui apparait (avec les nouvelles positions)
        ld hl,s8_todo
        bit 0,(hl)
        jr z,s8t_nc
        res 0,(hl)
        ld a,(s8_dcol)
        call s8_col
s8t_nc: ld hl,s8_todo
        bit 1,(hl)
        ret z
        res 1,(hl)
        ld a,(s8_drow)
        jp s8_row

; s8_cam : camera affichee (s8_camx, s8_camy) d'apres cx, cy et les decalages fins
s8_cam:
        ld hl,(s8_cx)
        add hl,hl
        add hl,hl
        add hl,hl
        add hl,hl
        if ASIC
        ld a,(s8_hs)                    ; X = cx x 16 - 15 + (15 - hs)
        ld e,a
        ld d,0
        or a
        sbc hl,de
        endif
        ld (s8_camx),hl
        ld a,(s8_cy)
        ld l,a
        ld h,0
        add hl,hl
        add hl,hl
        add hl,hl
        if ASIC
        ld a,(s8_vs)
        ld e,a
        ld d,0
        add hl,de
        endif
        ld (s8_camy),hl
        ret

; HL = min(HL, DE) (non signes)
s8_min:
        push hl
        or a
        sbc hl,de
        pop hl
        ret c
        ex de,hl
        ret

s8_setoff:
        ld a,h
        and 3
        ld h,a
        ld (scr_off),hl
        add hl,hl
        ld (scr_off2),hl
        ret

; scroll8_apply : envoie le debut d'ecran au CRTC (et le decalage fin sur CPC+).
;   A appeler juste apres wait_frame.
scroll8_apply:
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
        ld a,(s8_vs)
        add a,a
        add a,a
        add a,a
        add a,a
        ld b,a
        ld a,(s8_hs)
        or b
        or #80                          ; bord gauche elargi : cache la colonne en cours
        ld (ASIC_SSCR),a
        endif
        ret

; s8_word : A = rangee ecran (0-24), C = colonne ecran (0-39) -> HL = adresse
;   (&C000 + 2 x ((debut + rangee x 40 + colonne) mod 1024)), ligne 0 du caractere
s8_word:
        ld l,a
        ld h,0
        add hl,hl
        add hl,hl
        add hl,hl                       ; x 8
        ld d,h
        ld e,l
        add hl,hl
        add hl,hl                       ; x 32
        add hl,de                       ; x 40
        ld e,c
        ld d,0
        add hl,de
        ld de,(scr_off)
        add hl,de
        add hl,hl                       ; en octets
        ld a,h
        and 7                           ; mod 2048
        or #C0
        ld h,a
        ret

; s8_put : DE = graphismes (2 octets x 8 lignes, pas = tl_w), HL = ecran
s8_put:
        ld a,(tl_w)
        ld c,a
        ld b,8
s8p_l:  ld a,(de)
        ld (hl),a
        inc de
        inc l                           ; adresse paire : pas de retenue
        ld a,(de)
        ld (hl),a
        dec de
        dec l
        ld a,e
        add a,c
        ld e,a
        jr nc,s8p_n
        inc d
s8p_n:  ld a,h
        add a,8                         ; ligne suivante du caractere (bloc de 2 Ko suivant)
        ld h,a
        djnz s8p_l
        ret

; s8_tile : A = numero de tuile, DE = decalage dans la tuile -> DE = graphismes
s8_tile:
        ld l,a
        ld h,0
        ld a,(s8_szs)
        or a
        jr z,s8tl_n
        ld b,a
s8tl_m: add hl,hl
        djnz s8tl_m
s8tl_n: add hl,de
        ld de,(tl_gfx)
        add hl,de
        ex de,hl
        ret

; s8_sub : A = rangee de caracteres de la carte -> A = decalage de la rangee
;   dans la tuile (sous-rangee x 8 x tl_w)
s8_sub:
        ld b,a
        ld a,(s8_rhs)
        or a
        ret z                           ; tuile de 8 lignes : decalage 0
        ld a,b
        and 1
        ret z
        ld a,(tl_w)                     ; 8 x tl_w
        add a,a
        add a,a
        add a,a
        ret

; s8_maprow : A = rangee de caracteres de la carte -> HL = debut de la rangee de tuiles
s8_maprow:
        ld b,a
        ld a,(s8_rhs)
        or a
        ld a,b
        jr z,s8m_1
        srl a
s8m_1:  ld hl,0                         ; HL = A x largeur
        ld de,(s8_mapw)
        ld d,0
        ld b,8
s8m_m:  add hl,hl
        rla
        jr nc,s8m_n
        add hl,de
s8m_n:  djnz s8m_m
        ld de,(s8_map)
        add hl,de
        ret

; s8_row : A = rangee ecran (0-24) : dessine les 40 caracteres de cette rangee
s8_row:
        ld (s8_t0),a                    ; rangee ecran
        ld b,a
        ld a,(s8_cy)
        add a,b                         ; rangee de la carte
        ld b,a
        ld a,(s8_nrows)
        dec a
        cp b
        ret c                           ; hors de la carte
        ld a,b
        push af
        call s8_sub
        ld (s8_t0+1),a                  ; decalage de la rangee dans la tuile
        pop af
        call s8_maprow
        ld (s8_t0+2),hl                 ; rangee de tuiles dans la carte
        ld c,0                          ; C = colonne ecran
s8r_l:  push bc
        ld hl,(s8_cx)                   ; colonne de la carte = cx + C
        ld b,0
        add hl,bc
        ld de,(s8_ncols)
        push hl
        or a
        sbc hl,de
        pop hl
        jr nc,s8r_x                     ; hors de la carte : fin de la rangee
        ld a,(s8_cws)                   ; tuile = colonne >> cws, reste = colonne mod cw
        ld b,a
        ld a,l
        and 3
        ld e,a                          ; (reste calcule ci-dessous)
        ld a,b
        or a
        jr z,s8r_1
s8r_s:  srl h
        rr l
        djnz s8r_s
s8r_1:  ld a,(s8_cws)                   ; reste = colonne & (cw - 1)
        or a
        jr z,s8r_r0
        cp 1
        ld a,e
        jr nz,s8r_r2
        and 1
        jr s8r_r
s8r_r2: and 3
        jr s8r_r
s8r_r0: xor a
s8r_r:  add a,a                         ; x 2 octets
        ld e,a
        ld a,(s8_t0+1)
        add a,e
        ld e,a
        ld d,0                          ; DE = decalage dans la tuile
        push de
        ld de,(s8_t0+2)
        add hl,de
        ld a,(hl)                       ; numero de tuile
        pop de
        call s8_tile                    ; DE = graphismes
        pop bc
        push bc
        push de
        ld a,(s8_t0)
        call s8_word                    ; HL = ecran
        pop de
        call s8_put
        pop bc
        inc c
        ld a,c
        cp S8_COLS
        jr nz,s8r_l
        ret
s8r_x:  pop bc
        ret

; s8_col : A = colonne ecran (0-39) : dessine les 25 caracteres de cette colonne
s8_col:
        ld (s8_t0+3),a                  ; colonne ecran
        ld c,a
        ld b,0
        ld hl,(s8_cx)
        add hl,bc                       ; colonne de la carte
        ld de,(s8_ncols)
        push hl
        or a
        sbc hl,de
        pop hl
        ret nc                          ; hors de la carte
        ld a,l                          ; reste = colonne & (cw - 1), x 2
        ld e,a
        ld a,(s8_cws)
        or a
        jr z,s8c_r0
        ld b,a
        cp 1
        ld a,e
        jr nz,s8c_r2
        and 1
        jr s8c_r
s8c_r2: and 3
        jr s8c_r
s8c_r0: xor a
        ld b,a
s8c_r:  add a,a
        ld (s8_t0+1),a                  ; octet dans la tuile
        ld a,b                          ; colonne de tuile = colonne >> cws
        or a
        jr z,s8c_t
s8c_s:  srl h
        rr l
        djnz s8c_s
s8c_t:  ld (s8_t0+4),hl                 ; colonne de tuile
        xor a                           ; A = rangee ecran
s8c_l:  ld (s8_t0),a
        ld b,a
        ld a,(s8_cy)
        add a,b                         ; rangee de la carte
        ld b,a
        ld a,(s8_nrows)
        dec a
        cp b
        ret c                           ; hors de la carte
        ld a,b
        push af
        call s8_sub
        ld e,a
        ld a,(s8_t0+1)
        add a,e
        ld e,a
        ld d,0
        pop af
        push de                         ; decalage dans la tuile
        call s8_maprow                  ; HL = rangee de tuiles
        ld de,(s8_t0+4)
        add hl,de
        ld a,(hl)                       ; tuile
        pop de
        call s8_tile                    ; DE = graphismes
        push de
        ld a,(s8_t0+3)
        ld c,a
        ld a,(s8_t0)
        call s8_word
        pop de
        call s8_put
        ld a,(s8_t0)
        inc a
        cp S8_ROWS+1
        jr nz,s8c_l
        ret

; scr_xy : B = colonne (octets, 0-81), C = ligne -> HL = adresse ecran,
;   en tenant compte du defilement (memoire circulaire par bloc de 2 Ko).
;   Sur CPC+, la ligne C s'affiche a la ligne C - s8_vs (soft-scroll vertical).
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
