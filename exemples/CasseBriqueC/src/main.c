/*============================================================================
 *  CASSE-BRIQUE C - le casse-brique du tutoriel, ecrit en C (GX4000 / CPC+)
 *
 *  Une raquette et une balle en sprites materiels, 60 briques dessinees dans
 *  l'ecran (mode 1), une couleur par rangee grace aux interruptions raster,
 *  des bruitages joues par le DMA. Meme jeu que exemples/CasseBrique (en
 *  assembleur) : on peut comparer les deux, etape par etape.
 *==========================================================================*/
#include "gx.h"

/*=== ETAPE 1 : les constantes du jeu ======================================*/
#define BR_COLS   10          /* 10 colonnes de briques */
#ifndef BR_ROWS               /* (peut etre change dans Projet > Proprietes : BR_ROWS=2) */
#define BR_ROWS   6           /* 6 rangees */
#endif
#define BR_TOP    32          /* ligne de la premiere rangee */
#define BR_H      8           /* hauteur d'une rangee (lignes) */
#define BR_W      8           /* largeur d'une brique (octets = 32 pixels mode 1) */
#define PAD_Y     184         /* ligne de la raquette */
#define PAD_W     64          /* largeur de la raquette (pixels mode 2) */
#define TOP_WALL  14          /* plafond (sous la ligne du score) */
#define BALL_SPR  0           /* numeros des sprites materiels */
#define PAD_SPR   1

/*=== ETAPE 1 : les variables ==============================================*/
static uint8_t  bricks[BR_COLS * BR_ROWS];   /* 1 = brique presente */
static uint8_t  nbricks;                     /* briques restantes */
static int16_t  pad_x;                       /* raquette : X en pixels mode 2 (0-639) */
static int16_t  ball_x;                      /* balle : X (pixels mode 2), Y (lignes) */
static uint8_t  ball_y;
static int8_t   ball_dx, ball_dy;            /* vitesse */
static uint8_t  serve;                       /* 1 = la balle attend sur la raquette */
static uint8_t  lives;
static uint16_t score;
static uint8_t  speed;                       /* vitesse verticale (2, puis 3) */

/*=== ETAPE 8 : une couleur par rangee de briques (interruptions raster) ===
 * Les briques sont dessinees avec l'encre 3 ; a chaque rangee, une
 * interruption raster change la couleur de l'encre 3, puis elle redevient
 * blanche pour le score. Format : premiere ligne, puis (type, couleur,
 * ligne suivante) ; RASTER_INK_ENTRY vient de lib/c/gx.h. */
static const uint8_t raster_bricks[] = {
    BR_TOP - 2,                                       /* un peu avant la brique */
    RASTER_INK_ENTRY(0x00F2, BR_TOP - 1 + 1 * BR_H),  /* rouge (&0GRB) */
    RASTER_INK_ENTRY(0x08F0, BR_TOP - 1 + 2 * BR_H),  /* orange */
    RASTER_INK_ENTRY(0x0FF0, BR_TOP - 1 + 3 * BR_H),  /* jaune */
    RASTER_INK_ENTRY(0x0F40, BR_TOP - 1 + 4 * BR_H),  /* vert */
    RASTER_INK_ENTRY(0x0F0F, BR_TOP - 1 + 5 * BR_H),  /* cyan */
    RASTER_INK_ENTRY(0x044F, BR_TOP - 1 + 6 * BR_H),  /* bleu */
    RASTER_INK_ENTRY(0x0FFF, 199),                    /* retour au blanc (score) */
    RASTER_END_ENTRY                                  /* fin d'image, armement */
};

/*=== ETAPE 7 bis : score et vies ==========================================*/
static void show_score(void)
{
    gx_ink(3);
    gx_text(0, 0, "SCORE");
    gx_text_num(6, 0, score, 4);
    gx_text(32, 0, "VIES");
    gx_text_num(37, 0, lives, 1);
}

/*=== ETAPE 5 : les briques ================================================*/
/* draw_brick : 7 lignes de 8 octets ; le dernier pixel reste noir (joint).
 * pattern = 0xFF (encre 3 sur 4 pixels) pour dessiner, 0 pour effacer. */
static void draw_brick(uint8_t col, uint8_t row, uint8_t pattern)
{
    uint8_t y = BR_TOP + row * BR_H, i, j;
    for (i = 0; i < BR_H - 1; i++) {
        uint8_t *p = GX_LINE(y + i) + col * BR_W;    /* adresse de l'octet a l'ecran */
        for (j = 0; j < BR_W - 1; j++) *p++ = pattern;
        *p = pattern & 0xEE;                         /* 8e octet : 3 pixels sur 4 */
    }
}

/* new_level : remplit le tableau et dessine les 60 briques */
static void new_level(void)
{
    uint8_t c, r;
    for (r = 0; r < BR_ROWS; r++)
        for (c = 0; c < BR_COLS; c++) {
            bricks[r * BR_COLS + c] = 1;
            draw_brick(c, r, 0xFF);
        }
    nbricks = BR_COLS * BR_ROWS;
    serve = 1;                                       /* la balle attend sur la raquette */
}

/*=== ETAPE 6 : la raquette ================================================*/
static void move_pad(void)
{
    if (joy & JOY_LEFT) {
        pad_x -= 8;                                  /* 8 pixels mode 2 par image */
        if (pad_x < 0) pad_x = 0;
    }
    if (joy & JOY_RIGHT) {
        pad_x += 8;
        if (pad_x > 640 - PAD_W) pad_x = 640 - PAD_W;
    }
    gx_spr_set(PAD_SPR, pad_x, PAD_Y, F_RAQUETTE, ZOOM_X4Y1);  /* x4 en largeur : 64 pixels */
}

