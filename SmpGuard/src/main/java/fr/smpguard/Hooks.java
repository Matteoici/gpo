package fr.smpguard;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;

/**
 * Lien avec SmpAdmin, sans dépendance de compilation :
 * - les joueurs gelés (/freeze) ou en vanish ne sont pas analysés
 * - au seuil "sus-vl", le joueur est marqué suspect (/sus) : le staff est alerté à chaque connexion
 */
final class Hooks {

    private Hooks() {
    }

    private static Object state() {
        try {
            Plugin p = Bukkit.getPluginManager().getPlugin("SmpAdmin");
            if (p == null || !p.isEnabled()) return null;
            return p.getClass().getMethod("state").invoke(p);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean inSet(String field, UUID id) {
        try {
            Object st = state();
            if (st == null) return false;
            Object set = st.getClass().getField(field).get(st);
            return set instanceof Set<?> s && ((Set<Object>) s).contains(id);
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean isFrozen(Player p) {
        return inSet("frozen", p.getUniqueId());
    }

    static boolean isVanished(Player p) {
        return inSet("vanished", p.getUniqueId());
    }

    static boolean adminPresent() {
        Plugin p = Bukkit.getPluginManager().getPlugin("SmpAdmin");
        return p != null && p.isEnabled();
    }

    /** Les deux joueurs sont-ils dans la même guilde (SmpGuilds) ? */
    static boolean sameGuild(Player a, Player b) {
        try {
            Plugin p = Bukkit.getPluginManager().getPlugin("SmpGuilds");
            if (p == null || !p.isEnabled()) return false;
            Object gm = p.getClass().getMethod("guilds").invoke(p);
            var of = gm.getClass().getMethod("of", UUID.class);
            Object ga = of.invoke(gm, a.getUniqueId());
            return ga != null && ga == of.invoke(gm, b.getUniqueId());
        } catch (Throwable t) {
            return false;
        }
    }

    /** /sus <joueur> <raison> lancé par la console (n'écrase pas un suspect déjà signalé). */
    static void markSuspect(String player, String reason) {
        if (!adminPresent()) return;
        try {
            Object st = state();
            if (st != null) {
                Player p = Bukkit.getPlayerExact(player);
                if (p != null) {
                    Object sus = st.getClass().getMethod("suspect", UUID.class).invoke(st, p.getUniqueId());
                    if (sus != null) return;
                }
            }
        } catch (Throwable ignored) {
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "sus " + player + " " + reason);
    }
}
