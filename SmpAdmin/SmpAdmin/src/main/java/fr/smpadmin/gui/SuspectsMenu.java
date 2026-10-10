package fr.smpadmin.gui;

import fr.smpadmin.Items;
import fr.smpadmin.SmpAdmin;
import fr.smpadmin.StaffState;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;

import static fr.smpadmin.Texts.pc;
import static fr.smpadmin.Texts.ph;

/** Liste des suspects : fiche, observation, retrait. */
public final class SuspectsMenu extends Menu {

    private int page;

    public SuspectsMenu(SmpAdmin plugin, Player viewer, int page) {
        super(plugin, viewer, 6, plugin.texts().get("gui.suspects.title", ph("count", plugin.state().suspects().size())));
        this.page = page;
    }

    @Override
    protected void build() {
        List<StaffState.Suspect> list = plugin.state().suspects();
        int[] slots = MainMenu.SLOTS;
        int pages = pageCount(list.size(), slots.length);
        page = Math.min(page, pages - 1);
        if (list.isEmpty()) set(22, item(Material.LIME_DYE, "gui.suspects.empty"));
        for (int i = 0; i < slots.length; i++) {
            int idx = page * slots.length + i;
            if (idx >= list.size()) break;
            StaffState.Suspect s = list.get(idx);
            OfflinePlayer target = Bukkit.getOfflinePlayer(s.id());
            Player online = target.getPlayer();
            TagResolver[] r = {ph("player", s.name()), ph("reason", s.reason()), ph("staff", s.staff()),
                    ph("date", plugin.actions().date(s.date())),
                    pc("status", t().get(online != null ? "gui.player.online" : "gui.player.offline"))};
            button(slots[i], Items.head(target, t().get("gui.suspects.item.name", r), t().list("gui.suspects.item.lore", r)), c -> {
                if (c.isRightClick()) {
                    plugin.actions().unsus(viewer, target);
                    redraw();
                } else if (c.isShiftClick() && online != null && online.isOnline()) {
                    viewer.closeInventory();
                    plugin.spectate().watch(viewer, online);
                } else {
                    new PlayerMenu(plugin, viewer, target).open();
                }
            });
        }
        pages(page, pages, p -> new SuspectsMenu(plugin, viewer, p).open());
        back(45, () -> new MainMenu(plugin, viewer, 0).open());
        close(49);
    }
}
