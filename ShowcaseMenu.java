package fr.smpmarket.gui;

import fr.smpmarket.MarketCommand;
import fr.smpmarket.Shops;
import fr.smpmarket.Showcase;
import fr.smpmarket.SmpMarket;
import fr.smpmarket.Stall;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import static fr.smpmarket.Texts.ph;

/** Gestion d'une vitrine : stock, prix, suppression. */
public final class ShowcaseMenu extends Menu {

    private final Stall stall;
    private final Showcase showcase;

    public ShowcaseMenu(SmpMarket plugin, Player viewer, Stall stall, Showcase showcase) {
        super(plugin, viewer, 5, plugin.texts().get("gui.showcase.title"));
        this.stall = stall;
        this.showcase = showcase;
    }

    @Override
    protected void build() {
        if (!stall.isOwner(viewer.getUniqueId()) || !stall.showcases.contains(showcase)) {
            viewer.closeInventory();
            return;
        }
        Showcase c = showcase;
        set(13, CatalogMenu.display(this, c, "gui.showcase.info-lore", ph("price", money(c.price)), ph("stock", c.stock),
                ph("max", s().maxStock), ph("sold", c.sold), ph("revenue", money(c.revenue))));

        int inInv = Shops.count(viewer.getInventory(), c.template);
        button(29, item(Material.CHEST, "gui.showcase.add", ph("count", inInv)), click -> {
            plugin.shops().addStock(viewer, stall, c);
            redraw();
        });
        button(30, item(Material.HOPPER, "gui.showcase.take-stack"), click -> {
            plugin.shops().withdraw(viewer, stall, c, c.template.getMaxStackSize());
            redraw();
        });
        button(31, item(Material.DROPPER, "gui.showcase.take-all"), click -> {
            plugin.shops().withdraw(viewer, stall, c, c.stock);
            redraw();
        });
        button(32, item(Material.GOLD_INGOT, "gui.showcase.price", ph("price", money(c.price))),
                click -> plugin.input().ask(viewer, "input.price", text -> {
                    double v = MarketCommand.parseMoney(text);
                    if (v <= 0) t().send(viewer, "error.bad-amount", ph("value", text));
                    else plugin.shops().setPrice(viewer, stall, c, v);
                    open();
                }));
        button(33, item(Material.BARRIER, "gui.showcase.remove"), click -> {
            if (!click.isShiftClick()) return;
            plugin.shops().remove(viewer, stall, c);
            new StallMenu(plugin, viewer, stall).open();
        });
        back(36, () -> new StallMenu(plugin, viewer, stall).open());
        close(44);
    }
}
