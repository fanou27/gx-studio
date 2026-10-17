;=============================================================================
; lib/raster.asm - interruptions raster chainees (PRI de l'ASIC)
;
; Une table raster = [ligne de la 1re entree] puis des entrees de 4 octets :
;     type, donnee (mot), ligne de l'interruption suivante
;   type 0 : couleur de l'encre RASTER_INK (effet "copper")
;   type 1 : bande de sprites : la donnee pointe sur un bloc de 38 octets
;            (X,Y,zoom des sprites 0-5 puis couleurs sprites 1-4) -> multiplexage
;   type 2 : fin de la zone visible (ligne 199) : frame_flag = 1
;   type 3 : "armement" (ligne 304) : passe a la table en attente (double tampon)
;   type 4 : couleur d'une encre quelconque : donnee = encre x &1000 + &0GRB
;            (tables produites par l'editeur de rasters du studio : RASTER_NOM)
; Lignes au-dela de 255 : selon la machine (console, emulateurs), le registre
; PRI est compare au compteur de lignes sur 8 bits (p declenche aux lignes p
; ET p+256) ou sur 9 bits (p seulement). Le demarrage (pri_detect, boot.asm)
; mesure lequel et range dans pri_arm la ligne d'armement :
;   8 bits : 48 (= ligne 304, bord bas) ; 9 bits : 204 (sous l'image).
; La fin d'image programme PRI = pri_arm, puis l'armement programme la
; premiere ligne de la table suivante : une image complete par tour dans
; les deux cas.
;=============================================================================
RASTER_INK      equ 3                   ; encre modifiee par le type 0
ARM_LINE        equ 48                  ; (valeur ecrite dans les tables : remplacee par pri_arm)
ARM_LINE_8      equ 48                  ; 48 + 256 = ligne 304 (bord bas)
ARM_LINE_9      equ 204                 ; sous la zone visible (fin d'image en 199)

irq_handler:
        push af
        push bc
        push de
        push hl
        ld hl,(pri_ptr)
        ld a,(hl)                       ; type
        inc hl
        ld e,(hl)
        inc hl
        ld d,(hl)                       ; donnee
        inc hl
        ld b,(hl)                       ; ligne suivante
        inc hl
        ld (pri_ptr),hl
        or a
        jr z,irq_ink
        dec a
        jr z,irq_band
        dec a
        jr z,irq_end
        dec a
        jr nz,irq_any                   ; type 4
        ; --- type 3 : armement -> table en attente
        ld hl,(pri_pending)
        ld a,(hl)
        inc hl
        ld (pri_ptr),hl
        ld (ASIC_PRI),a
        jp irq_ret
irq_any:                                ; type 4 : encre (bits 12-15 de la donnee) = &0GRB
        ld a,b
        ld (ASIC_PRI),a
        ld a,d
        rrca
        rrca
        rrca
        and #1E                         ; numero d'encre x 2
        ld l,a
        ld h,ASIC_PAL/256
        ld (hl),e
        inc hl
        ld a,d
        and #0F
        ld (hl),a
        jp irq_ret
irq_ink:
        ld a,b
        ld (ASIC_PRI),a
        ld (ASIC_PAL+RASTER_INK*2),de
        jp irq_ret
irq_end:
        ld a,(pri_arm)                 ; (mesuree au demarrage)
        ld (ASIC_PRI),a
        ld a,1
        ld (frame_flag),a
        ld hl,frame_count
        inc (hl)
        jp irq_ret
irq_band:
        ld a,b
        ld (ASIC_PRI),a
        ex de,hl                        ; HL = bloc de la bande
        ld de,ASIC_SPR_POS
        repeat 6                        ; sprites 0 a 5 : X, Y, zoom
        ldi
        ldi
        ldi
        ldi
        ldi
        ld a,e
        add a,3
        ld e,a
        rend
        ld de,ASIC_SPR_PAL              ; couleurs sprites 1 a 4
        repeat 8
        ldi
        rend
irq_ret:
        pop hl
        pop de
        pop bc
        pop af
        ei
        ret

; raster_start : HL = table raster (demarre immediatement)
raster_start:
        di
        ld (pri_pending),hl
        ld a,(hl)
        inc hl
        ld (pri_ptr),hl
        ld (ASIC_PRI),a
        ei
        ret

; wait_frame : attend la fin de la zone visible (ligne 199)
wait_frame:
        ld a,(frame_flag)
        or a
        jr z,wait_frame
        xor a
        ld (frame_flag),a
        ret

; wait_frames : B images
wait_frames:
        push bc
        call wait_frame
        pop bc
        djnz wait_frames
        ret

BAND_SIZE       equ 38

; table minimale : fin d'image + armement (utilisee au demarrage)
raster_idle:
        db 199
        db 2 : dw 0 : db ARM_LINE
        db 3 : dw 0 : db 0
