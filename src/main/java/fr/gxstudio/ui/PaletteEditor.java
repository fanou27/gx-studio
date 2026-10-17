package fr.gxstudio.ui;

import fr.gxstudio.model.*;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Editeur de palette (16 encres). CPC+/GX4000 : 4096 couleurs (R, V, B de 0 a 15).
 * CPC : choix parmi les 27 couleurs du Gate Array (numeros firmware 0-26).
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public class PaletteEditor extends EditorTab {
    private final Palette pal;
    private final Supplier<Project> project;
    private final Runnable onChange;
    private int sel = 1;
    private final GridPane swatches = new GridPane();
    private final VBox chooser = new VBox(8);
    private final Slider r = slider(), g = slider(), b = slider();
    private final Label value = new Label();
    private boolean updating;

    public PaletteEditor(Path file, Supplier<Project> project, Runnable onChange) throws Exception {
        super(file);
        this.project = project;
        this.onChange = onChange;
        pal = Palette.load(file);
        swatches.setHgap(4); swatches.setVgap(4);
        for (Slider s : new Slider[]{r, g, b})
            s.valueProperty().addListener((o, x, y) -> {
                if (updating) return;
                pal.rgb[sel] = (int) r.getValue() << 8 | (int) g.getValue() << 4 | (int) b.getValue();
                changed();
            });
        boolean spr = file.getFileName().toString().startsWith("sprites");
        Label help = new Label(spr ? "Palette des sprites matériels : couleurs 1 à 15 (0 = transparent)."
                : "Palette de l'écran : encres 0 à 15 (mode 1 : encres 0 à 3 ; mode 0 : les 16).\n"
                + "Sur CPC, les sprites logiciels utilisent aussi cette palette.");
        VBox root = new VBox(12, help, swatches, new Separator(), chooser);
        root.setPadding(new Insets(12));
        setContent(new ScrollPane(root));
        refresh();
        dirty.set(false);
    }

    private static Slider slider() {
        Slider s = new Slider(0, 15, 0);
        s.setMajorTickUnit(5); s.setMinorTickCount(4); s.setSnapToTicks(true);
        s.setShowTickLabels(true); s.setShowTickMarks(true); s.setPrefWidth(320);
        return s;
    }

    private void changed() { dirty.set(true); drawSwatches(); onChange.run(); }

    @Override public void refresh() {
        drawSwatches();
        chooser.getChildren().clear();
        Target t = project.get().target;
        if (t.plus) {
            chooser.getChildren().addAll(new Label("Encre " + sel + " :"), row("Rouge", r), row("Vert", g), row("Bleu", b), value);
            updating = true;
            r.setValue((pal.rgb[sel] >> 8) & 15); g.setValue((pal.rgb[sel] >> 4) & 15); b.setValue(pal.rgb[sel] & 15);
            updating = false;
        } else {
            GridPane cpc = new GridPane();
            cpc.setHgap(3); cpc.setVgap(3);
            for (int n = 0; n < 27; n++) {
                final int fw = n;
                Button c = new Button(Integer.toString(n));
                c.setPrefSize(46, 34);
                int rgb = Palette.firmwareToRgb(n);
                boolean light = ((rgb >> 8) & 15) + ((rgb >> 4) & 15) * 2 + (rgb & 15) > 20;
                c.setStyle("-fx-background-color: " + String.format("#%06X", Palette.toRgb24(rgb)) + "; -fx-text-fill: " + (light ? "black" : "white") + ";"
                        + (Palette.rgbToFirmware(pal.rgb[sel]) == n ? "-fx-border-color: yellow; -fx-border-width: 3;" : ""));
                c.setOnAction(e -> { pal.rgb[sel] = Palette.firmwareToRgb(fw); changed(); refresh(); });
                cpc.add(c, n % 9, n / 9);
            }
            chooser.getChildren().addAll(new Label("Encre " + sel + " : couleur du CPC (numéro firmware, comme INK " + sel + ",n en BASIC)"), cpc);
        }
    }

    private static HBox row(String name, Slider s) {
        Label l = new Label(name);
        l.setMinWidth(60);
        HBox h = new HBox(8, l, s);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private void drawSwatches() {
        swatches.getChildren().clear();
        Target t = project.get().target;
        for (int i = 0; i < 16; i++) {
            final int idx = i;
            int shown = Palette.shown(pal.rgb[i], t);
            Button sw = new Button(i + "\n" + (t.plus ? String.format("#%03X", pal.rgb[i]) : "fw " + Palette.rgbToFirmware(pal.rgb[i])));
            boolean light = ((shown >> 8) & 15) + ((shown >> 4) & 15) * 2 + (shown & 15) > 20;
            sw.setPrefSize(64, 52);
            sw.setStyle("-fx-background-color: " + String.format("#%06X", Palette.toRgb24(shown)) + "; -fx-text-fill: " + (light ? "black" : "white")
                    + "; -fx-border-color: " + (i == sel ? "yellow" : "#444") + "; -fx-border-width: " + (i == sel ? 3 : 1) + ";");
            sw.setOnAction(e -> { sel = idx; refresh(); });
            swatches.add(sw, i % 8, i / 8);
        }
        value.setText(String.format("Valeur ASIC (&0GRB) : &%04X", Palette.asicWord(pal.rgb[sel])));
    }

    public Palette palette() { return pal; }

    @Override public void save() throws Exception {
        pal.save(file);
        dirty.set(false);
    }
}
