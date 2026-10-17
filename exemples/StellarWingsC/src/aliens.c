/*============================================================================
 *  aliens.c - formation (sprites multiplexes), plongeurs, bombes, boss
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
static uint8_t launch_t, bomb_t;        /* minuteurs : prochain plongeur, prochaine bombe */
static diver_t divers[NDIVERS];
static shot_t  bombs[2];                /* sprites 9 et 10 */
static int16_t bx;                      /* vaisseau mere */
static uint8_t by, bdir, bflash, bfire, bdeath;

/* couleurs des sprites 1-4 de chaque rangee, pour chaque type d'alien (&0GRB) */
static const uint16_t rowpal_tab[3 * 16] = {
    0x0DF3, 0x0FFF, 0x04EC, 0x04EC,  0x09E2, 0x0FFF, 0x04AF, 0x04AF,
    0x0DF3, 0x0FFF, 0x04EC, 0x04EC,  0x09E2, 0x0FFF, 0x04AF, 0x04AF,
    0x0F99, 0x0FFF, 0x0FF4, 0x04F4,  0x0C6F, 0x0FFF, 0x0FF4, 0x04F4,
    0x0F99, 0x0FFF, 0x0FF4, 0x04F4,  0x0C6F, 0x0FFF, 0x0FF4, 0x04F4,
    0x06CF, 0x0FF6, 0x049D, 0x049D,  0x06FA, 0x0FF6, 0x04C9, 0x04C9,
    0x06CF, 0x0FF6, 0x049D, 0x049D,  0x06FA, 0x0FF6, 0x04C9, 0x04C9
};
static const uint16_t pal_boss[4]   = { 0x0DDF, 0x03F3, 0x0C4F, 0x0FF4 };
static const uint16_t pal_white4[4] = { 0x0FFF, 0x0FFF, 0x0FFF, 0x0FFF };
static const uint8_t  row_pts[4]  = { 5, 4, 3, 2 };    /* dizaines de points par rangee */
static const uint8_t  dive_pts[4] = { 10, 8, 6, 4 };   /* en pique : le double */
static const uint8_t  bits[8] = { 1, 2, 4, 8, 16, 32, 64, 128 };

/*---- aliens_reset : appele par turn_reset ----------------------------------*/
void aliens_reset(void)
{
    uint8_t i;
    for (i = 0; i < 4; i++) diving[i] = 0;
    for (i = 0; i < NDIVERS; i++) divers[i].state = 0;
    bombs[0].on = bombs[1].on = 0;
    fx = 184;
    fy = 24;
    fdir = fsub = 0;
    bdir = bflash = bdeath = 0;
    bx = 256;
    by = 28;
    bfire = 60;
    launch_t = 150;
    bomb_t = 90;
}

/*---- wave_prepare : type d'aliens, boss ou formation, images des sprites 0-5 */
void wave_prepare(void)
{
    uint8_t i, hp;
    is_boss = (ply.wave & 3) == 0;              /* un vaisseau mere toutes les 4 vagues */
    wave_type = (ply.wave - 1) % 3;
    rowpal = rowpal_tab + wave_type * 16;
    if (is_boss) {
        if (!ply.bhp) {                         /* points de vie si nouveau boss */
            hp = ply.wave * 2 + 20;
            ply.bhp = hp < 60 ? hp : 60;
        }
        for (i = 0; i < 4; i++) {
            ply.alive[i] = 0;
            spr_want[i] = F_BOSS0 + i;          /* sprites 0-3 = les 4 quarts du boss */
        }
    } else {
        if (!(ply.alive[0] | ply.alive[1] | ply.alive[2] | ply.alive[3]))
            for (i = 0; i < 4; i++) ply.alive[i] = 0x3F;  /* formation vide : on la remplit */
        for (i = 0; i < 6; i++) spr_want[i] = F_ALIEN_A + wave_type;
    }
}

