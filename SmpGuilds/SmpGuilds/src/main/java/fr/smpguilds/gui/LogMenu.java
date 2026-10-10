package fr.smpguilds.gui;

import fr.smpguilds.Guild;
import fr.smpguilds.Items;
import fr.smpguilds.SmpGuilds;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Journal de la guilde (dernières actions). */
public final class LogMenu extends Menu {

    private static final int PER_PAGE = 36;
    private final int page;

    public LogMenu(SmpGuilds plugin, Player viewer, int page) {
        super(plugin, viewer, 6, plugin.messages().get("gui.log.title"));
        this.page = page;
    }

    @Override
    protected void build() {
        Guild g = myGuild();
        if (g == null) {
            viewer.closeInventory();
            return;
        }
        List<String> log = g.log;
        int from = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && from + i < log.size(); i++) {
            String entry = log.get(from + i);
            String[] parts = entry.split(" \\| ", 2);
            Component name = Component.text(parts.length > 1 ? parts[1] : entry, NamedTextColor.WHITE)
                    .decoration(TextDecoration.ITALIC, false);
            List<Component> lore = parts.length > 1
                    ? List.of(Component.text(parts[0], NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)) : List.of();
            set(9 + i, Items.of(Material.PAPER, name, lore));
        }
        if (log.isEmpty()) set(22, item(Material.STRUCTURE_VOID, "gui.log.empty"));
        back(49, () -> new MainMenu(plugin, viewer).open());
        pages(page, pageCount(log, PER_PAGE), p -> new LogMenu(plugin, viewer, p).open());
        fill(g.color);
    }
}
