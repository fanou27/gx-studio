/*============================================================================
 * lib/c/gx.h - bibliotheque GX Studio pour le C (SDCC) : CPC, CPC+, GX4000
 *
 * Les fonctions ci-dessous appellent les routines assembleur de lib/ (glue :
 * lib/c/gx_c.asm). Les ressources du projet sont dans build/gen.h (inclus) :
 * F_NOM (sprites), NOM_CH / NOM_OFS / NOM_SPEED (sons), MAP_NOM, TILES_NOM,
 * ANIM_NOM, RASTER_NOM, pal_screen, pal_sprites, line_addr...
 *
 * Coordonnees des sprites (gx_sprite) : x en pixels du mode de l'ecran
 * (mode 0 : 0-159, mode 1 : 0-319), y en lignes (0-199).
 *==========================================================================*/
#ifndef GX_H
#define GX_H
#include <stdint.h>
#include "gen.h"            /* build/gen.h : config.h + ressources */

#define GXAPI __sdcccall(0)  /* parametres sur la pile, resultat dans L / HL */

/* ---- manettes (et clavier sur CPC) : bits de joy et joy_new ---- */
#define J_UP     0
#define J_DOWN   1
#define J_LEFT   2
#define J_RIGHT  3
#define J_FIRE1  4
#define J_FIRE2  5
#define JOY_UP    (1 << J_UP)
#define JOY_DOWN  (1 << J_DOWN)
#define JOY_LEFT  (1 << J_LEFT)
#define JOY_RIGHT (1 << J_RIGHT)
#define JOY_FIRE1 (1 << J_FIRE1)
#define JOY_FIRE2 (1 << J_FIRE2)
#define JOY_FIRE  (JOY_FIRE1 | JOY_FIRE2)
extern volatile uint8_t joy;          /* boutons appuyes (mis a jour par gx_read_joy) */
extern volatile uint8_t joy_new;      /* nouveaux appuis depuis le dernier gx_read_joy */
extern volatile uint8_t frame_count;  /* +1 a chaque image */

/* ---- demarrage, images ---- */
void gx_init(void) GXAPI;             /* a appeler au debut de main() : son, sprites */
void gx_wait_frame(void) GXAPI;       /* attend la fin de l'image (CPC+ : ligne 199) */
void gx_wait_frames(uint8_t n) GXAPI;
void gx_read_joy(void) GXAPI;

/* ---- ecran, couleurs, texte ---- */
void gx_clear_screen(void) GXAPI;
void gx_palette(const void *pal) GXAPI;         /* pal_screen (assets/palettes/ecran.pal) */
void gx_sprite_palette(const void *pal) GXAPI;  /* pal_sprites (CPC+/GX4000) */
void gx_ink(uint8_t ink) GXAPI;                 /* encre du texte */
void gx_text(uint8_t col, uint8_t line, const char *s) GXAPI;  /* col : caracteres, line : lignes */
void gx_text_num(uint8_t col, uint8_t line, uint16_t value, uint8_t digits) GXAPI; /* nombre (gx.c) */
#define GX_LINE(y) ((uint8_t *)line_addr[y])    /* adresse ecran de la ligne y */
#define GX_COLS (SCREEN_MODE == 0 ? 20 : SCREEN_MODE == 1 ? 40 : 80)
void gx_clear_lines(uint8_t y, uint8_t n) GXAPI;                 /* efface n lignes (gx.c) */
void gx_blit(const void *src, uint8_t wbytes, uint8_t h, uint8_t xbyte, uint8_t y) GXAPI; /* image (IMG_NOM, IMG_NOM_W, IMG_NOM_H) a l'ecran (gx.c) */

/* ---- sons : assets/sons/NOM.snd -> GX_SOUND(NOM) ---- */
void gx_sound(uint8_t ch, uint16_t ofs, uint8_t speed) GXAPI;
#define GX_SOUND(N) gx_sound(N##_CH, N##_OFS, N##_SPEED)
void gx_sound_stop(uint8_t ch) GXAPI;
void gx_sound_stop_all(void) GXAPI;
void gx_psg(uint8_t reg, uint8_t value) GXAPI;  /* ecriture directe dans le PSG */
void gx_sound_tempo(uint8_t speed) GXAPI;       /* change la vitesse du son du canal 0 en cours de lecture */

/* ---- sprites communs a toutes les cibles (CPC+ : materiels, CPC : logiciels) ---- */
void gx_sprite(uint8_t n, uint16_t x, uint8_t y, uint8_t frame) GXAPI;
void gx_sprite_hide(uint8_t n) GXAPI;
void gx_sprites_update(void) GXAPI;   /* une fois par image, apres gx_wait_frame() */

