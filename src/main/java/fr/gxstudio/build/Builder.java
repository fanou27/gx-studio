package fr.gxstudio.build;

import fr.gxstudio.gen.AssetCompiler;
import fr.gxstudio.model.Project;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.*;
import java.util.stream.Stream;

/**
 * Construction d'un projet :
 * 1. ressources -> build/ (AssetCompiler) ;
 * 2. copie des sources dans build/stage/ en y ajoutant une etiquette locale
 *    ".__lF_L" devant chaque ligne de code (meme ligne : les numeros de ligne
 *    des erreurs restent justes) : rasm donne ainsi l'adresse de chaque ligne,
 *    pour les points d'arret et le pas a pas ;
 * 2 bis. code C (CCompiler : SDCC puis traduction pour rasm) ;
 * 3. rasm -> .cpr ou .dsk + symboles ;
 * 4. fichier de symboles pour Caprice32 (etiquettes + points d'arret) et
 *    carte lignes <-> adresses (debogage depuis le studio).
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public class Builder {
    public record Message(Path file, int line, String text, boolean error) {
        @Override public String toString() {
            return (file != null ? file.getFileName() + ":" + line + " : " : "") + text;
        }
    }

    public final List<Message> messages = new ArrayList<>();
    public final Map<String, Integer> symbols = new LinkedHashMap<>();
    /** Noms des etiquettes (adresses de code ou de donnees), sans les EQU. */
    public final Set<String> labels = new HashSet<>();
    /** Etiquettes d'une page recouverte par une autre aux memes adresses (voir readSymbols). */
    public final Set<String> shadowed = new HashSet<>();
    public final List<Integer> breakpointAddrs = new ArrayList<>();
    /** Carte des lignes : "fichier:ligne" -> adresse, et adresse -> lignes. */
    public final Map<String, Integer> addrOfLine = new HashMap<>();
    public final TreeMap<Integer, List<SourceLine>> linesAtAddr = new TreeMap<>();
    private final List<String> lineFiles = new ArrayList<>();
    private final Set<String> emitting = new HashSet<>();
    public boolean ok;
    private final Project p;
    private final Settings settings;
    private final Consumer<String> log;

    public Builder(Project p, Settings s, Consumer<String> log) { this.p = p; this.settings = s; this.log = log; }

    private static final Pattern ERR = Pattern.compile("\\[(.+?):(\\d+)\\]\\s*(.*)");
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*m");

    /** breakpoints : fichier (relatif au projet, ex. src/main.asm) -> lignes (1 = premiere). */
    public boolean build(Map<String, Set<Integer>> breakpoints) throws IOException, InterruptedException {
        ok = false;
        messages.clear();
        log.accept("== Ressources (sprites, palettes, sons, images)");
        AssetCompiler ac = new AssetCompiler(p);
        ac.compile();
        for (String m : ac.messages) { messages.add(new Message(null, 0, m, false)); log.accept("  " + m); }

        Path stage = p.build().resolve("stage");
        deleteTree(stage);
        lineFiles.clear();
        Map<String, int[]> lineLabels = new HashMap<>();          // etiquette de ligne -> {fichier, ligne}
        for (String dir : List.of("src", "lib")) {
            Path from = p.dir.resolve(dir);
            if (!Files.isDirectory(from)) continue;
            try (Stream<Path> s = Files.walk(from)) {
                for (Path f : (Iterable<Path>) s.sorted()::iterator) {
                    Path rel = p.dir.relativize(f), to = stage.resolve(rel.toString());
                    if (Files.isDirectory(f)) { Files.createDirectories(to); continue; }
                    if (!f.toString().endsWith(".asm")) { Files.copy(f, to); continue; }
                    int fi = lineFiles.size();
                    lineFiles.add(rel.toString().replace('\\', '/'));
                    List<String> lines = Files.readAllLines(f, StandardCharsets.ISO_8859_1);
                    // une etiquette locale (".__lF_L") devant chaque ligne de code, sur la meme ligne :
                    // les numeros de ligne des erreurs restent justes, et rasm donne l'adresse de chaque ligne
                    int depth = 0;
                    for (int i = 0; i < lines.size(); i++) {
                        String t = lines.get(i).replaceAll(";.*", "").trim().toLowerCase(Locale.ROOT);
                        if (t.matches("(.*\\s)?(macro|repeat|rept|while|struct|function)(\\s.*)?")) { depth++; continue; }
                        if (t.matches("(.*\\s)?(mend|endm|endmacro|rend|endrepeat|endr|wend|endwhile|endstruct|endfunction)(\\s.*)?")) { depth = Math.max(0, depth - 1); continue; }
                        if (depth > 0 || !isCode(lines.get(i))) continue;
                        String lab = "__l" + fi + "_" + (i + 1);
                        lines.set(i, "." + lab + " : " + lines.get(i));
                        lineLabels.put(lab.toUpperCase(Locale.ROOT), new int[]{fi, i + 1});
                    }
                    Files.write(to, lines, StandardCharsets.ISO_8859_1);
                }
            }
        }
        // code C (src/*.c, lib/c/*.c) : SDCC -> build/c_code.asm
        if (CCompiler.hasC(p)) {
            log.accept("== C (SDCC)");
            CCompiler cc = new CCompiler(p, settings, log);
            boolean cok = cc.compile(lineFiles.size());
            messages.addAll(cc.messages);
            if (!cok) { log.accept("== ECHEC (C)"); return false; }
            lineFiles.addAll(cc.files);
            lineLabels.putAll(cc.lineLabels);
        }
        // build/ vu depuis stage/ : lien vers le dossier build (gen.asm, *.bin)
        Path link = stage.resolve("build");
        try { Files.createSymbolicLink(link, p.build().toAbsolutePath()); }
        catch (Exception e) { copyFlat(p.build(), link); }

        Path main = stage.resolve(p.mainFile);
        String radix = p.build().resolve(p.shortName().toLowerCase(Locale.ROOT)).toAbsolutePath().toString();
        List<String> cmd = new ArrayList<>(List.of(settings.rasm, main.getFileName().toString(),
                "-o", radix, "-s", "-sa", "-eo"));     // -sa : etiquettes, locales, EQU et variables
        for (String d : p.defines.split("[,\\s]+")) if (!d.isBlank()) cmd.add("-D" + d.trim());
        log.accept("== " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(main.getParent().toFile()).redirectErrorStream(true);
        if (!p.target.plus) Files.deleteIfExists(p.output());     // la disquette est recreee
        Process proc;
        try { proc = pb.start(); }
        catch (IOException e) {
            messages.add(new Message(null, 0, "rasm introuvable (" + settings.rasm + ") : voir Reglages", true));
            log.accept("ERREUR : rasm introuvable (" + settings.rasm + ")");
            return false;
        }
        int errors = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.ISO_8859_1))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = ANSI.matcher(line).replaceAll("");
                if (line.isBlank()) continue;
                log.accept(line);
                Matcher m = ERR.matcher(line.trim());
                if (m.matches()) {
                    Path src = main.getParent().resolve(m.group(1)).normalize();
                    Path real = src.startsWith(stage) ? p.dir.resolve(stage.relativize(src).toString()) : src;
                    boolean warn = m.group(3).toLowerCase(Locale.ROOT).startsWith("warning");
                    messages.add(new Message(real, Integer.parseInt(m.group(2)), m.group(3), !warn));
                    if (!warn) errors++;
                }
            }
        }
        int code = proc.waitFor();
        ok = code == 0 && errors == 0;
        if (ok) readSymbols(Paths.get(radix + ".sym"), lineLabels, breakpoints);
        log.accept(ok ? "== OK : " + p.output() : "== ECHEC (" + Math.max(errors, 1) + " erreur(s))");
        return ok;
    }

    private static boolean isCode(String l) {
        String t = l.replaceAll(";.*", "").trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return false;
        if (t.matches("^[a-z_.@][\\w.@]*:?\\s*$")) return false;            // etiquette seule
        if (t.matches(".*\\b(equ|macro|mend|endm|include|incbin|org|if|ifdef|ifndef|else|endif|repeat|rend|bank|buildcpr|save|assert|print|struct|endstruct)\\b.*")) return false;
        return !t.startsWith("=") && !t.contains("=");
    }

    private void readSymbols(Path sym, Map<String, int[]> lineLabels, Map<String, Set<Integer>> breakpoints) throws IOException {
        symbols.clear();
        labels.clear();
        breakpointAddrs.clear();
        addrOfLine.clear();
        linesAtAddr.clear();
        if (!Files.exists(sym)) return;
        shadowed.clear();
        StringBuilder cap = new StringBuilder("; symboles pour Caprice32 (GX Studio)\n");
        List<String> all = Files.readAllLines(sym, StandardCharsets.ISO_8859_1);
        // Pages de cartouche aux memes adresses (ex. : page 0 = demarrage en ROM, page 1 =
        // programme recopie en RAM en &0000) : pour retrouver une ligne ou une routine a partir
        // d'une adresse (profil, pile, debogueur), la page de numero le plus grand l'emporte ;
        // celle qu'elle recouvre ne sert qu'au demarrage.
        Map<Integer, int[]> range = new HashMap<>();
        for (String l : all) {
            String[] s = l.trim().split("\\s+");
            if (s.length < 4 || !s[1].startsWith("#") || !s[3].equals("L") || !s[2].matches("B\\d+")) continue;
            int addr = Integer.parseInt(s[1].substring(1), 16) & 0xFFFF;
            int[] r = range.computeIfAbsent(Integer.parseInt(s[2].substring(1)), k -> new int[] { addr, addr });
            r[0] = Math.min(r[0], addr);
            r[1] = Math.max(r[1], addr);
        }
        for (String l : all) {
            String[] s = l.trim().split("\\s+");
            if (s.length < 2 || !s[1].startsWith("#")) continue;
            int addr = Integer.parseInt(s[1].substring(1), 16) & 0xFFFF;
            String name = s[0];
            int bank = s.length >= 3 && s[2].matches("B\\d+") ? Integer.parseInt(s[2].substring(1)) : 0;
            boolean hidden = false;
            for (Map.Entry<Integer, int[]> r : range.entrySet())
                if (r.getKey() > bank && addr >= r.getValue()[0] && addr <= r.getValue()[1]) hidden = true;
            int dot = name.lastIndexOf('.');
            String leaf = dot >= 0 ? name.substring(dot + 1) : name;
            int[] fl = lineLabels.get(leaf);
            if (fl != null) {
                addrOfLine.put(lineFiles.get(fl[0]) + ":" + fl[1], addr);
                if (!hidden) linesAtAddr.computeIfAbsent(addr, k -> new ArrayList<>()).add(new SourceLine(lineFiles.get(fl[0]), fl[1]));
                continue;
            }
            if (leaf.startsWith("__BP_")) continue;
            symbols.put(name, addr);
            if (hidden) shadowed.add(name);
            boolean label = s.length < 4 || s[3].equals("L");      // A = EQU, V = variable rasm : pas pour Caprice32
            if (label) labels.add(name);
            if (label) cap.append(String.format("al  $%04x .%s%n", addr, name.toLowerCase(Locale.ROOT)));
        }
        // lignes qui produisent des octets : l'adresse de la ligne de code suivante (meme fichier) est plus grande
        Map<String, TreeMap<Integer, Integer>> byFile = new HashMap<>();
        addrOfLine.forEach((k, a) -> {
            int c = k.lastIndexOf(':');
            byFile.computeIfAbsent(k.substring(0, c), x -> new TreeMap<>()).put(Integer.parseInt(k.substring(c + 1)), a);
        });
        emitting.clear();
        byFile.forEach((f, m) -> {
            Map.Entry<Integer, Integer> prevE = null;
            for (Map.Entry<Integer, Integer> e : m.entrySet()) {
                if (prevE != null && !prevE.getValue().equals(e.getValue())) emitting.add(f + ":" + prevE.getKey());
                prevE = e;
            }
            if (prevE != null) emitting.add(f + ":" + prevE.getKey());
        });
        breakpoints.forEach((file, set) -> set.forEach(ln -> {
            Integer a = addressOf(file, ln);
            if (a != null && !breakpointAddrs.contains(a)) breakpointAddrs.add(a);
        }));
        for (int a : breakpointAddrs) cap.append(String.format("b  $%04x%n", a));
        Files.writeString(p.build().resolve("caprice.sym"), cap.toString());
        if (!breakpointAddrs.isEmpty()) log.accept("  points d'arret : " + breakpointAddrs.stream().map(a -> String.format("&%04X", a)).toList());
    }

    /** Dossier du projet (le profil y relit les lignes de source). */
    public Path projectDir() { return p.dir; }

    /** Une ligne de source (fichier relatif au projet, ligne a partir de 1). */
    public record SourceLine(String file, int line) { }

    /** Adresse d'une ligne, ou de la premiere ligne de code qui la suit (null si aucune). */
    public Integer addressOf(String file, int line) {
        for (int l = line; l < line + 200; l++) {
            Integer a = addrOfLine.get(file + ":" + l);
            if (a != null) return a;
        }
        return null;
    }

    /**
     * Ligne de source correspondant a une adresse : la ligne qui commence exactement a cette
     * adresse (la derniere si plusieurs : les lignes sans octets, comme un appel de VAR, partagent
     * l'adresse de la suivante), sinon la ligne precedente la plus proche (instruction au milieu
     * d'une macro). null si l'adresse est hors du programme (ROM, firmware...).
     */
    public SourceLine lineAt(int addr) {
        Map.Entry<Integer, List<SourceLine>> e = linesAtAddr.floorEntry(addr);
        if (e == null || addr - e.getKey() > 64) return null;
        SourceLine best = null;
        boolean bestEmits = false;
        for (SourceLine s : e.getValue()) {
            boolean emits = emitting.contains(s.file() + ":" + s.line());
            if (best == null || emits && !bestEmits || emits == bestEmits && s.file().equals(best.file()) && s.line() > best.line()) {
                best = s;
                bestEmits = emits;
            }
        }
        return best;
    }
    static void deleteTree(Path d) throws IOException {
        if (!Files.exists(d, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> s = Files.walk(d)) {
            for (Path f : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(f);
        }
    }

    private static void copyFlat(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        try (Stream<Path> s = Files.list(from)) {
            for (Path f : s.toList()) if (Files.isRegularFile(f)) Files.copy(f, to.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
