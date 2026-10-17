/*============================================================================
 *  aliens.c - etape 2 : la formation (sprites multiplexes)
 *
 *  La formation compte 4 rangees de 6 aliens mais n'utilise que les sprites
 *  0-5 : une interruption raster par rangee (juste avant elle) repositionne
 *  les six sprites et change leurs couleurs 1-4 (multiplexage). Les ailes
 *  battent par la palette : la couleur 3 (ailes hautes) ou 4 (ailes basses)
 *  passe au noir.
 *==========================================================================*/
#include "stellar.h"

uint8_t is_boss, march_on, alive_count;
uint8_t wing;                           /* 0 / 1 : ailes hautes / basses */
static int16_t fx;                      /* abscisse de la colonne 0 (pixels mode 2, signee) */
static uint8_t fy;                      /* ligne de la rangee 0 */
static uint8_t fdir;                    /* 0 = vers la droite, 1 = vers la gauche */
static uint8_t fsub;                    /* fraction de pixel (1/4) */
static uint8_t diving[4];               /* aliens partis en pique (meme format que ply.alive) */
static uint8_t wave_type;               /* 0, 1, 2 : forme des aliens */
static const uint16_t *rowpal;          /* couleurs des 4 rangees */
static uint8_t last_tempo;
static uint8_t lcol, rcol, brow;        /* colonnes extremes, derniere rangee occupee */

/* couleurs des sprites 1-4 de chaque rangee, pour chaque type d'alien (&0GRB) */
static const uint16_t rowpal_tab[3 * 16] = {
    0x0DF3, 0x0FFF, 0x04EC, 0x04EC,  0x09E2, 0x0FFF, 0x04AF, 0x04AF,
    0x0DF3, 0x0FFF, 0x04EC, 0x04EC,  0x09E2, 0x0FFF, 0x04AF, 0x04AF,
    0x0F99, 0x0FFF, 0x0FF4, 0x04F4,  0x0C6F, 0x0FFF, 0x0FF4, 0x04F4,
    0x0F99, 0x0FFF, 0x0FF4, 0x04F4,  0x0C6F, 0x0FFF, 0x0FF4, 0x04F4,
    0x06CF, 0x0FF6, 0x049D, 0x049D,  0x06FA, 0x0FF6, 0x04C9, 0x04C9,
    0x06CF, 0x0FF6, 0x049D, 0x049D,  0x06FA, 0x0FF6, 0x04C9, 0x04C9
};
static const uint8_t  row_pts[4]  = { 5, 4, 3, 2 };    /* dizaines de points par rangee */
static const uint8_t  bits[8] = { 1, 2, 4, 8, 16, 32, 64, 128 };

/*---- aliens_reset : appele par turn_reset ----------------------------------*/
void aliens_reset(void)
{
    uint8_t i;
    for (i = 0; i < 4; i++) diving[i] = 0;
    fx = 184;
    fy = 24;
    fdir = fsub = 0;
}

/*---- wave_prepare : type d'aliens, boss ou formation, images des sprites 0-5 */
void wave_prepare(void)
{
    uint8_t i;
    is_boss = 0;                                /* (le vaisseau mere viendra a l'etape 3) */
    wave_type = (ply.wave - 1) % 3;
    rowpal = rowpal_tab + wave_type * 16;
    if (!(ply.alive[0] | ply.alive[1] | ply.alive[2] | ply.alive[3]))
        for (i = 0; i < 4; i++) ply.alive[i] = 0x3F;  /* formation vide : on la remplit */
    for (i = 0; i < 6; i++) spr_want[i] = F_ALIEN_A + wave_type;
}

/* wave_cleared : 1 = vague terminee */
uint8_t wave_cleared(void)
{
    if (expl_active()) return 0;
    return !alive_count;
}

/*---- comptes de la formation ---------------------------------------------*/
/* count_alive : nombre d'aliens, colonnes extremes, derniere rangee occupee */
void count_alive(void)
{
    uint8_t r, a, n = 0, cols = 0;
    brow = 0;
    for (r = 0; r < 4; r++) {
        a = ply.alive[r];
        if (a) {
            cols |= a;
            brow = r;
            for (; a; a >>= 1) n += a & 1;
        }
    }
    alive_count = n;
    if (!cols) return;
    for (lcol = 0; !(cols & bits[lcol]); lcol++) ;
    for (rcol = 5; !(cols & bits[rcol]); rcol--) ;
}

/* alien_visible : l'alien (rangee, colonne) est-il dans la formation ? */
static uint8_t alien_visible(uint8_t row, uint8_t col)
{
    uint8_t b = bits[col];
    return (ply.alive[row] & b) && !(diving[row] & b);
}

/* alien_destroy : l'alien disparait */
static void alien_destroy(uint8_t row, uint8_t col)
{
    uint8_t m = ~bits[col];
    ply.alive[row] &= m;
    diving[row] &= m;
    count_alive();
}

/* col_bottom : rangee de l'alien le plus bas d'une colonne (0xFF = aucun) */
static uint8_t col_bottom(uint8_t col)
{
    int8_t r;
    for (r = 3; r >= 0; r--)
        if (alien_visible(r, col)) return r;
    return 0xFF;
}

/*---- la marche (musique de la formation) : accelere quand la formation fond */
static uint8_t march_tempo_val(void)
{
    return alive_count + 4;
}

void march_start(void)
{
    march_on = 1;
    last_tempo = march_tempo_val();
    gx_sound(MARCHE_CH, MARCHE_OFS, last_tempo);
}

void march_stop(void)
{
    march_on = 0;
    gx_sound_stop(0);
    gx_psg(8, 0);                               /* volume de la voie A a zero */
}

