package fr.smpmarket;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.UUID;

import static fr.smpmarket.Texts.pc;
import static fr.smpmarket.Texts.ph;

/** Vitrines : création, stock, achats. */
public final class Shops {

    private final SmpMarket plugin;

    Shops(SmpMarket plugin) {
        this.plugin = plugin;
    }

    private Settings s() {
        return plugin.settings();
    }

    private Texts t() {
        return plugin.texts();
    }

    public Stall stallAt(Block b) {
        if (b == null) return null;
        for (Stall st : plugin.store().stalls()) if (st.region.contains(b)) return st;
        return null;
    }

    public Stall stallAt(org.bukkit.Location l) {
        if (l == null) return null;
        for (Stall st : plugin.store().stalls()) if (st.region.contains(l)) return st;
        return null;
    }

    /** Nom de l'objet tel qu'affiché en jeu. */
    public static Component itemName(ItemStack it) {
        return it.effectiveName();
    }

    // ================================================================= création

    /** /marche vitrine <prix> : l'objet en main est exposé sur le bloc visé. */
    public void create(Player p, Block block, double price) {
        Stall st = stallAt(block);
        if (st == null) {
            t().send(p, "showcase.outside-stall");
            return;
        }
        if (!st.isOwner(p.getUniqueId())) {
            t().send(p, "showcase.not-your-stall");
            return;
        }
        if (st.showcaseAt(block.getX(), block.getY(), block.getZ()) != null) {
            t().send(p, "showcase.already");
            return;
        }
        if (st.physicalCount() >= st.showcaseLimit()) {
            t().send(p, "showcase.limit", ph("max", st.showcaseLimit()));
            return;
        }
        Block above = block.getRelative(0, 1, 0);
        if (!(above.isEmpty() || above.isPassable()) || !st.region.contains(above)) {
            t().send(p, "showcase.no-space");
            return;
        }
        if (price < s().minPrice || price > s().maxPrice) {
            t().send(p, "showcase.bad-price", ph("min", s().money(s().minPrice)), ph("max", s().money(s().maxPrice)));
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            t().send(p, "showcase.empty-hand");
            return;
        }
        ItemStack template = hand.clone();
        template.setAmount(1);
        int stock = Math.min(hand.getAmount(), s().maxStock);
        hand.setAmount(hand.getAmount() - stock);
        p.getInventory().setItemInMainHand(hand.getAmount() <= 0 ? null : hand);

        Showcase c = new Showcase(UUID.randomUUID(), template, price, stock, block.getX(), block.getY(), block.getZ());
        st.showcases.add(c);
        plugin.store().markDirty();
        plugin.visuals().ensure(st);
        t().send(p, "showcase.created", pc("item", itemName(template)), ph("price", s().money(price)), ph("stock", stock));
        Fx.sound(p, "block.amethyst_block.chime", 1.2f);
    }

    /** Met l'objet en main en vente au catalogue du stand (sans vitrine dans le monde). */
    public void createVirtual(Player p, Stall st, double price) {
        if (!st.isOwner(p.getUniqueId())) {
            t().send(p, "showcase.not-your-stall");
            return;
        }
        if (st.virtualCount() >= st.catalogSlots) {
            t().send(p, "catalog.no-slot");
            return;
        }
        if (price < s().minPrice || price > s().maxPrice) {
            t().send(p, "showcase.bad-price", ph("min", s().money(s().minPrice)), ph("max", s().money(s().maxPrice)));
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            t().send(p, "showcase.empty-hand");
            return;
        }
        ItemStack template = hand.clone();
        template.setAmount(1);
        int stock = Math.min(hand.getAmount(), s().maxStock);
        hand.setAmount(hand.getAmount() - stock);
        p.getInventory().setItemInMainHand(hand.getAmount() <= 0 ? null : hand);
        Showcase c = Showcase.virtual(UUID.randomUUID(), template, price, stock);
        st.showcases.add(c);
        plugin.store().markDirty();
        plugin.visuals().refreshSign(st);
        t().send(p, "catalog.created", pc("item", itemName(template)), ph("price", s().money(price)), ph("stock", stock));
        Fx.sound(p, "block.amethyst_block.chime", 1.2f);
    }

