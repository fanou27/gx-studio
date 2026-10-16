#!/usr/bin/env python3
# (c) 2026 ALONSO Stephane [Fanou]
# GX Studio : produit les donnees de l'ecran de presentation (splash) commun a
# tous les jeux : templates/lib/common/splash_data.asm
#   - le logo FANOU 1972, redessine en pixels mode 0 (16 encres), compacte (RLE) ;
#   - les textes ("Jeux developpe avec" / "GXStudio By Fanou", "FANOU 1972"...),
#     ecrits a l'execution avec une petite police 8 x 16 (Unifont) : seuls les
#     caracteres utilises sont gardes.
# Usage : python3 tools/mksplash.py [apercu.png]
import os, sys
from PIL import Image, ImageDraw, ImageFont
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, '..', 'templates', 'lib', 'common', 'splash_data.asm')
FB = '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'
FU = ImageFont.truetype('/usr/share/fonts/opentype/unifont/unifont.otf', 16)
# encres 0-15 : couleurs du firmware (0-26)
FW = [0, 1, 20, 6, 18, 2, 13, 26, 24, 3, 15, 14, 10, 4, 0, 0]
LV = [0, 128, 255]
def fw_rgb(n): return (LV[(n // 3) % 3], LV[n // 9], LV[n % 3])
RGB = [fw_rgb(n) for n in FW]
BLACK, NAVY, CYAN, RED, GREEN, BLUE, GREY, WHITE, YELLOW, DRED, ORANGE, LBLUE = range(12)
PAL = sum(RGB, ()) + (0, 0, 0) * (256 - 16)
SW, SH = 160, 200                       # ecran mode 0

# ---- le logo : dessine sur une grille "carree" (2 colonnes = 1 pixel mode 0)
LW, LH = 176, 118                       # 88 pixels mode 0 x 118 lignes
lg = Image.new('P', (LW, LH), BLACK); lg.putpalette(PAL)
d = ImageDraw.Draw(lg)
d.rounded_rectangle([0, 0, LW - 1, LH - 1], 8, fill=CYAN)
d.rounded_rectangle([4, 2, LW - 5, LH - 3], 7, fill=NAVY)
for i, c in enumerate((RED, GREEN, BLUE)):          # les 3 bandes R, V, B
    x = 50 + i * 26
    d.polygon([(x + 8, 7), (x + 28, 7), (x + 20, 16), (x, 16)], fill=c)
for i in range(7):                                  # pattes du composant
    x = 47 + i * 13
    d.rectangle([x, 22, x + 5, 29], fill=GREY)
    d.rectangle([x, 87, x + 5, 94], fill=GREY)
for i in range(5):
    y = 37 + i * 10
    d.rectangle([22, y, 33, y + 3], fill=GREY)
    d.rectangle([142, y, 153, y + 3], fill=GREY)
d.rectangle([32, 28, 143, 88], fill=BLACK)         # le composant
d.rectangle([35, 30, 140, 86], fill=GREY)
d.ellipse([40, 33, 48, 40], fill=BLACK)
d.ellipse([127, 76, 135, 83], fill=BLACK)
d.text((88, 59), "A", font=ImageFont.truetype(FB, 50), fill=WHITE, anchor='mm')
scr = Image.new('P', (SW, SH), BLACK); scr.putpalette(PAL)
LX, LY = (SW - LW // 2) // 2, 14                    # coin du logo (pixels mode 0)
for y in range(LH):
    for x in range(LW // 2):
        a, b = lg.getpixel((2 * x, y)), lg.getpixel((2 * x + 1, y))
        scr.putpixel((LX + x, LY + y), a if a in (WHITE, GREY) or b in (NAVY, BLACK) else b)
logo = scr.copy()

# ---- textes (dessines a l'execution) : (x pixel pair, y, encre, texte)
def centered(t): return (SW - int(FU.getlength(t))) // 2 & ~1
TEXTS = [(LX + 14, LY + 51, YELLOW, "</"), (LX + 58, LY + 51, YELLOW, "/>"),
         (centered("FANOU 1972"), LY + 97, RED, "FANOU 1972"),
         (centered("Jeux développé avec"), 148, WHITE, "Jeux développé avec"),
         (centered("GXStudio By Fanou"), 168, CYAN, "GXStudio"),
         (centered("GXStudio By Fanou") + 72, 168, WHITE, "By"),
         (centered("GXStudio By Fanou") + 96, 168, YELLOW, "Fanou")]
chars = []
for x, y, c, t in TEXTS:
    for ch in t:
        if ch != ' ' and ch not in chars: chars.append(ch)
def glyph(ch):
    m = Image.new('1', (8, 16), 0)
    dm = ImageDraw.Draw(m); dm.fontmode = '1'
    dm.text((0, 0), ch, font=FU, fill=1)
    return [sum(m.getpixel((x, y)) << (7 - x) for x in range(8)) for y in range(16)]
GLY = {ch: glyph(ch) for ch in chars}
for x, y, c, t in TEXTS:                            # apercu
    for k, ch in enumerate(t):
        if ch == ' ': continue
        for yy, row in enumerate(GLY[ch]):
            for xx in range(8):
                if row & (0x80 >> xx): scr.putpixel((x + 8 * k + xx, y + yy), c)

# ---- octets mode 0 et compactage du logo
def m0(p0, p1):
    def bits(p, s):
        return (((p & 1) << 7) | ((p & 2) << 2) | ((p & 4) << 3) | ((p & 8) >> 2)) >> s
    return bits(p0, 0) | bits(p1, 1)
X0, W, Y0, H = LX // 2, LW // 4, LY, LH
raw = bytearray()
for y in range(Y0, Y0 + H):
    for xb in range(X0, X0 + W):
        raw.append(m0(logo.getpixel((2 * xb, y)), logo.getpixel((2 * xb + 1, y))))
# RLE : n < &80 : n+1 octets copies ; n >= &80 : (n & &7F) + 2 fois l'octet suivant
out, i = bytearray(), 0
while i < len(raw):
    j = i
    while j + 1 < len(raw) and raw[j + 1] == raw[i] and j - i < 128: j += 1
    if j > i:
        out += bytes([0x80 | (j - i - 1), raw[i]]); i = j + 1
    else:
        k = i + 1
        while k < len(raw) and k - i < 128 and not (k + 1 < len(raw) and raw[k + 1] == raw[k]): k += 1
        out += bytes([k - i - 1]) + raw[i:k]; i = k
def asic(rgb):
    q = lambda v: 0 if v < 64 else (6 if v < 192 else 15)
    return (q(rgb[1]) << 8) | (q(rgb[0]) << 4) | q(rgb[2])
L = ['; (c) 2026 ALONSO Stephane [Fanou]',
     '; GX Studio : donnees de l\'ecran de presentation (splash.asm)',
     '; produit par tools/mksplash.py - ne pas modifier a la main',
     'SPLASH_X        equ %d                  ; logo : octet de depart dans la ligne' % X0,
     'SPLASH_Y        equ %d                  ; premiere ligne' % Y0,
     'SPLASH_W        equ %d                  ; largeur (octets)' % W,
     'SPLASH_H        equ %d                 ; hauteur (lignes)' % H,
     'splash_fw:                              ; encres 0-15 (couleurs du firmware)',
     '        db ' + ','.join(str(n) for n in FW),
     'splash_asic:                            ; encres 0-15 (CPC+ : &0GRB)',
     '        dw ' + ','.join('#%03X' % asic(c) for c in RGB),
     'splash_texts:                           ; x (pixel pair), y, encre, caracteres (police), &FF',
     ]
for x, y, c, t in TEXTS:
    L.append('        db %d,%d,%d,%s,#FF          ; %s' % (x, y, c, ','.join(str(chars.index(ch)) if ch != ' ' else '#FE' for ch in t), t.encode('ascii', 'replace').decode()))
L.append('        db #FF')
L.append('splash_font:                            ; %d caracteres 8 x 16 (Unifont)' % len(chars))
for ch in chars:
    L.append('        db ' + ','.join('#%02X' % b for b in GLY[ch]) + '    ; ' + (ch if ord(ch) < 128 else '?'))
L.append('splash_rle:                             ; logo mode 0, RLE (%d -> %d octets)' % (len(raw), len(out)))
for p in range(0, len(out), 16):
    L.append('        db ' + ','.join('#%02X' % b for b in out[p:p + 16]))
open(OUT, 'w').write('\n'.join(L) + '\n')
print('splash : logo %d x %d octets -> %d ; police %d caracteres ; total ~%d octets' % (W, H, len(out), len(chars), len(out) + 16 * len(chars) + 32 + 16 + 80))
if len(sys.argv) > 1:
    scr.convert('RGB').resize((SW * 4, SH * 2), Image.NEAREST).save(sys.argv[1])
