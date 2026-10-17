package fr.gxstudio.ui;

import fr.gxstudio.model.*;
import javafx.collections.*;
import javafx.geometry.*;
import javafx.scene.canvas.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

/**
 * Editeur de rasters (CPC+ / GX4000) : liste des changements de couleur (ligne, encre, couleur),
 * generateur de degrades et apercu des encres ligne par ligne.
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public class RasterEditor extends EditorTab {
    private final RasterTable table;
    private final Supplier<Project> project;
    private final Supplier<Palette> palette;
    private final TableView<RasterTable.Entry> view = new TableView<>();
    private final ObservableList<RasterTable.Entry> rows = FXCollections.observableArrayList();
    private final Spinner<Integer> line = new Spinner<>(1, 197, 40), ink = new Spinner<>(0, 15, 0);
    private final Slider r = sl(), g = sl(), b = sl();
    private final Label swatch = new Label("      "), warn = new Label();
    private final Canvas preview = new Canvas(400, 400);
    private boolean updating;

    public RasterEditor(Path file, Supplier<Project> project, Supplier<Palette> palette) throws Exception {
        super(file);
        this.project = project;
        this.palette = palette;
        table = RasterTable.load(file);
        TableColumn<RasterTable.Entry, Number> cL = new TableColumn<>("Ligne"), cI = new TableColumn<>("Encre");
        TableColumn<RasterTable.Entry, RasterTable.Entry> cC = new TableColumn<>("Couleur");
        cL.setCellValueFactory(d -> new javafx.beans.property.SimpleIntegerProperty(d.getValue().line()));
        cI.setCellValueFactory(d -> new javafx.beans.property.SimpleIntegerProperty(d.getValue().ink()));
        cC.setCellValueFactory(d -> new javafx.beans.property.SimpleObjectProperty<>(d.getValue()));
        cC.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(RasterTable.Entry e, boolean empty) {
                super.updateItem(e, empty);
                if (empty || e == null) { setText(null); setGraphic(null); return; }
                Label sw = new Label("    ");
                sw.setStyle("-fx-background-color: " + Gfx.web(e.rgb()) + "; -fx-border-color: #555;");
                setGraphic(sw);
                setText(String.format(" #%03X", e.rgb()));
            }
        });
        cL.setPrefWidth(70); cI.setPrefWidth(60); cC.setPrefWidth(120);
        view.getColumns().addAll(List.of(cL, cI, cC));
        view.setItems(rows);
        view.getSelectionModel().selectedItemProperty().addListener((o, a, e) -> { if (e != null) load(e); });
        Button add = new Button("Ajouter / remplacer"), del = new Button("Supprimer");
        add.setOnAction(e -> { put(new RasterTable.Entry(line.getValue(), ink.getValue(), rgb())); });
        del.setOnAction(e -> {
            RasterTable.Entry s = view.getSelectionModel().getSelectedItem();
            if (s != null) { table.entries.remove(s); changed(); }
        });
        for (Slider s : List.of(r, g, b)) s.valueProperty().addListener((o, x, y) -> { if (!updating) swatch(); });
        line.setEditable(true); line.setPrefWidth(80); ink.setPrefWidth(70);
        GridPane ed = new GridPane();
        ed.setHgap(6); ed.setVgap(6);
        ed.addRow(0, new Label("Ligne"), line, new Label("Encre"), ink);
        ed.addRow(1, new Label("Rouge"), r);
        ed.addRow(2, new Label("Vert"), g);
        ed.addRow(3, new Label("Bleu"), b, swatch);
        GridPane.setColumnSpan(r, 3); GridPane.setColumnSpan(g, 3);
        // --- degrade
        Spinner<Integer> from = new Spinner<>(1, 197, 40), to = new Spinner<>(1, 197, 100), stepS = new Spinner<>(2, 50, 4), gInk = new Spinner<>(0, 15, 0);
        TextField c1 = new TextField("#00F"), c2 = new TextField("#0F0");
        for (Spinner<Integer> s : List.of(from, to, stepS, gInk)) { s.setPrefWidth(75); s.setEditable(true); }
        c1.setPrefColumnCount(5); c2.setPrefColumnCount(5);
        Button gen = new Button("Générer le dégradé");
        gen.setOnAction(e -> gradient(from.getValue(), to.getValue(), stepS.getValue(), gInk.getValue(), hex(c1.getText()), hex(c2.getText())));
        GridPane gp = new GridPane();
        gp.setHgap(6); gp.setVgap(6);
        gp.addRow(0, new Label("De la ligne"), from, new Label("à"), to);
        gp.addRow(1, new Label("Toutes les"), stepS, new Label("lignes, encre"), gInk);
        gp.addRow(2, new Label("Couleur début"), c1, new Label("fin"), c2);
        gp.add(gen, 1, 3, 3, 1);
        TitledPane tp = new TitledPane("Dégradé (« copper »)", gp);
        tp.setCollapsible(false);
        warn.setWrapText(true);
        warn.setStyle("-fx-text-fill: #b5651d;");
        Label help = new Label("Chaque ligne du tableau change la couleur d'une encre à partir de cette ligne d'écran.\n"
                + "La couleur reste jusqu'au changement suivant, y compris en haut de l'image suivante :\n"
                + "terminez par la couleur d'origine si besoin. Dans le programme :\n"
                + "   ld hl,RASTER_NOM : call raster_start");
        VBox left = new VBox(8, view, new HBox(6, add, del), ed, tp, warn);
        left.setPadding(new Insets(10));
        left.setPrefWidth(430);
        view.setPrefHeight(210);
        help.setWrapText(true);
        help.setMaxWidth(400);
        VBox right = new VBox(8, new Label("Aperçu : chaque encre, ligne par ligne (0 à 199)"), preview, help);
        right.setPadding(new Insets(10));
        setContent(new ScrollPane(new HBox(left, right)));
        refresh();
        swatch();
        dirty.set(false);
    }

    private static Slider sl() {
        Slider s = new Slider(0, 15, 0);
        s.setMajorTickUnit(5); s.setMinorTickCount(4); s.setSnapToTicks(true); s.setShowTickMarks(true); s.setShowTickLabels(true);
        s.setPrefWidth(240);
        return s;
    }

    private static int hex(String s) {
        try {
            String h = s.replace("#", "").trim();
            if (h.length() == 6) { int v = Integer.parseInt(h, 16); return ((v >> 20) & 15) << 8 | ((v >> 12) & 15) << 4 | ((v >> 4) & 15); }
            return Integer.parseInt(h, 16) & 0xFFF;
        } catch (Exception e) { return 0; }
    }

    private int rgb() { return (int) r.getValue() << 8 | (int) g.getValue() << 4 | (int) b.getValue(); }
    private void swatch() { swatch.setStyle("-fx-background-color: " + Gfx.web(rgb()) + "; -fx-border-color: #555;"); }

    private void load(RasterTable.Entry e) {
        updating = true;
        line.getValueFactory().setValue(e.line());
        ink.getValueFactory().setValue(e.ink());
        r.setValue((e.rgb() >> 8) & 15); g.setValue((e.rgb() >> 4) & 15); b.setValue(e.rgb() & 15);
        updating = false;
        swatch();
    }

    /** Une seule entree par ligne : une interruption ne change qu'une encre. */
    private void put(RasterTable.Entry e) {
        table.entries.removeIf(x -> x.line() == e.line());
        table.entries.add(e);
        changed();
    }

    private void gradient(int a, int z, int step, int ink, int c1, int c2) {
        if (z < a) { int t = a; a = z; z = t; }
        int n = Math.max(1, (z - a) / step);
        for (int i = 0; i <= n; i++) {
            int l = a + i * step;
            if (l > z) break;
            double f = (double) i / n;
            int rr = (int) Math.round(((c1 >> 8) & 15) * (1 - f) + ((c2 >> 8) & 15) * f);
            int gg = (int) Math.round(((c1 >> 4) & 15) * (1 - f) + ((c2 >> 4) & 15) * f);
            int bb = (int) Math.round((c1 & 15) * (1 - f) + (c2 & 15) * f);
            final int ll = l;
            table.entries.removeIf(x -> x.line() == ll);
            table.entries.add(new RasterTable.Entry(l, ink, rr << 8 | gg << 4 | bb));
        }
        changed();
    }

    private void changed() { dirty.set(true); refresh(); }

    @Override public void refresh() {
        table.sort();
        rows.setAll(table.entries);
        Project p = project.get();
        List<String> pb = new ArrayList<>(table.check());
        if (!p.target.plus) pb.add(0, "Les rasters ne sont générés que pour le CPC+ et la GX4000.");
        warn.setText(String.join("\n", pb));
        // apercu : une colonne par encre du mode
        int n = Gfx.inks(p.screenMode);
        Palette pal = palette.get();
        int[] col = new int[16];
        for (int i = 0; i < 16; i++) col[i] = pal.rgb[i];
        for (RasterTable.Entry e : table.entries) col[e.ink() & 15] = e.rgb();     // couleur persistante en haut d'image
        GraphicsContext gc = preview.getGraphicsContext2D();
        double cw = preview.getWidth() / n;
        int k = 0;
        for (int y = 0; y < 200; y++) {
            while (k < table.entries.size() && table.entries.get(k).line() == y) { RasterTable.Entry e = table.entries.get(k++); col[e.ink() & 15] = e.rgb(); }
            for (int i = 0; i < n; i++) {
                gc.setFill(Color.web(Gfx.web(p.target.plus ? col[i] : Palette.shown(col[i], p.target))));
                gc.fillRect(i * cw, y * 2, cw, 2);
            }
            while (k < table.entries.size() && table.entries.get(k).line() < y) k++;
        }
        gc.setFill(Color.WHITE);
        gc.setStroke(Color.BLACK);
        for (int i = 0; i < n; i++) { gc.strokeText("encre " + i, i * cw + 4, 14); gc.fillText("encre " + i, i * cw + 4, 14); }
    }

    @Override public void save() throws Exception {
        table.save(file);
        dirty.set(false);
    }
}
