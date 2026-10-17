package fr.gxstudio;

import fr.gxstudio.build.*;
import fr.gxstudio.model.*;

import java.nio.file.*;
import java.util.*;

/**
 * Commandes sans interface (scripts, integration continue) :
 *   gxstudio new DOSSIER NOM CIBLE RAM [MODE] [C]  (CIBLE = CPC, CPC_PLUS, GX4000 ; C = projet en C)
 *   gxstudio build DOSSIER [fichier:ligne ...]    (points d'arret)
 *   gxstudio run DOSSIER
 *   gxstudio import MUSIQUE.mid|.mp3|.wav SORTIE.snd [bpm] [secondes]
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public class Cli {
    public static int run(String[] a) throws Exception {
        Settings s = Settings.load();
        switch (a[0]) {
            case "new" -> {
                Target t = Target.valueOf(a[3]);
                int mode = a.length > 5 ? Integer.parseInt(a[5]) : (t.plus ? 1 : 0);
                boolean c = a.length > 6 && a[6].equalsIgnoreCase("C");
                Project p = ProjectFactory.create(Paths.get(a[1]), a[2], t, Integer.parseInt(a[4]), mode, s.templatesDir(), c);
                System.out.println("Projet cree : " + p.dir.toAbsolutePath());
                return 0;
            }
            case "build" -> {
                Project p = Project.load(Paths.get(a[1]));
                Map<String, Set<Integer>> bps = new HashMap<>();
                for (int i = 2; i < a.length; i++) {
                    String[] fl = a[i].split(":");
                    bps.computeIfAbsent(fl[0], k -> new TreeSet<>()).add(Integer.parseInt(fl[1]));
                }
                Builder b = new Builder(p, s, System.out::println);
                boolean ok = b.build(bps);
                for (Builder.Message m : b.messages) System.out.println((m.error() ? "E " : "W ") + m);
                return ok ? 0 : 1;
            }
            case "run" -> {
                Project p = Project.load(Paths.get(a[1]));
                System.out.println(String.join(" ", Emulator.command(p, s)));
                new Emulator().run(p, s, System.out::println);
                return 0;
            }
            case "import" -> {                      // import MUSIQUE.mid|.mp3|.wav SORTIE.snd [bpm] [secondes]
                fr.gxstudio.gen.MusicImport.Options o = new fr.gxstudio.gen.MusicImport.Options();
                if (a.length > 3) o.bpm = Double.parseDouble(a[3]);
                if (a.length > 4) o.maxSeconds = Double.parseDouble(a[4]);
                java.io.File f = new java.io.File(a[1]);
                fr.gxstudio.gen.MusicGrid g = f.getName().toLowerCase().matches(".*\\.(mid|midi)$")
                        ? fr.gxstudio.gen.MusicImport.fromMidi(f, o, System.out::println)
                        : fr.gxstudio.gen.MusicImport.fromAudio(fr.gxstudio.gen.MusicImport.decode(f, System.out::println), o, System.out::println);
                java.nio.file.Files.writeString(Paths.get(a[2]), g.toText());
                fr.gxstudio.gen.SoundCompiler.Result r = fr.gxstudio.gen.SoundCompiler.compile(g.toText());
                System.out.println(g.size() + " pas, " + r.words.size() * 2 + " octets, erreurs : " + r.errors);
                return 0;
            }
            default -> { System.err.println("commande inconnue : " + a[0]); return 2; }
        }
    }
}
