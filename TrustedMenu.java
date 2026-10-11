package fr.smpmarket.gui;

import fr.smpmarket.Items;
import fr.smpmarket.SmpMarket;
import fr.smpmarket.Stall;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static fr.smpmarket.Texts.ph;

/** Joueurs autorisés à construire dans le stand. */
public final class TrustedMenu extends Menu {

    private final Stall stall;

    public TrustedMenu(SmpMarket plugin, Player viewer, Stall stall) {
        super(plugin, viewer, 5, plugin.texts().get("gui.trusted.title"));
        this.stall = stall;
    }

    @Override
    protected void build() {
        if (!stall.isOwner(viewer.getUniqueId())) {
            viewer.closeInventory();
            return;
        }
        List<Map.Entry<UUID, String>> list = new ArrayList<>(stall.trusted.entrySet());
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        for (int i = 0; i < Math.min(list.size(), slots.length); i++) {
            Map.Entry<UUID, String> e = list.get(i);
            button(slots[i], Items.head(Bukkit.getOfflinePlayer(e.getKey()), t().get("gui.trusted.player.name", ph("player", e.getValue())),
                    t().list("gui.trusted.player.lore")), c -> {
                stall.trusted.remove(e.getKey());
                plugin.store().markDirty();
                t().send(viewer, "stall.untrusted", ph("player", e.getValue()));
                redraw();
            });
        }
        button(31, item(Material.LIME_DYE, "gui.trusted.add"), c -> plugin.input().ask(viewer, "input.trust", name -> {
            OfflinePlayer o = Bukkit.getOfflinePlayerIfCached(name);
            if (o == null || o.getName() == null) t().send(viewer, "error.unknown-player", ph("player", name));
            else {
                stall.trusted.put(o.getUniqueId(), o.getName());
                plugin.store().markDirty();
                t().send(viewer, "stall.trusted", ph("player", o.getName()));
            }
            open();
        }));
        back(36, () -> new StallMenu(plugin, viewer, stall).open());
        close(44);
    }
}
