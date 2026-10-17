package fr.gxstudio.build;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/**
 * Reglages de l'atelier (~/.gxstudio/settings.properties) : outils externes.
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public class Settings {
    public static final Path DIR = Paths.get(System.getProperty("user.home"), ".gxstudio");
    public static final Path FILE = DIR.resolve("settings.properties");
    public String rasm = "rasm";
    /** Compilateur C pour Z80 (SDCC), pour les fichiers src/*.c */
    public String sdcc = "sdcc";
    public String caprice = "cap32";
    public String capriceOptions = "";
    /** Dossier des modeles (lib + projets de depart). Vide = a cote du programme. */
    public String templates = "";
    public String lastProject = "";

    public static Settings load() {
        Settings s = new Settings();
        Properties p = new Properties();
        if (Files.exists(FILE)) try (Reader r = Files.newBufferedReader(FILE)) { p.load(r); } catch (IOException ignored) { }
        s.rasm = p.getProperty("rasm", s.rasm);
        s.sdcc = p.getProperty("sdcc", s.sdcc);
        s.caprice = p.getProperty("caprice", s.caprice);
        s.capriceOptions = p.getProperty("capriceOptions", s.capriceOptions);
        s.templates = p.getProperty("templates", s.templates);
        s.lastProject = p.getProperty("lastProject", "");
        return s;
    }

    public void save() {
        Properties p = new Properties();
        p.setProperty("rasm", rasm);
        p.setProperty("sdcc", sdcc);
        p.setProperty("caprice", caprice);
        p.setProperty("capriceOptions", capriceOptions);
        p.setProperty("templates", templates);
        p.setProperty("lastProject", lastProject);
        try {
            Files.createDirectories(DIR);
            try (Writer w = Files.newBufferedWriter(FILE)) { p.store(w, "GX Studio"); }
        } catch (IOException ignored) { }
    }

    /** Dossier des modeles : reglage, sinon variable GXSTUDIO_HOME, sinon ./templates. */
    public Path templatesDir() {
        if (!templates.isBlank()) return Paths.get(templates);
        String home = System.getenv("GXSTUDIO_HOME");
        if (home != null) return Paths.get(home, "templates");
        try {
            Path jar = Paths.get(Settings.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            for (Path d = jar.getParent(); d != null; d = d.getParent())
                if (Files.isDirectory(d.resolve("templates"))) return d.resolve("templates");
        } catch (Exception ignored) { }
        return Paths.get("templates");
    }
}
