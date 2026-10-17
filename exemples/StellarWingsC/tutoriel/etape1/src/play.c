/*============================================================================
 *  play.c - etape 1 : une image de jeu, le vaisseau et ses tirs
 *==========================================================================*/
#include "stellar.h"

int16_t ship_x;
uint8_t ship_dead;
static uint8_t fire_cool;               /* images avant le tir suivant */
static shot_t  bullets[2];              /* tirs du joueur (sprites 7 et 8) */

static void ship_update(void);
static void bullets_update(void);

/*---- play_frame : une image de jeu (50 par seconde) ----------------------*/
void play_frame(void)
{
    gx_wait_frame();                    /* ligne 199 : bord bas, rien n'est affiche */
    gx_spr_update_from(SPR_SHIP);       /* sprites 6-15 */
    gx_read_joy();
    stars_update();
    ship_update();
    bullets_update();
    if (hud_dirty) hud_update();
}

/*---- turn_reset : positions de depart ------------------------------------*/
void turn_reset(void)
{
    uint8_t i;
    ship_x = 304;
    ship_dead = 0;
    fire_cool = 0;
    bullets[0].on = bullets[1].on = 0;
    for (i = 6; i < 16; i++) gx_spr_hide(i);
}

/*---- vaisseau : deplacement et tir ---------------------------------------*/
static void fire_bullet(shot_t *b, int8_t dx)
{
    b->x = ship_x + dx;
    b->y = (uint8_t)(SHIP_Y - 6);
    b->on = 1;
}

static void ship_update(void)
{
    if (joy & JOY_LEFT) {
        ship_x -= 4;
        if (ship_x < 8) ship_x = 8;
    }
    if (joy & JOY_RIGHT) {
        ship_x += 4;
        if (ship_x > 600) ship_x = 600;
    }
    if (fire_cool) {
        fire_cool--;
    } else if ((joy & JOY_FIRE) && !bullets[0].on) {
        fire_bullet(&bullets[0], 0);    /* bouton maintenu = tir automatique */
        fire_cool = 10;
        GX_SOUND(TIR);
    }
    gx_spr_set(SPR_SHIP, ship_x, SHIP_Y, F_SHIP, ZOOM_X2Y1);
}

/*---- tirs du joueur : montent de 7 lignes par image ----------------------*/
static void bullet_one(shot_t *b, uint8_t spr)
{
    if (b->on) {
        if (b->y < 7 + 10) {
            b->on = 0;                  /* sorti par le haut */
        } else {
            b->y -= 7;
            gx_spr_set(spr, b->x, b->y, F_LASER, ZOOM_X2Y1);
            return;
        }
    }
    gx_spr_hide(spr);
}

static void bullets_update(void)
{
    bullet_one(&bullets[0], SPR_BULLET);
    bullet_one(&bullets[1], SPR_BULLET + 1);
}
