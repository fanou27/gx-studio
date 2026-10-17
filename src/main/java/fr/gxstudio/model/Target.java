package fr.gxstudio.model;

/**
 * Machine cible d'un projet.
 *
 * (c) 2026 ALONSO Stéphane [Fanou]
 */
public enum Target {
    CPC("CPC 464/664/6128", false, true),
    CPC_PLUS("CPC 464+/6128+", true, true),
    GX4000("GX4000", true, false);

    public final String label;
    /** ASIC disponible : sprites materiels, rasters PRI, son DMA, 4096 couleurs. */
    public final boolean plus;
    public final boolean keyboard;

    Target(String label, boolean plus, boolean keyboard) {
        this.label = label; this.plus = plus; this.keyboard = keyboard;
    }

    /** Tailles de RAM proposees (en Ko). La GX4000 n'a que 64 Ko. */
    public int[] ramChoices() {
        return switch (this) {
            case GX4000 -> new int[]{64};
            case CPC_PLUS -> new int[]{64, 128};
            case CPC -> new int[]{64, 128, 256, 512, 576};
        };
    }

    /** Format de sortie : cartouche pour l'ASIC, disquette pour le CPC. */
    public String outputExt() { return plus ? "cpr" : "dsk"; }

    @Override public String toString() { return label; }
}
