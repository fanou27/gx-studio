package fr.gxstudio.ui;

import fr.gxstudio.gen.AssetCompiler;
import fr.gxstudio.model.*;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Apercu d'une image PNG et de sa conversion dans le mode ecran du projet.
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public class ImageViewer extends EditorTab {
    private final Supplier<Project> project;
    private final Supplier<Palette> palette;
    private final ImageView orig = new ImageView(), conv = new ImageView();
    private final Label info = new Label();

    public ImageViewer(Path file, Supplier<Project> project, Supplier<Palette> palette) throws Exception {
        super(file);
        this.project = project;
        this.palette = palette;
        Label help = new Label("Les images de assets/images sont converties à la compilation (encre la plus proche) :\n"
                + "étiquettes IMG_NOM, IMG_NOM_W (octets), IMG_NOM_H (lignes). Sur CPC 128 Ko et plus, nommer\n"
                + "l'image nom@4.png pour la ranger dans la banque 4 (chargée au démarrage, voir bank_set).");
        VBox v = new VBox(10, help, info, new HBox(20, new VBox(4, new Label("Original"), orig), new VBox(4, new Label("Converti"), conv)));
        v.setPadding(new Insets(12));
        setContent(new ScrollPane(v));
        refresh();
    }

    @Override public void refresh() {
        try {
            BufferedImage im = ImageIO.read(file.toFile());
            Project p = project.get();
            int mode = p.screenMode, sx = mode == 0 ? 4 : mode == 1 ? 2 : 1, ncol = mode == 0 ? 16 : mode == 1 ? 4 : 2;
            Palette pal = palette.get();
            orig.setImage(scale(im, sx, 2, null, ncol, p.target));
            conv.setImage(scale(im, sx, 2, pal, ncol, p.target));
            int ppb = mode == 0 ? 2 : mode == 1 ? 4 : 8;
            info.setText(String.format("%d x %d pixels -> %d octets x %d lignes = %d octets (mode %d, %d couleurs)",
                    im.getWidth(), im.getHeight(), (im.getWidth() + ppb - 1) / ppb, im.getHeight(),
                    (im.getWidth() + ppb - 1) / ppb * im.getHeight(), mode, ncol));
        } catch (Exception e) {
            info.setText("Image illisible : " + e.getMessage());
        }
    }

    private static Image scale(BufferedImage im, int sx, int sy, Palette pal, int ncol, Target t) {
        WritableImage w = new WritableImage(im.getWidth() * sx, im.getHeight() * sy);
        PixelWriter pw = w.getPixelWriter();
        for (int y = 0; y < im.getHeight(); y++)
            for (int x = 0; x < im.getWidth(); x++) {
                int argb = im.getRGB(x, y);
                if (pal != null) {
                    int rgb = ((argb >> 20) & 15) << 8 | ((argb >> 12) & 15) << 4 | ((argb >> 4) & 15);
                    int best = 0, bd = Integer.MAX_VALUE;
                    for (int i = 0; i < ncol; i++) {
                        int d = Palette.dist(Palette.shown(pal.rgb[i], t), rgb);
                        if (d < bd) { bd = d; best = i; }
                    }
                    argb = (argb >>> 24) < 128 ? 0xFF000000 | Palette.toRgb24(Palette.shown(pal.rgb[0], t))
                            : 0xFF000000 | Palette.toRgb24(Palette.shown(pal.rgb[best], t));
                }
                for (int j = 0; j < sy; j++) for (int i = 0; i < sx; i++) pw.setArgb(x * sx + i, y * sy + j, argb);
            }
        return w;
    }

    @Override public void save() { }
}
