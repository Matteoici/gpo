package fr.smpevents.afk;

import fr.smpevents.DataStore;
import fr.smpevents.Hooks;
import fr.smpevents.Settings;
import fr.smpevents.SmpEvents;
import fr.smpevents.Texts;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static fr.smpevents.Texts.ph;

/**
 * Zones AFK : rester dans la zone rapporte une récompense toutes les X minutes.
 * Une seule récompense par connexion (adresse IP) : les doubles comptes ne gagnent rien de plus.
 * Personne ne prend de dégâts ni ne perd de faim dans la zone.
 */
public final class AfkZones implements Listener {

    /** Zone rectangulaire (deux coins). */
    public record Zone(String name, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        boolean contains(Location l) {
            return l.getWorld() != null && l.getWorld().getName().equals(world)
                    && l.getX() >= minX && l.getX() < maxX + 1 && l.getY() >= minY && l.getY() < maxY + 1
                    && l.getZ() >= minZ && l.getZ() < maxZ + 1;
        }
    }

    public record Reward(double chance, double money, List<String> commands, String label) {
    }

    private final SmpEvents plugin;
    private final File file;
    private final Map<String, Zone> zones = new LinkedHashMap<>();
    private Location spawn;
    private TextDisplay label;

    private final Map<UUID, Location[]> selections = new HashMap<>();
    private final Map<UUID, Integer> seconds = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<UUID, Integer> today = new HashMap<>();
    private String todayKey = "";

    // réglages
    public boolean enabled;
    public int intervalSeconds;
    public double money;
    public List<String> commands = List.of();
    public final List<Reward> bonus = new ArrayList<>();
    public int maxPerDay;
    public boolean resetOnLeave;
    public boolean protect;
    public boolean onePerIp;
    public int teleportDelay;