#if ASIC
/* ---- CPC+ / GX4000 : sprites materiels, rasters ---- */
#define ZOOM_X1Y1 0x05
#define ZOOM_X2Y1 0x09
#define ZOOM_X4Y1 0x0D
#define ZOOM_X2Y2 0x0A
#define ZOOM_X4Y2 0x0E
#define ZOOM_X4Y4 0x0F
void gx_spr_set(uint8_t n, int16_t x, int16_t y, uint8_t frame, uint8_t zoom) GXAPI; /* x : pixels mode 2 (0-639), y : lignes (signe) */
void gx_spr_hide(uint8_t n) GXAPI;
void gx_spr_hide_all(void) GXAPI;
void gx_spr_update(void) GXAPI;       /* = gx_sprites_update */
void gx_spr_update_from(uint8_t first) GXAPI; /* recopie les sprites first..15 (les autres : bandes raster) puis charge les images */
void gx_raster_start(const void *table) GXAPI;  /* demarre la table tout de suite */
void gx_raster_queue(const void *table) GXAPI;  /* table utilisee a partir de l'image suivante (double tampon) */
/* tables raster ecrites en C : premiere ligne, puis entrees de 4 octets */
#define RASTER_INK_ENTRY(color, next) 0, (color) & 0xFF, (color) >> 8, (next)  /* type 0 : encre 3 */
#define RASTER_ANY_ENTRY(ink, color, next) 4, (color) & 0xFF, ((ink) << 4) | ((color) >> 8), (next)
#define RASTER_END_ENTRY 2, 0, 0, 0, 3, 0, 0, 0 /* fin d'image (ligne 199) puis armement */
#define RASTER_BAND_ENTRY(addr, next) 1, (addr) & 0xFF, (addr) >> 8, (next) /* type 1 : bande de sprites 0-5 */
#define RASTER_ARM_LINE 48                       /* (ligne de la fin d'image : remplacee par la valeur mesuree) */
#define GX_BAND_SIZE 38   /* bloc d'une bande : 6 x (X lo, X hi, Y lo, Y hi, zoom) puis couleurs 1-4 des sprites */
extern const unsigned char raster_idle[];        /* table vide */
extern uint8_t spr_want[16];                     /* image voulue pour chaque sprite (chargee par gx_spr_update) */
/* registres de l'ASIC (page &4000-&7FFF ouverte en permanence par la bibliotheque) */
#define GX_ASIC_PAL     ((volatile uint16_t *)0x6400)  /* encres 0-15 de l'ecran (&0GRB) */
#define GX_ASIC_SPR_PAL ((volatile uint16_t *)0x6422)  /* couleurs 1-15 des sprites : [0] = couleur 1 */
#else
/* ---- CPC : sprites logiciels ---- */
#define pal_sprites ((const void *)0)   /* (pas de palette des sprites : ce sont les encres de l'ecran) */
void gx_sw_set(uint8_t slot, uint8_t xbyte, uint8_t y, uint8_t frame) GXAPI; /* x en octets (2 pixels) */
void gx_sw_hide(uint8_t slot) GXAPI;
void gx_sw_update(void) GXAPI;
#endif

/* ---- decors en tuiles (editeur de decors) ---- */
void gx_tiles_use(const void *gfx, uint8_t width, uint8_t height) GXAPI;   /* TILES_NOM, _W, _H */
void gx_tile_draw(uint8_t tile, uint8_t col, uint8_t line) GXAPI;         /* col en octets */
void gx_map_origin(uint8_t col, uint8_t line) GXAPI;                       /* coin de la carte a l'ecran */
void gx_map_draw(const void *cell, uint8_t map_w, uint8_t cols, uint8_t rows) GXAPI;
uint8_t gx_map_get(const void *map, uint8_t map_w, uint8_t x, uint8_t y) GXAPI;
uint8_t gx_tile_solid(uint8_t tile, const void *flags) GXAPI;              /* TFLAGS_NOM */

/* ---- animations (editeur d'animations) ---- */
typedef struct { uint8_t s[5]; } gx_anim_t;
void gx_anim_start(gx_anim_t *state, const void *anim) GXAPI;              /* ANIM_NOM */
uint8_t gx_anim_step(gx_anim_t *state) GXAPI;                              /* -> F_... */

/* ---- divers (gx.c) ---- */
uint8_t gx_rand(void) GXAPI;
#endif
