package fr.smpguilds.gui;

import fr.smpguilds.Guild;
import fr.smpguilds.Items;
import fr.smpguilds.SmpGuilds;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.List;

/** Choix de la couleur du tag et du nom. */
public final class ColorMenu extends Menu {

    public ColorMenu(SmpGuilds plugin, Player viewer) {
        super(plugin, viewer, 4, plugin.messages().get("gui.color.title"));
    }

    @Override
    protected void build() {
        Guild g = myGuild();
        if (g == null) {
            viewer.closeInventory();
            return;
        }
        int slot = 10;
        for (String key : NamedTextColor.NAMES.keys()) {
            NamedTextColor c = NamedTextColor.NAMES.value(key);
            if (slot % 9 == 8) slot += 2;
            var it = Items.of(Menus.wool(c), Component.text("[" + g.tag + "] " + g.name, c),
                    List.of(msg().get(c == g.color ? "gui.color.current" : "gui.color.pick")));
            if (c == g.color) Items.glow(it);
            button(slot++, it, t -> {
                plugin.actions().setColor(viewer, key);
                new SettingsMenu(plugin, viewer).open();
            });
        }
        back(31, () -> new SettingsMenu(plugin, viewer).open());
        fill(g.color);
    }
}
