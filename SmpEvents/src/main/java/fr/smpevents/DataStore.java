package fr.smpevents;

import fr.smpevents.quest.QuestProgress;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Sauvegarde des joueurs dans players.yml. */
public final class DataStore {

    private final SmpEvents plugin;
    private final Map<UUID, PlayerData> players = new HashMap<>();
    private final File file;

    DataStore(SmpEvents plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "players.yml");
    }

    public PlayerData get(UUID id) {
        return players.get(id);
    }

    public PlayerData of(OfflinePlayer p) {
        PlayerData d = players.get(p.getUniqueId());
        if (d == null) {
            d = new PlayerData(p.getUniqueId(), p.getName() == null ? "?" : p.getName());
            players.put(p.getUniqueId(), d);
        } else if (p.getName() != null) {
            d.name = p.getName();
        }
        return d;
    }

    public boolean has(UUID id) {
        return players.containsKey(id);
    }

    public Collection<PlayerData> all() {
        return players.values();
    }

    public PlayerData byName(String name) {
        for (PlayerData d : players.values()) if (d.name != null && d.name.equalsIgnoreCase(name)) return d;
        return null;
    }

    // ------------------------------------------------------------------ lecture

    void load() {
        players.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("players");
        if (root == null) return;
        for (String k : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(k);
            if (s == null) continue;
            UUID id;
            try {
                id = UUID.fromString(k);
            } catch (IllegalArgumentException e) {
                continue;
            }
            PlayerData d = new PlayerData(id, s.getString("name", "?"));
            d.day = s.getString("day", "");
            d.week = s.getString("week", "");
            readQuests(s.getStringList("daily"), d.daily);
            readQuests(s.getStringList("weekly"), d.weekly);
            d.dailyBonusGiven = s.getBoolean("bonus-given");
            d.streak = s.getInt("streak");
            d.lastFullDay = s.getString("last-full-day", "");
            d.rerollsUsed = s.getInt("rerolls");
            d.money = s.getDouble("money");
            d.kills = s.getLong("kills");
            d.deaths = s.getLong("deaths");
            d.mobs = s.getLong("mobs");
            d.playtimeMin = s.getLong("playtime");
            d.questsDone = s.getLong("quests");
            d.kothWins = s.getLong("koth");
            d.bountyEarned = s.getLong("bounty-earned");
            d.bountyClaims = s.getLong("bounty-claims");
            d.killsBase = s.getLong("base.kills");
            d.deathsBase = s.getLong("base.deaths");
            d.mobsBase = s.getLong("base.mobs");
            d.playtimeBase = s.getLong("base.playtime");
            d.seen = s.getLong("seen");
            readUuidMap(s.getConfigurationSection("links.guilds"), d.guilds);
            readUuidMap(s.getConfigurationSection("links.allies"), d.allies);
            readUuidMap(s.getConfigurationSection("links.friends"), d.friends);
            ConfigurationSection ips = s.getConfigurationSection("links.ips");
            if (ips != null) for (String ip : ips.getKeys(false)) d.ips.put(ip, ips.getLong(ip));
            players.put(id, d);
        }
    }

    private static void readQuests(List<String> raw, List<QuestProgress> out) {
        for (String line : raw) {
            QuestProgress q = QuestProgress.parse(line);
            if (q != null) out.add(q);
        }
    }

    private static void readUuidMap(ConfigurationSection s, Map<UUID, Long> out) {
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            try {
                out.put(UUID.fromString(k), s.getLong(k));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ écriture

    /** Prépare le texte sur le thread principal, écrit le fichier en arrière-plan. */
    public void save(boolean async) {
        long maxAge = plugin.settings().memoryDays * 86_400_000L;
        YamlConfiguration y = new YamlConfiguration();
        for (PlayerData d : players.values()) {
            d.forgetOld(maxAge);
            String p = "players." + d.id + ".";
            y.set(p + "name", d.name);
            if (!d.day.isEmpty()) {
                y.set(p + "day", d.day);
                y.set(p + "daily", writeQuests(d.daily));
                y.set(p + "bonus-given", d.dailyBonusGiven);
                y.set(p + "rerolls", d.rerollsUsed);
            }
            if (!d.week.isEmpty()) {
                y.set(p + "week", d.week);
                y.set(p + "weekly", writeQuests(d.weekly));
            }
            if (d.streak > 0) y.set(p + "streak", d.streak);
            if (!d.lastFullDay.isEmpty()) y.set(p + "last-full-day", d.lastFullDay);
            y.set(p + "money", d.money);
            setIf(y, p + "kills", d.kills);
            setIf(y, p + "deaths", d.deaths);
            setIf(y, p + "mobs", d.mobs);
            setIf(y, p + "playtime", d.playtimeMin);
            setIf(y, p + "quests", d.questsDone);
            setIf(y, p + "koth", d.kothWins);
            setIf(y, p + "bounty-earned", d.bountyEarned);
            setIf(y, p + "bounty-claims", d.bountyClaims);
            setIf(y, p + "base.kills", d.killsBase);
            setIf(y, p + "base.deaths", d.deathsBase);
            setIf(y, p + "base.mobs", d.mobsBase);
            setIf(y, p + "base.playtime", d.playtimeBase);
            setIf(y, p + "seen", d.seen);
            for (var e : d.guilds.entrySet()) y.set(p + "links.guilds." + e.getKey(), e.getValue());
            for (var e : d.allies.entrySet()) y.set(p + "links.allies." + e.getKey(), e.getValue());
            for (var e : d.friends.entrySet()) y.set(p + "links.friends." + e.getKey(), e.getValue());
            for (var e : d.ips.entrySet()) y.set(p + "links.ips." + e.getKey(), e.getValue());
        }
        String text = y.saveToString();
        if (async) Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(file, text));
        else write(file, text);
    }

    private static void setIf(YamlConfiguration y, String path, long v) {
        if (v != 0) y.set(path, v);
    }

    private static List<String> writeQuests(List<QuestProgress> list) {
        List<String> out = new ArrayList<>();
        for (QuestProgress q : list) out.add(q.serialize());
        return out;
    }

    /** Écriture sûre : fichier temporaire puis remplacement (pas de fichier à moitié écrit si le serveur coupe). */
    public static synchronized void write(File file, String text) {
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            File tmp = new File(file.getPath() + ".tmp");
            Files.writeString(tmp.toPath(), text, StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.writeString(file.toPath(), text, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                Bukkit.getLogger().warning("[SmpEvents] Sauvegarde impossible de " + file.getName() + " : " + ex.getMessage());
            }
        }
    }
}
