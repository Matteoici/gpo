package fr.smpmarket.gui;

import fr.smpmarket.Auction;
import fr.smpmarket.Items;
import fr.smpmarket.MarketCommand;
import fr.smpmarket.Settings;
import fr.smpmarket.Showcase;
import fr.smpmarket.SmpMarket;
import fr.smpmarket.Stall;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import static fr.smpmarket.Texts.pc;
import static fr.smpmarket.Texts.ph;

/** Gestion de sa boutique (pour le locataire). */
public final class StallMenu extends Menu {

    private final Stall stall;

    public StallMenu(SmpMarket plugin, Player viewer, Stall stall) {
        super(plugin, viewer, 6, plugin.texts().get("gui.stall.title"));
        this.stall = stall;
    }

    @Override
    protected void build() {
        if (!stall.isOwner(viewer.getUniqueId())) {
            viewer.closeInventory();
            return;
        }
        long now = System.currentTimeMillis();
        TagResolver tags = plugin.auctions().stallTags(stall);
        set(4, Items.glow(item(Material.EMERALD, "gui.stall.header", tags, ph("time", Settings.time(stall.leaseEnd - now)),
                ph("sold", stall.totalSold), ph("revenue", money(stall.totalRevenue)),
                ph("used", stall.showcases.size()), ph("max", stall.maxShowcases))));

        for (int i = 0; i < Math.min(stall.showcases.size(), 21); i++) {
            Showcase c = stall.showcases.get(i);
            button(GRID[i], CatalogMenu.display(this, c, "gui.stall.showcase-lore", ph("price", money(c.price)),
                    ph("stock", c.stock), ph("sold", c.sold), ph("revenue", money(c.revenue))),
                    click -> new ShowcaseMenu(plugin, viewer, stall, c).open());
        }

        button(46, item(Material.NAME_TAG, "gui.stall.rename", tags), c -> plugin.input().ask(viewer, "input.name", name -> {
            MarketCommand.rename(plugin, viewer, stall, name);
            open();
        }));
        button(47, item(Material.PLAYER_HEAD, "gui.stall.trusted", ph("count", stall.trusted.size())),
                c -> new TrustedMenu(plugin, viewer, stall).open());
        button(48, item(stall.autoRenew ? Material.REPEATER : Material.COMPARATOR, "gui.stall.autorenew",
                pc("state", t().get(stall.autoRenew ? "gui.stall.on" : "gui.stall.off")), ph("price", money(stall.startPrice))), c -> {
            stall.autoRenew = !stall.autoRenew;
            plugin.store().markDirty();
            t().send(viewer, stall.autoRenew ? "stall.auto-renew-on" : "stall.auto-renew-off");
            redraw();
        });
        button(49, item(Material.ENDER_PEARL, "gui.stall.tp"), c -> {
            viewer.closeInventory();
            MarketCommand.teleport(plugin, viewer, stall);
        });
        set(50, item(Material.BOOK, "gui.stall.howto"));
        button(52, Items.glow(item(Material.PAINTING, "gui.stall.style")), c -> new StyleMenu(plugin, viewer, stall).open());
        Auction a = stall.auction;
        if (a != null && a.renewal) {
            button(51, Items.glow(item(Material.BELL, "gui.stall.renewal",
                    ph("bid", money(a.hasBid() ? a.bid : stall.startPrice)),
                    pc("bidder", a.hasBid() ? t().get("gui.auctions.by", ph("player", a.bidderName)) : t().get("gui.auctions.waiting")),
                    ph("time", Settings.time(a.endsAt - now)))),
                    c -> new AuctionMenu(plugin, viewer, stall, () -> new StallMenu(plugin, viewer, stall).open()).open());
        }
        back(45, () -> new MainMenu(plugin, viewer).open());
        close(53);
    }
}
