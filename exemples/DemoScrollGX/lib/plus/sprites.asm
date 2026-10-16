;=============================================================================
; lib/sprites.asm - sprites materiels : "ombre" en RAM, recopie, images
; spr_tab : 16 x (X lo, X hi, Y lo, Y hi, zoom). X en pixels mode 2 (0-639)
; a partir du debut de l'affichage, Y en lignes (signes).
; Les sprites SPR_FIRST_SHADOW..15 sont recopies a chaque image par spr_commit ;
; les sprites inferieurs peuvent etre geres par les bandes raster (multiplexage).
;=============================================================================

; spr_set : A = sprite, HL = X, DE = Y, B = image, C = zoom (registres detruits)
spr_set:
        push bc
        push de
        push hl
        push af
        ld l,a
        add a,a
        add a,a
        add a,l
        ld e,a
        ld d,0
        ld hl,spr_tab
        add hl,de                       ; HL = entree de l'ombre
        pop af
        pop de                          ; X
        ld (hl),e
        inc hl
        ld (hl),d
        inc hl
        pop de                          ; Y
        ld (hl),e
        inc hl
        ld (hl),d
        inc hl
        pop bc
        ld (hl),c                       ; zoom
        ld e,a
        ld d,0
        ld hl,spr_want
        add hl,de
        ld (hl),b                       ; image voulue
        ret

; spr_hide : A = sprite
spr_hide:
        ld l,a
        add a,a
        add a,a
        add a,l
        add a,4
        ld e,a
        ld d,0
        ld hl,spr_tab
        add hl,de
        ld (hl),0
        ret

; spr_hide_all : cache tout (ombre + ASIC)
spr_hide_all:
        ld hl,spr_tab+4
        ld de,5
        ld b,16
sha_l:  ld (hl),0
        add hl,de
        djnz sha_l
        ld hl,ASIC_SPR_POS+4
        ld de,8
        ld b,16
sha_2:  ld (hl),0
        add hl,de
        djnz sha_2
        ret

; spr_commit : recopie l'ombre des sprites A..15 dans l'ASIC
spr_commit:
        ld b,a
        ld l,a
        add a,a
        add a,a
        add a,l
        ld e,a
        ld d,0
        ld hl,spr_tab
        add hl,de
        ld a,b
        add a,a
        add a,a
        add a,a
        ld e,a
        ld d,ASIC_SPR_POS/256
        ld a,16
        sub b
sc_l:   ldi
        ldi
        ldi
        ldi
        ldi
        inc e
        inc e
        inc e
        dec a
        jr nz,sc_l
        ret

; spr_load : copie dans la RAM sprite les images demandees qui ont change (4 max)
spr_load:
        ld c,4
        ld b,0
        ld hl,spr_want
sl_l:   ld a,(hl)
        push hl
        ld de,16
        add hl,de
        cp (hl)
        jr z,sl_same
        ld (hl),a
        call spr_copy_image
        dec c
sl_same:
        pop hl
        ld a,c
        or a
        ret z
        inc hl
        inc b
        ld a,b
        cp 16
        jr nz,sl_l
        ret

; spr_copy_image : A = image, B = sprite (registres B et C preserves)
spr_copy_image:
        push bc
        push af
        ld a,PAGE_SPR
        ld bc,ROMSEL
        or #80
        out (c),a
        pop af
        add a,SPRITES/256
        ld h,a
        ld l,0
        pop bc
        push bc
        ld a,b
        add a,ASIC_SPR_DATA/256
        ld d,a
        ld e,0
        ld bc,256
        ldir
        ld a,(cur_page)
        ld bc,ROMSEL
        or #80
        out (c),a
        pop bc
        ret
