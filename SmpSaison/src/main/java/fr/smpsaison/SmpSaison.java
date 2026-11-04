package fr.smpsaison;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Métiers, passe de saison et classements de la semaine. */
public final class SmpSaison extends JavaPlugin implements Listener {

    static final MiniMessage MM = MiniMessage.miniMessage();
    private Data data;
    private Jobs jobs;
    private final Map<UUID, Long> lastMove = new HashMap<>();
    private final Map<UUID, Integer> playMinutes = new HashMap<>();

    static Component mm(String s) {
        return MM.deserialize(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    Data data() {
        return data;
    }

    Jobs jobs() {
        return jobs;
    }

    String money() {
        return getConfig().getString("money-symbol", "$");
    }

    static String fmt(double v) {
        long r = Math.round(v);
        if (Math.abs(v - r) < 0.05) return String.format(Locale.FRANCE, "%,d", r).replace(' ', ' ').replace(' ', ' ');
        return String.format(Locale.FRANCE, "%,.1f", v).replace(' ', ' ').replace(' ', ' ');
    }

    ZoneId zone() {
        try {
            return ZoneId.of(getConfig().getString("timezone", "Europe/Paris"));
        } catch (Exception e) {
            return ZoneId.of("Europe/Paris");
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (getConfig().getInt("config-version", 1) < 2) {
            // passe premium : 5 000 000
            getConfig().set("season.premium-price", 5000000);
            getConfig().set("config-version", 2);
            saveConfig();
        }
        data = new Data(getDataFolder(), getLogger());
        data.load();
        jobs = new Jobs(this);
        jobs.load();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(jobs, this);
        getServer().getPluginManager().registerEvents(new Gui.Listen(this), this);
        if (data.week.isEmpty()) {
            data.week = weekId();
            data.dirty = true;
        }
        // chaque minute : temps de jeu, changement de semaine, sauvegarde
        Bukkit.getScheduler().runTaskTimer(this, this::minute, 1200L, 1200L);
        Bukkit.getScheduler().runTaskTimer(this, () -> jobs.flushBars(), 20L, 20L);
        for (Player p : Bukkit.getOnlinePlayers()) join(p);
    }

    @Override
    public void onDisable() {
        if (data != null) data.save();
    }

    // ================================================================= XP de saison

    int seasonNumber() {
        return getConfig().getInt("season.number", 1);
    }

    /** Joueur prêt pour la saison en cours (nouvelle saison = tout repart à zéro). */
    Data.P season(UUID id) {
        Data.P p = data.of(id);
        if (p.seasonNumber != seasonNumber()) {
            p.seasonNumber = seasonNumber();
            p.seasonXp = 0;
            p.premium = false;
            p.claimedFree.clear();
            p.claimedPremium.clear();
            data.dirty = true;
        }
        return p;
    }

    int xpPerTier() {
        return Math.max(1, getConfig().getInt("season.xp-per-tier", 1000));
    }

    int maxTier() {
        ConfigurationSection t = getConfig().getConfigurationSection("season.tiers");
        int max = 0;
        if (t != null) for (String k : t.getKeys(false)) {
            try {
                max = Math.max(max, Integer.parseInt(k));
            } catch (NumberFormatException ignored) {
            }
        }
        return Math.max(1, max);
    }

    int tierOf(Data.P p) {
        return (int) Math.min(maxTier(), p.seasonXp / xpPerTier());
    }

    boolean premium(Player pl, Data.P p) {
        return p.premium || pl.hasPermission("smpsaison.premium");
    }

    void addSeasonXp(UUID id, double xp) {
        if (xp <= 0) return;
        Data.P p = season(id);
        int before = tierOf(p);
        p.seasonXp += Math.round(xp);
        addWeekly(id, "saison", xp);
        data.dirty = true;
        int after = tierOf(p);
        Player pl = Bukkit.getPlayer(id);
        if (pl != null && after > before) {
            pl.showTitle(net.kyori.adventure.title.Title.title(mm("<gold><b>🎫 Palier " + after + " !"),
                    mm("<gray>Récupère tes récompenses : <yellow>/passe")));
            pl.playSound(pl.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        }
    }

    void addWeekly(UUID id, String board, double v) {
        if (v == 0) return;
        data.of(id).weekly.merge(board, v, Double::sum);
        data.dirty = true;
    }

    // ================================================================= récompenses

    /** Commandes de récompense : tout de suite s'il est là, sinon à sa prochaine connexion. */
    void reward(UUID id, List<String> cmds) {
        Player p = Bukkit.getPlayer(id);
        if (p == null) {
            data.of(id).pending.addAll(cmds);
            data.dirty = true;
            return;
        }
        for (String c : cmds) run(p, c);
    }

    private void run(OfflinePlayer p, String c) {
        c = c.trim();
        if (c.startsWith("money:")) {
            try {
                Bank.give(p, Double.parseDouble(c.substring(6).trim()));
            } catch (NumberFormatException e) {
                getLogger().warning("Récompense invalide : " + c);
            }
            return;
        }
        String name = p.getName() == null ? p.getUniqueId().toString() : p.getName();
        if (c.startsWith("/")) c = c.substring(1);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("%player%", name));
    }

    /** Récupère un palier. free = ligne gratuite. Renvoie un message d'erreur, ou null si c'est bon. */
    String claim(Player pl, int tier, boolean free) {
        Data.P p = season(pl.getUniqueId());
        if (tier > tierOf(p)) return "<red>Palier " + tier + " pas encore atteint.";
        if (!free && !premium(pl, p)) return "<red>C'est une récompense du passe premium. <yellow>/passe premium";
        var set = free ? p.claimedFree : p.claimedPremium;
        if (set.contains(tier)) return "<gray>Déjà récupéré.";
        List<String> cmds = getConfig().getStringList("season.tiers." + tier + "." + (free ? "free" : "premium") + ".rewards");
        set.add(tier);
        data.dirty = true;
        for (String c : cmds) run(pl, c);
        pl.playSound(pl.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
        return null;
    }

    int claimAll(Player pl) {
        Data.P p = season(pl.getUniqueId());
        int n = 0;
        for (int t = 1; t <= tierOf(p); t++) {
            if (!p.claimedFree.contains(t) && claim(pl, t, true) == null) n++;
            if (premium(pl, p) && !p.claimedPremium.contains(t) && claim(pl, t, false) == null) n++;
        }
        return n;
    }

    void buyPremium(Player pl) {
        Data.P p = season(pl.getUniqueId());
        if (premium(pl, p)) {
            pl.sendMessage(mm("<green>Tu as déjà le passe premium."));
            return;
        }
        double price = getConfig().getDouble("season.premium-price", 5000000);
        if (!Bank.take(pl, price)) {
            pl.sendMessage(mm("<red>Il te faut <white>" + fmt(price) + " " + money() + "</white> pour le passe premium."));
            return;
        }
        p.premium = true;
        data.dirty = true;
        pl.sendMessage(mm("<gold><b>🎫 Passe premium débloqué !</b> <gray>Tous les paliers déjà atteints sont à récupérer dans <yellow>/passe"));
        pl.playSound(pl.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        Bukkit.broadcast(mm("<gold>🎫 <white>" + pl.getName() + "</white> a pris le <b>passe premium</b> de la saison !"));
    }

    // ================================================================= semaine

    /** "2026-W45" (la semaine commence au jour / à l'heure de remise à zéro). */
    String weekId() {
        ZonedDateTime now = ZonedDateTime.now(zone());
        DayOfWeek day;
        try {
            day = DayOfWeek.valueOf(getConfig().getString("weekly.reset-day", "MONDAY").toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            day = DayOfWeek.MONDAY;
        }
        int hour = getConfig().getInt("weekly.reset-hour", 0);
        // on recule jusqu'au dernier moment de remise à zéro
        ZonedDateTime start = now.withHour(hour).withMinute(0).withSecond(0).withNano(0);
        while (start.getDayOfWeek() != day || start.isAfter(now)) start = start.minusDays(1);
        return start.toLocalDate().toString();
    }

    ZonedDateTime nextReset() {
        LocalDate d = LocalDate.parse(weekId().length() == 10 ? weekId() : LocalDate.now(zone()).toString());
        return d.plusDays(7).atTime(getConfig().getInt("weekly.reset-hour", 0), 0).atZone(zone());
    }

    record Rank(UUID id, String name, double value) {
    }

    List<Rank> top(String board, int n) {
        List<Rank> out = new ArrayList<>();
        for (Data.P p : data.all()) {
            double v = p.weekly.getOrDefault(board, 0.0);
            if (v > 0) out.add(new Rank(p.id, p.name, v));
        }
        out.sort((a, b) -> Double.compare(b.value(), a.value()));
        return out.size() > n ? out.subList(0, n) : out;
    }

    /** Fin de semaine : les 3 premiers de chaque classement sont récompensés, puis tout repart à zéro. */
    void weeklyReset() {
        ConfigurationSection boards = getConfig().getConfigurationSection("weekly.boards");
        List<String> lines = new ArrayList<>();
        if (boards != null) for (String b : boards.getKeys(false)) {
            List<Rank> t = top(b, 3);
            if (t.isEmpty()) continue;
            StringBuilder sb = new StringBuilder(boards.getString(b + ".name", b) + " <dark_gray>»</dark_gray> ");
            for (int i = 0; i < t.size(); i++) {
                Rank r = t.get(i);
                reward(r.id(), boards.getStringList(b + ".rewards." + (i + 1)));
                sb.append(i == 0 ? "<gold>🥇 " : i == 1 ? " <gray>🥈 " : " <#cd7f32>🥉 ").append("<white>").append(MM.escapeTags(r.name()));
            }
            lines.add(sb.toString());
        }
        for (Data.P p : data.all()) p.weekly.clear();
        data.week = weekId();
        data.dirty = true;
        data.save();
        Bukkit.broadcast(mm(""));
        Bukkit.broadcast(mm("<gradient:#ffd36b:#ff8a3d><b>🏆 FIN DE LA SEMAINE</b></gradient> <gray>— les gagnants ont reçu leurs récompenses :"));
        for (String l : lines) Bukkit.broadcast(mm("  " + l));
        Bukkit.broadcast(mm("<gray>Nouvelle semaine, nouveaux classements : <yellow>/hebdo"));
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
    }

    private void minute() {
        if (!weekId().equals(data.week)) weeklyReset();
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Long mv = lastMove.get(p.getUniqueId());
            if (mv == null || now - mv > 5 * 60_000L) continue; // AFK : pas d'XP de temps de jeu
            int m = playMinutes.merge(p.getUniqueId(), 1, Integer::sum);
            if (m % 10 == 0) addSeasonXp(p.getUniqueId(), getConfig().getDouble("season.xp.playtime-10min", 25));
        }
        if (data.dirty) data.save();
    }

    // ================================================================= événements

    private void join(Player pl) {
        Data.P p = season(pl.getUniqueId());
        p.name = pl.getName();
        lastMove.put(pl.getUniqueId(), System.currentTimeMillis());
        String today = LocalDate.now(zone()).toString();
        if (!today.equals(p.lastLoginDay)) {
            p.lastLoginDay = today;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!pl.isOnline()) return;
                double xp = getConfig().getDouble("season.xp.daily-login", 100);
                addSeasonXp(pl.getUniqueId(), xp);
                pl.sendMessage(mm("<gold>🎫 +" + fmt(xp) + " XP de saison <gray>(première connexion du jour) · <yellow>/passe"));
            }, 100L);
        }
        if (!p.pending.isEmpty()) {
            List<String> todo = new ArrayList<>(p.pending);
            p.pending.clear();
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!pl.isOnline()) {
                    data.of(pl.getUniqueId()).pending.addAll(todo);
                    return;
                }
                for (String c : todo) run(pl, c);
                pl.sendMessage(mm("<gold>🏆 Tu as reçu des récompenses pendant ton absence (classements de la semaine) !"));
            }, 80L);
        }
        data.dirty = true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        join(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastMove.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (e.hasChangedBlock() || e.hasChangedOrientation()) lastMove.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onKill(EntityDeathEvent e) {
        Player k = e.getEntity().getKiller();
        if (k == null || k.equals(e.getEntity())) return;
        if (e.getEntity() instanceof Player) {
            addSeasonXp(k.getUniqueId(), getConfig().getDouble("season.xp.player-kill", 40));
            addWeekly(k.getUniqueId(), "kills", 1);
        } else if (e.getEntity() instanceof org.bukkit.entity.Monster || e.getEntity() instanceof org.bukkit.entity.Enemy) {
            double r = e.getEntity().fromMobSpawner() ? getConfig().getDouble("jobs-settings.spawner-ratio", 0.2) : 1;
            addSeasonXp(k.getUniqueId(), getConfig().getDouble("season.xp.mob-kill", 1) * r);
            if (!e.getEntity().fromMobSpawner()) addWeekly(k.getUniqueId(), "mobs", 1);
        }
    }

    // ================================================================= commandes

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String n = cmd.getName().toLowerCase(Locale.ROOT);
        Player pl = s instanceof Player p ? p : null;
        switch (n) {
            case "metiers" -> {
                if (pl != null) Gui.jobs(this, pl);
            }
            case "hebdo" -> {
                if (pl != null) Gui.weekly(this, pl);
            }
            case "passe" -> {
                if (pl == null) return true;
                String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
                if (sub.equals("premium") || sub.equals("acheter")) buyPremium(pl);
                else if (sub.equals("tout") || sub.equals("all")) {
                    int k = claimAll(pl);
                    pl.sendMessage(mm(k == 0 ? "<gray>Rien à récupérer pour l'instant." : "<green>✔ " + k + " récompense(s) récupérée(s) !"));
                } else Gui.pass(this, pl, -1);
            }
            case "saison" -> admin(s, a);
            default -> {
            }
        }
        return true;
    }

