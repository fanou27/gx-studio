package fr.gxstudio.ui;

import fr.gxstudio.build.Builder;
import fr.gxstudio.build.DebugClient;
import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.*;

/**
 * Onglet "Debogage" : commandes (continuer, pause, pas a pas), registres du Z80, pile,
 * memoire et variables surveillees. Relie au serveur du Caprice32 corrige par DebugClient.
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public class DebugPanel extends BorderPane implements DebugClient.Listener {
    /** Ce que le panneau demande a la fenetre principale. */
    public interface Host {
        /** Montre la ligne ou le programme est arrete (null : effacer le marquage). */
        void showPc(Builder.SourceLine line, int pc);
        void debugStatus(String s);
        void debugEnded();
        void watchesChanged();
        /** Message dans la console (le panneau la montre). */
        void log(String s);
    }

    private static final String[] REGS = {"PC", "SP", "AF", "BC", "DE", "HL", "IX", "IY", "AF'", "BC'", "DE'", "HL'", "I", "R", "IM", "IFF1"};

    private final Host host;
    private DebugClient client;
    private Builder map;
    private DebugClient.Regs last, prev;
    private boolean stopped;

    private final Label state = new Label("Pas de session : F5 lance le programme avec le débogueur");
    private final Button bCont = new Button("▶ Continuer"), bPause = new Button("⏸ Pause"),
            bStep = new Button("↓ Pas à pas"), bOver = new Button("↷ Pas principal"), bOut = new Button("↑ Sortir"),
            bProf = new Button("⏱ Profil 5 s");
    private final Map<String, Label> regLabels = new LinkedHashMap<>();
    private final Label flags = new Label();
    private final ListView<String> stack = new ListView<>();
    private final TextField memAddr = new TextField("&C000");
    private final TextArea memView = new TextArea();
    private final CheckBox memRam = new CheckBox("RAM");
    private final ListView<String> watches = new ListView<>();
    private final List<String> watchNames = new ArrayList<>();
    private final TextField watchField = new TextField();

    public DebugPanel(Host host) {
        this.host = host;
        getStyleClass().add("debug-panel");
        bCont.setTooltip(new Tooltip("F8 : reprendre l'exécution"));
        bPause.setTooltip(new Tooltip("F6 : arrêter le programme là où il est"));
        bStep.setTooltip(new Tooltip("F11 : une instruction (entre dans les CALL)"));
        bOver.setTooltip(new Tooltip("F10 : une instruction ; un CALL, RST, LDIR, DJNZ est exécuté en entier"));
        bOut.setTooltip(new Tooltip("Maj+F11 : continue jusqu'au RET de la routine en cours"));
        bProf.setTooltip(new Tooltip("Mesure pendant 5 secondes ou le programme passe son temps (résultat dans la console)"));
        for (Button b : List.of(bCont, bPause, bStep, bOver, bOut, bProf)) b.setMinWidth(Region.USE_PREF_SIZE);
        bProf.setOnAction(e -> profile(5));
        bCont.setOnAction(e -> cont());
        bPause.setOnAction(e -> pause());
        bStep.setOnAction(e -> step());
        bOver.setOnAction(e -> stepOver());
        bOut.setOnAction(e -> stepOut());
        HBox buttons = new HBox(6, bCont, bPause, bStep, bOver, bOut, new Separator(javafx.geometry.Orientation.VERTICAL), bProf);
        buttons.setAlignment(Pos.CENTER_LEFT);
        VBox bar = new VBox(4, buttons, state);
        bar.setPadding(new Insets(2, 2, 6, 2));

        GridPane regs = new GridPane();
        regs.setHgap(6);
        regs.setVgap(2);
        for (int i = 0; i < REGS.length; i++) {
            Label n = new Label(REGS[i]), v = new Label("----");
            n.getStyleClass().add("reg-name");
            v.getStyleClass().add("reg-value");
            regLabels.put(REGS[i], v);
            regs.add(n, (i / 8) * 2, i % 8);
            regs.add(v, (i / 8) * 2 + 1, i % 8);
        }
        flags.getStyleClass().add("reg-value");
        regs.add(new Label("Drapeaux"), 0, 8, 2, 1);
        regs.add(flags, 2, 8, 2, 1);
        TitledPane tRegs = titled("Registres Z80", regs);

        stack.getStyleClass().add("mono");
        stack.setPrefWidth(250);
        TitledPane tStack = titled("Pile (SP)", stack);

        memView.setEditable(false);
        memView.getStyleClass().add("mono");
        memView.setPrefColumnCount(42);
        memView.setWrapText(false);
        memAddr.setPrefColumnCount(12);
        memAddr.setPromptText("adresse ou étiquette");
        memAddr.setOnAction(e -> refreshMemory());
        Button mGo = new Button("Afficher");
        mGo.setOnAction(e -> refreshMemory());
        mGo.setText("↻");
        mGo.setTooltip(new Tooltip("Afficher (adresse &C000, #C000, étiquette, étiquette+10)"));
        memRam.setTooltip(new Tooltip("Lire la RAM elle-même : sur CPC+/GX4000 la cartouche (ROM) masque &0000-&3FFF et &C000-&FFFF "
                + "en lecture ; cocher pour voir l'écran ou les variables qui sont dessous"));
        memRam.setOnAction(e -> refreshMemory());
        HBox mBar = new HBox(6, memAddr, mGo, memRam);
        mBar.setAlignment(Pos.CENTER_LEFT);
        mBar.setAlignment(Pos.CENTER_LEFT);
        VBox mem = new VBox(4, mBar, memView);
        VBox.setVgrow(memView, Priority.ALWAYS);
        TitledPane tMem = titled("Mémoire", mem);

        watches.getStyleClass().add("mono");
        watches.setPrefWidth(400);
        watches.setTooltip(new Tooltip("étiquette, adresse, octet en décimal, hexadécimal et signé, puis le mot de 16 bits"));
        watchField.setPromptText("étiquette (ex. ball_x) ou &adresse");
        watchField.setOnAction(e -> addWatch());
        Button wAdd = new Button("+"), wDel = new Button("−");
        wAdd.setOnAction(e -> addWatch());
        wDel.setOnAction(e -> {
            int i = watches.getSelectionModel().getSelectedIndex();
            if (i >= 0 && i < watchNames.size()) { watchNames.remove(i); refreshWatches(); host.watchesChanged(); }
        });
        HBox wBar = new HBox(4, watchField, wAdd, wDel);
        VBox w = new VBox(4, wBar, watches);
        VBox.setVgrow(watches, Priority.ALWAYS);
        TitledPane tWatch = titled("Variables", w);

        tRegs.setMinWidth(Region.USE_PREF_SIZE);
        tMem.setMinWidth(330);
        stack.setPrefWidth(220);
        watches.setPrefWidth(300);
        HBox body = new HBox(8, tRegs, tStack, tWatch, tMem);
        HBox.setHgrow(tWatch, Priority.ALWAYS);
        setTop(bar);
        setCenter(body);
        update();
    }

    private static TitledPane titled(String t, javafx.scene.Node n) {
        TitledPane p = new TitledPane(t, n);
        p.setCollapsible(false);
        p.setMaxHeight(Double.MAX_VALUE);
        return p;
    }

    // ------------------------------------------------------------------ session
    public void attach(DebugClient c, Builder b) {
        client = c;
        map = b;
        stopped = false;
        last = prev = null;
        state.setText("Programme en cours d'exécution");
        update();
    }

    /** Nouvelle carte des lignes (compilation) sans relancer. */
    public void setMap(Builder b) { map = b; }

    public boolean connected() { return client != null && !client.isClosed(); }
    public boolean isStopped() { return connected() && stopped; }
    public DebugClient client() { return client; }

    public void cont() { if (connected() && stopped) { host.showPc(null, -1); client.cont(); } }
    public void pause() { if (connected() && !stopped) client.pause(); }
    public void step() { if (connected() && stopped) { cStepBegin(false); client.step(); } }
    public void stepOver() { if (connected() && stopped) { cStepBegin(true); client.stepOver(); } }

    // pas a pas dans un fichier C : on avance jusqu'a la ligne de C suivante
    private Builder.SourceLine cFrom;
    private boolean cOver;
    private int cCount;
    private void cStepBegin(boolean over) {
        cFrom = null;
        if (map == null || last == null) return;
        Builder.SourceLine l = map.lineAt(last.get("PC"));
        if (l != null && l.file().endsWith(".c")) { cFrom = l; cOver = over; cCount = 0; }
    }
    /** Profil : compte les instructions pendant n secondes, puis rapport dans la console. */
    public void profile(int seconds) {
        if (!connected() || map == null) { host.log("== Profil : lancer d'abord le programme avec F5 (Caprice32 corrigé 0.5.1)"); return; }
        if (stopped) cont();
        host.log("== Profil : mesure pendant " + seconds + " s, jouez normalement...");
        bProf.setDisable(true);
        Builder b = map;
        client.command("prof clear", r -> { });
        client.command("prof on", r -> {
            if (!r.startsWith("OK")) Platform.runLater(() -> {
                host.log("== Profil indisponible : ce Caprice32 n'a pas le correctif GX Studio 0.5.1 (commande prof)");
                bProf.setDisable(false);
            });
        });
        javafx.animation.PauseTransition t = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(seconds));
        t.setOnFinished(e -> {
            if (!connected()) { bProf.setDisable(false); return; }
            try {
                java.nio.file.Path f = java.nio.file.Files.createTempFile("gxprof", ".bin");
                client.command("prof off", r -> { });
                client.command("prof save " + f.toAbsolutePath(), r -> Platform.runLater(() -> {
                    bProf.setDisable(false);
                    try {
                        if (r.startsWith("OK")) host.log(fr.gxstudio.build.Profiler.report(f, b, seconds));
                        java.nio.file.Files.deleteIfExists(f);
                    } catch (Exception ex) { host.log("== Profil : " + ex.getMessage()); }
                }));
            } catch (Exception ex) { bProf.setDisable(false); host.log("== Profil : " + ex.getMessage()); }
        });
        t.play();
    }

    public void stepOut() { if (connected() && stopped) { host.showPc(null, -1); client.stepOut(); } }

    public List<String> watchNames() { return watchNames; }

    public void setMemAddress(String a) { memAddr.setText(a); refreshMemory(); }

    public void setWatches(Collection<String> names) {
        watchNames.clear();
        watchNames.addAll(names);
        refreshWatches();
    }

    @Override public void stopped(String reason, DebugClient.Regs regs) {
        Platform.runLater(() -> {
            stopped = true;
            prev = last;
            last = regs;
            int pc = regs.get("PC");
            Builder.SourceLine l = map == null ? null : map.lineAt(pc);
            if (cFrom != null && "step".equals(reason) && ++cCount < 2000
                    && (l == null && !cOver || l != null && l.file().equals(cFrom.file()) && l.line() == cFrom.line())) {
                last = regs;                                     // meme ligne de C : pas suivant
                if (cOver) client.stepOver(); else client.step();
                return;
            }
            cFrom = null;
            String where = l == null ? String.format("&%04X (hors du programme : ROM, firmware ou données)", pc)
                    : String.format("&%04X  %s:%d", pc, l.file(), l.line());
            String why = switch (reason) {
                case "bp" -> "Point d'arrêt";
                case "step" -> "Pas à pas";
                case "pause" -> "Pause";
                case "watch" -> "Surveillance mémoire";
                default -> reason;
            };
            state.setText("Arrêté (" + why + ") en " + where);
            host.showPc(l, pc);
            host.debugStatus("Débogage : arrêté en " + where);
            update();
            refreshAll();
        });
    }

    @Override public void running() {
        Platform.runLater(() -> {
            stopped = false;
            state.setText("Programme en cours d'exécution");
            update();
        });
    }

    @Override public void closed() {
        Platform.runLater(() -> {
            client = null;
            stopped = false;
            state.setText("Session terminée (Caprice32 fermé)");
            host.showPc(null, -1);
            host.debugEnded();
            update();
        });
    }

    private void update() {
        boolean c = connected();
        bCont.setDisable(!c || !stopped);
        bStep.setDisable(!c || !stopped);
        bOver.setDisable(!c || !stopped);
        bOut.setDisable(!c || !stopped);
        bPause.setDisable(!c || stopped);
        state.getStyleClass().removeAll("debug-state-run", "debug-state-stop");
        if (c) state.getStyleClass().add(stopped ? "debug-state-stop" : "debug-state-run");
        if (last != null) {
            for (String r : REGS) {
                Label v = regLabels.get(r);
                int val = last.get(r);
                v.setText(r.equals("IM") || r.equals("IFF1") ? Integer.toString(val)
                        : r.equals("I") || r.equals("R") ? String.format("%02X", val) : String.format("%04X", val));
                boolean changed = prev != null && prev.get(r) != val && !r.equals("R");
                v.getStyleClass().remove("reg-changed");
                if (changed) v.getStyleClass().add("reg-changed");
                String sym = symbolAt(val);
                v.setTooltip(sym == null ? null : new Tooltip(sym));
            }
            int f = last.get("AF") & 255;
            String[] names = {"S", "Z", "-", "H", "-", "P/V", "N", "C"};
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                if (names[i].equals("-")) continue;
                sb.append(((f >> (7 - i)) & 1) != 0 ? names[i] : names[i].toLowerCase(Locale.ROOT)).append(' ');
            }
            flags.setText(sb.toString().trim());
            flags.setTooltip(new Tooltip("Majuscule = drapeau à 1 : S signe, Z zéro, H demi-retenue, P/V parité/débordement, N soustraction, C retenue"));
        }
    }

    // ------------------------------------------------------------------ memoire, pile, variables
    private void refreshAll() {
        refreshStack();
        refreshMemory();
        refreshWatches();
    }

    private void refreshStack() {
        if (!connected() || last == null) return;
        int sp = last.get("SP");
        client.memory(sp, 16, b -> Platform.runLater(() -> {
            List<String> rows = new ArrayList<>();
            for (int i = 0; i + 1 < b.length; i += 2) {
                int w = (b[i] & 255) | (b[i + 1] & 255) << 8;
                String sym = symbolNear(w);
                rows.add(String.format("%04X: %04X %s", (sp + i) & 0xFFFF, w, sym == null ? "" : sym));
            }
            stack.getItems().setAll(rows);
        }));
    }

    private void refreshMemory() {
        if (!connected()) return;
        Integer a = resolve(memAddr.getText());
        if (a == null) { memView.setText("Adresse ou étiquette inconnue : " + memAddr.getText()); return; }
        int base = a & 0xFFF0;
        java.util.function.Consumer<byte[]> show = b -> Platform.runLater(() -> {
            StringBuilder sb = new StringBuilder();
            for (int r = 0; r < b.length; r += 8) {
                sb.append(String.format("%04X  ", (base + r) & 0xFFFF));
                StringBuilder asc = new StringBuilder();
                for (int i = 0; i < 8 && r + i < b.length; i++) {
                    int v = b[r + i] & 255;
                    sb.append(String.format("%02X ", v));
                    asc.append(v >= 32 && v < 127 ? (char) v : '.');
                }
                sb.append(' ').append(asc).append('\n');
            }
            memView.setText(sb.toString());
        });
        if (memRam.isSelected()) client.ram(base, 256, show); else client.memory(base, 256, show);
    }

    private void addWatch() {
        String n = watchField.getText().trim();
        if (n.isEmpty()) return;
        watchNames.add(n);
        watchField.clear();
        refreshWatches();
        host.watchesChanged();
    }

    private void refreshWatches() {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < watchNames.size(); i++) rows.add(String.format("%-16s ?", watchNames.get(i)));
        watches.getItems().setAll(rows);
        if (!connected()) return;
        for (int i = 0; i < watchNames.size(); i++) {
            int idx = i;
            String n = watchNames.get(i);
            Integer a = resolve(n);
            if (a == null) { watches.getItems().set(idx, String.format("%-16s étiquette inconnue", n)); continue; }
            client.memory(a, 2, b -> Platform.runLater(() -> {
                if (idx >= watches.getItems().size() || b.length < 2) return;
                int v = b[0] & 255, w = v | (b[1] & 255) << 8;
                watches.getItems().set(idx, String.format("%-10s &%04X = %3d #%02X (%4d) mot %5d",
                        n, a, v, v, (byte) v, w));
            }));
        }
    }

    /** "&C000", "#C000", "$C000", "0xC000", nombre decimal ou etiquette (avec +n). */
    public Integer resolve(String s) {
        s = s.trim();
        if (s.isEmpty()) return null;
        int plus = 0;
        int p = s.lastIndexOf('+');
        if (p > 0) {
            Integer o = resolve(s.substring(p + 1));
            if (o == null) return null;
            plus = o;
            s = s.substring(0, p).trim();
        }
        try {
            if (s.startsWith("&") || s.startsWith("#") || s.startsWith("$")) return (Integer.parseInt(s.substring(1), 16) + plus) & 0xFFFF;
            if (s.toLowerCase(Locale.ROOT).startsWith("0x")) return (Integer.parseInt(s.substring(2), 16) + plus) & 0xFFFF;
            if (s.matches("\\d+")) return (Integer.parseInt(s) + plus) & 0xFFFF;
        } catch (NumberFormatException e) { return null; }
        if (map == null) return null;
        String u = s.toUpperCase(Locale.ROOT);
        Integer a = map.symbols.get(u);
        if (a == null) a = map.symbols.get("_" + u);                  // C : variable ou fonction globale
        if (a == null)
            for (Map.Entry<String, Integer> e : map.symbols.entrySet())  // C : static d'un module (m_src_main__nom)
                if (e.getKey().startsWith("M_") && e.getKey().endsWith("__" + u)) { a = e.getValue(); break; }
        if (a == null)
            for (Map.Entry<String, Integer> e : map.symbols.entrySet())
                if (e.getKey().endsWith("." + u)) { a = e.getValue(); break; }
        return a == null ? null : (a + plus) & 0xFFFF;
    }

    private String symbolAt(int addr) {
        if (map == null) return null;
        for (Map.Entry<String, Integer> e : map.symbols.entrySet()) if (e.getValue() == addr && map.labels.contains(e.getKey()) && !e.getKey().startsWith(".") && !map.shadowed.contains(e.getKey())) return e.getKey().toLowerCase(Locale.ROOT);
        return null;
    }

    /** Noms du C : m_src_main__hit_bricks -> hit_bricks, _main -> main. */
    private static String cName(String s) {
        String r = s.replaceFirst("^(?i)m_.*?__", "");
        if (r.length() != s.length()) return r;
        return s.startsWith("_") && s.length() > 1 && !s.startsWith("__") ? s.substring(1) : s;
    }

    /** Pour la pile : "routine+n" si l'adresse est dans le programme (adresse de retour). */
    private String symbolNear(int addr) {
        if (map == null) return null;
        Builder.SourceLine l = map.lineAt(addr);
        if (l == null) return null;
        String best = null;
        int bestA = -1;
        for (Map.Entry<String, Integer> e : map.symbols.entrySet()) {
            int a = e.getValue();
            if (a <= addr && a > bestA && addr - a < 512 && map.labels.contains(e.getKey()) && !e.getKey().startsWith(".") && !map.shadowed.contains(e.getKey())
                    && !e.getKey().matches(".*\\.(L\\d+|__L.*|D_.*)")) { bestA = a; best = e.getKey(); }
        }
        if (best == null) return null;
        best = cName(best);
        String f = l.file().substring(l.file().lastIndexOf('/') + 1);
        return best.toLowerCase(Locale.ROOT) + (addr > bestA ? "+" + (addr - bestA) : "") + " (" + f + ":" + l.line() + ")";
    }
}
