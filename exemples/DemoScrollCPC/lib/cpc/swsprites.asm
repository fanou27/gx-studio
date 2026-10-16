;=============================================================================
; lib/cpc/swsprites.asm - sprites logiciels en mode 0 (CPC classique)
; Un sprite (genere par le studio) : largeur (octets = 2 pixels), hauteur,
; puis pour chaque octet un couple (masque, donnee) : ecran = (ecran AND
; masque) OR donnee. L'encre 0 est transparente.
; Les sprites sont geres par "emplacements" (SW_SLOTS) : sw_update efface
; tout (en restaurant le decor sauvegarde) puis redessine tout.
; Requis : SW_SLOTS, SW_BUF (octets max par sprite), variables sw_tab
; (SW_SLOTS x SW_SLOT_SIZE) et sw_bufs (SW_SLOTS x SW_BUF), line_addr,
; spr_table (table des adresses des images, generee).
; Coordonnees : X en octets (0-79, 1 octet = 2 pixels mode 0), Y en lignes.
; Compatible avec le defilement (lib/common/scroll.asm) : les adresses tiennent
; compte du debut d'ecran (variable scroll_vars, 16 octets, toujours requise).
;=============================================================================
sw_off          equ scroll_vars+2       ; decalage de l'ecran en octets (= scr_off2)
SW_SLOT_SIZE    equ 12                  ; actif, X, Y, image(2), dessine, ecran(2), L, H, tampon(2)

; sw_init : prepare les emplacements (adresses des tampons)
sw_init:
        ld ix,sw_tab
        ld hl,sw_bufs
        ld b,SW_SLOTS
swi_l:  ld (ix+0),0
        ld (ix+5),0
        ld (ix+10),l
        ld (ix+11),h
        ld de,SW_BUF
        add hl,de
        ld de,SW_SLOT_SIZE
        add ix,de
        djnz swi_l
        ret

; sw_slot : A = emplacement -> IX
sw_slot:
        ld ix,sw_tab
        or a
        ret z
        ld de,SW_SLOT_SIZE
sws_l:  add ix,de
        dec a
        jr nz,sws_l
        ret

; sw_set : A = emplacement, B = X (octets), C = Y (lignes), D = image
sw_set:
        push de
        call sw_slot
        pop de
        ld (ix+1),b
        ld (ix+2),c
        ld l,d
        ld h,0
        add hl,hl
        ld de,spr_table
        add hl,de
        ld a,(hl)
        inc hl
        ld h,(hl)
        ld (ix+3),a
        ld (ix+4),h
        ld (ix+0),1
        ret

; sw_hide : A = emplacement
sw_hide:
        call sw_slot
        ld (ix+0),0
        ret

; sw_update : efface (ordre inverse) puis redessine (ordre normal) les sprites
sw_update:
        ld a,SW_SLOTS-1
swu_e:  push af
        call sw_slot
        ld a,(ix+5)
        or a
        call nz,sw_restore
        pop af
        dec a
        jp p,swu_e
        xor a
swu_d:  push af
        call sw_slot
        ld a,(ix+0)
        or a
        call nz,sw_draw
        pop af
        inc a
        cp SW_SLOTS
        jr nz,swu_d
        ret

; sw_restore : IX = emplacement -> recopie le decor sauvegarde
sw_restore:
        ld (ix+5),0
        ld e,(ix+6)
        ld d,(ix+7)                     ; DE = ecran
        ld l,(ix+10)
        ld h,(ix+11)                    ; HL = tampon
        ld c,(ix+9)
swr_l:  push de
        ld b,(ix+8)
swr_b:  ld a,(hl)
        ld (de),a
        inc hl
        call sw_inc
        djnz swr_b
        pop de
        call sw_down
        dec c
        jr nz,swr_l
        ret

; sw_draw : IX = emplacement -> sauvegarde le decor et dessine
sw_draw:
        ld l,(ix+3)
        ld h,(ix+4)
        ld a,(hl)
        ld (ix+8),a                     ; largeur
        inc hl
        ld a,(hl)
        ld (ix+9),a                     ; hauteur
        inc hl
        push hl
        ld b,(ix+1)
        ld c,(ix+2)
        call sw_xy                      ; HL = adresse ecran (defilement compris)
        ld (ix+6),l
        ld (ix+7),h
        ld (ix+5),1
        ex de,hl                        ; DE = ecran
        exx
        ld l,(ix+10)
        ld h,(ix+11)                    ; HL' = tampon
        exx
        pop hl                          ; HL = masques / donnees
        ld c,(ix+9)
swd_r:  push de
        ld b,(ix+8)
swd_c:  ld a,(de)
        exx
        ld (hl),a
        inc hl
        exx
        and (hl)
        inc hl
        or (hl)
        inc hl
        ld (de),a
        call sw_inc
        djnz swd_c
        pop de
        call sw_down
        dec c
        jr nz,swd_r
        ret

; sw_inc : DE = DE + 1 dans la memoire ecran circulaire (bloc de 2 Ko)
sw_inc:
        inc e
        ret nz
        inc d
        ld a,d
        and 7
        ret nz
        ld a,d
        sub 8
        ld d,a
        ret

; sw_down : DE = adresse ecran -> ligne suivante (ecran en &C000, defilement compris)
sw_down:
        ld a,d
        add a,8
        ld d,a
        ret nc
        ld a,e                          ; passage a la rangee de caracteres suivante
        add a,#50
        ld e,a
        ld a,d
        adc a,0
        and 7
        or #C0
        ld d,a
        ret

; sw_xy : B = X (octets), C = Y -> HL = adresse ecran (debut d'ecran compris)
sw_xy:
        push de
        ld l,c
        ld h,0
        add hl,hl
        ld de,line_addr
        add hl,de
        ld e,(hl)
        inc hl
        ld d,(hl)
        ld hl,(sw_off)
        ld a,b
        add a,l
        ld l,a
        adc a,h
        sub l
        ld h,a
        add hl,de
        ld a,h
        and 7
        ld h,a
        ld a,d
        and #F8
        or h
        ld h,a
        pop de
        ret
