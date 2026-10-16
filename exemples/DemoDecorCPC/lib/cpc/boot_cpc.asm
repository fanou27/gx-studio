;=============================================================================
; lib/cpc/boot_cpc.asm - demarrage d'un jeu CPC charge par RUN"NOM
; A placer au debut du code (le fichier binaire demarre ici).
; Requis : RAM_START, STACK_TOP (variables, mises a zero), SCREEN_MODE,
;          le label "main", et (gen.asm) extra_files (fichiers des banques).
; Le firmware sert seulement a charger les fichiers supplementaires, puis il
; est coupe : interruptions, ROM et memoire appartiennent au jeu.
;=============================================================================
cpc_start:
        ld hl,extra_files               ; fichiers a charger dans les banques (128 Ko+)
        call load_extras
        di
        ld sp,STACK_TOP
        im 1
        ld bc,GA+GA_ROMOFF+SCREEN_MODE  ; ROM coupees, mode ecran
        out (c),c
        ld bc,GA+BANK_NORMAL
        out (c),c
        ld hl,RAM_START                 ; variables a zero (sans appel : la pile y est)
cb_clr: ld (hl),0
        inc hl
        ld a,h
        cp STACK_TOP/256
        jr nz,cb_clr
        ld a,#C3                        ; RST 38h -> notre gestionnaire
        ld (#38),a
        ld hl,irq_cpc
        ld (#39),hl
        ld hl,crtc_cpc
        xor a
cb_crt: ld b,CRTC_SEL/256
        out (c),a
        ld b,CRTC_DATA/256
        ld e,(hl)
        out (c),e
        inc hl
        inc a
        cp 14
        jr nz,cb_crt
        ld bc,PPI_CTRL+#82
        out (c),c
        ld hl,pal_black16
        call set_palette
        call psg_init
        call clear_screen
        ld a,GA_ROMOFF                  ; ecran de presentation GX Studio (3 s)
        call gx_splash
        ei
        jp main

crtc_cpc:
        db 63,40,46,#8E,38,0,25,30,0,7,0,0,#30,0

; load_extras : HL = table (config banque, adresse, longueur du nom, nom) ; 0 = fin
;   utilise le firmware (AMSDOS) : appele avant de le couper
load_extras:
        ld a,(hl)
        or a
        ret z
        push hl                         ; RUN" laisse le firmware en mode cassette :
        ld c,7                          ; on relance la ROM disque (AMSDOS = ROM 7)
        ld de,#0040                     ; KL INIT BACK
        ld hl,#ABFF
        call #BCCE
        pop hl
le_next:
        ld a,(hl)
        or a
        ret z
        ld b,GA/256
        out (c),a                       ; banque visible en &4000
        inc hl
        ld e,(hl)
        inc hl
        ld d,(hl)                       ; DE = adresse de chargement
        inc hl
        ld b,(hl)                       ; B = longueur du nom
        inc hl
        push de
        push hl
        ld a,l
        add a,b
        ld l,a
        jr nc,le_1
        inc h
le_1:   ex (sp),hl                      ; pile = entree suivante, HL = nom
        ld de,#C000                     ; tampon de 2 Ko : l'ecran
        call #BC77                      ; CAS IN OPEN
        pop bc                          ; BC = entree suivante
        pop hl                          ; HL = adresse
        push bc
        jr nc,le_err
        call #BC83                      ; CAS IN DIRECT
        call #BC7A                      ; CAS IN CLOSE
le_err: ld bc,GA+BANK_NORMAL
        out (c),c
        pop hl
        jr le_next

; irq_cpc : 300 interruptions par seconde (toutes les 52 lignes)
;   frame_flag = 1 a la synchro verticale ; lecteur de son a chaque appel
irq_cpc:
        push af
        push bc
        push de
        push hl
        push ix
        ld b,PPI_B/256
        in a,(c)
        rra
        jr nc,ic_1
        ld a,1                          ; synchro verticale : nouvelle image
        ld (frame_flag),a
        ld hl,frame_count
        inc (hl)
ic_1:   call snd_tick
        pop ix
        pop hl
        pop de
        pop bc
        pop af
        ei
        ret

; wait_frame : attend la synchro verticale (debut du bord haut)
wait_frame:
        ld a,(frame_flag)
        or a
        jr z,wait_frame
        xor a
        ld (frame_flag),a
        ret

wait_frames:
        push bc
        call wait_frame
        pop bc
        djnz wait_frames
        ret

; set_palette : HL = 16 couleurs (numeros firmware 0-26) -> encres 0-15, bord = encre 0
set_palette:
        ld c,0
sp_l:   ld a,(hl)
        inc hl
        call fw_hw
        ld b,GA/256
        out (c),c                       ; choix de l'encre
        out (c),a                       ; sa couleur
        inc c
        ld a,c
        cp 16
        jr nz,sp_l
        ld a,#10                        ; bord = couleur de l'encre 0
        out (c),a
        ld de,-16
        add hl,de
        ld a,(hl)
        call fw_hw
        out (c),a
        ret

; set_ink_color : A = encre (0-15, 16 = bord), C = couleur firmware (0-26)
set_ink_color:
        ld b,GA/256
        out (c),a
        ld a,c
        call fw_hw
        out (c),a
        ret

; fw_hw : A = couleur firmware -> A = commande Gate Array (&40 + couleur materielle)
fw_hw:
        push hl
        push de
        ld e,a
        ld d,0
        ld hl,hw_colors
        add hl,de
        ld a,(hl)
        or #40
        pop de
        pop hl
        ret

hw_colors:                              ; couleur firmware -> couleur materielle
        db #14,#04,#15,#1C,#18,#1D,#0C,#05,#0D,#16,#06,#17,#1E,#00,#1F,#0E,#07,#0F
        db #12,#02,#13,#1A,#19,#1B,#0A,#03,#0B
pal_black16:
        ds 16,0

; clear_screen : &C000-&FFFF a zero
clear_screen:
        di
        ld (lib_tmp),sp
        ld sp,0
        ld de,0
        ld b,0
cs_l:   repeat 32
        push de
        rend
        djnz cs_l
        ld sp,(lib_tmp)
        ei
        ret

; psg_w : A = registre, C = valeur
psg_w:
        ld b,PPI_A/256
        out (c),a
        ld b,PPI_C/256
        ld a,#C0
        out (c),a
        xor a
        out (c),a
        ld b,PPI_A/256
        out (c),c
        ld b,PPI_C/256
        ld a,#80
        out (c),a
        xor a
        out (c),a
        ret

psg_init:
        ld a,13
pi_l:   ld c,0
        push af
        call psg_w
        pop af
        dec a
        jp p,pi_l
        ld a,7
        ld c,PSG_MIXER
        jp psg_w

; bank_set : A = configuration (#C0 normal, #C4-#C7 banque 4-7 en &4000...)
bank_set:
        ld b,GA/256
        out (c),a
        ret

        include "../common/splash.asm"  ; ecran de presentation (logo, 3 secondes)
