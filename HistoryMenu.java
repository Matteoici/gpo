package fr.smpadmin.gui;

import fr.smpadmin.Items;
import fr.smpadmin.Punishment;
import fr.smpadmin.SmpAdmin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static fr.smpadmin.Texts.pc;
import static fr.smpadmin.Texts.ph;
import static fr.smpadmin.Texts.pp;

/** Casier d'un joueur : toutes ses sanctions. Clic droit sur une sanction active pour la lever. */
public final class HistoryMenu extends Menu {

    private final OfflinePlayer target;
    private int page;

    public HistoryMenu(SmpAdmin plugin, Player viewer, OfflinePlayer target, int page) {
        super(plugin, viewer, 6, plugin.texts().get("gui.history.title", ph("player", PlayerMenu.nameOf(target))));
        this.target = target;
        this.page = page;
    }

    private static Material icon(Punishment p) {
        return switch (p.type) {
            case BAN -> Material.NETHERITE_AXE;
            case MUTE -> Material.STRING;
            case KICK -> Material.OAK_DOOR;
            case WARN -> Material.PAPER;
        };
    }

    @Override
    protected void build() {
        List<Punishment> list = plugin.sanctions().history(target.getUniqueId());
        int[] slots = MainMenu.SLOTS;
        int pages = pageCount(list.size(), slots.length);
        page = Math.min(page, pages - 1);
        if (list.isEmpty()) set(22, item(Material.LIME_DYE, "gui.history.empty"));
        long now = System.currentTimeMillis();
        for (int i = 0; i < slots.length; i++) {
            int idx = page * slots.length + i;
            if (idx >= list.size()) break;
            Punishment p = list.get(idx);
            boolean active = p.isActive(now);
            TagResolver[] r = {plugin.actions().resolvers(p),
                    pp("type", t().raw("types." + p.type.name().toLowerCase(Locale.ROOT))), pc("status", plugin.status(p))};
            List<Component> lore = new ArrayList<>(t().list("gui.history.item.lore", r));
            if (active && canRevoke(p)) {
                lore.add(Component.empty());
                lore.add(t().get("gui.history.revoke"));
            }
            ItemStack it = Items.of(icon(p), t().get("gui.history.item.name", r), lore);
            if (active) Items.glow(it);
            button(slots[i], it, c -> {
                if (c.isRightClick() && p.isActive(System.currentTimeMillis()) && canRevoke(p)) {
                    plugin.actions().revoke(viewer, p);
                    redraw();
                }
            });
        }
        pages(page, pages, p -> new HistoryMenu(plugin, viewer, target, p).open());
        back(45, () -> new PlayerMenu(plugin, viewer, target).open());
        close(49);
    }

    private boolean canRevoke(Punishment p) {
        return p.type == Punishment.Type.BAN ? viewer.hasPermission("smpadmin.unban") : viewer.hasPermission("smpadmin.mute");
    }
}
