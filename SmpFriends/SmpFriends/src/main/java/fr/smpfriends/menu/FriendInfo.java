package fr.smpfriends.menu;

import fr.smpfriends.FriendsPlugin;
import fr.smpfriends.Setting;
import fr.smpfriends.Text;
import fr.smpfriends.VaultHook;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Construit les lignes d'information sur un ami (statut, position, argent, stats). */
final class FriendInfo {

    private FriendInfo() {
    }

    static Component status(FriendsPlugin plugin, OfflinePlayer friend) {
        Player p = friend.getPlayer();
        if (p == null) {
            long last = friend.getLastSeen();
            String ago = last > 0 ? " · vu il y a " + Text.duration(System.currentTimeMillis() - last) : "";
            return Text.t("○ Hors ligne" + ago, NamedTextColor.RED);
        }
        if (plugin.activity().isAfk(p)) {
            return Text.t("◐ AFK depuis " + Text.duration(plugin.activity().idleMs(p)), NamedTextColor.GOLD);
        }
        return Text.t("● En ligne", NamedTextColor.GREEN);
    }

    static List<Component> location(FriendsPlugin plugin, OfflinePlayer friend) {
        List<Component> out = new ArrayList<>();
        Player p = friend.getPlayer();
        if (!plugin.friends().allows(friend.getUniqueId(), Setting.SHOW_LOCATION)) {
            out.add(Text.t("✖ Position cachée", NamedTextColor.DARK_GRAY));
        } else if (p == null) {
            out.add(Text.t("Position inconnue (hors ligne)", NamedTextColor.DARK_GRAY));
        } else {
            Location l = p.getLocation();
            out.add(Text.line("Monde :", Text.world(p.getWorld()) + " (" + p.getWorld().getName() + ")"));
            out.add(Text.line("Position :", l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ()));
        }
        return out;
    }

    static Component money(FriendsPlugin plugin, OfflinePlayer friend) {
        VaultHook v = plugin.vault();
        if (v == null || !v.available()) return null;
        if (!plugin.friends().allows(friend.getUniqueId(), Setting.SHOW_MONEY)) {
            return Text.t("✖ Argent caché", NamedTextColor.DARK_GRAY);
        }
        return Text.line("Argent :", Text.money(v.balance(friend), plugin.symbol()));
    }

    static List<Component> stats(FriendsPlugin plugin, OfflinePlayer friend) {
        List<Component> out = new ArrayList<>();
        if (!plugin.friends().allows(friend.getUniqueId(), Setting.SHOW_STATS)) {
            out.add(Text.t("✖ Stats cachées", NamedTextColor.DARK_GRAY));
            return out;
        }
        int kills = stat(friend, Statistic.PLAYER_KILLS);
        int deaths = stat(friend, Statistic.DEATHS);
        int mobs = stat(friend, Statistic.MOB_KILLS);
        long playMs = stat(friend, Statistic.PLAY_ONE_MINUTE) * 50L; // stocké en ticks
        String kd = deaths == 0 ? String.valueOf(kills) : String.format(Locale.ROOT, "%.2f", kills / (double) deaths);
        out.add(Text.line("Kills :", String.valueOf(kills)));
        out.add(Text.line("Morts :", String.valueOf(deaths)));
        out.add(Text.line("K/D :", kd));
        out.add(Text.line("Mobs tués :", String.valueOf(mobs)));
        out.add(Text.line("Temps de jeu :", Text.duration(playMs)));
        return out;
    }

    private static int stat(OfflinePlayer p, Statistic s) {
        try {
            return p.getStatistic(s);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Ce que fait le joueur en ce moment. */
    static List<Component> doing(FriendsPlugin plugin, Player p) {
        List<Component> out = new ArrayList<>();
        ItemStack hand = p.getInventory().getItemInMainHand();
        out.add(Text.line("En main :", hand.getType().isAir() ? "rien" : Text.pretty(hand.getType().name())));
        out.add(Text.line("Mode :", Text.pretty(p.getGameMode().name())));
        out.add(Text.line("Vie :", String.format(Locale.ROOT, "%.0f ❤", p.getHealth())));
        out.add(Text.line("Faim :", p.getFoodLevel() + " / 20"));
        out.add(Text.line("Niveau XP :", String.valueOf(p.getLevel())));
        out.add(Text.line("Ping :", p.getPing() + " ms"));
        return out;
    }

    static OfflinePlayer player(UUID id) {
        Player online = Bukkit.getPlayer(id);
        return online != null ? online : Bukkit.getOfflinePlayer(id);
    }
}
