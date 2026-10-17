package fr.gxstudio.gen;

import fr.gxstudio.model.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Transforme les ressources du projet (sprites, palettes, sons, images) en fichiers
 * pour l'assembleur, dans build/ : config.inc, gen.asm, sprites.bin, font.bin, *.bin, save.inc.
 * C'est l'equivalent Java du script tools/gfx.py de STELLAR WINGS.
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public class AssetCompiler {
    public final List<String> messages = new ArrayList<>();
    private final Project p;
    private final StringBuilder asm = new StringBuilder();
    /** incbin des donnees (images, tuiles, cartes) : page 2 de la cartouche ou programme CPC. */
    private final StringBuilder dataIncl = new StringBuilder();
    private List<String> spriteNames = new ArrayList<>();
    /** numero de la premiere image de chaque sprite (sprites materiels hauts : plusieurs images). */
    private final List<Integer> spriteFirst = new ArrayList<>();

    public AssetCompiler(Project p) { this.p = p; }

    private void a(String s) { asm.append(s).append('\n'); }

    public void compile() throws IOException {
        Path b = p.build();
        Files.createDirectories(b);
        writeConfig(b);
        a("; ---- genere par GX Studio a partir de assets/ : ne pas modifier ----");
        Palette screen = palette("ecran", defaultScreen());
        Palette sprPal = palette("sprites", defaultSprites());
        // palettes
        if (p.target.plus) {
            a("pal_screen:  dw " + words(screen.rgb, 16, true));
            a("pal_sprites: dw " + words(Arrays.copyOfRange(sprPal.rgb, 1, 16), 15, true));
        } else {
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < 16; i++) s.append(i > 0 ? "," : "").append(Palette.rgbToFirmware(screen.rgb[i]));
            a("pal_screen:  db " + s + "            ; couleurs firmware 0-26");
        }
        lineAddr();
        sprites(b);
        sounds();
        images(b, screen);
        tiles(b);
        maps(b);
        animations();
        rasters();
        Files.writeString(b.resolve("images.inc"), "; donnees incluses par le programme principal (images, tuiles, cartes)\n" + dataIncl, StandardCharsets.UTF_8);
        try (InputStream in = AssetCompiler.class.getResourceAsStream("/fr/gxstudio/font.bin")) {
            Files.write(b.resolve("font.bin"), in.readAllBytes());
        }
        boolean c = fr.gxstudio.build.CCompiler.hasC(p);
        if (c) asm.append(cAliases(asm.toString() + "\n" + dataIncl));
        Files.writeString(b.resolve("gen.asm"), asm.toString(), StandardCharsets.UTF_8);
        if (c) writeCHeaders(b);
    }

    // ------------------------------------------------------------------ C (SDCC)
    private static final java.util.regex.Pattern EQU = java.util.regex.Pattern.compile("^([A-Za-z_]\\w*)\\s+equ\\s+([^;]+?)\\s*(;.*)?$", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern LAB = java.util.regex.Pattern.compile("^([A-Za-z_]\\w*):");

    /** etiquettes des donnees generees, vues du C sous le nom "_nom" (convention de SDCC) */
    private static List<String> dataLabels(String text) {
        List<String> r = new ArrayList<>();
        for (String l : text.split("\n")) {
            java.util.regex.Matcher m = LAB.matcher(l.trim());
            if (m.find() && !m.group(1).startsWith("_") && !r.contains(m.group(1))) r.add(m.group(1));
        }
        return r;
    }

    private String cAliases(String text) {
        StringBuilder s = new StringBuilder("\n; ---- noms des donnees pour le C (SDCC prefixe les noms par _) ----\n");
        for (String n : dataLabels(text)) s.append("_").append(n).append(" equ ").append(n).append("\n");
        return s.toString();
    }

    /** build/config.h et build/gen.h : les memes constantes et donnees, pour le C. */
    private void writeCHeaders(Path b) throws IOException {
        StringBuilder h = new StringBuilder("/* genere par GX Studio : configuration du projet - ne pas modifier */\n#ifndef GX_CONFIG_H\n#define GX_CONFIG_H\n");
        for (String l : Files.readAllLines(b.resolve("config.inc"))) {
            java.util.regex.Matcher m = EQU.matcher(l.trim());
            if (m.matches()) h.append("#define ").append(m.group(1)).append(" ").append(cValue(m.group(2))).append("\n");
        }
        h.append("#endif\n");
        Files.writeString(b.resolve("config.h"), h.toString(), StandardCharsets.UTF_8);
        String text = Files.readString(b.resolve("gen.asm"), StandardCharsets.UTF_8) + "\n" + dataIncl;
        StringBuilder g = new StringBuilder("/* genere par GX Studio a partir de assets/ : ne pas modifier */\n#ifndef GX_GEN_H\n#define GX_GEN_H\n#include \"config.h\"\n");
        g.append("\n/* donnees (tables, palettes, cartes, tuiles, animations, rasters) */\n");
        for (String n : dataLabels(text)) {
            if (n.equals("line_addr")) g.append("extern const unsigned int line_addr[];      /* adresse de chaque ligne de l'ecran */\n");
            else g.append("extern const unsigned char ").append(n).append("[];\n");
        }
        g.append("\n/* constantes (numeros des sprites, sons, tailles...) */\n");
        for (String l : text.split("\n")) {
            java.util.regex.Matcher m = EQU.matcher(l.trim());
            if (!m.matches() || m.group(1).startsWith("_")) continue;
            String v = cValue(m.group(2));
            g.append("#define ").append(m.group(1)).append(" ").append(v).append("\n");
        }
        g.append("#endif\n");
        Files.writeString(b.resolve("gen.h"), g.toString(), StandardCharsets.UTF_8);
    }

    /** valeur rasm -> C : #1F / &1F -> 0x1F, %101 -> 0b101 */
    private static String cValue(String v) {
        String s = v.trim().replaceAll("[#&]([0-9A-Fa-f]+)", "0x$1").replaceAll("%([01]+)", "0b$1");
        return s.matches("[\\w]+") ? s : "(" + s + ")";
    }

    private void writeConfig(Path b) throws IOException {
        Target t = p.target;
        String c = "; ---- configuration du projet (GX Studio) ----\n"
                + "TARGET_CPC      equ " + (t == Target.CPC ? 1 : 0) + "\n"
                + "TARGET_PLUS     equ " + (t == Target.CPC_PLUS ? 1 : 0) + "\n"
                + "TARGET_GX4000   equ " + (t == Target.GX4000 ? 1 : 0) + "\n"
                + "ASIC            equ " + (t.plus ? 1 : 0) + "               ; sprites materiels, rasters, DMA\n"
                + "HAS_KEYBOARD    equ " + (t.keyboard ? 1 : 0) + "\n"
                + "SOUND_SW        equ " + (t.plus ? 0 : 1) + "               ; 1 = lecteur de son logiciel\n"
                + "RAM_KB          equ " + p.ramKb + "\n"
                + "SCREEN_MODE     equ " + p.screenMode + "\n";
        Files.writeString(b.resolve("config.inc"), c, StandardCharsets.UTF_8);
    }

    private Palette palette(String name, Palette def) throws IOException {
        Path f = p.assets("palettes").resolve(name + ".pal");
        if (Files.exists(f)) return Palette.load(f);
        def.save(f);
        return def;
    }

    public static Palette defaultScreen() {
        return Palette.of(0x001, 0x00F, 0x0F0, 0xFFF, 0xF00, 0xFF0, 0x0FF, 0xF0F,
                          0x666, 0x00A, 0x0A0, 0xAAA, 0xA00, 0xFA0, 0x06F, 0xF6A);
    }

    public static Palette defaultSprites() {
        return Palette.of(0x000, 0xFFF, 0xAAF, 0x66F, 0xF44, 0xFA0, 0xFF4, 0x4F4,
                          0x0AA, 0xF4F, 0xA4F, 0x888, 0x444, 0x0FF, 0xFF8, 0xF80);
    }

    private static String words(int[] rgb, int n, boolean asic) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < n; i++) s.append(i > 0 ? "," : "").append(String.format("#%04X", Palette.asicWord(rgb[i])));
        return s.toString();
    }

    private void lineAddr() {
        a("line_addr:                              ; adresse de chaque ligne de l'ecran (&C000)");
        for (int y = 0; y < 200; y += 8) {
            StringBuilder s = new StringBuilder(" dw ");
            for (int k = 0; k < 8; k++) s.append(k > 0 ? "," : "").append(String.format("#%04X", 0xC000 + (y + k) / 8 * 80 + ((y + k) % 8) * 2048));
            a(s.toString());
        }
    }

    // ------------------------------------------------------------------ sprites
    private void sprites(Path b) throws IOException {
        List<Path> files = p.list("sprites", "spr");
        spriteNames = new ArrayList<>();
        for (Path f : files) spriteNames.add(Project.baseName(f).toLowerCase(Locale.ROOT));
        int i = 0;
        spriteFirst.clear();
        if (!p.target.plus) for (int k = 0; k < files.size(); k++) spriteFirst.add(k);
        if (!p.target.plus) for (Path f : files) a("F_" + Project.ident(f) + " equ " + i++);
        a("NB_FRAMES equ " + files.size());
        // images en mode 0 (2 pixels par octet, ligne par ligne, largeur paire) de tous les
        // sprites, pour toutes les cibles : build/sprites_m0.bin (dessin logiciel, decor...)
        ByteArrayOutputStream m0 = new ByteArrayOutputStream();
        for (Path f : files) {
            Sprite s = Sprite.load(f);
            int wb = (s.w + 1) / 2;
            a("M0_" + Project.ident(f) + " equ " + m0.size() + "          ; " + s.w + "x" + s.h + ", " + wb * s.h + " octets dans sprites_m0.bin");
            for (int y = 0; y < s.h; y++)
                for (int x = 0; x < wb * 2; x += 2) {
                    int p0 = s.px[y][x], p1 = x + 1 < s.w ? s.px[y][x + 1] : 0;
                    m0.write(mode0(p0, 0) | mode0(p1, 1));
                }
        }
        a("SPRITES_M0_SIZE equ " + m0.size());
        Files.write(b.resolve("sprites_m0.bin"), m0.toByteArray());
        if (p.target.plus) {
            // sprites materiels : 16 pixels de large ; un sprite plus haut que 16 lignes
            // occupe plusieurs images 16x16 empilees (F_nom = la premiere, H_nom = leur nombre)
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int img = 0;
            for (Path f : files) {
                Sprite s = Sprite.load(f);
                int n = Math.max(1, (s.h + 15) / 16);
                if (s.w > 16) messages.add(f.getFileName() + " : sprite materiel limite a 16 pixels de large");
                a("F_" + Project.ident(f) + " equ " + img);
                spriteFirst.add(img);
                if (n > 1) a("H_" + Project.ident(f) + " equ " + n + "                ; " + s.h + " lignes : " + n + " images 16x16");
                for (int k = 0; k < n; k++)
                    for (int y = 16 * k; y < 16 * k + 16; y++)
                        for (int x = 0; x < 16; x++) out.write(y < s.h && x < s.w ? s.px[y][x] & 15 : 0);
                img += n;
            }
            a("NB_HWIMG equ " + img + "                ; images 16x16 dans sprites.bin (256 octets chacune)");
            if (img > 64) messages.add("attention : " + img + " images 16x16, plus de 64 : sprites.bin depasse une page de 16 Ko");
            if (files.isEmpty()) out.write(new byte[256]);
            Files.write(b.resolve("sprites.bin"), out.toByteArray());
        } else {
            a("spr_table:");
            for (Path f : files) a(" dw spr_" + Project.ident(f).toLowerCase(Locale.ROOT));
            int maxBytes = 1;
            for (Path f : files) {
                Sprite s = Sprite.load(f);
                int wb = (s.w + 1) / 2;
                maxBytes = Math.max(maxBytes, wb * s.h);
                a("spr_" + Project.ident(f).toLowerCase(Locale.ROOT) + ":  db " + wb + "," + s.h + "   ; " + s.w + "x" + s.h + " pixels mode 0");
                for (int y = 0; y < s.h; y++) {
                    StringBuilder l = new StringBuilder(" db ");
                    for (int x = 0; x < wb * 2; x += 2) {
                        int p0 = x < s.w ? s.px[y][x] : 0, p1 = x + 1 < s.w ? s.px[y][x + 1] : 0;
                        int mask = (p0 == 0 ? 0xAA : 0) | (p1 == 0 ? 0x55 : 0);
                        int data = mode0(p0, 0) | mode0(p1, 1);
                        l.append(x > 0 ? "," : "").append(String.format("#%02X,#%02X", mask, data));
                    }
                    a(l.toString());
                }
            }
            a("SPR_MAX_BYTES equ " + maxBytes + "          ; plus grand sprite (octets)");
        }
    }

    /** Octet mode 0 : pixel 0 = bits 7,3,5,1 ; pixel 1 = bits 6,2,4,0 (bit 0 de l'encre en premier). */
    public static int mode0(int pen, int pix) {
        int v = ((pen & 1) << 7) | ((pen & 2) << 2) | ((pen & 4) << 3) | ((pen & 8) >> 2);
        return pix == 0 ? v : v >> 1;
    }

    /** Octet mode 1 : pixel i (0-3) : bit 0 de l'encre en 7-i, bit 1 en 3-i. */
    public static int mode1(int pen, int pix) {
        return ((pen & 1) << (7 - pix)) | (((pen >> 1) & 1) << (3 - pix));
    }

    // ------------------------------------------------------------------ sons
    private void sounds() throws IOException {
        List<Path> files = p.list("sons", "snd");
        a("snd_lists:");
        int off = 0;
        for (Path f : files) {
            SoundCompiler.Result r = SoundCompiler.compile(Files.readString(f, StandardCharsets.UTF_8));
            for (String e : r.errors) messages.add(f.getFileName() + " : " + e);
            String id = Project.ident(f);
            a(id + "_OFS equ " + off);
            a(id + "_SPEED equ " + r.speed);
            a(id + "_CH equ " + r.channel);
            List<Integer> w = r.words;
            for (int i = 0; i < w.size(); i += 12) {
                StringBuilder s = new StringBuilder(" dw ");
                for (int k = i; k < Math.min(w.size(), i + 12); k++) s.append(k > i ? "," : "").append(String.format("#%04X", w.get(k)));
                a(s.toString());
            }
            off += 2 * w.size();
        }
        a("SND_LISTS_SIZE equ " + off);
        if (p.target.plus) { a("dma_lists equ snd_lists"); a("DMA_LISTS_SIZE equ SND_LISTS_SIZE"); }
    }

    // ------------------------------------------------------------------ images
    private void images(Path b, Palette screen) throws IOException {
        List<Path> files = p.list("images", "png");
        int mode = p.screenMode;
        int ppb = mode == 0 ? 2 : mode == 1 ? 4 : 8, ncol = mode == 0 ? 16 : mode == 1 ? 4 : 2;
        Map<Integer, ByteArrayOutputStream> banks = new TreeMap<>();
        StringBuilder incl = new StringBuilder();
        for (Path f : files) {
            BufferedImage im = ImageIO.read(f.toFile());
            if (im == null) { messages.add(f.getFileName() + " : image illisible"); continue; }
            String base = Project.baseName(f);
            int bank = 0;
            if (base.contains("@")) {
                bank = Integer.parseInt(base.substring(base.indexOf('@') + 1));
                base = base.substring(0, base.indexOf('@'));
            }
            String id = base.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
            int wb = (im.getWidth() + ppb - 1) / ppb, h = im.getHeight();
            byte[] data = new byte[wb * h];
            for (int y = 0; y < h; y++)
                for (int x = 0; x < wb * ppb; x++) {
                    int pen = 0;
                    if (x < im.getWidth()) {
                        int argb = im.getRGB(x, y);
                        if ((argb >>> 24) >= 128) pen = nearest(argb, screen, ncol, p.target);
                    }
                    int v = mode == 0 ? mode0(pen, x % 2) : mode == 1 ? mode1(pen, x % 4) : (pen & 1) << (7 - x % 8);
                    data[y * wb + x / ppb] |= (byte) v;
                }
            a("IMG_" + id + "_W equ " + wb + "               ; largeur en octets");
            a("IMG_" + id + "_H equ " + h);
            if (bank >= 4 && !p.target.plus) {
                if (p.ramKb < 128) { messages.add(f.getFileName() + " : banque " + bank + " impossible avec " + p.ramKb + " Ko"); continue; }
                ByteArrayOutputStream bo = banks.computeIfAbsent(bank, k -> new ByteArrayOutputStream());
                a("IMG_" + id + " equ #" + Integer.toHexString(0x4000 + bo.size()).toUpperCase());
                a("IMG_" + id + "_BANK equ #" + Integer.toHexString(bankConfig(bank)).toUpperCase() + "     ; a passer a bank_set");
                bo.write(data);
                if (bo.size() > 16384) messages.add("banque " + bank + " : plus de 16 Ko !");
            } else {
                Files.write(b.resolve("img_" + id.toLowerCase(Locale.ROOT) + ".bin"), data);
                incl.append(String.format("IMG_%s: incbin \"img_%s.bin\"%n", id, id.toLowerCase(Locale.ROOT)));
            }
        }
        dataIncl.append(incl);
        // fichiers des banques (CPC 128 Ko et plus) + table de chargement
        a("extra_files:                            ; banque, adresse, nom (charges au demarrage)");
        StringBuilder save = new StringBuilder("; sauvegarde sur la disquette (GX Studio)\n");
        for (var e : banks.entrySet()) {
            String fn = "BANK" + e.getKey() + ".BIN";
            Files.write(b.resolve(fn.toLowerCase(Locale.ROOT)), e.getValue().toByteArray());
            a(String.format(" db #%02X : dw #4000 : db %d,\"%s\"", bankConfig(e.getKey()), fn.length(), fn));
        }
        a(" db 0");
        if (!p.target.plus) {
            String dsk = p.output().toAbsolutePath().toString();
            save.append(String.format("        save \"%s.BIN\",cpc_start,code_end-cpc_start,DSK,\"%s\"%n", p.shortName(), dsk));
            for (var e : banks.entrySet())
                save.append(String.format("        bank%n        org #4000%n        incbin \"%s\"%n        save \"BANK%d.BIN\",#4000,%d,DSK,\"%s\"%n",
                        b.resolve("bank" + e.getKey() + ".bin").toAbsolutePath(), e.getKey(), e.getValue().size(), dsk));
            Files.writeString(b.resolve("save.inc"), save.toString(), StandardCharsets.UTF_8);
        }
    }


    // ------------------------------------------------------------------ tuiles
    private int ppb() { return p.screenMode == 0 ? 2 : p.screenMode == 1 ? 4 : 8; }

    private int encode(int pen, int x) {
        int m = p.screenMode;
        return m == 0 ? mode0(pen, x % 2) : m == 1 ? mode1(pen & 3, x % 4) : (pen & 1) << (7 - x % 8);
    }

    private void tiles(Path b) throws IOException {
        for (Path f : p.list("tuiles", "til")) {
            Tileset t = Tileset.load(f);
            String id = Project.ident(f);
            int ppb = ppb();
            if (t.w % ppb != 0) messages.add(f.getFileName() + " : largeur " + t.w + " non multiple de " + ppb + " pixels (mode " + p.screenMode + ")");
            int wb = (t.w + ppb - 1) / ppb;
            byte[] data = new byte[wb * t.h * t.tiles.size()];
            int o = 0;
            for (int[][] tile : t.tiles)
                for (int y = 0; y < t.h; y++) {
                    for (int x = 0; x < wb * ppb; x++) data[o + x / ppb] |= (byte) encode(x < t.w ? tile[y][x] : 0, x);
                    o += wb;
                }
            Files.write(b.resolve("tiles_" + id.toLowerCase(Locale.ROOT) + ".bin"), data);
            dataIncl.append(String.format("TILES_%s: incbin \"tiles_%s.bin\"%n", id, id.toLowerCase(Locale.ROOT)));
            a("TILES_" + id + "_W equ " + wb + "            ; largeur d'une tuile (octets)");
            a("TILES_" + id + "_H equ " + t.h + "            ; hauteur (lignes)");
            a("TILES_" + id + "_N equ " + t.tiles.size() + "            ; nombre de tuiles");
            StringBuilder fl = new StringBuilder("TFLAGS_" + id + ":  db ");
            for (int i = 0; i < t.flags.size(); i++) fl.append(i > 0 ? "," : "").append(t.flags.get(i));
            a(fl + "   ; attributs (bit 0 = solide)");
        }
    }

    private void maps(Path b) throws IOException {
        for (Path f : p.list("cartes", "map")) {
            TileMap m = TileMap.load(f);
            String id = Project.ident(f);
            byte[] data = new byte[m.w * m.h];
            for (int y = 0; y < m.h; y++) for (int x = 0; x < m.w; x++) data[y * m.w + x] = (byte) m.cells[y][x];
            Files.write(b.resolve("map_" + id.toLowerCase(Locale.ROOT) + ".bin"), data);
            dataIncl.append(String.format("MAP_%s: incbin \"map_%s.bin\"%n", id, id.toLowerCase(Locale.ROOT)));
            a("MAP_" + id + "_W equ " + m.w + "            ; largeur (cases)");
            a("MAP_" + id + "_H equ " + m.h + "            ; hauteur (cases)");
            String ts = m.tileset.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
            if (!Files.exists(p.assets("tuiles").resolve(m.tileset + ".til")))
                messages.add(f.getFileName() + " : jeu de tuiles '" + m.tileset + "' introuvable");
            else a("MAP_" + id + "_TILES equ TILES_" + ts + "   ; son jeu de tuiles");
        }
    }

    // ------------------------------------------------------------------ animations
    private void animations() throws IOException {
        for (Path f : p.list("animations", "ani")) {
            Animation an = Animation.load(f);
            String id = Project.ident(f);
            StringBuilder s = new StringBuilder();
            for (String fr : an.frames) {
                int i = spriteNames.indexOf(fr.toLowerCase(Locale.ROOT));
                if (i < 0) { messages.add(f.getFileName() + " : sprite '" + fr + "' introuvable"); i = 0; }
                s.append(",").append(i < spriteFirst.size() ? spriteFirst.get(i) : i);
            }
            if (an.frames.isEmpty()) { messages.add(f.getFileName() + " : animation vide"); s.append(",0"); }
            a(String.format("ANIM_%s:  db %d,%d,%d%s   ; nombre, delai, mode (0 boucle, 1 une fois, 2 aller-retour), images",
                    id, Math.max(1, an.frames.size()), an.delay, an.mode, s));
        }
    }

    // ------------------------------------------------------------------ rasters
    private void rasters() throws IOException {
        List<Path> files = p.list("rasters", "ras");
        if (!files.isEmpty() && !p.target.plus) { messages.add("rasters ignores : reserves au CPC+ et a la GX4000"); return; }
        for (Path f : files) {
            RasterTable r = RasterTable.load(f);
            for (String e : r.check()) messages.add(f.getFileName() + " : " + e);
            String id = Project.ident(f);
            List<RasterTable.Entry> e = r.entries;
            a("RASTER_" + id + ":                          ; table pour raster_start (type 4 : encre quelconque)");
            if (e.isEmpty()) { a(" db 199"); }
            else {
                a(" db " + e.get(0).line());
                for (int i = 0; i < e.size(); i++) {
                    RasterTable.Entry x = e.get(i);
                    int next = i + 1 < e.size() ? e.get(i + 1).line() : 199;
                    a(String.format(" db 4 : dw #%04X : db %d       ; ligne %d : encre %d = #%03X",
                            (x.ink() & 15) << 12 | Palette.asicWord(x.rgb()), next, x.line(), x.ink(), x.rgb()));
                }
            }
            a(" db 2 : dw 0 : db ARM_LINE       ; fin d'image (ligne 199)");
            a(" db 3 : dw 0 : db 0              ; armement (ligne 304)");
        }
    }

    /** Banque n (4..) -> valeur Gate Array qui la place en &4000. */
    public static int bankConfig(int n) { return 0xC4 + ((n - 4) / 4) * 8 + (n % 4); }

    private static int nearest(int argb, Palette pal, int ncol, Target t) {
        int rgb = ((argb >> 20) & 15) << 8 | ((argb >> 12) & 15) << 4 | ((argb >> 4) & 15);
        int best = 0, bd = Integer.MAX_VALUE;
        for (int i = 0; i < ncol; i++) {
            int d = Palette.dist(Palette.shown(pal.rgb[i], t), rgb);
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }
}
