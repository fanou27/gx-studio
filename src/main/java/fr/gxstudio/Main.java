package fr.gxstudio;

/**
 * Point d'entree : interface graphique, ou commandes (new / build / run).
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public class Main {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && !args[0].startsWith("-")) System.exit(Cli.run(args));
        javafx.application.Application.launch(fr.gxstudio.ui.StudioApp.class, args);
    }
}
