package fr.smpdrop;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;

/**
 * %smpdrop_next%      temps avant le prochain largage (ex. 1h42 ou 4:05)
 * %smpdrop_status%    aucun / annonce / chute / au sol / pillage
 * %smpdrop_x% / _z%   position du largage en cours (vide sinon)
 * %smpdrop_opened%    nombre de caisses ouvertes par le joueur
 */
final class DropExpansion extends PlaceholderExpansion {

    private final SmpDrop plugin;

    DropExpansion(SmpDrop plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "smpdrop";
    }

    @Override
    public String getAuthor() {
        return "SmpDrop";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        DropEvent ev = plugin.manager().current();
        Location c = ev == null ? null : ev.center();
        switch (params.toLowerCase()) {
            case "next":
                return ev != null ? "en cours" : DropEvent.time(plugin.manager().nextDrop() - System.currentTimeMillis());
            case "status":
                if (ev == null) return "aucun";
                return switch (ev.phase()) {
                    case SEARCHING, ANNOUNCED -> "annonce";
                    case FALLING -> "chute";
                    case LANDED -> "au sol";
                    case LOOTING -> "pillage";
                    case ENDED -> "aucun";
                };
            case "x":
                return c == null ? "" : String.valueOf(c.getBlockX());
            case "z":
                return c == null ? "" : String.valueOf(c.getBlockZ());
            case "opened":
                return player == null ? "0" : String.valueOf(plugin.manager().wins(player.getUniqueId()));
            default:
                return null;
        }
    }
}