    @SuppressWarnings("deprecation")
    private void admin(CommandSender s, String[] a) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "xp" -> {
                // /saison xp <joueur> <n> [raison]   (utilisé aussi par SmpBoss et SmpDrop)
                if (a.length < 3) {
                    s.sendMessage(mm("<red>/saison xp <joueur> <nombre>"));
                    return;
                }
                OfflinePlayer t = Bukkit.getOfflinePlayer(a[1]);
                double v;
                try {
                    v = Double.parseDouble(a[2]);
                } catch (NumberFormatException e) {
                    s.sendMessage(mm("<red>Nombre invalide."));
                    return;
                }
                addSeasonXp(t.getUniqueId(), v);
                if (t.getPlayer() != null && a.length >= 4) t.getPlayer().sendMessage(mm("<gold>🎫 +" + fmt(v) + " XP de saison <gray>(" + String.join(" ", java.util.Arrays.copyOfRange(a, 3, a.length)) + ")"));
                if (!(s instanceof org.bukkit.command.ConsoleCommandSender)) s.sendMessage(mm("<green>+" + fmt(v) + " XP de saison à " + a[1]));
            }
            case "premium" -> {
                if (a.length < 2) {
                    s.sendMessage(mm("<red>/saison premium <joueur> [retirer]"));
                    return;
                }
                OfflinePlayer t = Bukkit.getOfflinePlayer(a[1]);
                boolean on = a.length < 3 || !a[2].equalsIgnoreCase("retirer");
                season(t.getUniqueId()).premium = on;
                data.dirty = true;
                s.sendMessage(mm("<green>Passe premium " + (on ? "donné à " : "retiré à ") + a[1]));
            }
            case "info" -> {
                if (a.length < 2) {
                    s.sendMessage(mm("<red>/saison info <joueur>"));
                    return;
                }
                Data.P p = season(Bukkit.getOfflinePlayer(a[1]).getUniqueId());
                s.sendMessage(mm("<gold>" + a[1] + " : <white>palier " + tierOf(p) + "</white> (" + fmt(p.seasonXp) + " XP)" + (p.premium ? " <gold>premium" : "")));
                for (String j : jobs.ids()) s.sendMessage(mm("<gray>  " + jobs.name(j) + " <white>niv. " + p.level(j)));
            }
            case "reset" -> {
                // nouvelle saison : numéro + 1, tout le monde repart du palier 0 (les métiers restent)
                if (a.length < 2 || !a[1].equalsIgnoreCase("confirmer")) {
                    s.sendMessage(mm("<red>Nouvelle saison : tous les paliers et passes premium repartent à zéro (les métiers restent). <white>/saison reset confirmer"));
                    return;
                }
                getConfig().set("season.number", seasonNumber() + 1);
                saveConfig();
                for (Player p : Bukkit.getOnlinePlayers()) season(p.getUniqueId());
                Bukkit.broadcast(mm("<gradient:#ff8a3d:#ffd36b><b>🎫 La saison " + seasonNumber() + " commence !</b></gradient> <gray>Nouveau passe : <yellow>/passe"));
            }
            case "hebdo-reset" -> {
                weeklyReset();
                s.sendMessage(mm("<green>Semaine terminée de force (récompenses données)."));
            }
            case "reload" -> {
                reloadConfig();
                jobs.load();
                s.sendMessage(mm("<green>SmpSaison rechargé."));
            }
            default -> s.sendMessage(mm("<gold>/saison xp <joueur> <n> · premium <joueur> [retirer] · info <joueur> · reset confirmer · hebdo-reset · reload"));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        String n = cmd.getName().toLowerCase(Locale.ROOT);
        if (n.equals("passe") && a.length == 1) out.addAll(List.of("premium", "tout"));
        if (n.equals("saison")) {
            if (a.length == 1) out.addAll(List.of("xp", "premium", "info", "reset", "hebdo-reset", "reload"));
            else if (a.length == 2) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
