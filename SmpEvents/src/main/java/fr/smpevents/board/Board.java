package fr.smpevents.board;

import fr.smpevents.PlayerData;
import org.bukkit.Material;

import java.util.Locale;

/** Les classements disponibles. Le nom (key) sert dans les commandes : /classement placer argent */
public enum Board {
    MONEY("argent", Material.GOLD_INGOT, Format.MONEY),
    KILLS("kills", Material.NETHERITE_SWORD, Format.COUNT),
    DEATHS("morts", Material.SKELETON_SKULL, Format.COUNT),
    MOBS("monstres", Material.ZOMBIE_HEAD, Format.COUNT),
    PLAYTIME("temps", Material.CLOCK, Format.HOURS),
    QUESTS("quetes", Material.WRITABLE_BOOK, Format.COUNT),
    KOTH("koth", Material.GOLDEN_HELMET, Format.COUNT),
    HUNTERS("chasseurs", Material.CROSSBOW, Format.MONEY),
    WANTED("primes", Material.TARGET, Format.MONEY);

    public enum Format {
        MONEY, COUNT, HOURS
    }

    public final String key;
    public final Material icon;
    public final Format format;

    Board(String key, Material icon, Format format) {
        this.key = key;
        this.icon = icon;
        this.format = format;
    }

    public static Board of(String s) {
        if (s == null) return null;
        String k = s.toLowerCase(Locale.ROOT).trim();
        for (Board b : values()) if (b.key.equals(k) || b.name().equalsIgnoreCase(k)) return b;
        return switch (k) {
            case "money", "baltop", "balance", "eco" -> MONEY;
            case "kill", "pvp" -> KILLS;
            case "deaths", "mort" -> DEATHS;
            case "mob", "mobs", "monstre" -> MOBS;
            case "playtime", "time", "heures" -> PLAYTIME;
            case "quests", "quete" -> QUESTS;
            case "hunters", "chasseur", "bounty" -> HUNTERS;
            case "wanted", "prime", "recherches" -> WANTED;
            default -> null;
        };
    }

    /** Valeur d'un joueur (WANTED est calculé à part, depuis les primes en cours). */
    public double value(PlayerData d) {
        return switch (this) {
            case MONEY -> d.money;
            case KILLS -> d.kills;
            case DEATHS -> d.deaths;
            case MOBS -> d.mobs;
            case PLAYTIME -> d.playtimeMin;
            case QUESTS -> d.questsDone;
            case KOTH -> d.kothWins;
            case HUNTERS -> d.bountyEarned;
            case WANTED -> 0;
        };
    }
}
