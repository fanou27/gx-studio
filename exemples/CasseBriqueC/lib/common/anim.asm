;=============================================================================
; lib/common/anim.asm - animations de sprites (GX Studio)
; Une animation (ANIM_NOM, generee) : nombre d'images, delai, mode, images.
;   mode 0 = boucle, 1 = une fois (s'arrete sur la derniere), 2 = aller-retour
; Etat d'une animation en cours : 5 octets en RAM (adresse, indice, compteur, sens).
;=============================================================================
AN_SIZE equ 5

; anim_start : IX = etat (5 octets), HL = animation (ANIM_NOM)
anim_start:
        ld (ix+0),l
        ld (ix+1),h
        ld (ix+2),0                     ; indice
        inc hl
        ld a,(hl)
        ld (ix+3),a                     ; compteur = delai
        ld (ix+4),1                     ; sens (aller-retour)
        ret

; anim_step : IX = etat -> A = image a afficher (F_...) ;
;   CF = 1 quand une animation "une fois" est terminee. A appeler a chaque image.
anim_step:
        ld l,(ix+0)
        ld h,(ix+1)
        dec (ix+3)
        jr nz,as_get
        inc hl
        ld a,(hl)                       ; delai
        ld (ix+3),a
        inc hl
        ld a,(hl)                       ; mode
        dec hl
        dec hl
        ld c,(hl)                       ; C = nombre d'images
        or a
        jr z,as_loop
        dec a
        jr z,as_once
        ; --- aller-retour
        ld a,c
        cp 2
        jr c,as_get
        ld a,(ix+2)
        add a,(ix+4)
        ld (ix+2),a
        or a
        jr z,as_turn
        inc a
        cp c
        jr nz,as_get
as_turn:
        ld a,(ix+4)
        neg
        ld (ix+4),a
        jr as_get
as_loop:
        ld a,(ix+2)
        inc a
        cp c
        jr c,as_l1
        xor a
as_l1:  ld (ix+2),a
        jr as_get
as_once:
        ld a,(ix+2)
        inc a
        cp c
        jr nc,as_end
        ld (ix+2),a
as_get: ld l,(ix+0)
        ld h,(ix+1)
        ld a,(ix+2)
        add a,3
        ld e,a
        ld d,0
        add hl,de
        ld a,(hl)
        or a                            ; CF = 0
        ret
as_end: ld (ix+3),1                     ; reste sur la derniere image
        call as_get
        scf
        ret