/* wave_cleared : 1 = vague terminee */
uint8_t wave_cleared(void)
{
    if (expl_active()) return 0;
    if (is_boss) return !bdeath && !ply.bhp;
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
    return is_boss ? 5 : alive_count + 4;
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

static void build_boss(uint8_t *t)
{
    uint8_t *b = br_blk;
    uint8_t zoom = (ply.bhp | bdeath) ? ZOOM_X4Y2 : 0;
    *t++ = by - 7;                              /* une seule bande : les 4 quarts du boss */
    *t++ = 1;
    *t++ = (uint8_t)(uint16_t)b;
    *t++ = (uint16_t)b >> 8;
    *t++ = 199;
    b = put_spr(b, bx, by, zoom);               /* 4 x (32 x 32) = 128 x 64 pixels */
    b = put_spr(b, bx + 64, by, zoom);
    b = put_spr(b, bx, by + 32, zoom);
    b = put_spr(b, bx + 64, by + 32, zoom);
    b = put_spr(b, bx + 64, by + 32, 0);        /* sprites 4 et 5 inutilises */
    b = put_spr(b, bx + 64, by + 32, 0);
    put_colors(b, bflash ? pal_white4 : pal_boss);
    tab_tail(t);
}

#if BANDES_EN_C
/* band_row en C (pour comparer avec src/bands.asm : definir BANDES_EN_C=1) */
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
#endif

/* build_raster : construit la table raster de l'image suivante
 * (une entree "bande de sprites" par rangee, juste avant elle). Le contenu
 * de chaque bande (6 sprites + 4 couleurs) est rempli par band_row, ecrite
 * en assembleur (src/bands.asm) : c'etait la 2e routine la plus lente en C. */
void build_raster(void)
{
    uint8_t *t = next_tab(), *b = br_blk;
    uint8_t r, y = fy;
    if (is_boss) {
        build_boss(t);
        return;
    }
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

/*---- plongeurs (sprites 12-14) : decollage, attaque guidee vers le vaisseau,
 *     retour par le haut de l'ecran vers leur place dans la formation ------*/
static void div_launch(void)
{
    uint8_t w, t, n, c, tries, row;
    diver_t *d;
    if (frozen || ship_dead) return;
    if (--launch_t) return;
    w = ply.wave < 12 ? ply.wave : 12;          /* delai : 110 - 8 x vague (30 mini) + hasard */
    t = 110 - (w << 3);
    if (t < 30) t = 30;
    launch_t = t + (rand8() & 63);
    n = (ply.wave >> 1) + 1;                    /* plongeurs simultanes : 1 + vague/2 (3 maxi) */
    if (n > NDIVERS) n = NDIVERS;
    for (d = divers; n; n--, d++)
        if (!d->state) break;
    if (!n) return;
    c = rand8() & 7;                            /* une colonne au hasard... */
    if (c >= 6) c -= 6;
    for (tries = 6; tries; tries--) {           /* ...qui a encore un alien */
        row = col_bottom(c);
        if (row != 0xFF) break;
        if (++c == 6) c = 0;
    }
    if (!tries) return;
    d->row = row;
    d->col = c;
    diving[row] |= bits[c];                     /* l'alien quitte la formation */
    d->x = fx + c * COL_PITCH;
    d->y = (uint8_t)(fy + row * ROW_PITCH);
    d->vx = d->x < 304 ? -2 : 2;                /* s'ecarte vers l'exterieur */
    d->timer = 12;
    d->bomb = (rand8() & 31) + 20;
    d->state = 1;
}

/* div_move_x : X += VX, borne 0..608 */
static void div_move_x(diver_t *d)
{
    d->x += d->vx;
    if (d->x < 0) {
        d->x = 0;
        d->vx = 0;
    }
    if (d->x >= 609) {
        d->x = 608;
        d->vx = 0;
    }
}

static void spawn_bomb(int16_t x, uint8_t y);

static void diver_one(diver_t *d, uint8_t spr)
{
    uint8_t y, v;
    switch (d->state) {
    case 0:
        gx_spr_hide(spr);
        return;
    case 1:                                     /* decollage */
        d->y -= 2;
        div_move_x(d);
        if (!--d->timer) d->state = 2;
        break;
    case 2:                                     /* attaque */
        v = ply.wave >> 2;
        y = (uint8_t)d->y + (v < 2 ? v : 2) + 2;
        d->y = y;
        if (y >= 200) {                         /* sorti en bas : reapparait en haut */
            d->state = 3;
            d->y = -24;
            break;
        }
        if (!(frame_count & 3)) {               /* guidage vers le vaisseau */
            if (ship_x - d->x < 0) {
                if (d->vx != -4) d->vx--;
            } else if (d->vx != 4) {
                d->vx++;
            }
        }
        div_move_x(d);
        if (!--d->bomb) {                       /* bombe */
            d->bomb = (rand8() & 31) + 30;
            if (!ship_dead && y < 140) spawn_bomb(d->x, y + 8);
        }
        if (!ship_dead && abs16((int16_t)y - SHIP_Y) < 14 && abs16(ship_x - d->x) < 24) {
            kill_ship();                        /* collision avec le vaisseau */
            d->state = 0;
            alien_destroy(d->row, d->col);
            gx_spr_hide(spr);
            return;
        }
        break;
    default:                                    /* 3 : retour vers la formation */
        d->x = fx + d->col * COL_PITCH;
        d->y += 2;
        if (d->y >= (uint8_t)(fy + d->row * ROW_PITCH)) {
            d->state = 0;                       /* arrive : reprend sa place */
            diving[d->row] &= ~bits[d->col];
            gx_spr_hide(spr);
            return;
        }
        break;
    }
    gx_spr_set(spr, d->x, d->y, F_DIVE_A + wave_type, ZOOM_X2Y1);
}

void divers_update(void)
{
    uint8_t i;
    div_launch();
    for (i = 0; i < NDIVERS; i++) diver_one(&divers[i], SPR_DIVER + i);
}

/*---- collisions des tirs du joueur (hx, hy = pointe du laser) -----------*/
/* hit_divers : 1 = un plongeur est touche */
uint8_t hit_divers(int16_t hx, uint8_t hy)
{
    uint8_t i;
    diver_t *d = divers;
    for (i = 0; i < NDIVERS; i++, d++) {
        if (!d->state || d->y < 0 || d->y > 255) continue;
        if ((uint8_t)(hy - (uint8_t)d->y) >= 16) continue;
        if (abs16(d->x + 16 - hx) >= 14) continue;
        d->state = 0;                           /* abattu */
        alien_destroy(d->row, d->col);
        add_score(dive_pts[d->row]);            /* points doubles en pique */
        expl_start(d->x, (uint8_t)d->y);
        if (rand8() < 90) spawn_caps(d->x, (uint8_t)d->y);  /* une fois sur trois : capsule */
        return 1;
    }
    return 0;
}

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

/*---- bombes ennemies (sprites 9 et 10) -----------------------------------*/
/* spawn_bomb : si une place est libre */
static void spawn_bomb(int16_t x, uint8_t y)
{
    shot_t *b = bombs;
    if (b->on) {
        b++;
        if (b->on) return;
    }
    b->on = 1;
    b->x = x;
    b->y = y;
}

/* bomb_drop : la formation lache des bombes (une fois sur deux au-dessus du joueur) */
static void bomb_drop(void)
{
    uint8_t w, c, row;
    int16_t d;
    if (frozen || ship_dead || is_boss) return;
    if (--bomb_t) return;
    w = ply.wave < 10 ? ply.wave : 10;
    bomb_t = 60 - (w << 2) + (rand8() & 31);
    if (rand8() & 0x80) {                       /* colonne au-dessus du vaisseau */
        d = ship_x + 16 - fx;
        if (d < 0) return;
        for (c = 0; d >= COL_PITCH; c++) d -= COL_PITCH;
        if (c >= 6) return;
    } else {                                    /* colonne au hasard */
        c = rand8() & 7;
        if (c >= 6) c -= 6;
    }
    row = col_bottom(c);
    if (row == 0xFF) return;
    spawn_bomb(fx + c * COL_PITCH, fy + row * ROW_PITCH + 10);
}

static void bomb_one(shot_t *b, uint8_t spr)
{
    if (b->on) {
        b->y += ply.wave < 5 ? 3 : 4;
        if (b->y < 196) {
            if (!ship_dead && (uint8_t)(b->y - 165) < 18 && abs16(ship_x - b->x) < 14) {
                b->on = 0;
                kill_ship();
            } else {
                gx_spr_set(spr, b->x, b->y, F_BOMB1 + ((frame_count >> 2) & 1), ZOOM_X2Y1);
                return;
            }
        }
        b->on = 0;
    }
    gx_spr_hide(spr);
}

void bombs_update(void)
{
    bomb_drop();
    bomb_one(&bombs[0], SPR_BOMB);
    bomb_one(&bombs[1], SPR_BOMB + 1);
}

/*---- vaisseau mere : sprites 0-3 en zoom x4 / x2 = 128 x 64 pixels -------*/
void boss_update(void)
{
    uint8_t a, r;
    if (bflash) bflash--;
    if (bdeath) {                               /* detruit : explosions en chaine */
        if (--bdeath & 7) return;
        bflash = 2;
        r = rand8() & 127;
        if (r >= 100) r -= 40;
        a = rand8() & 31;
        expl_start(bx + r, by + a + 8);
        return;
    }
    if (!ply.bhp || frozen) return;
    a = (frame_count >> 2) & 31;                /* flottement vertical */
    if (a >= 16) a = ~a & 15;
    by = a + 24;
    a = ply.bhp < 12 ? 4 : 2;                   /* blesse : plus rapide */
    if (!bdir) {
        bx += a;
        if (bx >= 640 - 16 - 128) bdir = 1;
    } else {
        bx -= a;
        if (bx < 16) bdir = 0;
    }
    if (ship_dead || --bfire) return;
    bfire = (rand8() & 15) + 18;
    spawn_bomb(bx + ((rand8() & 0x80) ? 80 : 16), by + 52);  /* canon gauche ou droit */
}

/* hit_boss : 1 = touche */
uint8_t hit_boss(int16_t hx, uint8_t hy)
{
    int16_t d;
    if (bdeath || !ply.bhp) return 0;
    d = hx - bx;
    if (d < 8 || d >= 120 || hy < by || (uint8_t)(hy - by) >= 60) return 0;
    bflash = 3;
    add_score(1);
    if (--ply.bhp == 0) {
        bdeath = 72;                            /* 9 explosions en chaine */
        add_score(500);
        march_stop();
    } else {
        GX_SOUND(BOSS_TOUCHE);
    }
    return 1;
}
