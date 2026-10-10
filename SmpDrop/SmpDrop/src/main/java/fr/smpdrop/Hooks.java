package fr.smpdrop;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * Liens optionnels avec les autres plugins du serveur, sans dépendance de compilation :
 * - SmpGuilds : ne pas larguer dans un territoire de guilde
 * - SmpStart  : un joueur sous protection débutant (intouchable) ne peut pas ouvrir la caisse
 * Si un plugin est absent ou change, la vérification est simplement ignorée.
 */
final class Hooks {

    private Hooks() {
    }

    static boolean isClaimed(Location loc) {
        try {
            Plugin p = Bukkit.getPluginManager().getPlugin("SmpGuilds");
            if (p == null || !p.isEnabled()) return false;
            Object manager = p.getClass().getMethod("guilds").invoke(p);
            Object guild = manager.getClass().getMethod("at", Location.class).invoke(manager, loc);
            return guild != null;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean isNewbieProtected(Player player) {
        try {
            Plugin p = Bukkit.getPluginManager().getPlugin("SmpStart");
            if (p == null || !p.isEnabled()) return false;
            Object data = p.getClass().getMethod("data").invoke(p);
            Object pd = data.getClass().getMethod("get", UUID.class).invoke(data, player.getUniqueId());
            if (pd == null) return false;
            return pd.getClass().getField("protectionSec").getInt(pd) > 0;
        } catch (Throwable t) {
            return false;
        }
    }
}