/*---- form_update : balancement, descente, tempo de la marche -------------*/
void form_update(void)
{
    uint8_t v, s, edge;
    int16_t ex;
    wing = (frame_count >> 3) & 1;
    if (march_on) {
        v = march_tempo_val();
        if (v != last_tempo) {
            last_tempo = v;
            gx_sound_tempo(v);
        }
    }
    if (frozen || !alive_count) return;
    v = ((28 - alive_count) >> 2) + 3;          /* vitesse en 1/4 de pixel par image */
    s = ply.wave >> 1;
    v += s < 7 ? s : 6;
    if (alive_count == 1) v += 8;               /* le dernier alien fonce */
    s = fsub + v;
    fsub = s & 3;
    v = s >> 2;
    if (!fdir) {
        fx += v;
        ex = fx + rcol * COL_PITCH;             /* X de la colonne la plus a droite */
        if (ex < 640 - 16 - 32) return;
    } else {
        fx -= v;
        ex = fx + lcol * COL_PITCH;             /* X de la colonne la plus a gauche */
        if (ex >= 16) return;
    }
    fdir ^= 1;                                  /* demi-tour... */
    if (fy < 148) fy += 4;                      /* ...et la formation descend d'un cran */
    edge = brow * ROW_PITCH + fy + 16;
    if (edge >= 164) kill_ship();               /* invasion : la formation atteint le vaisseau */
}

/*---- tables raster (double tampon) ---------------------------------------*/
static uint8_t rbuf;
static uint8_t rtab[2][80];                     /* tables raster */
static uint8_t bands[2][4 * GX_BAND_SIZE];      /* blocs des bandes de sprites */
static uint8_t *cur_tab, *br_blk;

/* next_tab : passe a l'autre tampon (celui que les interruptions ne lisent pas) */
uint8_t *next_tab(void)
{
    rbuf ^= 1;
    cur_tab = rtab[rbuf];
    br_blk = bands[rbuf];
    return cur_tab;
}

/* tab_tail : fin d'image + armement, puis publication de la table */
void tab_tail(uint8_t *p)
{
    p[0] = 2; p[1] = 0; p[2] = 0; p[3] = RASTER_ARM_LINE;
    p[4] = 3; p[5] = 0; p[6] = 0; p[7] = 0;
    gx_raster_queue(cur_tab);                   /* prise en compte a la fin de l'image */
}

/* put_spr : X, Y, zoom d'un sprite dans le bloc d'une bande */
static uint8_t *put_spr(uint8_t *b, int16_t x, uint8_t y, uint8_t zoom)
{
    b[0] = (uint8_t)x;
    b[1] = (uint8_t)(x >> 8);
    b[2] = y;
    b[3] = 0;
    b[4] = zoom;
    return b + 5;
}

/* put_colors : couleurs 1-4 des sprites (4 mots &0GRB) */
static uint8_t *put_colors(uint8_t *b, const uint16_t *pal)
{
    uint8_t i;
    for (i = 0; i < 4; i++) {
        *b++ = (uint8_t)pal[i];
        *b++ = (uint8_t)(pal[i] >> 8);
    }
    return b;
}

/* band_row : remplit le bloc d'une bande (6 sprites + 4 couleurs) */
void band_row(uint8_t *b, int16_t x, uint8_t y, uint8_t vis,
              const uint16_t *pal, uint8_t wg) __sdcccall(0)
{
    uint8_t i;
    for (i = 0; i < 6; i++) {
        b = put_spr(b, x, y, (vis & 1) ? ZOOM_X2Y1 : 0);
        vis >>= 1;
        x += COL_PITCH;
    }
    put_colors(b, pal);
    if (wg) b[4] = b[5] = 0;                    /* ailes basses : couleur 3 au noir */
    else    b[6] = b[7] = 0;                    /* ailes hautes : couleur 4 au noir */
}

/* build_raster : construit la table raster de l'image suivante
 * (une entree "bande de sprites" par rangee, juste avant elle) */
void build_raster(void)
{
    uint8_t *t = next_tab(), *b = br_blk;
    uint8_t r, y = fy;
    *t++ = fy - 7;                              /* 1re interruption : 7 lignes avant la rangee 0 */
    for (r = 0; r < 4; r++) {
        *t++ = 1;                               /* entree : bande de sprites */
        *t++ = (uint8_t)(uint16_t)b;
        *t++ = (uint16_t)b >> 8;
        *t++ = r == 3 ? 199 : y + ROW_PITCH - 7;
        band_row(b, fx, y, ply.alive[r] & ~diving[r], rowpal + r * 4, wing);
        b += GX_BAND_SIZE;
        y += ROW_PITCH;
    }
    tab_tail(t);
}

/*---- collisions des tirs du joueur (hx, hy = pointe du laser) -----------*/
/* hit_formation : 1 = un alien de la formation est touche */
uint8_t hit_formation(int16_t hx, uint8_t hy)
{
    int16_t dx = hx - fx;
    uint8_t c, r, rx, ry;
    if (dx < 0 || dx >= 512) return 0;
    for (c = 0; dx >= COL_PITCH; c++) dx -= COL_PITCH;  /* colonne (division sans routine de division) */
    rx = (uint8_t)dx;
    if (c >= 6 || rx < 4 || rx >= 28) return 0; /* entre deux colonnes */
    if (hy < fy) return 0;
    ry = hy - fy;
    for (r = 0; ry >= ROW_PITCH; r++) ry -= ROW_PITCH;  /* rangee */
    if (ry >= 16 || r >= 4) return 0;
    if (!alien_visible(r, c)) return 0;
    alien_destroy(r, c);
    add_score(row_pts[r]);
    expl_start(fx + c * COL_PITCH, fy + r * ROW_PITCH);  /* explosion a la place de l'alien */
    return 1;
}

