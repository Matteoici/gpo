package fr.smpstart;

import org.bukkit.Material;

import java.util.List;
import java.util.Locale;

/** Une étape du guide (lue dans config.yml > steps). */
public record Step(String key, String name, Material icon, List<String> description, StepType type,
                   List<String> targets, int amount, String hint, Reward reward) {

    /**
     * Le sujet de l'événement compte-t-il pour cette étape ?
     * Matériaux / créatures : nom exact, "*_LOG" (finit par), "COAL*" (commence par), "ANY", "HOSTILE".
     * Commandes : égalité avec la ligne tapée, ou la ligne commence par la cible suivie d'un espace.
     */
    public boolean accepts(String subject, boolean hostile) {
        if (type == StepType.PLAYTIME) return true;
        if (subject == null) return false;
        if (type == StepType.COMMAND) {
            String line = subject.toLowerCase(Locale.ROOT).trim();
            for (String t : targets) {
                String target = t.toLowerCase(Locale.ROOT).trim();
                if (target.isEmpty()) continue;
                if (line.equals(target) || line.startsWith(target + " ")) return true;
            }
            return false;
        }
        String name = subject.toUpperCase(Locale.ROOT);
        for (String t : targets) {
            String p = t.toUpperCase(Locale.ROOT).trim();
            if (p.equals("ANY")) return true;
            if (p.equals("HOSTILE")) {
                if (hostile) return true;
            } else if (p.startsWith("*")) {
                if (name.endsWith(p.substring(1))) return true;
            } else if (p.endsWith("*")) {
                if (name.startsWith(p.substring(0, p.length() - 1))) return true;
            } else if (name.equals(p)) {
                return true;
            }
        }
        return false;
    }
}
