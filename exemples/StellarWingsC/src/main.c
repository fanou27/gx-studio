/*============================================================================
 *  STELLAR WINGS C - shoot'em up pour GX4000 / CPC 464+ / 6128+, ecrit en C
 *
 *  Dans l'esprit de Space Hawks / Space Invaders : une formation qui descend,
 *  des aliens qui plongent en pique, un vaisseau mere toutes les 4 vagues,
 *  1 ou 2 joueurs en alternance. Meme jeu que STELLAR WINGS en assembleur.
 *
 *  main.c : demarrage, ecran titre, enchainement des parties et des joueurs
 *==========================================================================*/
#include "stellar.h"

uint8_t  nplayers, cur_player;
player_t ply, pblk[2];
uint16_t hiscore;
uint8_t  frozen;

static const uint16_t pal_black[4] = { 0, 0, 0, 0 };
static const uint16_t pal_title[4] = { 0x0000, 0x0337, 0x0BBF, 0x0FF4 };
/* degrade "copper" du logo (encre 3), une couleur toutes les 4 lignes (&0GRB) */
#define NB_COPPER 14
static const uint16_t copper[NB_COPPER] = {
    0x0FF4, 0x0DF3, 0x0BF2, 0x09F2, 0x07F3, 0x05F5, 0x04E8,
    0x04CB, 0x05AE, 0x078F, 0x096F, 0x0B5F, 0x0D6F, 0x0F9F
};
static const char s_gameover[] = "GAME OVER";

/*---- screen_reset : coupe le son, les sprites et les rasters, efface l'ecran */
static void screen_reset(void)
{
    gx_sound_stop_all();
    march_on = 0;
    star_skip = 0;
    gx_raster_start(raster_idle);
    gx_wait_frame();
    gx_spr_hide_all();
    gx_palette(pal_black);
    gx_clear_screen();
}

/*---- ecran titre ---------------------------------------------------------*/
/* title_raster : 16 changements de l'encre 3 (4 lignes chacun) sur le logo,
 * degrade decale a chaque image => effet "copper" anime */
static void title_raster(void)
{
    uint8_t *t = next_tab();
    uint8_t e, line = LOGO_Y + 4, i;
    *t++ = LOGO_Y;
    e = (frame_count >> 1) % NB_COPPER;
    for (i = 0; i < 16; i++) {
        *t++ = 0;                               /* type 0 : encre 3 */
        *t++ = (uint8_t)copper[e];
        *t++ = copper[e] >> 8;
        *t++ = line;
        line += 4;
        if (++e == NB_COPPER) e = 0;
    }
    *t++ = 0;                                   /* puis l'encre 3 redevient fixe (textes) */
    *t++ = (uint8_t)pal_title[3];
    *t++ = pal_title[3] >> 8;
    *t++ = 199;
    tab_tail(t);
}

/* title_parade : 6 aliens qui se balancent sous le logo (sprites 0-5) */
static void title_parade(void)
{
    uint8_t a = frame_count & 127, i;
    uint16_t c3;
    int16_t x;
    if (a >= 64) a = ~a & 63;
    x = a + (a >> 1) + 16;
    for (i = 0; i < 6; i++) {
        gx_spr_set(i, x, 98, F_ALIEN_A + i % 3, ZOOM_X2Y1);
        x += 96;
    }
    GX_ASIC_SPR_PAL[0] = 0x0DF3;                /* couleurs 1-4 (1re rangee, aliens A) */
    GX_ASIC_SPR_PAL[1] = 0x0FFF;
    c3 = 0x04EC;
    if (frame_count & 8) {                      /* ailes battantes */
        GX_ASIC_SPR_PAL[2] = 0;
        GX_ASIC_SPR_PAL[3] = c3;
    } else {
        GX_ASIC_SPR_PAL[2] = c3;
        GX_ASIC_SPR_PAL[3] = 0;
    }
}

/* title_screen : logo, defile d'aliens, musique. TIR 1 = un joueur, TIR 2 = deux */
static void title_screen(void)
{
    uint8_t a;
    screen_reset();
    gx_blit(IMG_LOGO, IMG_LOGO_W, IMG_LOGO_H, (80 - IMG_LOGO_W) / 2, LOGO_Y);
    gx_ink(1);
    msg_center("- L'ATTAQUE DES FAUCONS -", 84);
    gx_ink(2);
    msg_center("TIR 1 : UN JOUEUR", 128);
    msg_center("TIR 2 : DEUX JOUEURS", 142);
    gx_ink(3);
    gx_text(13, 162, "RECORD");
    print_score(21, 162, hiscore);
    gx_ink(1);
    msg_center("GX4000 / CPC+   2026", 186);
    gx_sprite_palette(pal_sprites);
    gx_palette(pal_title);
    GX_SOUND(TITRE);                            /* musique du titre, canal 0 */
    for (;;) {
        gx_wait_frame();
        gx_spr_update_from(0);                  /* ici pas de multiplexage : 16 sprites directs */
        title_raster();
        title_parade();
        rand8();
        gx_read_joy();
        if (joy_new & JOY_FIRE1) { nplayers = 1; break; }
        if (joy_new & JOY_FIRE2) { nplayers = 2; break; }
    }
    a = frame_count ^ (uint8_t)rnd;             /* le hasard depend du moment de l'appui */
    if (!a) a = 1;
    rnd = (rnd & 0xFF00) | a;
}

