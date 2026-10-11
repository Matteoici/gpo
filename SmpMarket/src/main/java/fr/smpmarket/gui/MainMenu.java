package fr.smpmarket.gui;

import fr.smpmarket.Items;
import fr.smpmarket.Settings;
import fr.smpmarket.SmpMarket;
import fr.smpmarket.Stall;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

import static fr.smpmarket.Texts.ph;

/** /marche : menu principal. */
public final class MainMenu extends Menu {

    public MainMenu(SmpMarket plugin, Player viewer) {
        super(plugin, viewer, 5, plugin.texts().get("gui.main.title"));
    }

    @Override
    protected void build() {
        int rented = 0, free = 0, auctions = 0;
        for (Stall st : plugin.store().stalls()) {
            if (st.isRented()) rented++;
            else free++;
            if (st.auction != null) auctions++;
        }
        set(4, Items.head(viewer, t().get("gui.main.header.name"), t().list("gui.main.header.lore", ph("rented", rented),
                ph("free", free), ph("balance", money(plugin.economy().balance(viewer))))));

        button(20, item(Material.CHEST, "gui.main.directory"), c -> new DirectoryMenu(plugin, viewer, 0).open());
        button(21, item(Material.SPYGLASS, "gui.main.search"), c -> plugin.input().ask(viewer, "input.search",
                q -> new SearchMenu(plugin, viewer, q, 0).open()));
        button(22, item(Material.BELL, "gui.main.auctions", ph("count", auctions)), c -> new AuctionsMenu(plugin, viewer, 0).open());

        List<Stall> mine = plugin.store().ownedBy(viewer.getUniqueId());
        if (mine.isEmpty()) {
            set(23, item(Material.GRAY_DYE, "gui.main.nostall"));
        } else {
            Stall st = mine.get(0);
            button(23, Items.glow(item(Material.EMERALD, "gui.main.mystall", ph("shop", st.displayName()),
                    ph("time", Settings.time(st.leaseEnd - System.currentTimeMillis())))), c -> new StallMenu(plugin, viewer, st).open());
        }
        int parcels = plugin.store().parcels(viewer.getUniqueId()).size();
        var p = item(Material.BARREL, "gui.main.parcels", ph("count", parcels));
        button(24, parcels > 0 ? Items.glow(p) : p, c -> new MailboxMenu(plugin, viewer).open());

        set(40, item(Material.BOOK, "gui.main.help", ph("days", Math.round(s().leaseMs / 86_400_000.0)),
                ph("tax", (int) s().taxPercent)));
        close(44);
    }
}
