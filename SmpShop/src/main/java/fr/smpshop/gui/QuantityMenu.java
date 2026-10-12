package fr.smpshop.gui;

import fr.smpshop.Items;
import fr.smpshop.Shop;
import fr.smpshop.SmpShop;
import fr.smpshop.Texts;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import static fr.smpshop.Texts.ph;

/** Choix de la quantité avant achat. */
public final class QuantityMenu extends Menu {

    private final Shop.Category cat;
    private final Shop.Entry entry;
    private int packs;

    public QuantityMenu(SmpShop plugin, Player viewer, Shop.Category cat, Shop.Entry entry, int packs) {
        super(plugin, viewer, 4, plugin.texts().get("shop.quantity-title"));
        this.cat = cat;
        this.entry = entry;
        this.packs = packs;
    }

    private int max() {
        return entry.isCommand() ? entry.maxQuantity() : Math.max(1, Math.min(entry.maxQuantity(), 2304 / entry.item().getAmount()));
    }

    private void change(int delta) {
        packs = Math.max(1, Math.min(max(), packs + delta));
        redraw();
    }

    @Override
    protected void build() {
        Shop shop = plugin.shop();
        int count = entry.isCommand() ? packs : packs * entry.item().getAmount();
        ItemStack icon = entry.item().clone();
        icon.setAmount(Math.max(1, Math.min(64, count)));
        Component name = entry.name() != null ? Texts.parse(entry.name()) : Component.translatable(entry.item().translationKey());
        set(13, Items.decorate(icon, t().get("shop.quantity-item", Texts.pc("item", name), ph("count", count)),
                t().list("shop.quantity-lore", ph("price", shop.priceText(cat, shop.cost(entry, packs))))));
        int[] deltas = {-64, -16, -1, 1, 16, 64};
        int[] slots = {19, 20, 21, 23, 24, 25};
        for (int i = 0; i < deltas.length; i++) {
            int d = deltas[i];
            ItemStack b = new ItemStack(d < 0 ? Material.RED_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE, Math.abs(d));
            button(slots[i], Items.decorate(b, t().get(d < 0 ? "shop.minus" : "shop.plus", ph("n", Math.abs(d))), null), c -> change(d));
        }
        button(22, item(Material.EMERALD_BLOCK, "shop.confirm", ph("count", count), ph("price", shop.priceText(cat, shop.cost(entry, packs)))),
                c -> {
                    if (shop.buy(viewer, cat, entry, packs)) redraw();
                });
        back(27, () -> new CategoryMenu(plugin, viewer, cat).open());
        close(35);
    }
}
