package fr.gxstudio.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Table de rasters (.ras, CPC+ / GX4000) : a une ligne donnee, une encre change de couleur.
 * Une ligne par changement : "ligne encre #RGB".
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public class RasterTable {
    public record Entry(int line, int ink, int rgb) { }
    public final List<Entry> entries = new ArrayList<>();

    public static RasterTable load(Path f) throws IOException {
        RasterTable r = new RasterTable();
        for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            String s = l.replaceAll(";.*", "").strip();
            if (s.isEmpty() || s.startsWith("//")) continue;
            String[] p = s.split("\\s+");
            if (p.length < 3) continue;
            try {
                r.entries.add(new Entry(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2].replace("#", ""), 16) & 0xFFF));
            } catch (NumberFormatException ignored) { }
        }
        r.sort();
        return r;
    }

    public void save(Path f) throws IOException {
        StringBuilder sb = new StringBuilder("; GX Studio - rasters : ligne encre couleur(#RGB)\n");
        for (Entry e : entries) sb.append(String.format("%d %d #%03X%n", e.line(), e.ink(), e.rgb()));
        Files.createDirectories(f.getParent());
        Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
    }

    public void sort() { entries.sort(Comparator.comparingInt(Entry::line)); }

    /** Problemes : lignes hors 1..197, trop proches (moins de 2 lignes d'ecart). */
    public List<String> check() {
        List<String> pb = new ArrayList<>();
        int last = -10;
        for (Entry e : entries) {
            if (e.line() < 1 || e.line() > 197) pb.add("ligne " + e.line() + " : doit etre entre 1 et 197");
            if (e.line() - last < 2) pb.add("ligne " + e.line() + " : trop proche de la precedente (2 lignes minimum)");
            if (e.ink() < 0 || e.ink() > 15) pb.add("ligne " + e.line() + " : encre 0 a 15");
            last = e.line();
        }
        return pb;
    }
}
