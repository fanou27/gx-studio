package fr.gxstudio.ui;

import fr.gxstudio.model.*;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.*;
import javafx.scene.canvas.*;
import javafx.scene.control.*;
import javafx.scene.image.*;
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
 * Editeur de sprite : dessin au pixel (clic gauche = encre choisie, clic droit = transparent),
 * remplissage, pipette, miroirs, decalages, import PNG, apercu a la taille reelle.
 * Sur CPC+/GX4000 : sprite materiel 16x16 (palette des sprites) ; sur CPC : sprite logiciel
 * mode 0 de taille libre (palette de l'ecran, pixels deux fois plus larges que hauts).
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public class SpriteEditor extends EditorTab {
    private Sprite spr;
    private final Supplier<Project> project;
    private final Supplier<Palette> palette;
    private int pen = 1;
    private final Canvas canvas = new Canvas();
    private final ImageView preview = new ImageView();
    private final HBox palBox = new HBox(2);
    private final ToggleGroup tools = new ToggleGroup();
    private final Deque<Sprite> undo = new ArrayDeque<>();
    private final Spinner<Integer> wSpin = new Spinner<>(2, 64, 16, 2), hSpin = new Spinner<>(1, 64, 16);
    private final Label info = new Label();
    private final CheckBox grid = new CheckBox("Grille");

    public SpriteEditor(Path file, Supplier<Project> project, Supplier<Palette> palette) throws Exception {
        super(file);
        this.project = project;
        this.palette = palette;
        spr = Sprite.load(file);

        ToggleButton pencil = new ToggleButton("Crayon"), fill = new ToggleButton("Remplir"), pick = new ToggleButton("Pipette");
        for (ToggleButton b : List.of(pencil, fill, pick)) b.setToggleGroup(tools);
        pencil.setUserData("pencil"); fill.setUserData("fill"); pick.setUserData("pick");
        pencil.setSelected(true);
        Button mh = new Button("Miroir ↔"), mv = new Button("Miroir ↕"), clr = new Button("Effacer"),
               sl = new Button("←"), sr = new Button("→"), su = new Button("↑"), sd = new Button("↓"),
               imp = new Button("Importer PNG…"), sym = new Button("Symétrie gauche→droite");
        mh.setOnAction(e -> edit(() -> { for (int[] r : spr.px) for (int i = 0; i < r.length / 2; i++) { int t = r[i]; r[i] = r[r.length - 1 - i]; r[r.length - 1 - i] = t; } }));
        mv.setOnAction(e -> edit(() -> Collections.reverse(Arrays.asList(spr.px))));
        sym.setOnAction(e -> edit(() -> { for (int[] r : spr.px) for (int i = 0; i < r.length / 2; i++) r[r.length - 1 - i] = r[i]; }));
        clr.setOnAction(e -> edit(() -> { for (int[] r : spr.px) Arrays.fill(r, 0); }));
        sl.setOnAction(e -> shift(-1, 0)); sr.setOnAction(e -> shift(1, 0));
        su.setOnAction(e -> shift(0, -1)); sd.setOnAction(e -> shift(0, 1));
        imp.setOnAction(e -> importPng());
        grid.setSelected(true);
        grid.setOnAction(e -> redraw());
        wSpin.getValueFactory().setValue(spr.w);
        hSpin.getValueFactory().setValue(spr.h);
        wSpin.setPrefWidth(70); hSpin.setPrefWidth(70);
        wSpin.valueProperty().addListener((o, a, b) -> { if (b != spr.w) edit(() -> spr = spr.resized(b, spr.h)); });
        hSpin.valueProperty().addListener((o, a, b) -> { if (b != spr.h) edit(() -> spr = spr.resized(spr.w, b)); });

        ToolBar tb = new ToolBar(pencil, fill, pick, new Separator(), mh, mv, sym, sl, sr, su, sd, clr, new Separator(), imp, grid);
        HBox size = new HBox(6, new Label("Taille :"), wSpin, new Label("x"), hSpin);
        size.setAlignment(Pos.CENTER_LEFT);
        VBox right = new VBox(10, new Label("Aperçu (taille réelle x2)"), preview, size, info,
                new Label("Clic gauche : dessiner\nClic droit : transparent\nCtrl+Z : annuler"));
        right.setPadding(new Insets(10));
        canvas.setOnMousePressed(this::mouse);
        canvas.setOnMouseDragged(this::mouse);
        canvas.setOnMouseReleased(e -> { });
        ScrollPane sp = new ScrollPane(new StackPane(canvas));
        sp.setFitToWidth(true); sp.setFitToHeight(true);
        BorderPane root = new BorderPane(sp, tb, right, palBox, null);
        palBox.setPadding(new Insets(6));
        root.setOnKeyPressed(e -> { if (e.isControlDown() && e.getCode() == KeyCode.Z) undo(); });
        setContent(root);
        refresh();
        dirty.set(false);
    }

    private boolean plus() { return project.get().target.plus; }

    @Override public void refresh() {
        boolean p = plus();
        if (p && spr.w > 16) spr = spr.resized(16, spr.h);
        info.setText(p ? "Sprite matériel : 16 pixels de large au plus,\nplus haut que 16 : images 16x16 empilées\n(palette des sprites, 15 couleurs)" :
                "Sprite logiciel mode 0\n(palette de l'écran ; largeur paire)");
        palBox.getChildren().clear();
        Palette pal = palette.get();
        for (int i = 0; i < 16; i++) {
            final int idx = i;
            StackPane sw = new StackPane();
            sw.setPrefSize(30, 30);
            String col = i == 0 ? "linear-gradient(to bottom right, #ccc 50%, #888 50%)" : web(Palette.shown(pal.rgb[i], project.get().target));
            sw.setStyle("-fx-background-color: " + col + "; -fx-border-color: " + (i == pen ? "#ff0" : "#333") + "; -fx-border-width: " + (i == pen ? 3 : 1) + ";");
            sw.getChildren().add(new Label(i == 0 ? "0" : ""));
            sw.setOnMouseClicked(e -> { pen = idx; refresh(); });
            Tooltip.install(sw, new Tooltip(i == 0 ? "0 : transparent" : "encre " + i));
            palBox.getChildren().add(sw);
        }
        redraw();
    }

    private static String web(int rgb) { return String.format("#%06X", Palette.toRgb24(rgb)); }

    private double cw() { return plus() ? 22 : 34; }
    private double ch() { return plus() ? 22 : 17; }

    private void redraw() {
        double cw = cw(), chh = ch();
        canvas.setWidth(spr.w * cw);
        canvas.setHeight(spr.h * chh);
        GraphicsContext g = canvas.getGraphicsContext2D();
        Palette pal = palette.get();
        Target t = project.get().target;
        for (int y = 0; y < spr.h; y++)
            for (int x = 0; x < spr.w; x++) {
                int v = spr.px[y][x];
                if (v == 0) {
                    g.setFill(((x + y) & 1) == 0 ? Color.gray(0.8) : Color.gray(0.65));
                } else g.setFill(Color.web(web(Palette.shown(pal.rgb[v], t))));
                g.fillRect(x * cw, y * chh, cw, chh);
            }
        if (grid.isSelected()) {
            g.setStroke(Color.gray(0.3, 0.5));
            g.setLineWidth(1);
            for (int x = 0; x <= spr.w; x++) g.strokeLine(x * cw, 0, x * cw, spr.h * chh);
            for (int y = 0; y <= spr.h; y++) g.strokeLine(0, y * chh, spr.w * cw, y * chh);
        }
        // apercu : 1 pixel mode 1 = 2x2 ; sprite materiel en zoom x2/x1 ; mode 0 = 4x2
        int sx = plus() ? 2 : 4, sy = 2;
        WritableImage im = new WritableImage(spr.w * sx, spr.h * sy);
        PixelWriter pw = im.getPixelWriter();
        for (int y = 0; y < spr.h * sy; y++)
            for (int x = 0; x < spr.w * sx; x++) {
                int v = spr.px[y / sy][x / sx];
                pw.setArgb(x, y, v == 0 ? 0xFF000010 : 0xFF000000 | Palette.toRgb24(Palette.shown(pal.rgb[v], t)));
            }
        preview.setImage(im);
    }

    private void mouse(MouseEvent e) {
        int x = (int) (e.getX() / cw()), y = (int) (e.getY() / ch());
        if (x < 0 || y < 0 || x >= spr.w || y >= spr.h) return;
        String tool = (String) tools.getSelectedToggle().getUserData();
        int v = e.getButton() == MouseButton.SECONDARY ? 0 : pen;
        if (tool.equals("pick")) { pen = spr.px[y][x]; refresh(); return; }
        if (e.getEventType() == MouseEvent.MOUSE_PRESSED) pushUndo();
        if (tool.equals("fill")) { if (e.getEventType() == MouseEvent.MOUSE_PRESSED) flood(x, y, spr.px[y][x], v); }
        else spr.px[y][x] = v;
        dirty.set(true);
        redraw();
    }

    private void flood(int x, int y, int from, int to) {
        if (from == to) return;
        Deque<int[]> q = new ArrayDeque<>();
        q.add(new int[]{x, y});
        while (!q.isEmpty()) {
            int[] c = q.poll();
            if (c[0] < 0 || c[1] < 0 || c[0] >= spr.w || c[1] >= spr.h || spr.px[c[1]][c[0]] != from) continue;
            spr.px[c[1]][c[0]] = to;
            q.add(new int[]{c[0] + 1, c[1]}); q.add(new int[]{c[0] - 1, c[1]});
            q.add(new int[]{c[0], c[1] + 1}); q.add(new int[]{c[0], c[1] - 1});
        }
    }

    private void shift(int dx, int dy) {
        edit(() -> {
            Sprite n = new Sprite(spr.w, spr.h);
            for (int y = 0; y < spr.h; y++)
                for (int x = 0; x < spr.w; x++)
                    n.px[(y + dy + spr.h) % spr.h][(x + dx + spr.w) % spr.w] = spr.px[y][x];
            spr = n;
        });
    }

    private void pushUndo() { undo.push(spr.copy()); if (undo.size() > 50) undo.removeLast(); }

    private void undo() {
        if (undo.isEmpty()) return;
        spr = undo.pop();
        wSpin.getValueFactory().setValue(spr.w);
        hSpin.getValueFactory().setValue(spr.h);
        dirty.set(true);
        redraw();
    }

    private void edit(Runnable r) { pushUndo(); r.run(); dirty.set(true); redraw(); }

    /** Import d'une image : chaque pixel prend l'encre la plus proche (transparence = 0). */
    private void importPng() {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png", "*.gif", "*.bmp"));
        java.io.File f = fc.showOpenDialog(canvas.getScene().getWindow());
        if (f == null) return;
        try {
            BufferedImage im = ImageIO.read(f);
            int w = Math.min(plus() ? 16 : 64, im.getWidth() + (im.getWidth() & 1)), h = Math.min(64, im.getHeight());
            Palette pal = palette.get();
            Target t = project.get().target;
            edit(() -> {
                spr = new Sprite(w, h);
                for (int y = 0; y < h && y < im.getHeight(); y++)
                    for (int x = 0; x < w && x < im.getWidth(); x++) {
                        int argb = im.getRGB(x, y);
                        if ((argb >>> 24) < 128) continue;
                        int rgb = ((argb >> 20) & 15) << 8 | ((argb >> 12) & 15) << 4 | ((argb >> 4) & 15);
                        int best = 1, bd = Integer.MAX_VALUE;
                        for (int i = 1; i < 16; i++) {
                            int d = Palette.dist(Palette.shown(pal.rgb[i], t), rgb);
                            if (d < bd) { bd = d; best = i; }
                        }
                        spr.px[y][x] = best;
                    }
            });
            wSpin.getValueFactory().setValue(spr.w);
            hSpin.getValueFactory().setValue(spr.h);
        } catch (Exception ex) {
            new Alert(Alert.AlertType.ERROR, "Image illisible : " + ex.getMessage()).showAndWait();
        }
    }

    public Sprite sprite() { return spr; }

    @Override public void save() throws Exception {
        spr.save(file);
        dirty.set(false);
    }

    /** Image du sprite (pour l'arbre du projet ou la documentation). */
    public static BufferedImage thumbnail(Sprite s, Palette pal, Target t) {
        BufferedImage im = new BufferedImage(s.w, s.h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < s.h; y++)
            for (int x = 0; x < s.w; x++)
                if (s.px[y][x] != 0) im.setRGB(x, y, 0xFF000000 | Palette.toRgb24(Palette.shown(pal.rgb[s.px[y][x]], t)));
        return im;
    }
}
