package fr.smpmarket.gui;

import fr.smpmarket.Items;
import fr.smpmarket.Shops;
import fr.smpmarket.Showcase;
import fr.smpmarket.SmpMarket;
import fr.smpmarket.Stall;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import static fr.smpmarket.Texts.ph;

/** Achat dans une vitrine : choix de la quantité. */
public final class BuyMenu extends Menu {

    private static final int[] AMOUNTS = {1, 4, 8, 16, 32, 64};
    private static final int[] SLOTS = {28, 29, 30, 31, 32, 33};

    private final Stall stall;
    private final Showcase showcase;
    private final Runnable back;

    public BuyMenu(SmpMarket plugin, Player viewer, Stall stall, Showcase showcase, Runnable back) {
        super(plugin, viewer, 5, plugin.texts().get("gui.buy.title"));
        this.stall = stall;
        this.showcase = showcase;
        this.back = back;
    }

    /** Quantité maximale achetable : stock, argent, place. */
    private int max() {
        double balance = plugin.economy().balance(viewer);
        int byMoney = showcase.price <= 0 ? Integer.MAX_VALUE : (int) Math.floor(balance / showcase.price);
        int bySpace = Shops.space(viewer.getInventory(), showcase.template);
        return Math.max(0, Math.min(showcase.stock, Math.min(byMoney, bySpace)));
    }

    @Override
    protected void build() {
        if (!stall.isRented() || !stall.showcases.contains(showcase)) {
            viewer.closeInventory();
            return;
        }
        set(13, CatalogMenu.display(this, showcase, "gui.buy.info-lore", ph("price", money(showcase.price)),
                ph("stock", showcase.stock), ph("owner", stall.ownerName), ph("balance", money(plugin.economy().balance(viewer)))));

        int max = max();
        for (int i = 0; i < AMOUNTS.length; i++) {
            final int qty = AMOUNTS[i];
            boolean ok = qty <= max;
            TagResolver[] r = {ph("count", qty), ph("total", money(showcase.price * qty))};
            ItemStack icon = ok ? amountIcon(qty) : item(Material.GRAY_STAINED_GLASS_PANE, "gui.buy.amount", r);
            if (ok) icon = Items.of(icon.getType(), t().get("gui.buy.amount.name", r), t().list("gui.buy.amount.lore", r));
            if (ok) icon.setAmount(Math.min(qty, 64));
            button(SLOTS[i], icon, c -> {
                if (plugin.shops().buy(viewer, stall, showcase, qty)) redraw();
            });
        }
        if (max > 0) {
            TagResolver[] r = {ph("count", max), ph("total", money(showcase.price * max))};
            button(34, Items.glow(item(Material.EMERALD_BLOCK, "gui.buy.amount-max", r)), c -> {
                int m = max();
                if (m > 0 && plugin.shops().buy(viewer, stall, showcase, m)) redraw();
            });
        } else {
            set(34, item(Material.BARRIER, "gui.buy.cant"));
        }
        if (back != null) back(36, back);
        close(40);
    }

    private ItemStack amountIcon(int qty) {
        return new ItemStack(qty >= 32 ? Material.GOLD_BLOCK : qty >= 8 ? Material.GOLD_INGOT : Material.GOLD_NUGGET);
    }
}
