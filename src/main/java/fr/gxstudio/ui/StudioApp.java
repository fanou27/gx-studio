package fr.gxstudio.ui;

import fr.gxstudio.build.*;
import fr.gxstudio.model.*;
import javafx.application.*;
import javafx.collections.*;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.*;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * Fenetre principale de GX Studio.
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public class StudioApp extends Application {
    private Stage stage;
    private Project project;
    private final Settings settings = Settings.load();
    private final TreeView<Path> tree = new TreeView<>();
    private final TabPane editors = new TabPane();
    private final TextArea console = new TextArea();
    private final TextArea profText = new TextArea("Débogage > Mesurer les performances (Ctrl+F6), ou le bouton « Profil 5 s » de l'onglet Débogage,\n"
            + "pendant que le programme tourne : le rapport s'affiche ici.");
    private Tab profTab;
    private final ListView<Builder.Message> problems = new ListView<>();
    /** Resultat de recherche dans le projet. */
    public record Hit(Path file, int line, int col, String text) { }
    private final ListView<Hit> hits = new ListView<>();
    private Tab searchTab;
    private final TabPane bottom = new TabPane();
    private final Label status = new Label("Prêt");
    private final ToggleGroup targetGroup = new ToggleGroup();
    private final ComboBox<Integer> ramBox = new ComboBox<>();
    private final ComboBox<Integer> modeBox = new ComboBox<>(FXCollections.observableArrayList(0, 1, 2));
    private final Emulator emulator = new Emulator();
    private final Map<String, Set<Integer>> breakpoints = new TreeMap<>();
    private boolean updatingUi;
    private BorderPane root;
    // ---- debogage dans le studio (Caprice32 corrige : serveur de debogage)
    private DebugPanel debugPanel;
    private Tab debugTab;
    private Builder lastBuild;
    private final Set<Integer> liveBps = new HashSet<>();
    private AsmEditor pcEditor;
    private SplitPane center;

    @Override public void start(Stage st) {
        stage = st;
        console.setEditable(false);
        console.getStyleClass().add("console");
        problems.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(Builder.Message m, boolean empty) {
                super.updateItem(m, empty);
                setText(empty || m == null ? null : (m.error() ? "Erreur  " : "Avert.  ") + m);
                getStyleClass().removeAll("problem-error", "problem-warning");
                if (!empty && m != null) getStyleClass().add(m.error() ? "problem-error" : "problem-warning");
            }
        });
        problems.setOnMouseClicked(e -> {
            Builder.Message m = problems.getSelectionModel().getSelectedItem();
            if (e.getClickCount() == 2 && m != null && m.file() != null) openFile(m.file(), m.line());
        });
        Tab tc = new Tab("Console", console), tp = new Tab("Problèmes", problems);
        tc.setClosable(false); tp.setClosable(false);
        searchTab = new Tab("Recherche", hits);
        searchTab.setClosable(false);
        debugPanel = new DebugPanel(new DebugPanel.Host() {
            @Override public void showPc(Builder.SourceLine l, int pc) { showPcLine(l); }
            @Override public void debugStatus(String s) { status.setText(s); }
            @Override public void debugEnded() { liveBps.clear(); }
            @Override public void watchesChanged() { saveWatches(); }
            @Override public void log(String s) { StudioApp.this.log(s); }
        });
        debugTab = new Tab("Débogage", debugPanel);
        debugTab.setClosable(false);
        profText.setEditable(false);
        profText.getStyleClass().add("console");
        profTab = new Tab("Profil", profText);
        profTab.setClosable(false);
        bottom.getTabs().addAll(tc, tp, searchTab, debugTab, profTab);
        hits.getStyleClass().add("search-hit");
        hits.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(Hit h, boolean empty) {
                super.updateItem(h, empty);
                setText(empty || h == null ? null : project == null ? h.text() :
                        String.format("%-28s %5d : %s", project.dir.relativize(h.file()), h.line(), h.text().strip()));
            }
        });
        hits.setOnMouseClicked(e -> {
            Hit h = hits.getSelectionModel().getSelectedItem();
            if (e.getClickCount() == 2 && h != null) openAt(h);
        });

        tree.setShowRoot(true);
        tree.setCellFactory(tv -> new ProjectCell());
        tree.setOnMouseClicked(e -> {
            TreeItem<Path> it = tree.getSelectionModel().getSelectedItem();
            if (e.getClickCount() == 2 && it != null && it.getValue() != null && Files.isRegularFile(it.getValue())) openFile(it.getValue(), 0);
        });
        editors.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        editors.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> { if (a instanceof AsmEditor e) e.hideAssist(); });

        center = new SplitPane(editors, bottom);
        center.setOrientation(Orientation.VERTICAL);
        center.setDividerPositions(0.74);
        SplitPane main = new SplitPane(tree, center);
        main.setDividerPositions(0.2);
        SplitPane.setResizableWithParent(tree, false);

        root = new BorderPane(main, new VBox(menuBar(), toolBar()), null, statusBar(), null);
        Scene scene = new Scene(root, 1280, 820);
        scene.getStylesheets().add(StudioApp.class.getResource("/fr/gxstudio/studio.css").toExternalForm());
        st.setScene(scene);
        st.setTitle("GX Studio");
        st.setOnCloseRequest(e -> { if (!confirmDiscard()) e.consume(); else emulator.stop(); });
        st.show();

        List<String> args = getParameters().getRaw();
        if (args.size() >= 3 && args.get(0).equals("--snapshot")) { snapshotMode(Paths.get(args.get(1)), Paths.get(args.get(2)), args.subList(3, args.size())); return; }
        if (!args.isEmpty() && Files.exists(Paths.get(args.get(0)).resolve(Project.FILE))) openProject(Paths.get(args.get(0)));
        else if (!settings.lastProject.isBlank() && Files.exists(Paths.get(settings.lastProject).resolve(Project.FILE))) openProject(Paths.get(settings.lastProject));
        else showWelcome();
    }

    // ================================================================ menus, barre d'outils
    private MenuBar menuBar() {
        Menu file = new Menu("Fichier");
        file.getItems().addAll(
                item("Nouveau projet…", "Shortcut+Shift+N", this::newProject),
                item("Ouvrir un projet…", "Shortcut+O", this::openProjectDialog),
                new SeparatorMenuItem(),
                item("Enregistrer", "Shortcut+S", this::saveCurrent),
                item("Tout enregistrer", "Shortcut+Shift+S", this::saveAll),
                new SeparatorMenuItem(),
                item("Quitter", "Shortcut+Q", () -> { if (confirmDiscard()) { emulator.stop(); Platform.exit(); } }));
        Menu proj = new Menu("Projet");
        proj.getItems().addAll(
                item("Nouveau fichier source…", null, this::newSource),
                item("Nouveau sprite…", null, this::newSprite),
                item("Nouveau son…", null, this::newSound),
                item("Importer une image PNG…", null, this::importImage),
                new SeparatorMenuItem(),
                item("Nouveau jeu de tuiles…", null, this::newTileset),
                item("Nouvelle carte…", null, this::newMap),
                item("Nouvelle animation…", null, this::newAnimation),
                item("Nouvelle table de rasters…", null, this::newRaster),
                new SeparatorMenuItem(),
                item("Propriétés du projet…", null, this::projectProperties),
                item("Mettre à jour la bibliothèque (lib/)", null, this::updateLib),
                item("Ouvrir le dossier build/ (fichiers générés)", null, () -> openFile(project.build().resolve("gen.asm"), 0)));
        Menu build = new Menu("Construire");
        build.getItems().addAll(
                item("Compiler", "F7", () -> build(false)),
                item("Compiler et lancer dans Caprice32", "F5", () -> build(true)),
                item("Arrêter l'émulateur", "Shift+F5", this::stopEmulator),
                new SeparatorMenuItem(),
                item("Point d'arrêt sur la ligne", "F9", () -> { if (current() instanceof AsmEditor a) a.toggleBreakpointAtCaret(); }),
                item("Supprimer tous les points d'arrêt", null, this::clearBreakpoints));
        Menu debug = new Menu("Débogage");
        debug.getItems().addAll(
                item("Lancer avec le débogueur", null, () -> build(true)),
                item("Continuer", "F8", () -> debugPanel.cont()),
                item("Pause", "F6", () -> debugPanel.pause()),
                item("Pas à pas (entre dans les CALL)", "F11", () -> debugPanel.step()),
                item("Pas principal (passe les CALL)", "F10", () -> debugPanel.stepOver()),
                item("Sortir de la routine", "Shift+F11", () -> debugPanel.stepOut()),
                item("Mesurer les performances (profil 5 s)", "Ctrl+F6", () -> debugPanel.profile(5)),
                new SeparatorMenuItem(),
                item("Point d'arrêt sur la ligne", null, () -> { if (current() instanceof AsmEditor a) a.toggleBreakpointAtCaret(); }),
                item("Ajouter la variable sous le curseur", "Shortcut+Shift+W", this::watchWordAtCaret),
                item("Afficher l'onglet Débogage", null, () -> bottom.getSelectionModel().select(debugTab)));
        Menu tools = new Menu("Outils");
        tools.getItems().addAll(item("Réglages (rasm, Caprice32)…", null, this::settingsDialog));
        Menu help = new Menu("Aide");
        help.getItems().addAll(item("Routines de la bibliothèque…", "F1", this::libraryHelp), item("À propos", null, this::about));
        Menu edit = new Menu("Édition");
        edit.getItems().addAll(
                item("Rechercher…", "Shortcut+F", () -> { if (current() instanceof AsmEditor a) a.showFind(false); }),
                item("Remplacer…", "Shortcut+H", () -> { if (current() instanceof AsmEditor a) a.showFind(true); }),
                item("Suivant", "F3", () -> { if (current() instanceof AsmEditor a) a.findNext(true); }),
                item("Précédent", "Shift+F3", () -> { if (current() instanceof AsmEditor a) a.findNext(false); }),
                new SeparatorMenuItem(),
                item("Rechercher dans le projet…", "Shortcut+Shift+F", this::searchProject),
                item("Aller à la définition", "F12", this::gotoDefinition),
                new SeparatorMenuItem(),
                item("Compléter le code", "Shortcut+Space", () -> { if (current() instanceof AsmEditor a) a.complete(); }),
                item("Aide / paramètres de la fonction", "Shortcut+Shift+Space", () -> { if (current() instanceof AsmEditor a) a.helpAtCaret(); }),
                item("Aller à la ligne…", "Shortcut+G", this::gotoLineDialog));
        return new MenuBar(file, edit, proj, build, debug, tools, help);
    }

    private MenuItem item(String text, String key, Runnable r) {
        MenuItem m = new MenuItem(text);
        if (key != null) m.setAccelerator(KeyCombination.keyCombination(key));
        boolean free = text.startsWith("Nouveau projet") || text.startsWith("Ouvrir un projet") || text.equals("Quitter")
                || text.startsWith("Réglages") || text.startsWith("À propos");
        m.setOnAction(e -> { if (free || project != null) r.run(); else alert("Ouvrez ou créez d'abord un projet."); });
        return m;
    }

    private ToolBar toolBar() {
        Button nb = new Button("Nouveau"), ob = new Button("Ouvrir"), sb = new Button("Enregistrer"),
               bb = new Button("⚙ Compiler (F7)"), rb = new Button("▶ Lancer (F5)");
        nb.setOnAction(e -> newProject());
        ob.setOnAction(e -> openProjectDialog());
        sb.setOnAction(e -> saveAll());
        bb.setOnAction(e -> { if (project != null) build(false); });
        rb.setOnAction(e -> { if (project != null) build(true); });
        rb.setStyle("-fx-font-weight: bold;");
        HBox targets = new HBox(8, new Label("Cible :"));
        for (Target t : Target.values()) {
            RadioButton r = new RadioButton(t == Target.CPC ? "CPC" : t == Target.CPC_PLUS ? "CPC+" : "GX4000");
            r.setUserData(t);
            r.setToggleGroup(targetGroup);
            r.setTooltip(new Tooltip(t.label + (t.plus ? " : cartouche, sprites matériels, DMA" : " : disquette, sprites logiciels")
                    + (t.keyboard ? "" : " (pas de clavier : manettes seulement)")));
            targets.getChildren().add(r);
        }
        targets.setAlignment(Pos.CENTER_LEFT);
        targets.getStyleClass().add("target-box");
        targetGroup.selectedToggleProperty().addListener((o, a, b) -> { if (!updatingUi && b != null) changeTarget((Target) b.getUserData(), a); });
        ramBox.setOnAction(e -> {
            if (updatingUi || project == null || ramBox.getValue() == null) return;
            project.ramKb = ramBox.getValue();
            saveProjectFile();
        });
        modeBox.setOnAction(e -> {
            if (updatingUi || project == null || modeBox.getValue() == null) return;
            project.screenMode = modeBox.getValue();
            saveProjectFile();
            editors.getTabs().forEach(t -> ((EditorTab) t).refresh());
        });
        return new ToolBar(nb, ob, sb, new Separator(), bb, rb, new Separator(), targets,
                new Label("RAM :"), ramBox, new Label("Ko"), new Separator(), new Label("Mode écran :"), modeBox);
    }

    private HBox statusBar() {
        HBox h = new HBox(status);
        h.setPadding(new Insets(3, 8, 3, 8));
        return h;
    }

    // ================================================================ projet
    private void showWelcome() {
        Label t = new Label("GX Studio");
        t.setStyle("-fx-font-size: 30px; -fx-font-weight: bold;");
        Label s = new Label("Atelier de création de jeux pour Amstrad CPC, CPC+ et GX4000\n(assembleur rasm, émulateur Caprice32)");
        s.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        Button n = new Button("Nouveau projet…"), o = new Button("Ouvrir un projet…");
        n.setOnAction(e -> newProject());
        o.setOnAction(e -> openProjectDialog());
        VBox v = new VBox(16, t, s, new HBox(10, n, o));
        ((HBox) v.getChildren().get(2)).setAlignment(Pos.CENTER);
        v.setAlignment(Pos.CENTER);
        Tab w = new Tab("Bienvenue", v);
        editors.getTabs().setAll(w);
    }

    private void newProject() {
        if (!confirmDiscard()) return;
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Nouveau projet");
        d.initOwner(stage);
        TextField name = new TextField("MonJeu");
        TextField dir = new TextField(Paths.get(System.getProperty("user.home"), "gxstudio").toString());
        Button browse = new Button("…");
        browse.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            File f = dc.showDialog(d.getOwner());
            if (f != null) dir.setText(f.getPath());
        });
        ToggleGroup tg = new ToggleGroup();
        HBox tbox = new HBox(10);
        ComboBox<Integer> ram = new ComboBox<>(), mode = new ComboBox<>(FXCollections.observableArrayList(0, 1));
        for (Target t : Target.values()) {
            RadioButton r = new RadioButton(t.label);
            r.setUserData(t);
            r.setToggleGroup(tg);
            tbox.getChildren().add(r);
        }
        tg.selectedToggleProperty().addListener((o, a, b) -> {
            Target t = (Target) b.getUserData();
            ram.setItems(FXCollections.observableArrayList(Arrays.stream(t.ramChoices()).boxed().toList()));
            ram.setValue(t.ramChoices()[0]);
            mode.setValue(t.plus ? 1 : 0);
        });
        tg.selectToggle(tg.getToggles().get(2));
        ToggleGroup lg = new ToggleGroup();
        RadioButton lAsm = new RadioButton("Assembleur Z80"), lC = new RadioButton("C (SDCC) + assembleur");
        lAsm.setToggleGroup(lg); lC.setToggleGroup(lg); lAsm.setSelected(true);
        if (snapNewName != null) {                                                         // captures (documentation)
            if (snapNewName.endsWith("#0")) { snapNewName = snapNewName.substring(0, snapNewName.length() - 2); mode.setValue(0); }
            name.setText(snapNewName); lC.setSelected(true); snapNewName = null;
        }   // captures (documentation)
        Label note = new Label("CPC : disquette .dsk, sprites logiciels en mode 0, son par interruption.\n"
                + "CPC+ / GX4000 : cartouche .cpr, sprites matériels, rasters, son DMA (GX4000 : 64 Ko, pas de clavier).");
        GridPane g = new GridPane();
        g.setHgap(8); g.setVgap(10); g.setPadding(new Insets(14));
        g.addRow(0, new Label("Nom du jeu :"), name);
        g.addRow(1, new Label("Dossier parent :"), new HBox(4, dir, browse));
        g.addRow(2, new Label("Machine :"), tbox);
        g.addRow(3, new Label("Mémoire (Ko) :"), ram);
        g.addRow(4, new Label("Mode écran :"), mode);
        g.addRow(5, new Label("Langage :"), new HBox(10, lAsm, lC));
        g.add(note, 0, 6, 2, 1);
        HBox.setHgrow(dir, Priority.ALWAYS);
        dir.setPrefColumnCount(30);
        d.getDialogPane().setContent(g);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        if (d.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        String n = name.getText().trim().isEmpty() ? "MonJeu" : name.getText().trim();
        Path pdir = Paths.get(dir.getText()).resolve(n.replaceAll("[^A-Za-z0-9_-]", "_"));
        try {
            Target t = (Target) tg.getSelectedToggle().getUserData();
            ProjectFactory.create(pdir, n, t, ram.getValue(), mode.getValue(), settings.templatesDir(), lC.isSelected());
            openProject(pdir);
            log("Projet créé : " + pdir);
        } catch (Exception ex) {
            error("Création impossible", ex);
        }
    }

    private void openProjectDialog() {
        if (!confirmDiscard()) return;
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Dossier du projet (contenant " + Project.FILE + ")");
        File f = dc.showDialog(stage);
        if (f == null) return;
        if (!Files.exists(f.toPath().resolve(Project.FILE))) { alert("Ce dossier ne contient pas de " + Project.FILE); return; }
        openProject(f.toPath());
    }

    private void openProject(Path dir) {
        try {
            project = Project.load(dir);
            settings.lastProject = dir.toAbsolutePath().toString();
            settings.save();
            loadBreakpoints();
            loadWatches();
            editors.getTabs().clear();
            refreshTree();
            updateToolbar();
            stage.setTitle("GX Studio - " + project.name + " (" + project.target.label + ")");
            Path main = dir.resolve(project.mainFile);
            if (Files.exists(main)) openFile(main, 0);
            status.setText("Projet " + project.name + " : " + dir);
        } catch (Exception e) {
            error("Ouverture impossible", e);
        }
    }

    private void updateToolbar() {
        updatingUi = true;
        for (Toggle t : targetGroup.getToggles()) if (t.getUserData() == project.target) targetGroup.selectToggle(t);
        ramBox.setItems(FXCollections.observableArrayList(Arrays.stream(project.target.ramChoices()).boxed().toList()));
        ramBox.setValue(project.ramKb);
        ramBox.setDisable(project.target.ramChoices().length == 1);
        modeBox.setValue(project.screenMode);
        updatingUi = false;
    }

    private void changeTarget(Target t, Toggle previous) {
        if (project == null || t == project.target) return;
        boolean family = t.plus != project.target.plus;
        if (family) {
            Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                    "Passer de " + project.target.label + " à " + t.label + " change de bibliothèque :\n"
                    + (t.plus ? "sprites matériels, rasters et son DMA (cartouche)." : "sprites logiciels et son par interruption (disquette).")
                    + "\n\nLa bibliothèque correspondante sera ajoutée dans lib/, mais votre code (src/) "
                    + "doit être adapté : le plus simple est de partir d'un nouveau projet pour cette cible.\n\nContinuer ?",
                    ButtonType.OK, ButtonType.CANCEL);
            a.initOwner(stage);
            if (a.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                updatingUi = true; targetGroup.selectToggle(previous); updatingUi = false;
                return;
            }
        }
        project.target = t;
        project.fixRam();
        if (family) {
            project.screenMode = t.plus ? 1 : 0;
            try { ProjectFactory.updateLib(project, settings.templatesDir()); } catch (Exception e) { error("Bibliothèque", e); }
        }
        saveProjectFile();
        updateToolbar();
        refreshTree();
        editors.getTabs().forEach(x -> ((EditorTab) x).refresh());
        stage.setTitle("GX Studio - " + project.name + " (" + project.target.label + ")");
        log("Cible : " + t.label + ", " + project.ramKb + " Ko" + (t.keyboard ? "" : " (manettes seulement)"));
    }

    private void saveProjectFile() {
        try { project.save(); } catch (Exception e) { error("Enregistrement du projet", e); }
    }

    private void projectProperties() {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Propriétés du projet");
        d.initOwner(stage);
        TextField name = new TextField(project.name), main = new TextField(project.mainFile), defs = new TextField(project.defines);
        GridPane g = new GridPane();
        g.setHgap(8); g.setVgap(10); g.setPadding(new Insets(14));
        g.addRow(0, new Label("Nom :"), name);
        g.addRow(1, new Label("Fichier principal :"), main);
        g.addRow(2, new Label("Définitions rasm (-D) :"), defs);
        g.add(new Label("Exemple : GOD_MODE=1, START_WAVE=4 (séparées par des virgules)"), 1, 3);
        d.getDialogPane().setContent(g);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        if (d.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        project.name = name.getText().trim();
        project.mainFile = main.getText().trim();
        project.defines = defs.getText().trim();
        saveProjectFile();
        refreshTree();
    }

    private void updateLib() {
        try {
            ProjectFactory.updateLib(project, settings.templatesDir());
            log("Bibliothèque mise à jour depuis " + settings.templatesDir());
            refreshTree();
        } catch (Exception e) { error("Bibliothèque", e); }
    }

    // ================================================================ arbre du projet
    private void refreshTree() {
        TreeItem<Path> r = new TreeItem<>(project.dir);
        r.setExpanded(true);
        r.getChildren().add(folder("Code (src)", project.dir.resolve("src"), ".asm", ".inc", ".c", ".h"));
        r.getChildren().add(folder("Sprites", project.assets("sprites"), ".spr"));
        r.getChildren().add(folder("Sons", project.assets("sons"), ".snd"));
        r.getChildren().add(folder("Palettes", project.assets("palettes"), ".pal"));
        r.getChildren().add(folder("Images", project.assets("images"), ".png"));
        r.getChildren().add(folder("Décors : tuiles", project.assets("tuiles"), ".til"));
        r.getChildren().add(folder("Décors : cartes", project.assets("cartes"), ".map"));
        r.getChildren().add(folder("Animations", project.assets("animations"), ".ani"));
        r.getChildren().add(folder("Rasters" + (project.target.plus ? "" : " (CPC+/GX)"), project.assets("rasters"), ".ras"));
        TreeItem<Path> lib = folder("Bibliothèque (lib)", project.dir.resolve("lib"), ".asm", ".inc", ".c", ".h");
        lib.setExpanded(false);
        r.getChildren().add(lib);
        tree.setRoot(r);
    }

    private final Map<Path, String> labels = new HashMap<>();

    private TreeItem<Path> folder(String label, Path dir, String... ext) {
        labels.put(dir, label);
        TreeItem<Path> it = new TreeItem<>(dir);
        it.setExpanded(true);
        if (Files.isDirectory(dir)) {
            try (Stream<Path> s = Files.walk(dir)) {
                for (Path f : s.filter(Files::isRegularFile).sorted().toList())
                    if (Arrays.stream(ext).anyMatch(x -> f.toString().toLowerCase(Locale.ROOT).endsWith(x))) it.getChildren().add(new TreeItem<>(f));
            } catch (Exception ignored) { }
        }
        return it;
    }

    private class ProjectCell extends TreeCell<Path> {
        @Override protected void updateItem(Path p, boolean empty) {
            super.updateItem(p, empty);
            setContextMenu(null);
            setGraphic(null);
            if (empty || p == null) { setText(null); return; }
            if (project != null && p.equals(project.dir)) setText(project.name + "  [" + project.target.label + ", " + project.ramKb + " Ko]");
            else if (labels.containsKey(p)) {
                setText(labels.get(p));
                ContextMenu cm = new ContextMenu();
                String l = labels.get(p);
                if (l.startsWith("Code")) cm.getItems().add(menu("Nouveau fichier source…", StudioApp.this::newSource));
                if (l.startsWith("Sprites")) cm.getItems().add(menu("Nouveau sprite…", StudioApp.this::newSprite));
                if (l.startsWith("Sons")) cm.getItems().add(menu("Nouveau son…", StudioApp.this::newSound));
                if (l.startsWith("Images")) cm.getItems().add(menu("Importer une image PNG…", StudioApp.this::importImage));
                if (l.startsWith("Décors : tuiles")) cm.getItems().add(menu("Nouveau jeu de tuiles…", StudioApp.this::newTileset));
                if (l.startsWith("Décors : cartes")) cm.getItems().add(menu("Nouvelle carte…", StudioApp.this::newMap));
                if (l.startsWith("Animations")) cm.getItems().add(menu("Nouvelle animation…", StudioApp.this::newAnimation));
                if (l.startsWith("Rasters")) cm.getItems().add(menu("Nouvelle table de rasters…", StudioApp.this::newRaster));
                setContextMenu(cm.getItems().isEmpty() ? null : cm);
            } else {
                Path rel = getTreeItem().getParent() != null && getTreeItem().getParent().getValue() != null ? getTreeItem().getParent().getValue().relativize(p) : p.getFileName();
                setText(rel.toString());
                if (p.toString().endsWith(".spr")) {
                    try {
                        Sprite s = Sprite.load(p);
                        Palette pal = sprPalette();
                        Image im = SwingFXUtils.toFXImage(SpriteEditor.thumbnail(s, pal, project.target), null);
                        ImageView iv = new ImageView(im);
                        iv.setFitHeight(16); iv.setPreserveRatio(true); iv.setSmooth(false);
                        setGraphic(iv);
                    } catch (Exception ignored) { }
                }
                ContextMenu cm = new ContextMenu(menu("Ouvrir", () -> openFile(p, 0)), menu("Renommer…", () -> rename(p)), menu("Supprimer", () -> delete(p)));
                if (p.toString().endsWith(".spr")) cm.getItems().add(1, menu("Dupliquer", () -> duplicate(p)));
                setContextMenu(cm);
            }
        }
    }

    private static MenuItem menu(String t, Runnable r) { MenuItem m = new MenuItem(t); m.setOnAction(e -> r.run()); return m; }

    // ================================================================ fichiers et editeurs
    private EditorTab current() { return (EditorTab) (editors.getSelectionModel().getSelectedItem() instanceof EditorTab e ? e : null); }

    public void openFile(Path f, int line) {
        f = f.toAbsolutePath().normalize();
        for (Tab t : editors.getTabs())
            if (t instanceof EditorTab e && e.file.toAbsolutePath().normalize().equals(f)) {
                editors.getSelectionModel().select(t);
                if (line > 0 && e instanceof AsmEditor a) a.gotoLine(line);
                return;
            }
        try {
            String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
            EditorTab tab;
            if (n.endsWith(".spr")) tab = new SpriteEditor(f, () -> project, this::sprPalette);
            else if (n.endsWith(".pal")) tab = new PaletteEditor(f, () -> project, this::palettesChanged);
            else if (n.endsWith(".snd")) tab = new MusicEditor(f);
            else if (n.endsWith(".png")) tab = new ImageViewer(f, () -> project, this::screenPalette);
            else if (n.endsWith(".til")) tab = new TilesetEditor(f, () -> project, this::screenPalette, this::tilesetEdited);
            else if (n.endsWith(".map")) tab = new MapEditor(f, () -> project, this::screenPalette, this::liveTileset, this::tilesetNames, this::tilesetImported);
            else if (n.endsWith(".ani")) tab = new AnimEditor(f, () -> project, this::sprPalette, this::projectSprites);
            else if (n.endsWith(".ras")) tab = new RasterEditor(f, () -> project, this::screenPalette);
            else {
                String rel = relKey(f);
                Path ff = f;
                tab = new AsmEditor(f, breakpoints.getOrDefault(rel, Set.of()).stream().map(x -> x - 1).collect(Collectors.toSet()),
                        () -> breakpointsChanged(ff));
            }
            editors.getTabs().removeIf(t -> !(t instanceof EditorTab));
            editors.getTabs().add(tab);
            editors.getSelectionModel().select(tab);
            tab.setOnCloseRequest(e -> {
                if (tab.isDirty()) {
                    Alert a = new Alert(Alert.AlertType.CONFIRMATION, tab.file.getFileName() + " a été modifié. L'enregistrer ?",
                            ButtonType.YES, ButtonType.NO, ButtonType.CANCEL);
                    a.initOwner(stage);
                    ButtonType r = a.showAndWait().orElse(ButtonType.CANCEL);
                    if (r == ButtonType.CANCEL) e.consume();
                    else if (r == ButtonType.YES) try { tab.save(); } catch (Exception ex) { error("Enregistrement", ex); }
                }
            });
            if (line > 0 && tab instanceof AsmEditor a) Platform.runLater(() -> a.gotoLine(line));
        } catch (Exception e) {
            error("Ouverture de " + f.getFileName(), e);
        }
    }

    /** Palette ouverte (et peut-etre modifiee) dans un onglet, sinon celle du disque. */
    private Palette livePalette(String name) {
        Path f = project.assets("palettes").resolve(name + ".pal");
        for (Tab t : editors.getTabs())
            if (t instanceof PaletteEditor pe && pe.file.toAbsolutePath().equals(f.toAbsolutePath())) return pe.palette();
        try { return Files.exists(f) ? Palette.load(f) : name.equals("ecran") ? fr.gxstudio.gen.AssetCompiler.defaultScreen() : fr.gxstudio.gen.AssetCompiler.defaultSprites(); }
        catch (Exception e) { return new Palette(); }
    }
    private Palette screenPalette() { return livePalette("ecran"); }
    private Palette sprPalette() { return project.target.plus ? livePalette("sprites") : livePalette("ecran"); }

    private void palettesChanged() {
        for (Tab t : editors.getTabs()) if (!(t instanceof PaletteEditor) && t instanceof EditorTab e) e.refresh();
        tree.refresh();
    }

    private void saveCurrent() {
        EditorTab t = current();
        if (t == null) return;
        try { t.save(); status.setText("Enregistré : " + t.file.getFileName()); tree.refresh(); } catch (Exception e) { error("Enregistrement", e); }
    }

    private boolean saveAll() {
        for (Tab t : editors.getTabs())
            if (t instanceof EditorTab e && e.isDirty())
                try { e.save(); } catch (Exception ex) { error("Enregistrement de " + e.file.getFileName(), ex); return false; }
        tree.refresh();
        return true;
    }

    private boolean confirmDiscard() {
        boolean dirty = editors.getTabs().stream().anyMatch(t -> t instanceof EditorTab e && e.isDirty());
        if (!dirty) return true;
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "Des fichiers sont modifiés. Les enregistrer ?", ButtonType.YES, ButtonType.NO, ButtonType.CANCEL);
        a.initOwner(stage);
        ButtonType r = a.showAndWait().orElse(ButtonType.CANCEL);
        if (r == ButtonType.YES) return saveAll();
        return r == ButtonType.NO;
    }

    private String ask(String title, String prompt, String def) {
        TextInputDialog d = new TextInputDialog(def);
        d.setTitle(title);
        d.setHeaderText(null);
        d.setContentText(prompt);
        d.initOwner(stage);
        return d.showAndWait().map(String::trim).filter(s -> !s.isEmpty()).orElse(null);
    }

    private static String fileName(String s) { return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_@-]", "_"); }

    private void newSprite() {
        String n = ask("Nouveau sprite", "Nom du sprite (devient F_NOM dans le code) :", "sprite");
        if (n == null) return;
        Path f = project.assets("sprites").resolve(fileName(n) + ".spr");
        try {
            if (Files.exists(f)) { alert("Ce sprite existe déjà."); return; }
            new Sprite(16, 16).save(f);
            refreshTree();
            openFile(f, 0);
        } catch (Exception e) { error("Nouveau sprite", e); }
    }

    private void newSound() {
        String n = ask("Nouveau son", "Nom du son (devient NOM_OFS, NOM_SPEED, NOM_CH) :", "son");
        if (n == null) return;
        Path f = project.assets("sons").resolve(fileName(n) + ".snd");
        try {
            if (Files.exists(f)) { alert("Ce son existe déjà."); return; }
            Files.createDirectories(f.getParent());
            Files.writeString(f, "; " + n + "\ncanal 0\nvitesse 51\nduree 24\nvolume A 12\nrelache A 8\nA: C5 E5 G5 C6*2\n", StandardCharsets.UTF_8);
            refreshTree();
            openFile(f, 0);
        } catch (Exception e) { error("Nouveau son", e); }
    }

    private void newSource() {
        String n = ask("Nouveau fichier source", "Nom du fichier (.asm, ou .c pour du C) :", "module");
        if (n == null) return;
        boolean c = n.trim().toLowerCase(Locale.ROOT).endsWith(".c");
        String ext = c ? ".c" : ".asm";
        Path f = project.dir.resolve("src").resolve(fileName(n.trim().replaceAll("(?i)\\.(asm|c)$", "")) + ext);
        try {
            if (Files.exists(f)) { alert("Ce fichier existe déjà."); return; }
            if (c) Files.writeString(f, "/* " + f.getFileName() + " : compile automatiquement avec les autres fichiers .c de src/ */\n"
                    + "#include \"gx.h\"\n\n", StandardCharsets.ISO_8859_1);
            else Files.writeString(f, ";=============================================================================\n; " + f.getFileName()
                    + "\n; (a inclure dans " + project.mainFile + " : include \"" + f.getFileName() + "\")\n;=============================================================================\n\n",
                    StandardCharsets.ISO_8859_1);
            refreshTree();
            openFile(f, 0);
        } catch (Exception e) { error("Nouveau fichier", e); }
    }

    // ---------------------------------------------------------------- decors, animations, rasters
    /** Jeu de tuiles ouvert dans un onglet (modifications en cours), sinon lu sur le disque. */
    private Tileset liveTileset(String name) {
        Path f = project.assets("tuiles").resolve(name + ".til").toAbsolutePath();
        for (Tab t : editors.getTabs()) if (t instanceof TilesetEditor te && te.file.toAbsolutePath().equals(f)) return te.ts;
        try { return Files.exists(f) ? Tileset.load(f) : null; } catch (Exception e) { return null; }
    }

    private List<String> tilesetNames() {
        try { return project.list("tuiles", "til").stream().map(Project::baseName).toList(); } catch (Exception e) { return List.of(); }
    }

    private void tilesetEdited() {
        for (Tab t : editors.getTabs()) if (t instanceof MapEditor m) m.refresh();
    }

    /** Une carte a ajoute des tuiles (import PNG) : onglet du jeu a enregistrer, ou enregistrement direct. */
    private void tilesetImported(String name, Tileset ts) {
        Path f = project.assets("tuiles").resolve(name + ".til").toAbsolutePath();
        for (Tab t : editors.getTabs()) if (t instanceof TilesetEditor te && te.file.toAbsolutePath().equals(f)) { te.externalChange(); return; }
        try { ts.save(f); } catch (Exception e) { error("Jeu de tuiles", e); }
    }

    private Map<String, Sprite> projectSprites() {
        Map<String, Sprite> m = new TreeMap<>();
        try {
            for (Path f : project.list("sprites", "spr")) {
                Sprite s = null;
                for (Tab t : editors.getTabs()) if (t instanceof SpriteEditor se && se.file.toAbsolutePath().equals(f.toAbsolutePath())) s = se.sprite();
                m.put(Project.baseName(f).toLowerCase(Locale.ROOT), s != null ? s : Sprite.load(f));
            }
        } catch (Exception ignored) { }
        return m;
    }

    private Path newAsset(String kind, String ext, String title, String prompt, String def) {
        String n = ask(title, prompt, def);
        if (n == null) return null;
        Path f = project.assets(kind).resolve(fileName(n) + "." + ext);
        if (Files.exists(f)) { alert("Ce fichier existe déjà."); return null; }
        return f;
    }

    private void newTileset() {
        Path f = newAsset("tuiles", "til", "Nouveau jeu de tuiles", "Nom (devient TILES_NOM dans le code) :", "decor");
        if (f == null) return;
        try {
            Tileset t = new Tileset();
            t.w = 8; t.h = 8;
            t.addTile();                                  // tuile 0 : vide (encre 0)
            int b = t.addTile();                          // tuile 1 : bloc plein, solide
            for (int[] r : t.tiles.get(b)) java.util.Arrays.fill(r, Math.min(3, Gfx.inks(project.screenMode) - 1));
            t.flags.set(b, 1);
            t.save(f);
            refreshTree();
            openFile(f, 0);
        } catch (Exception e) { error("Nouveau jeu de tuiles", e); }
    }

    private void newMap() {
        List<String> names = tilesetNames();
        if (names.isEmpty()) { alert("Créez d'abord un jeu de tuiles (Projet > Nouveau jeu de tuiles)."); return; }
        Path f = newAsset("cartes", "map", "Nouvelle carte", "Nom (devient MAP_NOM dans le code) :", "niveau1");
        if (f == null) return;
        try {
            TileMap m = new TileMap();
            m.tileset = names.get(0);
            Tileset t = liveTileset(m.tileset);
            int tw = t == null ? 8 : t.w, th = t == null ? 8 : t.h;
            m.resize(Math.max(1, (project.screenMode == 0 ? 160 : 320) / tw), Math.max(1, 200 / th));   // un ecran
            m.save(f);
            refreshTree();
            openFile(f, 0);
        } catch (Exception e) { error("Nouvelle carte", e); }
    }

    private void newAnimation() {
        Path f = newAsset("animations", "ani", "Nouvelle animation", "Nom (devient ANIM_NOM dans le code) :", "marche");
        if (f == null) return;
        try {
            new fr.gxstudio.model.Animation().save(f);
            refreshTree();
            openFile(f, 0);
        } catch (Exception e) { error("Nouvelle animation", e); }
    }

    private void newRaster() {
        if (!project.target.plus) alert("Les rasters sont réservés au CPC+ et à la GX4000 : la table sera ignorée pour un CPC.");
        Path f = newAsset("rasters", "ras", "Nouvelle table de rasters", "Nom (devient RASTER_NOM dans le code) :", "ciel");
        if (f == null) return;
        try {
            RasterTable r = new RasterTable();
            int a = 8, z = 96;
            for (int l = a; l <= z; l += 8) {                       // exemple : degrade du fond (encre 0)
                double k = (double) (l - a) / (z - a);
                r.entries.add(new RasterTable.Entry(l, 0, (int) Math.round(2 + 6 * k) << 4 | (int) Math.round(12 - 6 * k)));
            }
            r.entries.add(new RasterTable.Entry(104, 0, 0x000));
            r.save(f);
            refreshTree();
            openFile(f, 0);
        } catch (Exception e) { error("Nouvelle table de rasters", e); }
    }

    private void importImage() {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images PNG", "*.png"));
        File f = fc.showOpenDialog(stage);
        if (f == null) return;
        try {
            Path to = project.assets("images").resolve(fileName(f.getName().replaceAll("\\.png$", "")) + ".png");
            Files.createDirectories(to.getParent());
            Files.copy(f.toPath(), to, StandardCopyOption.REPLACE_EXISTING);
            refreshTree();
            openFile(to, 0);
        } catch (Exception e) { error("Import", e); }
    }

    private void rename(Path p) {
        String base = Project.baseName(p), ext = p.getFileName().toString().substring(base.length());
        String n = ask("Renommer", "Nouveau nom :", base);
        if (n == null) return;
        try {
            editors.getTabs().removeIf(t -> t instanceof EditorTab e && e.file.equals(p) && !e.isDirty());
            Files.move(p, p.resolveSibling(fileName(n) + ext));
            refreshTree();
        } catch (Exception e) { error("Renommer", e); }
    }

    private void duplicate(Path p) {
        try {
            Path to = p.resolveSibling(Project.baseName(p) + "_2.spr");
            Files.copy(p, to);
            refreshTree();
            openFile(to, 0);
        } catch (Exception e) { error("Dupliquer", e); }
    }

    private void delete(Path p) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "Supprimer " + p.getFileName() + " ?", ButtonType.OK, ButtonType.CANCEL);
        a.initOwner(stage);
        if (a.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        try {
            editors.getTabs().removeIf(t -> t instanceof EditorTab e && e.file.equals(p));
            Files.delete(p);
            refreshTree();
        } catch (Exception e) { error("Supprimer", e); }
    }

    // ================================================================ points d'arret
    private String relKey(Path f) { return project.dir.toAbsolutePath().normalize().relativize(f.toAbsolutePath().normalize()).toString().replace('\\', '/'); }

    private void loadBreakpoints() {
        breakpoints.clear();
        Path f = project.dir.resolve(".breakpoints");
        try {
            if (Files.exists(f)) for (String l : Files.readAllLines(f)) {
                int i = l.lastIndexOf(':');
                if (i > 0) breakpoints.computeIfAbsent(l.substring(0, i), k -> new TreeSet<>()).add(Integer.parseInt(l.substring(i + 1).trim()));
            }
        } catch (Exception ignored) { }
    }

    private void breakpointsChanged(Path f) {
        for (Tab t : editors.getTabs())
            if (t instanceof AsmEditor a && a.file.equals(f)) {
                Set<Integer> s = new TreeSet<>();
                for (int l : a.breakpoints) s.add(l + 1);
                if (s.isEmpty()) breakpoints.remove(relKey(f)); else breakpoints.put(relKey(f), s);
            }
        StringBuilder sb = new StringBuilder();
        breakpoints.forEach((k, v) -> v.forEach(l -> sb.append(k).append(':').append(l).append('\n')));
        try { Files.writeString(project.dir.resolve(".breakpoints"), sb.toString()); } catch (Exception ignored) { }
        status.setText(breakpoints.values().stream().mapToInt(Set::size).sum() + " point(s) d'arrêt");
        syncLiveBreakpoints();
    }

    /** Programme en cours dans Caprice32 : envoie les points d'arret ajoutes ou retires. */
    private void syncLiveBreakpoints() {
        if (!debugPanel.connected() || lastBuild == null) return;
        Set<Integer> want = new HashSet<>();
        breakpoints.forEach((f, set) -> set.forEach(l -> { Integer a = lastBuild.addressOf(f, l); if (a != null) want.add(a); }));
        for (int a : new ArrayList<>(liveBps)) if (!want.contains(a)) { debugPanel.client().removeBreakpoint(a); liveBps.remove(a); }
        for (int a : want) if (liveBps.add(a)) debugPanel.client().addBreakpoint(a);
        boolean edited = editors.getTabs().stream().anyMatch(t -> t instanceof AsmEditor e && e.dirty.get());
        if (edited) status.setText(status.getText() + " (source modifié depuis la compilation : adresses approximatives, recompiler)");
    }

    private void clearBreakpoints() {
        breakpoints.clear();
        for (Tab t : editors.getTabs()) if (t instanceof AsmEditor a) a.clearBreakpoints();
        try { Files.deleteIfExists(project.dir.resolve(".breakpoints")); } catch (Exception ignored) { }
        syncLiveBreakpoints();
    }

    // ================================================================ debogage
    private void stopEmulator() {
        if (debugPanel.client() != null) debugPanel.client().close();
        emulator.stop();
    }

    private void connectDebugger(int port, Builder b) {
        try {
            DebugClient c = new DebugClient(port, debugPanel);
            debugPanel.attach(c, b);
            liveBps.clear();
            c.clearBreakpoints();
            for (int a : b.breakpointAddrs) { c.addBreakpoint(a); liveBps.add(a); }
            if (center.getDividerPositions()[0] > 0.62) center.setDividerPositions(0.6);   // place pour les registres
            log("== Débogueur relié à Caprice32 (port " + port + ") : F6 pause, F8 continuer, F10/F11 pas à pas, onglet Débogage");
        } catch (Exception e) { log("Débogueur : connexion impossible (" + e.getMessage() + ")"); }
    }

    /** Marque la ligne ou le programme est arrete (ouvre le fichier si besoin). */
    private void showPcLine(Builder.SourceLine l) {
        if (pcEditor != null) { pcEditor.setPcLine(-1); pcEditor = null; }
        if (l == null || project == null) return;
        Path f = project.dir.resolve(l.file());
        openFile(f, 0);
        for (Tab t : editors.getTabs())
            if (t instanceof AsmEditor a && a.file.toAbsolutePath().normalize().equals(f.toAbsolutePath().normalize())) {
                editors.getSelectionModel().select(t);
                a.setPcLine(l.line() - 1);
                pcEditor = a;
            }
        if (bottom.getSelectionModel().getSelectedItem() != debugTab) bottom.getSelectionModel().select(debugTab);
    }

    private void watchWordAtCaret() {
        if (!(current() instanceof AsmEditor a)) return;
        String w = a.wordAtCaret();
        if (w.isEmpty()) return;
        List<String> n = new ArrayList<>(debugPanel.watchNames());
        if (!n.contains(w)) n.add(w);
        debugPanel.setWatches(n);
        saveWatches();
        bottom.getSelectionModel().select(debugTab);
    }

    private void saveWatches() {
        try { Files.write(project.dir.resolve(".watches"), debugPanel.watchNames()); } catch (Exception ignored) { }
    }

    private void loadWatches() {
        try {
            Path f = project.dir.resolve(".watches");
            debugPanel.setWatches(Files.exists(f) ? Files.readAllLines(f) : List.of());
        } catch (Exception ignored) { }
    }

    // ================================================================ compilation et lancement
    private void build(boolean run) {
        if (project == null || !saveAll()) return;
        console.clear();
        problems.getItems().clear();
        bottom.getSelectionModel().select(0);
        status.setText("Compilation…");
        Map<String, Set<Integer>> bps = new HashMap<>(breakpoints);
        Builder b = new Builder(project, settings, s -> Platform.runLater(() -> console.appendText(s + "\n")));
        Task<Boolean> task = new Task<>() {
            @Override protected Boolean call() throws Exception { return b.build(bps); }
        };
        task.setOnSucceeded(e -> {
            problems.getItems().setAll(b.messages);
            if (task.getValue()) {
                status.setText("Compilation réussie : " + project.output().getFileName()
                        + (b.breakpointAddrs.isEmpty() ? "" : " - " + b.breakpointAddrs.size() + " point(s) d'arrêt"));
                lastBuild = b;
                debugPanel.setMap(b);
                if (run) {
                    try {
                        if (debugPanel.client() != null) debugPanel.client().close();
                        showPcLine(null);
                        emulator.run(project, settings, s -> Platform.runLater(() -> console.appendText(s + "\n")),
                                port -> Platform.runLater(() -> connectDebugger(port, b)));
                        PauseTransitionHelper.after(5000, () -> {
                            if (!debugPanel.connected() && emulator.running())
                                console.appendText("== Débogage dans le studio indisponible : ce Caprice32 n'a pas le correctif GX Studio 0.4 "
                                        + "(caprice32/LISEZMOI.txt). Au point d'arrêt, Caprice32 ouvre ses propres outils (Maj+F2).\n");
                        });
                    } catch (Exception ex) { error("Caprice32", ex); }
                }
            } else {
                status.setText("Échec de la compilation : " + b.messages.stream().filter(Builder.Message::error).count() + " erreur(s)");
                bottom.getSelectionModel().select(1);
                b.messages.stream().filter(m -> m.error() && m.file() != null).findFirst().ifPresent(m -> openFile(m.file(), m.line()));
            }
        });
        task.setOnFailed(e -> { status.setText("Erreur"); error("Compilation", task.getException()); });
        Thread th = new Thread(task, "build");
        th.setDaemon(true);
        th.start();
    }

    public void log(String s) {
        if (s.startsWith("== Profil sur")) {                                     // rapport : onglet Profil
            profText.setText(s);
            System.out.println(s);
            bottom.getSelectionModel().select(profTab);
            return;
        }
        console.appendText(s + "\n");
    }


    // ================================================================ recherche dans le projet
    private List<Path> sourceFiles() {
        List<Path> out = new ArrayList<>();
        for (String d : List.of("src", "lib")) {
            Path dir = project.dir.resolve(d);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> s = Files.walk(dir)) {
                s.filter(f -> f.toString().matches(".*\\.(asm|inc|c|h)$")).sorted().forEach(out::add);
            } catch (Exception ignored) { }
        }
        for (String g : List.of("gen.asm", "config.inc", "gen.h")) { Path f = project.build().resolve(g); if (Files.exists(f)) out.add(f); }
        return out;
    }

    /** Texte d'un fichier : celui de l'onglet ouvert (modifications comprises), sinon le disque. */
    private List<String> lines(Path f) {
        for (Tab t : editors.getTabs())
            if (t instanceof AsmEditor a && a.file.toAbsolutePath().equals(f.toAbsolutePath())) return Arrays.asList(a.area.getText().split("\n", -1));
        try { return Files.readAllLines(f, StandardCharsets.ISO_8859_1); } catch (Exception e) { return List.of(); }
    }

    private void searchProject() {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Rechercher dans le projet");
        d.initOwner(stage);
        TextField q = new TextField(current() instanceof AsmEditor a ? (a.area.getSelectedText().contains("\n") ? "" : a.area.getSelectedText()) : "");
        q.setPrefColumnCount(32);
        CheckBox cs = new CheckBox("Respecter les majuscules"), word = new CheckBox("Mot entier"), re = new CheckBox("Expression régulière"),
                lib = new CheckBox("Inclure la bibliothèque (lib/) et les fichiers générés");
        lib.setSelected(true);
        VBox v = new VBox(8, new Label("Texte à chercher dans tous les fichiers .asm / .inc / .c / .h :"), q, cs, word, re, lib);
        v.setPadding(new Insets(12));
        d.getDialogPane().setContent(v);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Platform.runLater(q::requestFocus);
        if (d.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK || q.getText().isEmpty()) return;
        searchProjectRun(q.getText(), cs.isSelected(), word.isSelected(), re.isSelected(), lib.isSelected());
    }

    private void searchProjectRun(String text, boolean caseSensitive, boolean wholeWord, boolean regex, boolean withLib) {
        String rx = regex ? text : java.util.regex.Pattern.quote(text);
        if (wholeWord) rx = "(?<![\\w.@])" + rx + "(?![\\w@])";
        java.util.regex.Pattern p;
        try { p = java.util.regex.Pattern.compile(rx, caseSensitive ? 0 : java.util.regex.Pattern.CASE_INSENSITIVE); }
        catch (Exception e) { alert("Expression invalide : " + e.getMessage()); return; }
        List<Hit> res = new ArrayList<>();
        for (Path f : sourceFiles()) {
            String rel = project.dir.relativize(f).toString();
            if (!withLib && !rel.startsWith("src")) continue;
            List<String> ls = lines(f);
            for (int i = 0; i < ls.size() && res.size() < 5000; i++) {
                java.util.regex.Matcher m = p.matcher(ls.get(i));
                if (m.find()) res.add(new Hit(f, i + 1, m.start(), ls.get(i)));
            }
        }
        showHits(res, "« " + text + " »");
    }

    private void showHits(List<Hit> res, String what) {
        hits.getItems().setAll(res);
        searchTab.setText("Recherche (" + res.size() + ")");
        bottom.getSelectionModel().select(searchTab);
        status.setText(res.size() + " résultat(s) pour " + what + " - double-clic pour ouvrir");
    }

    private void openAt(Hit h) {
        openFile(h.file(), h.line());
        Platform.runLater(() -> { if (current() instanceof AsmEditor a) a.gotoLineAndColumn(h.line(), h.col()); });
    }

    /** F12 : cherche la definition de l'etiquette sous le curseur (etiquette:, equ, VAR, macro). */
    private void gotoDefinition() {
        if (!(current() instanceof AsmEditor a)) return;
        String w = a.wordAtCaret();
        if (w.isEmpty()) return;
        String name = java.util.regex.Pattern.quote(w.startsWith(".") ? w.substring(1) : w);
        java.util.regex.Pattern def = java.util.regex.Pattern.compile(
                "^(\\.?" + name + "\\s*:|\\.?" + name + "\\s+(equ|=)\\b|\\s*VAR\\s+" + name + "\\s*,|\\s*macro\\s+" + name + "\\b|" + name + "(\\s|$))",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        List<Hit> res = new ArrayList<>();
        for (Path f : sourceFiles()) {
            List<String> ls = lines(f);
            for (int i = 0; i < ls.size(); i++) {
                String l = ls.get(i);
                if (l.isEmpty() || Character.isWhitespace(l.charAt(0)) && !l.trim().toUpperCase(Locale.ROOT).startsWith("VAR") && !l.trim().toLowerCase(Locale.ROOT).startsWith("macro")) continue;
                if (def.matcher(l).find()) res.add(new Hit(f, i + 1, 0, l));
            }
        }
        if (res.isEmpty()) { status.setText("Définition de « " + w + " » introuvable (étiquette générée par rasm ou faute de frappe ?)"); return; }
        if (res.size() == 1) { openAt(res.get(0)); status.setText("Définition de « " + w + " »"); }
        else showHits(res, "la définition de « " + w + " »");
    }

    private void gotoLineDialog() {
        if (!(current() instanceof AsmEditor a)) return;
        String n = ask("Aller à la ligne", "Numéro de ligne (1 à " + a.area.getParagraphs().size() + ") :", Integer.toString(a.area.getCurrentParagraph() + 1));
        if (n == null) return;
        try { a.gotoLine(Integer.parseInt(n.trim())); } catch (NumberFormatException ignored) { }
    }

    // ================================================================ reglages, aide
    private void settingsDialog() {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Réglages");
        d.initOwner(stage);
        TextField rasm = new TextField(settings.rasm), cap = new TextField(settings.caprice), opt = new TextField(settings.capriceOptions),
                tpl = new TextField(settings.templates), sdcc = new TextField(settings.sdcc);
        for (TextField t : List.of(rasm, cap, opt, tpl, sdcc)) t.setPrefColumnCount(36);
        GridPane g = new GridPane();
        g.setHgap(8); g.setVgap(10); g.setPadding(new Insets(14));
        g.addRow(0, new Label("rasm :"), rasm, pick(rasm, false));
        g.addRow(1, new Label("Caprice32 (cap32) :"), cap, pick(cap, false));
        g.addRow(2, new Label("Options Caprice32 :"), opt);
        g.add(new Label("ex. : -O system.limit_speed=1   (-O section.option=valeur, voir cap32.cfg)"), 1, 3);
        g.addRow(4, new Label("Dossier des modèles :"), tpl, pick(tpl, true));
        g.add(new Label("vide = " + settings.templatesDir()), 1, 5);
        g.addRow(6, new Label("Compilateur C (sdcc) :"), sdcc, pick(sdcc, false));
        g.add(new Label("pour les projets en C (sudo apt install sdcc)"), 1, 7);
        d.getDialogPane().setContent(g);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        if (d.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        settings.rasm = rasm.getText().trim();
        settings.caprice = cap.getText().trim();
        settings.capriceOptions = opt.getText().trim();
        settings.templates = tpl.getText().trim();
        settings.sdcc = sdcc.getText().trim().isEmpty() ? "sdcc" : sdcc.getText().trim();
        settings.save();
    }

    private Button pick(TextField t, boolean dir) {
        Button b = new Button("…");
        b.setOnAction(e -> {
            File f;
            if (dir) f = new DirectoryChooser().showDialog(stage);
            else f = new FileChooser().showOpenDialog(stage);
            if (f != null) t.setText(f.getPath());
        });
        return b;
    }

    /** Liste des routines de la bibliotheque du projet (lignes de commentaire "; nom : ..."). */
    private void libraryHelp() {
        if (project == null) return;
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> s = Files.walk(project.dir.resolve("lib"))) {
            for (Path f : s.filter(x -> x.toString().endsWith(".asm")).sorted().toList()) {
                sb.append("==== ").append(project.dir.relativize(f)).append('\n');
                for (String l : Files.readAllLines(f, StandardCharsets.ISO_8859_1))
                    if (l.matches(";\\s*[a-z_0-9]+\\s*:.*")) sb.append("  ").append(l.substring(1).trim()).append('\n');
            }
        } catch (Exception e) { sb.append(e.getMessage()); }
        TextArea ta = new TextArea(sb.toString());
        ta.setEditable(false);
        ta.getStyleClass().add("console");
        Stage w = new Stage();
        w.initOwner(stage);
        w.setTitle("Routines de la bibliothèque (" + (project.target.plus ? "CPC+ / GX4000" : "CPC") + ")");
        w.setScene(new Scene(ta, 820, 600));
        w.show();
    }

    /** Boite "A propos" : logo et mentions de l'auteur (Stephane ALONSO). */
    private void about() {
        Alert aboutAlert = new Alert(Alert.AlertType.INFORMATION);
        aboutAlert.initOwner(stage);
        aboutAlert.setTitle("À propos de GX Studio");
        aboutAlert.setHeaderText("GX Studio 0.5.2 - Environnement de Développement CPC / CPC+ / GX4000");
        ImageView logoView = new ImageView();
        try {
            logoView.setImage(new Image(getClass().getResourceAsStream("/fr/gxstudio/logo.jpeg")));
            logoView.setFitWidth(128);
            logoView.setPreserveRatio(true);
            logoView.setSmooth(true);
        } catch (Exception e) {
            System.err.println("Impossible de charger le logo : " + e.getMessage());
        }
        javafx.scene.text.Text copyrightText = new javafx.scene.text.Text("(c) 2026 ALONSO Stéphane [Fanou]");
        copyrightText.setStyle("-fx-font-family: 'Courier New'; -fx-font-weight: bold; -fx-fill: #333333;");
        javafx.scene.text.Text descriptionText = new javafx.scene.text.Text(
                "Enseignant & Développeur Passionné d'Architecture 8-bits.\n"
                + "Outil de cross-développement ASM / C optimisé pour Amstrad.\n\n"
                + "Assembleur : rasm (Édouard Bergé) - Compilateur C : SDCC\n"
                + "Émulateur : Caprice32 - Interface : JavaFX, RichTextFX");
        descriptionText.setStyle("-fx-text-alignment: center;");
        VBox contentBox = new VBox(15);
        contentBox.setAlignment(Pos.CENTER);
        contentBox.setPadding(new Insets(10, 10, 10, 10));
        contentBox.getChildren().addAll(logoView, copyrightText, descriptionText);
        aboutAlert.getDialogPane().setContent(contentBox);
        aboutAlert.getDialogPane().getStylesheets().add(getClass().getResource("/fr/gxstudio/studio.css").toExternalForm());
        aboutAlert.showAndWait();
    }

    private void alert(String s) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, s);
        a.initOwner(stage);
        a.setHeaderText(null);
        a.showAndWait();
    }

    private void error(String what, Throwable e) {
        log("ERREUR " + what + " : " + e);
        Alert a = new Alert(Alert.AlertType.ERROR, what + " :\n" + e.getMessage());
        a.initOwner(stage);
        a.showAndWait();
    }

    // ================================================================ captures (tests / documentation)
    /**
     * Mode capture (tests, documentation) : ouvre le projet puis execute une liste d'actions :
     * "fichier[:ligne]" (relatif au projet) ouvre un fichier, "@bp:N" pose un point d'arret ligne N,
     * "@build" compile, "@shot:nom" enregistre une capture, "@new:nom" capture la boite "Nouveau projet",
     * "@props:nom" la boite des proprietes, "@bottom:N" choisit l'onglet du bas.
     */
    private String snapNewName;

    private void snapshotMode(Path project, Path outDir, List<String> actions) {
        openProject(project);
        List<Runnable> steps = new ArrayList<>();
        if (actions.isEmpty()) actions = List.of(this.project.mainFile, "@shot:ui1_main", "assets/sprites/vaisseau.spr", "@shot:ui2_vaisseau",
                "assets/sons/musique.snd", "@shot:ui3_musique", "assets/palettes/ecran.pal", "@shot:ui4_ecran",
                this.project.mainFile + ":120", "@bp:120", "@build", "@shot:ui9_build", "@clearbp");
        for (String act : actions) {
            if (act.startsWith("@shot:")) steps.add(() -> shot(outDir.resolve(act.substring(6) + ".png")));
            else if (act.startsWith("@bp:")) steps.add(() -> { if (current() instanceof AsmEditor a) a.toggleBreakpoint(Integer.parseInt(act.substring(4)) - 1); });
            else if (act.equals("@build")) { steps.add(() -> build(false)); steps.add(() -> { }); steps.add(() -> { }); steps.add(() -> { }); }
            else if (act.equals("@clearbp")) steps.add(this::clearBreakpoints);
            else if (act.equals("@run")) steps.add(() -> build(true));
            else if (act.startsWith("@wait:")) { for (int k = 0; k < Integer.parseInt(act.substring(6)) / 700; k++) steps.add(() -> { }); }
            else if (act.startsWith("@dbg:")) steps.add(() -> {
                switch (act.substring(5)) {
                    case "cont" -> debugPanel.cont();
                    case "pause" -> debugPanel.pause();
                    case "step" -> debugPanel.step();
                    case "over" -> debugPanel.stepOver();
                    case "out" -> debugPanel.stepOut();
                    default -> { }
                }
            });
            else if (act.startsWith("@key:")) steps.add(() -> {          // @key:ligne:bit:0|1 (manette 1 = ligne 9)
                String[] k = act.split(":");
                if (debugPanel.client() != null) debugPanel.client().key(Integer.parseInt(k[1]), Integer.parseInt(k[2]), k[3].equals("1"));
            });
            else if (act.startsWith("@emushot:")) steps.add(() -> { if (debugPanel.client() != null) debugPanel.client().screenshot(outDir.resolve(act.substring(9) + ".png").toAbsolutePath().toString()); });
            else if (act.startsWith("@prof:")) steps.add(() -> debugPanel.profile(Integer.parseInt(act.substring(6))));
            else if (act.startsWith("@watch:")) steps.add(() -> { List<String> n = new ArrayList<>(debugPanel.watchNames()); n.add(act.substring(7)); debugPanel.setWatches(n); });
            else if (act.startsWith("@mem:")) steps.add(() -> debugPanel.setMemAddress(act.substring(5)));
            else if (act.equals("@stop")) steps.add(this::stopEmulator);
            else if (act.startsWith("@insert:")) steps.add(() -> {          // @insert:ligne:nombre (tests)
                String[] a = act.split(":");
                if (current() instanceof AsmEditor e) {
                    int n = Integer.parseInt(a[2]);
                    if (n >= 0) e.area.insertText(Integer.parseInt(a[1]) - 1, 0, "\n".repeat(n));
                    else e.area.deleteText(Integer.parseInt(a[1]) - 1, 0, Integer.parseInt(a[1]) - 1 - n, 0);
                }
            });
            else if (act.equals("@save")) steps.add(this::saveAll);
            else if (act.startsWith("@goto:")) steps.add(() -> {           // @goto:ligne:colonne
                String[] a2 = act.split(":");
                if (current() instanceof AsmEditor a) { a.gotoLineAndColumn(Integer.parseInt(a2[1]), Integer.parseInt(a2[2])); a.area.requestFocus(); }
            });
            else if (act.startsWith("@type:")) steps.add(() -> {           // @type:texte (frappe reelle, \n = Entree)
                if (current() instanceof AsmEditor a)
                    for (char ch : act.substring(6).replace("\\n", "\n").toCharArray())
                        javafx.event.Event.fireEvent(a.area, new KeyEvent(KeyEvent.KEY_TYPED, String.valueOf(ch), "", KeyCode.UNDEFINED, false, false, false, false));
            });
            else if (act.startsWith("@keys:")) steps.add(() -> {           // @keys:CTRL+SPACE,DOWN,ENTER...
                if (current() instanceof AsmEditor a)
                    for (String k : act.substring(6).split(",")) {
                        boolean ctrl = k.contains("CTRL+"), shift = k.contains("SHIFT+");
                        KeyCode code = KeyCode.valueOf(k.replace("CTRL+", "").replace("SHIFT+", ""));
                        javafx.event.Event.fireEvent(a.area, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, ctrl, false, false));
                    }
            });
            else if (act.startsWith("@xshot:")) steps.add(() -> {          // capture de tout l'ecran (fenetres surgissantes comprises)
                try {
                    Files.createDirectories(outDir);
                    new ProcessBuilder("import", "-window", "root", outDir.resolve(act.substring(7) + ".png").toString()).inheritIO().start().waitFor();
                } catch (Exception e) { System.err.println("capture : " + e); }
            });
            else if (act.startsWith("@find:")) steps.add(() -> { if (current() instanceof AsmEditor a) { a.area.selectRange(0, 0); a.showFind(act.startsWith("@find:R")); a.setFindText(act.substring(act.startsWith("@find:R") ? 7 : 6)); a.findNext(true); } });
            else if (act.startsWith("@psearch:")) steps.add(() -> searchProjectRun(act.substring(9), false, true, false, true));
            else if (act.startsWith("@def:")) steps.add(() -> {
                String[] a2 = act.split(":");
                if (current() instanceof AsmEditor a) { a.gotoLineAndColumn(Integer.parseInt(a2[1]), Integer.parseInt(a2[2])); gotoDefinition(); }
            });
            else if (act.startsWith("@bottom:")) steps.add(() -> bottom.getSelectionModel().select(Integer.parseInt(act.substring(8))));
            else if (act.startsWith("@new:") || act.startsWith("@newc:") || act.startsWith("@props:") || act.startsWith("@about:")) steps.add(() -> {
                String name = act.substring(act.indexOf(':') + 1);
                if (act.startsWith("@newc:")) {                 // @newc:fichier[:nom du jeu]
                    String[] q = name.split(":", 2);
                    name = q[0];
                    snapNewName = q.length > 1 ? q[1] : "Casse-Brique C";
                }
                final String shotName = name;
                PauseTransitionHelper.after(900, () -> {
                    for (Window w : new ArrayList<>(Window.getWindows()))
                        if (w != stage && w instanceof Stage st) {
                            try {
                                WritableImage im = st.getScene().snapshot(null);
                                Files.createDirectories(outDir);
                                ImageIO.write(SwingFXUtils.fromFXImage(im, null), "png", outDir.resolve(shotName + ".png").toFile());
                            } catch (Exception e) { System.err.println("capture : " + e); }
                            st.close();
                        }
                });
                if (act.startsWith("@new")) newProject(); else if (act.startsWith("@about")) about(); else projectProperties();
            });
            else {
                String f = act;
                int line = 0;
                int c = act.lastIndexOf(':');
                if (c > 1) { f = act.substring(0, c); line = Integer.parseInt(act.substring(c + 1)); }
                Path path = project.resolve(f);
                int ln = line;
                steps.add(() -> openFile(path, ln));
            }
        }
        steps.add(() -> { stopEmulator(); Platform.exit(); });
        runSteps(steps, 0);
    }

    private void runSteps(List<Runnable> steps, int i) {
        if (i >= steps.size()) return;
        // runLater : une boite de dialogue (showAndWait) est interdite pendant une animation
        PauseTransitionHelper.after(700, () -> Platform.runLater(() -> { steps.get(i).run(); runSteps(steps, i + 1); }));
    }

    private void shot(Path f) {
        try {
            Files.createDirectories(f.getParent());
            WritableImage im = stage.getScene().snapshot(null);
            ImageIO.write(SwingFXUtils.fromFXImage(im, null), "png", f.toFile());
        } catch (Exception e) { System.err.println("capture : " + e); }
    }

    static class PauseTransitionHelper {
        static void after(int ms, Runnable r) {
            javafx.animation.PauseTransition p = new javafx.animation.PauseTransition(javafx.util.Duration.millis(ms));
            p.setOnFinished(e -> r.run());
            p.play();
        }
    }
}
