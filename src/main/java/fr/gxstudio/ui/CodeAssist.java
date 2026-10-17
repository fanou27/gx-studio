package fr.gxstudio.ui;

import fr.gxstudio.ui.SymbolIndex.Kind;
import fr.gxstudio.ui.SymbolIndex.Param;
import fr.gxstudio.ui.SymbolIndex.Sym;
import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.text.*;
import javafx.stage.Popup;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.event.MouseOverTextEvent;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * Aide a la saisie de l'editeur de code, comme dans un IDE moderne :
 *  - Ctrl+Espace : liste de completion (fonctions et routines de la bibliotheque, ressources,
 *    symboles du projet, instructions Z80 / mots du C), avec la documentation a cote ;
 *  - parametres : en C, en tapant "(" ou "," (ou Ctrl+Maj+Espace) une bulle montre la
 *    signature de la fonction appelee, le parametre en cours en gras et son explication ;
 *  - survol : la souris arretee sur une fonction / routine de la bibliotheque affiche son aide.
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
final class CodeAssist {
    private final CodeArea area;
    private final Path file;
    private final boolean cMode;

    private final Popup completion = new Popup(), hint = new Popup(), hover = new Popup();
    private final ListView<Sym> list = new ListView<>();
    private final VBox docBox = new VBox(4);
    private final ScrollPane docScroll = new ScrollPane(docBox);
    private List<Sym> symbols = List.of();
    /** position du debut du mot en cours de completion */
    private int wordStart = -1;

