package fr.gxstudio.ui;

import javafx.beans.property.*;
import javafx.scene.control.Tab;

import java.nio.file.Path;

/**
 * Onglet d'edition d'un fichier : sait s'enregistrer et signale les modifications.
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public abstract class EditorTab extends Tab {
    public final Path file;
    protected final BooleanProperty dirty = new SimpleBooleanProperty(false);

    protected EditorTab(Path file) {
        this.file = file;
        String n = file.getFileName().toString();
        setText(n);
        dirty.addListener((o, a, d) -> setText(d ? "* " + n : n));
    }

    public boolean isDirty() { return dirty.get(); }
    public abstract void save() throws Exception;
    /** Appele quand la cible ou les palettes changent. */
    public void refresh() { }
}
