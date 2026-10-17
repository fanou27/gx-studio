package fr.gxstudio.gen;

import java.util.*;

/**
 * Compile un fichier son (.snd) en liste d'instructions DMA de l'ASIC
 * (jouee par le DMA sur CPC+/GX4000, ou par le lecteur logiciel sur CPC).
 *
 * <pre>
 * ; musique : une ligne par voie
 * vitesse 51        ; diviseur : 1 tic = (vitesse+1) lignes (51 => 1/6 d'image)
 * duree 42          ; duree d'une note en tics
 * boucle            ; rejouer sans fin
 * volume A 12       ; volume de la voie A (0-15)
 * relache A 8       ; volume apres les 2/3 de la note (0 = aucun)
 * bruit C 10        ; la voie C joue du bruit (periode 10)
 * A: A4 C5 E5 A5*2 - G5/2 .
 *    (note+octave, # pour diese, *n ou /n sur la duree, '-' silence, '.' prolonge,
 *     !v volume de la note en hexadecimal : C5!a)
 *
 * ; bruitage : instructions directes
 * TONE B 50 / VOL B 13 / NOISE 8 / LOAD r v / PAUSE n / REPEAT n / LOOP / STOP
 * </pre>
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public class SoundCompiler {
    public static final int STOP = 0x4020, LOOP = 0x4001;
    public static int load(int r, int v) { return (r << 8) | (v & 0xFF); }
    public static int pause(int n) { return 0x1000 | Math.max(1, Math.min(4095, n)); }
    public static int repeat(int n) { return 0x2000 | (n & 0xFFF); }

    public static class Result {
        public final List<Integer> words = new ArrayList<>();
        public int speed = 51, channel = 0;
        public final List<String> errors = new ArrayList<>();
    }

    private static final String[] NOTES = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};

    public static int period(String note) {
        String n = note.toUpperCase(Locale.ROOT);
        int o = n.charAt(n.length() - 1) - '0';
        String nm = n.substring(0, n.length() - 1).replace("DO", "C");
        int idx = Arrays.asList(NOTES).indexOf(nm);
        if (idx < 0 || o < 0 || o > 9) throw new IllegalArgumentException("note inconnue : " + note);
        int midi = 12 * (o + 1) + idx;
        double f = 440 * Math.pow(2, (midi - 69) / 12.0);
        return (int) Math.round(62500 / f) & 0xFFF;
    }

    public static Result compile(String text) {
        Result res = new Result();
        int dur = 42;
        boolean loop = false;
        int[] vol = {12, 11, 10}, rel = {8, 7, 0}, noise = {-1, -1, -1};
        Map<Integer, List<String>> voices = new TreeMap<>();
        List<Integer> raw = new ArrayList<>();
        int lineNo = 0;
        for (String line : text.split("\n")) {
            lineNo++;
            String l = line.replaceAll(";.*", "").strip();
            if (l.isEmpty() || l.startsWith("#") && !l.contains(":")) continue;
            try {
                if (l.matches("(?i)[ABC]\\s*:.*")) {
                    int v = Character.toUpperCase(l.charAt(0)) - 'A';
                    voices.computeIfAbsent(v, k -> new ArrayList<>())
                          .addAll(Arrays.asList(l.substring(l.indexOf(':') + 1).trim().split("\\s+")));
                    continue;
                }
                String[] p = l.split("\\s+");
                String k = p[0].toLowerCase(Locale.ROOT);
                switch (k) {
                    case "vitesse", "speed" -> res.speed = Integer.parseInt(p[1]) & 255;
                    case "canal", "channel" -> res.channel = Integer.parseInt(p[1]) % 3;
                    case "duree", "durée", "length" -> dur = Integer.parseInt(p[1]);
                    case "boucle" -> loop = true;
                    case "volume" -> vol[voice(p[1])] = Integer.parseInt(p[2]);
                    case "relache", "relâche", "release" -> rel[voice(p[1])] = Integer.parseInt(p[2]);
                    case "bruit" -> noise[voice(p[1])] = Integer.parseInt(p[2]);
                    case "tone" -> { int v = voice(p[1]), per = Integer.parseInt(p[2]);
                                     raw.add(load(v * 2, per & 255)); raw.add(load(v * 2 + 1, per >> 8)); }
                    case "vol" -> raw.add(load(8 + voice(p[1]), Integer.parseInt(p[2])));
                    case "noise" -> raw.add(load(6, Integer.parseInt(p[1])));
                    case "load" -> raw.add(load(Integer.parseInt(p[1]), Integer.parseInt(p[2])));
                    case "pause" -> raw.add(pause(Integer.parseInt(p[1])));
                    case "repeat" -> raw.add(repeat(Integer.parseInt(p[1])));
                    case "loop" -> raw.add(LOOP);
                    case "stop" -> raw.add(STOP);
                    default -> res.errors.add("ligne " + lineNo + " : mot inconnu '" + p[0] + "'");
                }
            } catch (RuntimeException e) {
                res.errors.add("ligne " + lineNo + " : " + e.getMessage());
            }
        }
        if (voices.isEmpty()) {
            res.words.addAll(raw);
        } else {
            // evenements : temps (tics) -> instructions
            TreeMap<Integer, List<Integer>> ev = new TreeMap<>();
            int total = 0;
            for (var e : voices.entrySet()) {
                int v = e.getKey(), t = 0;
                for (String tok : e.getValue()) {
                    if (tok.isEmpty()) continue;
                    int d = dur;
                    String n = tok;
                    if (n.contains("*")) { d = dur * Integer.parseInt(n.substring(n.indexOf('*') + 1)); n = n.substring(0, n.indexOf('*')); }
                    else if (n.contains("/")) { d = Math.max(1, dur / Integer.parseInt(n.substring(n.indexOf('/') + 1))); n = n.substring(0, n.indexOf('/')); }
                    List<Integer> at = ev.computeIfAbsent(t, k -> new ArrayList<>());
                    int nv = vol[v];
                    if (n.contains("!")) {                       // volume de la note : C5!a
                        try { nv = Integer.parseInt(n.substring(n.indexOf('!') + 1), 16) & 15; }
                        catch (NumberFormatException ex) { res.errors.add("volume invalide : " + n); }
                        n = n.substring(0, n.indexOf('!'));
                    }
                    if (n.equals("-")) at.add(load(8 + v, 0));
                    else if (!n.equals(".")) {
                        try {
                            if (noise[v] >= 0) at.add(load(6, noise[v]));
                            else { int per = period(n); at.add(load(v * 2, per & 255)); at.add(load(v * 2 + 1, per >> 8)); }
                            at.add(load(8 + v, nv));
                            int rv = rel[v] > 0 ? Math.min(rel[v], Math.max(0, nv - (vol[v] - rel[v]))) : 0;
                            if (rel[v] > 0 && d >= 3) ev.computeIfAbsent(t + d * 2 / 3, k -> new ArrayList<>()).add(load(8 + v, rv));
                        } catch (IllegalArgumentException ex) { res.errors.add(ex.getMessage()); }
                    }
                    t += d;
                }
                total = Math.max(total, t);
            }
            if (loop) res.words.add(repeat(4095));
            List<Integer> times = new ArrayList<>(ev.keySet());
            final int end = total;
            times.removeIf(x -> x >= end);
            if (times.isEmpty() || times.get(0) != 0) times.add(0, 0);
            int[] last = new int[16];
            Arrays.fill(last, -1);
            for (int i = 0; i < times.size(); i++) {
                int t = times.get(i);
                for (int w : ev.getOrDefault(t, List.of())) {      // registre deja a cette valeur : inutile
                    int r = (w >> 8) & 15, val = w & 255;
                    if (last[r] == val) continue;
                    last[r] = val;
                    res.words.add(w);
                }
                int next = i + 1 < times.size() ? times.get(i + 1) : total;
                int gap = next - t;
                while (gap > 0) { int g = Math.min(gap, 4095); res.words.add(pause(g)); gap -= g; }
            }
            if (loop) res.words.add(LOOP);
            else for (int v : voices.keySet()) res.words.add(load(8 + v, 0));
        }
        res.words.add(STOP); res.words.add(STOP); res.words.add(STOP);
        return res;
    }

    private static int voice(String s) {
        int v = Character.toUpperCase(s.charAt(0)) - 'A';
        if (v < 0 || v > 2) throw new IllegalArgumentException("voie A, B ou C attendue : " + s);
        return v;
    }
}
