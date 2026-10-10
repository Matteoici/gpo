package fr.smpguilds.gui;

import fr.smpguilds.Guild;
import fr.smpguilds.GuildPerm;
import fr.smpguilds.SmpGuilds;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static fr.smpguilds.Messages.ph;

/** Alliés (shift + clic droit = rompre) et demandes d'alliance reçues (clic = accepter). */
public final class AlliesMenu extends Menu {

    public AlliesMenu(SmpGuilds plugin, Player viewer) {
        super(plugin, viewer, 6, plugin.messages().get("gui.allies.title"));
    }

    @Override
    protected void build() {
        Guild g = myGuild();
        if (g == null) {
            viewer.closeInventory();
            return;
        }
        boolean allowed = gm().has(g, viewer.getUniqueId(), GuildPerm.ALLY);
        int max = plugin.settings().i("guild.max-allies", 3);
        set(4, item(Material.SHIELD, "gui.allies.header", ph("count", g.allies.size()), ph("max", max)));
        List<Guild> allies = new ArrayList<>();
        for (UUID id : g.allies) if (gm().get(id) != null) allies.add(gm().get(id));
        for (int i = 0; i < Math.min(7, allies.size()); i++) {
            Guild a = allies.get(i);
            button(10 + i, Menus.icon(plugin, a, "gui.allies.ally-lore"), t -> {
                if (t == ClickType.SHIFT_RIGHT && allowed) {
                    viewer.closeInventory();
                    plugin.actions().unally(viewer, a.name);
                }
            });
        }
        if (allies.isEmpty()) set(13, item(Material.STRUCTURE_VOID, "gui.allies.none"));
        set(22, item(Material.OAK_HANGING_SIGN, "gui.allies.requests-header"));
        List<Guild> requests = gm().allyRequestsTo(g);
        for (int i = 0; i < Math.min(7, requests.size()); i++) {
            Guild req = requests.get(i);
            button(28 + i, Menus.icon(plugin, req, "gui.allies.request-lore"), t -> {
                if (!allowed) return;
                viewer.closeInventory();
                plugin.actions().ally(viewer, g, req);
            });
        }
        if (allowed) {
            button(47, item(Material.LIME_BANNER, "gui.allies.add"),
                    t -> new GuildListMenu(plugin, viewer, GuildListMenu.Mode.ALLY_TARGET, 0).open());
        }
        back(49, () -> new MainMenu(plugin, viewer).open());
        fill(g.color);
    }
}
