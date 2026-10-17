package fr.gxstudio.build;

import java.util.*;
import java.util.regex.*;

/**
 * Traduit l'assembleur produit par SDCC (syntaxe sdas : "ld a, #0x05",
 * "ld -2 (ix), e", ".area", "00104$:") en source rasm, pour assembler le code C
 * avec le reste du projet en une seule passe (symboles communs, placement par
 * rasm, debogage ligne a ligne dans le studio).
 *
 * Zones :
 *  - _CODE, _HOME : code et constantes (ROM) ;
 *  - _DATA, _BSS : variables (RAM, mises a zero) -> "nom equ c_ram+n" ;
 *  - _INITIALIZED : variables initialisees (RAM) ; leurs valeurs (_INITIALIZER,
 *    en ROM, dans le meme ordre) sont recopiees au demarrage par c_init ;
 *  - _GSINIT : code d'initialisation, execute par c_init.
 * Les etiquettes locales d'un module ("___str_1:") recoivent le nom du module ;
 * les etiquettes temporaires ("00104$") deviennent des locales rasm (".L00104").
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public class SdccToRasm {
    /** Resultat de la traduction d'un module. */
    public static class Module {
        public final String name;
        public final List<String> code = new ArrayList<>(), gsinit = new ArrayList<>(), initializer = new ArrayList<>();
        /** variables en RAM : nom -> taille (ordre conserve) */
        public final LinkedHashMap<String, Integer> initVars = new LinkedHashMap<>(), bssVars = new LinkedHashMap<>();
        public final Set<String> defined = new HashSet<>(), referenced = new HashSet<>();
        Module(String n) { name = n; }
    }

    /** Ligne de C : fichier (tel qu'ecrit par SDCC) et numero. */
    public interface LineMarker { String label(String file, int line); }

    private static final Pattern LINE_MARK = Pattern.compile("^;\\s*([^:;\\s][^:]*\\.[ch]):(\\d+):");
    private static final Pattern LABEL = Pattern.compile("^([A-Za-z_.$][\\w.$]*|\\d+\\$)(::?)\\s*(.*)$");
    private static final Pattern SYMBOL = Pattern.compile("(?<![\\w.$])([A-Za-z_][\\w]*)(?![\\w$])");
    private static final Pattern TEMP = Pattern.compile("(?<![\\w.])(\\d+)\\$");
    private static final Set<String> REGS = new HashSet<>(Arrays.asList(
        "a","b","c","d","e","h","l","i","r","af","bc","de","hl","sp","ix","iy","ixh","ixl","iyh","iyl",
        "af'","nz","z","nc","po","pe","p","m"));

    /**
     * text : sortie de SDCC (ou source .s de sa bibliotheque) ; module : nom unique ;
     * marker : etiquettes de lignes C (null = aucune, bibliotheque).
     */
    public static Module convert(String text, String module, LineMarker marker) {
        Module m = new Module(module);
        String[] lines = text.split("\r?\n");
        // 1re passe : etiquettes globales (::, .globl) et locales du module
        Set<String> globl = new HashSet<>(), localLabels = new HashSet<>();
        for (String raw : lines) {
            String l = stripComment(raw).trim();
            if (l.startsWith(".globl")) for (String g : l.substring(6).split(",")) globl.add(g.trim());
        }
        for (String raw : lines) {
            Matcher lm = LABEL.matcher(stripComment(raw).trim());
            if (!lm.matches()) continue;
            String n = lm.group(1);
            if (n.startsWith(".")) { if (!n.startsWith(".L")) localLabels.add(n); continue; }
            if (n.endsWith("$")) continue;
            if (lm.group(2).equals("::") || globl.contains(n)) m.defined.add(n);
            else localLabels.add(n);
        }
        String prefix = "m_" + module.replaceAll("[^A-Za-z0-9_]", "_") + "_";
        String area = "_CODE";
        String pendingLine = null;
        Set<String> usedLineLabels = new HashSet<>();
        String pendingVar = null;                 // etiquette en attente de son .ds (zones RAM)
        for (String raw : lines) {
            String trimmed = raw.trim();
            if (marker != null) {
                Matcher mk = LINE_MARK.matcher(trimmed);
                if (mk.find()) { String lab = marker.label(mk.group(1), Integer.parseInt(mk.group(2))); if (lab != null) pendingLine = lab; continue; }
            }
            String l = stripComment(raw).trim();
            if (l.isEmpty()) continue;
            // --- directives
            if (l.startsWith(".") && !(LABEL.matcher(l).matches() && localLabels.contains(LABEL.matcher(l).replaceFirst("$1")))) {
                String[] p = l.split("\\s+", 2);
                String d = p[0].toLowerCase(Locale.ROOT), arg = p.length > 1 ? p[1].trim() : "";
                switch (d) {
                    case ".area" -> { area = arg.split("\\s+")[0]; continue; }
                    case ".module", ".optsdcc", ".globl", ".title", ".sbttl", ".list", ".nlist", ".page", ".include", ".z80", ".hd64", ".r800", ".ez80", ".even", ".odd" -> { continue; }
                    default -> { }
                }
                boolean ram = isRam(area);
                if (d.equals(".ds") || d.equals(".blkb") || d.equals(".rmb")) {
                    int n = (int) evalNumber(arg);
                    if (ram) {
                        if (pendingVar == null) pendingVar = prefix + "anon" + (m.bssVars.size() + m.initVars.size());
                        (area.equals("_INITIALIZED") ? m.initVars : m.bssVars).merge(pendingVar, n, Integer::sum);
                        pendingVar = null;
                    } else out(m, area).add("        defs " + n + ",0");
                    continue;
                }
                String data = switch (d) {
                    case ".db", ".byte", ".fcb" -> "        db " + exprList(arg, localLabels, prefix, m);
                    case ".dw", ".word", ".fdb" -> "        dw " + exprList(arg, localLabels, prefix, m);
                    case ".ascii", ".str" -> "        db " + asciiBytes(arg, false);
                    case ".asciz", ".strz" -> "        db " + asciiBytes(arg, true);
                    default -> null;
                };
                if (data == null) { out(m, area).add("; (SDCC) " + l); continue; }
                out(m, area).add(data);
                continue;
            }
            // --- etiquette (eventuellement suivie d'une instruction)
            Matcher lm = LABEL.matcher(l);
            if (lm.matches() && !REGS.contains(lm.group(1).toLowerCase(Locale.ROOT))) {
                String n = lm.group(1);
                String rn = n.endsWith("$") ? ".L" + n.substring(0, n.length() - 1) : localLabels.contains(n) ? local(prefix, n) : n;
                if (isRam(area)) pendingVar = rn;
                else out(m, area).add(rn + ":");
                l = lm.group(3).trim();
                if (l.isEmpty()) continue;
            }
            // --- instruction
            if (isRam(area)) continue;
            String[] p = l.split("\\s+", 2);
            String mn = p[0].toLowerCase(Locale.ROOT);
            List<String> ops = p.length > 1 ? splitOps(p[1]) : new ArrayList<>();
            for (int i = 0; i < ops.size(); i++) ops.set(i, operand(ops.get(i), localLabels, prefix, m));
            if (ops.size() == 2 && ops.get(0).equalsIgnoreCase("a") && Set.of("sub", "and", "xor", "or", "cp").contains(mn))
                ops.remove(0);
            String ins = "        " + mn + (ops.isEmpty() ? "" : " " + String.join(",", ops));
            List<String> o = out(m, area);
            if (pendingLine != null && area.equals("_CODE") && usedLineLabels.add(pendingLine))
                ins = "." + pendingLine + " : " + ins.trim();      // etiquette de ligne C (debogage)
            pendingLine = null;
            o.add(ins);
        }
        return m;
    }

    /** nom rasm d'une etiquette locale au module (".boucle" de sdas -> m_module_d_boucle) */
    private static String local(String prefix, String n) {
        return n.startsWith(".") ? prefix + "d_" + n.substring(1) : prefix + n;
    }

    private static boolean isRam(String area) {
        return area.equals("_DATA") || area.equals("_BSS") || area.equals("_INITIALIZED") || area.equals("_DABS") || area.equals("_HEAP");
    }

    private static List<String> out(Module m, String area) {
        return switch (area) {
            case "_GSINIT", "_GSFINAL" -> m.gsinit;
            case "_INITIALIZER" -> m.initializer;
            default -> m.code;
        };
    }

    static String stripComment(String l) {
        boolean q = false;
        for (int i = 0; i < l.length(); i++) {
            char c = l.charAt(i);
            if (c == '"') q = !q;
            else if (c == ';' && !q) return l.substring(0, i);
        }
        return l;
    }

    private static List<String> splitOps(String s) {
        List<String> r = new ArrayList<>();
        int depth = 0, st = 0;
        boolean q = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') q = !q;
            if (q) continue;
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) { r.add(s.substring(st, i).trim()); st = i + 1; }
        }
        r.add(s.substring(st).trim());
        return r;
    }

    private static final Pattern INDEX = Pattern.compile("^(.+?)\\s*\\(\\s*(ix|iy)\\s*\\)$", Pattern.CASE_INSENSITIVE);

    private static String operand(String op, Set<String> locals, String prefix, Module m) {
        String o = op.trim();
        if (REGS.contains(o.toLowerCase(Locale.ROOT)) || o.equalsIgnoreCase("af'")) return o;
        Matcher ix = INDEX.matcher(o);
        if (ix.matches() && !ix.group(1).trim().startsWith("(")) {
            String off = expr(ix.group(1).trim(), locals, prefix, m);
            return "(" + ix.group(2).toLowerCase(Locale.ROOT) + (off.startsWith("-") ? off : "+" + off) + ")";
        }
        if (o.startsWith("(") && o.endsWith(")")) {
            String in = o.substring(1, o.length() - 1).trim();
            if (REGS.contains(in.toLowerCase(Locale.ROOT))) return "(" + in + ")";
            return "(" + expr(in, locals, prefix, m) + ")";
        }
        if (o.startsWith("#")) {
            String e = expr(o.substring(1).trim(), locals, prefix, m);
            return e.startsWith("(") ? "0+" + e : e;
        }
        return expr(o, locals, prefix, m);
    }

    /** Expression sdas -> rasm : #, <(..) / >(..), symboles locaux, temporaires. */
    static String expr(String e, Set<String> locals, String prefix, Module m) {
        String s = e.replace("#", "");
        s = lohi(s);
        s = TEMP.matcher(s).replaceAll(".L$1");
        Matcher dm = Pattern.compile("(?<![\\w])(\\.[A-Za-z_]\\w*)").matcher(s);
        StringBuilder db = new StringBuilder();
        while (dm.find()) dm.appendReplacement(db, Matcher.quoteReplacement(locals.contains(dm.group(1)) ? local(prefix, dm.group(1)) : dm.group(1)));
        dm.appendTail(db);
        s = db.toString();
        Matcher sm = SYMBOL.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (sm.find()) {
            String n = sm.group(1);
            String rep = n;
            if (n.matches("0[xX][0-9A-Fa-f]+") || n.matches("[0-9].*")) rep = n;
            else if (locals.contains(n)) rep = local(prefix, n);
            else if (!REGS.contains(n.toLowerCase(Locale.ROOT)) && !n.matches("(?i)L\\d+")) m.referenced.add(n);
            sm.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        sm.appendTail(sb);
        return sb.toString().trim();
    }

    /** "<(x)" -> "((x)&255)", ">(x)" -> "((x)>>8)" (aussi sans parentheses). */
    private static String lohi(String s) {
        StringBuilder r = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean unary = (c == '<' || c == '>') && (i + 1 < s.length()) && s.charAt(i + 1) != c && s.charAt(i + 1) != '='
                    && (i == 0 || "(+-*/&|^, ".indexOf(s.charAt(i - 1)) >= 0) && (i == 0 || s.charAt(i - 1) != c);
            if (!unary) { r.append(c); continue; }
            int j = i + 1;
            while (j < s.length() && s.charAt(j) == ' ') j++;
            String operand;
            if (j < s.length() && s.charAt(j) == '(') {
                int d = 0, k = j;
                for (; k < s.length(); k++) { if (s.charAt(k) == '(') d++; else if (s.charAt(k) == ')' && --d == 0) break; }
                operand = s.substring(j, Math.min(k + 1, s.length()));
                i = k;
            } else {
                int k = j;
                while (k < s.length() && (Character.isLetterOrDigit(s.charAt(k)) || s.charAt(k) == '_' || s.charAt(k) == '$')) k++;
                operand = s.substring(j, k);
                i = k - 1;
            }
            r.append(c == '<' ? "((" + operand + ")&255)" : "((" + operand + ")>>8)");
        }
        return r.toString();
    }

    private static String exprList(String arg, Set<String> locals, String prefix, Module m) {
        List<String> r = new ArrayList<>();
        for (String x : splitOps(arg)) r.add(expr(x, locals, prefix, m));
        return String.join(",", r);
    }

    /** .ascii "..." -> liste d'octets (rasm n'accepte pas les \ dans les chaines). */
    private static String asciiBytes(String arg, boolean zero) {
        String a = arg.trim();
        char delim = a.charAt(0);
        int end = a.lastIndexOf(delim);
        String s = a.substring(1, end > 0 ? end : a.length());
        List<String> r = new ArrayList<>();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n' -> r.add("10"); case 'r' -> r.add("13"); case 't' -> r.add("9");
                    case '0' -> r.add("0"); case '\\' -> r.add("92"); case '"' -> r.add("34"); case '\'' -> r.add("39");
                    case 'x' -> { int k = i + 1; while (k < s.length() && k < i + 3 && Character.digit(s.charAt(k), 16) >= 0) k++;
                                  r.add(String.valueOf(Integer.parseInt(s.substring(i + 1, k), 16))); i = k - 1; }
                    default -> {
                        if (Character.isDigit(n)) { int k = i; while (k < s.length() && k < i + 3 && s.charAt(k) >= '0' && s.charAt(k) <= '7') k++;
                                                    r.add(String.valueOf(Integer.parseInt(s.substring(i, k), 8))); i = k - 1; }
                        else r.add(String.valueOf((int) n));
                    }
                }
            } else r.add(String.valueOf((int) c & 0xFF));
        }
        if (zero) r.add("0");
        if (r.isEmpty()) return "0";
        return String.join(",", r);
    }

    static long evalNumber(String s) {
        String t = s.replace("#", "").trim();
        if (t.toLowerCase(Locale.ROOT).startsWith("0x")) return Long.parseLong(t.substring(2), 16);
        return Long.parseLong(t);
    }
}
