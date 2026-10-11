package fr.smpmarket.gui;

import fr.smpmarket.Auction;
import fr.smpmarket.Items;
import fr.smpmarket.Settings;
import fr.smpmarket.SmpMarket;
import fr.smpmarket.Stall;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static fr.smpmarket.Texts.pc;
import static fr.smpmarket.Texts.ph;

/** Toutes les enchères ouvertes, celles qui se terminent le plus tôt en premier. */
public final class AuctionsMenu extends Menu {

    private int page;

    public AuctionsMenu(SmpMarket plugin, Player viewer, int page) {
        super(plugin, viewer, 6, plugin.texts().get("gui.auctions.title", ph("count", count(plugin))));
        this.page = page;
    }

    private static int count(SmpMarket plugin) {
        int n = 0;
        for (Stall s : plugin.store().stalls()) if (s.auction != null) n++;
        return n;
    }

    @Override
    protected void build() {
        List<Stall> list = new ArrayList<>();
        for (Stall s : plugin.store().stalls()) if (s.auction != null) list.add(s);
        list.sort(Comparator.comparingLong((Stall s) -> s.auction.endsAt == 0 ? Long.MAX_VALUE : s.auction.endsAt)
                .thenComparing(s -> s.id));
        int pages = pageCount(list.size(), GRID.length);
        page = Math.min(page, pages - 1);
        if (list.isEmpty()) set(22, item(Material.GRAY_DYE, "gui.auctions.empty"));
        long now = System.currentTimeMillis();
        for (int i = 0; i < GRID.length; i++) {
            int idx = page * GRID.length + i;
            if (idx >= list.size()) break;
            Stall st = list.get(idx);
            Auction a = st.auction;
            boolean mine = viewer.getUniqueId().equals(a.bidder);
            TagResolver[] r = {plugin.auctions().stallTags(st),
                    pc("kind", a.renewal ? t().get("gui.auctions.kind-renewal", ph("owner", st.ownerName == null ? "-" : st.ownerName))
                            : t().get("gui.auctions.kind-free")),
                    ph("bid", money(a.hasBid() ? a.bid : st.startPrice)),
                    pc("bidder", a.hasBid() ? t().get("gui.auctions.by", ph("player", a.bidderName)) : t().get("gui.auctions.waiting")),
                    ph("time", a.endsAt > 0 ? Settings.time(a.endsAt - now) : "-")};
            ItemStack it = item(a.renewal ? Material.CLOCK : Material.BELL, "gui.auctions.item", r);
            button(GRID[i], mine ? Items.glow(it) : it,
                    c -> new AuctionMenu(plugin, viewer, st, () -> new AuctionsMenu(plugin, viewer, page).open()).open());
        }
        pages(page, pages, p -> new AuctionsMenu(plugin, viewer, p).open());
        back(45, () -> new MainMenu(plugin, viewer).open());
        close(49);
    }
}
