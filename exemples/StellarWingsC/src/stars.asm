;=============================================================================
; src/stars.asm - les etoiles du fond, en assembleur (appelees depuis le C)
;
; void stars_update(void) __sdcccall(0);   (declaree dans stellar.h)
; Le tableau stars[] (fx.c) contient 40 etoiles de 4 octets :
;   colonne (octet 0-79), motif (pixel et encre), ligne, vitesse (1-3)
; Chaque image : effacer l'etoile, la descendre de sa vitesse, la redessiner ;
; en bas de l'ecran elle repart en haut dans une colonne au hasard. Pendant un
; message (star_skip = 1), les lignes 124 a 151 sont evitees.
; Le profil du jeu (documentation, chapitre "Performances") montre que cette
; boucle prend le plus de temps du jeu : voir les mesures dans la documentation.
; Registres : AF, BC, DE, HL sont libres ; IX (pointeur de pile du C) n'est
; pas touche.
;=============================================================================
ST_NB           equ 40                  ; = NSTARS (stellar.h)
ST_MSG_TOP      equ 124                 ; = MSG_TOP
ST_MSG_LINES    equ 28                  ; = MSG_LINES

_stars_update:
        ld hl,_stars                    ; HL parcourt le tableau (IX n'est pas utilise)
        ld b,ST_NB
st_loop:
        push bc
        ld c,(hl)                       ; C = colonne
        inc hl
        inc hl
        ld a,(hl)                       ; A = ligne
        call st_ptr                     ; DE = adresse de l'etoile a l'ecran
        jr z,st_1
        xor a
        ld (de),a                       ; efface
st_1:   ld a,(hl)
        inc hl
        add a,(hl)                      ; descend de sa vitesse
        dec hl
        cp 184
        jr c,st_2
        sub 168                         ; repart en haut...
        ld (hl),a
        dec hl
        dec hl
        push hl
        call st_col                     ; ...dans une colonne au hasard
        pop hl
        ld (hl),a
        ld c,a
        inc hl
        inc hl
        ld a,(hl)
st_2:   ld (hl),a
        call st_ptr
        jr z,st_3
        dec hl
        ld a,(hl)                       ; motif
        ld (de),a                       ; dessine
        inc hl
st_3:   inc hl                          ; etoile suivante
        inc hl
        pop bc
        djnz st_loop
        ret

; st_col -> A = colonne au hasard (0-79) ; appelle la fonction C rand8()
st_col:
        call _rand8                     ; __sdcccall(0) : resultat dans L
        ld a,l
        and 127
        cp 80
        ret c
        sub 48
        ret

; st_ptr : A = ligne, C = colonne -> DE = adresse ecran (NZ) ;
;          Z = ligne dans la zone des messages (ne rien toucher). HL est preserve.
st_ptr:
        ld e,a
        ld a,(_star_skip)
        or a
        jr z,stp_ok
        ld a,e
        sub ST_MSG_TOP
        cp ST_MSG_LINES
        jr nc,stp_ok
        xor a                           ; Z
        ret
stp_ok: push hl
        ld l,e
        ld h,0
        add hl,hl
        ld de,line_addr                 ; table generee par le studio (build/gen.asm)
        add hl,de
        ld a,(hl)
        inc hl
        ld d,(hl)
        add a,c                         ; + colonne
        ld e,a
        jr nc,stp_1
        inc d
stp_1:  pop hl
        or 1                            ; NZ (D vaut au moins &C0)
        ret
