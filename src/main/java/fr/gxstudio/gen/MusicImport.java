package fr.gxstudio.gen;

import javax.sound.midi.*;
import javax.sound.sampled.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * Import de musiques externes vers la grille 3 voies du PSG :
 *  - MIDI : notes exactes, reduction a 3 voies (A = la plus aigue, B = la plus grave,
 *    C = une voix du milieu, ou la batterie en bruit) ;
 *  - MP3 / OGG / FLAC (via ffmpeg) et WAV : analyse du spectre pas a pas
 *    (somme des harmoniques) pour retrouver une melodie, une basse et une voix
 *    intermediaire. C'est une approximation "chiptune" du morceau.
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public class MusicImport {
    public static class Options {
        public double bpm = 0;               // 0 = celui du fichier (MIDI) ou detection (audio)
        public int stepsPerBeat = 4;         // 4 = double croche
        public double start = 0, maxSeconds = 45;
        public boolean drums = true;         // batterie -> bruit sur la voie C
        public boolean release = true;       // articulation (volume reduit aux 2/3 de la note)
        public boolean loop = true;
        public int transpose = 0;
        public double threshold = 0.12;      // audio : sensibilite (part du maximum)
    }

    // =================================================================== MIDI
    private record Note(double start, double end, int pitch, int vel, int ch) { }

    public static MusicGrid fromMidi(File f, Options o, Consumer<String> log) throws Exception {
        Sequence seq = MidiSystem.getSequence(f);
        int ppq = seq.getResolution();
        TreeMap<Long, Integer> tempo = new TreeMap<>();
        tempo.put(0L, 500000);
        List<Note> notes = new ArrayList<>();
        Map<Integer, long[]> open = new HashMap<>();
        List<MidiEvent> all = new ArrayList<>();
        for (Track t : seq.getTracks()) for (int i = 0; i < t.size(); i++) all.add(t.get(i));
        all.sort(Comparator.comparingLong(MidiEvent::getTick));
        for (MidiEvent e : all)
            if (e.getMessage() instanceof MetaMessage m && m.getType() == 0x51) {
                byte[] d = m.getData();
                tempo.put(e.getTick(), (d[0] & 255) << 16 | (d[1] & 255) << 8 | (d[2] & 255));
            }
        if (seq.getDivisionType() != Sequence.PPQ) throw new IOException("fichier MIDI en SMPTE non pris en charge");
        for (MidiEvent e : all) {
            if (!(e.getMessage() instanceof ShortMessage sm)) continue;
            int cmd = sm.getCommand(), ch = sm.getChannel(), key = ch << 8 | sm.getData1();
            boolean noteOn = cmd == ShortMessage.NOTE_ON && sm.getData2() > 0;
            boolean noteOff = cmd == ShortMessage.NOTE_OFF || (cmd == ShortMessage.NOTE_ON && sm.getData2() == 0);
            if (noteOn) {
                long[] prev = open.remove(key);
                if (prev != null) notes.add(new Note(sec(prev[0], tempo, ppq), sec(e.getTick(), tempo, ppq), sm.getData1(), (int) prev[1], ch));
                open.put(key, new long[]{e.getTick(), sm.getData2()});
            } else if (noteOff) {
                long[] prev = open.remove(key);
                if (prev != null) notes.add(new Note(sec(prev[0], tempo, ppq), sec(e.getTick(), tempo, ppq), sm.getData1(), (int) prev[1], ch));
            }
        }
        double bpm = o.bpm > 0 ? o.bpm : 60e6 / tempo.firstEntry().getValue();
        double stepSec = 60.0 / (bpm * o.stepsPerBeat);
        boolean hasDrums = o.drums && notes.stream().anyMatch(n -> n.ch == 9);
        log.accept(String.format("MIDI : %d notes, tempo %.0f, %s", notes.size(), bpm, hasDrums ? "batterie sur la voie C" : "sans batterie"));
        double end = notes.stream().mapToDouble(n -> n.end).max().orElse(0);
        // canaux melodiques classes par hauteur moyenne : le plus aigu -> A, le plus grave -> B, les autres -> C
        Map<Integer, Double> avg = new TreeMap<>();
        for (int ch = 0; ch < 16; ch++) {
            final int c = ch;
            if (ch == 9) continue;
            notes.stream().filter(n -> n.ch == c).mapToInt(Note::pitch).average().ifPresent(a -> avg.put(c, a));
        }
        List<Integer> chans = new ArrayList<>(avg.keySet());
        chans.sort((x, y) -> Double.compare(avg.get(y), avg.get(x)));
        Map<Integer, Integer> chanVoice = new HashMap<>();
        for (int i = 0; i < chans.size(); i++) chanVoice.put(chans.get(i), i == 0 ? 0 : i == chans.size() - 1 ? 1 : 2);
        if (chans.size() >= 2) log.accept("canaux : " + chans + " -> A = canal " + (chans.get(0) + 1) + ", B = canal " + (chans.get(chans.size() - 1) + 1));
        int nSteps = (int) Math.min(Math.ceil((Math.min(end, o.start + o.maxSeconds) - o.start) / stepSec), 4000);
        MusicGrid g = base(o, stepSec, hasDrums);
        g.resize(Math.max(1, nSteps));
        int[] prev = {-1, -1, -1};
        for (int s = 0; s < nSteps; s++) {
            double t0 = o.start + s * stepSec, t1 = t0 + stepSec;
            List<Note> act = new ArrayList<>();
            boolean drumHit = false;
            for (Note n : notes) {
                if (n.end <= t0 + stepSec * 0.25 || n.start >= t1 - stepSec * 0.25) continue;   // present sur l'essentiel du pas
                if (n.ch == 9) { if (n.start >= t0 - stepSec * 0.25 && n.start < t1 - stepSec * 0.25) drumHit = true; continue; }
                act.add(n);
            }
            Note[] pick = new Note[3];
            if (chanVoice.size() >= 2) {                     // plusieurs canaux : une voie par partie
                for (Note n : act) {
                    int v = chanVoice.getOrDefault(n.ch, 2);
                    if (v == 2 && hasDrums) continue;
                    if (pick[v] == null || (v == 1 ? n.pitch < pick[v].pitch : n.pitch > pick[v].pitch)) pick[v] = n;
                }
            } else {                                          // un seul canal (piano) : aigu, grave, milieu
                act.sort(Comparator.comparingInt(Note::pitch));
                pick[0] = act.isEmpty() ? null : act.get(act.size() - 1);
                pick[1] = act.size() > 1 ? act.get(0) : null;
                pick[2] = act.size() > 2 && !hasDrums ? act.get(act.size() / 2) : null;
            }
            for (int v = 0; v < 3; v++) {
                if (hasDrums && v == 2) { g.rows.get(s)[2] = drumHit ? "C4" : (prev[2] == 1 ? "-" : ""); prev[2] = drumHit ? 1 : 0; continue; }
                Note n = pick[v];
                String cell;
                if (n == null) cell = prev[v] == -2 ? "" : "-";
                else {
                    boolean attack = n.start >= t0 - stepSec * 0.25;
                    int p = n.pitch + o.transpose;
                    cell = (!attack && prev[v] == p) ? "" : MusicGrid.make(p, vol(n.vel));
                    prev[v] = p;
                }
                if (n == null) prev[v] = -2;
                g.rows.get(s)[v] = cell;
            }
        }
        g.used[1] = true;
        g.used[2] = true;
        return g;
    }

    private static double sec(long tick, TreeMap<Long, Integer> tempo, int ppq) {
        double s = 0;
        long last = 0;
        int us = 500000;
        for (var e : tempo.entrySet()) {
            if (e.getKey() >= tick) break;
            s += (e.getKey() - last) * (double) us / ppq / 1e6;
            last = e.getKey();
            us = e.getValue();
        }
        return s + (tick - last) * (double) us / ppq / 1e6;
    }

    private static int vol(int vel) { return (int) Math.round(7 + 8 * Math.sqrt(vel / 127.0)); }

    private static MusicGrid base(Options o, double stepSec, boolean drums) {
        MusicGrid g = new MusicGrid();
        g.setStepSeconds(stepSec);
        g.loop = o.loop;
        g.volume[0] = 13; g.volume[1] = 12; g.volume[2] = drums ? 11 : 10;
        g.release[0] = o.release ? 9 : 0;
        g.release[1] = o.release ? 8 : 0;
        g.release[2] = o.release ? 6 : 0;
        if (drums) { g.noise[2] = 6; g.release[2] = 0; }
        Arrays.fill(g.used, true);
        g.rows.clear();
        return g;
    }

    // =================================================================== audio
    /** Decode un fichier audio en echantillons mono 22050 Hz (MP3 & co : ffmpeg). */
    public static float[] decode(File f, Consumer<String> log) throws Exception {
        String n = f.getName().toLowerCase(Locale.ROOT);
        File wav = f;
        if (!n.endsWith(".wav")) {
            wav = File.createTempFile("gxstudio", ".wav");
            wav.deleteOnExit();
            List<String> cmd = List.of("ffmpeg", "-y", "-loglevel", "error", "-i", f.getAbsolutePath(), "-ac", "1", "-ar", "22050", "-f", "wav", wav.getAbsolutePath());
            log.accept("ffmpeg : conversion en WAV…");
            Process p;
            try { p = new ProcessBuilder(cmd).redirectErrorStream(true).start(); }
            catch (IOException e) { throw new IOException("ffmpeg est nécessaire pour lire les MP3 : sudo apt install ffmpeg"); }
            String out = new String(p.getInputStream().readAllBytes());
            if (p.waitFor() != 0) throw new IOException("ffmpeg : " + out);
        }
        try (AudioInputStream in0 = AudioSystem.getAudioInputStream(wav)) {
            AudioFormat target = new AudioFormat(22050, 16, 1, true, false);
            AudioInputStream in = AudioSystem.isConversionSupported(target, in0.getFormat()) ? AudioSystem.getAudioInputStream(target, in0) : in0;
            AudioFormat fm = in.getFormat();
            byte[] b = in.readAllBytes();
            int ch = fm.getChannels(), bytes = fm.getSampleSizeInBits() / 8, frames = b.length / (ch * bytes);
            float[] s = new float[frames];
            boolean big = fm.isBigEndian();
            for (int i = 0; i < frames; i++) {
                float sum = 0;
                for (int c = 0; c < ch; c++) {
                    int o = (i * ch + c) * bytes;
                    int v = bytes == 1 ? (b[o] & 255) - 128 << 8
                            : big ? (b[o] << 8) | (b[o + 1] & 255) : (b[o + 1] << 8) | (b[o] & 255);
                    sum += v / 32768f;
                }
                s[i] = sum / ch;
            }
            if (fm.getSampleRate() != 22050) s = resample(s, fm.getSampleRate(), 22050);
            return s;
        }
    }

    private static float[] resample(float[] s, float from, float to) {
        int n = (int) (s.length * (double) to / from);
        float[] r = new float[n];
        for (int i = 0; i < n; i++) {
            double x = i * (double) from / to;
            int k = (int) x;
            double f = x - k;
            r[i] = (float) (k + 1 < s.length ? s[k] * (1 - f) + s[k + 1] * f : s[Math.min(k, s.length - 1)]);
        }
        return r;
    }

    /** Tempo estime (60-180) par autocorrelation de l'energie des attaques. */
    public static double detectBpm(float[] s) {
        int hop = 512;
        int n = s.length / hop;
        double[] env = new double[n];
        double prev = 0;
        for (int i = 0; i < n; i++) {
            double e = 0;
            for (int k = 0; k < hop; k++) e += s[i * hop + k] * s[i * hop + k];
            e = Math.sqrt(e);
            env[i] = Math.max(0, e - prev);
            prev = e;
        }
        double best = 120, bestV = -1;
        for (double bpm = 60; bpm <= 180; bpm += 0.5) {
            double v = acf(env, 22050.0 / hop * 60 / bpm);
            if (v > bestV) { bestV = v; best = bpm; }
        }
        if (best < 90 && acf(env, 22050.0 / hop * 60 / (best * 2)) > 0.5 * bestV) best *= 2;   // double tempo plus probable
        return best;
    }

    private static double acf(double[] env, double lag) {
        double v = 0;
        int l = (int) lag;
        double f = lag - l;
        for (int i = 0; i + l + 1 < env.length; i++) v += env[i] * (env[i + l] * (1 - f) + env[i + l + 1] * f);
        return v;
    }

    public static MusicGrid fromAudio(float[] s, Options o, Consumer<String> log) {
        double bpm = o.bpm > 0 ? o.bpm : detectBpm(s);
        double stepSec = 60.0 / (bpm * o.stepsPerBeat);
        int sr = 22050, N = 4096;
        int first = (int) (o.start * sr);
        int nSteps = (int) Math.min((Math.min(s.length / (double) sr, o.start + o.maxSeconds) - o.start) / stepSec, 4000);
        log.accept(String.format("Audio : %.1f s, tempo %.1f, %d pas", s.length / (double) sr, bpm, Math.max(0, nSteps)));
        if (nSteps <= 0) throw new IllegalArgumentException("morceau trop court");
        double[] win = new double[N];
        for (int i = 0; i < N; i++) win[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1));
        double[][] sal = new double[nSteps][128];
        double[] hf = new double[nSteps];
        double max = 1e-9, hfMax = 1e-9;
        double[] re = new double[N], im = new double[N];
        for (int st = 0; st < nSteps; st++) {
            int c = first + (int) ((st + 0.5) * stepSec * sr) - N / 2;
            for (int i = 0; i < N; i++) { int k = c + i; re[i] = k >= 0 && k < s.length ? s[k] * win[i] : 0; im[i] = 0; }
            fft(re, im);
            double[] mag = new double[N / 2];
            for (int i = 0; i < N / 2; i++) mag[i] = Math.hypot(re[i], im[i]);
            for (int note = 28; note <= 100; note++) {                 // somme des harmoniques
                double f0 = 440 * Math.pow(2, (note - 69) / 12.0), v = 0;
                for (int h = 1; h <= 5; h++) {
                    double bin = f0 * h * N / sr;
                    int b = (int) Math.round(bin);
                    if (b + 1 >= N / 2) break;
                    v += Math.max(mag[b], Math.max(mag[b - 1], mag[b + 1])) / h;
                }
                sal[st][note] = v;
                max = Math.max(max, v);
            }
            double e = 0;
            for (int i = (int) (5000.0 * N / sr); i < N / 2; i++) e += mag[i];
            hf[st] = e;
            hfMax = Math.max(hfMax, e);
        }
        MusicGrid g = base(o, stepSec, o.drums);
        g.resize(nSteps);
        int[][] notes = new int[3][nSteps];
        int[][] vols = new int[3][nSteps];
        for (int st = 0; st < nSteps; st++) {
            int a = best(sal[st], 55, 96, -1, -1, max * o.threshold);
            int b = best(sal[st], 28, 54, -1, -1, max * o.threshold * 0.8);
            int c = o.drums ? -1 : best(sal[st], 48, 84, a, b, max * o.threshold * 1.3);
            int[] p = {a, b, c};
            for (int v = 0; v < 3; v++) {
                notes[v][st] = p[v];
                vols[v][st] = p[v] < 0 ? 0 : (int) Math.round(Math.max(6, Math.min(15, 15 + 4 * Math.log(sal[st][p[v]] / max) / Math.log(2))));
            }
        }
        for (int v = 0; v < 3; v++) notes[v] = median3(notes[v]);
        double prevHf = 0;
        for (int st = 0; st < nSteps; st++) {
            for (int v = 0; v < (o.drums ? 2 : 3); v++) {
                int n = notes[v][st];
                int before = st > 0 ? notes[v][st - 1] : -9;
                String cell;
                if (n < 0) cell = before < 0 && st > 0 ? "" : "-";
                else {
                    boolean again = n == before && st > 0 && sal[st][n] > 1.6 * sal[st - 1][n];   // meme note rejouee
                    cell = n == before && !again ? "" : MusicGrid.make(n + o.transpose, vols[v][st]);
                }
                g.rows.get(st)[v] = cell;
            }
            if (o.drums) {                                              // attaques aigues -> bruit
                boolean hit = hf[st] > hfMax * 0.35 && hf[st] > prevHf * 1.3;
                g.rows.get(st)[2] = hit ? MusicGrid.make(60, (int) Math.round(9 + 6 * hf[st] / hfMax)) : (st > 0 && !g.rows.get(st - 1)[2].isEmpty() && !g.rows.get(st - 1)[2].equals("-") ? "-" : "");
                prevHf = hf[st];
            }
        }
        return g;
    }

    private static int best(double[] sal, int lo, int hi, int not1, int not2, double thr) {
        int b = -1;
        double bv = thr;
        for (int n = lo; n <= hi; n++) {
            if (near(n, not1) || near(n, not2)) continue;
            if (sal[n] > bv) { bv = sal[n]; b = n; }
        }
        return b;
    }

    private static boolean near(int n, int m) {
        if (m < 0) return false;
        int d = Math.abs(n - m) % 12;
        return d <= 1 || d == 11;
    }

    private static int[] median3(int[] a) {
        int[] r = a.clone();
        for (int i = 1; i + 1 < a.length; i++) {
            int x = a[i - 1], y = a[i], z = a[i + 1];
            if (x == z && y != x) r[i] = x;                          // note isolee d'un pas : bruit d'analyse
        }
        return r;
    }

    /** FFT radix 2 en place. */
    static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { double t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len, wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = a + len / 2;
                    double xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                    double t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t;
                }
            }
        }
    }
}