/*=== ETAPE 7 : la balle ===================================================*/
static int8_t abs8(int8_t v) { return v < 0 ? -v : v; }

static void rebound(void) { GX_SOUND(REBOND); }

/* hit_pad : la balle touche la raquette -> remonte ; l'angle depend du point d'impact */
static const int8_t pad_angle[8] = { -6, -4, -4, -2, 2, 4, 4, 6 };
static void hit_pad(void)
{
    uint8_t bottom;
    int16_t rel;
    if (ball_dy < 0) return;                         /* elle monte deja */
    bottom = ball_y + 4;
    if (bottom < PAD_Y || bottom >= PAD_Y + 5) return;
    rel = ball_x + 4 - pad_x;                        /* position relative a la raquette */
    if (rel < 0 || rel >= PAD_W) return;
    ball_dx = pad_angle[rel >> 3];                   /* 8 zones */
    ball_dy = -(int8_t)speed;
    rebound();
}

/* hit_bricks : le centre de la balle est-il dans une brique ? */
static void hit_bricks(void)
{
    uint8_t cy = ball_y + 2, row, col, k;
    if (cy < BR_TOP || cy >= BR_TOP + BR_ROWS * BR_H) return;
    row = (cy - BR_TOP) >> 3;
    col = (uint8_t)((ball_x + 4) >> 6);              /* 64 pixels mode 2 par brique */
    if (col >= BR_COLS) return;
    k = row * BR_COLS + col;
    if (!bricks[k]) return;                          /* deja cassee */
    bricks[k] = 0;
    draw_brick(col, row, 0);                         /* efface la brique */
    ball_dy = -ball_dy;                              /* rebond vertical */
    score += 10 * (BR_ROWS - row);                   /* points : 10 x (6 - rangee) */
    show_score();
    GX_SOUND(BRIQUE);
    if (--nbricks == 0) {                            /* tableau termine : un peu plus vite */
        speed = 3;
        new_level();
    }
}

/* ball_lost : une vie de moins, la balle revient sur la raquette */
static void ball_lost(void)
{
    GX_SOUND(PERDU);
    lives--;
    serve = 1;
    show_score();
}

static void move_ball(void)
{
    if (serve) {                                     /* service : la balle suit la raquette */
        ball_x = pad_x + PAD_W / 2 - 4;
        ball_y = (uint8_t)(PAD_Y - 4);
        if (joy_new & JOY_FIRE) {
            serve = 0;
            ball_dx = 4;
            ball_dy = -(int8_t)speed;                /* vers le haut */
        }
    } else {
        ball_x += ball_dx;                           /* deplacement horizontal, murs */
        if (ball_x < 0) { ball_x = 0; ball_dx = abs8(ball_dx); rebound(); }
        if (ball_x >= 640 - 8) { ball_x = 640 - 8; ball_dx = -abs8(ball_dx); rebound(); }
        ball_y += ball_dy;                           /* deplacement vertical, plafond */
        if (ball_y < TOP_WALL) { ball_y = TOP_WALL; ball_dy = abs8(ball_dy); rebound(); }
        hit_bricks();
        hit_pad();
        if (ball_y >= 196) ball_lost();              /* balle perdue */
    }
    gx_spr_set(BALL_SPR, ball_x, ball_y, F_BALLE, ZOOM_X2Y1);
}

/*=== ETAPE 2 : l'ecran titre ==============================================*/
static void title(void)
{
    gx_spr_hide_all();
    gx_raster_start(raster_idle);                    /* pas de couleurs par rangee */
    gx_clear_screen();
    gx_ink(3);
    gx_text(13, 70, "CASSE-BRIQUE GX");
    gx_ink(2);
    gx_text(13, 120, "APPUIE SUR TIR");
    GX_SOUND(MUSIQUE);                               /* assets/sons/musique.snd */
    do {
        gx_wait_frame();
        gx_read_joy();
    } while (!(joy_new & JOY_FIRE));
}

/*=== ETAPES 2 a 4 : demarrage, partie, boucle de jeu ======================*/
void main(void)
{
    gx_init();                                       /* son (DMA), sprites */
    gx_palette(pal_screen);                          /* encres 0-3 (assets/palettes/ecran.pal) */
    gx_sprite_palette(pal_sprites);                  /* couleurs 1-15 des sprites */
    for (;;) {
        title();
        /*--- ETAPE 3 : une nouvelle partie */
        gx_sound_stop_all();
        gx_clear_screen();
        lives = 3;
        speed = 2;
        score = 0;
        pad_x = (640 - PAD_W) / 2;
        new_level();
        gx_raster_start(raster_bricks);              /* couleurs par rangee (etape 8) */
        show_score();
        /*--- ETAPE 4 : la boucle de jeu (50 fois par seconde) */
        while (lives) {
            gx_wait_frame();                         /* ligne 199 : l'ecran visible est fini */
            gx_spr_update();                         /* positions calculees a l'image precedente */
            gx_read_joy();
            move_pad();
            move_ball();
        }
        /*--- plus de vie : fin de partie */
        gx_spr_hide_all();
        gx_ink(3);
        gx_text(15, 120, "GAME OVER");
        gx_wait_frames(150);
    }
}
