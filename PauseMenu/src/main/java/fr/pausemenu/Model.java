package fr.pausemenu;

import java.util.List;

/** Données lues dans config.yml. Aucune dépendance à Bukkit, pour rester simple à tester. */
final class Model {

    private Model() {}

    /** Un bouton. mode = "run" ou "suggest". */
    record Entry(String key, String name, String command, String mode, String description) {
        boolean suggest() {
            return "suggest".equalsIgnoreCase(mode);
        }
    }

    record Category(String key, String name, String color, String description, List<Entry> entries) {}

    record Settings(String title, String buttonLabel, String titleColor, boolean flat,
                    int columns, int buttonWidth, boolean quickActions) {}
}
