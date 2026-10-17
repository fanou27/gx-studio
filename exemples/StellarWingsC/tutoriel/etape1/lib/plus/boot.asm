;=============================================================================
; lib/plus/boot.asm - demarrage d'une cartouche CPC+ / GX4000
; (c) 2026 ALONSO Stephane [Fanou]
; A placer en tete de la page 0 (ORG 0). Le jeu fournit le label "main".
; Variables : pri_arm (1 octet) en plus de celles de raster.asm.
; Requis : RAM_START (debut des variables), STACK_TOP, SCREEN_MODE (0,1,2),
;          PAGE_DATA (page de cartouche visible en &C000 par defaut).
;=============================================================================
        di
        im 1
        ld sp,STACK_TOP
        jp lib_boot
        defs #38-$,0
        jp irq_handler                  ; RST 38h

asic_unlock_seq:
        db #FF,#00,#FF,#77,#B3,#51,#A8,#D4,#62,#39,#9C,#46,#2B,#15,#8A,#CD,#EE
crtc_std:
        db 63,40,46,#8E,38,0,25,30,0,7,0,0,#30,0,0,0

lib_boot:
        ld bc,GA+#90+SCREEN_MODE        ; ROM basse et haute actives, RAZ interruptions
        out (c),c
        ld hl,asic_unlock_seq           ; deverrouillage de l'ASIC
        ld e,17
lb_unl: ld a,(hl)
        inc hl
        ld b,CRTC_SEL/256
        out (c),a
        dec e
        jr nz,lb_unl
        ld bc,GA+RMR2_ASIC              ; registres ASIC en &4000
        out (c),c
        ld hl,crtc_std                  ; CRTC standard
        xor a
lb_crt: ld b,CRTC_SEL/256
        out (c),a
        ld b,CRTC_DATA/256
        ld e,(hl)
        out (c),e
        inc hl
        inc a
        cp 16
        jr nz,lb_crt
        ld bc,PPI_CTRL+#82
        out (c),c
        ld hl,RAM_START                 ; RAM des variables a zero (sans appel : la pile y est)
lb_clr: ld (hl),0
        inc hl
        ld a,h
        cp STACK_TOP/256
        jr nz,lb_clr
        ld a,PAGE_DATA                  ; (apres l'effacement : cur_page est en RAM)
        call select_page
        ld hl,raster_idle               ; table raster minimale (sans danger)
        ld (pri_pending),hl
        inc hl
        ld (pri_ptr),hl
        xor a                           ; ASIC : pas de raster, split, scroll, DMA
        ld (ASIC_PRI),a
        ld (ASIC_SPLT),a
        ld (ASIC_SSCR),a
        ld (ASIC_DCSR),a
        ld hl,ASIC_PAL                  ; palette noire
        ld b,32*2
lb_pal: ld (hl),0
        inc hl
        djnz lb_pal
        ld hl,ASIC_SPR_POS+4            ; sprites invisibles
        ld b,16
lb_spr: ld (hl),0
        ld de,8
        add hl,de
        djnz lb_spr
        call pri_detect
        call psg_init
        call clear_screen
        ld a,#80                        ; ecran de presentation GX Studio (3 s)
        call gx_splash
        jp main

; pri_detect : le registre PRI compare-t-il 8 ou 9 bits du compteur de lignes ?
;   Au debut de la synchro verticale (ligne 240), PRI = 30 : en 8 bits la ligne
;   286 arrive apres ~46 lignes, en 9 bits la ligne 30 de l'image suivante apres
;   ~102 lignes. On guette le bit 7 de DCSR (interruption raster demandee) avec
;   les interruptions coupees. Sans reponse (emulateur qui ne le gere pas) :
;   9 bits. Resultat dans pri_arm (voir raster.asm).
pri_detect:
        ld a,ARM_LINE_9
        ld (pri_arm),a
        ld a,(ASIC_DCSR)
        rla
        ret c                           ; demande deja presente : mesure impossible
        ld b,PPI_B/256
pd_w0:  in a,(c)                        ; attendre la fin d'une synchro verticale...
        rra
        jr c,pd_w0
pd_w1:  in a,(c)                        ; ...puis le debut de la suivante
        rra
        jr nc,pd_w1
        ld a,30
        ld (ASIC_PRI),a
        ld hl,0
pd_l:   ld a,(ASIC_DCSR)                ; 16 us par tour
        rla
        jr c,pd_got
        inc hl
        ld a,h
        cp 4
        jr c,pd_l
        jr pd_end                       ; 16 ms sans reponse : 9 bits
pd_got: ld de,-300                      ; < 300 tours (~75 lignes) : 8 bits
        add hl,de
        jr c,pd_end
        ld a,ARM_LINE_8
        ld (pri_arm),a
pd_end: xor a
        ld (ASIC_PRI),a
        ret

; select_page : A = page de cartouche visible en &C000
select_page:
        ld (cur_page),a
        ld bc,ROMSEL
        or #80
        out (c),a
        ret

; clear_screen : &C000-&FFFF a zero (PUSH ecrit toujours en RAM)
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

; psg_w : A = registre, C = valeur (ecriture directe par le processeur)
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

; set_palette4 : HL = 4 mots &0GRB -> encres 0-3 ; bord noir
set_palette4:
        ld de,ASIC_PAL
        ld bc,8
        ldir
        ld hl,0
        ld (ASIC_BORDER),hl
        ret

; set_palette16 : HL = 16 mots &0GRB -> encres 0-15 ; bord = encre 0
set_palette16:
        ld de,ASIC_PAL
        ld bc,32
        ldir
        ld hl,(ASIC_PAL)
        ld (ASIC_BORDER),hl
        ret

; set_spr_palette : HL = 15 mots -> couleurs sprites 1-15
set_spr_palette:
        ld de,ASIC_SPR_PAL
        ld bc,30
        ldir
        ret

        include "../common/splash.asm"  ; ecran de presentation (logo, 3 secondes)
