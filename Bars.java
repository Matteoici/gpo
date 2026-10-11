package fr.smpguard.gui;

/** Petite barre de couleur pour les scores : vert -> jaune -> rouge. */
final class Bars {

    private Bars() {
    }

    static String bar(double vl, double alertVl) {
        int len = 10;
        double f = Math.min(1, vl / Math.max(0.1, alertVl * 1.5));
        int full = (int) Math.round(f * len);
        String color = f < 0.34 ? "<green>" : f < 0.67 ? "<yellow>" : "<red>";
        return color + "|".repeat(full) + "<dark_gray>" + "|".repeat(len - full);
    }
}
