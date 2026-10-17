package fr.gxstudio.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Une image de sprite : largeur x hauteur pixels, valeur 0 = transparent, 1-15 = couleur.
 * Format texte (.spr) lisible et versionnable :
 * <pre>
 * size 16 16
 * ....1111....
 * </pre>
 * '.' = 0, '1'-'9' et 'a'-'f' = couleurs 1 a 15.
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public class Sprite {
    public static final String HEX = ".123456789abcdef";
    public int w, h;
    public int[][] px;      // [y][x]

    public Sprite(int w, int h) { this.w = w; this.h = h; px = new int[h][w]; }

    public Sprite copy() {
        Sprite s = new Sprite(w, h);
        for (int y = 0; y < h; y++) s.px[y] = px[y].clone();
        return s;
    }

    public static Sprite load(Path f) throws IOException {
        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        int w = 16, h = 16;
        List<String> rows = new ArrayList<>();
        for (String l : lines) {
            String t = l.strip();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith(";")) continue;
            if (t.startsWith("size")) {
                String[] p = t.split("\\s+");
                w = Integer.parseInt(p[1]); h = Integer.parseInt(p[2]);
            } else rows.add(t);
        }
        Sprite s = new Sprite(w, h);
        for (int y = 0; y < h && y < rows.size(); y++) {
            String r = rows.get(y);
            for (int x = 0; x < w && x < r.length(); x++) {
                int v = HEX.indexOf(Character.toLowerCase(r.charAt(x)));
                s.px[y][x] = Math.max(v, 0);
            }
        }
        return s;
    }

    public void save(Path f) throws IOException {
        StringBuilder sb = new StringBuilder("# GX Studio - sprite\nsize " + w + " " + h + "\n");
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) sb.append(HEX.charAt(px[y][x] & 15));
            sb.append('\n');
        }
        Files.createDirectories(f.getParent());
        Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
    }

    /** Redimensionne (contenu conserve en haut a gauche). */
    public Sprite resized(int nw, int nh) {
        Sprite s = new Sprite(nw, nh);
        for (int y = 0; y < Math.min(h, nh); y++)
            for (int x = 0; x < Math.min(w, nw); x++) s.px[y][x] = px[y][x];
        return s;
    }

    public static Sprite fromRows(String... rows) {
        Sprite s = new Sprite(rows[0].length(), rows.length);
        for (int y = 0; y < rows.length; y++)
            for (int x = 0; x < rows[y].length(); x++) s.px[y][x] = Math.max(0, HEX.indexOf(rows[y].charAt(x)));
        return s;
    }
}
