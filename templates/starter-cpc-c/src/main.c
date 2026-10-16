/*============================================================================
 *  {{NAME}} - projet GX Studio en C pour {{TARGET}}
 *
 *  Point de depart : un vaisseau dirige a la manette, qui tire sur un ennemi
 *  rebondissant. Musique et bruitages (assets/sons), sprites (assets/sprites).
 *  Les fonctions gx_... viennent de lib/c/gx.h ; les ressources (F_..., sons,
 *  palettes) de build/gen.h, produit par le studio. Tout est a modifier !
 *==========================================================================*/
#include "gx.h"

#define SCR_W   (SCREEN_MODE == 0 ? 160 : 320)  /* largeur en pixels du mode */
#define SPR_W   16

enum { SHIP = 0, SHOT = 1, FOE = 2 };          /* numeros des sprites */

static int16_t ship_x, foe_x;
static uint8_t ship_y, foe_y, shot_y, boom;
static uint16_t shot_x;
static int8_t foe_dx, foe_dy;
static uint8_t shot_on;
static uint16_t score;

#if ASIC
/* degrade "copper" : l'encre 3 change de couleur toutes les 4 lignes (lignes 40 a 100) */
static const unsigned char copper[] = {
    40,
    RASTER_INK_ENTRY(0x00F0, 44), RASTER_INK_ENTRY(0x02F2, 48), RASTER_INK_ENTRY(0x04F4, 52),
    RASTER_INK_ENTRY(0x06F6, 56), RASTER_INK_ENTRY(0x08F8, 60), RASTER_INK_ENTRY(0x0AFA, 64),
    RASTER_INK_ENTRY(0x0CFC, 68), RASTER_INK_ENTRY(0x0EFE, 72), RASTER_INK_ENTRY(0x0CFC, 76),
    RASTER_INK_ENTRY(0x0AFA, 80), RASTER_INK_ENTRY(0x08F8, 84), RASTER_INK_ENTRY(0x06F6, 88),
    RASTER_INK_ENTRY(0x04F4, 92), RASTER_INK_ENTRY(0x02F2, 96), RASTER_INK_ENTRY(0x0FFF, 199),
    RASTER_END_ENTRY
};
#endif

static void show_score(void)
{
    gx_ink(3);
    gx_text(0, 0, "SCORE");
    gx_text_num(6, 0, score, 4);
}

static void title(void)
{
    gx_sprite_hide(SHIP);
    gx_sprite_hide(SHOT);
    gx_sprite_hide(FOE);
    gx_sprites_update();
    gx_clear_screen();
#if ASIC
    gx_raster_start(copper);
#endif
    gx_ink(3);
    gx_text(GX_COLS / 2 - 5, 60, "{{NAME_UPPER}}");
    gx_ink(2);
    gx_text(GX_COLS / 2 - 7, 120, "APPUIE SUR TIR");
    GX_SOUND(MUSIQUE);
    do {
        gx_wait_frame();
        gx_read_joy();
    } while (!(joy_new & JOY_FIRE));
    gx_sound_stop_all();
}

static void move_ship(void)
{
    if ((joy & JOY_LEFT) && ship_x > 0) ship_x -= 2;
    if ((joy & JOY_RIGHT) && ship_x < SCR_W - SPR_W) ship_x += 2;
    if ((joy & JOY_UP) && ship_y > 20) ship_y -= 2;
    if ((joy & JOY_DOWN) && ship_y < 182) ship_y += 2;
    if ((joy_new & JOY_FIRE) && !shot_on) {
        shot_on = 1;
        shot_x = ship_x;
        shot_y = ship_y - 8;
        GX_SOUND(SON_TIR);
    }
    gx_sprite(SHIP, ship_x, ship_y, F_VAISSEAU);
}

static void move_shot(void)
{
    if (!shot_on) { gx_sprite_hide(SHOT); return; }
    if (shot_y < 22) { shot_on = 0; gx_sprite_hide(SHOT); return; }
    shot_y -= 6;
    if (!boom && (int16_t)shot_x - foe_x < 12 && foe_x - (int16_t)shot_x < 12
        && shot_y - foe_y < 14 && foe_y - shot_y < 14) {   /* touche ! */
        shot_on = 0;
        boom = 24;
        score += 10;
        show_score();
        GX_SOUND(SON_BOOM);
        gx_sprite_hide(SHOT);
        return;
    }
    gx_sprite(SHOT, shot_x, shot_y, F_TIR);
}

static void move_foe(void)
{
    if (boom) {                                  /* explosion, puis retour en haut */
        if (--boom == 0) foe_y = 30;
        gx_sprite(FOE, foe_x, foe_y, F_EXPLOSION);
        return;
    }
    foe_x += foe_dx;
    if (foe_x < 4 || foe_x > SCR_W - SPR_W - 4) foe_dx = -foe_dx;
    foe_y += foe_dy;
    if (foe_y < 20 || foe_y > 180) foe_dy = -foe_dy;
    gx_sprite(FOE, foe_x, foe_y, F_ENNEMI);
}

void main(void)
{
    gx_init();
    gx_palette(pal_screen);
    gx_sprite_palette(pal_sprites);
    for (;;) {
        title();
        gx_clear_screen();
        ship_x = SCR_W / 2 - SPR_W / 2; ship_y = 170;
        foe_x = 40; foe_y = 40; foe_dx = 1; foe_dy = 2;
        shot_on = 0; boom = 0; score = 0;
        show_score();
        for (;;) {                               /* la boucle de jeu, une fois par image */
            gx_wait_frame();
            gx_sprites_update();                 /* positions calculees a l'image precedente */
            gx_read_joy();
            move_ship();
            move_shot();
            move_foe();
            if (joy_new & JOY_FIRE2 && score >= 100) break;   /* (exemple de sortie) */
        }
    }
}
