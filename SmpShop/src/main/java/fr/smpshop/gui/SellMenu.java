package fr.smpshop.gui;

import fr.smpshop.Items;
import fr.smpshop.Seller;
import fr.smpshop.SmpShop;
import fr.smpshop.Texts;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

import static fr.smpshop.Texts.ph;

/**
 * /sell : on dépose ses objets dans les 5 premières lignes, la dernière ligne affiche le total.
 * À la fermeture, tout ce qui se vend est vendu, le reste est rendu.
 */
public final class SellMenu implements InventoryHolder {

    public static final int AREA = 45;
    private static final int INFO = 49, SELL = 51, CANCEL = 47;

    private final SmpShop plugin;
    private final Player viewer;
    private final Inventory inv;
    private boolean done;

    public SellMenu(SmpShop plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.inv = Bukkit.createInventory(this, 54, plugin.texts().get("sell.title"));
        for (int i = AREA; i < 54; i++) inv.setItem(i, tag(Items.filler(Material.BLACK_STAINED_GLASS_PANE)));
        refresh();
    }

    public void open() {
        viewer.openInventory(inv);
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }

    private ItemStack tag(ItemStack it) {
        var meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(plugin.menuKey(), PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    /** Recalcule le total affiché. */
    public void refresh() {
        Texts t = plugin.texts();
        List<ItemStack> items = new ArrayList<>();
        int unsellable = 0;
        for (int i = 0; i < AREA; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            items.add(it);
            if (plugin.worth().unitPrice(it) <= 0) unsellable += it.getAmount();
        }
        double total = plugin.seller().value(viewer, items);
        double mult = plugin.worth().multiplier(viewer);
        inv.setItem(INFO, tag(Items.of(Material.GOLD_INGOT, t.get("sell.info.name", ph("money", plugin.settings().money(total))),
                t.list(mult > 1 ? "sell.info.lore-bonus" : "sell.info.lore", ph("money", plugin.settings().money(total)),
                        ph("unsellable", unsellable), ph("bonus", Math.round((mult - 1) * 100))))));
        inv.setItem(SELL, tag(Items.of(Material.LIME_CONCRETE, t.get("sell.confirm.name", ph("money", plugin.settings().money(total))),
                t.list("sell.confirm.lore"))));
        inv.setItem(CANCEL, tag(Items.of(Material.BARRIER, t.get("sell.cancel.name"), t.list("sell.cancel.lore"))));
    }

    /** Clic sur la dernière ligne. */
    public void clickBottom(int slot) {
        if (slot == SELL) viewer.closeInventory(); // la vente se fait à la fermeture
        else if (slot == CANCEL) {
            giveBackAll();
            viewer.closeInventory();
        }
    }

    private void giveBackAll() {
        List<ItemStack> back = new ArrayList<>();
        for (int i = 0; i < AREA; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && !it.getType().isAir()) back.add(it);
            inv.setItem(i, null);
        }
        give(back);
        done = true;
    }

    /** Fermeture (ou déconnexion) : on vend ce qui se vend, on rend le reste. */
    public void close() {
        if (done) return;
        done = true;
        Seller.Result r = plugin.seller().sellSlots(viewer, inv, 0, AREA);
        List<ItemStack> back = new ArrayList<>();
        for (int i = 0; i < AREA; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && !it.getType().isAir()) back.add(it);
            inv.setItem(i, null);
        }
        give(back);
        if (r.items() > 0) plugin.seller().report(viewer, r);
        if (!back.isEmpty()) plugin.texts().send(viewer, "sell.returned", ph("count", back.stream().mapToInt(ItemStack::getAmount).sum()));
    }

    private void give(List<ItemStack> items) {
        for (ItemStack it : items) {
            for (ItemStack rest : viewer.getInventory().addItem(it).values()) {
                viewer.getWorld().dropItemNaturally(viewer.getLocation(), rest);
            }
        }
    }
}
