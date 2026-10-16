;=============================================================================
; lib/common/input.asm - manettes 1 et 2 (lignes 9 et 6 de la matrice clavier) + clavier
;=============================================================================
key_line:                               ; D = ligne -> A = touches appuyees (1 = appuye)
        ld bc,PPI_A+14
        out (c),c
        ld bc,PPI_C+#C0
        out (c),c
        ld bc,PPI_C+#00
        out (c),c
        ld bc,PPI_CTRL+#92
        out (c),c
        ld a,d
        or #40
        ld b,PPI_C/256
        out (c),a
        ld b,PPI_A/256
        in a,(c)
        ld bc,PPI_CTRL+#82
        out (c),c
        ld bc,PPI_C+#00
        out (c),c
        cpl
        ret

; read_joy : joy = manettes 1+2 (+ fleches/ESPACE/COPY si HAS_KEYBOARD) ;
;   joy_new = nouveaux appuis. Avec le lecteur de son logiciel (SOUND_SW=1),
;   les interruptions sont coupees pendant la lecture (il ecrit aussi dans le PSG).
read_joy:
        if SOUND_SW
        di
        endif
        ld d,9
        call key_line
        ld e,a
        ld d,6
        call key_line
        or e
        and #3F
        ld e,a
        if HAS_KEYBOARD
        ld d,0
        call key_line
        rra
        jr nc,rj1
        set J_UP,e
rj1:    rra
        jr nc,rj2
        set J_RIGHT,e
rj2:    rra
        jr nc,rj3
        set J_DOWN,e
rj3:    ld d,1
        call key_line
        rra
        jr nc,rj4
        set J_LEFT,e
rj4:    rra
        jr nc,rj5
        set J_FIRE2,e
rj5:    ld d,5
        call key_line
        rla
        jr nc,rj6
        set J_FIRE1,e
rj6:
        endif
        if SOUND_SW
        ei
        endif
        ld a,(joy)
        cpl
        and e
        ld (joy_new),a
        ld a,e
        ld (joy),a
        ret
