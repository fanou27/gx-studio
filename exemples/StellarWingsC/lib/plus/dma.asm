;=============================================================================
; lib/dma.asm - son par le DMA de l'ASIC
; Chaque canal (0, 1, 2) lit en RAM une liste d'instructions de 16 bits et
; ecrit lui-meme dans le PSG, une instruction par ligne d'ecran, sans le Z80 :
;   LOAD r,v (&0rvv)   PAUSE n (&1nnn)   REPEAT n (&2nnn)   LOOP (&4001)  STOP (&4020)
; Une pause dure n x (diviseur+1) lignes (64 us). Les listes sont generees par
; tools/gfx.py puis recopiees en RAM au demarrage (le DMA ne lit que la RAM).
;=============================================================================

; dma_init : recopie les listes en RAM et arrete les canaux
dma_init:
        xor a
        ld (dma_en),a
        ld (ASIC_DCSR),a
        ld hl,dma_lists
        ld de,DMA_RAM
        ld bc,DMA_LISTS_SIZE
        ldir
        ret

; dma_bit : A = canal (0-2) -> A = masque (1, 2 ou 4)
dma_bit:
        inc a
        push bc
        ld b,a
        ld a,%10000000
db_l:   rlca
        djnz db_l
        pop bc
        ret

; dma_play : A = canal (0-2), HL = liste (en RAM), B = diviseur
;   le canal est coupe, reprogramme puis relance (depart au debut de la liste)
dma_play:
        push af
        call dma_stop
        pop af
        push af
        add a,a
        add a,a
        ld e,a
        ld d,ASIC_DMA0/256
        ex de,hl                        ; HL = registres du canal, DE = liste
        ld (hl),e
        inc hl
        ld (hl),d
        inc hl
        ld (hl),b
        pop af
        call dma_bit
        ld hl,dma_en
        or (hl)
        ld (hl),a
        ld (ASIC_DCSR),a
        ret

; dma_stop : A = canal  (B et HL preserves)
dma_stop:
        call dma_bit
        cpl
        push hl
        ld hl,dma_en
        and (hl)
        ld (hl),a
        ld (ASIC_DCSR),a
        pop hl
        ret

; dma_stop_all : coupe les 3 canaux et le son
dma_stop_all:
        xor a
        ld (dma_en),a
        ld (ASIC_DCSR),a
        ld a,8
        ld c,0
        call psg_w
        ld a,9
        ld c,0
        call psg_w
        ld a,10
        ld c,0
        jp psg_w

; dma_tempo : A = nouveau diviseur du canal 0 (change le tempo en cours de lecture)
dma_tempo:
        ld (ASIC_DMA0+2),a
        ret
