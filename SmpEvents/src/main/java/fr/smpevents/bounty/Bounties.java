package fr.smpevents.bounty;

import fr.smpevents.DataStore;
import fr.smpevents.PlayerData;
import fr.smpevents.Relations;
import fr.smpevents.Settings;
import fr.smpevents.SmpEvents;
import fr.smpevents.Texts;
import fr.smpevents.quest.QuestType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static fr.smpevents.Texts.ph;

/**
 * Primes avec anti-abus : la prime n'est PAS versée si le tueur et la victime sont (ou ont été ces X derniers jours)
 * dans la même guilde, dans des guildes alliées, amis, ou sur la même connexion.
 * Dans ce cas la prime reste sur la tête de la victime.
 */
public final class Bounties implements Listener {

    private final SmpEvents plugin;
    private final Map<UUID, Bounty> bounties = new HashMap<>();
    private final File file;

    public boolean enabled;
    public double minAmount;
    public double feePercent;
    public int expireDays;
    public double announceMin;
    public boolean blockLinks;

    public Bounties(SmpEvents plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "bounties.yml");
    }

    public void loadSettings(ConfigurationSection c) {
        if (c == null) c = plugin.getConfig().createSection("bounties");
        enabled = c.getBoolean("enabled", true);
        minAmount = Math.max(1, c.getDouble("min-amount", 5000));
        feePercent = Math.max(0, Math.min(90, c.getDouble("fee-percent", 10)));
        expireDays = Math.max(1, c.getInt("expire-days", 7));
        announceMin = c.getDouble("announce-min", 50000);
        blockLinks = c.getBoolean("block-friends-guild-allies-ip", true);
    }

    // ================================================================= fichier

    public void load() {
        bounties.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("bounties");
        if (root == null) return;
        for (String k : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(k);
            if (s == null) continue;
            try {
                Bounty b = new Bounty(UUID.fromString(k), s.getString("name", "?"));
                b.created = s.getLong("created", System.currentTimeMillis());
                b.updated = s.getLong("updated", b.created);
                b.blockedTries = s.getInt("blocked");
                ConfigurationSection c = s.getConfigurationSection("from");
                if (c != null) {
                    for (String id : c.getKeys(false)) {
                        UUID u = UUID.fromString(id);
                        b.contributions.put(u, c.getDouble(id + ".amount"));
                        b.names.put(u, c.getString(id + ".name", "?"));
                    }
                }
                if (!b.contributions.isEmpty()) bounties.put(b.target, b);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Bounty b : bounties.values()) {
            String p = "bounties." + b.target + ".";
            y.set(p + "name", b.targetName);
            y.set(p + "created", b.created);
            y.set(p + "updated", b.updated);
            y.set(p + "blocked", b.blockedTries);
            for (var e : b.contributions.entrySet()) {
                y.set(p + "from." + e.getKey() + ".amount", e.getValue());
                y.set(p + "from." + e.getKey() + ".name", b.names.getOrDefault(e.getKey(), "?"));
            }
        }
        DataStore.write(file, y.saveToString());
    }

    // ================================================================= accès

    public Collection<Bounty> all() {
        return bounties.values();
    }

    public List<Bounty> sorted() {
        List<Bounty> l = new ArrayList<>(bounties.values());
        l.sort(Comparator.comparingDouble(Bounty::total).reversed());
        return l;
    }

    public Bounty of(UUID target) {
        return bounties.get(target);
    }

    // ================================================================= poser une prime

    public void place(Player from, OfflinePlayer target, double amount) {
        Texts t = plugin.texts();
        Settings s = plugin.settings();
        if (!enabled) {
            t.send(from, "bounty.disabled");
            return;
        }
        if (target.getUniqueId().equals(from.getUniqueId())) {
            t.send(from, "bounty.self");
            return;
        }
        if (amount < minAmount || Double.isNaN(amount) || Double.isInfinite(amount)) {
            t.send(from, "bounty.min", ph("min", s.money(minAmount)));
            return;
        }
        amount = Math.floor(amount);
        if (!plugin.takeMoney(from, amount)) {
            t.send(from, "bounty.no-money", ph("price", s.money(amount)));
            return;
        }
        double net = Math.floor(amount * (1 - feePercent / 100.0));
        String name = target.getName() == null ? "?" : target.getName();
        Bounty b = bounties.computeIfAbsent(target.getUniqueId(), id -> new Bounty(id, name));
        b.targetName = name;
        b.contributions.merge(from.getUniqueId(), net, Double::sum);
        b.names.put(from.getUniqueId(), from.getName());
        b.updated = System.currentTimeMillis();
        save();
        t.send(from, "bounty.placed", ph("player", name), ph("amount", s.money(net)), ph("fee", s.money(amount - net)),
                ph("total", s.money(b.total())));
        Player online = target.getPlayer();
        if (online != null) {
            t.send(online, "bounty.on-you", ph("total", s.money(b.total())));
            online.playSound(Sound.sound(Key.key("minecraft", "entity.wither.spawn"), Sound.Source.MASTER, 0.4f, 1.6f));
        }
        if (b.total() >= announceMin) {
            Bukkit.broadcast(t.prefixed("bounty.broadcast-placed", ph("player", name), ph("total", s.money(b.total()))));
        }
        plugin.boards().recompute();
    }

    // ================================================================= mort de la cible

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Bounty b = bounties.get(victim.getUniqueId());
        if (b == null || !enabled) return;
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) return;
        Texts t = plugin.texts();
        Settings s = plugin.settings();

        if (blockLinks) {
            Relations.Link link = plugin.relations().link(killer, victim);
            if (link != null) {
                b.blockedTries++;
                save();
                String reason = t.raw("bounty.link." + link.name().toLowerCase());
                t.send(killer, "bounty.blocked", ph("player", victim.getName()), Texts.pp("reason", reason));
                String log = killer.getName() + " a tué " + victim.getName() + " (prime " + s.money(b.total())
                        + ") mais ils sont liés : " + link + " -> prime NON versée";
                plugin.getLogger().info(log);
                for (Player o : Bukkit.getOnlinePlayers()) {
                    if (o.hasPermission("smpevents.admin")) {
                        t.send(o, "bounty.staff-blocked", ph("killer", killer.getName()), ph("player", victim.getName()),
                                Texts.pp("reason", reason), ph("total", s.money(b.total())));
                    }
                }
                return;
            }
        }
        bounties.remove(victim.getUniqueId());
        double own = b.contributions.getOrDefault(killer.getUniqueId(), 0.0);
        double total = b.total();
        double gain = total - own;
        plugin.giveMoney(killer, total);
        PlayerData kd = plugin.data().of(killer);
        kd.bountyEarned += (long) gain;
        kd.bountyClaims++;
        plugin.markDirty();
        save();
        fr.smpevents.SmpEvents.titleWin(killer, "bounty");
        t.send(killer, "bounty.claimed", ph("player", victim.getName()), ph("total", s.money(total)));
        killer.playSound(Sound.sound(Key.key("minecraft", "entity.player.levelup"), Sound.Source.MASTER, 1f, 0.8f));
        Bukkit.broadcast(t.prefixed("bounty.broadcast-claimed", ph("killer", killer.getName()), ph("player", victim.getName()),
                ph("total", s.money(total))));
        plugin.quests().progress(killer, QuestType.BOUNTY, "BOUNTY", 1);
        plugin.boards().recompute();
    }

    // ================================================================= expiration / retrait

    /** Toutes les minutes : les primes trop vieilles sont remboursées à ceux qui les ont mises. */
    public void expire() {
        long limit = System.currentTimeMillis() - expireDays * 86_400_000L;
        List<Bounty> old = new ArrayList<>();
        for (Bounty b : bounties.values()) if (b.updated < limit) old.add(b);
        for (Bounty b : old) refund(b, "bounty.expired");
    }

    public boolean cancel(UUID target) {
        Bounty b = bounties.get(target);
        if (b == null) return false;
        refund(b, "bounty.cancelled");
        return true;
    }

    private void refund(Bounty b, String key) {
        bounties.remove(b.target);
        for (var e : b.contributions.entrySet()) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(e.getKey());
            plugin.giveMoney(op, e.getValue());
            Player online = op.getPlayer();
            if (online != null) plugin.texts().send(online, key, ph("player", b.targetName),
                    ph("amount", plugin.settings().money(e.getValue())));
        }
        save();
        plugin.boards().recompute();
    }
}
