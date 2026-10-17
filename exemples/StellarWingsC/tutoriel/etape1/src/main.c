/*============================================================================
 *  STELLAR WINGS C - tutoriel, etape 1 : ecran titre, vaisseau, tirs, etoiles
 *==========================================================================*/
#include "stellar.h"

uint8_t  nplayers, cur_player;
player_t ply, pblk[2];
uint16_t hiscore;
uint8_t  frozen;

static const uint16_t pal_black[4] = { 0, 0, 0, 0 };
static const uint16_t pal_title[4] = { 0x0000, 0x0337, 0x0BBF, 0x0FF4 };

/*---- screen_reset : coupe le son, les sprites et les rasters, efface l'ecran */
static void screen_reset(void)
{
    gx_sound_stop_all();
    gx_raster_start(raster_idle);
    gx_wait_frame();
    gx_spr_hide_all();
    gx_palette(pal_black);
    gx_clear_screen();
}

/* title_parade : 6 aliens qui se balancent sous le logo (sprites 0-5) */
static void title_parade(void)
{
    uint8_t a = frame_count & 127, i;
    int16_t x;
    if (a >= 64) a = ~a & 63;
    x = a + (a >> 1) + 16;
    for (i = 0; i < 6; i++) {
        gx_spr_set(i, x, 98, F_ALIEN_A + i % 3, ZOOM_X2Y1);
        x += 96;
    }
}

/* title_screen : logo, textes, musique ; TIR 1 pour jouer */
static void title_screen(void)
{
    screen_reset();
    gx_blit(IMG_LOGO, IMG_LOGO_W, IMG_LOGO_H, (80 - IMG_LOGO_W) / 2, LOGO_Y);
    gx_ink(1);
    msg_center("- L'ATTAQUE DES FAUCONS -", 84);
    gx_ink(2);
    msg_center("TIR 1 : UN JOUEUR", 128);
    gx_ink(3);
    gx_text(13, 162, "RECORD");
    print_score(21, 162, hiscore);
    gx_sprite_palette(pal_sprites);
    gx_palette(pal_title);
    GX_SOUND(TITRE);
    do {
        gx_wait_frame();
        gx_spr_update_from(0);
        title_parade();
        rand8();
        gx_read_joy();
    } while (!(joy_new & JOY_FIRE));
    nplayers = 1;
}

/* game : pour l'instant, le vaisseau seul sous les etoiles */
static void game(void)
{
    ply.lives = 3;
    ply.wave = 1;
    screen_reset();
    gx_palette(pal_screen);
    gx_sprite_palette(pal_sprites);
    stars_init();
    hud_draw();
    turn_reset();
    for (;;) play_frame();
}

void main(void)
{
    gx_init();
    rnd = 0xACE1;
    hiscore = 1000;                             /* record initial : 10 000 points */
    for (;;) {
        title_screen();
        game();
    }
}
