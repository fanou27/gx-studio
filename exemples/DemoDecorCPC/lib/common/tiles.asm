;=============================================================================
; lib/common/tiles.asm - decors en tuiles (GX Studio) : CPC, CPC+, GX4000
; Les tuiles (TILES_NOM) et les cartes (MAP_NOM) sont produites par l'editeur
; de decors. Une tuile = TILES_NOM_W octets x TILES_NOM_H lignes, au format de
; l'ecran ; une carte = MAP_NOM_W x MAP_NOM_H numeros de tuiles (1 octet).
; Requis : line_addr, variable tile_vars (12 octets).
; Sur CPC+/GX4000 tuiles et cartes sont en page 2 (lue en &C000) : la page
; PAGE_DATA doit etre selectionnee (c'est le cas par defaut).
;=============================================================================
tl_gfx  equ tile_vars                   ; adresse des graphismes
tl_w    equ tile_vars+2                 ; largeur d'une tuile (octets)
tl_h    equ tile_vars+3                 ; hauteur (lignes)
tl_size equ tile_vars+4                 ; octets par tuile
map_x0  equ tile_vars+6                 ; colonne ecran (octet) du coin de la carte
map_y0  equ tile_vars+7                 ; ligne ecran du coin de la carte
map_w   equ tile_vars+8                 ; largeur de la carte (cases)
tl_tmp  equ tile_vars+9                 ; (3 octets de travail)

; tiles_use : HL = graphismes (TILES_NOM), B = largeur (octets), C = hauteur (lignes)
;   ex. : ld hl,TILES_DECOR : ld bc,TILES_DECOR_W*256+TILES_DECOR_H : call tiles_use
tiles_use:
        ld (tl_gfx),hl
        ld a,b
        ld (tl_w),a
        ld a,c
        ld (tl_h),a
        ld hl,0                         ; taille = largeur x hauteur
        ld d,0
        ld e,b
tu_m:   add hl,de
        dec c
        jr nz,tu_m
        ld (tl_size),hl
        ret

; tile_draw : A = tuile, B = colonne ecran (octet), C = ligne ecran
tile_draw:
        push bc
        ld hl,0                         ; HL = A x taille
        ld de,(tl_size)
        ld b,8
td_m:   add hl,hl
        rla
        jr nc,td_n
        add hl,de
td_n:   djnz td_m
        ld de,(tl_gfx)
        add hl,de                       ; HL = graphismes de la tuile
        pop bc
        ld a,(tl_h)
td_l:   push af
        push hl
        ld l,c                          ; adresse de la ligne C + colonne B
        ld h,0
        add hl,hl
        ld de,line_addr
        add hl,de
        ld a,(hl)
        inc hl
        ld h,(hl)
        add a,b
        ld l,a
        adc a,h
        sub l
        ld h,a
        ex de,hl                        ; DE = ecran
        pop hl
        push bc
        ld a,(tl_w)
        ld c,a
        ld b,0
        ldir                            ; une ligne de la tuile
        pop bc
        inc c
        pop af
        dec a
        jr nz,td_l
        ret

; map_draw : HL = premiere case a dessiner (MAP_NOM + y x largeur + x),
;            D = largeur de la carte (cases), B = colonnes, C = rangees a dessiner
;   dessine a partir de (map_x0, map_y0) : colonne en octets, ligne ecran
map_draw:
        ld a,d
        ld (map_w),a
        ld a,(map_y0)
        ld (tl_tmp+1),a                 ; ligne ecran courante
md_r:   push bc
        push hl
        ld a,(map_x0)
        ld (tl_tmp),a                   ; colonne ecran courante
md_c:   push bc
        push hl
        ld a,(hl)
        ld hl,tl_tmp
        ld b,(hl)
        inc hl
        ld c,(hl)
        call tile_draw
        ld hl,tl_tmp                    ; colonne suivante
        ld a,(tl_w)
        add a,(hl)
        ld (hl),a
        pop hl
        inc hl
        pop bc
        djnz md_c
        pop hl                          ; rangee suivante de la carte
        ld a,(map_w)
        ld e,a
        ld d,0
        add hl,de
        ld a,(tl_h)                     ; et de l'ecran
        ld b,a
        ld a,(tl_tmp+1)
        add a,b
        ld (tl_tmp+1),a
        pop bc
        dec c
        jr nz,md_r
        ret

; map_get : HL = carte (MAP_NOM), D = largeur, B = x, C = y (cases)
;   -> HL = adresse de la case, A = numero de tuile
map_get:
        ld e,d
        ld d,0
        ld a,c
        or a
        jr z,mg_x
mg_y:   add hl,de
        dec a
        jr nz,mg_y
mg_x:   ld e,b
        add hl,de
        ld a,(hl)
        ret

; map_solid : A = numero de tuile, HL = table TFLAGS_NOM -> NZ si la tuile est solide (bit 0)
map_solid:
        ld e,a
        ld d,0
        add hl,de
        ld a,(hl)
        and 1
        ret
