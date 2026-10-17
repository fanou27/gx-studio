package fr.gxstudio.build;

import fr.gxstudio.model.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Creation d'un projet a partir des modeles (templates/).
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public class ProjectFactory {

    public static Project create(Path dir, String name, Target target, int ramKb, int mode, Path templates) throws IOException {
        return create(dir, name, target, ramKb, mode, templates, false);
    }

    /** c = true : projet en C (src/main.c + src/main.asm qui assemble le tout). */
    public static Project create(Path dir, String name, Target target, int ramKb, int mode, Path templates, boolean c) throws IOException {
        if (Files.exists(dir.resolve(Project.FILE))) throw new IOException("Un projet existe deja dans " + dir);
        Files.createDirectories(dir);
        Project p = new Project(dir);
        p.name = name;
        p.target = target;
        p.ramKb = ramKb;
        p.fixRam();
        p.screenMode = mode;
        Map<String, String> vars = Map.of(
                "{{NAME}}", name,
                "{{NAME_UPPER}}", name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9 !?:.,'()/+-]", " "),
                "{{TARGET}}", target.label,
                "{{SHORT}}", p.shortName());
        String starter = (target.plus ? "starter-plus" : "starter-cpc") + (c ? "-c" : "");
        copy(templates.resolve(starter), dir, vars);
        copy(templates.resolve("starter-common"), dir, vars);
        updateLib(p, templates);
        Files.createDirectories(p.assets("images"));
        p.save();
        return p;
    }

    /** (Re)copie la bibliotheque adaptee a la cible dans lib/ (common + plus ou cpc). */
    public static void updateLib(Project p, Path templates) throws IOException {
        Path lib = p.dir.resolve("lib");
        copy(templates.resolve("lib/common"), lib.resolve("common"), Map.of());
        String sub = p.target.plus ? "plus" : "cpc";
        copy(templates.resolve("lib").resolve(sub), lib.resolve(sub), Map.of());
        if (Files.isDirectory(templates.resolve("lib/c"))) copy(templates.resolve("lib/c"), lib.resolve("c"), Map.of());
    }

    static void copy(Path from, Path to, Map<String, String> vars) throws IOException {
        if (!Files.isDirectory(from)) throw new IOException("Modele introuvable : " + from);
        try (Stream<Path> s = Files.walk(from)) {
            for (Path f : (Iterable<Path>) s::iterator) {
                Path dst = to.resolve(from.relativize(f).toString());
                if (Files.isDirectory(f)) { Files.createDirectories(dst); continue; }
                String n = f.getFileName().toString();
                if (n.endsWith(".asm") || n.endsWith(".inc") || n.endsWith(".txt") || n.endsWith(".c") || n.endsWith(".h")) {
                    String t = Files.readString(f, StandardCharsets.ISO_8859_1);
                    for (var e : vars.entrySet()) t = t.replace(e.getKey(), e.getValue());
                    Files.writeString(dst, t, StandardCharsets.ISO_8859_1);
                } else Files.copy(f, dst, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
