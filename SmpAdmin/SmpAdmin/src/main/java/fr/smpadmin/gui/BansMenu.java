package fr.smpadmin.gui;

import fr.smpadmin.Items;
import fr.smpadmin.Punishment;
import fr.smpadmin.SmpAdmin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;

import static fr.smpadmin.Texts.ph;

/** Bannissements actifs : clic droit pour débannir. */
public final class BansMenu extends Menu {

    private int page;

    public BansMenu(SmpAdmin plugin, Player viewer, int page) {
        super(plugin, viewer, 6, plugin.texts().get("gui.bans.title", ph("count", plugin.sanctions().activeBans().size())));
        this.page = page;
    }

    @Override
    protected void build() {
        List<Punishment> bans = plugin.sanctions().activeBans();
        int[] slots = MainMenu.SLOTS;
        int pages = pageCount(bans.size(), slots.length);
        page = Math.min(page, pages - 1);
        if (bans.isEmpty()) set(22, item(Material.LIME_DYE, "gui.bans.empty"));
        for (int i = 0; i < slots.length; i++) {
            int idx = page * slots.length + i;
            if (idx >= bans.size()) break;
            Punishment b = bans.get(idx);
            OfflinePlayer target = Bukkit.getOfflinePlayer(b.target);
            var r = plugin.actions().resolvers(b);
            button(slots[i], Items.head(target, t().get("gui.bans.item.name", r), t().list("gui.bans.item.lore", r)), c -> {
                if (c.isRightClick()) {
                    if (!viewer.hasPermission("smpadmin.unban")) return;
                    plugin.actions().unban(viewer, target);
                    redraw();
                } else {
                    new PlayerMenu(plugin, viewer, target).open();
                }
            });
        }
        pages(page, pages, p -> new BansMenu(plugin, viewer, p).open());
        back(45, () -> new MainMenu(plugin, viewer, 0).open());
        close(49);
    }
}
