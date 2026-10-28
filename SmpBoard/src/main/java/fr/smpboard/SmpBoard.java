package fr.smpboard;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.lang.reflect.Method;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scoreboard (à droite) + TAB (en-tête, pied, nom des joueurs).
 * <p>
 * Chaque joueur a son propre scoreboard (pour ses infos à lui), et les équipes du scoreboard principal (titres au
 * dessus de la tête de SmpTitles, couleurs de SmpSandevistan...) y sont recopiées en continu : rien ne casse.
 * Si un autre plugin met son propre scoreboard (le classement des dégâts pendant le boss), on lui laisse la place
 * et on revient après.
 */
public final class SmpBoard extends JavaPlugin implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String[] ENTRIES = new String[15];

    static {
        for (int i = 0; i < 15; i++) ENTRIES[i] = "§" + Integer.toHexString(i) + "§r";
    }

    private final class Board {
        final Scoreboard sb;
        final Objective obj;
        final String[] last = new String[15];
        int lines;
        String lastTitle;
        final Set<String> mirrored = new HashSet<>();

        Board() {
            sb = Bukkit.getScoreboardManager().getNewScoreboard();
            obj = sb.registerNewObjective("smpboard", Criteria.DUMMY, Component.empty());
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
            obj.numberFormat(NumberFormat.blank());
        }
    }

    private final Map<UUID, Board> boards = new HashMap<>();
    private final Map<UUID, String> lastTab = new HashMap<>();
    private final Map<UUID, String> lastName = new HashMap<>();
    private VaultHook vault;
    private NamespacedKey hiddenKey;
    private List<String> titleFrames = List.of();
    private int frame, tick;
    private DateTimeFormatter dateFmt, timeFmt;
    private ZoneId zone;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        hiddenKey = new NamespacedKey(this, "hidden");
        if (Bukkit.getPluginManager().getPlugin("Vault") != null) vault = new VaultHook();
        load();
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::update, 20L, 1L);
        for (Player p : Bukkit.getOnlinePlayers()) attach(p);
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Board b = boards.get(p.getUniqueId());
            if (b != null && p.getScoreboard() == b.sb) p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
        boards.clear();
    }

    private void load() {
        reloadConfig();
        titleFrames = buildTitle();
        zone = ZoneId.of(getConfig().getString("timezone", "Europe/Paris"));
        dateFmt = DateTimeFormatter.ofPattern(getConfig().getString("date-format", "dd/MM/yyyy"), Locale.FRANCE);
        timeFmt = DateTimeFormatter.ofPattern(getConfig().getString("time-format", "HH:mm"), Locale.FRANCE);
        for (Board b : boards.values()) b.lastTitle = null;
        lastTab.clear();
        lastName.clear();
    }

    /** Titre animé : soit une liste d'images, soit un reflet qui passe sur le texte. */
    private List<String> buildTitle() {
        List<String> frames = getConfig().getStringList("title.frames");
        if (!frames.isEmpty()) return frames;
        String text = getConfig().getString("title.text", "GPO SMP");
        String base = getConfig().getString("title.color", "<gradient:#ff7a00:#ffd36b>");
        String shine = getConfig().getString("title.shine-color", "#fff3d6");
        String deco = getConfig().getString("title.decoration", "<b>");
        String left = getConfig().getString("title.left", "<#ff7a00>☠ "), right = getConfig().getString("title.right", " <#ff7a00>☠");
        List<String> out = new ArrayList<>();
        int n = text.length();
        if (!getConfig().getBoolean("title.shine", true)) {
            out.add(left + base + deco + MM.escapeTags(text) + right);
            return out;
        }
        for (int i = -3; i <= n + 2; i++) {
            StringBuilder sb = new StringBuilder(left);
            for (int c = 0; c < n; c++) {
                String ch = MM.escapeTags(String.valueOf(text.charAt(c)));
                boolean hl = Math.abs(c - i) <= 1;
                sb.append(hl ? "<" + shine + ">" + deco + ch + "</" + shine + ">" : base + deco + ch + "</gradient>");
            }
            sb.append(right);
            out.add(sb.toString());
        }
        // pause sans reflet
        String still = left + base + deco + MM.escapeTags(text) + right;
        for (int k = 0; k < getConfig().getInt("title.pause-frames", 20); k++) out.add(still);
        return out;
    }

    // ================================================================= joueurs

    private void attach(Player p) {
        if (hidden(p) || disabledWorld(p)) return;
        Board b = boards.computeIfAbsent(p.getUniqueId(), k -> new Board());
        Scoreboard cur = p.getScoreboard();
        if (cur == Bukkit.getScoreboardManager().getMainScoreboard()) {
            mirror(b);
            p.setScoreboard(b.sb);
        }
    }

    private boolean hidden(Player p) {
        return p.getPersistentDataContainer().has(hiddenKey, PersistentDataType.BYTE);
    }

    private boolean disabledWorld(Player p) {
        return getConfig().getStringList("disabled-worlds").contains(p.getWorld().getName());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) attach(p);
        }, 10L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        boards.remove(e.getPlayer().getUniqueId());
        lastTab.remove(e.getPlayer().getUniqueId());
        lastName.remove(e.getPlayer().getUniqueId());
    }

    // ================================================================= mise à jour

    private void update() {
        tick++;
        int speed = Math.max(1, getConfig().getInt("title.speed-ticks", 3));
        boolean titleTick = tick % speed == 0;
        if (titleTick && !titleFrames.isEmpty()) frame = (frame + 1) % titleFrames.size();
        int every = Math.max(1, getConfig().getInt("update-ticks", 20));
        boolean full = tick % every == 0;
        if (!titleTick && !full) return;
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean off = hidden(p) || disabledWorld(p);
            Board b = boards.get(p.getUniqueId());
            if (off) {
                if (b != null && p.getScoreboard() == b.sb) p.setScoreboard(main);
                boards.remove(p.getUniqueId());
                if (full) tab(p);
                continue;
            }
            if (b == null || p.getScoreboard() == main) {
                attach(p);
                b = boards.get(p.getUniqueId());
            }
            if (b == null) continue;
            boolean mine = p.getScoreboard() == b.sb;
            if (titleTick && mine && !titleFrames.isEmpty()) {
                String t = titleFrames.get(frame);
                if (!t.equals(b.lastTitle)) {
                    b.obj.displayName(MM.deserialize(t));
                    b.lastTitle = t;
                }
            }
            if (full) {
                mirror(b);
                if (mine) lines(p, b);
                tab(p);
            }
        }
    }

    private void lines(Player p, Board b) {
        List<String> src = getConfig().getStringList("lines");
        int n = Math.min(15, src.size());
        for (int i = 0; i < n; i++) {
            String line = fill(p, src.get(i));
            Score s = b.obj.getScore(ENTRIES[i]);
            if (s.getScore() != n - i || !s.isScoreSet()) s.setScore(n - i);
            if (!line.equals(b.last[i])) {
                s.customName(MM.deserialize(line));
                b.last[i] = line;
            }
        }
        for (int i = n; i < b.lines; i++) {
            b.sb.resetScores(ENTRIES[i]);
            b.last[i] = null;
        }
        b.lines = n;
    }

    private void tab(Player p) {
        if (!getConfig().getBoolean("tab.enabled", true)) return;
        String h = fill(p, String.join("\n", getConfig().getStringList("tab.header")));
        String f = fill(p, String.join("\n", getConfig().getStringList("tab.footer")));
        String key = h + "\u0000" + f;
        if (!key.equals(lastTab.get(p.getUniqueId()))) {
            p.sendPlayerListHeaderAndFooter(MM.deserialize(h), MM.deserialize(f));
            lastTab.put(p.getUniqueId(), key);
        }
        String fmt = getConfig().getString("tab.name-format", "");
        if (fmt != null && !fmt.isBlank()) {
            String name = fill(p, fmt);
            // on ne remet le nom que s'il a changé : SmpTitles peut glisser son titre devant
            if (!name.equals(lastName.get(p.getUniqueId()))) {
                p.playerListName(MM.deserialize(name));
                lastName.put(p.getUniqueId(), name);
            }
        }
    }

    // ================================================================= équipes recopiées du scoreboard principal

    private void mirror(Board b) {
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        Set<String> seen = new HashSet<>();
        for (Team mt : main.getTeams()) {
            seen.add(mt.getName());
            Team t = b.sb.getTeam(mt.getName());
            if (t == null) {
                t = b.sb.registerNewTeam(mt.getName());
                b.mirrored.add(mt.getName());
            }
            if (!b.mirrored.contains(t.getName())) continue; // équipe d'un autre plugin sur ce scoreboard
            if (!mt.prefix().equals(t.prefix())) t.prefix(mt.prefix());
            if (!mt.suffix().equals(t.suffix())) t.suffix(mt.suffix());
            if (!mt.displayName().equals(t.displayName())) t.displayName(mt.displayName());
            Object c = mt.hasColor() ? mt.color() : null, tc = t.hasColor() ? t.color() : null;
            if (!java.util.Objects.equals(c, tc)) t.color(c instanceof NamedTextColor nc ? nc : null);
            if (mt.allowFriendlyFire() != t.allowFriendlyFire()) t.setAllowFriendlyFire(mt.allowFriendlyFire());
            if (mt.canSeeFriendlyInvisibles() != t.canSeeFriendlyInvisibles()) t.setCanSeeFriendlyInvisibles(mt.canSeeFriendlyInvisibles());
            for (Team.Option o : Team.Option.values()) {
                if (mt.getOption(o) != t.getOption(o)) t.setOption(o, mt.getOption(o));
            }
            Set<String> want = mt.getEntries();
            for (String e : want) {
                if (t.hasEntry(e)) continue;
                Team cur = b.sb.getEntryTeam(e);
                // une équipe locale (ex : pseudos cachés de SmpSalles) garde la main
                if (cur != null && !b.mirrored.contains(cur.getName())) continue;
                t.addEntry(e);
            }
            for (String e : new ArrayList<>(t.getEntries())) if (!want.contains(e)) t.removeEntry(e);
        }
        for (String name : new ArrayList<>(b.mirrored)) {
            if (seen.contains(name)) continue;
            Team t = b.sb.getTeam(name);
            if (t != null) t.unregister();
            b.mirrored.remove(name);
        }
    }

    // ================================================================= variables

    private String fill(Player p, String s) {
        if (s == null || s.isEmpty()) return "";
        if (s.indexOf('{') >= 0) {
            ZonedDateTime now = ZonedDateTime.now(zone);
            s = rep(s, "{player}", p.getName());
            s = rep(s, "{online}", String.valueOf(Bukkit.getOnlinePlayers().size()));
            s = rep(s, "{max}", String.valueOf(Bukkit.getMaxPlayers()));
            s = rep(s, "{ping}", String.valueOf(p.getPing()));
            s = rep(s, "{world}", p.getWorld().getName());
            s = rep(s, "{x}", String.valueOf(p.getLocation().getBlockX()));
            s = rep(s, "{y}", String.valueOf(p.getLocation().getBlockY()));
            s = rep(s, "{z}", String.valueOf(p.getLocation().getBlockZ()));
            s = rep(s, "{date}", now.format(dateFmt));
            s = rep(s, "{time}", now.format(timeFmt));
            s = rep(s, "{ip}", getConfig().getString("server-ip", ""));
            if (s.contains("{tps}")) s = rep(s, "{tps}", String.format(Locale.FRANCE, "%.1f", Math.min(20, Bukkit.getTPS()[0])));
            if (s.contains("{kills}")) s = rep(s, "{kills}", String.valueOf(p.getStatistic(Statistic.PLAYER_KILLS)));
            if (s.contains("{deaths}")) s = rep(s, "{deaths}", String.valueOf(p.getStatistic(Statistic.DEATHS)));
            if (s.contains("{kd}")) {
                int k = p.getStatistic(Statistic.PLAYER_KILLS), d = p.getStatistic(Statistic.DEATHS);
                s = rep(s, "{kd}", String.format(Locale.FRANCE, "%.2f", d == 0 ? k : (double) k / d));
            }
            if (s.contains("{playtime}")) {
                long min = p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20 / 60;
                s = rep(s, "{playtime}", min >= 60 ? (min / 60) + "h" + String.format("%02d", min % 60) : min + " min");
            }
            if (s.contains("{money}")) s = rep(s, "{money}", vault == null ? "0" : money(vault.money(p)));
            if (s.contains("{rank}")) s = rep(s, "{rank}", vault == null ? "" : legacyToMini(vault.prefix(p)));
            if (s.contains("{group}")) s = rep(s, "{group}", vault == null ? "" : vault.group(p));
            if (s.contains("{boss}")) s = rep(s, "{boss}", boss());
            if (s.contains("{duel_")) {
                s = rep(s, "{duel_elo}", duel("elo", p.getUniqueId(), "1000"));
                s = rep(s, "{duel_wins}", duel("wins", p.getUniqueId(), "0"));
                s = rep(s, "{duel_losses}", duel("losses", p.getUniqueId(), "0"));
            }
        }
        if (s.indexOf('%') >= 0) {
            s = papi(p, s);
            // variable inconnue (PlaceholderAPI absent...) : on l'efface plutôt que d'afficher %truc%
            s = s.replaceAll("%[A-Za-z0-9_]+%", "");
        }
        if (s.indexOf('§') >= 0 || s.indexOf('&') >= 0) s = legacyToMini(s);
        return s;
    }

    private static String rep(String s, String k, String v) {
        return s.contains(k) ? s.replace(k, v) : s;
    }

    /** 950 · 12,3k · 4,56M · 1,2Md */
    static String money(double v) {
        double a = Math.abs(v);
        if (a < 1000) return String.valueOf((long) v);
        String[] u = {"k", "M", "Md", "B"};
        int i = -1;
        while (a >= 1000 && i < u.length - 1) {
            a /= 1000;
            v /= 1000;
            i++;
        }
        String n = a >= 100 ? String.valueOf((long) v) : a >= 10 ? String.format(Locale.FRANCE, "%.1f", v) : String.format(Locale.FRANCE, "%.2f", v);
        if (n.contains(",")) n = n.replaceAll(",?0+$", "");
        return n + u[i];
    }

    // ---- autres plugins (sans dépendance : par réflexion)

    private Method bossMethod;
    private Object bossPlugin;

    private String boss() {
        Plugin pl = Bukkit.getPluginManager().getPlugin("SmpBoss");
        if (pl == null || !pl.isEnabled()) return getConfig().getString("no-boss", "<gray>—");
        try {
            if (bossPlugin != pl) {
                bossMethod = pl.getClass().getMethod("scoreboardStatus");
                bossPlugin = pl;
            }
            return String.valueOf(bossMethod.invoke(pl));
        } catch (ReflectiveOperationException e) {
            return getConfig().getString("no-boss", "<gray>—");
        }
    }

    private final Map<String, Method> duelMethods = new HashMap<>();

    private String duel(String what, UUID id, String def) {
        Plugin pl = Bukkit.getPluginManager().getPlugin("SmpDuel");
        if (pl == null || !pl.isEnabled()) return def;
        try {
            Method m = duelMethods.get(what);
            if (m == null || m.getDeclaringClass() != pl.getClass()) {
                m = pl.getClass().getMethod(what, UUID.class);
                duelMethods.put(what, m);
            }
            return String.valueOf(m.invoke(null, id));
        } catch (ReflectiveOperationException e) {
            return def;
        }
    }

    private Method papiMethod;

    private String papi(Player p, String s) {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return s;
        try {
            if (papiMethod == null) {
                Class<?> c = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
                papiMethod = c.getMethod("setPlaceholders", org.bukkit.OfflinePlayer.class, String.class);
            }
            return String.valueOf(papiMethod.invoke(null, p, s));
        } catch (ReflectiveOperationException e) {
            return s;
        }
    }

    // ---- couleurs "&c" / "§c" / "&#ff0000" (grades LuckPerms, PlaceholderAPI) → MiniMessage

    private static final Pattern HEX = Pattern.compile("[&§]#([0-9a-fA-F]{6})");
    private static final Pattern SECTION_HEX = Pattern.compile("§x(§[0-9a-fA-F]){6}");
    private static final Map<Character, String> CODES = Map.ofEntries(
            Map.entry('0', "<black>"), Map.entry('1', "<dark_blue>"), Map.entry('2', "<dark_green>"), Map.entry('3', "<dark_aqua>"),
            Map.entry('4', "<dark_red>"), Map.entry('5', "<dark_purple>"), Map.entry('6', "<gold>"), Map.entry('7', "<gray>"),
            Map.entry('8', "<dark_gray>"), Map.entry('9', "<blue>"), Map.entry('a', "<green>"), Map.entry('b', "<aqua>"),
            Map.entry('c', "<red>"), Map.entry('d', "<light_purple>"), Map.entry('e', "<yellow>"), Map.entry('f', "<white>"),
            Map.entry('k', "<obf>"), Map.entry('l', "<b>"), Map.entry('m', "<st>"), Map.entry('n', "<u>"), Map.entry('o', "<i>"),
            Map.entry('r', "<reset>"));

    static String legacyToMini(String s) {
        if (s == null || s.isEmpty()) return "";
        Matcher sx = SECTION_HEX.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (sx.find()) sx.appendReplacement(sb, "<#" + sx.group().replace("§x", "").replace("§", "") + ">");
        sx.appendTail(sb);
        s = HEX.matcher(sb.toString()).replaceAll("<#$1>");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < s.length()) {
                String tag = CODES.get(Character.toLowerCase(s.charAt(i + 1)));
                if (tag != null) {
                    out.append(tag);
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    @SuppressWarnings("unused")
    private static Component legacy(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }

    // ================================================================= commande

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length >= 1 && a[0].equalsIgnoreCase("reload") && s.hasPermission("smpboard.admin")) {
            load();
            s.sendMessage(MM.deserialize("<green>SmpBoard rechargé."));
            return true;
        }
        if (!(s instanceof Player p)) return true;
        if (hidden(p)) {
            p.getPersistentDataContainer().remove(hiddenKey);
            attach(p);
            p.sendMessage(MM.deserialize(getConfig().getString("messages.shown", "<green>Scoreboard affiché.")));
        } else {
            p.getPersistentDataContainer().set(hiddenKey, PersistentDataType.BYTE, (byte) 1);
            Board b = boards.remove(p.getUniqueId());
            if (b != null && p.getScoreboard() == b.sb) p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            p.sendMessage(MM.deserialize(getConfig().getString("messages.hidden", "<gray>Scoreboard caché. <white>/sb</white> pour le remettre.")));
        }
        return true;
    }
}
