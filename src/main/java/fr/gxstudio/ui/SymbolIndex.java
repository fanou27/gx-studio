package fr.gxstudio.ui;

import fr.gxstudio.model.Project;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Stream;

/**
 * Symboles connus pour la completion (Ctrl+Espace) et l'aide (survol, Ctrl+Maj+Espace) :
 * API C de la bibliotheque (documentation detaillee : apidoc.txt), routines et constantes
 * assembleur de lib/ (lues dans leurs commentaires d'en-tete), ressources generees
 * (build/gen.h, build/gen.asm), symboles du projet (src/) et mots du langage.
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public final class SymbolIndex {

    public enum Kind {
        FUNCTION("f", "#2b6cb0"), MACRO("#", "#9c4221"), VARIABLE("v", "#2f855a"), CONSTANT("c", "#b7791f"),
        ROUTINE("r", "#2b6cb0"), LABEL("l", "#2f855a"), TYPE("t", "#6b46c1"), RESOURCE("a", "#c05621"),
        INSTRUCTION("z", "#4a5568"), DIRECTIVE("d", "#b83280"), KEYWORD("k", "#4a5568");
        public final String badge, color;
        Kind(String badge, String color) { this.badge = badge; this.color = color; }
    }

    public record Param(String name, String doc) { }

    /**
     * Un symbole. params == null : pas appelable ; liste vide : fonction sans parametre.
     * origin : d'ou il vient (fichier, "bibliotheque"...).
     */
    public record Sym(String name, Kind kind, String sig, String summary, List<Param> params, String ret,
                      List<String> examples, String target, String origin, int rank) {
        public boolean callable() { return params != null; }
        public boolean documented() { return !summary.isEmpty() || sig != null && !sig.equals(name); }
        String detail() {
            if (sig == null) return "";
            if (kind == Kind.FUNCTION) {
                int p = sig.indexOf(name);
                return p > 0 ? sig.substring(0, p).trim() : "";
            }
            if (kind == Kind.ROUTINE && !params.isEmpty()) return params.size() + " reg.";
            return "";
        }
    }

    private SymbolIndex() { }

    // ================================================================ point d'entree

    /** Tous les symboles utiles pour un fichier (texte courant de l'editeur compris). */
    public static List<Sym> forFile(Path file, String buffer, boolean cMode) {
        Path dir = projectDir(file);
        final boolean plus = isPlus(dir);
        List<Sym> out = new ArrayList<>();
        if (cMode) {
            for (Sym s : API) if (s.target.equals("all") || s.target.equals(plus ? "plus" : "cpc")) out.add(s);
        }
        if (dir != null) {
            List<Path> lib = files(dir.resolve("lib"));
            List<Path> src = files(dir.resolve("src"));
            for (Path f : lib) {
                String n = f.toString();
                if (cMode && n.endsWith(".h")) out.addAll(cached(f, plus, p -> parseC(p, lines(p), plus, 1, "bibliothèque")));
                if (!cMode && n.endsWith(".asm")) out.addAll(cached(f, plus, p -> parseAsmLib(p, lines(p))));
                if (!cMode && n.endsWith(".inc")) out.addAll(cached(f, plus, p -> parseAsm(p, lines(p), 2, "bibliothèque", false)));
            }
            Path gen = dir.resolve("build").resolve(cMode ? "gen.h" : "gen.asm");
            if (Files.isRegularFile(gen)) out.addAll(cached(gen, plus, p -> cMode ? parseC(p, lines(p), plus, 3, "ressources (assets)")
                                                                              : parseAsm(p, lines(p), 3, "ressources (assets)", true)));
            if (cMode) {
                Path cfg = dir.resolve("build").resolve("config.h");
                if (Files.isRegularFile(cfg)) out.addAll(cached(cfg, plus, p -> parseC(p, lines(p), plus, 3, "configuration du projet")));
            }
            for (Path f : src) {
                String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
                boolean isC = n.endsWith(".c") || n.endsWith(".h");
                if (isC != cMode) continue;
                if (f.toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize())) continue;
                out.addAll(cached(f, plus, p -> isC ? parseC(p, lines(p), plus, 2, rel(dir, p)) : parseAsm(p, lines(p), 2, rel(dir, p), false)));
            }
        }
        List<String> buf = Arrays.asList(buffer.split("\n", -1));
        String me = dir != null ? rel(dir, file) : file.getFileName().toString();
        out.addAll(cMode ? parseC(file, buf, plus, 2, me) : parseAsm(file, buf, 2, me, false));
        out.addAll(cMode ? C_WORDS : ASM_WORDS);
        return dedupe(out, !cMode);
    }

    /** Symboles qui commencent par (ou contiennent) le debut tape, les meilleurs d'abord. */
    public static List<Sym> complete(List<Sym> all, String prefix, boolean ignoreCase) {
        String lp = prefix.toLowerCase(Locale.ROOT);
        record Scored(Sym s, int score) { }
        List<Scored> res = new ArrayList<>();
        for (Sym s : all) {
            String n = s.name, ln = n.toLowerCase(Locale.ROOT);
            int sc;
            if (prefix.isEmpty()) sc = 0;
            else if (n.startsWith(prefix)) sc = 0;
            else if (ln.startsWith(lp)) sc = 1;
            else if (lp.length() >= 2 && (ln.contains("_" + lp) || ln.contains("." + lp))) sc = 2;
            else if (lp.length() >= 3 && ln.contains(lp)) sc = 3;
            else continue;
            if (n.equals(prefix) && s.kind != Kind.FUNCTION && s.kind != Kind.ROUTINE && s.kind != Kind.MACRO) sc = 4;  // deja ecrit
            res.add(new Scored(s, sc));
        }
        res.sort(Comparator.comparingInt((Scored x) -> x.score).thenComparingInt(x -> x.s.rank)
                .thenComparing(x -> x.s.name.length()).thenComparing(x -> x.s.name.toLowerCase(Locale.ROOT)));
        List<Sym> out = new ArrayList<>();
        for (Scored x : res) { out.add(x.s); if (out.size() >= 400) break; }
        return out;
    }

    /** Symbole de ce nom (le mieux documente). */
    public static Sym find(List<Sym> all, String name, boolean ignoreCase) {
        Sym best = null;
        for (Sym s : all)
            if (ignoreCase ? s.name.equalsIgnoreCase(name) : s.name.equals(name))
                if (best == null || s.rank < best.rank) best = s;
        return best;
    }

    // ================================================================ fichiers

    private static boolean isPlus(Path dir) {
        if (dir == null) return true;
        try { return Project.load(dir).target.plus; } catch (Exception e) { return true; }
    }

    static Path projectDir(Path file) {
        for (Path d = file.toAbsolutePath().getParent(); d != null; d = d.getParent())
            if (Files.isRegularFile(d.resolve(Project.FILE))) return d;
        return null;
    }

    private static List<Path> files(Path d) {
        if (!Files.isDirectory(d)) return List.of();
        try (Stream<Path> s = Files.walk(d, 4)) {
            return s.filter(Files::isRegularFile).filter(p -> p.toString().matches(".*\\.(asm|inc|c|h)$")).sorted().toList();
        } catch (IOException e) { return List.of(); }
    }

    private static List<String> lines(Path p) {
        try { return Files.readAllLines(p, StandardCharsets.ISO_8859_1); } catch (IOException e) { return List.of(); }
    }

    private static String rel(Path dir, Path f) {
        try { return dir.relativize(f.toAbsolutePath()).toString(); } catch (Exception e) { return f.getFileName().toString(); }
    }

    private record Cached(long mtime, List<Sym> syms) { }
    private static final Map<String, Cached> CACHE = new HashMap<>();

    private interface Parser { List<Sym> parse(Path p); }

    private static synchronized List<Sym> cached(Path f, boolean plus, Parser parser) {
        long t;
        try { t = Files.getLastModifiedTime(f).toMillis(); } catch (IOException e) { return List.of(); }
        String key = f.toAbsolutePath() + "|" + plus + "|" + parser.getClass().getName();
        Cached c = CACHE.get(key);
        if (c == null || c.mtime != t) { c = new Cached(t, parser.parse(f)); CACHE.put(key, c); }
        return c.syms;
    }

    private static List<Sym> dedupe(List<Sym> in, boolean ignoreCase) {
        Map<String, Sym> m = new LinkedHashMap<>();
        for (Sym s : in) {
            String k = ignoreCase ? s.name.toLowerCase(Locale.ROOT) : s.name;
            Sym o = m.get(k);
            if (o == null || better(s, o)) m.put(k, s);
        }
        return new ArrayList<>(m.values());
    }

    private static boolean better(Sym a, Sym b) {
        if (a.rank != b.rank) return a.rank < b.rank;
        return a.summary.length() > b.summary.length();
    }

    // ================================================================ documentation de l'API C (apidoc.txt)

    static final List<Sym> API = loadApi();

    private static List<Sym> loadApi() {
        List<Sym> out = new ArrayList<>();
        try (InputStream in = SymbolIndex.class.getResourceAsStream("/fr/gxstudio/apidoc.txt")) {
            if (in == null) return out;
            String name = null, sig = null, target = "all", ret = "";
            StringBuilder sum = new StringBuilder();
            List<Param> params = new ArrayList<>();
            List<String> ex = new ArrayList<>();
            List<String> all = new ArrayList<>(new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList());
            all.add("== ");
            for (String l : all) {
                if (l.startsWith("#")) continue;
                if (l.startsWith("== ")) {
                    if (name != null) out.add(apiSym(name, sig, target, sum.toString().trim(), params, ret, ex));
                    name = l.substring(3).trim();
                    if (name.isEmpty()) name = null;
                    sig = null; target = "all"; ret = ""; sum.setLength(0);
                    params = new ArrayList<>(); ex = new ArrayList<>();
                } else if (l.startsWith("sig:")) sig = l.substring(4).trim();
                else if (l.startsWith("cible:")) target = l.substring(6).trim();
                else if (l.startsWith("@param ")) {
                    String r = l.substring(7).trim();
                    int sp = r.indexOf(' ');
                    params.add(sp < 0 ? new Param(r, "") : new Param(r.substring(0, sp), r.substring(sp + 1).trim()));
                } else if (l.startsWith("@return ")) ret = l.substring(8).trim();
                else if (l.startsWith("@ex ")) ex.add(l.substring(4));
                else if (l.startsWith("@see ")) { /* les liens sont dans le texte */ }
                else if (!l.isBlank()) sum.append(l.trim()).append(' ');
            }
        } catch (IOException ignored) { }
        return out;
    }

    private static Sym apiSym(String name, String sig, String target, String sum, List<Param> params, String ret, List<String> ex) {
        Kind k;
        if (sig == null) sig = name;
        if (sig.matches("#define\\s+\\w+\\(.*")) k = Kind.MACRO;
        else if (sig.startsWith("#define")) k = Kind.CONSTANT;
        else if (sig.startsWith("extern")) k = Kind.VARIABLE;
        else if (sig.startsWith("typedef")) k = Kind.TYPE;
        else k = Kind.FUNCTION;
        List<Param> ps = null;
        if (k == Kind.FUNCTION) ps = params;
        else if (k == Kind.MACRO && sig.matches("#define\\s+\\w+\\(.*")) ps = params;
        return new Sym(name, k, sig, sum, ps, ret, ex, target, "bibliothèque (lib/c/gx.h)", 0);
    }

    // ================================================================ C : prototypes, #define, variables

    private static final Pattern C_FUNC = Pattern.compile(
            "^\\s*((?:static\\s+|inline\\s+|extern\\s+|const\\s+|unsigned\\s+|signed\\s+)*[A-Za-z_]\\w*[\\s*]+)([A-Za-z_]\\w*)\\s*\\(([^()]*)\\)\\s*(GXAPI|__\\w+(?:\\(\\d\\))?)?\\s*([;{]?)\\s*(?:/\\*(.*?)\\*/|//(.*))?\\s*$");
    private static final Pattern C_DEFINE = Pattern.compile("^\\s*#\\s*define\\s+([A-Za-z_]\\w*)(\\([^)]*\\))?\\s*(.*?)\\s*(?:/\\*(.*?)\\*/\\s*)?$");
    private static final Pattern C_VAR = Pattern.compile(
            "^\\s*((?:extern\\s+|static\\s+|const\\s+|volatile\\s+|unsigned\\s+|signed\\s+)*[A-Za-z_]\\w*[\\s*]+)([A-Za-z_]\\w*)\\s*(\\[[^\\]]*\\])?\\s*(=[^;]*)?;\\s*(?:/\\*(.*?)\\*/|//(.*))?\\s*$");
    private static final Pattern C_TYPEDEF = Pattern.compile("^\\s*typedef\\s+.*?\\b([A-Za-z_]\\w*)\\s*;");
    private static final Set<String> NOT_TYPES = Set.of("return", "else", "goto", "case", "do", "while", "if", "for", "switch", "sizeof");

    static List<Sym> parseC(Path f, List<String> ls, boolean plus, int rank, String origin) {
        List<Sym> out = new ArrayList<>();
        Deque<Boolean> cond = new ArrayDeque<>();   // #if ASIC : branche active ?
        boolean active = true;
        int depth = 0;                              // profondeur des accolades : 0 = niveau global
        for (int i = 0; i < ls.size(); i++) {
            String l = ls.get(i);
            String t = l.trim();
            if (t.startsWith("#if") || t.startsWith("#ifdef") || t.startsWith("#ifndef")) {
                boolean v = true;
                if (t.matches("#if\\s+ASIC\\b.*")) v = plus;
                else if (t.matches("#if\\s+!\\s*ASIC\\b.*")) v = !plus;
                cond.push(v);
                active = !cond.contains(false);
                continue;
            }
            if (t.startsWith("#else") && !cond.isEmpty()) { cond.push(!cond.pop()); active = !cond.contains(false); continue; }
            if (t.startsWith("#endif") && !cond.isEmpty()) { cond.pop(); active = !cond.contains(false); continue; }
            if (!active) continue;
            if (depth == 0) {
                Matcher m;
                if ((m = C_DEFINE.matcher(l)).matches()) {
                    String name = m.group(1);
                    if (name.endsWith("_H")) continue;   // garde d'inclusion
                    String args = m.group(2), val = m.group(3), com = m.group(4);
                    String doc = com != null ? com.trim() : comment(ls, i);
                    boolean res = origin.startsWith("ressources");
                    List<Param> ps = null;
                    if (args != null) {
                        ps = new ArrayList<>();
                        for (String a : args.substring(1, args.length() - 1).split(",")) if (!a.isBlank()) ps.add(new Param(a.trim(), ""));
                    }
                    String sig = "#define " + name + (args != null ? args : "") + (val.isEmpty() ? "" : " " + val);
                    Kind k = args != null ? Kind.MACRO : res ? Kind.RESOURCE : Kind.CONSTANT;
                    if (res && doc.isEmpty()) doc = resourceDoc(name);
                    out.add(new Sym(name, k, sig, doc, ps, "", List.of(), "all", origin, rank));
                    continue;
                }
                if ((m = C_FUNC.matcher(l)).matches() && !NOT_TYPES.contains(m.group(1).trim()) && !m.group(1).contains("=")) {
                    String type = m.group(1).trim().replaceAll("\\s+", " "), name = m.group(2), args = m.group(3).trim();
                    String com = m.group(6) != null ? m.group(6) : m.group(7);
                    String doc = com != null ? com.trim() : comment(ls, i);
                    List<Param> ps = new ArrayList<>();
                    if (!args.isEmpty() && !args.equals("void"))
                        for (String a : args.split(",")) {
                            String[] w = a.trim().split("[\\s*]+");
                            String pn = w[w.length - 1];
                            ps.add(new Param(pn, a.trim()));
                        }
                    String sig = type + (type.endsWith("*") ? "" : " ") + name + "(" + (args.isEmpty() ? "void" : args) + ")";
                    out.add(new Sym(name, Kind.FUNCTION, sig, doc, ps, "", List.of(), "all", origin, rank));
                } else if ((m = C_TYPEDEF.matcher(l)).find()) {
                    out.add(new Sym(m.group(1), Kind.TYPE, t.replaceAll("\\s+", " "), comment(ls, i), null, "", List.of(), "all", origin, rank));
                } else if ((m = C_VAR.matcher(l)).matches() && !NOT_TYPES.contains(m.group(1).trim())) {
                    String com = m.group(5) != null ? m.group(5) : m.group(6);
                    String doc = com != null ? com.trim() : comment(ls, i);
                    String sig = m.group(1).trim().replaceAll("\\s+", " ") + " " + m.group(2) + (m.group(3) != null ? m.group(3) : "");
                    boolean res = origin.startsWith("ressources");
                    if (res && doc.isEmpty()) doc = resourceDoc(m.group(2));
                    out.add(new Sym(m.group(2), res ? Kind.RESOURCE : Kind.VARIABLE, sig, doc, null, "", List.of(), "all", origin, rank));
                }
            }
            depth += braces(l);
            if (depth < 0) depth = 0;
        }
        return out;
    }

    /** Variation de la profondeur des accolades sur une ligne (hors chaines et commentaires //). */
    private static int braces(String l) {
        int d = 0;
        boolean str = false;
        char q = 0;
        for (int i = 0; i < l.length(); i++) {
            char c = l.charAt(i);
            if (str) { if (c == '\\') i++; else if (c == q) str = false; continue; }
            if (c == '"' || c == '\'') { str = true; q = c; }
            else if (c == '/' && i + 1 < l.length() && l.charAt(i + 1) == '/') break;
            else if (c == '{') d++;
            else if (c == '}') d--;
        }
        return d;
    }

    /** Commentaire juste au-dessus de la ligne i (C : // ou bloc ; asm : ;). */
    private static String comment(List<String> ls, int i) {
        List<String> parts = new ArrayList<>();
        for (int j = i - 1; j >= 0 && i - j <= 12; j--) {
            String t = ls.get(j).trim();
            if (t.startsWith("//")) parts.add(0, t.substring(2).trim());
            else if (t.startsWith(";")) parts.add(0, t.replaceFirst("^;+", "").trim());
            else if (t.endsWith("*/")) {
                int k = j;
                StringBuilder sb = new StringBuilder();
                while (k >= 0) {
                    String u = ls.get(k).trim();
                    sb.insert(0, u + " ");
                    if (u.startsWith("/*")) break;
                    k--;
                }
                parts.add(0, sb.toString().replaceAll("/\\*+|\\*+/", "").replaceAll("\\s*\\*\\s+", " ").trim());
                j = k;
            } else break;
        }
        String s = String.join(" ", parts).replaceAll("[=\\-]{4,}", "").replaceAll("\\s+", " ").trim();
        return s.length() > 600 ? s.substring(0, 600) + "…" : s;
    }

    private static String resourceDoc(String n) {
        if (n.startsWith("F_")) return "Image de sprite (éditeur de sprites), pour gx_sprite / gx_spr_set.";
        if (n.endsWith("_CH")) return "Canal du son (assets/sons), pour gx_sound / GX_SOUND.";
        if (n.endsWith("_OFS")) return "Position du son dans les listes, pour gx_sound / GX_SOUND.";
        if (n.endsWith("_SPEED")) return "Vitesse (diviseur) du son, pour gx_sound / GX_SOUND.";
        if (n.startsWith("MAP_")) return "Carte (éditeur de décors).";
        if (n.startsWith("TILES_")) return "Jeu de tuiles (éditeur de tuiles).";
        if (n.startsWith("TFLAGS_")) return "Propriétés des tuiles (solide...), pour gx_tile_solid.";
        if (n.startsWith("ANIM_")) return "Animation (éditeur d'animations), pour gx_anim_start.";
        if (n.startsWith("RASTER_")) return "Table raster (éditeur de rasters), pour gx_raster_start.";
        if (n.startsWith("IMG_")) return "Image importée (PNG), pour gx_blit.";
        return "Ressource générée à partir de assets/.";
    }

    // ================================================================ assembleur

    private static final Pattern ASM_HEADER = Pattern.compile("^;+\\s*([A-Za-z_][\\w.]*)\\s*:\\s*(.*)$");
    private static final Pattern ASM_LABEL = Pattern.compile("^([A-Za-z_@.][\\w.@]*)\\s*:");
    private static final Pattern ASM_EQU = Pattern.compile("^\\s*([A-Za-z_@.][\\w.@]*)\\s*:?\\s+(?i:equ)\\s+([^;]*?)\\s*(;\\s*(.*))?$");
    private static final Pattern ASM_MACRO = Pattern.compile("^\\s*(?i:macro)\\s+([A-Za-z_]\\w*)\\s*(.*?)\\s*(;.*)?$");
    private static final Pattern ASM_REG = Pattern.compile("^(A|B|C|D|E|H|L|BC|DE|HL|IX|IY|AF|SP|\\(HL\\))\\s*=\\s*(.+)$");

    /** Routines de la bibliotheque : "; nom : description" juste avant "nom:". */
    static List<Sym> parseAsmLib(Path f, List<String> ls) {
        List<Sym> out = new ArrayList<>();
        String origin = "bibliothèque (" + f.getParent().getFileName() + "/" + f.getFileName() + ")";
        for (int i = 0; i < ls.size(); i++) {
            Matcher h = ASM_HEADER.matcher(ls.get(i));
            if (!h.matches()) continue;
            String name = h.group(1);
            String first = h.group(2).trim();
            List<String> cont = new ArrayList<>();
            int j = i + 1;
            while (j < ls.size() && ls.get(j).startsWith(";") && !ASM_HEADER.matcher(ls.get(j)).matches()) {
                cont.add(ls.get(j).replaceFirst("^;+", "").trim());
                j++;
            }
            // les registres continuent sur les lignes suivantes si la ligne finit par "," / "->"
            // ou si la suivante commence par un registre ("D = ...") ou par "->"
            int k0 = 0;
            while (k0 < cont.size()) {
                String nx = cont.get(k0);
                boolean go = first.endsWith(",") || first.endsWith("->") || first.endsWith("=") || nx.startsWith("->")
                        || first.contains("=") && nx.matches("^(A|B|C|D|E|H|L|BC|DE|HL|IX|IY)\\s*=.*");
                if (!go) break;
                first = first + " " + nx;
                k0++;
            }
            String extra = String.join(" ", cont.subList(k0, cont.size())).trim();
            boolean defined = false;
            for (int k = j; k < Math.min(ls.size(), j + 3); k++)
                if (ls.get(k).matches("^\\Q" + name + "\\E\\s*:.*") || ls.get(k).matches("(?i)^\\s*macro\\s+\\Q" + name + "\\E\\b.*")) defined = true;
            if (!defined) continue;
            Sym r = routine(name, first.replaceAll("\\s+", " ").trim(), origin);
            if (!extra.isEmpty()) {
                String sum = r.summary().isEmpty() ? extra : r.summary() + " " + extra;
                r = new Sym(r.name(), r.kind(), r.sig(), sum.replaceAll("\\s+", " ").trim(), r.params(), r.ret(), r.examples(), r.target(), r.origin(), r.rank());
            }
            out.add(r);
            i = j - 1;
        }
        out.addAll(parseAsm(f, ls, 2, origin, false).stream().filter(s -> s.kind == Kind.CONSTANT || s.kind == Kind.MACRO).toList());
        return out;
    }

    /** "A = sprite, HL = X -> A = image ; remarque" : registres d'entree, de sortie, description. */
    static Sym routine(String name, String text, String origin) {
        String in = text, outRegs = "", note = "";
        int arrow = text.indexOf("->");
        if (arrow >= 0) { in = text.substring(0, arrow).trim(); outRegs = text.substring(arrow + 2).trim(); }
        List<Param> ps = new ArrayList<>();
        List<String> desc = new ArrayList<>();
        String[] parts = splitTop(in);
        for (String p : parts) {
            Matcher m = ASM_REG.matcher(p.trim());
            if (m.matches()) {
                String d = m.group(2).trim();
                Matcher note2 = Pattern.compile("^(.*?)\\s*\\(((?:registres?|tout)[^)]*)\\)$").matcher(d);
                if (note2.matches()) { d = note2.group(1); desc.add(note2.group(2)); }
                ps.add(new Param(m.group(1), d));
            }
            else if (!p.isBlank()) desc.add(p.trim());
        }
        // la sortie peut etre suivie d'une remarque (" ; ..." ou phrase)
        int semi = outRegs.indexOf(';');
        if (semi >= 0) { note = outRegs.substring(semi + 1).trim(); outRegs = outRegs.substring(0, semi).trim(); }
        String summary = String.join(", ", desc);
        if (summary.isEmpty() && ps.isEmpty() && outRegs.isEmpty()) summary = text;
        if (!note.isEmpty()) summary = (summary.isEmpty() ? "" : summary + " ") + note;
        StringBuilder sig = new StringBuilder("call " + name);
        if (!ps.isEmpty()) {
            sig.append("    ; ");
            for (int i = 0; i < ps.size(); i++) sig.append(i > 0 ? ", " : "").append(ps.get(i).name());
        }
        if (!outRegs.isEmpty()) sig.append(ps.isEmpty() ? "    ; " : " ").append("-> ").append(shortOut(outRegs));
        return new Sym(name, Kind.ROUTINE, sig.toString(), summary, ps, outRegs, List.of(), "all", origin, 1);
    }

    private static String shortOut(String s) {
        Matcher m = Pattern.compile("\\b(A|B|C|D|E|H|L|BC|DE|HL|IX|IY|CF|NZ|Z)\\b\\s*=").matcher(s);
        List<String> r = new ArrayList<>();
        while (m.find()) r.add(m.group(1));
        return r.isEmpty() ? (s.length() > 24 ? s.substring(0, 24) + "…" : s) : String.join(", ", r);
    }

    /** Coupe sur les virgules qui ne sont pas entre parentheses. */
    private static String[] splitTop(String s) {
        List<String> r = new ArrayList<>();
        int d = 0, st = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') d++;
            else if (c == ')') d--;
            else if (c == ',' && d == 0) { r.add(s.substring(st, i)); st = i + 1; }
        }
        r.add(s.substring(st));
        return r.toArray(new String[0]);
    }

    /** Etiquettes, constantes (equ) et macros d'un source assembleur. */
    static List<Sym> parseAsm(Path f, List<String> ls, int rank, String origin, boolean resources) {
        List<Sym> out = new ArrayList<>();
        for (int i = 0; i < ls.size(); i++) {
            String l = ls.get(i);
            Matcher m;
            if ((m = ASM_EQU.matcher(l)).matches()) {
                String doc = m.group(4) != null ? m.group(4).trim() : comment(ls, i);
                if (resources && doc.isEmpty()) doc = resourceDoc(m.group(1));
                out.add(new Sym(m.group(1), resources ? Kind.RESOURCE : Kind.CONSTANT, m.group(1) + " equ " + m.group(2), doc,
                        null, "", List.of(), "all", origin, rank));
            } else if ((m = ASM_MACRO.matcher(l)).matches()) {
                List<Param> ps = new ArrayList<>();
                for (String a : m.group(2).split(",")) if (!a.isBlank()) ps.add(new Param(a.trim(), ""));
                out.add(new Sym(m.group(1), Kind.MACRO, "macro " + m.group(1) + (m.group(2).isEmpty() ? "" : " " + m.group(2)),
                        comment(ls, i), ps, "", List.of(), "all", origin, rank));
            } else if ((m = ASM_LABEL.matcher(l)).find()) {
                String name = m.group(1);
                if (name.startsWith(".") || name.startsWith("@")) continue;   // etiquettes locales
                String rest = l.substring(m.end()).trim();
                String doc = comment(ls, i);
                if (doc.isEmpty() && rest.startsWith(";")) doc = rest.substring(1).trim();
                if (resources && doc.isEmpty()) doc = resourceDoc(name);
                out.add(new Sym(name, resources ? Kind.RESOURCE : Kind.LABEL, name + ":", doc, null, "", List.of(), "all", origin, rank));
            }
        }
        return out;
    }

    // ================================================================ mots des langages

    private static final String[][] Z80 = {
        {"adc", "adc a,r", "Addition avec la retenue : A = A + r + CF."},
        {"add", "add a,r  /  add hl,rr", "Addition : A = A + r (8 bits) ou HL = HL + rr (16 bits)."},
        {"and", "and r", "ET logique avec A (masque de bits). CF = 0."},
        {"bit", "bit n,r", "Teste le bit n (0-7) de r : Z = 1 si le bit est à 0."},
        {"call", "call adresse  /  call cc,adresse", "Appelle une routine (empile l'adresse de retour). cc : z, nz, c, nc, p, m, pe, po."},
        {"ccf", "ccf", "Inverse la retenue CF."},
        {"cp", "cp r", "Compare A et r (A - r sans garder le résultat) : Z = 1 si égaux, CF = 1 si A < r."},
        {"cpd", "cpd", "Compare A et (HL), puis HL-1, BC-1."},
        {"cpdr", "cpdr", "Recherche A vers le bas : répète cpd jusqu'à BC = 0 ou A = (HL)."},
        {"cpi", "cpi", "Compare A et (HL), puis HL+1, BC-1."},
        {"cpir", "cpir", "Recherche A dans la mémoire : répète cpi jusqu'à BC = 0 ou A = (HL)."},
        {"cpl", "cpl", "Inverse tous les bits de A (complément à 1)."},
        {"daa", "daa", "Ajuste A en décimal codé binaire (BCD) après une addition ou une soustraction."},
        {"dec", "dec r  /  dec rr", "Décrémente de 1. 8 bits : Z mis à jour ; 16 bits : aucun indicateur."},
        {"di", "di", "Interdit les interruptions."},
        {"djnz", "djnz étiquette", "B = B - 1 et saute si B <> 0 : la boucle classique (au plus 127 octets plus loin)."},
        {"ei", "ei", "Autorise les interruptions (après l'instruction suivante)."},
        {"ex", "ex de,hl  /  ex af,af'  /  ex (sp),hl", "Échange deux registres (ou HL et le sommet de la pile)."},
        {"exx", "exx", "Échange BC, DE, HL avec les registres secondaires BC', DE', HL'."},
        {"halt", "halt", "Attend la prochaine interruption (sur CPC : 300 par seconde)."},
        {"im", "im 0 / 1 / 2", "Mode d'interruption (le CPC utilise im 1 : appel en &0038)."},
        {"in", "in a,(n)  /  in r,(c)", "Lit un port d'entrée (sur CPC : adresse du port dans BC)."},
        {"inc", "inc r  /  inc rr", "Incrémente de 1. 8 bits : Z mis à jour ; 16 bits : aucun indicateur."},
        {"ind", "ind", "Lit le port (C) dans (HL), puis HL-1, B-1."},
        {"indr", "indr", "Répète ind jusqu'à B = 0."},
        {"ini", "ini", "Lit le port (C) dans (HL), puis HL+1, B-1."},
        {"inir", "inir", "Répète ini jusqu'à B = 0."},
        {"jp", "jp adresse  /  jp cc,adresse  /  jp (hl)", "Saut absolu (conditionnel avec cc : z, nz, c, nc, p, m, pe, po)."},
        {"jr", "jr étiquette  /  jr cc,étiquette", "Saut relatif court (-128 à +127 octets) ; cc : z, nz, c, nc."},
        {"ld", "ld destination,source", "Copie une valeur : registre, constante, mémoire (ld a,(hl) ; ld (adresse),a ; ld hl,#C000...)."},
        {"ldd", "ldd", "Copie (HL) vers (DE), puis HL-1, DE-1, BC-1."},
        {"lddr", "lddr", "Copie BC octets de (HL) vers (DE) en descendant (blocs qui se chevauchent vers le haut)."},
        {"ldi", "ldi", "Copie (HL) vers (DE), puis HL+1, DE+1, BC-1 (plus rapide que ldir déroulé)."},
        {"ldir", "ldir", "Copie BC octets de (HL) vers (DE) : copie de bloc mémoire (21 cycles par octet)."},
        {"neg", "neg", "A = -A (complément à 2)."},
        {"nop", "nop", "Ne fait rien (1 µs sur CPC)."},
        {"or", "or r", "OU logique avec A. « or a » remet CF à 0 et teste si A = 0."},
        {"otdr", "otdr", "Répète outd jusqu'à B = 0."},
        {"otir", "otir", "Répète outi jusqu'à B = 0."},
        {"out", "out (n),a  /  out (c),r", "Écrit sur un port de sortie (sur CPC : adresse du port dans BC, ex. Gate Array #7Fxx)."},
        {"outd", "outd", "B-1, puis écrit (HL) sur le port (C), HL-1."},
        {"outi", "outi", "B-1, puis écrit (HL) sur le port (C), HL+1."},
        {"pop", "pop rr", "Dépile un registre 16 bits (BC, DE, HL, AF, IX, IY)."},
        {"push", "push rr", "Empile un registre 16 bits (BC, DE, HL, AF, IX, IY)."},
        {"res", "res n,r", "Met à 0 le bit n (0-7) de r."},
        {"ret", "ret  /  ret cc", "Retour de routine (conditionnel avec cc)."},
        {"reti", "reti", "Retour d'interruption."},
        {"retn", "retn", "Retour d'interruption non masquable."},
        {"rl", "rl r", "Rotation à gauche à travers la retenue (9 bits)."},
        {"rla", "rla", "Rotation de A à gauche à travers la retenue."},
        {"rlc", "rlc r", "Rotation à gauche (le bit 7 revient en bit 0 et dans CF)."},
        {"rlca", "rlca", "Rotation de A à gauche (bit 7 vers bit 0 et CF)."},
        {"rld", "rld", "Rotation de chiffres (4 bits) entre A et (HL), vers la gauche."},
        {"rr", "rr r", "Rotation à droite à travers la retenue (souvent après srl pour décaler 16 bits)."},
        {"rra", "rra", "Rotation de A à droite à travers la retenue."},
        {"rrc", "rrc r", "Rotation à droite (le bit 0 revient en bit 7 et dans CF)."},
        {"rrca", "rrca", "Rotation de A à droite (bit 0 vers bit 7 et CF)."},
        {"rrd", "rrd", "Rotation de chiffres (4 bits) entre A et (HL), vers la droite."},
        {"rst", "rst n", "Appel court d'une des adresses &00, &08... &38."},
        {"sbc", "sbc a,r  /  sbc hl,rr", "Soustraction avec la retenue : A = A - r - CF (ou HL = HL - rr - CF)."},
        {"scf", "scf", "Met la retenue CF à 1."},
        {"set", "set n,r", "Met à 1 le bit n (0-7) de r."},
        {"sla", "sla r", "Décalage à gauche (multiplie par 2), bit 7 dans CF."},
        {"sll", "sll r", "Décalage à gauche avec un 1 entrant (instruction non documentée)."},
        {"sra", "sra r", "Décalage à droite en gardant le signe (divise par 2 un nombre signé)."},
        {"srl", "srl r", "Décalage à droite (divise par 2), bit 0 dans CF."},
        {"sub", "sub r", "Soustraction : A = A - r."},
        {"xor", "xor r", "OU exclusif avec A. « xor a » met A à 0 (plus court que ld a,0)."},
    };

    private static final String[][] RASM = {
        {"org", "org adresse", "Adresse où l'assembleur place le code qui suit."},
        {"equ", "NOM equ valeur", "Définit une constante."},
        {"db", "db 1,2,\"texte\"", "Octets de données (aussi defb)."},
        {"dw", "dw #C000,label", "Mots de 16 bits de données (aussi defw)."},
        {"defb", "defb ...", "Octets de données (comme db)."},
        {"defw", "defw ...", "Mots de données (comme dw)."},
        {"defs", "defs taille,valeur", "Réserve une zone de taille octets (aussi ds)."},
        {"ds", "ds taille,valeur", "Réserve une zone de taille octets."},
        {"incbin", "incbin \"fichier\"", "Inclut un fichier binaire tel quel."},
        {"include", "include \"fichier.asm\"", "Assemble un autre fichier source à cet endroit."},
        {"macro", "macro NOM param1,param2", "Début d'une macro (fin : mend)."},
        {"mend", "mend", "Fin d'une macro."},
        {"endm", "endm", "Fin d'une macro (comme mend)."},
        {"if", "if condition", "Assemblage conditionnel (else, endif). Ex. : if ASIC."},
        {"else", "else", "Partie « sinon » d'un if."},
        {"endif", "endif", "Fin d'un if."},
        {"ifdef", "ifdef NOM", "Assemble si NOM est défini."},
        {"ifndef", "ifndef NOM", "Assemble si NOM n'est pas défini."},
        {"repeat", "repeat n ... rend", "Répète un bloc n fois."},
        {"rend", "rend", "Fin d'un repeat."},
        {"bank", "bank n", "Page de la cartouche (ou banque) où le code est placé."},
        {"buildcpr", "buildcpr", "Produit une cartouche .cpr (CPC+ / GX4000)."},
        {"save", "save \"fichier\",debut,taille", "Écrit une zone mémoire dans un fichier."},
        {"assert", "assert condition", "Arrête l'assemblage si la condition est fausse (vérification de taille...)."},
        {"print", "print expression", "Affiche une valeur pendant l'assemblage (console du studio)."},
        {"align", "align n", "Aligne l'adresse sur un multiple de n (ex. align 256 pour une table)."},
        {"struct", "struct NOM ... endstruct", "Structure de données."},
        {"endstruct", "endstruct", "Fin d'une structure."},
        {"list", "list", "Reprend le listing."},
        {"nolist", "nolist", "Suspend le listing."},
        {"str", "str \"texte\"", "Chaîne dont le dernier caractère a le bit 7 à 1."},
        {"run", "run adresse", "Adresse de démarrage du programme."},
    };

    private static final String[] C_KW = {
        "auto", "break", "case", "char", "const", "continue", "default", "do", "double", "else", "enum", "extern", "float", "for",
        "goto", "if", "inline", "int", "long", "register", "return", "short", "signed", "sizeof", "static", "struct", "switch",
        "typedef", "union", "unsigned", "void", "volatile", "while", "bool", "uint8_t", "int8_t", "uint16_t", "int16_t",
        "uint32_t", "int32_t", "__naked", "__asm", "__endasm", "__at", "__critical", "__interrupt", "#include", "#define",
        "#if", "#ifdef", "#ifndef", "#else", "#endif"};

    private static final Map<String, String> C_TYPES = Map.of(
            "uint8_t", "Entier non signé 8 bits (0-255) : le type le plus rapide sur Z80.",
            "int8_t", "Entier signé 8 bits (-128 à 127).",
            "uint16_t", "Entier non signé 16 bits (0-65535).",
            "int16_t", "Entier signé 16 bits (-32768 à 32767).",
            "uint32_t", "Entier non signé 32 bits (lent sur Z80).",
            "int32_t", "Entier signé 32 bits (lent sur Z80).");

    static final List<Sym> ASM_WORDS = new ArrayList<>(), C_WORDS = new ArrayList<>();
    static {
        for (String[] z : Z80) ASM_WORDS.add(new Sym(z[0], Kind.INSTRUCTION, z[1], z[2], null, "", List.of(), "all", "instruction Z80", 5));
        for (String[] d : RASM) ASM_WORDS.add(new Sym(d[0], Kind.DIRECTIVE, d[1], d[2], null, "", List.of(), "all", "directive de rasm", 6));
        for (String k : C_KW)
            C_WORDS.add(new Sym(k, k.contains("int") ? Kind.TYPE : Kind.KEYWORD, k, C_TYPES.getOrDefault(k, ""), null, "", List.of(), "all", "langage C", 5));
    }
}
