package fr.smpevents;

import fr.smpevents.quest.QuestProgress;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Tout ce que le plugin retient sur un joueur. */
public final class PlayerData {

    public final UUID id;
    public String name;

    // ---------------------------------------------------------------- quêtes
    public String day = "";
    public String week = "";
    public final List<QuestProgress> daily = new ArrayList<>();
    public final List<QuestProgress> weekly = new ArrayList<>();
    public boolean dailyBonusGiven;
    public int streak;
    public String lastFullDay = "";
    public int rerollsUsed;

    // ---------------------------------------------------------------- statistiques (classements)
    public double money;
    public long kills, deaths, mobs, playtimeMin;
    public long questsDone, kothWins, bountyEarned, bountyClaims;
    /** valeur des statistiques du jeu au dernier /classement reset (le classement affiche ce qui a été fait depuis) */
    public long killsBase, deathsBase, mobsBase, playtimeBase;
    public long seen;

    // ---------------------------------------------------------------- liens (anti-abus) : id -> dernière fois vu
    public final Map<UUID, Long> guilds = new HashMap<>();
    public final Map<UUID, Long> allies = new HashMap<>();
    public final Map<UUID, Long> friends = new HashMap<>();
    public final Map<String, Long> ips = new HashMap<>();

    public PlayerData(UUID id, String name) {
        this.id = id;
        this.name = name;
    }

    public void forgetOld(long maxAgeMs) {
        long limit = System.currentTimeMillis() - maxAgeMs;
        guilds.values().removeIf(t -> t < limit);
        allies.values().removeIf(t -> t < limit);
        friends.values().removeIf(t -> t < limit);
        ips.values().removeIf(t -> t < limit);
    }
}
