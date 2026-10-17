package fr.gxstudio.ui;

import fr.gxstudio.model.Palette;
import fr.gxstudio.model.Project;
import fr.gxstudio.model.Sprite;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.*;
import java.util.function.*;

/**
 * Editeur d'animation : suite d'images (sprites du projet), delai, mode, apercu anime.
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public class AnimEditor extends EditorTab {
    private final fr.gxstudio.model.Animation anim;
    private final Supplier<Project> project;
    private final Supplier<Palette> palette;
    private final Supplier<Map<String, Sprite>> sprites;
    private final ListView<String> available = new ListView<>(), seq = new ListView<>();
    private final Spinner<Integer> delay = new Spinner<>(1, 255, 6);
    private final ComboBox<String> mode = new ComboBox<>(FXCollections.observableArrayList("boucle", "une fois", "aller-retour"));
    private final ImageView preview = new ImageView();
    private final Label step = new Label();
    private final Timeline timeline = new Timeline();
    private int idx = 0, dir = 1;
    private Map<String, Sprite> cache = Map.of();

    public AnimEditor(Path file, Supplier<Project> project, Supplier<Palette> palette, Supplier<Map<String, Sprite>> sprites) throws Exception {
        super(file);
        this.project = project;
        this.palette = palette;
        this.sprites = sprites;
        anim = fr.gxstudio.model.Animation.load(file);
        available.setCellFactory(lv -> new SpriteCell());
        seq.setCellFactory(lv -> new SpriteCell());
        Button add = new Button("Ajouter →"), up = new Button("Monter"), down = new Button("Descendre"), rem = new Button("Retirer");
        add.setOnAction(e -> addSel());
        available.setOnMouseClicked(e -> { if (e.getClickCount() == 2) addSel(); });
        up.setOnAction(e -> move(-1));
        down.setOnAction(e -> move(1));
        rem.setOnAction(e -> {
            int i = seq.getSelectionModel().getSelectedIndex();
            if (i >= 0) { anim.frames.remove(i); changed(); }
        });
        delay.getValueFactory().setValue(anim.delay);
        delay.valueProperty().addListener((o, a, b) -> { anim.delay = b; changed(); });
        mode.getSelectionModel().select(anim.mode);
        mode.setOnAction(e -> { anim.mode = mode.getSelectionModel().getSelectedIndex(); changed(); });
        delay.setPrefWidth(80);
        VBox left = new VBox(6, new Label("Sprites du projet"), available, add);
        VBox mid = new VBox(6, new Label("Images de l'animation (dans l'ordre)"), seq, new HBox(6, up, down, rem));
        VBox right = new VBox(10, new Label("Délai : images (1/50 s) par étape"), delay, new Label("Mode"), mode,
                new Separator(), new Label("Aperçu (vitesse réelle)"), preview, step,
                new Label("Dans le programme :\n  ld ix,mon_etat (5 octets)\n  ld hl,ANIM_NOM\n  call anim_start\npuis à chaque image :\n  call anim_step  ; A = image\n  (A -> B de spr_set ou D de sw_set)"));
        for (VBox v : List.of(left, mid, right)) v.setPadding(new Insets(10));
        VBox.setVgrow(available, Priority.ALWAYS);
        VBox.setVgrow(seq, Priority.ALWAYS);
        HBox root = new HBox(left, mid, right);
        HBox.setHgrow(mid, Priority.ALWAYS);
        setContent(root);
        timeline.setCycleCount(Timeline.INDEFINITE);
        setOnClosed(e -> timeline.stop());
        refresh();
        dirty.set(false);
    }

    private void addSel() {
        String s = available.getSelectionModel().getSelectedItem();
        if (s != null) { anim.frames.add(s); changed(); }
    }

    private void move(int d) {
        int i = seq.getSelectionModel().getSelectedIndex(), j = i + d;
        if (i < 0 || j < 0 || j >= anim.frames.size()) return;
        Collections.swap(anim.frames, i, j);
        changed();
        seq.getSelectionModel().select(j);
    }

    private void changed() { dirty.set(true); refresh(); }

    @Override public void refresh() {
        cache = sprites.get();
        available.setItems(FXCollections.observableArrayList(new TreeSet<>(cache.keySet())));
        int s = seq.getSelectionModel().getSelectedIndex();
        seq.setItems(FXCollections.observableArrayList(anim.frames));
        if (s >= 0) seq.getSelectionModel().select(Math.min(s, anim.frames.size() - 1));
        idx = 0; dir = 1;
        timeline.stop();
        timeline.getKeyFrames().setAll(new KeyFrame(Duration.millis(20.0 * anim.delay), e -> tick()));
        timeline.play();
        tick();
    }

    private void tick() {
        if (anim.frames.isEmpty()) { preview.setImage(null); step.setText("(vide)"); return; }
        idx = Math.min(idx, anim.frames.size() - 1);
        Sprite sp = cache.get(anim.frames.get(idx));
        boolean plus = project.get().target.plus;
        if (sp != null) preview.setImage(Gfx.sprite(sp, palette.get(), project.get().target, plus ? 8 : 16, 8));
        step.setText("image " + (idx + 1) + " / " + anim.frames.size() + " : " + anim.frames.get(idx));
        int n = anim.frames.size();
        switch (anim.mode) {
            case 0 -> idx = (idx + 1) % n;
            case 1 -> idx = Math.min(idx + 1, n - 1);
            default -> {
                if (n > 1) { idx += dir; if (idx == 0 || idx == n - 1) dir = -dir; }
            }
        }
    }

    private class SpriteCell extends ListCell<String> {
        @Override protected void updateItem(String s, boolean empty) {
            super.updateItem(s, empty);
            if (empty || s == null) { setText(null); setGraphic(null); return; }
            setText(s);
            Sprite sp = cache.get(s);
            setGraphic(sp == null ? new Label("?") : new ImageView(Gfx.sprite(sp, palette.get(), project.get().target, project.get().target.plus ? 2 : 4, 2)));
        }
    }

    @Override public void save() throws Exception {
        anim.save(file);
        dirty.set(false);
    }
}
