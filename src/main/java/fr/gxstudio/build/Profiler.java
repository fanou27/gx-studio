package fr.gxstudio.build;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Profil d'execution : le Caprice32 corrige compte les cycles (T-states) executes a chaque adresse
 * (commande "prof" du serveur de debogage) ; on les regroupe par routine / fonction C et par
 * ligne de source a l'aide des symboles de la derniere compilation.
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public class Profiler {
    /** Rapport texte (pour la console). counts = fichier ecrit par "prof save" (65536 entiers). */
    public static String report(Path counts, Builder map, int seconds) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(Files.readAllBytes(counts)).order(ByteOrder.LITTLE_ENDIAN);
        long[] c = new long[65536];
        long total = 0;
        for (int a = 0; a < 65536 && bb.remaining() >= 4; a++) { c[a] = bb.getInt() & 0xFFFFFFFFL; total += c[a]; }
        if (total == 0) return "== Profil : aucune instruction comptee (programme arrete ?)\n";
        waitCache.clear();
        // routines : etiquettes "normales" triees par adresse
        TreeMap<Integer, String> funcs = new TreeMap<>();
        for (Map.Entry<String, Integer> e : map.symbols.entrySet()) {
            String n = e.getKey();
            if (!map.labels.contains(n) || n.startsWith(".") || n.contains(".") || map.shadowed.contains(n)) continue;
            funcs.merge(e.getValue(), n, (x, y) -> x.length() <= y.length() ? x : y);
        }
        Map<String, Long> byFunc = new HashMap<>(), byLine = new HashMap<>(), byFile = new HashMap<>();
        Map<String, String> funcWhere = new HashMap<>();
        long idle = 0, outside = 0;
        for (int a = 0; a < 65536; a++) {
            if (c[a] == 0) continue;
            Map.Entry<Integer, String> f = funcs.floorEntry(a);
            Builder.SourceLine l = map.lineAt(a);
            if (f == null || l == null) { outside += c[a]; continue; }
            String fn = cName(f.getValue());
            boolean rt = f.getValue().toUpperCase(Locale.ROOT).startsWith("M_RT_");   // bibliotheque de SDCC
            byFunc.merge(fn, c[a], Long::sum);
            byFile.merge(rt ? "(bibliotheque de SDCC)" : l.file(), c[a], Long::sum);
            if (!rt) byLine.merge(l.file() + ":" + l.line(), c[a], Long::sum);
            else funcWhere.putIfAbsent(fn, "(bibliotheque de SDCC : division, multiplication...)");
            Builder.SourceLine fl = map.lineAt(f.getKey());
            if (fl != null) funcWhere.putIfAbsent(fn, fl.file() + ":" + fl.line());
            if (fn.equals("wait_frame") || fn.equals("wait_frames") || isWaitLoop(map, l)) idle += c[a];
        }
        StringBuilder sb = new StringBuilder();
        final long tot = total;
        sb.append(String.format("== Profil sur %d s (%d images) : %,d cycles du Z80%n", seconds, seconds * 50, total));
        long perFrame = (total - idle) / (seconds * 50L) / 4;           // 4 cycles = 1 microseconde
        sb.append(String.format("   temps libre (attente : wait_frame, boucles while (...) ; du C) : %.0f %%%n", 100.0 * idle / total));
        sb.append(String.format("   travail par image : %,d us en moyenne (une image dure 19 968 us)%n", perFrame));
        if (outside > 0) sb.append(String.format("   hors du programme (ROM, firmware) : %.1f %%%n", 100.0 * outside / total));
        sb.append("   -- par fichier\n");
        top(byFile, 10).forEach(e -> sb.append(String.format("   %5.1f %%  %s%n", 100.0 * e.getValue() / tot, e.getKey())));
        sb.append("   -- routines (etiquettes) et fonctions qui prennent le plus de temps\n");
        top(byFunc, 15).forEach(e -> sb.append(String.format("   %5.1f %%  %-24s %s%n", 100.0 * e.getValue() / tot, e.getKey(),
                funcWhere.getOrDefault(e.getKey(), ""))));
        sb.append("   -- lignes qui prennent le plus de temps\n");
        top(byLine, 15).forEach(e -> sb.append(String.format("   %5.1f %%  %s%n", 100.0 * e.getValue() / tot, e.getKey())));
        return sb.toString();
    }

    /** Ligne C d'attente active : "while (condition) ;" (corps vide), ex. while (tick >= 0) ; */
    private static final Map<String, Boolean> waitCache = new HashMap<>();
    private static boolean isWaitLoop(Builder map, Builder.SourceLine l) {
        if (!l.file().endsWith(".c")) return false;
        return waitCache.computeIfAbsent(map.projectDir() + "/" + l.file() + ":" + l.line(), k -> {
            try {
                List<String> src = Files.readAllLines(map.projectDir().resolve(l.file()));
                String t = l.line() - 1 < src.size() ? src.get(l.line() - 1) : "";
                return t.replaceAll("/\\*.*?\\*/|//.*", "").matches("\\s*while\\s*\\(.*\\)\\s*;\\s*");
            } catch (IOException e) { return false; }
        });
    }

    private static List<Map.Entry<String, Long>> top(Map<String, Long> m, int n) {
        List<Map.Entry<String, Long>> l = new ArrayList<>(m.entrySet());
        l.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        return l.subList(0, Math.min(n, l.size()));
    }

    /** Noms du C : M_SRC_MAIN__HIT -> hit, _MAIN -> main ; etiquettes en minuscules. */
    static String cName(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)^m_rt_(.+?)(_d_.*)?$").matcher(s);
        if (m.matches()) return m.group(1).toLowerCase(Locale.ROOT) + " (SDCC)";
        String r = s.replaceFirst("^(?i)m_.*?__", "");
        if (r.length() == s.length() && s.startsWith("_") && s.length() > 1 && !s.startsWith("__")) r = s.substring(1);
        return r.toLowerCase(Locale.ROOT);
    }
}
