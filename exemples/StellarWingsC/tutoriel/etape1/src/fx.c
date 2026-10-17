/*============================================================================
 *  fx.c - etape 1 : hasard, score, panneau, messages, etoiles
 *==========================================================================*/
#include "stellar.h"

uint16_t rnd;                   /* graine du hasard */
uint8_t  hud_dirty;
uint8_t  star_skip;

/*---- rand8 : nombre pseudo-aleatoire (xorshift 16 bits, comme l'original) */
uint8_t rand8(void) __sdcccall(0)
{
    uint8_t h = rnd >> 8, l = (uint8_t)rnd;
    h ^= (uint8_t)((l >> 1) | (h << 7));
    l ^= (uint8_t)((h >> 1) | (l << 7));
    h ^= l;
    rnd = ((uint16_t)h << 8) | l;
    return h;
}

int16_t abs16(int16_t v) { return v < 0 ? -v : v; }

/*---- score : en dizaines de points (les points du jeu sont tous multiples de 10)
 *     une vie supplementaire en passant 10 000 points */
void add_score(uint16_t tens)
{
    uint16_t old = ply.score;
    ply.score += tens;
    if (ply.score < old) ply.score = 0xFFFF;        /* (plafond : 655 350 points) */
    hud_dirty |= 1;
    if (old < 1000 && ply.score >= 1000) {
        hud_dirty |= 2;
        ply.lives++;
        GX_SOUND(CAPSULE);
    }
}

/* update_hi : record battu ? (1 = oui, record mis a jour) */
uint8_t update_hi(void)
{
    if (ply.score <= hiscore) return 0;
    hiscore = ply.score;
    return 1;
}

/* print_score : 6 chiffres (5 chiffres des dizaines, puis 0) */
void print_score(uint8_t col, uint8_t line, uint16_t tens)
{
    gx_text_num(col, line, tens, 5);
    gx_text(col + 5, line, "0");
}

/*---- panneau : ligne 0 (scores, record) et ligne 192 (vies, vague) -------*/
static void hud_lives(void)
{
    char s[2];
    gx_ink(3);
    s[0] = '0' + (ply.lives < 10 ? ply.lives : 9);
    s[1] = 0;
    gx_text(5, 192, s);
    gx_text_num(35, 192, ply.wave, 2);
}

void hud_draw(void)
{
    gx_ink(2);
    gx_text(0, 0, "1UP");
    gx_text(16, 0, "HI");
    gx_text(0, 192, "VIES");
    gx_text(29, 192, "VAGUE");
    if (nplayers == 2) {
        gx_text(30, 0, "2UP");
        gx_ink(3);
        print_score(34, 0, cur_player == 0 ? pblk[1].score : ply.score);
    }
    gx_ink(3);
    print_score(4, 0, cur_player != 0 ? pblk[0].score : ply.score);
    print_score(19, 0, hiscore);
    hud_dirty = 0;
    hud_lives();
}

/* hud_update : redessine ce qui a change (score du joueur, record, vies) */
void hud_update(void)
{
    uint8_t d = hud_dirty;
    hud_dirty = 0;
    if (d & 2) hud_lives();
    if (d & 1) {
        gx_ink(3);
        print_score(cur_player ? 34 : 4, 0, ply.score);
        if (update_hi()) print_score(19, 0, hiscore);
    }
}

/*---- messages au centre de l'ecran (lignes 124-151, les etoiles l'evitent) */
void msg_open(void)
{
    star_skip = 1;
    gx_clear_lines(MSG_TOP, MSG_LINES);
}

void msg_close(void)
{
    gx_clear_lines(MSG_TOP, MSG_LINES);
    star_skip = 0;
}

void msg_center(const char *s, uint8_t line)
{
    uint8_t n = 0;
    while (s[n]) n++;
    gx_text((40 - n) >> 1, line, s);
}

/*---- etoiles : 40 points en mode 1, 3 vitesses (encres 1, 2, 3) ----------*/
typedef struct { uint8_t col, pat, y, speed; } star_t;   /* 4 octets : lus par stars.asm */
star_t stars[NSTARS];
static const uint8_t pat_tab[12] = {
    0x80, 0x40, 0x20, 0x10,     /* encre 1, pixel 0-3 du octet */
    0x08, 0x04, 0x02, 0x01,     /* encre 2 */
    0x88, 0x44, 0x22, 0x11      /* encre 3 */
};

static uint8_t star_col(void)   /* colonne (octet) au hasard, 0-79 */
{
    uint8_t a = rand8() & 127;
    return a < 80 ? a : a - 48;
}

void stars_init(void)
{
    uint8_t i, a, sp;
    star_t *s = stars;
    for (i = NSTARS; i; i--, s++) {
        s->col = star_col();
        a = rand8() & 127;
        s->y = a + (rand8() & 31) + 16;
        sp = i & 3;
        if (!sp) sp = 1;
        s->speed = sp;                              /* vitesse 1 a 3 = encre */
        s->pat = pat_tab[(sp - 1) * 4 + (rand8() & 3)];
    }
}

/* stars_update : effacer, descendre, redessiner les 40 etoiles a chaque image */
static uint8_t *star_ptr(uint8_t y, uint8_t col)    /* 0 = dans la zone des messages */
{
    if (star_skip && (uint8_t)(y - MSG_TOP) < MSG_LINES) return 0;
    return GX_LINE(y) + col;
}

void stars_update(void) __sdcccall(0)
{
    uint8_t i, y;
    uint8_t *p;
    star_t *s = stars;
    for (i = NSTARS; i; i--, s++) {
        p = star_ptr(s->y, s->col);
        if (p) *p = 0;                              /* efface */
        y = s->y + s->speed;
        if (y >= 184) {                             /* repart en haut, colonne au hasard */
            y -= 168;
            s->col = star_col();
        }
        s->y = y;
        p = star_ptr(y, s->col);
        if (p) *p = s->pat;                         /* dessine */
    }
}