/*---- messages ------------------------------------------------------------*/
static void msg_player(void)                    /* "JOUEUR 1" ou "JOUEUR 2" (ligne 130) */
{
    gx_ink(3);
    msg_center(cur_player ? "JOUEUR 2" : "JOUEUR 1", 130);
}

static void msg_wave(void)                      /* "VAGUE nn" ou alerte (ligne 142) */
{
    gx_ink(2);
    if (is_boss) {
        msg_center("ALERTE : VAISSEAU MERE !", 142);
    } else {
        gx_text(16, 142, "VAGUE");
        gx_text_num(22, 142, ply.wave, 2);
    }
}

/*---- deroulement d'un tour -----------------------------------------------*/
void play_frames(uint8_t n)                     /* n images de jeu */
{
    while (n--) play_frame();
}

/* next_wave : vague terminee -> felicitations, vague suivante */
static void next_wave(void)
{
    march_stop();
    GX_SOUND(BRAVO);
    frozen = 1;
    msg_open();
    msg_center("BRAVO !", 136);
    play_frames(90);
    ply.wave++;
    hud_dirty = 2;
    wave_prepare();
    turn_reset();
    msg_open();
    msg_wave();
    GX_SOUND(VAGUE);
    play_frames(100);
    msg_close();
    frozen = 0;
    march_start();
}

/* play_turn : le joueur courant joue jusqu'a la perte d'une vie */
static void play_turn(void)
{
    screen_reset();
    gx_palette(pal_screen);
    gx_sprite_palette(pal_sprites);
    stars_init();
    hud_draw();
    wave_prepare();
    turn_reset();
    frozen = 1;
    msg_open();
    msg_player();
    msg_wave();
    GX_SOUND(VAGUE);
    play_frames(110);
    msg_close();
    frozen = 0;
    march_start();
    for (;;) {
        play_frame();
        if (ship_dead) {
            if (ship_dead == 1) {               /* fin de l'explosion : une vie de moins */
                ply.lives--;
                gx_sound_stop_all();
                return;
            }
        } else if (wave_cleared()) {
            next_wave();
        }
    }
}

/*---- partie complete -----------------------------------------------------*/
static void player_init(player_t *p)
{
    uint8_t i, *b = (uint8_t *)p;
    for (i = 0; i < sizeof(player_t); i++) b[i] = 0;
    p->lives = 3;
    p->wave = START_WAVE;
}

/* player_over_msg : "JOUEUR n / GAME OVER" (partie a deux) */
static void player_over_msg(void)
{
    msg_open();
    msg_player();
    msg_center(s_gameover, 142);
    gx_wait_frames(120);
}

/* game_over : fin de partie */
static void game_over(void)
{
    uint8_t n;
    screen_reset();
    gx_palette(pal_screen);
    hud_draw();
    msg_open();
    msg_center(s_gameover, 136);
    GX_SOUND(GAMEOVER);
    gx_wait_frames(50);
    for (n = 200; n; n--) {
        gx_wait_frame();
        gx_read_joy();
        if (joy_new & JOY_FIRE) return;
    }
}

/* game : 1 ou 2 joueurs en alternance ; chaque tour dure une vie */
static void game(void)
{
    player_init(&pblk[0]);
    player_init(&pblk[1]);
    if (nplayers != 2) pblk[1].over = 1;        /* joueur 2 absent */
    cur_player = 0;
    for (;;) {
        ply = pblk[cur_player];                 /* copie de travail */
        play_turn();
        if (!ply.lives) {
            ply.over = 1;
            update_hi();
            if (nplayers == 2) player_over_msg();
        }
        pblk[cur_player] = ply;
        if (nplayers == 2 && !pblk[cur_player ^ 1].over) {
            cur_player ^= 1;                    /* au tour de l'autre joueur */
        } else if (ply.over) {
            game_over();                        /* plus personne : fin de partie */
            return;
        }
    }
}

/*---- main : appele en boucle par src/main.asm ----------------------------*/
void main(void)
{
    gx_init();                                  /* son (DMA), sprites */
    rnd = 0xACE1;
    hiscore = 1000;                             /* record initial : 10 000 points */
    for (;;) {
        title_screen();
        game();
    }
}