    private static final String POPUP_STYLE = "-fx-background-color: #fbfbf7; -fx-border-color: #9aa0a6; -fx-border-width: 1; "
            + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 10, 0, 2, 3);";
    private static final String MONO = "DejaVu Sans Mono";

    CodeAssist(CodeArea area, Path file, boolean cMode) {
        this.area = area;
        this.file = file;
        this.cMode = cMode;

        // ---- liste de completion + documentation a droite
        list.setPrefSize(330, 240);
        list.setFocusTraversable(false);
        list.setCellFactory(v -> new SymCell());
        list.setStyle("-fx-font-family: '" + MONO + "'; -fx-font-size: 12px;");
        list.getSelectionModel().selectedItemProperty().addListener((o, a, s) -> showDoc(s));
        list.setOnMouseClicked(e -> { if (e.getClickCount() == 2) accept(); });
        docBox.setPadding(new Insets(8));
        docBox.setPrefWidth(400);
        docScroll.setFitToWidth(true);
        docScroll.setPrefSize(420, 240);
        docScroll.setFocusTraversable(false);
        docScroll.setStyle("-fx-background: #fbfbf7; -fx-background-color: #fbfbf7;");
        HBox box = new HBox(list, docScroll);
        box.setStyle(POPUP_STYLE);
        completion.getContent().add(box);
        // pas de fermeture automatique (elle se declenche a tort sous certains gestionnaires de
        // fenetres) : on ferme nous-memes (Echap, choix, clic, curseur hors du mot, fenetre quittee)
        completion.setAutoHide(false);
        completion.setOnHidden(e -> wordStart = -1);

        hint.setAutoHide(false);
        hover.setAutoHide(false);
        area.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> hideAll());
        area.sceneProperty().addListener((o, a, sc) -> {
            if (sc != null) sc.windowProperty().addListener((o2, w0, w) -> {
                if (w != null) w.focusedProperty().addListener((o3, x, f) -> { if (!f) Platform.runLater(this::focusLost); });
            });
        });

        // ---- clavier : les touches de navigation vont a la liste tant qu'elle est ouverte
        area.addEventFilter(KeyEvent.KEY_PRESSED, this::keyPressed);
        area.addEventFilter(KeyEvent.KEY_TYPED, e -> {
            if (e.isControlDown() && " ".equals(e.getCharacter())) { e.consume(); return; }   // Ctrl+Espace n'ecrit rien
            String ch = e.getCharacter();
            if (cMode && ("(".equals(ch) || ",".equals(ch))) Platform.runLater(() -> paramHint(false));
            if (cMode && (")".equals(ch) || ";".equals(ch))) Platform.runLater(() -> paramHint(true));
        });
        area.caretPositionProperty().addListener((o, a, b) -> Platform.runLater(this::caretMoved));
        area.focusedProperty().addListener((o, a, f) -> { if (!f) Platform.runLater(this::focusLost); });
        area.addEventFilter(ScrollEvent.SCROLL, e -> hideAll());

        // ---- survol
        area.setMouseOverTextDelay(Duration.ofMillis(600));
        area.addEventHandler(MouseOverTextEvent.MOUSE_OVER_TEXT_BEGIN, e -> hoverAt(e.getCharacterIndex(), e.getScreenPosition()));
        area.addEventHandler(MouseOverTextEvent.MOUSE_OVER_TEXT_END, e -> hover.hide());
    }

    // ================================================================ clavier

    private void keyPressed(KeyEvent e) {
        if (hover.isShowing() && !e.getCode().isModifierKey()) hover.hide();
        if (e.getCode() == KeyCode.SPACE && e.isShortcutDown()) {
            if (e.isShiftDown()) helpAtCaret(); else complete();
            e.consume();
            return;
        }
        if (completion.isShowing()) {
            switch (e.getCode()) {
                case UP -> { move(-1); e.consume(); }
                case DOWN -> { move(1); e.consume(); }
                case PAGE_UP -> { move(-8); e.consume(); }
                case PAGE_DOWN -> { move(8); e.consume(); }
                case ENTER, TAB -> { accept(); e.consume(); }
                case ESCAPE -> { completion.hide(); e.consume(); }
                case LEFT, RIGHT, HOME, END -> completion.hide();
                default -> { }
            }
            return;
        }
        if (e.getCode() == KeyCode.ESCAPE && (hint.isShowing() || hover.isShowing())) { hint.hide(); hover.hide(); e.consume(); }
    }

    private void move(int d) {
        int n = list.getItems().size();
        if (n == 0) return;
        int i = Math.max(0, Math.min(n - 1, list.getSelectionModel().getSelectedIndex() + d));
        list.getSelectionModel().select(i);
        list.scrollTo(Math.max(0, i - 3));
    }

    private void caretMoved() {
        if (completion.isShowing()) {
            int c = area.getCaretPosition();
            if (c < wordStart || c > wordStart + 80) { completion.hide(); return; }
            String w = area.getText(wordStart, c);
            for (int i = 0; i < w.length(); i++) if (!isWord(w.charAt(i))) { completion.hide(); return; }
            filter(w);
        }
        if (hint.isShowing()) paramHint(true);
    }

    // ================================================================ completion

    /** Ctrl+Espace : ouvre la liste (ou complete directement s'il n'y a qu'un choix). */
    void complete() {
        hover.hide();
        symbols = SymbolIndex.forFile(file, area.getText(), cMode);
        int c = area.getCaretPosition();
        String t = area.getText();
        int a = c;
        while (a > 0 && isWord(t.charAt(a - 1))) a--;
        wordStart = a;
        String prefix = t.substring(a, c);
        List<Sym> res = rankForContext(SymbolIndex.complete(symbols, prefix, !cMode), a);
        if (res.isEmpty()) { wordStart = -1; return; }
        if (res.size() == 1 && !prefix.isEmpty()) { list.getItems().setAll(res); list.getSelectionModel().select(0); accept(); return; }
        list.getItems().setAll(res);
        list.getSelectionModel().select(0);
        list.scrollTo(0);
        caretBounds().ifPresent(b -> {
            if (!completion.isShowing()) completion.show(area, b.getMinX() - 4, b.getMaxY() + 2);
            fitOnScreen(completion, b);
        });
    }

    private void filter(String prefix) {
        List<Sym> res = rankForContext(SymbolIndex.complete(symbols, prefix, !cMode), wordStart);
        if (res.isEmpty()) { completion.hide(); return; }
        Sym sel = list.getSelectionModel().getSelectedItem();
        list.getItems().setAll(res);
        int i = sel != null ? res.indexOf(sel) : -1;
        list.getSelectionModel().select(prefix.isEmpty() || i < 0 ? 0 : i);
        if (i < 0) list.scrollTo(0);
    }

    /**
     * Dans les parametres d'un appel (C), une fonction qui ne renvoie rien (void) ne peut pas
     * servir de valeur : elle passe en fin de liste. En assembleur, apres "call" ou "jp",
     * les routines et etiquettes passent devant les instructions.
     */
    private List<Sym> rankForContext(List<Sym> res, int start) {
        String t = area.getText();
        if (cMode) {
            if (!insideCall(t, start)) return res;
            List<Sym> a = new ArrayList<>(), b = new ArrayList<>();
            for (Sym s : res) (s.kind() == Kind.FUNCTION && s.sig() != null && s.sig().startsWith("void ") ? b : a).add(s);
            a.addAll(b);
            return a;
        }
        int ls = t.lastIndexOf('\n', Math.max(0, start - 1)) + 1;
        String before = t.substring(ls, Math.max(ls, start)).trim().toLowerCase(Locale.ROOT);
        if (!before.matches("(.*\\s)?(call|jp|jr|djnz)(\\s+\\w+\\s*,)?")) return res;
        List<Sym> a = new ArrayList<>(), b = new ArrayList<>();
        for (Sym s : res) (s.kind() == Kind.ROUTINE || s.kind() == Kind.LABEL ? a : b).add(s);
        a.addAll(b);
        return a;
    }

    private static boolean insideCall(String t, int pos) {
        int depth = 0;
        for (int i = pos - 1; i >= 0 && pos - i < 3000; i--) {
            char ch = t.charAt(i);
            if (ch == ')') depth++;
            else if (ch == '(') { if (depth == 0) return true; depth--; }
            else if (ch == ';' || ch == '{' || ch == '}') return false;
        }
        return false;
    }

    /** Remplace le mot commence par le symbole choisi. */
    private void accept() {
        Sym s = list.getSelectionModel().getSelectedItem();
        int c = area.getCaretPosition();
        int a = wordStart >= 0 ? wordStart : c;
        completion.hide();
        if (s == null) return;
        String text = area.getText();
        // le reste du mot apres le curseur est remplace aussi
        int b = c;
        while (b < text.length() && isWord(text.charAt(b))) b++;
        String name = s.name();
        if (name.startsWith("#") && a > 0 && text.charAt(a - 1) == '#') name = name.substring(1);
        if (!cMode && (s.kind() == Kind.INSTRUCTION || s.kind() == Kind.DIRECTIVE)) {
            String typed = text.substring(a, c);
            if (!typed.isEmpty() && typed.equals(typed.toUpperCase(Locale.ROOT)) && !typed.equals(typed.toLowerCase(Locale.ROOT)))
                name = name.toUpperCase(Locale.ROOT);   // l'utilisateur ecrit en majuscules
        }
        String after = b < text.length() ? text.substring(b, Math.min(text.length(), b + 1)) : "";
        boolean call = cMode && s.callable() && (s.kind() == Kind.FUNCTION || s.kind() == Kind.MACRO) && !after.equals("(");
        String ins = call ? name + "()" : name;
        area.replaceText(a, b, ins);
        if (call) {
            if (s.params().isEmpty()) area.moveTo(a + ins.length());
            else { area.moveTo(a + ins.length() - 1); Platform.runLater(() -> paramHint(false)); }
        } else area.moveTo(a + ins.length());
        wordStart = -1;
    }

    private void showDoc(Sym s) {
        docBox.getChildren().clear();
        if (s != null) docBox.getChildren().addAll(docNodes(s, -1, true));
        docScroll.setVvalue(0);
    }

    // ================================================================ parametres (C)

    /**
     * Bulle des parametres de la fonction dont on ecrit l'appel.
     * @param updateOnly ne fait que mettre a jour (ou fermer) une bulle deja ouverte
     */
    void paramHint(boolean updateOnly) {
        if (!cMode || updateOnly && !hint.isShowing()) return;
        String t = area.getText();
        int c = area.getCaretPosition();
        int depth = 0, commas = 0, open = -1;
        for (int i = c - 1; i >= 0 && c - i < 3000; i--) {
            char ch = t.charAt(i);
            if (ch == ')') depth++;
            else if (ch == '(') { if (depth == 0) { open = i; break; } depth--; }
            else if (ch == ',' && depth == 0) commas++;
            else if (ch == ';' || ch == '{' || ch == '}') break;
        }
        if (open < 0) { hint.hide(); return; }
        int e = open;
        while (e > 0 && t.charAt(e - 1) == ' ') e--;
        int a = e;
        while (a > 0 && isWord(t.charAt(a - 1))) a--;
        String name = t.substring(a, e);
        if (symbols.isEmpty() || SymbolIndex.find(symbols, name, false) == null) symbols = SymbolIndex.forFile(file, t, cMode);
        Sym s = SymbolIndex.find(symbols, name, false);
        if (s == null || !s.callable()) { hint.hide(); return; }
        VBox v = new VBox(3);
        v.setPadding(new Insets(6, 8, 6, 8));
        v.setStyle(POPUP_STYLE);
        v.setMaxWidth(640);
        v.getChildren().addAll(docNodes(s, commas, false));
        hint.getContent().setAll(v);
        caretBounds().ifPresent(b -> {
            if (!hint.isShowing()) hint.show(area, b.getMinX(), b.getMinY() - 60);
            Platform.runLater(() -> {   // au-dessus de la ligne, une fois la taille connue
                hint.setX(Math.max(0, b.getMinX() - 8));
                hint.setY(b.getMinY() - hint.getHeight() - 4);
            });
        });
    }

    // ================================================================ aide (Ctrl+Maj+Espace, survol)

    /** Ctrl+Maj+Espace : parametres si l'on est dans un appel (C), sinon aide du mot sous le curseur. */
    void helpAtCaret() {
        completion.hide();
        if (cMode) {
            paramHint(false);
            if (hint.isShowing()) return;
        }
        int c = area.getCaretPosition();
        Sym s = symbolAt(c);
        if (s == null) return;
        caretBounds().ifPresent(b -> showHover(s, new Point2D(b.getMinX(), b.getMaxY() + 4)));
    }

    private void hoverAt(int index, Point2D screen) {
        if (completion.isShowing() || hint.isShowing()) return;
        Sym s = symbolAt(index);
        if (s == null || s.kind() == Kind.KEYWORD || s.kind() == Kind.INSTRUCTION || s.kind() == Kind.DIRECTIVE) return;
        if (!s.documented() && s.kind() != Kind.FUNCTION && s.kind() != Kind.ROUTINE) return;
        showHover(s, new Point2D(screen.getX() + 10, screen.getY() + 14));
    }

    private Sym symbolAt(int index) {
        String t = area.getText();
        if (index < 0 || index > t.length()) return null;
        int a = index, b = index;
        while (a > 0 && isWord(t.charAt(a - 1))) a--;
        while (b < t.length() && isWord(t.charAt(b))) b++;
        if (a == b) return null;
        String w = t.substring(a, b);
        if (symbols.isEmpty()) symbols = SymbolIndex.forFile(file, t, cMode);
        Sym s = SymbolIndex.find(symbols, w, !cMode);
        if (s == null) { symbols = SymbolIndex.forFile(file, t, cMode); s = SymbolIndex.find(symbols, w, !cMode); }
        return s;
    }

    private void showHover(Sym s, Point2D at) {
        VBox v = new VBox(4);
        v.setPadding(new Insets(8));
        v.setStyle(POPUP_STYLE);
        v.setMaxWidth(520);
        v.getChildren().addAll(docNodes(s, -1, true));
        hover.getContent().setAll(v);
        hover.show(area, at.getX(), at.getY());
    }

    // ================================================================ presentation

    /**
     * Texte d'aide d'un symbole.
     * @param current parametre en cours (gras), -1 : aucun
     * @param full    tout (resume, parametres, retour, exemples) ; sinon bulle de parametres
     */
    private List<javafx.scene.Node> docNodes(Sym s, int current, boolean full) {
        List<javafx.scene.Node> out = new ArrayList<>();
        out.add(signature(s, current));
        String where = s.origin();
        if ("plus".equals(s.target())) where += " - CPC+ / GX4000 seulement";
        else if ("cpc".equals(s.target())) where += " - CPC seulement";
        if (full) out.add(small(where, "#6c757d"));
        if (!full) {
            if (current >= 0 && s.params() != null && current < s.params().size()) {
                Param p = s.params().get(current);
                if (!p.doc().isEmpty() && !p.doc().equals(p.name())) out.add(flow(bold(p.name()), plain(" : " + p.doc())));
            }
            if (!s.summary().isEmpty()) out.add(wrap(s.summary(), "#333"));
            return out;
        }
        if (!s.summary().isEmpty()) out.add(wrap(s.summary(), "#222"));
        if (s.params() != null && !s.params().isEmpty() && s.params().stream().anyMatch(p -> !p.doc().isEmpty())) {
            out.add(title(s.kind() == Kind.ROUTINE ? "Entrée (registres)" : "Paramètres"));
            for (Param p : s.params()) out.add(flow(mono(p.name(), true), plain(p.doc().isEmpty() ? "" : "  " + p.doc())));
        }
        if (!s.ret().isEmpty()) {
            out.add(title(s.kind() == Kind.ROUTINE ? "Sortie" : "Retour"));
            out.add(wrap(s.ret(), "#222"));
        }
        if (!s.examples().isEmpty()) {
            out.add(title("Exemple"));
            for (String ex : s.examples()) {
                Label l = new Label(ex);
                l.setWrapText(true);
                l.setStyle("-fx-font-family: '" + MONO + "'; -fx-font-size: 12px; -fx-text-fill: #1a4d8f;");
                out.add(l);
            }
        }
        return out;
    }

    /** Signature, avec le parametre en cours en gras. */
    private TextFlow signature(Sym s, int current) {
        String sig = s.sig() == null ? s.name() : s.sig();
        TextFlow f = new TextFlow();
        f.setMaxWidth(600);
        int open = sig.indexOf('('), close = sig.lastIndexOf(')');
        if (current >= 0 && open > 0 && close > open && cMode) {
            f.getChildren().add(mono(sig.substring(0, open + 1), false));
            String[] args = sig.substring(open + 1, close).split(",");
            for (int i = 0; i < args.length; i++) {
                Text t = mono((i > 0 ? "," : "") + args[i], i == current);
                if (i == current) t.setStyle(t.getStyle() + " -fx-fill: #b5179e;");
                f.getChildren().add(t);
            }
            f.getChildren().add(mono(sig.substring(close), false));
        } else {
            Text t = mono(sig, true);
            f.getChildren().add(t);
        }
        return f;
    }

    private static Text mono(String s, boolean bold) {
        Text t = new Text(s);
        t.setStyle("-fx-font-family: '" + MONO + "'; -fx-font-size: 12.5px;" + (bold ? " -fx-font-weight: bold;" : ""));
        return t;
    }
    private static Text bold(String s) { Text t = new Text(s); t.setStyle("-fx-font-weight: bold;"); return t; }
    private static Text plain(String s) { return new Text(s); }
    private static TextFlow flow(Text... ts) { TextFlow f = new TextFlow(ts); f.setMaxWidth(600); return f; }
    private static Label wrap(String s, String color) {
        Label l = new Label(s);
        l.setWrapText(true);
        l.setMaxWidth(600);
        l.setStyle("-fx-text-fill: " + color + ";");
        return l;
    }
    private static Label small(String s, String color) {
        Label l = wrap(s, color);
        l.setStyle(l.getStyle() + " -fx-font-size: 11px; -fx-font-style: italic;");
        return l;
    }
    private static Label title(String s) {
        Label l = new Label(s);
        l.setStyle("-fx-font-weight: bold; -fx-text-fill: #444; -fx-padding: 4 0 0 0;");
        return l;
    }

    /** Ligne de la liste : pastille du genre, nom, type ou detail a droite. */
    private static final class SymCell extends ListCell<Sym> {
        @Override protected void updateItem(Sym s, boolean empty) {
            super.updateItem(s, empty);
            if (empty || s == null) { setGraphic(null); setText(null); return; }
            Label badge = new Label(s.kind().badge);
            badge.setMinWidth(16);
            badge.setAlignment(Pos.CENTER);
            badge.setStyle("-fx-background-color: " + s.kind().color + "; -fx-text-fill: white; -fx-font-size: 10px; "
                    + "-fx-background-radius: 3; -fx-font-weight: bold;");
            Label name = new Label(s.name());
            Label det = new Label(s.detail());
            det.setStyle("-fx-text-fill: #888; -fx-font-size: 11px;");
            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);
            HBox h = new HBox(6, badge, name, sp, det);
            h.setAlignment(Pos.CENTER_LEFT);
            setGraphic(h);
            setText(null);
        }
    }

    /** Garde la fenetre surgissante dans l'ecran (au-dessus de la ligne si elle deborde en bas). */
    private void fitOnScreen(Popup p, Bounds caret) {
        Platform.runLater(() -> {
            javafx.stage.Screen sc = javafx.stage.Screen.getScreensForRectangle(caret.getMinX(), caret.getMinY(), 1, 1)
                    .stream().findFirst().orElse(javafx.stage.Screen.getPrimary());
            Rectangle2D vb = sc.getVisualBounds();
            if (p.getY() + p.getHeight() > vb.getMaxY()) p.setY(caret.getMinY() - p.getHeight() - 2);
            if (p.getX() + p.getWidth() > vb.getMaxX()) p.setX(Math.max(vb.getMinX(), vb.getMaxX() - p.getWidth()));
        });
    }

    /** Position du curseur a l'ecran (meme quand le curseur clignotant est masque). */
    private Optional<Bounds> caretBounds() {
        Optional<Bounds> b = area.getCaretBounds();
        if (b.isPresent()) return b;
        int c = area.getCaretPosition(), n = area.getLength();
        if (n == 0) return Optional.empty();
        if (c > 0) {
            Optional<Bounds> ch = area.getCharacterBoundsOnScreen(c - 1, c);
            if (ch.isPresent() && area.getText(c - 1, c).charAt(0) != '\n')
                return ch.map(x -> new BoundingBox(x.getMaxX(), x.getMinY(), 1, x.getHeight()));
        }
        return area.getCharacterBoundsOnScreen(c, Math.min(n, c + 1)).map(x -> new BoundingBox(x.getMinX(), x.getMinY(), 1, x.getHeight()));
    }

    private boolean isWord(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || !cMode && (ch == '.' || ch == '@');
    }

    /** Le focus est parti ailleurs (autre controle, autre fenetre) : on ferme, sauf s'il est dans nos fenetres. */
    private void focusLost() {
        if (area.isFocused() || completion.isFocused() || hint.isFocused() || hover.isFocused()) return;
        hideAll();
    }

    void hideAll() { completion.hide(); hint.hide(); hover.hide(); }
}
