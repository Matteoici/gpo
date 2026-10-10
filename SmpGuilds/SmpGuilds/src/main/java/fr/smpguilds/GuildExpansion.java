package fr.smpguilds;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * Placeholders pour TAB, scoreboards, hologrammes, ajLeaderboards...
 * Classe chargée uniquement si PlaceholderAPI est installé.
 *
 * %smpguilds_name%  %smpguilds_tag%  %smpguilds_tag_formatted%  %smpguilds_role%  %smpguilds_level%
 * %smpguilds_xp%  %smpguilds_members%  %smpguilds_online%  %smpguilds_claims%  %smpguilds_bank%
 * %smpguilds_kills%  %smpguilds_deaths%  %smpguilds_kdr%  %smpguilds_wars_won%  %smpguilds_wars_lost%
 * %smpguilds_allies%  %smpguilds_rank%  %smpguilds_territory%  %smpguilds_at_war%
 * %smpguilds_top_<n>_name%  %smpguilds_top_<n>_tag%  %smpguilds_top_<n>_level%  %smpguilds_top_<n>_xp%
 */
public final class GuildExpansion extends PlaceholderExpansion {

    private final SmpGuilds plugin;

    private GuildExpansion(SmpGuilds plugin) {
        this.plugin = plugin;
    }

    static void hook(SmpGuilds plugin) {
        new GuildExpansion(plugin).register();
    }

    @Override
    public String getIdentifier() {
        return "smpguilds";
    }

    @Override
    public String getAuthor() {
        return "SmpGuilds";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    private static String legacy(Component c) {
        return LegacyComponentSerializer.legacySection().serialize(c);
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String p = params.toLowerCase(Locale.ROOT);
        GuildManager gm = plugin.guilds();

        if (p.startsWith("top_")) {
            String[] parts = p.split("_", 3);
            if (parts.length < 3) return "";
            int n;
            try {
                n = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                return "";
            }
            List<Guild> top = gm.top();
            if (n < 1 || n > top.size()) return parts[2].equals("name") ? "-" : "";
            Guild g = top.get(n - 1);
            return switch (parts[2]) {
                case "name" -> g.name;
                case "tag" -> g.tag;
                case "level" -> String.valueOf(gm.level(g).level());
                case "xp" -> String.valueOf(g.xp);
                case "members" -> String.valueOf(g.members.size());
                default -> "";
            };
        }

        if (player == null) return "";
        if (p.equals("territory")) {
            Player online = player.getPlayer();
            if (online == null) return "";
            Guild owner = gm.at(online.getLocation());
            return owner == null ? legacy(plugin.messages().get("placeholder.wilderness")) : owner.name;
        }

        Guild g = gm.of(player.getUniqueId());
        if (g == null) {
            return switch (p) {
                case "name" -> legacy(plugin.messages().get("placeholder.no-guild"));
                case "tag_formatted", "tag", "role" -> "";
                case "has_guild", "at_war" -> "false";
                default -> "0";
            };
        }
        return switch (p) {
            case "has_guild" -> "true";
            case "name" -> g.name;
            case "name_formatted" -> legacy(g.nameComponent());
            case "tag" -> g.tag;
            case "tag_formatted" -> legacy(g.tagComponent()) + " ";
            case "color" -> legacy(Component.text("", g.color));
            case "role" -> legacy(plugin.messages().get("role." + g.role(player.getUniqueId()).key()));
            case "leader" -> g.nameOf(g.leader);
            case "description" -> g.description;
            case "level" -> String.valueOf(gm.level(g).level());
            case "xp" -> String.valueOf(g.xp);
            case "members" -> String.valueOf(g.members.size());
            case "max_members" -> String.valueOf(gm.level(g).maxMembers());
            case "online" -> String.valueOf(g.online().size());
            case "claims" -> String.valueOf(g.claims.size());
            case "max_claims" -> String.valueOf(gm.level(g).maxClaims());
            case "bank" -> Messages.money(g.bank);
            case "bank_raw" -> String.valueOf((long) g.bank);
            case "kills" -> String.valueOf(g.kills);
            case "deaths" -> String.valueOf(g.deaths);
            case "kdr" -> String.valueOf(g.kdr());
            case "wars_won" -> String.valueOf(g.warsWon);
            case "wars_lost" -> String.valueOf(g.warsLost);
            case "allies" -> String.valueOf(g.allies.size());
            case "rank" -> String.valueOf(gm.top().indexOf(g) + 1);
            case "at_war" -> String.valueOf(plugin.wars().warOf(g) != null);
            case "member_kills" -> String.valueOf(g.stats(player.getUniqueId()).kills);
            case "member_deaths" -> String.valueOf(g.stats(player.getUniqueId()).deaths);
            default -> null;
        };
    }
}
