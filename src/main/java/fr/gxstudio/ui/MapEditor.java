package fr.gxstudio.ui;

import fr.gxstudio.model.*;
import javafx.collections.FXCollections;
import javafx.geometry.*;
import javafx.scene.canvas.*;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;

import javax.imageio.ImageIO;
import java.nio.file.Path;
import java.util.*;
import java.util.function.*;

/**
 * Editeur de carte : on peint avec les tuiles d'un jeu de tuiles (crayon, rectangle,
 * remplissage, pipette), avec le cadre d'un ecran pour reperer ce qui sera visible.
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public class MapEditor extends EditorTab {
    private TileMap map;
    private Tileset ts;
    private final Supplier<Project> project;
    private final Supplier<Palette> palette;
    private final Function<String, Tileset> tilesets;
    private final Supplier<List<String>> tilesetNames;
    private final BiConsumer<String, Tileset> onTilesetChanged;
    private int cur = 1;
    private final Canvas canvas = new Canvas();
    private final FlowPane palBox = new FlowPane(3, 3);
    private final ComboBox<String> tsBox = new ComboBox<>();
    private final Spinner<Integer> wSpin = new Spinner<>(1, 1024, 40), hSpin = new Spinner<>(1, 1024, 25);
    private final ComboBox<Integer> zoom = new ComboBox<>(FXCollections.observableArrayList(1, 2, 3, 4));
    private final CheckBox grid = new CheckBox("Grille"), frame = new CheckBox("Cadre écran");
    private final ToggleGroup tools = new ToggleGroup();
    private final Label status = new Label();
    private final Deque<TileMap> undo = new ArrayDeque<>();
    private Image[] cache = new Image[0];
    private int rx0 = -1, ry0 = -1, rx1, ry1;
    private boolean updating;

    public MapEditor(Path file, Supplier<Project> project, Supplier<Palette> palette, Function<String, Tileset> tilesets,
                     Supplier<List<String>> tilesetNames, BiConsumer<String, Tileset> onTilesetChanged) throws Exception {
        super(file);
        this.project = project;
        this.palette = palette;
        this.tilesets = tilesets;
        this.tilesetNames = tilesetNames;
        this.onTilesetChanged = onTilesetChanged;
        map = TileMap.load(file);
        ToggleButton pencil = new ToggleButton("Crayon"), rect = new ToggleButton("Rectangle"), fill = new ToggleButton("Remplir"), pick = new ToggleButton("Pipette");
        for (ToggleButton b : List.of(pencil, rect, fill, pick)) b.setToggleGroup(tools);
        pencil.setUserData("pencil"); rect.setUserData("rect"); fill.setUserData("fill"); pick.setUserData("pick");
        pencil.setSelected(true);
        tsBox.setOnAction(e -> { if (!updating && tsBox.getValue() != null) { map.tileset = tsBox.getValue(); dirty.set(true); refresh(); } });
        wSpin.setPrefWidth(80); hSpin.setPrefWidth(80); wSpin.setEditable(true); hSpin.setEditable(true);
        wSpin.valueProperty().addListener((o, a, b) -> { if (!updating && b != map.w) { pushUndo(); map.resize(b, map.h); dirty.set(true); redraw(); } });
        hSpin.valueProperty().addListener((o, a, b) -> { if (!updating && b != map.h) { pushUndo(); map.resize(map.w, b); dirty.set(true); redraw(); } });
        zoom.setValue(2);
        zoom.setOnAction(e -> { cache = new Image[0]; redraw(); });
        grid.setSelected(true); frame.setSelected(true);
        grid.setOnAction(e -> redraw()); frame.setOnAction(e -> redraw());
        Button imp = new Button("Importer PNG…");
        imp.setTooltip(new Tooltip("Découpe une image en tuiles (ajoutées au jeu de tuiles) et en fait la carte"));
        imp.setOnAction(e -> importPng());
        ToolBar tb = new ToolBar(new Label("Tuiles :"), tsBox, new Separator(), pencil, rect, fill, pick, new Separator(),
                new Label("Taille :"), wSpin, new Label("x"), hSpin, new Separator(), new Label("Zoom"), zoom, grid, frame, new Separator(), imp);
        canvas.setOnMousePressed(this::press);
        canvas.setOnMouseDragged(this::drag);
        canvas.setOnMouseReleased(this::release);
        canvas.setOnMouseMoved(e -> { int[] c = cell(e); status.setText(c == null ? "" : "case " + c[0] + "," + c[1] + "  tuile " + map.cells[c[1]][c[0]]); });
        ScrollPane center = new ScrollPane(canvas);
        ScrollPane left = new ScrollPane(palBox);
        left.setFitToWidth(true);
        left.setPrefWidth(200);
        palBox.setPadding(new Insets(6));
        HBox bottom = new HBox(status);
        bottom.setPadding(new Insets(4, 8, 4, 8));
        Label help = new Label("Clic gauche : peindre avec la tuile choisie (à gauche)  -  clic droit : prendre la tuile  -  Ctrl+Z : annuler");
        BorderPane root = new BorderPane(center, new VBox(tb, help), null, bottom, left);
        help.setPadding(new Insets(2, 8, 2, 8));
        root.setOnKeyPressed(e -> { if (e.isControlDown() && e.getCode() == KeyCode.Z) undo(); });
        setContent(root);
        refresh();
        dirty.set(false);
    }

    private int mode() { return project.get().screenMode; }

    @Override public void refresh() {
        updating = true;
        tsBox.setItems(FXCollections.observableArrayList(tilesetNames.get()));
        if (map.tileset.isEmpty() && !tsBox.getItems().isEmpty()) map.tileset = tsBox.getItems().get(0);
        tsBox.setValue(map.tileset);
        wSpin.getValueFactory().setValue(map.w);
        hSpin.getValueFactory().setValue(map.h);
        updating = false;
        ts = map.tileset.isEmpty() ? null : tilesets.apply(map.tileset);
        cache = new Image[0];
        palBox.getChildren().clear();
        if (ts != null) {
            Palette pal = palette.get();
            for (int i = 0; i < ts.tiles.size(); i++) {
                final int idx = i;
                ImageView iv = new ImageView(Gfx.tile(ts.tiles.get(i), pal, project.get().target, mode(), 3));
                Label l = new Label(Integer.toString(i), iv);
                l.setContentDisplay(ContentDisplay.TOP);
                l.setStyle("-fx-padding: 2; -fx-border-color: " + (i == cur ? "#e76f51" : "transparent") + "; -fx-border-width: 2;");
                l.setOnMouseClicked(e -> { cur = idx; refresh(); });
                palBox.getChildren().add(l);
            }
        }
        redraw();
    }

    private double tw() { return ts == null ? 16 : ts.w * Gfx.aspect(mode()) * zoom.getValue(); }
    private double th() { return ts == null ? 16 : ts.h * zoom.getValue(); }

    private Image img(int t) {
        if (ts == null || t >= ts.tiles.size()) return null;
        if (cache.length != ts.tiles.size()) cache = new Image[ts.tiles.size()];
        if (cache[t] == null) cache[t] = Gfx.tile(ts.tiles.get(t), palette.get(), project.get().target, mode(), zoom.getValue());
        return cache[t];
    }

    private void redraw() {
        canvas.setWidth(map.w * tw());
        canvas.setHeight(map.h * th());
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(Color.gray(0.15));
        g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        for (int y = 0; y < map.h; y++)
            for (int x = 0; x < map.w; x++) {
                Image im = img(map.cells[y][x]);
                if (im != null) g.drawImage(im, x * tw(), y * th());
                else { g.setFill(Color.web("#a00")); g.fillRect(x * tw(), y * th(), tw(), th()); }
            }
        if (grid.isSelected()) {
            g.setStroke(Color.gray(1, 0.18));
            g.setLineWidth(1);
            for (int x = 0; x <= map.w; x++) g.strokeLine(x * tw() + .5, 0, x * tw() + .5, map.h * th());
            for (int y = 0; y <= map.h; y++) g.strokeLine(0, y * th() + .5, map.w * tw(), y * th() + .5);
        }
        if (frame.isSelected() && ts != null) {      // un ecran : 320 x 200 pixels mode 1 (160 en mode 0)
            int scrW = (mode() == 0 ? 160 : mode() == 1 ? 320 : 640) / ts.w, scrH = 200 / ts.h;
            g.setStroke(Color.web("#ffd166"));
            g.setLineWidth(2);
            for (int sy = 0; sy * scrH < map.h; sy++)
                for (int sx = 0; sx * scrW < map.w; sx++)
                    g.strokeRect(sx * scrW * tw() + 1, sy * scrH * th() + 1, scrW * tw() - 2, scrH * th() - 2);
        }
        if (rx0 >= 0) {
            g.setStroke(Color.web("#e76f51"));
            g.setLineWidth(2);
            int x0 = Math.min(rx0, rx1), y0 = Math.min(ry0, ry1);
            g.strokeRect(x0 * tw(), y0 * th(), (Math.abs(rx1 - rx0) + 1) * tw(), (Math.abs(ry1 - ry0) + 1) * th());
        }
    }

    private int[] cell(MouseEvent e) {
        int x = (int) (e.getX() / tw()), y = (int) (e.getY() / th());
        return x < 0 || y < 0 || x >= map.w || y >= map.h ? null : new int[]{x, y};
    }

    private String tool() { return (String) tools.getSelectedToggle().getUserData(); }

    private void press(MouseEvent e) {
        int[] c = cell(e);
        if (c == null) return;
        if (e.getButton() == MouseButton.SECONDARY || tool().equals("pick")) { cur = map.cells[c[1]][c[0]]; refresh(); return; }
        pushUndo();
        switch (tool()) {
            case "rect" -> { rx0 = rx1 = c[0]; ry0 = ry1 = c[1]; redraw(); }
            case "fill" -> { flood(c[0], c[1], map.cells[c[1]][c[0]], cur); dirty.set(true); redraw(); }
            default -> paint(c);
        }
    }

    private void drag(MouseEvent e) {
        int[] c = cell(e);
        if (c == null || e.getButton() == MouseButton.SECONDARY) return;
        if (tool().equals("rect") && rx0 >= 0) { rx1 = c[0]; ry1 = c[1]; redraw(); }
        else if (tool().equals("pencil")) paint(c);
    }

    private void release(MouseEvent e) {
        if (!tool().equals("rect") || rx0 < 0) return;
        for (int y = Math.min(ry0, ry1); y <= Math.max(ry0, ry1); y++)
            for (int x = Math.min(rx0, rx1); x <= Math.max(rx0, rx1); x++) map.cells[y][x] = cur;
        rx0 = -1;
        dirty.set(true);
        redraw();
    }

    private void paint(int[] c) {
        if (map.cells[c[1]][c[0]] == cur) return;
        map.cells[c[1]][c[0]] = cur;
        dirty.set(true);
        GraphicsContext g = canvas.getGraphicsContext2D();
        Image im = img(cur);
        if (im != null) g.drawImage(im, c[0] * tw(), c[1] * th());
    }

    private void flood(int x, int y, int from, int to) {
        if (from == to) return;
        Deque<int[]> q = new ArrayDeque<>();
        q.add(new int[]{x, y});
        while (!q.isEmpty()) {
            int[] c = q.poll();
            if (c[0] < 0 || c[1] < 0 || c[0] >= map.w || c[1] >= map.h || map.cells[c[1]][c[0]] != from) continue;
            map.cells[c[1]][c[0]] = to;
            q.add(new int[]{c[0] + 1, c[1]}); q.add(new int[]{c[0] - 1, c[1]});
            q.add(new int[]{c[0], c[1] + 1}); q.add(new int[]{c[0], c[1] - 1});
        }
    }

    private void pushUndo() { undo.push(map.copy()); if (undo.size() > 50) undo.removeLast(); }

    private void undo() {
        if (undo.isEmpty()) return;
        map = undo.pop();
        dirty.set(true);
        refresh();
    }

    private void importPng() {
        if (ts == null) { new Alert(Alert.AlertType.WARNING, "Choisissez d'abord un jeu de tuiles.").showAndWait(); return; }
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png", "*.gif", "*.bmp"));
        java.io.File f = fc.showOpenDialog(canvas.getScene().getWindow());
        if (f == null) return;
        try {
            pushUndo();
            int added = TilesetEditor.importImage(ts, ImageIO.read(f), palette.get(), project.get(), map);
            onTilesetChanged.accept(map.tileset, ts);
            dirty.set(true);
            refresh();
            new Alert(Alert.AlertType.INFORMATION, "Carte de " + map.w + " x " + map.h + " cases ; " + added
                    + " tuile(s) ajoutée(s) au jeu « " + map.tileset + " ».").showAndWait();
        } catch (Exception ex) {
            new Alert(Alert.AlertType.ERROR, "Import impossible : " + ex.getMessage()).showAndWait();
        }
    }

    @Override public void save() throws Exception {
        map.save(file);
        dirty.set(false);
    }
}
