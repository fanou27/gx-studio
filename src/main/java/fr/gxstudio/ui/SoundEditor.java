package fr.gxstudio.ui;

import fr.gxstudio.gen.*;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.*;
import org.fxmisc.richtext.model.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Editeur de musique / bruitage (.snd) avec ecoute immediate (emulation du PSG).
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public class SoundEditor extends EditorTab {
    private final CodeArea area = new CodeArea();
    private final Label status = new Label();
    private final AySynth synth = new AySynth();
    private static final Pattern P = Pattern.compile(
            "(?<C>;[^\n]*)|(?<V>^\\s*[ABCabc]\\s*:)|(?<K>\\b(vitesse|canal|duree|boucle|volume|relache|bruit|TONE|VOL|NOISE|LOAD|PAUSE|REPEAT|LOOP|STOP)\\b)|(?<N>\\b[A-Ga-g]#?[0-9]\\b)",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    public SoundEditor(Path file) throws Exception {
        super(file);
        area.replaceText(Files.readString(file, StandardCharsets.UTF_8));
        area.setParagraphGraphicFactory(LineNumberFactory.get(area));
        area.getStyleClass().add("asm");
        area.textProperty().addListener((o, a, b) -> { dirty.set(true); area.setStyleSpans(0, hl(b)); });
        area.setStyleSpans(0, hl(area.getText()));
        Button play = new Button("▶ Écouter"), stop = new Button("■ Stop"), check = new Button("Vérifier");
        play.setOnAction(e -> play());
        stop.setOnAction(e -> synth.stop());
        check.setOnAction(e -> check());
        Label help = new Label("Musique : « A: A4 C5 E5*2 - . »  (note+octave, *n / /n durée, - silence, . prolonge) ; "
                + "réglages : vitesse, duree, boucle, volume, relache, bruit.\n"
                + "Bruitage : TONE B 60 / VOL B 13 / NOISE 10 / PAUSE n / REPEAT n / LOOP / STOP (instructions DMA de l'ASIC).");
        help.setWrapText(true);
        ToolBar tb = new ToolBar(play, stop, check, new Separator(), status);
        VBox top = new VBox(tb, help);
        help.setPadding(new Insets(4, 8, 4, 8));
        setContent(new BorderPane(new VirtualizedScrollPane<>(area), top, null, null, null));
        setOnClosed(e -> synth.stop());
        check();
        dirty.set(false);
    }

    private SoundCompiler.Result check() {
        SoundCompiler.Result r = SoundCompiler.compile(area.getText());
        status.setText(r.errors.isEmpty()
                ? r.words.size() + " instructions (" + 2 * r.words.size() + " octets), canal " + r.channel + ", vitesse " + r.speed
                : "Erreur : " + String.join(" ; ", r.errors));
        status.setStyle(r.errors.isEmpty() ? "" : "-fx-text-fill: #c00;");
        return r;
    }

    private void play() {
        SoundCompiler.Result r = check();
        synth.stop();
        short[] pcm = AySynth.render(r.words, r.speed, 30);
        synth.play(pcm);
    }

    static StyleSpans<Collection<String>> hl(String t) {
        Matcher m = P.matcher(t);
        int last = 0;
        StyleSpansBuilder<Collection<String>> sb = new StyleSpansBuilder<>();
        while (m.find()) {
            String c = m.group("C") != null ? "comment" : m.group("V") != null ? "label" : m.group("K") != null ? "keyword" : "number";
            sb.add(Collections.emptyList(), m.start() - last);
            sb.add(Collections.singleton(c), m.end() - m.start());
            last = m.end();
        }
        sb.add(Collections.emptyList(), t.length() - last);
        return sb.create();
    }

    @Override public void save() throws Exception {
        Files.writeString(file, area.getText(), StandardCharsets.UTF_8);
        dirty.set(false);
    }
}
