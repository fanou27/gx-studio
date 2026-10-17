package fr.gxstudio.model;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * Un projet : un dossier contenant projet.gxs, src/, lib/, assets/ et build/.
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public class Project {
    public static final String FILE = "projet.gxs";
    public final Path dir;
    public String name = "MonJeu";
    public Target target = Target.GX4000;
    public int ramKb = 64;
    public int screenMode = 1;
    public String mainFile = "src/main.asm";
    /** definitions -D passees a rasm (mise au point), ex. "GOD_MODE=1". */
    public String defines = "";

    public Project(Path dir) { this.dir = dir; }

    public static Project load(Path dir) throws IOException {
        Project p = new Project(dir);
        Properties pr = new Properties();
        try (Reader r = Files.newBufferedReader(dir.resolve(FILE), StandardCharsets.UTF_8)) { pr.load(r); }
        p.name = pr.getProperty("name", p.name);
        p.target = Target.valueOf(pr.getProperty("target", p.target.name()));
        p.ramKb = Integer.parseInt(pr.getProperty("ram", "64"));
        p.screenMode = Integer.parseInt(pr.getProperty("mode", p.target.plus ? "1" : "0"));
        p.mainFile = pr.getProperty("main", p.mainFile);
        p.defines = pr.getProperty("defines", "");
        p.fixRam();
        return p;
    }

    public void save() throws IOException {
        String t = "# GX Studio - projet\n"
                + "name=" + esc(name) + "\n"
                + "target=" + target.name() + "\n"
                + "ram=" + ramKb + "\n"
                + "mode=" + screenMode + "\n"
                + "main=" + esc(mainFile) + "\n"
                + "defines=" + esc(defines) + "\n";
        Files.writeString(dir.resolve(FILE), t, StandardCharsets.UTF_8);
    }

    private static String esc(String s) { return s.replace("\\", "\\\\").replace("\n", " "); }

    /** La RAM doit etre une des valeurs permises pour la cible. */
    public void fixRam() {
        int[] ch = target.ramChoices();
        for (int c : ch) if (c == ramKb) return;
        ramKb = ch[0];
    }

    /** Nom court pour les fichiers CPC (8 caracteres, majuscules). */
    public String shortName() {
        String s = name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (s.isEmpty()) s = "JEU";
        return s.length() > 8 ? s.substring(0, 8) : s;
    }

    public Path assets(String kind) { return dir.resolve("assets").resolve(kind); }
    public Path build() { return dir.resolve("build"); }
    public Path output() { return build().resolve(shortName().toLowerCase(Locale.ROOT) + "." + target.outputExt()); }

    /** Fichiers d'un type d'asset, tries par nom (l'ordre donne les numeros). */
    public List<Path> list(String kind, String ext) throws IOException {
        Path d = assets(kind);
        if (!Files.isDirectory(d)) return List.of();
        try (Stream<Path> s = Files.list(d)) {
            return s.filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith("." + ext))
                    .sorted().collect(Collectors.toList());
        }
    }

    public static String baseName(Path f) {
        String n = f.getFileName().toString();
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    /** Identifiant assembleur a partir d'un nom de fichier. */
    public static String ident(Path f) {
        return baseName(f).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
    }
}
