package fr.gxstudio.gen;

import javax.sound.sampled.*;
import java.util.List;

/**
 * Petit emulateur du PSG AY-3-8912 (1 MHz) pilote par une liste DMA :
 * permet d'ecouter une musique ou un bruitage sans lancer l'emulateur.
 * Une instruction est executee par ligne d'ecran (64 us), comme le DMA de l'ASIC.
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public class AySynth {
    private static final double[] VOL = {0, 0.0137, 0.0205, 0.0291, 0.0423, 0.0618, 0.0847, 0.1369,
            0.1691, 0.2647, 0.3527, 0.4499, 0.5711, 0.6873, 0.8482, 1.0};
    public static final int RATE = 44100;
    private volatile boolean stop;

    /** Rend la liste en echantillons 16 bits mono (duree maximale en secondes). */
    public static short[] render(List<Integer> words, int prescaler, double maxSeconds) {
        int[] reg = new int[16];
        reg[7] = 0b00011100;
        int ptr = 0, wait = 0, loopPtr = 0, loopCount = 0;
        boolean running = true;
        int[] cnt = new int[3];
        boolean[] out = new boolean[3];
        int noiseCnt = 0, lfsr = 1;
        boolean noiseOut = false;
        int nSamples = (int) (maxSeconds * RATE);
        short[] pcm = new short[nSamples];
        double linePos = 0, samplesPerLine = RATE * 64e-6;
        int produced = 0, silentLines = 0;
        double acc = 0;
        int accN = 0;
        while (produced < nSamples) {
            // --- une ligne : une instruction DMA
            if (running) {
                if (wait > 0) wait--;
                else if (ptr < words.size()) {
                    int w = words.get(ptr++);
                    switch (w & 0xF000) {
                        case 0x0000 -> reg[(w >> 8) & 15] = w & 0xFF;
                        case 0x1000 -> wait = (w & 0xFFF) * (prescaler + 1) - 1;
                        case 0x2000 -> { loopCount = w & 0xFFF; loopPtr = ptr; }
                        default -> {
                            if ((w & 1) != 0) { if (loopCount > 0) { loopCount--; ptr = loopPtr; } }
                            else if ((w & 0x20) != 0) running = false;
                        }
                    }
                } else running = false;
            }
            // --- 8 pas de 8 us (horloge des tonalites : 1 MHz / 8) pendant une ligne de 64 us
            double sum = 0;
            for (int k = 0; k < 8; k++) {
                for (int c = 0; c < 3; c++) {
                    int per = Math.max(1, (reg[c * 2] | (reg[c * 2 + 1] & 15) << 8));
                    if (++cnt[c] >= per) { cnt[c] = 0; out[c] = !out[c]; }
                }
                int np = Math.max(1, reg[6] & 31);
                if (++noiseCnt >= np * 2) {
                    noiseCnt = 0;
                    int bit = (lfsr ^ (lfsr >> 3)) & 1;
                    lfsr = (lfsr >> 1) | (bit << 16);
                    noiseOut = (lfsr & 1) != 0;
                }
                for (int c = 0; c < 3; c++) {
                    boolean toneOn = (reg[7] & (1 << c)) == 0, noiseOn = (reg[7] & (8 << c)) == 0;
                    boolean v = (!toneOn || out[c]) && (!noiseOn || noiseOut);
                    if (v) sum += VOL[reg[8 + c] & 15];
                }
            }
            acc += sum / 8;
            accN++;
            linePos += samplesPerLine;
            if (linePos >= 1) {
                double val = acc / accN;                 // moyenne depuis le dernier echantillon
                while (linePos >= 1 && produced < nSamples) {
                    pcm[produced++] = (short) Math.max(-32768, Math.min(32767, val * 12000 - 6000));
                    linePos -= 1;
                }
                acc = 0;
                accN = 0;
            }
            boolean silent = (reg[8] | reg[9] | reg[10]) == 0;
            silentLines = silent ? silentLines + 1 : 0;
            if (!running && silentLines > 2000) break;
        }
        short[] r = new short[produced];
        System.arraycopy(pcm, 0, r, 0, produced);
        return r;
    }

    /** Joue dans un fil separe ; stop() interrompt. */
    public void play(short[] pcm) {
        stop = false;
        Thread t = new Thread(() -> {
            AudioFormat f = new AudioFormat(RATE, 16, 1, true, false);
            try (SourceDataLine line = AudioSystem.getSourceDataLine(f)) {
                line.open(f, RATE / 5);
                line.start();
                byte[] buf = new byte[2048];
                int i = 0;
                while (i < pcm.length && !stop) {
                    int n = 0;
                    while (n < buf.length && i < pcm.length) { buf[n++] = (byte) pcm[i]; buf[n++] = (byte) (pcm[i] >> 8); i++; }
                    line.write(buf, 0, n);
                }
                if (!stop) line.drain();
                line.stop();
            } catch (Exception e) {
                System.err.println("son indisponible : " + e.getMessage());
            }
        }, "ay-play");
        t.setDaemon(true);
        t.start();
    }

    public void stop() { stop = true; }
}
