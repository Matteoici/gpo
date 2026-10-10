package fr.smpstart;

import java.util.List;

/** Récompense d'une étape : argent, objets ("MATERIAL:quantité") et commandes console ({player}). */
public record Reward(double money, List<String> items, List<String> commands) {

    public static final Reward NONE = new Reward(0, List.of(), List.of());

    public boolean isEmpty() {
        return money <= 0 && items.isEmpty() && commands.isEmpty();
    }
}
