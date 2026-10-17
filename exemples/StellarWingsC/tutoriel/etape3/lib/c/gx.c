/*============================================================================
 * lib/c/gx.c - petites fonctions de la bibliotheque GX Studio ecrites en C
 *==========================================================================*/
#include "gx.h"
#include <string.h>

/* gx_text_num : affiche value sur digits chiffres (zeros a gauche) */
void gx_text_num(uint8_t col, uint8_t line, uint16_t value, uint8_t digits) GXAPI
{
    char buf[6];
    uint8_t i;
    if (digits > 5) digits = 5;
    buf[digits] = 0;
    for (i = digits; i > 0; i--) {
        buf[i - 1] = '0' + (char)(value % 10);
        value /= 10;
    }
    gx_text(col, line, buf);
}

/* gx_rand : nombre pseudo-aleatoire 0-255 (registre a decalage 16 bits) */
static uint16_t gx_seed = 0xACE1;
uint8_t gx_rand(void) GXAPI
{
    uint16_t s = gx_seed;
    s ^= s << 7;
    s ^= s >> 9;
    s ^= s << 8;
    gx_seed = s;
    return (uint8_t)(s ^ frame_count);
}

/* gx_clear_lines : efface n lignes de l'ecran a partir de y */
void gx_clear_lines(uint8_t y, uint8_t n) GXAPI
{
    while (n--) memset(GX_LINE(y++), 0, 80);          /* 80 octets par ligne */
}

/* gx_blit : copie une image (ligne par ligne) ; CPC+ : l'image est lue dans la page de
 * donnees de la cartouche visible en &C000, et ecrite dans l'ecran (RAM sous la ROM) */
void gx_blit(const void *src, uint8_t wbytes, uint8_t h, uint8_t xbyte, uint8_t y) GXAPI
{
    const uint8_t *s = (const uint8_t *)src;
    while (h--) {
        memcpy(GX_LINE(y++) + xbyte, s, wbytes);
        s += wbytes;
    }
}
