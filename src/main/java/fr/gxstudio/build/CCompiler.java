package fr.gxstudio.build;

import fr.gxstudio.model.Project;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.*;
import java.util.stream.Stream;

/**
 * Code C d'un projet (src/*.c et lib/c/*.c) : compile par SDCC (Z80), traduit en
 * syntaxe rasm (SdccToRasm) et rassemble dans build/c_code.asm, que le
 * programme principal inclut. Les fonctions de la bibliotheque de SDCC dont le
 * code a besoin (multiplications, divisions, memcpy...) sont ajoutees a la
 * demande, a partir de leurs sources.
 *
 * Fichiers produits :
 *  - build/c_code.asm : c_init (variables initialisees, mises a zero), le code,
 *    les constantes, les valeurs initiales, et "nom equ c_ram+n" des variables ;
 *  - build/c_vars.inc : C_RAM_SIZE (a reserver : VAR c_ram,C_RAM_SIZE).
 * Convention d'appel : celle de SDCC (sdcccall 1) ; les fonctions de la
 * bibliotheque GX Studio (lib/c/gx.h) sont declarees __sdcccall(0) : parametres
 * sur la pile, resultat dans L ou HL. Le registre IY n'est jamais utilise par
 * le code C (--reserve-regs-iy) ; IX doit etre preserve par l'assembleur.
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public class CCompiler {
    public final List<Builder.Message> messages = new ArrayList<>();
    private final Project p;
    private final Settings settings;
    private final Consumer<String> log;
    /** fichiers C (relatifs au projet) -> indice dans la carte des lignes */
    private final Map<String, Integer> fileIndex = new LinkedHashMap<>();
    /** etiquette de ligne -> {fichier, ligne} */
    public final Map<String, int[]> lineLabels = new HashMap<>();
    public final List<String> files = new ArrayList<>();
    private int firstIndex;

    public CCompiler(Project p, Settings s, Consumer<String> log) { this.p = p; this.settings = s; this.log = log; }

    /** Le projet contient-il du C ? */
    public static boolean hasC(Project p) {
        return sources(p).stream().anyMatch(f -> p.dir.relativize(f).toString().replace('\\', '/').startsWith("src/"));
    }

    /** src/**.c puis lib/c/*.c (bibliotheque, seulement si le projet a du C) */
    static List<Path> sources(Project p) {
        List<Path> r = new ArrayList<>();
        for (String d : List.of("src", "lib/c")) {
            Path dir = p.dir.resolve(d);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> s = Files.walk(dir)) {
                s.filter(f -> f.toString().endsWith(".c")).sorted().forEach(r::add);
            } catch (IOException ignored) { }
        }
        return r;
    }

    /**
     * firstFileIndex : numero de fichier a donner au premier fichier C dans les
     * etiquettes de lignes (".__lF_L", comme les sources assembleur).
     */
    public boolean compile(int firstFileIndex) throws IOException, InterruptedException {
        firstIndex = firstFileIndex;
        Path out = p.build().resolve("c");
        Builder.deleteTree(out);
        Files.createDirectories(out);
        List<SdccToRasm.Module> mods = new ArrayList<>();
        boolean ok = true;
        for (Path src : sources(p)) {
            if (Files.isDirectory(src)) continue;
            String rel = p.dir.relativize(src).toString().replace('\\', '/');
            fileIndex.put(rel, firstIndex + files.size());
            files.add(rel);
            String mod = rel.replaceAll("\\.c$", "").replaceAll("[^A-Za-z0-9]", "_");
            Path asm = out.resolve(mod + ".s");
            if (!sdcc(rel, asm, p.dir)) { ok = false; continue; }
            final String relF = rel;
            mods.add(SdccToRasm.convert(Files.readString(asm, StandardCharsets.ISO_8859_1), mod,
                    (file, line) -> {
                        String f = file.replace('\\', '/');
                        Integer fi = fileIndex.get(f);
                        if (fi == null) return null;          // en-tete (.h) : pas d'etiquette
                        String lab = "__l" + fi + "_" + line;
                        lineLabels.put(lab.toUpperCase(Locale.ROOT), new int[]{fi, line});
                        return lab;
                    }));
        }
        if (!ok) return false;
        // --- fonctions de la bibliotheque de SDCC (a la demande)
        Set<String> defined = new HashSet<>(asmSymbols());
        for (SdccToRasm.Module m : mods) defined.addAll(m.defined);
        Runtime rt = new Runtime();
        for (int pass = 0; pass < 20; pass++) {
            Set<String> need = new TreeSet<>();
            for (SdccToRasm.Module m : mods) for (String r : m.referenced) if (!defined.contains(r)) need.add(r);
            need.removeIf(n -> !n.startsWith("_"));
            if (need.isEmpty()) break;
            boolean added = false;
            for (String n : need) {
                if (defined.contains(n)) continue;
                SdccToRasm.Module m = rt.module(n, out);
                if (m == null) continue;
                mods.add(m);
                defined.addAll(m.defined);
                added = true;
            }
            if (!added) {
                for (String n : need)
                    if (!defined.contains(n)) messages.add(new Builder.Message(null, 0, "C : fonction ou variable introuvable : " + n.substring(1)
                            + " (ni dans le C, ni dans la bibliotheque de SDCC, ni definie en assembleur sous le nom " + n + ")", true));
                return false;
            }
        }
        write(mods);
        log.accept("  C : " + files.size() + " fichier(s), " + mods.size() + " module(s) -> build/c_code.asm");
        return true;
    }

    /** sdcc -mz80 -S : erreurs "fichier:ligne: error n: texte" vers les messages du studio. */
    private boolean sdcc(String rel, Path asm, Path cwd) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(settings.sdcc, "-mz80", "--reserve-regs-iy", "--opt-code-size", "-S",
                "-I", "lib/c", "-I", "build", "-I", "src", "-o", asm.toAbsolutePath().toString()));
        for (String d : p.defines.split("[,\\s]+")) if (!d.isBlank()) cmd.add("-D" + d.trim());
        cmd.add(rel);
        Process proc;
        try { proc = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true).start(); }
        catch (IOException e) {
            messages.add(new Builder.Message(null, 0, "sdcc introuvable (" + settings.sdcc + ") : installer SDCC (sudo apt install sdcc) ou voir Reglages", true));
            return false;
        }
        boolean err = false;
        Pattern pm = Pattern.compile("^(.+?):(\\d+):\\s*(.*)$");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.ISO_8859_1))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                log.accept("  " + line);
                Matcher m = pm.matcher(line.trim());
                if (m.matches() && !m.group(1).contains(" ")) {
                    boolean warn = m.group(3).toLowerCase(Locale.ROOT).startsWith("warning");
                    messages.add(new Builder.Message(p.dir.resolve(m.group(1)), Integer.parseInt(m.group(2)), m.group(3), !warn));
                    if (!warn) err = true;
                }
            }
        }
        int code = proc.waitFor();
        if (code != 0 && !err) messages.add(new Builder.Message(p.dir.resolve(rel), 0, "sdcc : echec de la compilation", true));
        return code == 0 && !err;
    }

    /** Symboles "_nom" definis en assembleur dans le projet (src, lib, build/gen.asm) : fonctions C ecrites en asm. */
    private Set<String> asmSymbols() throws IOException {
        Set<String> r = new HashSet<>();
        Pattern lab = Pattern.compile("^(_[A-Za-z_]\\w*)\\s*(:|\\s+equ\\b)", Pattern.CASE_INSENSITIVE);
        List<Path> fs = new ArrayList<>();
        for (String d : List.of("src", "lib")) {
            Path dir = p.dir.resolve(d);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> s = Files.walk(dir)) { s.filter(f -> f.toString().endsWith(".asm")).forEach(fs::add); }
        }
        Path gen = p.build().resolve("gen.asm");
        if (Files.exists(gen)) fs.add(gen);
        for (Path f : fs)
            for (String l : Files.readAllLines(f, StandardCharsets.ISO_8859_1)) {
                Matcher m = lab.matcher(l.trim());
                if (m.find()) r.add(m.group(1));
            }
        return r;
    }

    /** Assemblage de build/c_code.asm et build/c_vars.inc */
    private void write(List<SdccToRasm.Module> mods) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("; ---- genere par GX Studio : code C (SDCC) traduit pour rasm - ne pas modifier ----\n");
        int initSize = 0, bssSize = 0;
        for (SdccToRasm.Module m : mods) for (int v : m.initVars.values()) initSize += v;
        for (SdccToRasm.Module m : mods) for (int v : m.bssVars.values()) bssSize += v;
        sb.append("; c_init : valeurs initiales des variables, autres variables a zero,\n");
        sb.append(";   initialisations statiques. A appeler avant la premiere fonction C.\n");
        sb.append("c_init:\n");
        if (initSize > 0)
            sb.append("        ld hl,c_initializer\n        ld de,c_ram\n        ld bc,").append(initSize).append("\n        ldir\n");
        if (bssSize > 0) {
            sb.append("        ld hl,c_ram+").append(initSize).append("\n        ld bc,").append(bssSize).append("\n");
            sb.append("c_init_z:\n        ld (hl),0\n        inc hl\n        dec bc\n        ld a,b\n        or c\n        jr nz,c_init_z\n");
        }
        for (SdccToRasm.Module m : mods) for (String l : m.gsinit) sb.append(l).append('\n');
        sb.append("        ret\n\n");
        for (SdccToRasm.Module m : mods) {
            sb.append("; ======== ").append(m.name).append('\n');
            for (String l : m.code) sb.append(l).append('\n');
        }
        sb.append("\nc_initializer:\n");
        for (SdccToRasm.Module m : mods) for (String l : m.initializer) sb.append(l).append('\n');
        sb.append("\n; variables du C (RAM reservee par le programme : VAR c_ram,C_RAM_SIZE)\n");
        int off = 0;
        for (SdccToRasm.Module m : mods) for (var e : m.initVars.entrySet()) { sb.append(e.getKey()).append(" equ c_ram+").append(off).append('\n'); off += e.getValue(); }
        for (SdccToRasm.Module m : mods) for (var e : m.bssVars.entrySet()) { sb.append(e.getKey()).append(" equ c_ram+").append(off).append('\n'); off += e.getValue(); }
        Files.writeString(p.build().resolve("c_code.asm"), sb.toString(), StandardCharsets.ISO_8859_1);
        Files.writeString(p.build().resolve("c_vars.inc"), "; genere par GX Studio : RAM des variables du C\nC_RAM_SIZE equ " + Math.max(1, off) + "\n");
    }

    /** Bibliotheque de SDCC : sources .s (z80) et .c, compilees a la demande. */
    private class Runtime {
        private final Map<String, Path> asmIndex = new HashMap<>();
        private Path srcRoot;
        private final Set<Path> done = new HashSet<>();

        Runtime() throws IOException, InterruptedException {
            srcRoot = findSrc();
            if (srcRoot == null) return;
            Path z = srcRoot.resolve("z80");
            if (Files.isDirectory(z)) try (Stream<Path> s = Files.list(z)) {
                for (Path f : s.filter(x -> x.toString().endsWith(".s")).sorted().toList()) {
                    String n = f.getFileName().toString();
                    if (n.startsWith("crt0") || n.equals("heap.s")) continue;
                    Set<String> glob = new HashSet<>();
                    for (String l : Files.readAllLines(f, StandardCharsets.ISO_8859_1)) {
                        String t = SdccToRasm.stripComment(l).trim();
                        if (t.startsWith(".globl")) for (String g : t.substring(6).split(",")) glob.add(g.trim());
                        Matcher m = Pattern.compile("^([A-Za-z_]\\w*)(::?)").matcher(t);
                        if (m.find() && (m.group(2).equals("::") || glob.contains(m.group(1)))) asmIndex.putIfAbsent(m.group(1), f);
                    }
                }
            }
        }

        private Path findSrc() throws IOException, InterruptedException {
            try {
                Process pr = new ProcessBuilder(settings.sdcc, "-mz80", "--print-search-dirs").redirectErrorStream(true).start();
                List<String> ls = new BufferedReader(new InputStreamReader(pr.getInputStream())).lines().toList();
                pr.waitFor();
                boolean lib = false;
                for (String l : ls) {
                    if (l.endsWith(":")) { lib = l.startsWith("libdir"); continue; }
                    if (lib) {
                        Path src = Paths.get(l.trim()).getParent().resolve("src").normalize();
                        if (Files.isDirectory(src)) return src;
                    }
                }
            } catch (IOException ignored) { }
            for (String c : List.of("/usr/share/sdcc/lib/src", "/usr/local/share/sdcc/lib/src"))
                if (Files.isDirectory(Paths.get(c))) return Paths.get(c);
            return null;
        }

        /** Module qui definit le symbole n (ou null). */
        SdccToRasm.Module module(String n, Path out) throws IOException, InterruptedException {
            if (srcRoot == null) return null;
            Path f = asmIndex.get(n);
            if (f != null) {
                if (!done.add(f)) return null;
                String mod = "rt_" + f.getFileName().toString().replace(".s", "");
                return SdccToRasm.convert(Files.readString(f, StandardCharsets.ISO_8859_1), mod, null);
            }
            String base = n.substring(1);                         // _memset -> memset, __mullong -> _mullong
            for (String cand : List.of(base + ".c", "_" + base + ".c")) {
                Path c = srcRoot.resolve(cand);
                if (!Files.exists(c) || !done.add(c)) continue;
                String mod = "rt_" + cand.replace(".c", "").replaceAll("[^A-Za-z0-9]", "_");
                Path asm = out.resolve(mod + ".s");
                Process pr = new ProcessBuilder(settings.sdcc, "-mz80", "--reserve-regs-iy", "--opt-code-size", "-S", "-o",
                        asm.toAbsolutePath().toString(), c.toAbsolutePath().toString()).redirectErrorStream(true).start();
                String o = new String(pr.getInputStream().readAllBytes());
                if (pr.waitFor() != 0) { messages.add(new Builder.Message(null, 0, "sdcc (bibliotheque " + cand + ") : " + o.trim(), true)); return null; }
                return SdccToRasm.convert(Files.readString(asm, StandardCharsets.ISO_8859_1), mod, null);
            }
            return null;
        }
    }
}
