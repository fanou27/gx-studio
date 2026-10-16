;=============================================================================
; lib/hw.inc - constantes materielles GX4000 / CPC+   (bibliotheque reutilisable)
;=============================================================================
GA              equ #7F00               ; Gate Array
RMR2_ASIC       equ #B8                 ; page 0 en &0000 + registres ASIC en &4000
RMR2_NOASIC     equ #A0                 ; page 0 en &0000 sans registres ASIC
ROMSEL          equ #DF00               ; selection ROM haute : &80 + page
CRTC_SEL        equ #BC00
CRTC_DATA       equ #BD00
PPI_A           equ #F400
PPI_B           equ #F500
PPI_C           equ #F600
PPI_CTRL        equ #F700
; --- ASIC (registres visibles en &4000-&7FFF) ---
ASIC_SPR_DATA   equ #4000               ; 16 sprites x 256 octets
ASIC_SPR_POS    equ #6000               ; X(2) Y(2) zoom(1), pas de 8
ASIC_PAL        equ #6400               ; encres ecran 0-15 (mot &0GRB)
ASIC_BORDER     equ #6420
ASIC_SPR_PAL    equ #6422               ; couleurs sprites 1-15
ASIC_PRI        equ #6800               ; ligne d'interruption raster
ASIC_SPLT       equ #6801
ASIC_SSA        equ #6802
ASIC_SSCR       equ #6804
ASIC_IVR        equ #6805
ASIC_DMA0       equ #6C00               ; canal 0 : adresse (2), diviseur (1)
ASIC_DMA1       equ #6C04
ASIC_DMA2       equ #6C08
ASIC_DCSR       equ #6C0F               ; bits 0-2 : canaux DMA actifs
; --- zoom des sprites : bits 3-2 = X, bits 1-0 = Y (01 x1, 10 x2, 11 x4)
ZOOM_X2Y1       equ %1001
ZOOM_X4Y2       equ %1110
ZOOM_X4Y1       equ %1101
; --- bits manette (1 = appuye)
J_UP            equ 0
J_DOWN          equ 1
J_LEFT          equ 2
J_RIGHT         equ 3
J_FIRE1         equ 4               ; (manette : bit 4 = tir 1, bit 5 = tir 2)
J_FIRE2         equ 5
; --- PSG
PSG_MIXER       equ %00011100           ; tonalites A et B, bruit sur C, port A en entree
