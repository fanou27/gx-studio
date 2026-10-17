/*============================================================================
 *  play.c - etape 2 : une image de jeu, le vaisseau, ses tirs
 *==========================================================================*/
#include "stellar.h"

int16_t ship_x;
uint8_t ship_dead;
static uint8_t fire_cool;               /* images avant le tir suivant */
static shot_t  bullets[2];              /* tirs du joueur (sprites 7 et 8) */

static void ship_update(void);
static void bullets_update(void);

/*---- play_frame : synchronisation + toute la logique d'une image (50 par seconde) */
void play_frame(void)
{
    gx_wait_frame();                    /* ligne 199 : bord bas, rien n'est affiche */
    gx_spr_update_from(SPR_SHIP);       /* sprites 6-15 (les 0-5 sont multiplexes) */
    /* battement d'ailes des plongeurs : couleur 7 ou 8 des sprites au noir */
    if (wing) {
        GX_ASIC_SPR_PAL[6] = 0;
        GX_ASIC_SPR_PAL[7] = ((const uint16_t *)pal_sprites)[7];
    } else {
        GX_ASIC_SPR_PAL[6] = ((const uint16_t *)pal_sprites)[6];
        GX_ASIC_SPR_PAL[7] = 0;
    }
    gx_read_joy();
    stars_update();
    ship_update();
    bullets_update();
    form_update();
    expl_update();
    build_raster();                     /* table raster de l'image suivante */
    if (hud_dirty) hud_update();
}

/*---- turn_reset : positions de depart (debut de tour ou nouvelle vague) --*/
void turn_reset(void)
{
    uint8_t i;
    ship_x = 304;
    ship_dead = 0;
    fire_cool = 0;
    bullets[0].on = bullets[1].on = 0;
    expl_reset();
    aliens_reset();                     /* formation */
    for (i = 6; i < 16; i++) gx_spr_hide(i);
    count_alive();
}

/*---- vaisseau : deplacement, tir, explosion ------------------------------*/
static void fire_bullet(shot_t *b, int8_t dx)
{
    b->x = ship_x + dx;
    b->y = (uint8_t)(SHIP_Y - 6);
    b->on = 1;
}

static void ship_update(void)
{
    int16_t step;
    if (ship_dead) {                    /* explosions en chaine, puis plus rien */
        if (ship_dead == 1) return;
        ship_dead--;
        if (ship_dead >= 70 && !(ship_dead & 15)) expl_quiet(ship_x, SHIP_Y);
        return;
    }
    if (!frozen) {
        step = (ply.power & 2) ? 7 : 4; /* capsule S : vaisseau plus rapide */
        if (joy & JOY_LEFT) {
            ship_x -= step;
            if (ship_x < 8) ship_x = 8;
        }
        if (joy & JOY_RIGHT) {
            ship_x += step;
            if (ship_x > 600) ship_x = 600;
        }
        if (fire_cool) {
            fire_cool--;
        } else if (joy & JOY_FIRE) {    /* bouton maintenu = tir automatique */
            uint8_t fired = 0;
            if (ply.power & 1) {        /* capsule D : deux tirs paralleles */
                if (!bullets[0].on && !bullets[1].on) {
                    fire_bullet(&bullets[0], -10);
                    fire_bullet(&bullets[1], 10);
                    fired = 1;
                }
            } else if (!bullets[0].on) {
                fire_bullet(&bullets[0], 0);
                fired = 1;
            }
            if (fired) {
                fire_cool = 10;
                GX_SOUND(TIR);          /* canal DMA 1 (voie B) */
            }
        }
    }
    gx_spr_set(SPR_SHIP, ship_x, SHIP_Y, F_SHIP, ZOOM_X2Y1);
}

/*---- tirs du joueur ------------------------------------------------------*/
/* bullet_hits : le tir touche-t-il un alien, un plongeur, le boss ? */
static void bullet_hits(shot_t *b)
{
    int16_t hx = b->x + 16;             /* pointe du laser (centre du sprite) */
    if (hit_formation(hx, b->y)) b->on = 0;
}

static void bullet_one(shot_t *b, uint8_t spr)
{
    if (b->on) {
        if (b->y < 7 + 10) {
            b->on = 0;                  /* sorti par le haut */
        } else {
            b->y -= 7;
            bullet_hits(b);
            if (b->on) {
                gx_spr_set(spr, b->x, b->y, F_LASER, ZOOM_X2Y1);
                return;
            }
        }
    }
    gx_spr_hide(spr);
}

static void bullets_update(void)
{
    bullet_one(&bullets[0], SPR_BULLET);
    bullet_one(&bullets[1], SPR_BULLET + 1);
}

