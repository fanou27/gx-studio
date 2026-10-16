;=============================================================================
; lib/c/gx_c.asm - liaison entre le C (SDCC) et la bibliotheque GX Studio
;
; Fonctions declarees dans lib/c/gx.h avec __sdcccall(0) : parametres sur la
; pile (octets un par un, mots en 2 octets, dans l'ordre), a partir de (SP+2) ;
; resultat 8 bits dans L, 16 bits dans HL ; l'appelant retire les parametres.
; IX est le pointeur de pile du C : chaque fonction le sauve (push ix) puis
; lit ses parametres en (ix+4)... IY n'est pas utilise par le C.
; Requis : les modules de lib/ inclus par le modele de projet C, et les noms
;   txt_ink / txt_at (texte du mode de l'ecran).
;=============================================================================
_joy            equ joy
_joy_new        equ joy_new
_frame_count    equ frame_count
        if ASIC
_raster_idle    equ raster_idle
_spr_want       equ spr_want
        endif

macro GX_ENTER
        push ix
        ld ix,0
        add ix,sp
mend
macro GX_LEAVE
        pop ix
        ret
mend

; ---- demarrage, images ----------------------------------------------------
_gx_init:
        GX_ENTER
        if ASIC
        call dma_init                   ; listes de son recopiees en RAM (le DMA lit la RAM)
        ld hl,spr_have                  ; aucune image chargee dans l'ASIC
        ld b,16
gxi_h:  ld (hl),#FF
        inc hl
        djnz gxi_h
        else
        call sw_init
        endif
        GX_LEAVE

_gx_wait_frame:
        GX_ENTER
        call wait_frame
        GX_LEAVE

_gx_wait_frames:
        GX_ENTER
        ld b,(ix+4)
        call wait_frames
        GX_LEAVE

_gx_read_joy:
        GX_ENTER
        call read_joy
        GX_LEAVE

; ---- ecran, couleurs, texte -----------------------------------------------
_gx_clear_screen:
        GX_ENTER
        call clear_screen
        GX_LEAVE

_gx_palette:
        GX_ENTER
        ld l,(ix+4)
        ld h,(ix+5)
        if ASIC
        if SCREEN_MODE==0
        call set_palette16
        else
        call set_palette4
        endif
        else
        call set_palette
        endif
        GX_LEAVE

_gx_sprite_palette:
        GX_ENTER
        if ASIC
        ld l,(ix+4)
        ld h,(ix+5)
        call set_spr_palette
        endif
        GX_LEAVE

_gx_ink:
        GX_ENTER
        ld a,(ix+4)
        call txt_ink
        GX_LEAVE

_gx_text:
        GX_ENTER
        ld b,(ix+4)
        ld c,(ix+5)
        ld e,(ix+6)
        ld d,(ix+7)
        call txt_at
        GX_LEAVE

; ---- sons -----------------------------------------------------------------
_gx_sound:                              ; (canal, decalage dans les listes, diviseur)
        GX_ENTER
        ld a,(ix+4)
        ld l,(ix+5)
        ld h,(ix+6)
        ld b,(ix+7)
        if ASIC
        ld de,DMA_RAM
        add hl,de
        call dma_play
        else
        ld de,snd_lists
        add hl,de
        call snd_play
        endif
        GX_LEAVE

_gx_sound_stop:
        GX_ENTER
        ld a,(ix+4)
        if ASIC
        call dma_stop
        else
        call snd_stop
        endif
        GX_LEAVE

_gx_sound_stop_all:
        GX_ENTER
        if ASIC
        call dma_stop_all
        else
        call snd_stop_all
        endif
        GX_LEAVE

_gx_psg:
        GX_ENTER
        ld a,(ix+4)
        ld c,(ix+5)
        call psg_w
        GX_LEAVE

_gx_sound_tempo:
        GX_ENTER
        ld a,(ix+4)
        if ASIC
        call dma_tempo
        else
        call snd_tempo
        endif
        GX_LEAVE

; ---- sprites communs ------------------------------------------------------
_gx_sprite:                             ; (n, x, y, image) : x en pixels du mode de l'ecran
        GX_ENTER
        if ASIC
        ld l,(ix+5)
        ld h,(ix+6)
        if SCREEN_MODE==0
        add hl,hl                       ; mode 0 : 1 pixel = 4 pixels mode 2
        add hl,hl
GX_ZOOM equ #0D                         ; x4 en largeur
        else
        if SCREEN_MODE==1
        add hl,hl
GX_ZOOM equ #09                         ; x2 en largeur
        else
GX_ZOOM equ #05
        endif
        endif
        ld e,(ix+7)
        ld d,0
        ld b,(ix+8)
        ld c,GX_ZOOM
        ld a,(ix+4)
        call spr_set
        else
        ld l,(ix+5)
        ld h,(ix+6)
        srl h                           ; mode 0 : 2 pixels par octet
        rr l
        ld b,l
        ld c,(ix+7)
        ld d,(ix+8)
        ld a,(ix+4)
        call sw_set
        endif
        GX_LEAVE

_gx_sprite_hide:
        GX_ENTER
        ld a,(ix+4)
        if ASIC
        call spr_hide
        else
        call sw_hide
        endif
        GX_LEAVE

_gx_sprites_update:
        if ASIC
_gx_spr_update:
        endif
        GX_ENTER
        if ASIC
        xor a
        call spr_commit                 ; positions calculees a l'image precedente
        call spr_load                   ; images qui ont change
        else
        call sw_update
        endif
        GX_LEAVE

        if ASIC
; ---- CPC+ / GX4000 --------------------------------------------------------
_gx_spr_set:                            ; (n, x, y, image, zoom) : x en pixels mode 2, y signe
        GX_ENTER
        ld l,(ix+5)
        ld h,(ix+6)
        ld e,(ix+7)
        ld d,(ix+8)
        ld b,(ix+9)
        ld c,(ix+10)
        ld a,(ix+4)
        call spr_set
        GX_LEAVE

_gx_spr_hide:
        GX_ENTER
        ld a,(ix+4)
        call spr_hide
        GX_LEAVE

_gx_spr_hide_all:
        GX_ENTER
        call spr_hide_all
        GX_LEAVE

_gx_spr_update_from:                    ; (premier sprite recopie)
        GX_ENTER
        ld a,(ix+4)
        call spr_commit
        call spr_load
        GX_LEAVE

_gx_raster_queue:                       ; table prise en compte a la fin de l'image en cours
        GX_ENTER
        ld l,(ix+4)
        ld h,(ix+5)
        di
        ld (pri_pending),hl
        ei
        GX_LEAVE

_gx_raster_start:
        GX_ENTER
        ld l,(ix+4)
        ld h,(ix+5)
        call raster_start
        GX_LEAVE
        else
; ---- CPC ------------------------------------------------------------------
_gx_sw_set:                             ; (emplacement, x en octets, y, image)
        GX_ENTER
        ld b,(ix+5)
        ld c,(ix+6)
        ld d,(ix+7)
        ld a,(ix+4)
        call sw_set
        GX_LEAVE

_gx_sw_hide:
        GX_ENTER
        ld a,(ix+4)
        call sw_hide
        GX_LEAVE

_gx_sw_update:
        GX_ENTER
        call sw_update
        GX_LEAVE
        endif

; ---- decors en tuiles -----------------------------------------------------
_gx_tiles_use:                          ; (graphismes, largeur, hauteur)
        GX_ENTER
        ld l,(ix+4)
        ld h,(ix+5)
        ld b,(ix+6)
        ld c,(ix+7)
        call tiles_use
        GX_LEAVE

_gx_tile_draw:                          ; (tuile, colonne, ligne)
        GX_ENTER
        ld a,(ix+4)
        ld b,(ix+5)
        ld c,(ix+6)
        call tile_draw
        GX_LEAVE

_gx_map_origin:                         ; (colonne en octets, ligne)
        GX_ENTER
        ld a,(ix+4)
        ld (map_x0),a
        ld a,(ix+5)
        ld (map_y0),a
        GX_LEAVE

_gx_map_draw:                           ; (case, largeur de la carte, colonnes, rangees)
        GX_ENTER
        ld l,(ix+4)
        ld h,(ix+5)
        ld d,(ix+6)
        ld b,(ix+7)
        ld c,(ix+8)
        call map_draw
        GX_LEAVE

_gx_map_get:                            ; (carte, largeur, x, y) -> tuile
        GX_ENTER
        ld l,(ix+4)
        ld h,(ix+5)
        ld d,(ix+6)
        ld b,(ix+7)
        ld c,(ix+8)
        call map_get
        ld l,a
        GX_LEAVE

_gx_tile_solid:                         ; (tuile, TFLAGS_NOM) -> 1 si solide
        GX_ENTER
        ld a,(ix+4)
        ld l,(ix+5)
        ld h,(ix+6)
        call map_solid
        ld l,0
        jr z,gts_0
        inc l
gts_0:  GX_LEAVE

; ---- animations -----------------------------------------------------------
_gx_anim_start:                         ; (etat, animation)
        GX_ENTER
        ld l,(ix+6)
        ld h,(ix+7)
        ld e,(ix+4)
        ld d,(ix+5)
        push de
        pop ix
        call anim_start
        GX_LEAVE

_gx_anim_step:                          ; (etat) -> image
        GX_ENTER
        ld e,(ix+4)
        ld d,(ix+5)
        push de
        pop ix
        call anim_step
        ld l,a
        GX_LEAVE
