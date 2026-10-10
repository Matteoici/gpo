package fr.smpguilds;

import org.bukkit.potion.PotionEffectType;

import java.util.List;

/** Un palier de niveau (lu dans config.yml > levels). */
public record Level(int level, long xp, int maxMembers, int maxClaims, int vaultRows, List<Effect> effects) {

    public record Effect(PotionEffectType type, int amplifier, String label) {}
}
