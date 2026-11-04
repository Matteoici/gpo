package fr.smpmonde;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** Événements du monde : Lune de sang, Pluie de météores, Raid du Colosse. */
public final class SmpMonde extends JavaPlugin {

    static final MiniMessage MM = MiniMessage.miniMessage();
    BloodMoon moon;
    Meteors meteors;
    Raid raid;
    private long nextMeteor;
    private String lastMoonDay = "", lastRaidDay = "";

    static Component mm(String s) {
        return MM.deserialize(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    static String fmt(double v) {
        return String.format(Locale.FRANCE, "%,d", Math.round(v)).replace(' ', ' ').replace(' ', ' ');
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        moon = new BloodMoon(this);
        meteors = new Meteors(this);
        raid = new Raid(this);
        getServer().getPluginManager().registerEvents(moon, this);
        getServer().getPluginManager().registerEvents(meteors, this);
        getServer().getPluginManager().registerEvents(raid, this);
        nextMeteor = System.currentTimeMillis() + (long) (getConfig().getDouble("meteors.every-minutes", 240) * 60_000);
        Bukkit.getScheduler().runTaskTimer(this, this::schedule, 200L, 200L);
    }

    @Override
    public void onDisable() {
        if (moon != null) moon.stop(false);
        if (meteors != null) meteors.cleanup();
        if (raid != null) raid.stop(false);
    }

    World world() {
        World w = Bukkit.getWorld(getConfig().getString("world", "world"));
        return w != null ? w : Bukkit.getWorlds().get(0);
    }

    ZoneId zone() {
        try {
            return ZoneId.of(getConfig().getString("timezone", "Europe/Paris"));
        } catch (Exception e) {
            return ZoneId.of("Europe/Paris");
        }
    }

    int survivors() {
        int n = 0;
        for (Player p : Bukkit.getOnlinePlayers()) if (p.getGameMode() == GameMode.SURVIVAL) n++;
        return n;
    }

    /** Toutes les 10 s : est-ce l'heure d'un événement ? */
    private void schedule() {
        ZonedDateTime now = ZonedDateTime.now(zone());
        String today = now.toLocalDate().toString();
        boolean enough = survivors() >= getConfig().getInt("min-players", 2);
        if (getConfig().getBoolean("blood-moon.enabled", true) && !moon.running() && !today.equals(lastMoonDay)
                && at(now, "blood-moon", 0) && enough) {
            lastMoonDay = today;
            moon.start();
        }
        if (getConfig().getBoolean("raid.enabled", true) && !raid.active() && !today.equals(lastRaidDay)
                && at(now, "raid", getConfig().getInt("raid.warning-minutes", 5))) {
            lastRaidDay = today;
            if (enough) raid.announce();
        }
        if (getConfig().getBoolean("meteors.enabled", true) && System.currentTimeMillis() >= nextMeteor) {
            nextMeteor = System.currentTimeMillis() + (long) (getConfig().getDouble("meteors.every-minutes", 240) * 60_000);
            if (enough && !moon.running() && !raid.active()) meteors.start();
        }
    }

    /** Vrai pendant les 2 minutes qui suivent (jour, heure, minute) moins `earlyMin` minutes. */
    private boolean at(ZonedDateTime now, String path, int earlyMin) {
        DayOfWeek day;
        try {
            day = DayOfWeek.valueOf(getConfig().getString(path + ".day", "SATURDAY").toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return false;
        }
        if (now.getDayOfWeek() != day) return false;
        ZonedDateTime t = now.withHour(getConfig().getInt(path + ".hour", 21)).withMinute(getConfig().getInt(path + ".minute", 0))
                .withSecond(0).withNano(0).minusMinutes(earlyMin);
        long d = java.time.Duration.between(t, now).toSeconds();
        return d >= 0 && d < 120;
    }

    ZonedDateTime next(String path) {
        ZonedDateTime now = ZonedDateTime.now(zone());
        DayOfWeek day = DayOfWeek.valueOf(getConfig().getString(path + ".day", "SATURDAY").toUpperCase(Locale.ROOT));
        ZonedDateTime t = now.withHour(getConfig().getInt(path + ".hour", 21)).withMinute(getConfig().getInt(path + ".minute", 0)).withSecond(0).withNano(0);
        while (t.getDayOfWeek() != day || t.isBefore(now)) t = t.plusDays(1);
        return t;
    }

    // ================================================================= récompenses

    void reward(OfflinePlayer p, List<String> cmds) {
        String name = p.getName() == null ? p.getUniqueId().toString() : p.getName();
        for (String c : cmds) {
            c = c.trim();
            if (c.startsWith("money:")) {
                money(p, parse(c.substring(6)));
            } else if (c.startsWith("saison:")) {
                seasonXp(name, parse(c.substring(7)), null);
            } else {
                if (c.startsWith("/")) c = c.substring(1);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("%player%", name));
            }
        }
    }

    private static double parse(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    void money(OfflinePlayer p, double v) {
        if (v <= 0 || Bukkit.getPluginManager().getPlugin("Vault") == null) return;
        try {
            Bank.give(p, v);
        } catch (Throwable ignored) {
        }
    }

    void seasonXp(String name, double xp, String why) {
        if (xp <= 0 || !Bukkit.getPluginManager().isPluginEnabled("SmpSaison")) return;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "saison xp " + name + " " + Math.round(xp) + (why == null ? "" : " " + why));
    }

    static final class Bank {
        static void give(OfflinePlayer p, double v) {
            var rsp = Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
            if (rsp != null) rsp.getProvider().depositPlayer(p, v);
        }
    }

    // ================================================================= lieux

    static boolean claimed(Location loc) {
        try {
            Plugin p = Bukkit.getPluginManager().getPlugin("SmpGuilds");
            if (p == null || !p.isEnabled()) return false;
            Object manager = p.getClass().getMethod("guilds").invoke(p);
            return manager.getClass().getMethod("at", Location.class).invoke(manager, loc) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Endroit permis pour un événement (loin du spawn, pas dans une guilde). */
    boolean allowed(Location l) {
        Location spawn = l.getWorld().getSpawnLocation();
        double safe = getConfig().getDouble("spawn-safe-radius", 200);
        if (l.getWorld().equals(spawn.getWorld()) && l.distanceSquared(spawn) < safe * safe) return false;
        if (!l.getWorld().getWorldBorder().isInside(l)) return false;
        return !(getConfig().getBoolean("avoid-guild-claims", true) && claimed(l));
    }

    /** Sol au point (x, z) si le chunk est chargé : bloc d'air juste au-dessus du sol, null si eau / lave / pas chargé. */
    static Location ground(World w, int x, int z) {
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return null;
        Block top = w.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        Material m = top.getType();
        if (top.isLiquid() || m == Material.WATER || m == Material.LAVA || m.name().contains("ICE")) return null;
        return top.getLocation().add(0.5, 1, 0.5);
    }

    /** Point au hasard autour d'un endroit (dans un anneau min/max), au sol. */
    Location around(Location c, double min, double max) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < 12; i++) {
            double a = r.nextDouble() * Math.PI * 2, d = min + r.nextDouble() * (max - min);
            Location g = ground(c.getWorld(), (int) Math.floor(c.getX() + Math.cos(a) * d), (int) Math.floor(c.getZ() + Math.sin(a) * d));
            if (g != null && allowed(g) && Math.abs(g.getY() - c.getY()) < 12) return g;
        }
        return null;
    }

    // ================================================================= commandes

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String n = cmd.getName().toLowerCase(Locale.ROOT);
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        boolean adm = s.hasPermission("smpmonde.admin");
        switch (n) {
            case "evenements" -> {
                s.sendMessage(mm("<gold><b>📅 Événements du monde"));
                java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("EEEE HH:mm", Locale.FRANCE);
                if (getConfig().getBoolean("blood-moon.enabled", true))
                    s.sendMessage(mm("<dark_red>🌑 Lune de sang <gray>— " + (moon.running() ? "<red><b>EN COURS</b>" : "<white>" + f.format(next("blood-moon")))));
                if (getConfig().getBoolean("raid.enabled", true))
                    s.sendMessage(mm("<dark_purple>👹 Raid du Colosse <gray>— " + (raid.active() ? "<red><b>EN COURS</b> <gray>(/raid tp)" : "<white>" + f.format(next("raid")))));
                if (getConfig().getBoolean("meteors.enabled", true))
                    s.sendMessage(mm("<gold>☄ Pluie de météores <gray>— " + (meteors.running() ? "<red><b>EN COURS</b>"
                            : "<white>dans " + Math.max(1, (nextMeteor - System.currentTimeMillis()) / 60000) + " min")));
            }
            case "lune" -> {
                if (!adm || sub.isEmpty()) return onCommand(s, getCommand("evenements"), label, new String[0]);
                if (sub.equals("start")) moon.start();
                else if (sub.equals("stop")) moon.stop(true);
            }
            case "meteores" -> {
                if (!adm || sub.isEmpty()) return onCommand(s, getCommand("evenements"), label, new String[0]);
                if (sub.equals("start")) meteors.start();
                else if (sub.equals("stop")) meteors.cleanup();
            }
            case "raid" -> {
                if (sub.equals("tp") || (sub.isEmpty() && raid.active())) {
                    if (s instanceof Player p) raid.tp(p);
                    return true;
                }
                if (!adm || sub.isEmpty()) return onCommand(s, getCommand("evenements"), label, new String[0]);
                switch (sub) {
                    case "lieu" -> {
                        if (!(s instanceof Player p)) return true;
                        Location l = p.getLocation();
                        getConfig().set("raid.location", l.getWorld().getName() + "," + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ());
                        saveConfig();
                        s.sendMessage(mm("<green>Arène du raid placée ici. <gray>(le Colosse apparaît à cet endroit et ne s'en éloigne pas de plus de 30 blocs)"));
                    }
                    case "start" -> raid.spawn();
                    case "annonce" -> raid.announce();
                    case "stop" -> raid.stop(true);
                    default -> s.sendMessage(mm("<gold>/raid lieu · annonce · start · stop · tp"));
                }
            }
            case "monde" -> {
                reloadConfig();
                s.sendMessage(mm("<green>SmpMonde rechargé."));
            }
            default -> {
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            switch (cmd.getName().toLowerCase(Locale.ROOT)) {
                case "lune", "meteores" -> {
                    if (s.hasPermission("smpmonde.admin")) out.addAll(List.of("start", "stop"));
                }
                case "raid" -> {
                    out.add("tp");
                    if (s.hasPermission("smpmonde.admin")) out.addAll(List.of("lieu", "annonce", "start", "stop"));
                }
                case "monde" -> out.add("reload");
                default -> {
                }
            }
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.startsWith(last));
        return out;
    }
}
