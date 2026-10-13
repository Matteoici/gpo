package fr.smpboss;

import org.bukkit.Material;

import java.util.List;

/**
 * Une relique : s'équipe dans /reliques (pas dans l'inventaire), ne tombe jamais à la mort.
 * health = cœurs en plus, speed = % de vitesse, regen = demi-cœurs rendus toutes les 4 s hors combat,
 * trail = particules sous les pieds, backpack = c'est le sac (27 cases, visible sur le dos),
 * visual = cosmétique visible (backpack, crown, lantern, wisp, amulet, boots, flames).
 */
public record Relic(String id, String name, Rarity rarity, Material icon, String itemModel, List<String> lore,
                    double health, double speed, double regen, String trail, boolean backpack,
                    String visual) {
}
