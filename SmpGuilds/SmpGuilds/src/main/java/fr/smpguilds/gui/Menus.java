package fr.smpguilds.gui;

import fr.smpguilds.Guild;
import fr.smpguilds.Items;
import fr.smpguilds.Level;
import fr.smpguilds.Messages;
import fr.smpguilds.SmpGuilds;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static fr.smpguilds.Messages.guild;
import static fr.smpguilds.Messages.money;
import static fr.smpguilds.Messages.ph;

/** Ouverture des menus + éléments communs (bannière de guilde, couleurs). */
public final class Menus {

    private static final Map<NamedTextColor, String> DYES = Map.ofEntries(
            Map.entry(NamedTextColor.BLACK, "BLACK"), Map.entry(NamedTextColor.DARK_BLUE, "BLUE"),
            Map.entry(NamedTextColor.DARK_GREEN, "GREEN"), Map.entry(NamedTextColor.DARK_AQUA, "CYAN"),
            Map.entry(NamedTextColor.DARK_RED, "RED"), Map.entry(NamedTextColor.DARK_PURPLE, "PURPLE"),
            Map.entry(NamedTextColor.GOLD, "ORANGE"), Map.entry(NamedTextColor.GRAY, "LIGHT_GRAY"),
            Map.entry(NamedTextColor.DARK_GRAY, "GRAY"), Map.entry(NamedTextColor.BLUE, "BLUE"),
            Map.entry(NamedTextColor.GREEN, "LIME"), Map.entry(NamedTextColor.AQUA, "LIGHT_BLUE"),
            Map.entry(NamedTextColor.RED, "RED"), Map.entry(NamedTextColor.LIGHT_PURPLE, "MAGENTA"),
            Map.entry(NamedTextColor.YELLOW, "YELLOW"), Map.entry(NamedTextColor.WHITE, "WHITE"));

    private Menus() {
    }

    public static void openMain(SmpGuilds plugin, Player p) {
        if (plugin.guilds().of(p) == null) new NoGuildMenu(plugin, p).open();
        else new MainMenu(plugin, p).open();
    }

    public static Material glass(NamedTextColor c) {
        return Material.valueOf(DYES.getOrDefault(c, "BLACK") + "_STAINED_GLASS_PANE");
    }

    public static Material wool(NamedTextColor c) {
        return Material.valueOf(DYES.getOrDefault(c, "WHITE") + "_WOOL");
    }

    /** Tous les placeholders d'une guilde (pour les textes de menus). */
    public static TagResolver resolvers(SmpGuilds plugin, Guild g) {
        Level lvl = plugin.guilds().level(g);
        Level next = plugin.settings().nextLevel(lvl);
        return TagResolver.resolver(guild(g),
                ph("description", g.description.isEmpty() ? "-" : g.description),
                ph("leader", g.nameOf(g.leader)),
                ph("level", lvl.level()),
                ph("xp", g.xp),
                ph("next", next == null ? "MAX" : String.valueOf(next.xp())),
                Messages.comp("progress", progressBar(g.xp, lvl.xp(), next == null ? lvl.xp() : next.xp())),
                ph("members", g.members.size()),
                ph("max_members", lvl.maxMembers()),
                ph("online", g.online().size()),
                ph("claims", g.claims.size()),
                ph("max_claims", lvl.maxClaims()),
                ph("bank", money(g.bank)),
                ph("kills", g.kills),
                ph("deaths", g.deaths),
                ph("kdr", g.kdr()),
                ph("wars_won", g.warsWon),
                ph("wars_lost", g.warsLost),
                ph("allies", g.allies.size()),
                ph("applications", g.applications.size()),
                Messages.comp("status", plugin.messages().get(g.open ? "info.open" : "info.closed")));
    }

    public static Component progressBar(long xp, long from, long to) {
        int total = 20;
        int filled = to <= from ? total : (int) Math.max(0, Math.min(total, (xp - from) * total / (to - from)));
        return Component.text("|".repeat(filled), NamedTextColor.GREEN)
                .append(Component.text("|".repeat(total - filled), NamedTextColor.DARK_GRAY));
    }

    /** Icône d'une guilde : sa bannière (ou une bannière blanche) avec ses infos. */
    public static ItemStack icon(SmpGuilds plugin, Guild g, String loreKey) {
        ItemStack base = g.banner != null ? g.banner : new ItemStack(Material.WHITE_BANNER);
        TagResolver r = resolvers(plugin, g);
        List<Component> lore = new ArrayList<>(plugin.messages().list(loreKey, r));
        return Items.decorate(base, plugin.messages().get("gui.common.guild-name", r), lore);
    }
}
