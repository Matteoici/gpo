package fr.smpguilds.gui;

import fr.smpguilds.Guild;
import fr.smpguilds.Messages;
import fr.smpguilds.SmpGuilds;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

import static fr.smpguilds.Messages.ph;

/** Menu pour les joueurs sans guilde : créer, parcourir, invitations. */
public final class NoGuildMenu extends Menu {

    public NoGuildMenu(SmpGuilds plugin, Player viewer) {
        super(plugin, viewer, 3, plugin.messages().get("gui.noguild.title"));
    }

    @Override
    protected void build() {
        double cost = plugin.settings().d("guild.create-cost", 0);
        button(11, item(Material.NETHER_STAR, "gui.noguild.create", ph("cost", Messages.money(cost))), t ->
                plugin.input().ask(viewer, "input.guild-name", name ->
                        plugin.input().ask(viewer, "input.guild-tag", tag -> plugin.actions().create(viewer, name, tag))));
        button(13, item(Material.COMPASS, "gui.noguild.browse", ph("count", gm().all().size())),
                t -> new GuildListMenu(plugin, viewer, GuildListMenu.Mode.BROWSE, 0).open());
        List<Guild> invites = gm().invitesOf(viewer.getUniqueId());
        button(15, item(invites.isEmpty() ? Material.PAPER : Material.MAP, "gui.noguild.invites", ph("count", invites.size())),
                t -> new GuildListMenu(plugin, viewer, GuildListMenu.Mode.INVITES, 0).open());
        button(22, item(Material.GOLD_BLOCK, "gui.noguild.top"),
                t -> new GuildListMenu(plugin, viewer, GuildListMenu.Mode.TOP, 0).open());
        fill(NamedTextColor.GOLD);
    }
}