    // ================================================================= achats d'emplacements

    public double nextShowcasePrice(Stall st) {
        return Math.round(s().showcaseBasePrice * Math.pow(s().showcasePriceGrowth, st.extraShowcases));
    }

    public double nextCatalogPrice(Stall st) {
        return Math.round(s().catalogBasePrice * Math.pow(s().catalogPriceGrowth, st.catalogSlots));
    }

    public void buyShowcase(Player p, Stall st) {
        if (st.extraShowcases >= s().maxExtraShowcases) {
            t().send(p, "upgrade.showcase-max", ph("max", st.showcaseLimit()));
            return;
        }
        double price = nextShowcasePrice(st);
        if (!plugin.economy().has(p, price) || !plugin.economy().take(p, price)) {
            t().send(p, "upgrade.no-money", ph("price", s().money(price)));
            return;
        }
        st.extraShowcases++;
        plugin.store().markDirty();
        t().send(p, "upgrade.showcase-bought", ph("max", st.showcaseLimit()), ph("price", s().money(price)));
        Fx.sound(p, "entity.player.levelup", 1.4f);
    }

    public void buyCatalogSlot(Player p, Stall st) {
        if (st.catalogSlots >= s().maxCatalogSlots) {
            t().send(p, "upgrade.catalog-max", ph("max", st.catalogSlots));
            return;
        }
        double price = nextCatalogPrice(st);
        if (!plugin.economy().has(p, price) || !plugin.economy().take(p, price)) {
            t().send(p, "upgrade.no-money", ph("price", s().money(price)));
            return;
        }
        st.catalogSlots++;
        plugin.store().markDirty();
        t().send(p, "upgrade.catalog-bought", ph("max", st.catalogSlots), ph("price", s().money(price)));
        Fx.sound(p, "entity.player.levelup", 1.4f);
    }

    // ================================================================= stock

    /** Place sur combien d'exemplaires de cet objet reste-t-il dans l'inventaire ? */
    public static int space(PlayerInventory inv, ItemStack template) {
        int max = template.getMaxStackSize();
        int free = 0;
        for (ItemStack it : inv.getStorageContents()) {
            if (it == null || it.getType().isAir()) free += max;
            else if (it.isSimilar(template)) free += Math.max(0, max - it.getAmount());
        }
        return free;
    }

    public static int count(PlayerInventory inv, ItemStack template) {
        int n = 0;
        for (ItemStack it : inv.getStorageContents()) if (it != null && it.isSimilar(template)) n += it.getAmount();
        return n;
    }

    /** Retire jusqu'à "amount" objets identiques de l'inventaire. Renvoie le nombre retiré. */
    public static int take(PlayerInventory inv, ItemStack template, int amount) {
        int taken = 0;
        ItemStack[] content = inv.getStorageContents();
        for (int i = 0; i < content.length && taken < amount; i++) {
            ItemStack it = content[i];
            if (it == null || !it.isSimilar(template)) continue;
            int n = Math.min(it.getAmount(), amount - taken);
            it.setAmount(it.getAmount() - n);
            content[i] = it.getAmount() <= 0 ? null : it;
            taken += n;
        }
        inv.setStorageContents(content);
        return taken;
    }

    /** Donne des objets (découpés en piles). Le reste tombe aux pieds du joueur. */
    public static void give(Player p, ItemStack template, int amount) {
        int max = Math.max(1, template.getMaxStackSize());
        while (amount > 0) {
            ItemStack it = template.clone();
            int n = Math.min(max, amount);
            it.setAmount(n);
            for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
            amount -= n;
        }
    }

    public int addStock(Player p, Stall st, Showcase c) {
        int room = s().maxStock - c.stock;
        if (room <= 0) {
            t().send(p, "showcase.stock-full", ph("max", s().maxStock));
            return 0;
        }
        int n = take(p.getInventory(), c.template, room);
        if (n == 0) {
            t().send(p, "showcase.nothing-to-add", pc("item", itemName(c.template)));
            return 0;
        }
        c.stock += n;
        plugin.store().markDirty();
        plugin.visuals().updateShowcase(st, c);
        t().send(p, "showcase.stock-added", ph("count", n), pc("item", itemName(c.template)), ph("stock", c.stock));
        Fx.sound(p, "entity.item.pickup", 1f);
        return n;
    }

