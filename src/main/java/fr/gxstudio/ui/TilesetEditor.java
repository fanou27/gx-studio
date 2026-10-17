package fr.gxstudio.ui;

import fr.gxstudio.model.*;
import javafx.geometry.*;
import javafx.scene.canvas.*;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

/**
 * Editeur de jeu de tuiles : liste des tuiles a gauche, dessin au pixel au centre,
 * attributs (solide...) a droite. Les tuiles utilisent les encres de l'ecran.
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public class TilesetEditor extends EditorTab {
    public final Tileset ts;
    private final Supplier<Project> project;
    private final Supplier<Palette> palette;
    private final Runnable onChange;
    private int sel = 0, pen = 1;
    private final FlowPane list = new FlowPane(4, 4);
    private final Canvas canvas = new Canvas();
    private final HBox pens = new HBox();
    private final CheckBox solid = new CheckBox("Solide (bit 0)");
    private final Spinner<Integer> flags = new Spinner<>(0, 255, 0);
    private final Spinner<Integer> wSpin = new Spinner<>(2, 32, 8), hSpin = new Spinner<>(1, 32, 8);
    private final Label info = new Label();
    private final Deque<Tileset> undo = new ArrayDeque<>();
    private boolean updating;

    public TilesetEditor(Path file, Supplier<Project> project, Supplier<Palette> palette, Runnable onChange) throws Exception {
        super(file);
        this.project = project;
        this.palette = palette;
        this.onChange = onChange;
        ts = Tileset.load(file);
        Button add = new Button("Nouvelle tuile"), dup = new Button("Dupliquer"), del = new Button("Supprimer"),
               mh = new Button("Miroir ↔"), mv = new Button("Miroir ↕"), clr = new Button("Effacer"), imp = new Button("Importer PNG…");
        add.setOnAction(e -> { pushUndo(); sel = ts.addTile(); changed(); });
        dup.setOnAction(e -> { pushUndo(); sel = ts.duplicate(sel); changed(); });
        del.setOnAction(e -> {
            if (ts.tiles.size() <= 1) return;
            pushUndo(); ts.tiles.remove(sel); ts.flags.remove(sel);
            sel = Math.min(sel, ts.tiles.size() - 1); changed();
        });
        mh.setOnAction(e -> edit(() -> { for (int[] r : cur()) for (int i = 0; i < r.length / 2; i++) { int t = r[i]; r[i] = r[r.length - 1 - i]; r[r.length - 1 - i] = t; } }));
        mv.setOnAction(e -> edit(() -> Collections.reverse(Arrays.asList(cur()))));
        clr.setOnAction(e -> edit(() -> { for (int[] r : cur()) Arrays.fill(r, 0); }));
        imp.setOnAction(e -> importPng());
        wSpin.getValueFactory().setValue(ts.w); hSpin.getValueFactory().setValue(ts.h);
        wSpin.setPrefWidth(70); hSpin.setPrefWidth(70);
        wSpin.valueProperty().addListener((o, a, b) -> { if (!updating && b != ts.w) { pushUndo(); ts.resize(b, ts.h); changed(); } });
        hSpin.valueProperty().addListener((o, a, b) -> { if (!updating && b != ts.h) { pushUndo(); ts.resize(ts.w, b); changed(); } });
        solid.setOnAction(e -> { if (!updating) { ts.flags.set(sel, (ts.flags.get(sel) & ~1) | (solid.isSelected() ? 1 : 0)); flagsChanged(); } });
        flags.valueProperty().addListener((o, a, b) -> { if (!updating) { ts.flags.set(sel, b); flagsChanged(); } });
        flags.setPrefWidth(80);

        ToolBar tb = new ToolBar(add, dup, del, new Separator(), mh, mv, clr, new Separator(), imp, new Separator(),
                new Label("Taille :"), wSpin, new Label("x"), hSpin);
        ScrollPane left = new ScrollPane(list);
        left.setFitToWidth(true);
        left.setPrefWidth(230);
        list.setPadding(new Insets(6));
        VBox right = new VBox(10, info, solid, new HBox(6, new Label("Attributs :"), flags),
                new Label("Bit 0 = solide (testé par map_solid) ;\nles autres bits sont libres\n(échelle, danger, bonus...)."),
                new Separator(), new Label("Clic gauche : encre choisie\nClic droit : encre 0 (fond)\nCtrl+Z : annuler"));
        right.setPadding(new Insets(10));
        canvas.setOnMousePressed(this::mouse);
        canvas.setOnMouseDragged(this::mouse);
        ScrollPane center = new ScrollPane(new StackPane(canvas));
        center.setFitToWidth(true); center.setFitToHeight(true);
        pens.setPadding(new Insets(6));
        BorderPane root = new BorderPane(center, tb, right, pens, left);
        root.setOnKeyPressed(e -> { if (e.isControlDown() && e.getCode() == KeyCode.Z) undo(); });
        setContent(root);
        refresh();
        dirty.set(false);
    }

    private int[][] cur() { return ts.tiles.get(sel); }
    private int mode() { return project.get().screenMode; }

    /** Modifie de l'exterieur (import dans une carte) : a enregistrer. */
    public void externalChange() { dirty.set(true); refresh(); }

    private void changed() { dirty.set(true); refresh(); onChange.run(); }
    private void flagsChanged() { dirty.set(true); updating = true; solid.setSelected((ts.flags.get(sel) & 1) != 0); flags.getValueFactory().setValue(ts.flags.get(sel)); updating = false; onChange.run(); }
    private void edit(Runnable r) { pushUndo(); r.run(); changed(); }

    private void pushUndo() {
        Tileset c = new Tileset();
        c.w = ts.w; c.h = ts.h;
        for (int i = 0; i < ts.tiles.size(); i++) { c.tiles.add(ts.tiles.get(i)); c.flags.add(ts.flags.get(i)); }
        c.tiles.replaceAll(t -> { int[][] n = new int[t.length][]; for (int y = 0; y < t.length; y++) n[y] = t[y].clone(); return n; });
        undo.push(c);
        if (undo.size() > 50) undo.removeLast();
    }

    private void undo() {
        if (undo.isEmpty()) return;
        Tileset c = undo.pop();
        ts.w = c.w; ts.h = c.h;
        ts.tiles.clear(); ts.tiles.addAll(c.tiles);
        ts.flags.clear(); ts.flags.addAll(c.flags);
        sel = Math.min(sel, ts.tiles.size() - 1);
        changed();
    }

    @Override public void refresh() {
        Project p = project.get();
        Palette pal = palette.get();
        int n = Gfx.inks(mode());
        pen = Math.min(pen, n - 1);
        updating = true;
        int ppb = mode() == 0 ? 2 : mode() == 1 ? 4 : 8;
        ((SpinnerValueFactory.IntegerSpinnerValueFactory) wSpin.getValueFactory()).setAmountToStepBy(ppb);
        wSpin.getValueFactory().setValue(ts.w); hSpin.getValueFactory().setValue(ts.h);
        solid.setSelected((ts.flags.get(sel) & 1) != 0);
        flags.getValueFactory().setValue(ts.flags.get(sel));
        updating = false;
        info.setText("Tuile " + sel + " / " + ts.tiles.size() + "\n" + ts.w + " x " + ts.h + " pixels mode " + mode()
                + "\n= " + (ts.w + ppb - 1) / ppb + " octets x " + ts.h + " lignes");
        list.getChildren().clear();
        for (int i = 0; i < ts.tiles.size(); i++) {
            final int idx = i;
            ImageView iv = new ImageView(Gfx.tile(ts.tiles.get(i), pal, p.target, mode(), 3));
            Label l = new Label(Integer.toString(i), iv);
            l.setContentDisplay(ContentDisplay.TOP);
            l.setStyle("-fx-padding: 2; -fx-border-color: " + (i == sel ? "#e76f51" : "transparent") + "; -fx-border-width: 2;");
            l.setOnMouseClicked(e -> { sel = idx; refresh(); });
            list.getChildren().add(l);
        }
        pens.getChildren().setAll(Gfx.penStrip(pal, p.target, n, pen, i -> { pen = i; refresh(); }, false));
        redraw();
    }

    private double cw() { return 30 * Gfx.aspect(mode()); }
    private double chh() { return 30; }

    private void redraw() {
        int[][] t = cur();
        canvas.setWidth(ts.w * cw());
        canvas.setHeight(ts.h * chh());
        GraphicsContext g = canvas.getGraphicsContext2D();
        Palette pal = palette.get();
        int n = Gfx.inks(mode());
        for (int y = 0; y < ts.h; y++)
            for (int x = 0; x < ts.w; x++) {
                g.setFill(Color.web(Gfx.web(Palette.shown(pal.rgb[t[y][x] % n], project.get().target))));
                g.fillRect(x * cw(), y * chh(), cw(), chh());
            }
        g.setStroke(Color.gray(0.5, 0.5));
        for (int x = 0; x <= ts.w; x++) g.strokeLine(x * cw(), 0, x * cw(), ts.h * chh());
        for (int y = 0; y <= ts.h; y++) g.strokeLine(0, y * chh(), ts.w * cw(), y * chh());
    }

    private void mouse(MouseEvent e) {
        int x = (int) (e.getX() / cw()), y = (int) (e.getY() / chh());
        if (x < 0 || y < 0 || x >= ts.w || y >= ts.h) return;
        if (e.getEventType() == MouseEvent.MOUSE_PRESSED) pushUndo();
        cur()[y][x] = e.getButton() == MouseButton.SECONDARY ? 0 : pen;
        dirty.set(true);
        redraw();
        updateThumb();
    }

    private void updateThumb() {
        if (sel < list.getChildren().size())
            ((ImageView) ((Label) list.getChildren().get(sel)).getGraphic()).setImage(Gfx.tile(cur(), palette.get(), project.get().target, mode(), 3));
        onChange.run();
    }

    /** Decoupe une image en tuiles (encre la plus proche) et ajoute celles qui manquent. */
    private void importPng() {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png", "*.gif", "*.bmp"));
        java.io.File f = fc.showOpenDialog(canvas.getScene().getWindow());
        if (f == null) return;
        try {
            BufferedImage im = ImageIO.read(f);
            pushUndo();
            int added = importImage(ts, im, palette.get(), project.get(), null);
            changed();
            new Alert(Alert.AlertType.INFORMATION, added + " tuile(s) ajoutée(s) (les tuiles identiques ne sont gardées qu'une fois).").showAndWait();
        } catch (Exception ex) {
            new Alert(Alert.AlertType.ERROR, "Image illisible : " + ex.getMessage()).showAndWait();
        }
    }

    /**
     * Decoupe l'image en tuiles de ts.w x ts.h, ajoute les nouvelles au jeu et, si map n'est pas nul,
     * remplit la carte (taille = image / tuile). Renvoie le nombre de tuiles ajoutees.
     */
    public static int importImage(Tileset ts, BufferedImage im, Palette pal, Project p, TileMap map) {
        int n = Gfx.inks(p.screenMode), added = 0;
        int cols = im.getWidth() / ts.w, rows = im.getHeight() / ts.h;
        if (map != null) map.resize(Math.max(1, cols), Math.max(1, rows));
        for (int ty = 0; ty < rows; ty++)
            for (int tx = 0; tx < cols; tx++) {
                int[][] t = new int[ts.h][ts.w];
                for (int y = 0; y < ts.h; y++)
                    for (int x = 0; x < ts.w; x++) {
                        int argb = im.getRGB(tx * ts.w + x, ty * ts.h + y);
                        int rgb = ((argb >> 20) & 15) << 8 | ((argb >> 12) & 15) << 4 | ((argb >> 4) & 15);
                        int best = 0, bd = Integer.MAX_VALUE;
                        for (int i = 0; i < n; i++) {
                            int d = Palette.dist(Palette.shown(pal.rgb[i], p.target), rgb);
                            if (d < bd) { bd = d; best = i; }
                        }
                        t[y][x] = (argb >>> 24) < 128 ? 0 : best;
                    }
                int idx = ts.find(t);
                if (idx < 0 && ts.tiles.size() < 256) { ts.tiles.add(t); ts.flags.add(0); idx = ts.tiles.size() - 1; added++; }
                if (map != null) map.cells[ty][tx] = Math.max(0, idx);
            }
        return added;
    }

    @Override public void save() throws Exception {
        ts.save(file);
        dirty.set(false);
    }
}
