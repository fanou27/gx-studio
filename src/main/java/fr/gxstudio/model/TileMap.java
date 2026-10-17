package fr.gxstudio.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * Carte (.map) : un tableau de numeros de tuiles (0-255) qui designe un jeu de tuiles.
 * <pre>
 * tuiles decor
 * size 40 25
 * 00 01 01 ...
 * </pre>
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public class TileMap {
    public String tileset = "";
    public int w = 40, h = 25;
    public int[][] cells = new int[25][40];

    public static TileMap load(Path f) throws IOException {
        TileMap m = new TileMap();
        int row = 0;
        for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            String s = l.strip();
            if (s.isEmpty() || s.startsWith("#") || s.startsWith(";")) continue;
            String[] p = s.split("\\s+");
            if (p[0].equals("tuiles")) m.tileset = p.length > 1 ? p[1] : "";
            else if (p[0].equals("size")) {
                m.w = Integer.parseInt(p[1]); m.h = Integer.parseInt(p[2]);
                m.cells = new int[m.h][m.w];
            } else if (row < m.h) {
                for (int x = 0; x < m.w && x < p.length; x++) m.cells[row][x] = Integer.parseInt(p[x], 16) & 255;
                row++;
            }
        }
        return m;
    }

    public void save(Path f) throws IOException {
        StringBuilder sb = new StringBuilder("# GX Studio - carte (numeros de tuiles en hexadecimal)\ntuiles " + tileset + "\nsize " + w + " " + h + "\n");
        for (int[] r : cells) {
            for (int x = 0; x < r.length; x++) sb.append(x > 0 ? " " : "").append(String.format("%02X", r[x]));
            sb.append('\n');
        }
        Files.createDirectories(f.getParent());
        Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
    }

    public void resize(int nw, int nh) {
        int[][] n = new int[nh][nw];
        for (int y = 0; y < Math.min(h, nh); y++) for (int x = 0; x < Math.min(w, nw); x++) n[y][x] = cells[y][x];
        cells = n; w = nw; h = nh;
    }

    public TileMap copy() {
        TileMap m = new TileMap();
        m.tileset = tileset; m.w = w; m.h = h;
        m.cells = new int[h][];
        for (int y = 0; y < h; y++) m.cells[y] = cells[y].clone();
        return m;
    }
}