    public AfkZones(SmpEvents plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "afk.yml");
    }

    public void loadSettings(ConfigurationSection c) {
        if (c == null) c = plugin.getConfig().createSection("afk-zone");
        enabled = c.getBoolean("enabled", true);
        intervalSeconds = Math.max(30, c.getInt("every-minutes", 10) * 60);
        money = c.getDouble("money", 10000);
        commands = c.getStringList("commands");
        bonus.clear();
        for (Map<?, ?> m : c.getMapList("bonus")) {
            double chance = num(m.get("chance"), 0);
            double mon = num(m.get("money"), 0);
            List<String> cmds = new ArrayList<>();
            if (m.get("commands") instanceof List<?> l) for (Object o : l) cmds.add(String.valueOf(o));
            String lab = m.get("label") == null ? "" : String.valueOf(m.get("label"));
            if (chance > 0) bonus.add(new Reward(chance, mon, cmds, lab));
        }
        maxPerDay = Math.max(0, c.getInt("max-rewards-per-day", 0));
        resetOnLeave = c.getBoolean("reset-timer-on-leave", true);
        protect = c.getBoolean("protect-players", true);
        onePerIp = c.getBoolean("one-reward-per-ip", true);
        teleportDelay = Math.max(0, c.getInt("teleport-delay-seconds", 3));
    }

    private static double num(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        try {
            return o == null ? def : Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ================================================================= fichier

    public void load() {
        zones.clear();
        spawn = null;
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("zones");
        if (root != null) {
            for (String k : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(k);
                if (s == null) continue;
                zones.put(k.toLowerCase(Locale.ROOT), new Zone(k, s.getString("world", "world"), s.getInt("min-x"), s.getInt("min-y"),
                        s.getInt("min-z"), s.getInt("max-x"), s.getInt("max-y"), s.getInt("max-z")));
            }
        }
        if (y.isConfigurationSection("spawn")) {
            World w = Bukkit.getWorld(y.getString("spawn.world", "world"));
            if (w != null) {
                spawn = new Location(w, y.getDouble("spawn.x"), y.getDouble("spawn.y"), y.getDouble("spawn.z"),
                        (float) y.getDouble("spawn.yaw"), (float) y.getDouble("spawn.pitch"));
            }
        }
        todayKey = y.getString("day", "");
        ConfigurationSection t = y.getConfigurationSection("today");
        if (t != null) {
            for (String k : t.getKeys(false)) {
                try {
                    today.put(UUID.fromString(k), t.getInt(k));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Zone z : zones.values()) {
            String p = "zones." + z.name + ".";
            y.set(p + "world", z.world);
            y.set(p + "min-x", z.minX);
            y.set(p + "min-y", z.minY);
            y.set(p + "min-z", z.minZ);
            y.set(p + "max-x", z.maxX);
            y.set(p + "max-y", z.maxY);
            y.set(p + "max-z", z.maxZ);
        }
        if (spawn != null) {
            y.set("spawn.world", spawn.getWorld().getName());
            y.set("spawn.x", spawn.getX());
            y.set("spawn.y", spawn.getY());
            y.set("spawn.z", spawn.getZ());
            y.set("spawn.yaw", spawn.getYaw());
            y.set("spawn.pitch", spawn.getPitch());
        }
        y.set("day", todayKey);
        for (var e : today.entrySet()) y.set("today." + e.getKey(), e.getValue());
        DataStore.write(file, y.saveToString());
    }

    // ================================================================= zone d'un joueur

    public Zone zoneAt(Location l) {
        for (Zone z : zones.values()) if (z.contains(l)) return z;
        return null;
    }

    private boolean counts(Player p) {
        return !p.isDead() && !Hooks.vanished(p) && zoneAt(p.getLocation()) != null;
    }

    // ================================================================= chaque seconde

    public void tickSecond() {
        String day = plugin.settings().dayKey();
        if (!day.equals(todayKey)) {
            todayKey = day;
            today.clear();
        }
        if (!enabled || zones.isEmpty()) {
            for (UUID id : new ArrayList<>(bars.keySet())) hideBar(id);
            return;
        }
        // une seule personne récompensée par connexion : la première trouvée dans la zone
        Set<String> ipsUsed = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            if (!counts(p)) {
                if (resetOnLeave) seconds.remove(id);
                hideBar(id);
                continue;
            }
            String ip = ip(p);
            boolean duplicate = onePerIp && ip != null && !ipsUsed.add(ip);
            boolean capped = maxPerDay > 0 && today.getOrDefault(id, 0) >= maxPerDay;
            if (duplicate || capped) {
                seconds.remove(id);
                showBar(p, duplicate ? "afk.bar-duplicate" : "afk.bar-capped", 0f, 0);
                continue;
            }
            int s = seconds.merge(id, 1, Integer::sum);
            if (s >= intervalSeconds) {
                seconds.put(id, 0);
                reward(p);
                s = 0;
            }
            showBar(p, "afk.bar", s / (float) intervalSeconds, intervalSeconds - s);
        }
    }

    private static String ip(Player p) {
        InetSocketAddress a = p.getAddress();
        if (a == null || a.getAddress() == null) return null;
        var ia = a.getAddress();
        if (ia.isLoopbackAddress() || ia.isSiteLocalAddress() || ia.isAnyLocalAddress()) return null;
        return ia.getHostAddress();
    }

    private void reward(Player p) {
        Texts t = plugin.texts();
        Settings s = plugin.settings();
        plugin.giveMoney(p, money);
        run(p, commands);
        today.merge(p.getUniqueId(), 1, Integer::sum);
        t.send(p, "afk.reward", ph("money", s.money(money)));
        p.playSound(Sound.sound(Key.key("minecraft", "entity.experience_orb.pickup"), Sound.Source.MASTER, 1f, 1.2f));
        for (Reward r : bonus) {
            if (ThreadLocalRandom.current().nextDouble() * 100 >= r.chance()) continue;
            plugin.giveMoney(p, r.money());
            run(p, r.commands());
            String label = r.label().isEmpty() ? s.money(r.money()) : r.label();
            t.send(p, "afk.bonus", Texts.pp("reward", label));
            p.playSound(Sound.sound(Key.key("minecraft", "entity.player.levelup"), Sound.Source.MASTER, 1f, 1.4f));
        }
    }

    private static void run(Player p, List<String> cmds) {
        for (String c : cmds) {
            String line = c.replace("%player%", p.getName()).replace("<player>", p.getName());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line.startsWith("/") ? line.substring(1) : line);
        }
    }

    private void showBar(Player p, String key, float progress, int left) {
        BossBar bar = bars.get(p.getUniqueId());
        var name = plugin.texts().get(key, ph("time", left / 60 + ":" + String.format("%02d", left % 60)),
                ph("money", plugin.settings().money(money)), ph("max", maxPerDay));
        if (bar == null) {
            bar = BossBar.bossBar(name, progress, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
            bars.put(p.getUniqueId(), bar);
            p.showBossBar(bar);
        } else {
            bar.name(name);
            bar.progress(Math.max(0f, Math.min(1f, progress)));
        }
    }

    private void hideBar(UUID id) {
        BossBar bar = bars.remove(id);
        Player p = Bukkit.getPlayer(id);
        if (bar != null && p != null) p.hideBossBar(bar);
    }

    // ================================================================= protection

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!protect || !(e.getEntity() instanceof Player p)) return;
        if (e.getCause() == EntityDamageEvent.DamageCause.VOID || e.getCause() == EntityDamageEvent.DamageCause.KILL) return;
        if (zoneAt(p.getLocation()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent e) {
        if (protect && e.getEntity() instanceof Player p && e.getFoodLevel() < p.getFoodLevel() && zoneAt(p.getLocation()) != null) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        seconds.remove(id);
        bars.remove(id);
        selections.remove(id);
    }

    // ================================================================= panneau au point d'arrivée

    public void ensureLabel() {
        if (spawn == null || (label != null && label.isValid())) return;
        if (!spawn.getWorld().isChunkLoaded(spawn.getBlockX() >> 4, spawn.getBlockZ() >> 4)) return;
        label = spawn.getWorld().spawn(spawn.clone().add(0, 2.6, 0), TextDisplay.class, d -> {
            d.setPersistent(false);
            d.getPersistentDataContainer().set(plugin.key(), PersistentDataType.BYTE, (byte) 1);
            d.setBillboard(Display.Billboard.CENTER);
            d.setBackgroundColor(Color.fromARGB(100, 0, 0, 0));
            d.setShadowed(true);
            d.setBrightness(new Display.Brightness(15, 15));
        });
        refreshLabel();
    }

    public void refreshLabel() {
        if (label == null || !label.isValid()) return;
        label.text(plugin.texts().lines("afk.label", ph("money", plugin.settings().money(money)),
                ph("minutes", intervalSeconds / 60)));
    }

    public void removeLabel() {
        if (label != null) label.remove();
        label = null;
    }

    public List<UUID> entityIds() {
        List<UUID> l = new ArrayList<>();
        if (label != null) l.add(label.getUniqueId());
        return l;
    }

    public void shutdown() {
        for (UUID id : new ArrayList<>(bars.keySet())) hideBar(id);
        removeLabel();
        save();
    }

    // ================================================================= commande /zoneafk

    public void command(CommandSender sender, String[] a) {
        Texts t = plugin.texts();
        boolean admin = sender.hasPermission("smpevents.admin");
        if (a.length == 0 || !admin) {
            if (!(sender instanceof Player p)) {
                t.send(sender, "players-only");
                return;
            }
            if (spawn == null) {
                t.send(p, "afk.no-spawn");
                return;
            }
            if (teleportDelay <= 0) {
                p.teleport(spawn);
                t.send(p, "afk.teleported");
                return;
            }
            t.send(p, "afk.teleporting", ph("seconds", teleportDelay));
            Location start = p.getLocation().clone();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                if (p.getLocation().getWorld() != start.getWorld() || p.getLocation().distanceSquared(start) > 1) {
                    t.send(p, "afk.moved");
                    return;
                }
                p.teleport(spawn);
                t.send(p, "afk.teleported");
            }, teleportDelay * 20L);
            return;
        }
        if (!(sender instanceof Player p)) {
            t.send(sender, "players-only");
            return;
        }
        Location here = p.getLocation();
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "pos1", "pos2" -> {
                Location[] sel = selections.computeIfAbsent(p.getUniqueId(), k -> new Location[2]);
                int i = a[0].equalsIgnoreCase("pos1") ? 0 : 1;
                sel[i] = here.getBlock().getLocation();
                t.send(p, "afk.pos", ph("n", i + 1), ph("x", here.getBlockX()), ph("y", here.getBlockY()), ph("z", here.getBlockZ()));
            }
            case "creer", "create" -> {
                Location[] sel = selections.get(p.getUniqueId());
                if (a.length < 2 || sel == null || sel[0] == null || sel[1] == null) {
                    t.send(p, "afk.need-selection");
                    return;
                }
                if (sel[0].getWorld() != sel[1].getWorld()) {
                    t.send(p, "afk.need-selection");
                    return;
                }
                int minY = Math.min(sel[0].getBlockY(), sel[1].getBlockY()), maxY = Math.max(sel[0].getBlockY(), sel[1].getBlockY());
                if (maxY - minY < 2) maxY = minY + 3; // sélection à plat : on donne de la hauteur
                Zone z = new Zone(a[1], sel[0].getWorld().getName(), Math.min(sel[0].getBlockX(), sel[1].getBlockX()), minY,
                        Math.min(sel[0].getBlockZ(), sel[1].getBlockZ()), Math.max(sel[0].getBlockX(), sel[1].getBlockX()), maxY,
                        Math.max(sel[0].getBlockZ(), sel[1].getBlockZ()));
                zones.put(a[1].toLowerCase(Locale.ROOT), z);
                if (spawn == null) {
                    spawn = new Location(sel[0].getWorld(), (z.minX + z.maxX) / 2.0 + 0.5, minY, (z.minZ + z.maxZ) / 2.0 + 0.5);
                }
                save();
                selections.remove(p.getUniqueId());
                t.send(p, "afk.created", ph("zone", z.name), ph("x", z.maxX - z.minX + 1), ph("y", z.maxY - z.minY + 1),
                        ph("z", z.maxZ - z.minZ + 1));
            }
            case "setspawn", "arrivee" -> {
                spawn = here.clone();
                removeLabel();
                save();
                ensureLabel();
                t.send(p, "afk.spawn-set");
            }
            case "supprimer", "delete" -> {
                if (a.length < 2 || zones.remove(a[1].toLowerCase(Locale.ROOT)) == null) {
                    t.send(p, "afk.unknown");
                    return;
                }
                save();
                t.send(p, "afk.deleted", ph("zone", a[1]));
            }
            case "liste", "list" -> {
                if (zones.isEmpty()) t.send(p, "afk.list-empty");
                for (Zone z : zones.values()) {
                    p.sendMessage(t.get("afk.list-line", ph("zone", z.name), ph("world", z.world),
                            ph("from", z.minX + " " + z.minY + " " + z.minZ), ph("to", z.maxX + " " + z.maxY + " " + z.maxZ)));
                }
            }
            case "tp" -> {
                if (spawn != null) p.teleport(spawn);
                else t.send(p, "afk.no-spawn");
            }
            default -> t.sendLines(p, "afk.help");
        }
    }

    public List<String> zoneNames() {
        List<String> l = new ArrayList<>();
        for (Zone z : zones.values()) l.add(z.name);
        return l;
    }
}
