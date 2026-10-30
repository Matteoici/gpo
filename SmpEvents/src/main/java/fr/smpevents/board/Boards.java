package fr.smpevents.board;

import fr.smpevents.PlayerData;
import fr.smpevents.Settings;
import fr.smpevents.SmpEvents;
import fr.smpevents.bounty.Bounty;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Calcul des classements à partir des statistiques des joueurs (en ligne et hors ligne). */
public final class Boards {

    public record Entry(UUID id, String name, double value) {
    }

    private final SmpEvents plugin;
    private final Map<Board, List<Entry>> sorted = new EnumMap<>(Board.class);
    private final ArrayDeque<OfflinePlayer> scanQueue = new ArrayDeque<>();
    private final Set<String> excluded = new HashSet<>();
    public int size;
    public int refreshSeconds;
    private boolean scanning;
    private boolean moneyOnly;
    public int rescanMinutes;

    public Boards(SmpEvents plugin) {
        this.plugin = plugin;
    }

    public void load(ConfigurationSection c) {
        if (c == null) c = plugin.getConfig().createSection("leaderboards");
        size = Math.max(3, Math.min(15, c.getInt("size", 10)));
        refreshSeconds = Math.max(15, c.getInt("refresh-seconds", 60));
        rescanMinutes = Math.max(0, c.getInt("offline-money-refresh-minutes", 30));
        excluded.clear();
        for (String s : c.getStringList("exclude")) excluded.add(s.toLowerCase(Locale.ROOT));
    }

    // ================================================================= relevés

    /** Statistiques à jour pour un joueur en ligne. */
    public void update(Player p) {
        PlayerData d = plugin.data().of(p);
        read(p, d);
        d.seen = System.currentTimeMillis();
    }

    private void read(OfflinePlayer p, PlayerData d) {
        if (plugin.economy() != null) {
            try {
                d.money = plugin.economy().balance(p);
            } catch (Throwable ignored) {
            }
        }
        if (moneyOnly && !p.isOnline()) return;
        try {
            d.kills = p.getStatistic(Statistic.PLAYER_KILLS);
            d.deaths = p.getStatistic(Statistic.DEATHS);
            d.mobs = p.getStatistic(Statistic.MOB_KILLS);
            d.playtimeMin = p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20 / 60;
        } catch (Throwable ignored) {
            // statistiques hors ligne indisponibles : on garde les dernières valeurs connues
        }
    }

    public void updateOnline() {
        for (Player p : Bukkit.getOnlinePlayers()) update(p);
    }

    /** Premier démarrage (ou /classement scan) : lit aussi les joueurs hors ligne, petit à petit pour ne pas faire laguer. */
    public int startScan(boolean all) {
        if (scanning) return scanQueue.size();
        moneyOnly = false;
        scanQueue.clear();
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op.getName() == null || op.isOnline()) continue;
            if (!all && plugin.data().has(op.getUniqueId())) continue;
            scanQueue.add(op);
        }
        scanning = !scanQueue.isEmpty();
        return scanQueue.size();
    }

    /**
     * Toutes les X minutes : relit seulement l'argent des joueurs hors ligne vus ces 30 derniers jours
     * (ils ont pu vendre au marché, recevoir un /pay...). Leurs autres statistiques ne bougent pas hors ligne.
     */
    public void startMoneyRefresh() {
        if (scanning || plugin.economy() == null) return;
        long limit = System.currentTimeMillis() - 30L * 86_400_000L;
        scanQueue.clear();
        for (PlayerData d : plugin.data().all()) {
            if (d.seen < limit || Bukkit.getPlayer(d.id) != null) continue;
            scanQueue.add(Bukkit.getOfflinePlayer(d.id));
        }
        moneyOnly = true;
        scanning = !scanQueue.isEmpty();
    }

    /** Appelé à chaque tick. */
    public void tickScan() {
        if (!scanning) return;
        for (int i = 0; i < (moneyOnly ? 10 : 4) && !scanQueue.isEmpty(); i++) {
            OfflinePlayer op = scanQueue.poll();
            PlayerData d = plugin.data().of(op);
            read(op, d);
            if (d.seen == 0) d.seen = op.getLastSeen();
        }
        if (scanQueue.isEmpty()) {
            scanning = false;
            plugin.markDirty();
            recompute();
            plugin.holograms().renderAll();
            if (!moneyOnly) plugin.getLogger().info("Classements : lecture des joueurs hors ligne terminée.");
            moneyOnly = false;
        }
    }

    public boolean scanning() {
        return scanning;
    }

    public int scanLeft() {
        return scanQueue.size();
    }

    // ================================================================= tri

    public void recompute() {
        for (Board b : Board.values()) {
            List<Entry> list = new ArrayList<>();
            if (b == Board.WANTED) {
                for (Bounty bo : plugin.bounties().all()) list.add(new Entry(bo.target, bo.targetName, bo.total()));
            } else if (b == Board.ELO) {
                for (var en : eloRanking().entrySet()) {
                    String name = Bukkit.getOfflinePlayer(en.getKey()).getName();
                    if (name == null || excluded.contains(name.toLowerCase(Locale.ROOT))) continue;
                    list.add(new Entry(en.getKey(), name, en.getValue()));
                }
            } else {
                for (PlayerData d : plugin.data().all()) {
                    if (d.name == null || excluded.contains(d.name.toLowerCase(Locale.ROOT))) continue;
                    double v = b.value(d);
                    if (v > 0) list.add(new Entry(d.id, d.name, v));
                }
            }
            list.sort(Comparator.comparingDouble(Entry::value).reversed());
            sorted.put(b, list);
        }
    }

    /** ELO des duels (SmpDuel), lu sans dépendance : vide si le plugin n'est pas là. */
    @SuppressWarnings("unchecked")
    private static Map<UUID, Integer> eloRanking() {
        var pl = Bukkit.getPluginManager().getPlugin("SmpDuel");
        if (pl == null || !pl.isEnabled()) return Map.of();
        try {
            Object r = pl.getClass().getMethod("eloRanking").invoke(null);
            return r instanceof Map<?, ?> m ? (Map<UUID, Integer>) m : Map.of();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Map.of();
        }
    }

    public List<Entry> top(Board b, int n) {
        List<Entry> l = sorted.getOrDefault(b, List.of());
        return l.subList(0, Math.min(n, l.size()));
    }

    /** Place du joueur (1 = premier), 0 = pas classé. */
    public int rank(Board b, UUID id) {
        List<Entry> l = sorted.getOrDefault(b, List.of());
        for (int i = 0; i < l.size(); i++) if (l.get(i).id().equals(id)) return i + 1;
        return 0;
    }

    public double valueOf(Board b, UUID id) {
        for (Entry e : sorted.getOrDefault(b, List.of())) if (e.id().equals(id)) return e.value();
        return 0;
    }

    public String format(Board b, double v) {
        Settings s = plugin.settings();
        return switch (b.format) {
            case MONEY -> s.money(v);
            case HOURS -> {
                long min = (long) v;
                yield min >= 60 ? (min / 60) + "h " + String.format("%02d", min % 60) + "m" : min + "m";
            }
            case COUNT -> fr.smpevents.quest.Quests.fmt((long) v);
        };
    }
}
