package fr.gxstudio.ui;

import fr.gxstudio.model.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;

/**
 * Rendus communs (tuiles, sprites) avec les proportions de pixels de la machine.
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public final class Gfx {
    private Gfx() { }

    /** Largeur d'un pixel a l'ecran pour un mode (mode 0 : pixels deux fois plus larges). */
    public static int aspect(int mode) { return mode == 0 ? 2 : 1; }

    /** Nombre d'encres du mode ecran. */
    public static int inks(int mode) { return mode == 0 ? 16 : mode == 1 ? 4 : 2; }

    public static int argb(Palette pal, int pen, Target t) {
        return 0xFF000000 | Palette.toRgb24(Palette.shown(pal.rgb[pen & 15], t));
    }

    /** Image d'une tuile (encre 0 = couleur de fond, pas de transparence). */
    public static WritableImage tile(int[][] px, Palette pal, Target t, int mode, int scale) {
        int h = px.length, w = px[0].length, sx = scale * aspect(mode);
        WritableImage im = new WritableImage(Math.max(1, w * sx), Math.max(1, h * scale));
        PixelWriter pw = im.getPixelWriter();
        int n = inks(mode);
        for (int y = 0; y < h * scale; y++)
            for (int x = 0; x < w * sx; x++) pw.setArgb(x, y, argb(pal, px[y / scale][x / sx] % n, t));
        return im;
    }

    /** Image d'un sprite (0 = transparent, montre en damier sombre). */
    public static WritableImage sprite(Sprite s, Palette pal, Target t, int sx, int sy) {
        WritableImage im = new WritableImage(s.w * sx, s.h * sy);
        PixelWriter pw = im.getPixelWriter();
        for (int y = 0; y < s.h * sy; y++)
            for (int x = 0; x < s.w * sx; x++) {
                int v = s.px[y / sy][x / sx];
                pw.setArgb(x, y, v == 0 ? (((x / 4 + y / 4) & 1) == 0 ? 0xFF202430 : 0xFF2A2F3D) : argb(pal, v, t));
            }
        return im;
    }

    public static String web(int rgb) { return String.format("#%06X", Palette.toRgb24(rgb)); }

    /** Bandeau d'encres cliquable (selection par clic gauche). */
    public static HBox penStrip(Palette pal, Target t, int n, int sel, java.util.function.IntConsumer onPick, boolean zeroTransparent) {
        HBox box = new HBox(2);
        for (int i = 0; i < n; i++) {
            final int idx = i;
            StackPane sw = new StackPane();
            sw.setPrefSize(28, 28);
            String col = i == 0 && zeroTransparent ? "linear-gradient(to bottom right, #ccc 50%, #888 50%)" : web(Palette.shown(pal.rgb[i], t));
            sw.setStyle("-fx-background-color: " + col + "; -fx-border-color: " + (i == sel ? "#ff0" : "#333") + "; -fx-border-width: " + (i == sel ? 3 : 1) + ";");
            sw.setOnMouseClicked(e -> onPick.accept(idx));
            javafx.scene.control.Tooltip.install(sw, new javafx.scene.control.Tooltip("encre " + i));
            box.getChildren().add(sw);
        }
        return box;
    }
}
