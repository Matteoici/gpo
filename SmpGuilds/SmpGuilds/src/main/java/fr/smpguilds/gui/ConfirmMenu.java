package fr.smpguilds.gui;

import fr.smpguilds.SmpGuilds;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Petite fenêtre « Confirmer / Annuler ». */
public final class ConfirmMenu extends Menu {

    private final String key;
    private final Runnable yes;
    private final Runnable no;
    private final TagResolver[] r;

    public ConfirmMenu(SmpGuilds plugin, Player viewer, String key, Runnable yes, Runnable no, TagResolver... r) {
        super(plugin, viewer, 3, plugin.messages().get(key + ".title", r));
        this.key = key;
        this.yes = yes;
        this.no = no;
        this.r = r;
    }

    @Override
    protected void build() {
        set(13, item(Material.PAPER, key + ".info", r));
        button(11, item(Material.LIME_CONCRETE, "gui.confirm.yes", r), t -> {
            viewer.closeInventory();
            yes.run();
        });
        button(15, item(Material.RED_CONCRETE, "gui.confirm.no", r), t -> no.run());
        fill(NamedTextColor.DARK_GRAY);
    }
}
