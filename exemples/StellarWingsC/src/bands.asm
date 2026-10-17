;=============================================================================
; src/bands.asm - remplissage d'une bande de sprites (multiplexage), appele
; par build_raster (aliens.c) pour chacune des 4 rangees de la formation.
;
; void band_row(uint8_t *blk, int16_t x, uint8_t y, uint8_t vis,
;               const uint16_t *pal, uint8_t wing) __sdcccall(0);
;   blk  : bloc de 38 octets lu par l'interruption raster (lib/plus/raster.asm)
;   x, y : position du premier alien de la rangee ; les colonnes sont espacees
;          de 48 pixels mode 2 (COL_PITCH)
;   vis  : bit c = l'alien de la colonne c est visible (sinon zoom 0)
;   pal  : 4 couleurs (&0GRB) des sprites pour cette rangee
;   wing : 0 = ailes hautes (couleur 4 au noir), 1 = ailes basses (couleur 3)
; Parametres sur la pile (convention __sdcccall(0)) : lus par IX comme dans
; lib/c/gx_c.asm ; IX est restaure avant le retour.
;=============================================================================
_band_row:
        push ix
        ld ix,0
        add ix,sp                       ; (ix+4) = premier parametre
        ld l,(ix+4)
        ld h,(ix+5)                     ; HL = bloc
        ld e,(ix+6)
        ld d,(ix+7)                     ; DE = X
        ld c,(ix+9)                     ; C = aliens visibles
        ld b,6
bdr_spr:
        ld (hl),e                       ; X
        inc hl
        ld (hl),d
        inc hl
        ld a,(ix+8)                     ; Y
        ld (hl),a
        inc hl
        ld (hl),0
        inc hl
        xor a
        rr c
        jr nc,bdr_z
        ld a,ZOOM_X2Y1
bdr_z:  ld (hl),a                       ; zoom (0 = invisible)
        inc hl
        ld a,e                          ; colonne suivante : X + 48
        add a,48
        ld e,a
        jr nc,bdr_c
        inc d
bdr_c:  djnz bdr_spr
        ex de,hl                        ; DE = couleurs 1-4 du bloc
        ld l,(ix+10)
        ld h,(ix+11)
        ld bc,8
        ldir
        ex de,hl                        ; HL = fin du bloc
        ld a,(ix+12)
        or a
        jr z,bdr_w0
        dec hl                          ; ailes basses : couleur 3 au noir
        dec hl
        dec hl
        ld (hl),0
        dec hl
        ld (hl),0
        jr bdr_end
bdr_w0: dec hl                          ; ailes hautes : couleur 4 au noir
        ld (hl),0
        dec hl
        ld (hl),0
bdr_end:
        pop ix
        ret
