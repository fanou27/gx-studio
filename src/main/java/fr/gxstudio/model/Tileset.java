package fr.gxstudio.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Jeu de tuiles (.til) : des images de W x H pixels dans les encres de l'ecran,
 * chacune avec un octet d'attributs (bit 0 = solide, les autres libres pour le jeu).
 * <pre>
 * size 8 8
 * tile flags 1
 * 11111111
 * ...
 * </pre>
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public class Tileset {
    public int w = 8, h = 8;
    public final List<int[][]> tiles = new ArrayList<>();
    public final List<Integer> flags = new ArrayList<>();

    public static Tileset load(Path f) throws IOException {
        Tileset t = new Tileset();
        int[][] cur = null;
        int row = 0;
        for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            String s = l.strip();
            if (s.isEmpty() || s.startsWith("#") || s.startsWith(";")) continue;
            if (s.startsWith("size")) {
                String[] p = s.split("\\s+");
                t.w = Integer.parseInt(p[1]); t.h = Integer.parseInt(p[2]);
            } else if (s.startsWith("tile")) {
                String[] p = s.split("\\s+");
                int fl = 0;
                for (int i = 1; i + 1 < p.length; i++) if (p[i].equals("flags")) fl = Integer.parseInt(p[i + 1]);
                cur = new int[t.h][t.w];
                t.tiles.add(cur);
                t.flags.add(fl & 255);
                row = 0;
            } else if (cur != null && row < t.h) {
                for (int x = 0; x < t.w && x < s.length(); x++) cur[row][x] = Math.max(0, Sprite.HEX.indexOf(Character.toLowerCase(s.charAt(x))));
                row++;
            }
        }
        if (t.tiles.isEmpty()) t.addTile();
        return t;
    }

    public void save(Path f) throws IOException {
        StringBuilder sb = new StringBuilder("# GX Studio - jeu de tuiles (encres de l'ecran ; flags : bit 0 = solide)\nsize " + w + " " + h + "\n");
        for (int i = 0; i < tiles.size(); i++) {
            sb.append("tile ").append(i).append(" flags ").append(flags.get(i)).append('\n');
            for (int[] r : tiles.get(i)) {
                for (int v : r) sb.append(Sprite.HEX.charAt(v & 15));
                sb.append('\n');
            }
        }
        Files.createDirectories(f.getParent());
        Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
    }

    public int addTile() { tiles.add(new int[h][w]); flags.add(0); return tiles.size() - 1; }

    public int duplicate(int i) {
        int[][] c = new int[h][];
        for (int y = 0; y < h; y++) c[y] = tiles.get(i)[y].clone();
        tiles.add(c);
        flags.add(flags.get(i));
        return tiles.size() - 1;
    }

    /** Change la taille de toutes les tuiles (contenu conserve en haut a gauche). */
    public void resize(int nw, int nh) {
        for (int i = 0; i < tiles.size(); i++) {
            int[][] o = tiles.get(i), n = new int[nh][nw];
            for (int y = 0; y < Math.min(h, nh); y++) for (int x = 0; x < Math.min(w, nw); x++) n[y][x] = o[y][x];
            tiles.set(i, n);
        }
        w = nw; h = nh;
    }

    /** Index d'une tuile identique, ou -1. */
    public int find(int[][] t) {
        for (int i = 0; i < tiles.size(); i++) if (Arrays.deepEquals(tiles.get(i), t)) return i;
        return -1;
    }
}
