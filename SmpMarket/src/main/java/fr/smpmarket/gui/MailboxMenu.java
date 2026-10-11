package fr.smpmarket.gui;

import fr.smpmarket.Items;
import fr.smpmarket.SmpMarket;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

import static fr.smpmarket.Texts.ph;

/** Colis : objets rendus (fin de location, vitrine supprimée avec un inventaire plein...). */
public final class MailboxMenu extends Menu {

    private static final int SHOWN = 45;

    public MailboxMenu(SmpMarket plugin, Player viewer) {
        super(plugin, viewer, 6, plugin.texts().get("gui.mailbox.title",
                ph("count", plugin.store().parcels(viewer.getUniqueId()).size())));
    }

    @Override
    protected void build() {
        List<ItemStack> parcels = plugin.store().parcels(viewer.getUniqueId());
        if (parcels.isEmpty()) set(22, item(Material.GRAY_DYE, "gui.mailbox.empty"));
        for (int i = 0; i < Math.min(parcels.size(), SHOWN); i++) {
            ItemStack stored = parcels.get(i);
            ItemStack shown = stored.clone();
            ItemMeta meta = shown.getItemMeta();
            if (meta != null) {
                List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                lore.addAll(t().list("gui.mailbox.item-lore"));
                meta.lore(lore);
                shown.setItemMeta(meta);
            }
            button(i, shown, c -> {
                if (take(stored)) redraw();
            });
        }
        if (!parcels.isEmpty()) {
            button(49, Items.glow(item(Material.HOPPER, "gui.mailbox.take-all")), c -> {
                for (ItemStack it : new ArrayList<>(parcels)) if (!take(it)) break;
                redraw();
            });
        }
        back(45, () -> new MainMenu(plugin, viewer).open());
        close(53);
    }

    /** Donne l'objet s'il y a la place. */
    private boolean take(ItemStack stored) {
        List<ItemStack> parcels = plugin.store().parcels(viewer.getUniqueId());
        if (!parcels.contains(stored)) return false;
        var left = viewer.getInventory().addItem(stored.clone());
        parcels.remove(stored);
        if (!left.isEmpty()) {
            // pas assez de place : on remet ce qui reste dans le colis
            for (ItemStack l : left.values()) parcels.add(l);
            t().send(viewer, "parcel.no-space");
            plugin.store().markDirty();
            return false;
        }
        plugin.store().markDirty();
        return true;
    }
}
