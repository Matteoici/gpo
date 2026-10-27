package fr.smpmarket.gui;

import fr.smpmarket.Items;
import fr.smpmarket.MarketCommand;
import fr.smpmarket.Showcase;
import fr.smpmarket.SmpMarket;
import fr.smpmarket.Stall;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

import static fr.smpmarket.Texts.ph;

/** Catalogue d'une boutique : tous ses articles avec prix et stock. */
public final class CatalogMenu extends Menu {

    private final Stall stall;

    public CatalogMenu(SmpMarket plugin, Player viewer, Stall stall) {
        super(plugin, viewer, 6, plugin.texts().get("gui.catalog.title", ph("shop", stall.displayName())));
        this.stall = stall;
    }

    /** L'objet vendu, avec prix et stock ajoutés sous sa description. */
    static ItemStack display(Menu m, Showcase c, String loreKey, TagResolver... r) {
        ItemStack it = c.template.clone();
        it.setAmount(Math.max(1, Math.min(c.stock, it.getMaxStackSize())));
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.addAll(m.t().list(loreKey, r));
            meta.lore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    /** Le joueur est-il assez près pour acheter dans ce stand ? */
    static boolean near(SmpMarket plugin, Player p, Stall st) {
        if (st.isPacked()) return true; // marché rangé : on achète à distance
        Location e = st.entrance();
        if (e == null || e.getWorld() != p.getWorld()) return false;
        double d = plugin.settings().buyDistance;
        if (st.region.contains(p.getLocation())) return true;
        double cx = (st.region.minX() + st.region.maxX() + 1) / 2.0;
        double cz = (st.region.minZ() + st.region.maxZ() + 1) / 2.0;
        double dx = p.getLocation().getX() - cx, dz = p.getLocation().getZ() - cz;
        double r = Math.max(st.region.sizeX(), st.region.sizeZ()) / 2.0 + d;
        return dx * dx + dz * dz <= r * r || p.getLocation().distanceSquared(e) <= d * d;
    }

    @Override
    protected void build() {
        if (!stall.isRented()) {
            viewer.closeInventory();
            return;
        }
        set(4, Items.head(Bukkit.getOfflinePlayer(stall.owner), t().get("gui.catalog.header.name", ph("shop", stall.displayName())),
                t().list("gui.catalog.header.lore", ph("owner", stall.ownerName), ph("sold", stall.totalSold))));
        List<Showcase> list = stall.showcases;
        if (list.isEmpty()) set(22, item(Material.GRAY_DYE, "gui.catalog.empty"));
        for (int i = 0; i < Math.min(list.size(), GRID.length); i++) {
            Showcase c = list.get(i);
            TagResolver[] r = {ph("price", money(c.price)), ph("stock", c.stock)};
            button(GRID[i], display(this, c, c.stock > 0 ? "gui.catalog.item-lore" : "gui.catalog.item-lore-empty", r), click -> {
                if (c.stock <= 0) return;
                if (!near(plugin, viewer, stall)) {
                    t().send(viewer, "buy.too-far");
                    return;
                }
                new BuyMenu(plugin, viewer, stall, c, () -> new CatalogMenu(plugin, viewer, stall).open()).open();
            });
        }
        if (stall.isPacked()) set(49, item(Material.BARREL, "claim.gui.packed-info"));
        else button(49, item(Material.ENDER_PEARL, "gui.catalog.tp"), c -> {
            viewer.closeInventory();
            MarketCommand.teleport(plugin, viewer, stall);
        });
        back(45, () -> new DirectoryMenu(plugin, viewer, 0).open());
        close(53);
    }
}
