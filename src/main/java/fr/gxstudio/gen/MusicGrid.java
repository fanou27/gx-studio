package fr.gxstudio.gen;

import java.util.*;

/**
 * Vue "grille" (tracker) d'un fichier son : 3 voies, une case par pas.
 * Une case vaut "" (rien : la note precedente continue), "-" (silence),
 * ou une note "C#5" eventuellement suivie d'un volume "!a".
 * Le texte produit reste au format .snd (un mot par pas, 16 pas par ligne).
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public class MusicGrid {
    public int speed = 51, step = 36, channel = 0;
    public boolean loop = false;
    public final int[] volume = {12, 11, 10}, release = {8, 7, 0}, noise = {-1, -1, -1};
    public final boolean[] used = {true, true, false};
    public List<String[]> rows = new ArrayList<>();       // rows.get(i)[voix]
    /** Lignes non reconnues (commentaires d'en-tete...) conservees telles quelles. */
    public final List<String> extra = new ArrayList<>();

    public MusicGrid() { resize(64); }

    public int size() { return rows.size(); }

    public void resize(int n) {
        while (rows.size() < n) rows.add(new String[]{"", "", ""});
        while (rows.size() > n) rows.remove(rows.size() - 1);
    }

    /** Lecture d'un texte .snd ; null si le fichier n'est pas une musique en grille (bruitage, durees /n...). */
    public static MusicGrid parse(String text) {
        MusicGrid g = new MusicGrid();
        g.rows.clear();
        Arrays.fill(g.used, false);
        List<List<String>> v = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        boolean anyVoice = false;
        for (String line : text.split("\n")) {
            String l = line.replaceAll(";.*", "").strip();
            if (l.isEmpty()) { if (line.strip().startsWith(";") && !anyVoice) g.extra.add(line.strip()); continue; }
            if (l.matches("(?i)[ABC]\\s*:.*")) {
                int voice = Character.toUpperCase(l.charAt(0)) - 'A';
                g.used[voice] = true;
                anyVoice = true;
                for (String tok : l.substring(l.indexOf(':') + 1).trim().split("\\s+")) {
                    if (tok.isEmpty()) continue;
                    if (tok.contains("/")) return null;
                    int mult = 1;
                    String t = tok;
                    int star = t.indexOf('*');
                    if (star >= 0) {
                        String rest = t.substring(star + 1), vol = "";
                        if (rest.contains("!")) { vol = rest.substring(rest.indexOf('!')); rest = rest.substring(0, rest.indexOf('!')); }
                        mult = Integer.parseInt(rest);
                        t = t.substring(0, star) + vol;
                    }
                    v.get(voice).add(t.equals(".") ? "" : t);
                    for (int k = 1; k < mult; k++) v.get(voice).add("");
                }
                continue;
            }
            String[] p = l.split("\\s+");
            try {
                switch (p[0].toLowerCase(Locale.ROOT)) {
                    case "vitesse", "speed" -> g.speed = Integer.parseInt(p[1]);
                    case "duree", "durée", "length" -> g.step = Integer.parseInt(p[1]);
                    case "canal", "channel" -> g.channel = Integer.parseInt(p[1]);
                    case "boucle" -> g.loop = true;
                    case "volume" -> g.volume[voice(p[1])] = Integer.parseInt(p[2]);
                    case "relache", "relâche", "release" -> g.release[voice(p[1])] = Integer.parseInt(p[2]);
                    case "bruit" -> g.noise[voice(p[1])] = Integer.parseInt(p[2]);
                    default -> { return null; }                  // instructions directes : bruitage
                }
            } catch (RuntimeException e) { return null; }
        }
        if (!anyVoice) return null;
        int n = Math.max(v.get(0).size(), Math.max(v.get(1).size(), v.get(2).size()));
        for (int i = 0; i < n; i++) {
            String[] r = new String[3];
            for (int k = 0; k < 3; k++) r[k] = i < v.get(k).size() ? v.get(k).get(i) : "";
            g.rows.add(r);
        }
        if (g.rows.isEmpty()) g.resize(16);
        return g;
    }

    private static int voice(String s) { return Character.toUpperCase(s.charAt(0)) - 'A'; }

    public String toText() {
        StringBuilder sb = new StringBuilder();
        for (String e : extra) sb.append(e).append('\n');
        if (extra.isEmpty()) sb.append("; musique (GX Studio : editeur en grille)\n");
        sb.append("canal ").append(channel).append('\n');
        sb.append("vitesse ").append(speed).append("          ; 1 tic = vitesse + 1 lignes\n");
        sb.append("duree ").append(step).append("            ; tics par pas de la grille\n");
        if (loop) sb.append("boucle\n");
        for (int k = 0; k < 3; k++) {
            if (!used[k]) continue;
            char c = (char) ('A' + k);
            sb.append("volume ").append(c).append(' ').append(volume[k]).append('\n');
            sb.append("relache ").append(c).append(' ').append(release[k]).append('\n');
            if (noise[k] >= 0) sb.append("bruit ").append(c).append(' ').append(noise[k]).append('\n');
        }
        for (int k = 0; k < 3; k++) {
            if (!used[k]) continue;
            char c = (char) ('A' + k);
            for (int i = 0; i < rows.size(); i += 16) {
                sb.append(c).append(':');
                for (int j = i; j < Math.min(rows.size(), i + 16); j++) {
                    String t = rows.get(j)[k];
                    sb.append(' ').append(t.isEmpty() ? "." : t);
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** Duree d'un pas en secondes. */
    public double stepSeconds() { return step * (speed + 1) * 64e-6; }

    /** Tempo en noires par minute, si un pas = une double croche. */
    public double bpm() { return 60.0 / (stepSeconds() * 4); }

    /** Choisit vitesse et duree pour un pas de s secondes (precision ~2 %). */
    public void setStepSeconds(double s) {
        double lines = s / 64e-6;
        speed = (int) Math.max(0, Math.min(255, Math.round(lines / 48) - 1));
        step = (int) Math.max(1, Math.round(lines / (speed + 1)));
    }

    // ------------------------------------------------------------------ notes
    public static final String[] NAMES = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};

    public static String noteName(int midi) { return NAMES[midi % 12] + (midi / 12 - 1); }

    /** "C#5!a" -> numero MIDI (ou -1). */
    public static int midiOf(String cell) {
        if (cell == null || cell.isEmpty() || cell.startsWith("-")) return -1;
        String n = cell.contains("!") ? cell.substring(0, cell.indexOf('!')) : cell;
        try {
            int o = n.charAt(n.length() - 1) - '0';
            int i = Arrays.asList(NAMES).indexOf(n.substring(0, n.length() - 1).toUpperCase(Locale.ROOT));
            return i < 0 ? -1 : 12 * (o + 1) + i;
        } catch (Exception e) { return -1; }
    }

    public static String volOf(String cell) { return cell != null && cell.contains("!") ? cell.substring(cell.indexOf('!') + 1) : ""; }

    public static String make(int midi, int vol) {
        midi = Math.max(12, Math.min(107, midi));
        return noteName(midi) + (vol >= 0 ? "!" + Integer.toHexString(vol & 15) : "");
    }
}
