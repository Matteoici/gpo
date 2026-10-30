package fr.smpcouvrefeu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Contrôle parental : des heures où certains joueurs ne peuvent pas se connecter (et sont déconnectés s'ils y sont).
 * L'heure vient du serveur (fuseau de la config) : changer l'heure de son PC, son pays ou son IP ne sert à rien.
 * Le blocage suit le compte (UUID). Option : les autres comptes venant de la même IP sont aussi bloqués pendant ces heures.
 */
public final class SmpCouvreFeu extends JavaPlugin implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** Une plage interdite qui commence à "start" et finit à "end" (le lendemain si end <= start). */
    record Window(LocalTime start, LocalTime end) {
        String text() {
            return fmt(start) + " → " + fmt(end);
        }
    }

    static final class Rule {
        String name;
        Window all, week, weekend;
        final Set<String> ips = new HashSet<>();
        long pauseUntil;
    }

    private final Map<UUID, Rule> rules = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> warned = new HashMap<>();
    private File dataFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "joueurs.yml");
        load();
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 100L, 200L);
    }

    // ================================================================= heures

    private ZoneId zone() {
        try {
            return ZoneId.of(getConfig().getString("timezone", "Europe/Paris"));
        } catch (Exception e) {
            return ZoneId.of("Europe/Paris");
        }
    }

    private Set<DayOfWeek> weekendDays() {
        Set<DayOfWeek> s = EnumSet.noneOf(DayOfWeek.class);
        for (String d : getConfig().getStringList("weekend-days")) {
            try {
                s.add(DayOfWeek.valueOf(d.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (s.isEmpty()) s.addAll(List.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY));
        return s;
    }

    /** La plage qui s'applique à la nuit qui commence ce jour-là. */
    private Window windowFor(Rule r, LocalDate day) {
        boolean we = weekendDays().contains(day.getDayOfWeek());
        Window w = we ? r.weekend : r.week;
        return w != null ? w : r.all;
    }

    /** Fin du blocage en cours, ou null si le joueur peut jouer maintenant. */
    ZonedDateTime blockedUntil(Rule r, ZonedDateTime now) {
        if (r.pauseUntil > System.currentTimeMillis()) return null;
        for (int back = 1; back >= 0; back--) {
            LocalDate day = now.toLocalDate().minusDays(back);
            Window w = windowFor(r, day);
            if (w == null) continue;
            ZonedDateTime s = day.atTime(w.start()).atZone(now.getZone());
            ZonedDateTime e = (w.end().isAfter(w.start()) ? day : day.plusDays(1)).atTime(w.end()).atZone(now.getZone());
            if (!now.isBefore(s) && now.isBefore(e)) return e;
        }
        return null;
    }

    /** Début du prochain blocage (dans les 24 h), ou null. */
    ZonedDateTime nextStart(Rule r, ZonedDateTime now) {
        for (int k = 0; k <= 1; k++) {
            LocalDate day = now.toLocalDate().plusDays(k);
            Window w = windowFor(r, day);
            if (w == null) continue;
            ZonedDateTime s = day.atTime(w.start()).atZone(now.getZone());
            if (s.isAfter(now)) return s;
        }
        return null;
    }

    static String fmt(LocalTime t) {
        return String.format("%02dh%02d", t.getHour(), t.getMinute());
    }

    static LocalTime parseTime(String s) {
        String x = s.toLowerCase(Locale.ROOT).replace("h", ":").replace(".", ":");
        if (x.endsWith(":")) x += "00";
        if (!x.contains(":")) x += ":00";
        String[] p = x.split(":");
        int h = Integer.parseInt(p[0]), m = p.length > 1 && !p[1].isEmpty() ? Integer.parseInt(p[1]) : 0;
        if (h == 24) h = 0;
        return LocalTime.of(h, m);
    }

    // ================================================================= connexions

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        ZonedDateTime now = ZonedDateTime.now(zone());
        Rule r = rules.get(e.getUniqueId());
        if (r != null) {
            ZonedDateTime until = blockedUntil(r, now);
            if (until != null) {
                e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, msg("kick", until));
                getLogger().info("Couvre-feu : connexion refusée pour " + e.getName());
                return;
            }
        }
        // un autre compte depuis la même IP qu'un joueur sous couvre-feu
        if (getConfig().getBoolean("block-ips", true) && e.getAddress() != null) {
            String ip = e.getAddress().getHostAddress();
            for (Map.Entry<UUID, Rule> en : rules.entrySet()) {
                if (en.getKey().equals(e.getUniqueId()) || !en.getValue().ips.contains(ip)) continue;
                ZonedDateTime until = blockedUntil(en.getValue(), now);
                if (until != null) {
                    e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, msg("kick-ip", until));
                    getLogger().info("Couvre-feu : " + e.getName() + " refusé (même IP que " + en.getValue().name + ")");
                    return;
                }
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Rule r = rules.get(e.getPlayer().getUniqueId());
        if (r == null || e.getPlayer().getAddress() == null) return;
        r.name = e.getPlayer().getName();
        if (r.ips.add(e.getPlayer().getAddress().getAddress().getHostAddress())) save();
    }

    private void tick() {
        ZonedDateTime now = ZonedDateTime.now(zone());
        List<Integer> warns = new ArrayList<>(getConfig().getIntegerList("warn-minutes"));
        warns.sort((a, b) -> b - a);
        for (Player p : Bukkit.getOnlinePlayers()) {
            Rule r = rules.get(p.getUniqueId());
            if (r == null) continue;
            ZonedDateTime until = blockedUntil(r, now);
            if (until != null) {
                p.kick(msg("kick", until));
                warned.remove(p.getUniqueId());
                continue;
            }
            ZonedDateTime next = nextStart(r, now);
            if (next == null || r.pauseUntil > System.currentTimeMillis()) continue;
            long mins = (Duration.between(now, next).getSeconds() + 59) / 60;
            for (int w : warns) {
                if (mins <= w && warned.getOrDefault(p.getUniqueId(), Integer.MAX_VALUE) > w) {
                    warned.put(p.getUniqueId(), w);
                    p.sendMessage(MM.deserialize(getConfig().getString("messages.warn", "").replace("<minutes>", String.valueOf(mins))));
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 0.8f);
                    break;
                }
            }
            if (mins > warns.stream().mapToInt(i -> i).max().orElse(15)) warned.remove(p.getUniqueId());
        }
    }

    private Component msg(String key, ZonedDateTime until) {
        return MM.deserialize(getConfig().getString("messages." + key, "Couvre-feu").replace("<until>", fmt(until.toLocalTime())));
    }

    // ================================================================= commande

    private static void say(CommandSender s, String m) {
        s.sendMessage(MM.deserialize("<gold>[Couvre-feu]</gold> " + m));
    }

    @SuppressWarnings("deprecation")
    private static OfflinePlayer find(String name) {
        Player on = Bukkit.getPlayerExact(name);
        return on != null ? on : Bukkit.getOfflinePlayer(name);
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        ZonedDateTime now = ZonedDateTime.now(zone());
        switch (sub) {
            case "ajouter", "add" -> {
                if (a.length < 4) {
                    say(s, "<red>/couvrefeu ajouter <joueur> <début> <fin> [tous|semaine|weekend]  <gray>ex. 22h 7h");
                    return true;
                }
                LocalTime st, en;
                try {
                    st = parseTime(a[2]);
                    en = parseTime(a[3]);
                } catch (Exception ex) {
                    say(s, "<red>Heure invalide (ex. 22h, 22h30, 7h).");
                    return true;
                }
                if (st.equals(en)) {
                    say(s, "<red>Le début et la fin sont pareils.");
                    return true;
                }
                OfflinePlayer op = find(a[1]);
                Rule r = rules.computeIfAbsent(op.getUniqueId(), k -> new Rule());
                r.name = op.getName() != null ? op.getName() : a[1];
                if (op.isOnline() && op.getPlayer() != null && op.getPlayer().getAddress() != null)
                    r.ips.add(op.getPlayer().getAddress().getAddress().getHostAddress());
                String when = a.length >= 5 ? a[4].toLowerCase(Locale.ROOT) : "tous";
                Window w = new Window(st, en);
                switch (when) {
                    case "semaine", "week" -> r.week = w;
                    case "weekend", "we" -> r.weekend = w;
                    default -> {
                        r.all = w;
                        when = "tous";
                    }
                }
                save();
                say(s, "<green>" + r.name + " <gray>: pas de connexion de <white>" + fmt(st) + "</white> à <white>" + fmt(en) + "</white> (" + when + ").");
                if (op.isOnline()) tick();
            }
            case "retirer", "remove", "supprimer" -> {
                if (a.length < 2) {
                    say(s, "<red>/couvrefeu retirer <joueur> [tous|semaine|weekend]");
                    return true;
                }
                OfflinePlayer op = find(a[1]);
                Rule r = rules.get(op.getUniqueId());
                if (r == null) {
                    say(s, "<red>Ce joueur n'a pas de couvre-feu.");
                    return true;
                }
                String when = a.length >= 3 ? a[2].toLowerCase(Locale.ROOT) : "";
                switch (when) {
                    case "semaine", "week" -> r.week = null;
                    case "weekend", "we" -> r.weekend = null;
                    case "tous" -> r.all = null;
                    default -> {
                        rules.remove(op.getUniqueId());
                        r = null;
                    }
                }
                if (r != null && r.all == null && r.week == null && r.weekend == null) rules.remove(op.getUniqueId());
                save();
                say(s, "<green>Couvre-feu retiré pour " + a[1] + (when.isEmpty() ? "" : " (" + when + ")") + ".");
            }
            case "pause", "exception" -> {
                if (a.length < 3) {
                    say(s, "<red>/couvrefeu pause <joueur> <minutes>  <gray>(autorisation exceptionnelle)");
                    return true;
                }
                Rule r = rules.get(find(a[1]).getUniqueId());
                if (r == null) {
                    say(s, "<red>Ce joueur n'a pas de couvre-feu.");
                    return true;
                }
                int m = Integer.parseInt(a[2].replaceAll("\\D", "0"));
                r.pauseUntil = System.currentTimeMillis() + m * 60_000L;
                save();
                say(s, "<green>" + r.name + " peut se connecter pendant encore <white>" + m + " min</white>.");
            }
            case "info" -> {
                if (a.length < 2) {
                    say(s, "<red>/couvrefeu info <joueur>");
                    return true;
                }
                Rule r = rules.get(find(a[1]).getUniqueId());
                if (r == null) {
                    say(s, "<gray>" + a[1] + " n'a pas de couvre-feu.");
                    return true;
                }
                info(s, r, now);
            }
            case "liste", "list" -> {
                if (rules.isEmpty()) say(s, "<gray>Personne n'a de couvre-feu.");
                for (Rule r : rules.values()) info(s, r, now);
            }
            default -> {
                say(s, "<white>/couvrefeu ajouter <joueur> <début> <fin> [tous|semaine|weekend]");
                say(s, "<white>/couvrefeu retirer <joueur> [tous|semaine|weekend]");
                say(s, "<white>/couvrefeu pause <joueur> <minutes> <gray>· <white>/couvrefeu info <joueur> <gray>· <white>/couvrefeu liste");
                say(s, "<gray>Heure du serveur : <white>" + fmt(now.toLocalTime()) + " <gray>(" + zone() + ")");
            }
        }
        return true;
    }

    private void info(CommandSender s, Rule r, ZonedDateTime now) {
        ZonedDateTime until = blockedUntil(r, now);
        String state = until != null ? "<red>bloqué jusqu'à " + fmt(until.toLocalTime()) : (r.pauseUntil > System.currentTimeMillis() ? "<aqua>exception en cours" : "<green>peut jouer");
        say(s, "<white><b>" + r.name + "</b> <gray>· " + state);
        if (r.all != null) say(s, "<gray>  tous les soirs : <white>" + r.all.text());
        if (r.week != null) say(s, "<gray>  semaine : <white>" + r.week.text());
        if (r.weekend != null) say(s, "<gray>  weekend : <white>" + r.weekend.text());
        if (!r.ips.isEmpty()) say(s, "<dark_gray>  " + r.ips.size() + " adresse(s) IP connue(s)");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("ajouter", "retirer", "pause", "info", "liste"));
        else if (a.length == 2) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
            for (Rule r : rules.values()) if (r.name != null && !out.contains(r.name)) out.add(r.name);
        } else if (a.length == 3 && a[0].equalsIgnoreCase("ajouter")) out.addAll(List.of("21h", "22h", "22h30", "23h"));
        else if (a.length == 4 && a[0].equalsIgnoreCase("ajouter")) out.addAll(List.of("6h", "7h", "8h"));
        else if ((a.length == 5 && a[0].equalsIgnoreCase("ajouter")) || (a.length == 3 && a[0].equalsIgnoreCase("retirer")))
            out.addAll(List.of("tous", "semaine", "weekend"));
        else if (a.length == 3 && a[0].equalsIgnoreCase("pause")) out.addAll(List.of("15", "30", "60"));
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    // ================================================================= sauvegarde

    private void load() {
        rules.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection sec = y.getConfigurationSection("joueurs");
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            try {
                ConfigurationSection c = sec.getConfigurationSection(k);
                if (c == null) continue;
                Rule r = new Rule();
                r.name = c.getString("nom", k);
                r.all = win(c.getString("tous"));
                r.week = win(c.getString("semaine"));
                r.weekend = win(c.getString("weekend"));
                r.ips.addAll(c.getStringList("ips"));
                r.pauseUntil = c.getLong("pause-jusqua", 0);
                rules.put(UUID.fromString(k), r);
            } catch (Exception e) {
                getLogger().warning("Entrée invalide : " + k);
            }
        }
    }

    private static Window win(String s) {
        if (s == null || !s.contains("-")) return null;
        String[] p = s.split("-");
        return new Window(LocalTime.parse(p[0].trim()), LocalTime.parse(p[1].trim()));
    }

    private static String str(Window w) {
        return w == null ? null : w.start() + "-" + w.end();
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Rule> en : rules.entrySet()) {
            String b = "joueurs." + en.getKey() + ".";
            Rule r = en.getValue();
            y.set(b + "nom", r.name);
            y.set(b + "tous", str(r.all));
            y.set(b + "semaine", str(r.week));
            y.set(b + "weekend", str(r.weekend));
            y.set(b + "ips", new ArrayList<>(r.ips));
            if (r.pauseUntil > System.currentTimeMillis()) y.set(b + "pause-jusqua", r.pauseUntil);
        }
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Sauvegarde impossible : " + e.getMessage());
        }
    }
}
