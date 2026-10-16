package fr.smpshop.gui;

import fr.smpshop.Items;
import fr.smpshop.Shop;
import fr.smpshop.SmpShop;
import fr.smpshop.Texts;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

import static fr.smpshop.Texts.ph;

/** Une catégorie de la boutique. Clic gauche = choisir la quantité, clic droit = acheter 1, shift = un stack. */
public final class CategoryMenu extends Menu {

    private final Shop.Category cat;

    public CategoryMenu(SmpShop plugin, Player viewer, Shop.Category cat) {
        super(plugin, viewer, cat.rows(), Texts.parse(cat.title()));
        this.cat = cat;
    }

    @Override
    protected void build() {
        Shop shop = plugin.shop();
        for (Shop.Entry e : cat.entries()) {
            if (e.slot() < 0 || e.slot() >= size() - 9) continue;
            List<Component> lore = new ArrayList<>();
            for (String s : e.lore()) lore.add(Texts.parse(s));
            if (!e.lore().isEmpty()) lore.add(Component.empty());
            int stack = stackPacks(e);
            lore.addAll(t().list("shop.entry-lore", ph("price", shop.priceText(cat, shop.cost(viewer, cat, e, 1))),
                    ph("amount", e.isCommand() ? 1 : e.item().getAmount()),
                    ph("stack", shop.priceText(cat, shop.cost(viewer, cat, e, stack))), ph("stack-count", e.isCommand() ? stack : stack * e.item().getAmount())));
            double off = cat.currency() == Shop.Currency.MONEY ? shop.discount(viewer) : 0;
            if (off > 0) lore.add(Texts.parse("<light_purple>✦ Réduction de grade : <white>-" + Math.round(off * 100) + "%"));
            ItemStack icon = e.item().clone();
            Component name = e.name() != null ? Texts.parse(e.name())
                    : t().get("shop.entry-name", Texts.pc("item", Component.translatable(e.item().translationKey())));
            button(e.slot(), Items.decorate(icon, name, lore), click -> {
                if (click == ClickType.RIGHT) {
                    shop.buy(viewer, cat, e, 1);
                    redraw();
                } else if (click.isShiftClick()) {
                    shop.buy(viewer, cat, e, stack);
                    redraw();
                } else {
                    new QuantityMenu(plugin, viewer, cat, e, 1).open();
                }
            });
        }
        int last = size() - 1;
        back(last - 8, () -> new MainShopMenu(plugin, viewer).open());
        String bal = cat.currency() == Shop.Currency.TOKENS ? s().tokens(plugin.tokens().get(viewer.getUniqueId()))
                : s().money(plugin.balance(viewer));
        set(last - 4, item(cat.currency() == Shop.Currency.TOKENS ? Material.AMETHYST_SHARD : Material.GOLD_NUGGET,
                "shop.balance", ph("balance", bal)));
        close(last);
    }

    /** Nombre de "packs" pour faire un stack (64 objets). */
    static int stackPacks(Shop.Entry e) {
        if (e.isCommand()) return Math.min(e.maxQuantity(), 16);
        return Math.max(1, e.item().getMaxStackSize() / e.item().getAmount());
    }
}
