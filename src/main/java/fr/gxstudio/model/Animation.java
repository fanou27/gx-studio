package fr.gxstudio.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Animation de sprite (.ani) : une suite d'images (noms des sprites), un delai en images
 * (1/50 s) et un mode : boucle, une_fois ou aller_retour.
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public class Animation {
    public static final String[] MODES = {"boucle", "une_fois", "aller_retour"};
    public final List<String> frames = new ArrayList<>();
    public int delay = 6;
    public int mode = 0;

    public static Animation load(Path f) throws IOException {
        Animation a = new Animation();
        for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            String s = l.replaceAll("[;#].*", "").strip();
            if (s.isEmpty()) continue;
            String[] p = s.split("\\s+");
            switch (p[0]) {
                case "images" -> a.frames.addAll(Arrays.asList(p).subList(1, p.length));
                case "delai" -> a.delay = Math.max(1, Math.min(255, Integer.parseInt(p[1])));
                case "mode" -> a.mode = Math.max(0, Arrays.asList(MODES).indexOf(p[1]));
                default -> { }
            }
        }
        return a;
    }

    public void save(Path f) throws IOException {
        String t = "; GX Studio - animation de sprite\n"
                + "images " + String.join(" ", frames) + "\n"
                + "delai " + delay + "          ; images (1/50 s) par etape\n"
                + "mode " + MODES[mode] + "     ; boucle, une_fois ou aller_retour\n";
        Files.createDirectories(f.getParent());
        Files.writeString(f, t, StandardCharsets.UTF_8);
    }
}
