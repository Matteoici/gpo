package fr.smpdrop;

import org.bukkit.Color;

/** Une rareté du butin (commun, rare...). rank : 0 = la plus commune. */
public record Rarity(String id, int rank, double weight, String display, Color color,
                     boolean glow, boolean announce) {
}
