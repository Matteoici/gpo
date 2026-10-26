package fr.smpantiflood;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-flood du chat : trop de messages trop vite, même message répété, lettres répétées (aaaaaaa),
 * MAJUSCULES, et sourdine automatique pour ceux qui insistent. Vaut aussi pour /msg, /r...
 */
public final class SmpAntiFlood extends JavaPlugin implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private static final class State {
        final Deque<Long> times = new ArrayDeque<>();
        final Deque<String> lastMessages = new ArrayDeque<>();
        final Deque<Long> lastTimes = new ArrayDeque<>();
        final Deque<Long> strikes = new ArrayDeque<>();
        long mutedUntil;
        long lastWarn;
    }

    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
    }

    private String msg(String key) {
        String s = getConfig().getString("messages." + key);
        return s == null ? "<red>[" + key + "]" : s;
    }

    // ================================================================= chat

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if (p.hasPermission("smpantiflood.bypass")) return;
        String plain = PlainTextComponentSerializer.plainText().serialize(e.message());
        String fixed = check(p, plain);
        if (fixed == null) {
            e.setCancelled(true);
            return;
        }
        if (!fixed.equals(plain)) e.message(Component.text(fixed));
    }

    // ================================================================= /msg, /r...

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (p.hasPermission("smpantiflood.bypass")) return;
        String line = e.getMessage().substring(1);
        String[] parts = line.split(" ", 2);
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        if (cmd.contains(":")) cmd = cmd.substring(cmd.indexOf(':') + 1);
        List<String> watched = getConfig().getStringList("commands");
        if (!watched.contains(cmd) || parts.length < 2) return;
        // /msg <joueur> <texte> : on vérifie le texte ; /r <texte> : tout
        String text = parts[1];
        boolean withTarget = getConfig().getStringList("commands-with-target").contains(cmd);
        String target = "";
        if (withTarget) {
            String[] t = text.split(" ", 2);
            if (t.length < 2) return;
            target = t[0] + " ";
            text = t[1];
        }
        String fixed = check(p, text);
        if (fixed == null) {
            e.setCancelled(true);
            return;
        }
        if (!fixed.equals(text)) e.setMessage("/" + parts[0] + " " + target + fixed);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        State s = states.get(e.getPlayer().getUniqueId());
        if (s != null && s.mutedUntil < System.currentTimeMillis()) states.remove(e.getPlayer().getUniqueId());
    }

    // ================================================================= règles

    /** Renvoie le message (éventuellement corrigé) ou null s'il est bloqué. */
    private String check(Player p, String message) {
        var c = getConfig();
        long now = System.currentTimeMillis();
        State s = states.computeIfAbsent(p.getUniqueId(), k -> new State());
        synchronized (s) {
            if (s.mutedUntil > now) {
                tell(p, msg("muted").replace("<seconds>", String.valueOf((s.mutedUntil - now) / 1000 + 1)));
                return null;
            }
            // 1) délai minimum entre deux messages
            long minGap = (long) (c.getDouble("min-delay-seconds", 1.0) * 1000);
            if (!s.times.isEmpty() && now - s.times.peekLast() < minGap) {
                strike(p, s, now, msg("too-fast"));
                return null;
            }
            // 2) trop de messages sur une période
            long window = (long) (c.getDouble("burst.seconds", 8) * 1000);
            int max = c.getInt("burst.max-messages", 5);
            while (!s.times.isEmpty() && now - s.times.peekFirst() > window) s.times.pollFirst();
            if (s.times.size() >= max) {
                strike(p, s, now, msg("too-many"));
                return null;
            }
            // 3) lettres / symboles répétés : "aaaaaaa" -> "aaa", "!!!!!!" -> "!!!"
            String text = message;
            int maxRepeat = Math.max(2, c.getInt("max-repeated-chars", 3));
            text = text.replaceAll("([^0-9])\\1{" + maxRepeat + ",}", "$1".repeat(maxRepeat));
            // mots répétés : "lol lol lol lol lol" -> "lol lol lol"
            text = text.replaceAll("(?i)\\b(\\S+)(\\s+\\1\\b){" + maxRepeat + ",}", "$1".repeat(1) + " $1".repeat(maxRepeat - 1));
            // 4) MAJUSCULES
            int letters = 0, caps = 0;
            for (char ch : text.toCharArray()) {
                if (Character.isLetter(ch)) {
                    letters++;
                    if (Character.isUpperCase(ch)) caps++;
                }
            }
            if (letters >= c.getInt("caps.min-letters", 8) && caps * 100 >= letters * c.getInt("caps.max-percent", 70)) {
                text = text.toLowerCase(Locale.ROOT);
                if (now - s.lastWarn > 5000) {
                    s.lastWarn = now;
                    tell(p, msg("caps"));
                }
            }
            // 5) même message (ou presque) que l'un des derniers
            String norm = normalize(text);
            long dupWindow = (long) (c.getDouble("duplicate.seconds", 30) * 1000);
            double sim = c.getDouble("duplicate.similarity", 0.85);
            while (!s.lastTimes.isEmpty() && now - s.lastTimes.peekFirst() > dupWindow) {
                s.lastTimes.pollFirst();
                s.lastMessages.pollFirst();
            }
            if (norm.length() >= c.getInt("duplicate.min-length", 3)) {
                for (String prev : s.lastMessages) {
                    if (prev.equals(norm) || similarity(prev, norm) >= sim) {
                        strike(p, s, now, msg("duplicate"));
                        return null;
                    }
                }
            }
            if (normalize(text).isEmpty() && !message.isBlank() && c.getBoolean("block-symbol-only", true) && message.length() > 6) {
                strike(p, s, now, msg("symbols"));
                return null;
            }
            s.times.addLast(now);
            s.lastMessages.addLast(norm);
            s.lastTimes.addLast(now);
            while (s.lastMessages.size() > c.getInt("duplicate.remember", 3)) {
                s.lastMessages.pollFirst();
                s.lastTimes.pollFirst();
            }
            return text;
        }
    }

    /** Un avertissement de plus ; trop d'avertissements = sourdine. */
    private void strike(Player p, State s, long now, String warning) {
        tell(p, warning);
        long window = (long) (getConfig().getDouble("mute.window-seconds", 60) * 1000);
        s.strikes.addLast(now);
        while (!s.strikes.isEmpty() && now - s.strikes.peekFirst() > window) s.strikes.pollFirst();
        if (s.strikes.size() >= getConfig().getInt("mute.strikes", 5)) {
            s.strikes.clear();
            int secs = getConfig().getInt("mute.seconds", 60);
            s.mutedUntil = now + secs * 1000L;
            tell(p, msg("muted-now").replace("<seconds>", String.valueOf(secs)));
            String staff = msg("staff-alert").replace("<player>", p.getName()).replace("<seconds>", String.valueOf(secs));
            Bukkit.getScheduler().runTask(this, () -> {
                for (Player o : Bukkit.getOnlinePlayers()) if (o.hasPermission("smpantiflood.alerts")) o.sendMessage(MM.deserialize(staff));
            });
        }
    }

    private void tell(Player p, String m) {
        Component c = MM.deserialize(m);
        Bukkit.getScheduler().runTask(this, () -> {
            if (p.isOnline()) p.sendMessage(c);
        });
    }

    private static String normalize(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toLowerCase(Locale.ROOT).toCharArray()) if (Character.isLetterOrDigit(ch)) b.append(ch);
        return b.toString();
    }

    /** 1 = identiques ; basé sur la distance de Levenshtein. */
    private static double similarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (Math.abs(a.length() - b.length()) > Math.max(a.length(), b.length()) * 0.4) return 0;
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return 1.0 - prev[b.length()] / (double) Math.max(a.length(), b.length());
    }

    // ================================================================= commande

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (a.length >= 1 && a[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage(MM.deserialize("<green>SmpAntiFlood rechargé."));
            return true;
        }
        if (a.length >= 2 && a[0].equalsIgnoreCase("unmute")) {
            Player t = Bukkit.getPlayerExact(a[1]);
            if (t != null) {
                State s = states.get(t.getUniqueId());
                if (s != null) s.mutedUntil = 0;
                sender.sendMessage(MM.deserialize("<green>" + t.getName() + " peut de nouveau parler."));
            }
            return true;
        }
        sender.sendMessage(MM.deserialize("<gold>/antiflood reload · /antiflood unmute <joueur>"));
        return true;
    }
}
