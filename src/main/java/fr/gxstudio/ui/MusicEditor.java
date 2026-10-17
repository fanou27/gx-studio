package fr.gxstudio.ui;

import fr.gxstudio.gen.*;
import javafx.animation.*;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.*;
import javafx.scene.canvas.*;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Editeur de musique facon "tracker" : 3 voies (A, B, C), une ligne par pas, saisie au
 * clavier (disposition AZERTY) ou au piano, volumes par note, ecoute immediate,
 * import de fichiers MIDI, MP3, WAV... L'onglet Texte montre le fichier .snd produit.
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public class MusicEditor extends EditorTab {
    private static final int RH = 18, NUMW = 46, CW = 112;
    private MusicGrid grid;
    private final CodeArea text = new CodeArea();
    private final TabPane tabs = new TabPane();
    private final Tab gridTab = new Tab("Grille"), textTab = new Tab("Texte");
    private final Canvas canvas = new Canvas(NUMW + 3 * CW + 2, 400);
    private final ScrollBar vbar = new ScrollBar();
    private int row = 0, voice = 0, selA = -1, selB = -1, octave = 4, editStep = 1, playRow = -1;
    private final Spinner<Integer> speed = new Spinner<>(0, 255, 51), step = new Spinner<>(1, 4095, 36), rows = new Spinner<>(1, 4000, 64),
            octaveS = new Spinner<>(0, 8, 4), stepS = new Spinner<>(0, 16, 1);
    private final ComboBox<String> volBox = new ComboBox<>();
    private final ComboBox<Integer> chan = new ComboBox<>(FXCollections.observableArrayList(0, 1, 2));
    private final CheckBox loop = new CheckBox("Boucle");
    private final CheckBox[] used = new CheckBox[3], noiseC = new CheckBox[3];
    private final Spinner<Integer>[] vol = new Spinner[3], rel = new Spinner[3], noiseP = new Spinner[3];
    private final Label bpm = new Label(), size = new Label(), status = new Label();
    private final AySynth synth = new AySynth(), preview = new AySynth();
    private final Timeline playhead = new Timeline();
    private List<String[]> clipboard = new ArrayList<>();
    private boolean updating, rawSound;

    // clavier AZERTY : rangee du bas = octave courante, rangee du haut = octave suivante
    private static final String KEYS_LOW = "wsxdcvgbhnj,", KEYS_HIGH = "aézertyu";
    private static final Map<String, Integer> KEYMAP = new HashMap<>();
    static {
        for (int i = 0; i < KEYS_LOW.length(); i++) KEYMAP.put(String.valueOf(KEYS_LOW.charAt(i)), i);
        String[] hi = {"a", "é", "z", "\"", "e", "r", "(", "t", "-", "y", "è", "u"};
        for (int i = 0; i < hi.length; i++) KEYMAP.put(hi[i], 12 + i);
    }

    public MusicEditor(Path file) throws Exception {
        super(file);
        String src = Files.readString(file, StandardCharsets.UTF_8);
        grid = MusicGrid.parse(src);
        rawSound = grid == null;
        if (rawSound) grid = new MusicGrid();
        text.replaceText(src);
        text.setParagraphGraphicFactory(LineNumberFactory.get(text));
        text.getStyleClass().add("asm");
        text.textProperty().addListener((o, a, b) -> { if (tabs.getSelectionModel().getSelectedItem() == textTab) { dirty.set(true); text.setStyleSpans(0, SoundEditor.hl(b)); updateSize(); } });
        text.setStyleSpans(0, SoundEditor.hl(text.getText()));
        textTab.setContent(new VirtualizedScrollPane<>(text));
        gridTab.setContent(buildGrid());
        gridTab.setClosable(false);
        textTab.setClosable(false);
        tabs.getTabs().addAll(gridTab, textTab);
        if (rawSound) {
            gridTab.setDisable(true);
            tabs.getSelectionModel().select(textTab);
            status.setText("Bruitage (instructions directes) : édition en texte.");
        }
        tabs.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> switchTab(b));
        Button play = new Button("▶ Écouter"), playFrom = new Button("▶ Depuis le curseur"), stop = new Button("■ Stop"),
               imp = new Button("Importer MIDI / MP3 / WAV…");
        play.setOnAction(e -> play(0));
        playFrom.setOnAction(e -> play(row));
        stop.setOnAction(e -> stopPlay());
        imp.setOnAction(e -> importMusic());
        ToolBar tb = new ToolBar(play, playFrom, stop, new Separator(), imp, new Separator(), size);
        setContent(new BorderPane(tabs, tb, null, statusBox(), null));
        setOnClosed(e -> stopPlay());
        loadSettings();
        updateSize();
        dirty.set(false);
    }

    private HBox statusBox() { HBox h = new HBox(status); h.setPadding(new Insets(3, 8, 3, 8)); return h; }

    // ================================================================ grille
    private BorderPane buildGrid() {
        vbar.setOrientation(Orientation.VERTICAL);
        vbar.valueProperty().addListener((o, a, b) -> draw());
        Pane holder = new Pane(canvas);
        holder.setMinSize(0, 0);
        holder.heightProperty().addListener((o, a, b) -> { canvas.setHeight(b.doubleValue()); draw(); });
        holder.setPrefWidth(canvas.getWidth());
        canvas.setFocusTraversable(true);
        canvas.setOnMousePressed(e -> {
            canvas.requestFocus();
            int r = (int) (e.getY() / RH) + first(), v = (int) ((e.getX() - NUMW) / CW);
            if (r < 0 || r >= grid.size()) return;
            if (e.isShiftDown()) { if (selA < 0) selA = row; selB = r; } else { selA = selB = -1; }
            row = r;
            if (v >= 0 && v < 3) voice = v;
            draw();
        });
        canvas.setOnScroll(e -> vbar.setValue(Math.max(0, Math.min(vbar.getMax(), vbar.getValue() - Math.signum(e.getDeltaY()) * 3))));
        canvas.setOnKeyPressed(this::key);
        canvas.setOnKeyTyped(this::typed);
        HBox gridBox = new HBox(holder, vbar);
        HBox.setHgrow(holder, Priority.NEVER);
        // --- panneau de droite
        speed.setEditable(true); step.setEditable(true); rows.setEditable(true);
        for (Spinner<Integer> s : List.of(speed, step, rows, octaveS, stepS)) s.setPrefWidth(85);
        speed.valueProperty().addListener((o, a, b) -> { if (!updating) { grid.speed = b; changed(false); } });
        step.valueProperty().addListener((o, a, b) -> { if (!updating) { grid.step = b; changed(false); } });
        rows.valueProperty().addListener((o, a, b) -> { if (!updating) { grid.resize(b); row = Math.min(row, b - 1); changed(true); } });
        octaveS.valueProperty().addListener((o, a, b) -> octave = b);
        stepS.valueProperty().addListener((o, a, b) -> editStep = b);
        chan.setOnAction(e -> { if (!updating) { grid.channel = chan.getValue(); changed(false); } });
        loop.setOnAction(e -> { if (!updating) { grid.loop = loop.isSelected(); changed(false); } });
        volBox.getItems().add("voie");
        for (int i = 15; i >= 0; i--) volBox.getItems().add(Integer.toString(i));
        volBox.setValue("voie");
        Spinner<Integer> bpmS = new Spinner<>(30, 300, 120);
        bpmS.setEditable(true);
        bpmS.setPrefWidth(85);
        Button setBpm = new Button("Fixer");
        setBpm.setOnAction(e -> { grid.setStepSeconds(60.0 / (bpmS.getValue() * 4)); loadSettings(); changed(false); });
        GridPane g = new GridPane();
        g.setHgap(6); g.setVgap(6);
        int r = 0;
        g.add(new Label("Tempo"), 0, r++, 3, 1);
        g.addRow(r++, new Label("vitesse"), speed);
        g.addRow(r++, new Label("durée d'un pas"), step);
        g.addRow(r++, new Label("noire ="), bpmS, setBpm);
        g.add(bpm, 0, r++, 3, 1);
        g.addRow(r++, new Label("lignes"), rows);
        g.addRow(r++, new Label("canal DMA"), chan);
        g.add(loop, 0, r++, 2, 1);
        g.add(new Separator(), 0, r++, 3, 1);
        for (int v = 0; v < 3; v++) {
            final int vv = v;
            used[v] = new CheckBox("Voie " + (char) ('A' + v));
            vol[v] = new Spinner<>(0, 15, 12); rel[v] = new Spinner<>(0, 15, 8); noiseP[v] = new Spinner<>(0, 31, 6);
            noiseC[v] = new CheckBox("bruit");
            for (Spinner<Integer> s : List.of(vol[v], rel[v], noiseP[v])) s.setPrefWidth(70);
            used[v].setOnAction(e -> { if (!updating) { grid.used[vv] = used[vv].isSelected(); changed(true); } });
            vol[v].valueProperty().addListener((o, a, b) -> { if (!updating) { grid.volume[vv] = b; changed(false); } });
            rel[v].valueProperty().addListener((o, a, b) -> { if (!updating) { grid.release[vv] = b; changed(false); } });
            noiseC[v].setOnAction(e -> { if (!updating) { grid.noise[vv] = noiseC[vv].isSelected() ? noiseP[vv].getValue() : -1; changed(true); } });
            noiseP[v].valueProperty().addListener((o, a, b) -> { if (!updating && grid.noise[vv] >= 0) { grid.noise[vv] = b; changed(false); } });
            g.add(used[v], 0, r++, 3, 1);
            g.addRow(r++, new Label("  volume"), vol[v], new Label("relâche"), rel[v]);
            g.addRow(r++, new Label("  "), noiseC[v], new Label("période"), noiseP[v]);
        }
        g.add(new Separator(), 0, r++, 3, 1);
        g.add(new Label("Saisie"), 0, r++, 3, 1);
        g.addRow(r++, new Label("octave"), octaveS);
        g.addRow(r++, new Label("avance (lignes)"), stepS);
        g.addRow(r++, new Label("volume note"), volBox);
        Button[] tr = {new Button("-12"), new Button("-1"), new Button("+1"), new Button("+12")};
        int[] dv = {-12, -1, 1, 12};
        for (int i = 0; i < 4; i++) { final int d = dv[i]; tr[i].setOnAction(e -> transpose(d)); }
        g.add(new Label("transposer la sélection"), 0, r++, 3, 1);
        g.add(new HBox(4, tr), 0, r++, 3, 1);
        Button ins = new Button("Insérer ligne"), del = new Button("Supprimer ligne");
        ins.setOnAction(e -> { pushRows(); grid.rows.add(row, new String[]{"", "", ""}); changed(true); });
        del.setOnAction(e -> { if (grid.size() > 1) { grid.rows.remove(row); row = Math.min(row, grid.size() - 1); changed(true); } });
        g.add(new HBox(4, ins, del), 0, r++, 3, 1);
        Label help = new Label("Clavier (AZERTY) : W S X D C V G B H N J , = do à si ;\nA É Z \" E R ( T - Y È U = octave du dessus.\n"
                + "Espace : silence   Suppr : vider   Tab / ← → : voie\nMaj + clic ou Maj + ↑↓ : sélection   Ctrl+C / Ctrl+V : copier / coller\n"
                + "Une case vide prolonge la note précédente.");
        help.setStyle("-fx-font-size: 11px;");
        g.add(help, 0, r, 3, 1);
        g.setPadding(new Insets(8));
        ScrollPane side = new ScrollPane(g);
        side.setFitToWidth(true);
        side.setPrefWidth(370);
        BorderPane bp = new BorderPane(gridBox, null, side, piano(), null);
        return bp;
    }

    private void pushRows() { }        // (place pour un futur "annuler")

    private HBox piano() {
        Pane keys = new Pane();
        int white = 0;
        List<javafx.scene.Node> blacks = new ArrayList<>();
        for (int o = 0; o < 3; o++)
            for (int n = 0; n < 12; n++) {
                boolean black = "0101001010100".charAt(n) == '1';
                final int rel = o * 12 + n;
                Region k = new Region();
                if (black) {
                    k.setStyle("-fx-background-color: #222; -fx-border-color: #000;");
                    k.setPrefSize(16, 38);
                    k.setLayoutX(white * 24 - 8);
                    blacks.add(k);
                } else {
                    k.setStyle("-fx-background-color: white; -fx-border-color: #555;");
                    k.setPrefSize(24, 62);
                    k.setLayoutX(white * 24);
                    white++;
                    keys.getChildren().add(k);
                }
                k.setOnMousePressed(e -> { enterNote(12 * (octave + 1) + rel); canvas.requestFocus(); });
            }
        keys.getChildren().addAll(blacks);
        keys.setPrefSize(white * 24 + 2, 64);
        Label l = new Label("Piano : 3 octaves à partir de l'octave de saisie");
        VBox v = new VBox(2, l, keys);
        HBox h = new HBox(v);
        h.setPadding(new Insets(4, 8, 6, 8));
        return h;
    }

    private int first() { return (int) vbar.getValue(); }
    private int visible() { return Math.max(1, (int) (canvas.getHeight() / RH)); }

    private void draw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth(), h = canvas.getHeight();
        g.setFill(Color.web("#1e1f29"));
        g.fillRect(0, 0, w, h);
        g.setFont(Font.font("DejaVu Sans Mono", 13));
        int f = first(), n = visible();
        vbar.setMax(Math.max(0, grid.size() - n));
        vbar.setVisibleAmount(n);
        int sa = Math.min(selA, selB), sb = Math.max(selA, selB);
        for (int i = 0; i < n && f + i < grid.size(); i++) {
            int r = f + i;
            double y = i * RH;
            if (r % 16 == 0) { g.setFill(Color.web("#2f3142")); g.fillRect(0, y, w, RH); }
            else if (r % 4 == 0) { g.setFill(Color.web("#262734")); g.fillRect(0, y, w, RH); }
            if (selA >= 0 && r >= sa && r <= sb) { g.setFill(Color.web("#3d4f7a")); g.fillRect(NUMW, y, 3 * CW, RH); }
            if (r == playRow) { g.setFill(Color.web("#5a3d1a")); g.fillRect(0, y, w, RH); }
            if (r == row) { g.setFill(Color.web("#44475a")); g.fillRect(NUMW + voice * CW, y, CW, RH); }
            g.setFill(Color.web("#6c7086"));
            g.fillText(String.format("%3d", r), 6, y + 14);
            for (int v = 0; v < 3; v++) {
                String c = grid.rows.get(r)[v];
                double x = NUMW + v * CW + 8;
                if (!grid.used[v]) { g.setFill(Color.web("#444")); g.fillText("  ·", x, y + 14); continue; }
                if (c.isEmpty()) { g.setFill(Color.web("#4a4d5e")); g.fillText("···", x, y + 14); }
                else if (c.startsWith("-")) { g.setFill(Color.web("#ff79c6")); g.fillText("===", x, y + 14); }
                else {
                    int m = MusicGrid.midiOf(c);
                    String name = grid.noise[v] >= 0 ? "BRUIT" : m < 0 ? c : MusicGrid.noteName(m);
                    g.setFill(Color.web(grid.noise[v] >= 0 ? "#ffb86c" : "#8be9fd"));
                    g.fillText(String.format("%-4s", name.replace("#", "#")), x, y + 14);
                    String vv = MusicGrid.volOf(c);
                    g.setFill(Color.web("#f1fa8c"));
                    g.fillText(vv.isEmpty() ? " ." : " " + vv.toUpperCase(Locale.ROOT), x + 52, y + 14);
                }
            }
        }
        g.setStroke(Color.web("#555"));
        for (int v = 0; v <= 3; v++) g.strokeLine(NUMW + v * CW + .5, 0, NUMW + v * CW + .5, h);
        g.setFill(Color.web("#bbb"));
    }

    private void ensureVisible() {
        int f = first(), n = visible();
        if (row < f) vbar.setValue(row);
        else if (row >= f + n) vbar.setValue(row - n + 1);
    }

    private void key(KeyEvent e) {
        KeyCode k = e.getCode();
        boolean shift = e.isShiftDown();
        if (e.isControlDown() && k == KeyCode.C) { copy(); e.consume(); return; }
        if (e.isControlDown() && k == KeyCode.V) { paste(); e.consume(); return; }
        if (e.isControlDown() && k == KeyCode.X) { copy(); clearSel(); e.consume(); return; }
        int old = row;
        switch (k) {
            case UP -> row = Math.max(0, row - 1);
            case DOWN -> row = Math.min(grid.size() - 1, row + 1);
            case PAGE_UP -> row = Math.max(0, row - 16);
            case PAGE_DOWN -> row = Math.min(grid.size() - 1, row + 16);
            case HOME -> row = 0;
            case END -> row = grid.size() - 1;
            case LEFT -> voice = (voice + 2) % 3;
            case RIGHT, TAB -> voice = (voice + 1) % 3;
            case SPACE -> { set("-"); advance(); }
            case DELETE, BACK_SPACE -> { if (selA >= 0) clearSel(); else { set(""); advance(); } }
            default -> { return; }
        }
        if (k == KeyCode.UP || k == KeyCode.DOWN || k == KeyCode.PAGE_UP || k == KeyCode.PAGE_DOWN || k == KeyCode.HOME || k == KeyCode.END) {
            if (shift) { if (selA < 0) selA = old; selB = row; } else selA = selB = -1;
        }
        e.consume();
        ensureVisible();
        draw();
    }

    private void typed(KeyEvent e) {
        if (e.isControlDown()) return;
        String c = e.getCharacter().toLowerCase(Locale.ROOT);
        Integer n = KEYMAP.get(c);
        if (n == null) return;
        enterNote(12 * (octave + 1) + n);
        e.consume();
    }

    private void enterNote(int midi) {
        if (!grid.used[voice]) { grid.used[voice] = true; loadSettings(); }
        String v = volBox.getValue();
        set(MusicGrid.make(midi, v == null || v.equals("voie") ? -1 : Integer.parseInt(v)));
        previewNote(midi);
        advance();
        ensureVisible();
        draw();
    }

    private void advance() { row = Math.min(grid.size() - 1, row + editStep); }

    private void set(String c) {
        grid.rows.get(row)[voice] = c;
        changed(false);
    }

    private void copy() {
        clipboard = new ArrayList<>();
        int a = selA < 0 ? row : Math.min(selA, selB), b = selA < 0 ? row : Math.max(selA, selB);
        for (int r = a; r <= b; r++) clipboard.add(grid.rows.get(r).clone());
        status.setText(clipboard.size() + " ligne(s) copiée(s)");
    }

    private void paste() {
        for (int i = 0; i < clipboard.size() && row + i < grid.size(); i++) grid.rows.set(row + i, clipboard.get(i).clone());
        changed(true);
    }

    private void clearSel() {
        int a = Math.min(selA, selB), b = Math.max(selA, selB);
        if (selA < 0) { a = b = row; }
        for (int r = a; r <= b; r++) grid.rows.set(r, new String[]{"", "", ""});
        changed(true);
    }

    private void transpose(int d) {
        int a = selA < 0 ? 0 : Math.min(selA, selB), b = selA < 0 ? grid.size() - 1 : Math.max(selA, selB);
        for (int r = a; r <= b; r++)
            for (int v = 0; v < 3; v++) {
                if (grid.noise[v] >= 0) continue;
                String c = grid.rows.get(r)[v];
                int m = MusicGrid.midiOf(c);
                if (m < 0) continue;
                String vo = MusicGrid.volOf(c);
                grid.rows.get(r)[v] = MusicGrid.make(m + d, vo.isEmpty() ? -1 : Integer.parseInt(vo, 16));
            }
        changed(true);
    }

    private void changed(boolean structure) {
        dirty.set(true);
        loadSettings();
        updateSize();
        draw();
    }

    private void loadSettings() {
        updating = true;
        speed.getValueFactory().setValue(grid.speed);
        step.getValueFactory().setValue(grid.step);
        rows.getValueFactory().setValue(grid.size());
        chan.setValue(grid.channel);
        loop.setSelected(grid.loop);
        for (int v = 0; v < 3; v++) {
            if (used[v] == null) continue;
            used[v].setSelected(grid.used[v]);
            vol[v].getValueFactory().setValue(grid.volume[v]);
            rel[v].getValueFactory().setValue(grid.release[v]);
            noiseC[v].setSelected(grid.noise[v] >= 0);
            if (grid.noise[v] >= 0) noiseP[v].getValueFactory().setValue(grid.noise[v]);
        }
        bpm.setText(String.format("un pas = %.0f ms  (noire = %.1f si 4 pas par temps)", grid.stepSeconds() * 1000, grid.bpm()));
        updating = false;
    }

    private String currentText() {
        return tabs.getSelectionModel().getSelectedItem() == gridTab && !rawSound ? grid.toText() : text.getText();
    }

    private void updateSize() {
        SoundCompiler.Result r = SoundCompiler.compile(currentText());
        int bytes = r.words.size() * 2;
        size.setText(bytes + " octets" + (r.errors.isEmpty() ? "" : "  -  erreur : " + String.join(" ; ", r.errors)));
        size.setStyle(bytes > 6000 || !r.errors.isEmpty() ? "-fx-text-fill: #c1121f;" : "");
        if (bytes > 6000) size.setText(size.getText() + "  (gros : vérifier la place en RAM, DMA_RAM)");
    }

    private void switchTab(Tab t) {
        if (t == textTab && !rawSound) {
            text.replaceText(grid.toText());
            text.setStyleSpans(0, SoundEditor.hl(text.getText()));
        } else if (t == gridTab) {
            MusicGrid g = MusicGrid.parse(text.getText());
            if (g == null) { status.setText("Le texte n'est pas une grille (durées /n ou instructions directes) : resté en texte."); tabs.getSelectionModel().select(textTab); return; }
            grid = g;
            loadSettings();
            draw();
        }
        updateSize();
    }

    // ================================================================ ecoute
    private void play(int from) {
        stopPlay();
        String src;
        if (from > 0 && !rawSound && tabs.getSelectionModel().getSelectedItem() == gridTab) {
            MusicGrid g = MusicGrid.parse(grid.toText());
            g.rows = new ArrayList<>(g.rows.subList(from, g.size()));
            for (int v = 0; v < 3; v++)                              // premiere case vide : on reprend la note en cours
                if (g.rows.get(0)[v].isEmpty())
                    for (int r = from; r >= 0; r--) { String c = grid.rows.get(r)[v]; if (!c.isEmpty()) { g.rows.get(0)[v] = c; break; } }
            src = g.toText();
        } else src = currentText();
        SoundCompiler.Result r = SoundCompiler.compile(src);
        synth.play(AySynth.render(r.words, r.speed, 120));
        if (!rawSound && tabs.getSelectionModel().getSelectedItem() == gridTab) {
            playRow = from;
            playhead.getKeyFrames().setAll(new KeyFrame(Duration.seconds(grid.stepSeconds()), e -> {
                playRow++;
                if (playRow >= grid.size()) { if (grid.loop) playRow = 0; else { stopPlay(); return; } }
                int f = first(), n = visible();
                if (playRow < f || playRow >= f + n) vbar.setValue(Math.min(vbar.getMax(), playRow));
                draw();
            }));
            playhead.setCycleCount(Timeline.INDEFINITE);
            playhead.play();
        }
    }

    private void stopPlay() {
        synth.stop();
        playhead.stop();
        playRow = -1;
        draw();
    }

    private void previewNote(int midi) {
        if (grid.noise[voice] >= 0) return;
        preview.stop();
        String src = "vitesse 0\nduree 3000\nvolume A 12\nrelache A 0\nA: " + MusicGrid.noteName(Math.max(12, Math.min(107, midi))) + "\n";
        preview.play(AySynth.render(SoundCompiler.compile(src).words, 0, 0.3));
    }

    // ================================================================ import
    private void importMusic() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Musique à importer");
        fc.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Musiques", "*.mid", "*.midi", "*.mp3", "*.wav", "*.ogg", "*.flac", "*.m4a"),
                new FileChooser.ExtensionFilter("Tous", "*.*"));
        File f = fc.showOpenDialog(canvas.getScene().getWindow());
        if (f == null) return;
        boolean midi = f.getName().toLowerCase(Locale.ROOT).matches(".*\\.(mid|midi)$");
        MusicImport.Options o = new MusicImport.Options();
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Importer " + f.getName());
        Spinner<Double> bpmS = new Spinner<>(0.0, 300.0, 0.0, 1.0), startS = new Spinner<>(0.0, 3600.0, 0.0, 1.0), maxS = new Spinner<>(5.0, 600.0, 45.0, 5.0),
                thr = new Spinner<>(0.02, 0.6, 0.12, 0.02);
        Spinner<Integer> spb = new Spinner<>(1, 8, 4), trans = new Spinner<>(-36, 36, 0);
        CheckBox drums = new CheckBox(midi ? "Batterie (canal 10) sur la voie C en bruit" : "Attaques aiguës (batterie) sur la voie C en bruit");
        CheckBox relC = new CheckBox("Articulation (volume réduit aux 2/3 de chaque note)"), loopC = new CheckBox("Musique en boucle");
        drums.setSelected(true); relC.setSelected(true); loopC.setSelected(true);
        for (Spinner<?> s : List.of(bpmS, startS, maxS, thr, spb, trans)) { s.setEditable(true); s.setPrefWidth(95); }
        GridPane g = new GridPane();
        g.setHgap(8); g.setVgap(8); g.setPadding(new Insets(12));
        int r = 0;
        g.addRow(r++, new Label("Tempo (noires/min, 0 = " + (midi ? "celui du fichier" : "détection") + ")"), bpmS);
        g.addRow(r++, new Label("Pas par temps (4 = double croche)"), spb);
        g.addRow(r++, new Label("Début (s)"), startS);
        g.addRow(r++, new Label("Durée maximale (s)"), maxS);
        g.addRow(r++, new Label("Transposition (demi-tons)"), trans);
        if (!midi) g.addRow(r++, new Label("Sensibilité (seuil, plus petit = plus de notes)"), thr);
        g.add(drums, 0, r++, 2, 1);
        g.add(relC, 0, r++, 2, 1);
        g.add(loopC, 0, r++, 2, 1);
        Label note = new Label(midi ? "MIDI : notes exactes. A = partie la plus aiguë, B = la plus grave, C = les autres (ou la batterie)."
                : "MP3 / WAV : analyse du spectre (somme des harmoniques) pour retrouver mélodie (A), basse (B) et une voix\n"
                + "intermédiaire (C). Résultat « chiptune » approximatif : idéal pour une mélodie claire. MP3, OGG, FLAC : ffmpeg requis.");
        g.add(note, 0, r, 2, 1);
        d.getDialogPane().setContent(g);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.initOwner(canvas.getScene().getWindow());
        if (d.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        o.bpm = bpmS.getValue(); o.stepsPerBeat = spb.getValue(); o.start = startS.getValue(); o.maxSeconds = maxS.getValue();
        o.transpose = trans.getValue(); o.threshold = thr.getValue(); o.drums = drums.isSelected(); o.release = relC.isSelected(); o.loop = loopC.isSelected();
        status.setText("Import en cours…");
        Task<MusicGrid> task = new Task<>() {
            @Override protected MusicGrid call() throws Exception {
                java.util.function.Consumer<String> log = s -> javafx.application.Platform.runLater(() -> status.setText(s));
                return midi ? MusicImport.fromMidi(f, o, log) : MusicImport.fromAudio(MusicImport.decode(f, log), o, log);
            }
        };
        task.setOnSucceeded(e -> {
            grid = task.getValue();
            grid.channel = chan.getValue() == null ? 0 : chan.getValue();
            if (rawSound) { rawSound = false; gridTab.setDisable(false); }
            tabs.getSelectionModel().select(gridTab);
            row = 0; vbar.setValue(0);
            changed(true);
            status.setText("Importé : " + f.getName() + " - " + grid.size() + " pas. Écoutez, puis retouchez la grille si besoin.");
        });
        task.setOnFailed(e -> {
            status.setText("Échec de l'import");
            new Alert(Alert.AlertType.ERROR, "Import impossible : " + task.getException().getMessage()).showAndWait();
        });
        Thread t = new Thread(task, "import");
        t.setDaemon(true);
        t.start();
    }

    @Override public void save() throws Exception {
        Files.writeString(file, currentText(), StandardCharsets.UTF_8);
        if (tabs.getSelectionModel().getSelectedItem() == gridTab && !rawSound) { text.replaceText(grid.toText()); }
        dirty.set(false);
    }
}
