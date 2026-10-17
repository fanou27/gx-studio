package fr.gxstudio.build;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/**
 * Liaison avec le serveur de debogage du Caprice32 corrige (option devtools.server_port).
 * Protocole texte, une ligne par commande ; les reponses et evenements arrivent sur un fil
 * de lecture et sont transmis a l'ecouteur (qui doit repasser dans le fil JavaFX).
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public class DebugClient implements Closeable {
    /** Etat des registres Z80 au dernier arret. */
    public static final class Regs {
        public final Map<String, Integer> v = new LinkedHashMap<>();
        public int get(String r) { return v.getOrDefault(r, 0); }
        static Regs parse(String line) {
            Regs r = new Regs();
            for (String t : line.substring(5).trim().split("\\s+")) {
                int e = t.indexOf('=');
                if (e > 0) r.v.put(t.substring(0, e), Integer.parseInt(t.substring(e + 1), 16));
            }
            return r;
        }
    }

    public interface Listener {
        void stopped(String reason, Regs regs);
        void running();
        void closed();
    }

    private final Socket socket;
    private final Writer out;
    private final Listener listener;
    private final Deque<Consumer<String>> pending = new ArrayDeque<>();
    private String stopReason;
    private volatile boolean closed;

    public DebugClient(int port, Listener l) throws IOException {
        socket = new Socket("127.0.0.1", port);
        socket.setTcpNoDelay(true);
        out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1);
        listener = l;
        Thread t = new Thread(this::readLoop, "gxdebug");
        t.setDaemon(true);
        t.start();
        request("hello", r -> { });
    }

    private void readLoop() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))) {
            String l;
            while ((l = r.readLine()) != null) {
                if (System.getenv("GXDEBUG_TRACE") != null) System.err.println("<< " + l);
                try { handle(l); } catch (RuntimeException e) { System.err.println("gxdebug : " + l + " : " + e); }
            }
        } catch (IOException ignored) { }
        closed = true;
        listener.closed();
    }

    private void handle(String l) {
        {
            {
                if (l.startsWith("STOP ")) { stopReason = l.split("\\s+")[1]; return; }
                if (l.startsWith("REGS ")) {
                    Regs regs = Regs.parse(l);
                    String why = stopReason;
                    stopReason = null;
                    if (why != null) { listener.stopped(why, regs); return; }
                }
                Consumer<String> c;
                synchronized (pending) { c = pending.pollFirst(); }
                if (c != null) c.accept(l);
            }
        }
    }

    private synchronized void send(String cmd) {
        if (closed) return;
        if (System.getenv("GXDEBUG_TRACE") != null) System.err.println(">> " + cmd);
        try { out.write(cmd + "\n"); out.flush(); } catch (IOException e) { closed = true; }
    }

    /** Commande avec reponse (OK, MEM..., BP..., REGS...). */
    private void request(String cmd, Consumer<String> reply) {
        synchronized (pending) { pending.addLast(reply); }
        send(cmd);
    }

    public boolean isClosed() { return closed; }

    public void pause() { send("pause"); }
    public void cont() { request("cont", r -> { }); listener.running(); }
    public void step() { send("step"); }
    public void stepOver() { send("over"); }
    public void stepOut() { send("out"); listener.running(); }
    public void addBreakpoint(int a) { request(String.format("bp add %04X", a), r -> { }); }
    public void removeBreakpoint(int a) { request(String.format("bp del %04X", a), r -> { }); }
    public void clearBreakpoints() { request("bp clear", r -> { }); }
    public void poke(int addr, byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) sb.append(String.format("%02X", b & 255));
        request(String.format("poke %04X %s", addr & 0xFFFF, sb), r -> { });
    }
    /** Commande quelconque (ex. "prof on") ; reponse (OK / ERR...) transmise a reply. */
    public void command(String cmd, Consumer<String> reply) { request(cmd, reply); }
    public void screenshot(String file) { request("shot " + file, r -> { }); }
    public void key(int row, int bit, boolean down) { request("key " + row + " " + bit + " " + (down ? 1 : 0), r -> { }); }

    /** Lecture memoire (vue du Z80) ; le resultat arrive sur le fil de lecture. */
    public void memory(int addr, int len, Consumer<byte[]> result) { read("mem", addr, len, result); }

    /** Lecture de la RAM physique (ex. l'ecran en &C000 quand la ROM haute de la cartouche le masque). */
    public void ram(int addr, int len, Consumer<byte[]> result) { read("ram", addr, len, result); }

    private void read(String cmd, int addr, int len, Consumer<byte[]> result) {
        request(String.format("%s %04X %d", cmd, addr & 0xFFFF, len), l -> {
            byte[] b = new byte[0];
            if (l.startsWith("MEM ")) {
                String[] p = l.split("\\s+");
                String h = p.length > 2 ? p[2] : "";
                b = new byte[h.length() / 2];
                for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
            }
            result.accept(b);
        });
    }

    @Override public void close() {
        closed = true;
        try { socket.close(); } catch (IOException ignored) { }
    }
}
