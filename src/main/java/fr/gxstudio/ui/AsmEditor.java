package fr.gxstudio.ui;

import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.scene.paint.Color;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.IntFunction;
import java.util.regex.*;

/**
 * Editeur de source assembleur Z80 ou C : coloration, numeros de ligne, points d'arret,
 * completion (Ctrl+Espace) et aide sur la bibliotheque (CodeAssist).
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public class AsmEditor extends EditorTab {
    public final CodeArea area = new CodeArea();
    /** Lignes avec point d'arret (0 = premiere). */
    public final Set<Integer> breakpoints = new TreeSet<>();
    /** Ligne ou le programme est arrete (debogage), -1 sinon. */
    private int pcLine = -1;
    private final Runnable onBreakpointsChanged;

    private static final String[] KEYWORDS = {
        "adc","add","and","bit","call","ccf","cp","cpd","cpdr","cpi","cpir","cpl","daa","dec","di","djnz","ei","ex","exx",
        "halt","im","in","inc","ind","indr","ini","inir","jp","jr","ld","ldd","lddr","ldi","ldir","neg","nop","or","otdr","otir",
        "out","outd","outi","pop","push","res","ret","reti","retn","rl","rla","rlc","rlca","rld","rr","rra","rrc","rrca","rrd",
        "rst","sbc","scf","set","sla","sll","sra","srl","sub","xor"};
    private static final String[] DIRECTIVES = {
        "org","equ","db","dw","defb","defw","defs","ds","incbin","include","macro","mend","endm","if","else","endif","ifdef",
        "ifndef","repeat","rend","bank","buildcpr","save","assert","print","align","struct","endstruct","list","nolist","str","run"};
    private static final Pattern PATTERN = Pattern.compile(
        "(?<COMMENT>;[^\n]*)"
        + "|(?<STRING>\"[^\"\n]*\"|'[^'\n]*')"
        + "|(?<LABEL>^[A-Za-z_.@][\\w.@]*:?)"
        + "|(?<KEYWORD>\\b(" + String.join("|", KEYWORDS) + ")\\b)"
        + "|(?<DIRECTIVE>\\b(" + String.join("|", DIRECTIVES) + ")\\b)"
        + "|(?<NUMBER>(#|&|\\$|%|0x)[0-9A-Fa-f]+\\b|\\b\\d+\\b)",
        Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    private static final String[] C_KEYWORDS = {
        "auto","break","case","char","const","continue","default","do","double","else","enum","extern","float","for","goto",
        "if","inline","int","long","register","return","short","signed","sizeof","static","struct","switch","typedef","union",
        "unsigned","void","volatile","while","bool","uint8_t","int8_t","uint16_t","int16_t","uint32_t","int32_t",
        "__sdcccall","__naked","__asm","__endasm","__at","__critical","__interrupt"};
    private static final Pattern C_PATTERN = Pattern.compile(
        "(?<COMMENT>//[^\n]*|/\\*(?s:.*?)\\*/)"
        + "|(?<STRING>\"([^\"\\\\\n]|\\\\.)*\"|'([^'\\\\\n]|\\\\.)*')"
        + "|(?<LABEL>^\\s*#\\s*\\w+)"
        + "|(?<KEYWORD>\\b(" + String.join("|", C_KEYWORDS) + ")\\b)"
        + "|(?<DIRECTIVE>\\bgx_\\w+)"
        + "|(?<NUMBER>\\b0[xX][0-9A-Fa-f]+\\b|\\b\\d+\\b)",
        Pattern.MULTILINE);
    /** fichier C (.c / .h) : coloration du C */
    private final boolean cMode;
    /** completion (Ctrl+Espace), bulle des parametres, aide au survol */
    private CodeAssist assist;

    /** Ctrl+Espace : liste de completion. */
    public void complete() { area.requestFocus(); assist.complete(); }
    /** Ctrl+Maj+Espace : parametres de l'appel en cours ou aide du mot sous le curseur. */
    public void helpAtCaret() { area.requestFocus(); assist.helpAtCaret(); }
    /** Ferme les fenetres d'aide (changement d'onglet). */
    public void hideAssist() { if (assist != null) assist.hideAll(); }

    public AsmEditor(Path file, Set<Integer> bps, Runnable onBreakpointsChanged) throws Exception {
        super(file);
        String fn = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        cMode = fn.endsWith(".c") || fn.endsWith(".h");
        this.onBreakpointsChanged = onBreakpointsChanged;
        breakpoints.addAll(bps);
        area.getStyleClass().add("asm");
        area.replaceText(Files.readString(file, StandardCharsets.ISO_8859_1));
        area.getUndoManager().forgetHistory();
        area.moveTo(0);
        area.setParagraphGraphicFactory(gutter());
        restyle();
        area.multiPlainChanges().successionEnds(Duration.ofMillis(150)).subscribe(c -> restyle());
        area.textProperty().addListener((o, a, b) -> dirty.set(true));
        area.plainTextChanges().subscribe(this::shiftBreakpoints);   // les points d'arret suivent les lignes
        assist = new CodeAssist(area, file, cMode);               // Ctrl+Espace, parametres, survol (avant la tabulation)
        area.addEventFilter(KeyEvent.KEY_PRESSED, e -> {          // tabulation = 8 espaces
            if (e.getCode() == KeyCode.TAB && !e.isControlDown()) { area.replaceSelection(cMode ? "    " : "        "); e.consume(); }
            if (e.getCode() == KeyCode.ESCAPE && searchBar.isVisible()) { hideFind(); e.consume(); }
        });
        buildSearchBar();
        setContent(new BorderPane(new VirtualizedScrollPane<>(area), searchBar, null, null, null));
        dirty.set(false);
    }

    // ================================================================ recherche / remplacement
    private final VBox searchBar = new VBox(4);
    private final TextField findField = new TextField(), replaceField = new TextField();
    private final CheckBox caseBox = new CheckBox("Aa"), wordBox = new CheckBox("Mot entier"), regexBox = new CheckBox("Regex");
    private final Label found = new Label();
    private final HBox replaceRow = new HBox(6);

    private void buildSearchBar() {
        findField.setPromptText("Rechercher (Entrée : suivant, Maj+Entrée : précédent)");
        replaceField.setPromptText("Remplacer par");
        findField.setPrefColumnCount(28);
        replaceField.setPrefColumnCount(28);
        caseBox.setTooltip(new Tooltip("Respecter les majuscules"));
        Button prev = new Button("▲"), next = new Button("▼"), close = new Button("✕"), one = new Button("Remplacer"), all = new Button("Tout remplacer");
        prev.setOnAction(e -> findNext(false));
        next.setOnAction(e -> findNext(true));
        close.setOnAction(e -> hideFind());
        one.setOnAction(e -> replaceOne());
        all.setOnAction(e -> replaceAll());
        findField.textProperty().addListener((o, a, b) -> { restyle(); countMatches(); });
        for (CheckBox c : List.of(caseBox, wordBox, regexBox)) c.setOnAction(e -> { restyle(); countMatches(); });
        findField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) { findNext(!e.isShiftDown()); e.consume(); }
            else if (e.getCode() == KeyCode.ESCAPE) { hideFind(); e.consume(); }
        });
        replaceField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) { replaceOne(); e.consume(); }
            else if (e.getCode() == KeyCode.ESCAPE) { hideFind(); e.consume(); }
        });
        HBox findRow = new HBox(6, new Label("Rechercher"), findField, prev, next, caseBox, wordBox, regexBox, found, close);
        replaceRow.getChildren().setAll(new Label("Remplacer "), replaceField, one, all);
        for (HBox h : List.of(findRow, replaceRow)) h.setAlignment(Pos.CENTER_LEFT);
        searchBar.getChildren().setAll(findRow, replaceRow);
        searchBar.setPadding(new Insets(4, 8, 4, 8));
        searchBar.setStyle("-fx-background-color: #e9ecef; -fx-border-color: #bbb; -fx-border-width: 0 0 1 0;");
        searchBar.setVisible(false);
        searchBar.setManaged(false);
    }

    /** Ouvre la barre de recherche (avec le texte selectionne). */
    public void showFind(boolean replace) {
        String sel = area.getSelectedText();
        if (!sel.isEmpty() && !sel.contains("\n")) findField.setText(sel);
        searchBar.setVisible(true);
        searchBar.setManaged(true);
        replaceRow.setVisible(replace);
        replaceRow.setManaged(replace);
        findField.requestFocus();
        findField.selectAll();
        restyle();
        countMatches();
    }

    public void setFindText(String s) { findField.setText(s); }

    public void hideFind() {
        searchBar.setVisible(false);
        searchBar.setManaged(false);
        restyle();
        area.requestFocus();
    }

    private Pattern query() {
        String q = findField.getText();
        if (q.isEmpty()) return null;
        String re = regexBox.isSelected() ? q : Pattern.quote(q);
        if (wordBox.isSelected()) re = "(?<![\\w.@])" + re + "(?![\\w@])";
        try { return Pattern.compile(re, caseBox.isSelected() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE); }
        catch (PatternSyntaxException e) { found.setText("regex invalide"); return null; }
    }

    private void countMatches() {
        Pattern p = query();
        if (p == null) { found.setText(""); return; }
        Matcher m = p.matcher(area.getText());
        int n = 0;
        while (m.find() && n < 100000) { n++; if (m.end() == m.start()) break; }
        found.setText(n == 0 ? "aucun résultat" : n + " résultat" + (n > 1 ? "s" : ""));
        found.setStyle(n == 0 ? "-fx-text-fill: #c1121f;" : "");
    }

    /** Selectionne l'occurrence suivante (ou precedente), en bouclant. */
    public void findNext(boolean forward) {
        if (!searchBar.isVisible() && findField.getText().isEmpty()) { showFind(false); return; }
        Pattern p = query();
        if (p == null) return;
        String t = area.getText();
        int caret = forward ? area.getSelection().getEnd() : area.getSelection().getStart();
        Matcher m = p.matcher(t);
        int s = -1, e = -1;
        if (forward) {
            if (m.find(Math.min(caret, t.length()))) { s = m.start(); e = m.end(); }
            else if (m.find(0)) { s = m.start(); e = m.end(); }
        } else {
            int ls = -1, le = -1;
            while (m.find()) {
                if (m.start() >= caret) break;
                ls = m.start(); le = m.end();
                if (m.end() == m.start()) break;
            }
            if (ls < 0) { m.reset(); while (m.find()) { ls = m.start(); le = m.end(); if (m.end() == m.start()) break; } }
            s = ls; e = le;
        }
        if (s < 0) { found.setText("aucun résultat"); return; }
        area.selectRange(s, e);
        area.requestFollowCaret();
        int line = area.getCurrentParagraph();
        area.showParagraphInViewport(line);
    }

    private void replaceOne() {
        Pattern p = query();
        if (p == null) return;
        String sel = area.getSelectedText();
        if (!sel.isEmpty() && p.matcher(sel).matches()) {
            String rep = regexBox.isSelected() ? p.matcher(sel).replaceFirst(replaceField.getText()) : replaceField.getText();
            area.replaceSelection(rep);
        }
        findNext(true);
        countMatches();
    }

    private void replaceAll() {
        Pattern p = query();
        if (p == null) return;
        Matcher m = p.matcher(area.getText());
        int n = 0;
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, regexBox.isSelected() ? replaceField.getText() : Matcher.quoteReplacement(replaceField.getText()));
            n++;
        }
        if (n == 0) { found.setText("aucun résultat"); return; }
        m.appendTail(sb);
        int caret = area.getCaretPosition();
        area.replaceText(sb.toString());
        area.moveTo(Math.min(caret, area.getLength()));
        found.setText(n + " remplacement" + (n > 1 ? "s" : ""));
    }

    /** Coloration syntaxique + surlignage des resultats de recherche. */
    private void restyle() {
        String t = area.getText();
        area.setStyleSpans(0, highlight(t, cMode ? C_PATTERN : PATTERN));
        Pattern p = searchBar.isVisible() ? query() : null;
        if (p == null) return;
        Matcher m = p.matcher(t);
        int n = 0;
        while (m.find() && n++ < 2000) {
            if (m.end() == m.start()) break;
            area.setStyle(m.start(), m.end(), List.of("match"));
        }
    }

    /** Mot (etiquette) sous le curseur. */
    public String wordAtCaret() {
        String t = area.getText();
        int c = area.getCaretPosition(), a = c, b = c;
        while (a > 0 && isWord(t.charAt(a - 1))) a--;
        while (b < t.length() && isWord(t.charAt(b))) b++;
        return t.substring(a, b);
    }

    private static boolean isWord(char ch) { return Character.isLetterOrDigit(ch) || ch == '_' || ch == '.' || ch == '@'; }

    public void gotoLineAndColumn(int line, int col) {
        gotoLine(line);
        int l = Math.max(0, Math.min(line - 1, area.getParagraphs().size() - 1));
        area.moveTo(l, Math.min(col, area.getParagraph(l).length()));
    }

    /** Marge : point d'arret (clic) + numero de ligne. */
    private IntFunction<Node> gutter() {
        return line -> {
            Circle dot = new Circle(5, breakpoints.contains(line) ? Color.web("#d62828") : Color.TRANSPARENT);
            Label num = new Label(String.format("%4d", line + 1));
            num.getStyleClass().add("lineno");
            Label pc = new Label(line == pcLine ? "\u25B6" : " ");
            pc.getStyleClass().add("pc-arrow");
            pc.setMinWidth(12);
            HBox box = new HBox(3, dot, num, pc);
            box.setAlignment(Pos.CENTER_LEFT);
            box.getStyleClass().add("gutter");
            box.setOnMouseClicked(e -> toggleBreakpoint(line));
            return box;
        };
    }

    public void toggleBreakpoint(int line) {
        if (!breakpoints.remove(line)) breakpoints.add(line);
        area.setParagraphGraphicFactory(gutter());          // redessine la marge
        onBreakpointsChanged.run();
    }

    /**
     * Deplace les points d'arret quand des lignes sont inserees ou supprimees au-dessus d'eux.
     * Une ligne supprimee emporte son point d'arret sur la ligne ou la suppression commence.
     */
    private void shiftBreakpoints(PlainTextChange ch) {
        int ins = count(ch.getInserted()), rem = count(ch.getRemoved());
        if (ins == rem || breakpoints.isEmpty()) return;
        String text = area.getText();
        int pos = Math.min(ch.getPosition(), text.length());
        int line = count(text.substring(0, pos));                 // ligne ou commence la modification
        boolean atLineStart = pos == 0 || text.charAt(pos - 1) == '\n';
        // insertion de lignes entieres en debut de ligne : la ligne courante descend aussi
        int from = atLineStart && ch.getInserted().endsWith("\n") && rem == 0 ? line : line + 1;
        Set<Integer> moved = new TreeSet<>();
        for (int b : breakpoints) {
            if (b < from) moved.add(b);
            else if (b <= line + rem && rem > 0 && b > line) moved.add(line);   // ligne supprimee
            else moved.add(Math.max(0, b + ins - rem));
        }
        if (moved.equals(breakpoints)) return;
        breakpoints.clear();
        breakpoints.addAll(moved);
        area.setParagraphGraphicFactory(gutter());
        onBreakpointsChanged.run();
    }

    private static int count(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    public void clearBreakpoints() {
        breakpoints.clear();
        area.setParagraphGraphicFactory(gutter());
    }

    /** Debogage : marque la ligne courante du programme (0 = premiere, -1 = aucune) et la montre. */
    public void setPcLine(int line) {
        if (pcLine >= 0 && pcLine < area.getParagraphs().size()) area.setParagraphStyle(pcLine, Collections.emptyList());
        pcLine = line;
        if (line >= 0 && line < area.getParagraphs().size()) {
            area.setParagraphStyle(line, List.of("pcline"));
            area.moveTo(line, 0);
            int first = area.firstVisibleParToAllParIndex(), last = area.lastVisibleParToAllParIndex();
            if (line < first + 2 || line > last - 2) area.showParagraphAtTop(Math.max(0, line - 10));
        }
        area.setParagraphGraphicFactory(gutter());
    }

    public int pcLine() { return pcLine; }

    public void toggleBreakpointAtCaret() { toggleBreakpoint(area.getCurrentParagraph()); }

    public void gotoLine(int line) {
        int l = Math.max(0, Math.min(line - 1, area.getParagraphs().size() - 1));
        area.moveTo(l, 0);
        area.showParagraphAtTop(Math.max(0, l - 8));   // la ligne visee vers le haut de la vue
        area.selectLine();
        area.requestFocus();
    }

    @Override public void save() throws Exception {
        Files.writeString(file, area.getText(), StandardCharsets.ISO_8859_1);
        dirty.set(false);
    }

    static StyleSpans<Collection<String>> highlight(String text) { return highlight(text, PATTERN); }

    static StyleSpans<Collection<String>> highlight(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        int last = 0;
        StyleSpansBuilder<Collection<String>> sb = new StyleSpansBuilder<>();
        while (m.find()) {
            String cls = m.group("COMMENT") != null ? "comment" : m.group("STRING") != null ? "string"
                    : m.group("LABEL") != null ? "label" : m.group("KEYWORD") != null ? "keyword"
                    : m.group("DIRECTIVE") != null ? "directive" : "number";
            sb.add(Collections.emptyList(), m.start() - last);
            sb.add(Collections.singleton(cls), m.end() - m.start());
            last = m.end();
        }
        sb.add(Collections.emptyList(), text.length() - last);
        return sb.create();
    }
}
