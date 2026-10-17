package fr.gxstudio.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Palette de 16 couleurs stockees en RVB 4 bits (#RGB, comme l'ASIC des CPC+).
 * Pour un CPC classique, chaque couleur est ramenee a la plus proche des 27 couleurs.
 * Format (.pal) : une ligne par encre : "indice #RGB".
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public class Palette {
    public final int[] rgb = new int[16];   // 0xRGB (4 bits par composante)

    public static Palette load(Path f) throws IOException {
        Palette p = new Palette();
        for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            String t = l.strip();
            if (t.isEmpty() || t.startsWith("#") && !t.matches("#[0-9a-fA-F]{3}") || t.startsWith(";")) continue;
            String[] s = t.split("\\s+");
            if (s.length < 2) continue;
            int i = Integer.parseInt(s[0]);
            if (i >= 0 && i < 16) p.rgb[i] = Integer.parseInt(s[1].replace("#", ""), 16) & 0xFFF;
        }
        return p;
    }

    public void save(Path f) throws IOException {
        StringBuilder sb = new StringBuilder("; GX Studio - palette (indice, couleur #RGB sur 4 bits)\n");
        for (int i = 0; i < 16; i++) sb.append(i).append(String.format(" #%03X%n", rgb[i]));
        Files.createDirectories(f.getParent());
        Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
    }

    public static Palette of(int... cols) {
        Palette p = new Palette();
        System.arraycopy(cols, 0, p.rgb, 0, Math.min(16, cols.length));
        return p;
    }

    // ------------------------------------------------------------ couleurs Amstrad
    /** Niveaux des 27 couleurs du CPC : 0, demi, plein -> 0, 6, 15 sur 4 bits (rendu ASIC). */
    private static final int[] LEVEL = {0, 6, 15};

    /** Couleur firmware (0-26) -> #RGB. Firmware n = 9*vert + 3*rouge + bleu (niveaux 0-2). */
    public static int firmwareToRgb(int n) {
        int g = n / 9, r = (n / 3) % 3, b = n % 3;
        return LEVEL[r] << 8 | LEVEL[g] << 4 | LEVEL[b];
    }

    /** #RGB -> couleur firmware la plus proche. */
    public static int rgbToFirmware(int rgb) {
        int best = 0, bd = Integer.MAX_VALUE;
        for (int n = 0; n < 27; n++) {
            int c = firmwareToRgb(n), d = dist(c, rgb);
            if (d < bd) { bd = d; best = n; }
        }
        return best;
    }

    public static int dist(int a, int b) {
        int dr = ((a >> 8) & 15) - ((b >> 8) & 15), dg = ((a >> 4) & 15) - ((b >> 4) & 15), db = (a & 15) - (b & 15);
        return dr * dr * 3 + dg * dg * 4 + db * db * 2;
    }

    /** Mot ASIC &0GRB. */
    public static int asicWord(int rgb) {
        int r = (rgb >> 8) & 15, g = (rgb >> 4) & 15, b = rgb & 15;
        return g << 8 | r << 4 | b;
    }

    /** #RGB -> couleur 24 bits (affichage). */
    public static int toRgb24(int rgb) {
        int r = (rgb >> 8) & 15, g = (rgb >> 4) & 15, b = rgb & 15;
        return (r * 17) << 16 | (g * 17) << 8 | b * 17;
    }

    /** Couleur telle qu'elle sera vue sur la cible. */
    public static int shown(int rgb, Target t) {
        return t.plus ? rgb : firmwareToRgb(rgbToFirmware(rgb));
    }
}
