package fr.smpmarket.gui;

import fr.smpmarket.Auction;
import fr.smpmarket.Items;
import fr.smpmarket.MarketCommand;
import fr.smpmarket.Settings;
import fr.smpmarket.Shops;
import fr.smpmarket.Showcase;
import fr.smpmarket.SmpMarket;
import fr.smpmarket.Stall;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static fr.smpmarket.Texts.pc;
import static fr.smpmarket.Texts.ph;

/** Toutes les boutiques, puis les stands libres. */
public final class DirectoryMenu extends Menu {

    private int page;

    public DirectoryMenu(SmpMarket plugin, Player viewer, int page) {
        super(plugin, viewer, 6, plugin.texts().get("gui.directory.title", ph("count", plugin.store().stalls().size())));
        this.page = page;
    }

    @Override
    protected void build() {
        List<Stall> list = new ArrayList<>(plugin.store().stalls());
        list.sort(Comparator.comparing((Stall s) -> !s.isRented()).thenComparing(s -> -s.itemsForSale())
                .thenComparing(s -> s.id));
        int pages = pageCount(list.size(), GRID.length);
        page = Math.min(page, pages - 1);
        if (list.isEmpty()) set(22, item(Material.GRAY_DYE, "gui.directory.empty"));
        long now = System.currentTimeMillis();
        for (int i = 0; i < GRID.length; i++) {
            int idx = page * GRID.length + i;
            if (idx >= list.size()) break;
            Stall st = list.get(idx);
            TagResolver tags = plugin.auctions().stallTags(st);
            if (st.isRented()) {
                List<Component> top = new ArrayList<>();
                for (Showcase c : st.showcases) {
                    if (c.stock <= 0) continue;
                    top.add(t().get("gui.directory.top-line", pc("item", Shops.itemName(c.template)), ph("price", money(c.price))));
                    if (top.size() >= 3) break;
                }
                TagResolver[] r = {tags, ph("owner", st.ownerName), ph("count", st.itemsForSale())};
                button(GRID[i], Items.head(Bukkit.getOfflinePlayer(st.owner), t().get("gui.directory.shop.name", r),
                        t().listWith("gui.directory.shop.lore", "top", top, r)), c -> {
                    if (c.isRightClick()) new CatalogMenu(plugin, viewer, st).open();
                    else {
                        viewer.closeInventory();
                        MarketCommand.teleport(plugin, viewer, st);
                    }
                });
            } else {
                Auction a = st.auction;
                TagResolver[] r = {tags, ph("bid", money(a != null && a.hasBid() ? a.bid : st.startPrice)),
                        ph("time", a != null && a.endsAt > 0 ? Settings.time(a.endsAt - now) : "-")};
                button(GRID[i], item(Material.OAK_HANGING_SIGN, "gui.directory.free", r),
                        c -> new AuctionMenu(plugin, viewer, st, () -> new DirectoryMenu(plugin, viewer, page).open()).open());
            }
        }
        pages(page, pages, p -> new DirectoryMenu(plugin, viewer, p).open());
        back(45, () -> new MainMenu(plugin, viewer).open());
        close(49);
    }

}
