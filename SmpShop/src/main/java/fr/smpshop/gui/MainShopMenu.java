package fr.smpshop.gui;

import fr.smpshop.Items;
import fr.smpshop.Shop;
import fr.smpshop.SmpShop;
import fr.smpshop.Texts;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

import static fr.smpshop.Texts.ph;

/** /shop : les catégories. */
public final class MainShopMenu extends Menu {

    public MainShopMenu(SmpShop plugin, Player viewer) {
        super(plugin, viewer, plugin.shop().mainRows, Texts.parse(plugin.shop().mainTitle));
    }

    @Override
    protected void build() {
        for (Shop.Category c : plugin.shop().categories()) {
            if (c.slot() < 0 || c.slot() >= size()) continue;
            List<Component> lore = new ArrayList<>();
            for (String s : c.lore()) lore.add(Texts.parse(s, ph("count", c.entries().size())));
            if (c.currency() == Shop.Currency.TOKENS) {
                lore.add(Component.empty());
                lore.add(t().get("shop.your-tokens", ph("tokens", s().tokens(plugin.tokens().get(viewer.getUniqueId())))));
            }
            lore.add(Component.empty());
            lore.add(t().get("shop.open-category"));
            button(c.slot(), Items.of(c.icon(), Texts.parse(c.name()), lore), click -> new CategoryMenu(plugin, viewer, c).open());
        }
        int last = size() - 1;
        if (size() >= 18) {
            button(last - 4, item(Material.GOLD_INGOT, "shop.sell-button", ph("balance", s().money(plugin.balance(viewer)))),
                    click -> new SellMenu(plugin, viewer).open());
        }
    }
}
