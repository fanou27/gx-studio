/*============================================================================
 *  STELLAR WINGS C - declarations communes a tous les fichiers du jeu
 *
 *  Le jeu est decoupe comme la version en assembleur :
 *    main.c   : demarrage, ecran titre, enchainement des parties et joueurs
 *    play.c   : une image de jeu, le vaisseau, ses tirs, la capsule
 *    aliens.c : formation (sprites multiplexes), plongeurs, bombes, boss
 *    fx.c     : hasard, score, panneau, messages, etoiles, explosions
 *  Les ressources (sprites F_..., sons, palettes, logo) viennent de build/gen.h
 *  (inclus par gx.h), generes par le studio a partir du dossier assets/.
 *==========================================================================*/
#ifndef STELLAR_H
#define STELLAR_H
#include "gx.h"

/*---- dimensions ----------------------------------------------------------*/
#define SHIP_Y      172         /* ligne du vaisseau */
#define LOGO_Y      16          /* ligne du logo sur l'ecran titre */
#define MSG_TOP     124         /* zone des messages : lignes 124 a 151 */
#define MSG_LINES   28
#define COL_PITCH   48          /* ecart entre colonnes de la formation (pixels mode 2) */
#define ROW_PITCH   24          /* ecart entre rangees (lignes) */
#define NDIVERS     3           /* plongeurs simultanes au maximum */
#define NSTARS      40          /* etoiles du fond */

/*---- mise au point (Projet > Proprietes : START_WAVE=4, GOD_MODE=1) ------*/
#ifndef START_WAVE
#define START_WAVE  1           /* vague de depart */
#endif
#ifndef GOD_MODE
#define GOD_MODE    0           /* 1 = vaisseau invincible */
#endif

/*---- sprites materiels ---------------------------------------------------
 *  0-5   formation (multiplexee : 4 rangees par interruptions raster) ou boss (0-3)
 *  6     vaisseau        7-8  tirs du joueur     9-10 bombes
 *  11    capsule         12-14 plongeurs         15   explosion           */
#define SPR_SHIP    6
#define SPR_BULLET  7
#define SPR_BOMB    9
#define SPR_CAPS    11
#define SPR_DIVER   12
#define SPR_EXPL    15

/*---- types ---------------------------------------------------------------*/
typedef struct {                /* un joueur (deux blocs : partie a deux en alternance) */
    uint16_t score;             /* en dizaines de points (affiche avec un 0 de plus) */
    uint8_t  lives;
    uint8_t  wave;              /* numero de vague (1, 2...) */
    uint8_t  alive[4];          /* aliens vivants : rangee r, bit c = colonne c */
    uint8_t  power;             /* bit 0 : tir double (capsule D), bit 1 : vitesse (S) */
    uint8_t  over;              /* 1 = partie terminee pour ce joueur */
    uint8_t  bhp;               /* points de vie du vaisseau mere */
} player_t;

typedef struct {                /* tir du joueur ou bombe */
    uint8_t on;
    int16_t x;                  /* pixels mode 2 */
    uint8_t y;
} shot_t;

typedef struct {                /* alien en pique */
    uint8_t state;              /* 0 libre, 1 decollage, 2 attaque, 3 retour */
    uint8_t row, col;           /* sa place dans la formation */
    int16_t x, y;               /* y signe : il repasse par le haut de l'ecran */
    int8_t  vx;
    uint8_t timer, bomb;
} diver_t;

/*---- variables partagees -------------------------------------------------*/
/* main.c */
extern uint8_t  nplayers, cur_player;
extern player_t ply;                    /* joueur en cours (copie de travail) */
extern player_t pblk[2];                /* sauvegarde des deux joueurs */
extern uint16_t hiscore;                /* record (dizaines de points) */
extern uint8_t  frozen;                 /* 1 = jeu fige pendant un message */
/* play.c */
extern int16_t  ship_x;
extern uint8_t  ship_dead;              /* compte a rebours de l'explosion du joueur */
/* aliens.c */
extern uint8_t  is_boss, march_on, alive_count;
extern uint8_t  wing;                   /* 0 / 1 : ailes hautes / basses (palette) */
/* fx.c */
extern uint8_t  hud_dirty;              /* bit 0 : score, bit 1 : vies et vague */
extern uint8_t  star_skip;              /* 1 = les etoiles evitent la zone des messages */

/*---- fonctions -----------------------------------------------------------*/
/* main.c */
void play_frames(uint8_t n);
/* play.c */
void play_frame(void);
void turn_reset(void);
void spawn_caps(int16_t x, uint8_t y);
/* aliens.c */
void aliens_reset(void);
void wave_prepare(void);
uint8_t wave_cleared(void);
void count_alive(void);
void form_update(void);
void divers_update(void);
void bombs_update(void);
void boss_update(void);
void build_raster(void);
void band_row(uint8_t *blk, int16_t x, uint8_t y, uint8_t vis,
              const uint16_t *pal, uint8_t wing) __sdcccall(0);   /* src/bands.asm */
uint8_t *next_tab(void);
void tab_tail(uint8_t *p);
uint8_t hit_divers(int16_t hx, uint8_t hy);
uint8_t hit_formation(int16_t hx, uint8_t hy);
uint8_t hit_boss(int16_t hx, uint8_t hy);
void march_start(void);
void march_stop(void);
/* fx.c */
uint8_t rand8(void) __sdcccall(0);      /* (appelee aussi par stars.asm) */
extern uint16_t rnd;
int16_t abs16(int16_t v);
void add_score(uint16_t tens);
uint8_t update_hi(void);
void print_score(uint8_t col, uint8_t line, uint16_t tens);
void hud_draw(void);
void hud_update(void);
void msg_open(void);
void msg_close(void);
void msg_center(const char *s, uint8_t line);
void stars_init(void);
void stars_update(void) __sdcccall(0);  /* en assembleur : src/stars.asm */
void expl_start(int16_t x, uint8_t y);
void expl_quiet(int16_t x, uint8_t y);
void expl_update(void);
void expl_reset(void);
uint8_t expl_active(void);
void kill_ship(void);
#endif
