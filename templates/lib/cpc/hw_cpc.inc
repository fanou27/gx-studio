;=============================================================================
; lib/cpc/hw_cpc.inc - constantes materielles CPC 464 / 664 / 6128 (GX Studio)
;=============================================================================
GA              equ #7F00               ; Gate Array : encres, mode, ROM, banques
GA_ROMOFF       equ #8C                 ; RMR : ROM basse et haute coupees (+ mode)
CRTC_SEL        equ #BC00
CRTC_DATA       equ #BD00
PPI_A           equ #F400
PPI_B           equ #F500
PPI_C           equ #F600
PPI_CTRL        equ #F700
; --- bits manette (1 = appuye) : ligne 9 (manette 1) et 6 (manette 2)
J_UP            equ 0
J_DOWN          equ 1
J_LEFT          equ 2
J_RIGHT         equ 3
J_FIRE1         equ 4
J_FIRE2         equ 5
; --- PSG
PSG_MIXER       equ %00011100           ; tonalites A et B, bruit sur C, port A en entree
; --- banques (CPC 6128 et extensions) : valeur envoyee au Gate Array
BANK_NORMAL     equ #C0                 ; 64 Ko de base
; #C4..#C7 : banque 4..7 en &4000 (128 Ko) ; +8 par bloc de 64 Ko au-dela
