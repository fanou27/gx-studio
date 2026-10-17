package fr.gxstudio.build;

import fr.gxstudio.model.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * Lancement de Caprice32 avec la bonne machine, la bonne RAM, le jeu et les symboles.
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public class Emulator {
    private Process process;

    /** Modele Caprice32 : 0 = 464, 1 = 664, 2 = 6128, 3 = 6128+. */
    public static int model(Project p) {
        if (p.target.plus) return 3;
        return p.ramKb <= 64 ? 1 : 2;
    }

    public static List<String> command(Project p, Settings s) { return command(p, s, 0); }

    /** debugPort > 0 : active le serveur de debogage du Caprice32 corrige (ignore par un Caprice32 d'origine). */
    public static List<String> command(Project p, Settings s, int debugPort) {
        List<String> c = new ArrayList<>();
        c.add(s.caprice);
        int model = model(p);
        int ram = model >= 2 ? Math.max(128, p.ramKb) : 64;
        if (p.target == Target.GX4000) ram = 128;       // Caprice32 d'origine : 128 Ko minimum en 6128+
        c.addAll(List.of("-O", "system.model=" + model, "-O", "system.ram_size=" + ram));
        // Caprice32 cherche ses ROM (cpc464/664/6128, amsdos) dans ./rom du dossier COURANT :
        // lance depuis le studio, il ne les trouve pas et quitte aussitot en CPC (la cartouche
        // CPC+/GX contient sa propre ROM, d'ou un plantage seulement pour les cibles CPC).
        Path roms = romDir(s);
        if (roms != null) c.addAll(List.of("-O", "rom.rom_path=" + roms));
        if (debugPort > 0) c.addAll(List.of("-O", "devtools.server_port=" + debugPort,
                "-O", "system.auto_pause=0"));                  // le focus ne doit pas relancer un programme arrete
        Path sym = p.build().resolve("caprice.sym");
        if (Files.exists(sym)) c.addAll(List.of("-s", sym.toAbsolutePath().toString()));
        for (String o : s.capriceOptions.trim().split("\\s+")) if (!o.isBlank()) c.add(o);
        if (!p.target.plus) c.addAll(List.of("-a", "run\"" + p.shortName()));
        c.add(p.output().toAbsolutePath().toString());
        return c;
    }

    /** Dossier de Caprice32 (lien symbolique resolu), ou null si introuvable. */
    static Path capDir(Settings s) {
        try {
            Path b = Paths.get(s.caprice);
            if (!b.isAbsolute() || !Files.exists(b)) {          // "cap32" seul : chercher dans le PATH
                for (String d : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
                    Path q = Paths.get(d, s.caprice);
                    if (Files.isExecutable(q)) { b = q; break; }
                }
            }
            return Files.exists(b) ? b.toRealPath().getParent() : null;
        } catch (Exception e) { return null; }
    }

    /** Dossier rom/ a cote de l'executable (installation compilee a la main), sinon null. */
    static Path romDir(Settings s) {
        Path d = capDir(s);
        if (d == null) return null;
        Path r = d.resolve("rom");
        return Files.exists(r.resolve("amsdos.rom")) ? r : null;
    }

    public void run(Project p, Settings s, Consumer<String> log) throws IOException { run(p, s, log, null); }

    /**
     * Lance Caprice32. Si onDebugServer n'est pas null, demande le serveur de debogage :
     * quand Caprice32 annonce qu'il ecoute ("GXDEBUG port"), onDebugServer recoit le port
     * (depuis le fil de lecture des messages).
     */
    public void run(Project p, Settings s, Consumer<String> log, java.util.function.IntConsumer onDebugServer) throws IOException {
        stop();
        int port = 0;
        if (onDebugServer != null) {
            try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) { port = ss.getLocalPort(); }
        }
        List<String> cmd = command(p, s, port);
        log.accept("== " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Path dir = capDir(s);
        if (dir != null) pb.directory(dir.toFile());          // cap32.cfg et rom/ relatifs au dossier courant
        try { process = pb.start(); }
        catch (IOException e) { log.accept("ERREUR : Caprice32 introuvable (" + s.caprice + ") : voir Reglages"); return; }
        Process pr = process;
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()))) {
                String l;
                while ((l = r.readLine()) != null) {
                    if (l.startsWith("INFO")) continue;
                    if (l.startsWith("GXDEBUG ") && onDebugServer != null) {
                        onDebugServer.accept(Integer.parseInt(l.substring(8).trim()));
                        continue;
                    }
                    log.accept("[cap32] " + l);
                    if (l.contains("Couldn't open ROM"))
                        log.accept("ERREUR : Caprice32 ne trouve pas ses ROM. Reglez rom_path dans cap32.cfg "
                                + "(section [rom]) ou ajoutez dans Outils > Reglages, options : -O rom.rom_path=/chemin/vers/caprice32/rom");
                }
            } catch (IOException ignored) { }
            log.accept("== Caprice32 ferme");
        }, "cap32-log");
        t.setDaemon(true);
        t.start();
    }

    public void stop() { if (process != null && process.isAlive()) process.destroy(); }
    public boolean running() { return process != null && process.isAlive(); }
}
