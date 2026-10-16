;=============================================================================
; lib/common/sound_sw.asm - lecteur de son logiciel (CPC classique)
; Il interprete EXACTEMENT les memes listes que le DMA des CPC+ :
;   LOAD r,v (&0rvv)  PAUSE n (&1nnn)  REPEAT n (&2nnn)  LOOP (&4001)  STOP (&4020)
; Une pause dure n x (diviseur+1) lignes ; snd_tick est appele a chaque
; interruption (toutes les 52 lignes) et retranche 52 lignes au delai.
; Requis : variable snd_ch (3 x SND_CH_SIZE octets), psg_w.
;=============================================================================
SND_CH_SIZE     equ 10                  ; actif, ptr(2), attente(2), diviseur, boucle(2), compte(2)

; snd_play : A = canal (0-2), HL = liste, B = diviseur
snd_play:
        push hl
        call snd_chan                   ; IX = canal
        pop hl
        di
        ld (ix+1),l
        ld (ix+2),h
        ld (ix+3),0
        ld (ix+4),0
        ld (ix+5),b
        ld (ix+0),1
        ei
        ret

; snd_stop : A = canal
snd_stop:
        call snd_chan
        ld (ix+0),0
        ret

snd_stop_all:
        xor a
        ld (snd_ch),a
        ld (snd_ch+SND_CH_SIZE),a
        ld (snd_ch+2*SND_CH_SIZE),a
        ld a,8
        ld c,0
        call psg_w
        ld a,9
        ld c,0
        call psg_w
        ld a,10
        ld c,0
        jp psg_w

; snd_tempo : A = diviseur du canal 0
snd_tempo:
        ld (snd_ch+5),a
        ret

; snd_chan : A = canal -> IX
snd_chan:
        ld ix,snd_ch
        or a
        ret z
        ld de,SND_CH_SIZE
sc_l:   add ix,de
        dec a
        jr nz,sc_l
        ret

; snd_tick : appele par l'interruption (300 fois par seconde)
snd_tick:
        ld ix,snd_ch
        ld b,3
st_l:   push bc
        ld a,(ix+0)
        or a
        jr z,st_n
        ld l,(ix+3)
        ld h,(ix+4)
        ld de,-52
        add hl,de
        ld (ix+3),l
        ld (ix+4),h
        bit 7,h
        jr nz,st_run
        ld a,h
        or l
        jr nz,st_n
st_run: call snd_exec
st_n:   ld de,SND_CH_SIZE
        add ix,de
        pop bc
        djnz st_l
        ret

; snd_exec : execute la liste jusqu'a une pause ou un STOP
snd_exec:
        ld l,(ix+1)
        ld h,(ix+2)
        ld b,64                         ; securite : 64 instructions au plus
se_l:   ld e,(hl)
        inc hl
        ld d,(hl)
        inc hl
        ld a,d
        and #F0
        jr z,se_load
        cp #10
        jr z,se_pause
        cp #20
        jr z,se_rep
        bit 0,e
        jr nz,se_loop
se_stop:
        ld (ix+0),0                     ; STOP (ou instruction inconnue)
        ret
se_load:
        push bc
        push hl
        ld a,d
        ld c,e
        call psg_w
        pop hl
        pop bc
se_next:
        djnz se_l
        ld (ix+1),l
        ld (ix+2),h
        ret
se_rep: ld a,d
        and #0F
        ld (ix+9),a
        ld (ix+8),e                     ; compte
        ld (ix+6),l
        ld (ix+7),h                     ; debut de la boucle
        jr se_next
se_loop:
        ld a,(ix+8)
        or (ix+9)
        jr z,se_next
        ld a,(ix+8)
        sub 1
        ld (ix+8),a
        jr nc,se_l1
        dec (ix+9)
se_l1:  ld l,(ix+6)
        ld h,(ix+7)
        jr se_next
se_pause:
        ld (ix+1),l
        ld (ix+2),h
        ld a,d
        and #0F
        ld d,a                          ; DE = n
        ld l,(ix+3)
        ld h,(ix+4)                     ; HL = attente actuelle (<= 0)
        ld a,(ix+5)
        ld b,a
        inc b                           ; B = diviseur + 1 (0 -> 256)
sp_m:   or a
        adc hl,de
        jp pe,sp_sat                    ; depassement : attente maximale
        djnz sp_m
        ld (ix+3),l
        ld (ix+4),h
        ret
sp_sat: ld (ix+3),#FF
        ld (ix+4),#7F
        ret
