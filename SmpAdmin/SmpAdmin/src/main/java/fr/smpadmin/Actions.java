package fr.smpadmin;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static fr.smpadmin.Texts.pc;
import static fr.smpadmin.Texts.ph;

/** Toutes les actions de modération, utilisées par les commandes et par les menus. */
public final class Actions {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final SmpAdmin plugin;

    Actions(SmpAdmin plugin) {
        this.plugin = plugin;
    }

    private Texts t() {
        return plugin.texts();
    }

    private Settings s() {
        return plugin.settings();
    }

    private Sanctions sanctions() {
        return plugin.sanctions();
    }

    // ================================================================= utilitaires

    public static String name(CommandSender sender) {
        return sender instanceof Player p ? p.getName() : "Console";
    }

    /** Joueur connecté, ou déjà venu sur le serveur (sans requête vers Mojang). */
    public OfflinePlayer resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(name);
        if (off != null && (off.hasPlayedBefore() || off.isOnline())) return off;
        return null;
    }

    public String date(long ms) {
        return DATE.format(Instant.ofEpochMilli(ms).atZone(s().zone));
    }

    public String duration(long ms) {
        return ms == 0 ? t().raw("duration.permanent") : Durations.format(ms);
    }

    public String remaining(Punishment p) {
        return p.duration == 0 ? t().raw("duration.permanent") : Durations.format(p.remaining(System.currentTimeMillis()));
    }

    public TagResolver resolvers(Punishment p) {
        return TagResolver.resolver(ph("player", p.targetName), ph("staff", p.staff), ph("reason", p.reason),
                ph("id", p.id), ph("duration", p.hasDuration() ? duration(p.duration) : "-"),
                ph("remaining", p.hasDuration() ? remaining(p) : "-"), ph("date", date(p.created)));
    }

    /** Message au staff (permission smpadmin.alerts) et à la console. */
    public void alert(Component message) {
        Component full = Texts.MM.deserialize(t().raw("alert-prefix")).append(message);
        for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("smpadmin.alerts")) p.sendMessage(full);
        Bukkit.getConsoleSender().sendMessage(full);
    }

    public void alert(String key, TagResolver... r) {
        alert(t().get(key, r));
    }

    private void broadcast(String key, TagResolver... r) {
        Component c = t().prefixed(key, r);
        for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(c);
    }

    /** Vérifie qu'on a le droit de sanctionner ce joueur. */
    private boolean canTarget(CommandSender staff, OfflinePlayer target) {
        if (staff instanceof Player sp && sp.getUniqueId().equals(target.getUniqueId())) {
            t().send(staff, "error.self");
            return false;
        }
        Player online = target.getPlayer();
        boolean exempt = online != null ? online.hasPermission("smpadmin.exempt") : target.isOp();
        if (exempt && staff instanceof Player sp && !sp.hasPermission("smpadmin.exempt")) {
            t().send(staff, "error.exempt", ph("player", nameOf(target)));
            return false;
        }
        return true;
    }

    private static String nameOf(OfflinePlayer p) {
        return p.getName() == null ? p.getUniqueId().toString().substring(0, 8) : p.getName();
    }

    public static void sound(Player p, String key, float pitch) {
        p.playSound(Sound.sound(Key.key("minecraft", key), Sound.Source.MASTER, 1f, pitch));
    }

    // ================================================================= écrans

    public Component banScreen(Punishment p) {
        return t().lines("ban.screen", resolvers(p));
    }

    // ================================================================= ban / unban

    public boolean ban(CommandSender staff, OfflinePlayer target, long duration, String reason, boolean silent) {
        if (!canTarget(staff, target)) return false;
        if (duration == Durations.PERMANENT && !staff.hasPermission("smpadmin.ban.perm")) {
            t().send(staff, "error.perm-ban");
            return false;
        }
        Punishment old = sanctions().activeBan(target.getUniqueId());
        if (old != null) sanctions().revoke(old, name(staff));
        Punishment p = sanctions().add(Punishment.Type.BAN, target.getUniqueId(), nameOf(target), name(staff), reason, duration);
        plugin.state().frozen.remove(target.getUniqueId());
        Player online = target.getPlayer();
        if (online != null) online.kick(banScreen(p));
        alert("ban.alert", resolvers(p));
        if (s().broadcastBan && !silent) broadcast("ban.broadcast", resolvers(p));
        return true;
    }

    public boolean unban(CommandSender staff, OfflinePlayer target) {
        Punishment p = sanctions().activeBan(target.getUniqueId());
        if (p == null) {
            t().send(staff, "ban.not-banned", ph("player", nameOf(target)));
            return false;
        }
        sanctions().revoke(p, name(staff));
        alert("ban.unban-alert", ph("staff", name(staff)), ph("player", p.targetName));
        return true;
    }

    // ================================================================= mute / unmute

    public boolean mute(CommandSender staff, OfflinePlayer target, long duration, String reason, boolean silent) {
        if (!canTarget(staff, target)) return false;
        Punishment old = sanctions().activeMute(target.getUniqueId());
        if (old != null) sanctions().revoke(old, name(staff));
        Punishment p = sanctions().add(Punishment.Type.MUTE, target.getUniqueId(), nameOf(target), name(staff), reason, duration);
        Player online = target.getPlayer();
        if (online != null) {
            t().send(online, "mute.notify", resolvers(p));
            sound(online, "block.note_block.bass", 0.6f);
        }
        alert("mute.alert", resolvers(p));
        if (s().broadcastMute && !silent) broadcast("mute.broadcast", resolvers(p));
        return true;
    }

    public boolean unmute(CommandSender staff, OfflinePlayer target) {
        Punishment p = sanctions().activeMute(target.getUniqueId());
        if (p == null) {
            t().send(staff, "mute.not-muted", ph("player", nameOf(target)));
            return false;
        }
        sanctions().revoke(p, name(staff));
        Player online = target.getPlayer();
        if (online != null) t().send(online, "mute.unmuted");
        alert("mute.unmute-alert", ph("staff", name(staff)), ph("player", p.targetName));
        return true;
    }

    /** Lève une sanction depuis le casier (ban ou mute actif). */
    public void revoke(CommandSender staff, Punishment p) {
        if (!p.isActive(System.currentTimeMillis())) return;
        OfflinePlayer target = Bukkit.getOfflinePlayer(p.target);
        if (p.type == Punishment.Type.BAN) unban(staff, target);
        else if (p.type == Punishment.Type.MUTE) unmute(staff, target);
    }

    // ================================================================= kick / warn

    public boolean kick(CommandSender staff, Player target, String reason, boolean silent) {
        if (!canTarget(staff, target)) return false;
        Punishment p = sanctions().add(Punishment.Type.KICK, target.getUniqueId(), target.getName(), name(staff), reason, -1);
        plugin.state().frozen.remove(target.getUniqueId());
        target.kick(t().lines("kick.screen", resolvers(p)));
        alert("kick.alert", resolvers(p));
        if (s().broadcastKick && !silent) broadcast("kick.broadcast", resolvers(p));
        return true;
    }

    public boolean warn(CommandSender staff, OfflinePlayer target, String reason, boolean silent) {
        if (!canTarget(staff, target)) return false;
        Punishment p = sanctions().add(Punishment.Type.WARN, target.getUniqueId(), nameOf(target), name(staff), reason, -1);
        int count = sanctions().count(target.getUniqueId(), Punishment.Type.WARN);
        TagResolver r = TagResolver.resolver(resolvers(p), ph("count", count));
        Player online = target.getPlayer();
        if (online != null) {
            online.showTitle(Title.title(t().get("warn.title", r), t().get("warn.subtitle", r),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofSeconds(1))));
            t().send(online, "warn.message", r);
            sound(online, "entity.villager.no", 0.8f);
        }
        alert("warn.alert", r);
        if (s().broadcastWarn && !silent) broadcast("warn.broadcast", r);
        return true;
    }

    // ================================================================= freeze

    public boolean isFrozen(UUID id) {
        return plugin.state().frozen.contains(id);
    }

    public void freeze(CommandSender staff, Player target, boolean on) {
        if (on && !canTarget(staff, target)) return;
        if (on) {
            plugin.state().frozen.add(target.getUniqueId());
            showFreeze(target);
            t().send(target, "freeze.chat");
            sound(target, "block.glass.break", 0.6f);
            alert("freeze.alert-on", ph("staff", name(staff)), ph("player", target.getName()));
        } else {
            plugin.state().frozen.remove(target.getUniqueId());
            target.clearTitle();
            t().send(target, "freeze.unfrozen");
            sound(target, "entity.player.levelup", 1.2f);
            alert("freeze.alert-off", ph("staff", name(staff)), ph("player", target.getName()));
        }
        plugin.state().save();
    }

    public void showFreeze(Player p) {
        p.showTitle(Title.title(t().get("freeze.title"), t().get("freeze.subtitle"),
                Title.Times.times(Duration.ZERO, Duration.ofSeconds(4), Duration.ofMillis(300))));
    }

    /** Le joueur gelé s'est déconnecté. */
    public void frozenQuit(Player p) {
        alert("freeze.quit-alert", ph("player", p.getName()));
        if (!s().banOnFreezeQuit) return;
        UUID id = p.getUniqueId();
        String name = p.getName();
        plugin.state().frozen.remove(id);
        plugin.state().save();
        // le joueur est encore "en ligne" pendant l'événement de déconnexion : on bannit juste après
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Punishment old = sanctions().activeBan(id);
            if (old != null) return;
            Punishment b = sanctions().add(Punishment.Type.BAN, id, name, "Console", s().freezeBanReason, s().freezeBanDuration);
            alert("ban.alert", resolvers(b));
            if (s().broadcastBan) broadcast("ban.broadcast", resolvers(b));
        }, 2L);
    }

    // ================================================================= suspects

    public void sus(CommandSender staff, OfflinePlayer target, String reason) {
        String r = reason == null || reason.isBlank() ? t().raw("sus.default-reason") : reason;
        plugin.state().addSuspect(new StaffState.Suspect(target.getUniqueId(), nameOf(target), r, name(staff),
                System.currentTimeMillis()));
        alert("sus.added", ph("staff", name(staff)), ph("player", nameOf(target)), ph("reason", r));
    }

    public void unsus(CommandSender staff, OfflinePlayer target) {
        if (plugin.state().removeSuspect(target.getUniqueId())) {
            alert("sus.removed", ph("staff", name(staff)), ph("player", nameOf(target)));
        } else {
            t().send(staff, "sus.not-sus", ph("player", nameOf(target)));
        }
    }

    /** Alerte staff quand un suspect se connecte, avec boutons cliquables. */
    public void suspectJoined(Player p, StaffState.Suspect s) {
        Component spec = t().get("sus.spec-button").clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/spec " + p.getName()));
        Component sheet = t().get("sus.sheet-button").clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/admin " + p.getName()));
        alert(t().get("sus.join", ph("player", p.getName()), ph("reason", s.reason()), pc("spec", spec), pc("sheet", sheet)));
    }

    // ================================================================= chat

    public void clearChat(CommandSender staff) {
        Component blank = Component.empty();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.hasPermission("smpadmin.chat")) for (int i = 0; i < 120; i++) p.sendMessage(blank);
            t().send(p, "chat.cleared", ph("player", name(staff)));
        }
    }

    public void toggleChatLock(CommandSender staff) {
        StaffState st = plugin.state();
        st.chatLocked = !st.chatLocked;
        st.save();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (st.chatLocked) t().send(p, "chat.locked", ph("player", name(staff)));
            else t().send(p, "chat.unlocked");
        }
    }

    public void staffChat(String from, String message) {
        Component c = t().get("staffchat.format", ph("player", from), ph("message", message));
        for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("smpadmin.staffchat")) p.sendMessage(c);
        Bukkit.getConsoleSender().sendMessage(c);
    }
}