    public void withdraw(Player p, Stall st, Showcase c, int amount) {
        int n = Math.min(amount, Math.min(c.stock, space(p.getInventory(), c.template)));
        if (n <= 0) {
            t().send(p, c.stock <= 0 ? "showcase.empty" : "buy.no-space");
            return;
        }
        c.stock -= n;
        give(p, c.template, n);
        plugin.store().markDirty();
        plugin.visuals().updateShowcase(st, c);
        t().send(p, "showcase.withdrawn", ph("count", n), pc("item", itemName(c.template)));
    }

    public void setPrice(Player p, Stall st, Showcase c, double price) {
        if (price < s().minPrice || price > s().maxPrice) {
            t().send(p, "showcase.bad-price", ph("min", s().money(s().minPrice)), ph("max", s().money(s().maxPrice)));
            return;
        }
        c.price = price;
        plugin.store().markDirty();
        plugin.visuals().updateShowcase(st, c);
        t().send(p, "showcase.price-set", pc("item", itemName(c.template)), ph("price", s().money(price)));
    }

    public void remove(Player p, Stall st, Showcase c) {
        int give = Math.min(c.stock, space(p.getInventory(), c.template));
        if (give > 0) give(p, c.template, give);
        int rest = c.stock - give;
        if (rest > 0) {
            plugin.store().addParcel(p.getUniqueId(), c.template, rest);
            t().send(p, "parcel.received", ph("count", rest));
        }
        st.showcases.remove(c);
        plugin.visuals().removeShowcase(c);
        plugin.store().markDirty();
        t().send(p, "showcase.removed", pc("item", itemName(c.template)));
    }

    // ================================================================= achat

    public boolean buy(Player buyer, Stall st, Showcase c, int qty) {
        if (!st.isRented() || !st.showcases.contains(c)) return false;
        if (st.isOwner(buyer.getUniqueId())) {
            t().send(buyer, "buy.own");
            return false;
        }
        if (qty <= 0 || c.stock <= 0) {
            t().send(buyer, "buy.out-of-stock");
            return false;
        }
        if (qty > c.stock) qty = c.stock;
        if (Shops.space(buyer.getInventory(), c.template) < qty) {
            t().send(buyer, "buy.no-space");
            return false;
        }
        double total = c.price * qty;
        Economy eco = plugin.economy();
        if (!eco.has(buyer, total)) {
            t().send(buyer, "buy.no-money", ph("price", s().money(total)), ph("balance", s().money(eco.balance(buyer))));
            Fx.sound(buyer, "entity.villager.no", 1f);
            return false;
        }
        if (!eco.take(buyer, total)) return false;
        double taxRate = s().taxFor(total);
        double net = Math.floor(total * (1 - taxRate / 100.0));
        double tax = total - net;
        OfflinePlayer seller = Bukkit.getOfflinePlayer(st.owner);
        eco.give(seller, net);

        c.stock -= qty;
        c.sold += qty;
        c.revenue += net;
        st.totalSold += qty;
        st.totalRevenue += net;
        give(buyer, c.template, qty);
        plugin.store().markDirty();
        plugin.visuals().updateShowcase(st, c);
        if (c.stock <= 0) plugin.visuals().refreshSign(st);

        Component item = itemName(c.template);
        t().send(buyer, "buy.done", ph("count", qty), pc("item", item), ph("price", s().money(total)), ph("shop", st.displayName()));
        Fx.sound(buyer, "entity.experience_orb.pickup", 1.4f);
        Player online = seller.getPlayer();
        if (online != null) {
            t().send(online, "buy.seller", ph("player", buyer.getName()), ph("count", qty), pc("item", item),
                    ph("price", s().money(net)), ph("tax", s().money(tax)), ph("rate", (int) Math.round(taxRate)));
            Fx.sound(online, "block.note_block.chime", 1.6f);
            if (c.stock <= 0) t().send(online, "buy.seller-empty", pc("item", item));
        } else {
            plugin.store().offlineSale(st.owner, qty, net);
        }
        return true;
    }
}
