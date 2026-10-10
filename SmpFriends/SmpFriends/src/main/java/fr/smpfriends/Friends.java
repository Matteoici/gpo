package fr.smpfriends;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Toute la logique : demandes, amis, favoris, messages, argent, notifications. */
public final class Friends {

    private final FriendsPlugin plugin;

    Friends(FriendsPlugin plugin) {
        this.plugin = plugin;
    }

    private FriendStore store() {
        return plugin.store();
    }

    public Profile profile(UUID id) {
        return store().get(id);
    }

    public boolean areFriends(UUID a, UUID b) {
        return profile(a).friends.contains(b);
    }

    /** Ce réglage du joueur owner est-il activé ? (valeur par défaut prise dans la config) */
    public boolean allows(UUID owner, Setting s) {
        return profile(owner).get(s, plugin.getConfig().getBoolean("defaults." + s.name(), true));
    }

    private int limit(OfflinePlayer p) {
        Player online = p.getPlayer();
        if (online != null && online.hasPermission("smpfriends.unlimited")) return Integer.MAX_VALUE;
        return Math.max(1, plugin.getConfig().getInt("max-friends", 30));
    }

    public static String name(UUID id) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        return op.getName() != null ? op.getName() : "?";
    }

    /** Trouve un joueur déjà venu sur le serveur, en ligne ou non. */
    public static OfflinePlayer find(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        return Bukkit.getOfflinePlayerIfCached(name);
    }

    // =============================================================== demandes

    public void request(Player from, String targetName) {
        OfflinePlayer target = find(targetName);
        if (target == null) {
            from.sendMessage(Text.msg("Joueur introuvable (il doit déjà être venu sur le serveur).", NamedTextColor.RED));
            return;
        }
        UUID f = from.getUniqueId(), t = target.getUniqueId();
        if (f.equals(t)) {
            from.sendMessage(Text.msg("Tu ne peux pas t'ajouter toi-même.", NamedTextColor.RED));
            return;
        }
        if (areFriends(f, t)) {
            from.sendMessage(Text.msg(target.getName() + " est déjà ton ami.", NamedTextColor.YELLOW));
            return;
        }
        // il m'avait déjà demandé : on accepte directement
        if (profile(f).requests.contains(t)) {
            accept(from, t);
            return;
        }
        if (!allows(t, Setting.ALLOW_REQUESTS)) {
            from.sendMessage(Text.msg(target.getName() + " n'accepte pas les demandes d'ami.", NamedTextColor.RED));
            return;
        }
        if (profile(f).friends.size() >= limit(from)) {
            from.sendMessage(Text.msg("Tu as atteint la limite d'amis.", NamedTextColor.RED));
            return;
        }
        if (!profile(t).requests.add(f)) {
            from.sendMessage(Text.msg("Demande déjà envoyée à " + target.getName() + ".", NamedTextColor.GRAY));
            return;
        }
        store().save();
        from.sendMessage(Text.msg("Demande envoyée à " + target.getName() + ".", NamedTextColor.GREEN));
        Player online = target.getPlayer();
        if (online != null) {
            online.sendMessage(Text.msg(from.getName() + " veut être ton ami ! ", NamedTextColor.WHITE)
                    .append(Text.button("[✔ Accepter]", NamedTextColor.GREEN, "/amis accept " + from.getName(), "Accepter"))
                    .append(Component.text(" "))
                    .append(Text.button("[✖ Refuser]", NamedTextColor.RED, "/amis deny " + from.getName(), "Refuser")));
            online.playSound(online.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
        }
    }

    public void accept(Player p, UUID from) {
        Profile me = profile(p.getUniqueId());
        if (!me.requests.remove(from)) {
            p.sendMessage(Text.msg("Aucune demande de ce joueur.", NamedTextColor.RED));
            return;
        }
        OfflinePlayer other = Bukkit.getOfflinePlayer(from);
        if (me.friends.size() >= limit(p) || profile(from).friends.size() >= limit(other)) {
            store().save();
            p.sendMessage(Text.msg("Limite d'amis atteinte (pour toi ou pour lui).", NamedTextColor.RED));
            return;
        }
        me.friends.add(from);
        profile(from).friends.add(p.getUniqueId());
        profile(from).requests.remove(p.getUniqueId());
        store().save();
        p.sendMessage(Text.msg("Tu es maintenant ami avec " + name(from) + " !", NamedTextColor.GREEN));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.5f);
        Player o = other.getPlayer();
        if (o != null) {
            o.sendMessage(Text.msg(p.getName() + " a accepté ta demande d'ami !", NamedTextColor.GREEN));
            o.playSound(o.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.5f);
        }
    }

    public void deny(Player p, UUID from) {
        if (profile(p.getUniqueId()).requests.remove(from)) {
            store().save();
            p.sendMessage(Text.msg("Demande de " + name(from) + " refusée.", NamedTextColor.GRAY));
        } else {
            p.sendMessage(Text.msg("Aucune demande de ce joueur.", NamedTextColor.RED));
        }
    }

    public void remove(Player p, UUID other) {
        Profile me = profile(p.getUniqueId());
        if (!me.friends.remove(other)) {
            p.sendMessage(Text.msg("Ce joueur n'est pas ton ami.", NamedTextColor.RED));
            return;
        }
        me.favorites.remove(other);
        profile(other).friends.remove(p.getUniqueId());
        profile(other).favorites.remove(p.getUniqueId());
        store().save();
        p.sendMessage(Text.msg(name(other) + " a été retiré de tes amis.", NamedTextColor.YELLOW));
    }

    public boolean toggleFavorite(Player p, UUID other) {
        Profile me = profile(p.getUniqueId());
        boolean now = !me.favorites.remove(other);
        if (now) me.favorites.add(other);
        store().save();
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, now ? 1.6f : 1f);
        return now;
    }

    // =============================================================== messages / argent

    public void message(Player from, UUID to, String text) {
        Player target = Bukkit.getPlayer(to);
        if (target == null) {
            from.sendMessage(Text.msg(name(to) + " est hors ligne.", NamedTextColor.RED));
            return;
        }
        from.sendMessage(Component.text("✉ Toi → " + target.getName() + " : ", Text.PREFIX_COLOR)
                .append(Component.text(text, NamedTextColor.WHITE)));
        target.sendMessage(Component.text("✉ " + from.getName() + " → toi : ", Text.PREFIX_COLOR)
                .append(Component.text(text, NamedTextColor.WHITE))
                .append(Component.text("  "))
                .append(Text.button("[Répondre]", Text.ACCENT, "/amis msg " + from.getName(), "Répondre")));
        target.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 1.6f);
    }

    public void friendChat(Player from, String text) {
        int sent = 0;
        Component line = Component.text("[Amis] ", Text.PREFIX_COLOR)
                .append(Component.text(from.getName() + " : ", NamedTextColor.YELLOW))
                .append(Component.text(text, NamedTextColor.WHITE));
        for (UUID u : profile(from.getUniqueId()).friends) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) {
                p.sendMessage(line);
                sent++;
            }
        }
        from.sendMessage(line);
        if (sent == 0) from.sendMessage(Text.msg("Aucun ami connecté n'a reçu le message.", NamedTextColor.GRAY));
    }

    public void pay(Player from, UUID to, double amount) {
        VaultHook v = plugin.vault();
        if (v == null || !v.available()) {
            from.sendMessage(Text.msg("Économie indisponible.", NamedTextColor.RED));
            return;
        }
        if (amount <= 0) return;
        OfflinePlayer target = Bukkit.getOfflinePlayer(to);
        String sym = plugin.symbol();
        if (!v.transfer(from, target, amount)) {
            from.sendMessage(Text.msg("Solde insuffisant ou paiement refusé.", NamedTextColor.RED));
            return;
        }
        from.sendMessage(Text.msg("Tu as envoyé " + Text.money(amount, sym) + " à " + name(to) + ".", NamedTextColor.GREEN));
        from.playSound(from.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.4f);
        Player o = target.getPlayer();
        if (o != null) {
            o.sendMessage(Text.msg(from.getName() + " t'a envoyé " + Text.money(amount, sym) + " !", NamedTextColor.GOLD));
            o.playSound(o.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
        }
    }

    /** Lance une commande d'un autre plugin depuis le joueur (« tpa {player} »...). */
    public void runCommand(Player p, String key, String friendName) {
        String cmd = plugin.getConfig().getString("commands." + key, "");
        if (cmd == null || cmd.isBlank()) return;
        p.closeInventory();
        p.performCommand(cmd.replace("{player}", friendName));
    }

    public boolean commandEnabled(String key) {
        String cmd = plugin.getConfig().getString("commands." + key, "");
        return cmd != null && !cmd.isBlank();
    }

    // =============================================================== liste / notifications

    /** Favoris d'abord, puis amis en ligne, puis ordre alphabétique. */
    public List<UUID> sorted(Player viewer, boolean onlineOnly) {
        Profile me = profile(viewer.getUniqueId());
        List<UUID> list = new ArrayList<>();
        for (UUID u : me.friends) {
            if (!onlineOnly || Bukkit.getPlayer(u) != null) list.add(u);
        }
        list.sort(Comparator.<UUID>comparingInt(u -> me.favorites.contains(u) ? 0 : 1)
                .thenComparingInt(u -> Bukkit.getPlayer(u) != null ? 0 : 1)
                .thenComparing(u -> name(u).toLowerCase()));
        return list;
    }

    public int onlineCount(Player p) {
        int n = 0;
        for (UUID u : profile(p.getUniqueId()).friends) if (Bukkit.getPlayer(u) != null) n++;
        return n;
    }

    void onJoin(Player p) {
        Profile me = profile(p.getUniqueId());
        if (!p.getName().equals(me.name)) {
            me.name = p.getName();
            store().save();
        }
        for (UUID u : me.friends) {
            Player f = Bukkit.getPlayer(u);
            if (f != null && allows(u, Setting.NOTIFICATIONS)) {
                f.sendMessage(Component.text("● ", NamedTextColor.GREEN)
                        .append(Component.text(p.getName() + " vient de se connecter", NamedTextColor.GRAY)));
                f.playSound(f.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.8f);
            }
        }
        int online = onlineCount(p);
        if (!me.friends.isEmpty()) {
            p.sendMessage(Text.msg(online + "/" + me.friends.size() + " amis en ligne. ", NamedTextColor.WHITE)
                    .append(Text.button("[Ouvrir]", Text.ACCENT, "/amis", "Ouvrir le menu des amis")));
        }
        if (!me.requests.isEmpty()) {
            p.sendMessage(Text.msg("Tu as " + me.requests.size() + " demande(s) d'ami ! ", NamedTextColor.GOLD)
                    .append(Text.button("[Voir]", NamedTextColor.GREEN, "/amis requests", "Voir les demandes")));
        }
    }

    void onQuit(Player p) {
        for (UUID u : profile(p.getUniqueId()).friends) {
            Player f = Bukkit.getPlayer(u);
            if (f != null && allows(u, Setting.NOTIFICATIONS)) {
                f.sendMessage(Component.text("○ ", NamedTextColor.RED)
                        .append(Component.text(p.getName() + " s'est déconnecté", NamedTextColor.GRAY)));
            }
        }
    }
}
