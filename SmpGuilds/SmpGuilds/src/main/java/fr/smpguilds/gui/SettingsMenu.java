package fr.smpguilds.gui;

import fr.smpguilds.Guild;
import fr.smpguilds.GuildActions;
import fr.smpguilds.Items;
import fr.smpguilds.Messages;
import fr.smpguilds.SmpGuilds;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import static fr.smpguilds.Messages.guild;
import static fr.smpguilds.Messages.ph;

/** Personnalisation : description, tag, nom, couleur, bannière, recrutement, home. */
public final class SettingsMenu extends Menu {

    public SettingsMenu(SmpGuilds plugin, Player viewer) {
        super(plugin, viewer, 4, plugin.messages().get("gui.settings.title"));
    }

    @Override
    protected void build() {
        Guild g = myGuild();
        if (g == null) {
            viewer.closeInventory();
            return;
        }
        GuildActions a = plugin.actions();
        var r = Menus.resolvers(plugin, g);
        button(10, item(Material.WRITABLE_BOOK, "gui.settings.description", r), t ->
                plugin.input().ask(viewer, "input.description", s -> a.setDescription(viewer, s)));
        button(11, item(Material.NAME_TAG, "gui.settings.tag", r), t ->
                plugin.input().ask(viewer, "input.tag", s -> a.setTag(viewer, s)));
        button(12, item(Material.ANVIL, "gui.settings.rename", r,
                ph("cost", Messages.money(plugin.settings().d("guild.rename-cost", 0)))), t ->
                plugin.input().ask(viewer, "input.rename", s -> a.rename(viewer, s)));
        button(13, item(Menus.wool(g.color), "gui.settings.color", r), t -> new ColorMenu(plugin, viewer).open());
        ItemStack banner = g.banner != null ? g.banner : new ItemStack(Material.WHITE_BANNER);
        button(14, Items.decorate(banner, msg().get("gui.settings.banner.name", r), msg().list("gui.settings.banner.lore", r)), t -> {
            a.setBanner(viewer);
            redraw();
        });
        button(15, item(g.open ? Material.LIME_DYE : Material.GRAY_DYE, "gui.settings.open", r), t -> {
            a.toggleOpen(viewer);
            redraw();
        });
        button(16, item(Material.RESPAWN_ANCHOR, "gui.settings.sethome", r), t -> {
            viewer.closeInventory();
            a.setHome(viewer);
        });
        button(22, item(Material.MAP, "gui.settings.map", r), t -> {
            viewer.closeInventory();
            a.map(viewer);
        });
        button(21, item(Material.GOLDEN_SHOVEL, "gui.settings.autoclaim", r), t -> {
            viewer.closeInventory();
            boolean on = plugin.territory().toggleAutoClaim(viewer);
            msg().send(viewer, on ? "claims.autoclaim-on" : "claims.autoclaim-off");
        });
        button(23, item(Material.TNT, "gui.settings.unclaimall", r), t ->
                new ConfirmMenu(plugin, viewer, "gui.confirm.unclaimall", () -> a.unclaimAll(viewer),
                        () -> new SettingsMenu(plugin, viewer).open(), guild(g)).open());
        back(31, () -> new MainMenu(plugin, viewer).open());
        fill(g.color);
    }
}
